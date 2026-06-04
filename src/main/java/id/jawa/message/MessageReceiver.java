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
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;

/**
 * Decrypt an incoming {@code <message>} stanza: pick the {@code <enc>} child, run
 * libsignal's {@link SessionCipher}, strip the random pad, parse the {@link Wa.Message}
 * protobuf, and unwrap a {@code DeviceSentMessage} envelope when present.
 *
 * <p>Mirrors {@code decryptMessageNode} in Baileys ({@code Utils/decode-wa-message.ts})
 * and {@code decryptDM} + {@code decryptMessages} in whatsmeow ({@code message.go}).
 *
 * <p>Direct (1-on-1) text messages only at this stage. Group ({@code skmsg}),
 * bot ({@code msmsg}), and any non-text payload types are not yet handled — they'll
 * arrive as a {@link Decoded} with {@code text == null}.
 */
public final class MessageReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(MessageReceiver.class);

    private MessageReceiver() {}

    /**
     * Decode result.
     *
     * @param senderJid   sender's device-specific JID (from {@code participant} attr
     *                    when present, else {@code from}). For direct messages this is
     *                    the contact's primary or companion device JID.
     * @param msgId       value of the {@code <message id=>} attr.
     * @param encType     the {@code <enc type=>} value: "pkmsg" or "msg".
     * @param message     the decrypted, DSM-unwrapped {@link Wa.Message}, or {@code null}
     *                    if no usable {@code <enc>} child was found.
     * @param text        the {@code conversation} field if the message is plain text,
     *                    else {@code null} (e.g. media, reactions, system messages).
     */
    public record Decoded(String senderJid, String msgId, String encType,
                          Wa.Message message, String text) {}

    /**
     * Decode {@code messageStanza} using sessions in {@code store}.
     *
     * @throws DecryptException when the {@code <enc>} payload exists but cannot be
     *                          decrypted (untrusted identity, no session, bad MAC, etc).
     *                          The caller should still send an {@code <ack>}, but may
     *                          choose to send a retry receipt instead of a delivery one.
     */
    public static Decoded decode(BinaryNode messageStanza, JaWaProtocolStore store)
            throws DecryptException {
        String msgId = messageStanza.attr("id");
        String from = messageStanza.attr("from");
        String participant = messageStanza.attr("participant");
        String senderJidStr = participant != null ? participant : from;
        if (senderJidStr == null) {
            throw new DecryptException("message has no from/participant attr");
        }

        BinaryNode enc = findEnc(messageStanza);
        if (enc == null) {
            return new Decoded(senderJidStr, msgId, null, null, null);
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
        SignalProtocolAddress addr = SessionBootstrap.addressFor(senderJid);
        SessionCipher cipher = new SessionCipher(store, addr);

        byte[] padded;
        try {
            padded = switch (encType) {
                case "pkmsg" -> cipher.decrypt(new PreKeySignalMessage(ciphertext));
                case "msg"   -> cipher.decrypt(new SignalMessage(ciphertext));
                default      -> throw new DecryptException("unsupported enc type: " + encType);
            };
        } catch (InvalidVersionException | InvalidMessageException | InvalidKeyException
                | InvalidKeyIdException | NoSessionException | UntrustedIdentityException
                | DuplicateMessageException | LegacyMessageException e) {
            throw new DecryptException("decrypt failed for " + addr + ": " + e, e);
        }

        byte[] body = MessageEncoder.unpad(padded);
        Wa.Message message;
        try {
            message = Wa.Message.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            throw new DecryptException("invalid Wa.Message proto from " + addr, e);
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

        LOG.debug("Decoded {} from {} (encType={}, text={})",
            msgId, senderJidStr, encType, text != null ? "yes" : "no");
        return new Decoded(senderJidStr, msgId, encType, message, text);
    }

    private static BinaryNode findEnc(BinaryNode stanza) {
        for (BinaryNode child : stanza.childrenList()) {
            if (!"enc".equals(child.tag())) continue;
            String type = child.attr("type");
            if ("msg".equals(type) || "pkmsg".equals(type)) return child;
        }
        return null;
    }

    /** Thrown when an {@code <enc>} payload cannot be decoded into a {@link Wa.Message}. */
    public static final class DecryptException extends Exception {
        public DecryptException(String message) { super(message); }
        public DecryptException(String message, Throwable cause) { super(message, cause); }
    }
}
