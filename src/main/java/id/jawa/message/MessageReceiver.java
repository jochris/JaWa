// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import com.google.protobuf.InvalidProtocolBufferException;
import id.jawa.binary.BinaryNode;
import id.jawa.proto.Wa;
import id.jawa.signal.JaWaProtocolStore;
import id.jawa.signal.SessionBootstrap;
import id.jawa.util.Jid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.groups.GroupCipher;
import org.whispersystems.libsignal.groups.GroupSessionBuilder;
import org.whispersystems.libsignal.groups.SenderKeyName;
import org.whispersystems.libsignal.groups.state.SenderKeyStore;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SenderKeyDistributionMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;

/**
 * Decrypt an incoming {@code <message>} stanza: pick the {@code <enc>} child, run
 * libsignal's {@link SessionCipher} (DMs) or {@link GroupCipher} (groups), strip the
 * random pad, parse the {@link Wa.Message} protobuf, unwrap a {@code DeviceSentMessage}
 * envelope when present, and process inline {@code senderKeyDistributionMessage}
 * payloads so subsequent group {@code skmsg} traffic decrypts.
 *
 * <p>Mirrors {@code decryptMessageNode} in Baileys ({@code Utils/decode-wa-message.ts})
 * and {@code decryptDM} + {@code decryptGroupMsg} in whatsmeow ({@code message.go}).
 */
public final class MessageReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(MessageReceiver.class);

    private MessageReceiver() {}

    /**
     * Decode result.
     *
     * @param senderJid   sender's device-specific JID (from {@code participant} attr
     *                    when present, else {@code from}). For groups this is the
     *                    individual sender device; for DMs it's the contact's device.
     * @param groupJid    group JID ({@code from} attr) when {@code <message>} is a group
     *                    message; {@code null} for 1-on-1 DMs.
     * @param msgId       value of the {@code <message id=>} attr.
     * @param encType     the {@code <enc type=>} value: "pkmsg", "msg", or "skmsg".
     * @param message     the decrypted, DSM-unwrapped {@link Wa.Message}, or {@code null}
     *                    if no usable {@code <enc>} child was found.
     * @param text        the {@code conversation} field if the message is plain text,
     *                    else {@code null} (e.g. media, reactions, system messages).
     */
    public record Decoded(String senderJid, String groupJid, String msgId, String encType,
                          Wa.Message message, String text, InteractiveResponse interactive) {}

    /**
     * Parsed interactive response — what the user tapped on a {@code buttonsMessage},
     * {@code listMessage}, or {@code interactiveMessage} we previously sent.
     *
     * <p>{@link #kind} disambiguates the source:
     * <ul>
     *   <li>{@code "buttons"} — {@link #selectedId} = the tapped {@code buttonId}
     *       (the {@code QuickReplyButton#buttonId} we set); {@link #displayText} = the
     *       button's visible text echo.</li>
     *   <li>{@code "list"} — {@link #selectedId} = the chosen {@code rowId}.</li>
     *   <li>{@code "native_flow"} — {@link #selectedId} = the native-flow button name
     *       (e.g. {@code "quick_reply"}, {@code "single_select"}); {@link #paramsJson}
     *       carries the response payload (parse for the actual user-selected id).</li>
     * </ul>
     */
    public record InteractiveResponse(String kind, String selectedId,
                                      String paramsJson, String displayText) {}

    /**
     * Decode {@code messageStanza}. {@code signalStore} holds DM session state;
     * {@code senderKeyStore} holds group sender-key state. Both are required because
     * group messages can ride in as either {@code skmsg} (group cipher) or as a
     * {@code pkmsg}/{@code msg} carrying a {@code SenderKeyDistributionMessage} that
     * establishes the group sender-key for the next round of {@code skmsg}.
     *
     * @throws DecryptException when the {@code <enc>} payload exists but cannot be
     *                          decrypted. The caller should still {@code <ack>} and
     *                          may send a {@code <receipt type=retry>}.
     */
    public static Decoded decode(BinaryNode messageStanza,
                                 JaWaProtocolStore signalStore,
                                 SenderKeyStore senderKeyStore)
            throws DecryptException {
        String msgId = messageStanza.attr("id");
        String from = messageStanza.attr("from");
        String participant = messageStanza.attr("participant");
        String senderJidStr = participant != null ? participant : from;
        if (senderJidStr == null) {
            throw new DecryptException("message has no from/participant attr");
        }

        // For group messages the server addresses the stanza to the group JID via `from`
        // and identifies the sender via `participant`. For DMs there's no participant.
        boolean isGroup = participant != null && from != null && !from.equals(participant);
        String groupJid = isGroup ? from : null;

        BinaryNode enc = findEnc(messageStanza);
        if (enc == null) {
            return new Decoded(senderJidStr, groupJid, msgId, null, null, null, null);
        }

        String encType = enc.attr("type");
        byte[] ciphertext = enc.bytesContent();
        if (ciphertext == null || ciphertext.length == 0) {
            throw new DecryptException("empty enc payload (type=" + encType + ")");
        }

        Jid senderJid = Jid.parse(senderJidStr);
        if (senderJid == null) {
            throw new DecryptException("malformed sender JID: " + senderJidStr);
        }
        SignalProtocolAddress senderAddr = SessionBootstrap.addressFor(senderJid);

        byte[] padded;
        try {
            padded = switch (encType) {
                case "pkmsg" -> new SessionCipher(signalStore, senderAddr)
                    .decrypt(new PreKeySignalMessage(ciphertext));
                case "msg"   -> new SessionCipher(signalStore, senderAddr)
                    .decrypt(new SignalMessage(ciphertext));
                case "skmsg" -> {
                    if (groupJid == null) {
                        throw new DecryptException("skmsg without group JID");
                    }
                    SenderKeyName name = new SenderKeyName(groupJid, senderAddr);
                    yield new GroupCipher(senderKeyStore, name).decrypt(ciphertext);
                }
                default      -> throw new DecryptException("unsupported enc type: " + encType);
            };
        } catch (InvalidVersionException | InvalidMessageException | InvalidKeyException
                | InvalidKeyIdException | NoSessionException | UntrustedIdentityException
                | DuplicateMessageException | LegacyMessageException e) {
            throw new DecryptException("decrypt failed for " + senderAddr + ": " + e, e);
        }

        byte[] body = MessageEncoder.unpad(padded);
        Wa.Message message;
        try {
            message = Wa.Message.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            throw new DecryptException("invalid Wa.Message proto from " + senderAddr, e);
        }

        // Inbound SKDM: a peer is announcing the group sender-key they'll use for
        // subsequent skmsg traffic in this group. Process now so the next skmsg decrypts.
        if (message.hasSenderKeyDistributionMessage()) {
            processSkdm(message.getSenderKeyDistributionMessage(), senderAddr, senderKeyStore);
        }

        // Unwrap DSM — when the sender is one of our own other devices echoing a
        // message into the chat with `destinationJid`, the actual user-visible message
        // is the inner `message` field.
        if (message.hasDeviceSentMessage() && message.getDeviceSentMessage().hasMessage()) {
            message = message.getDeviceSentMessage().getMessage();
        }

        String text = message.hasConversation() ? message.getConversation() : null;
        if (text == null && message.hasExtendedTextMessage()
                && message.getExtendedTextMessage().hasText()) {
            text = message.getExtendedTextMessage().getText();
        }

        InteractiveResponse interactive = parseInteractive(message);

        System.out.println(">>> Decoded Wa.Message: " + message);

        LOG.debug("Decoded {} from {}{} (encType={}, text={}, interactive={})",
            msgId, senderJidStr,
            groupJid != null ? " in group " + groupJid : "",
            encType, text != null ? "yes" : "no",
            interactive != null ? interactive.kind() + "/" + interactive.selectedId() : "no");
        return new Decoded(senderJidStr, groupJid, msgId, encType, message, text, interactive);
    }

    private static InteractiveResponse parseInteractive(Wa.Message message) {
        if (message.hasButtonsResponseMessage()) {
            var b = message.getButtonsResponseMessage();
            return new InteractiveResponse("buttons",
                b.getSelectedButtonId(), null, b.getSelectedDisplayText());
        }
        if (message.hasListResponseMessage()) {
            var l = message.getListResponseMessage();
            String rowId = l.hasSingleSelectReply() ? l.getSingleSelectReply().getSelectedRowId() : "";
            return new InteractiveResponse("list", rowId, null, l.getTitle());
        }
        if (message.hasInteractiveResponseMessage()) {
            var ir = message.getInteractiveResponseMessage();
            String name = ir.hasNativeFlowResponseMessage() ? ir.getNativeFlowResponseMessage().getName() : "";
            String params = ir.hasNativeFlowResponseMessage() ? ir.getNativeFlowResponseMessage().getParamsJson() : null;
            String body = ir.hasBody() ? ir.getBody().getText() : null;
            
            String selectedId = null;
            if (params != null) {
                var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(params);
                if (matcher.find()) {
                    selectedId = matcher.group(1);
                }
            }
            if (selectedId == null) {
                selectedId = name;
            }
            
            return new InteractiveResponse("native_flow", selectedId, params, body);
        }
        if (message.hasTemplateButtonReplyMessage()) {
            var t = message.getTemplateButtonReplyMessage();
            return new InteractiveResponse("template",
                t.getSelectedId(), null, t.getSelectedDisplayText());
        }
        return null;
    }

    private static void processSkdm(Wa.Message.SenderKeyDistributionMessage proto,
                                    SignalProtocolAddress senderAddr,
                                    SenderKeyStore senderKeyStore) {
        String groupId = proto.getGroupId();
        byte[] axoBytes = proto.getAxolotlSenderKeyDistributionMessage().toByteArray();
        if (groupId.isEmpty() || axoBytes.length == 0) {
            LOG.warn("Skipping malformed SKDM (groupId='{}', axoBytes={}B)", groupId, axoBytes.length);
            return;
        }
        try {
            SenderKeyDistributionMessage skdm = new SenderKeyDistributionMessage(axoBytes);
            SenderKeyName name = new SenderKeyName(groupId, senderAddr);
            new GroupSessionBuilder(senderKeyStore).process(name, skdm);
            LOG.debug("Processed SKDM from {} for group {}", senderAddr, groupId);
        } catch (LegacyMessageException | InvalidMessageException e) {
            LOG.warn("Invalid SKDM from {} for group {}: {}", senderAddr, groupId, e.toString());
        }
    }

    private static BinaryNode findEnc(BinaryNode stanza) {
        for (BinaryNode child : stanza.childrenList()) {
            if (!"enc".equals(child.tag())) continue;
            String type = child.attr("type");
            if ("msg".equals(type) || "pkmsg".equals(type) || "skmsg".equals(type)) return child;
        }
        return null;
    }

    /** Thrown when an {@code <enc>} payload cannot be decoded into a {@link Wa.Message}. */
    public static final class DecryptException extends Exception {
        public DecryptException(String message) { super(message); }
        public DecryptException(String message, Throwable cause) { super(message, cause); }
    }
}
