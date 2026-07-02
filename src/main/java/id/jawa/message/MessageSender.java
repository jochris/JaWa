// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import id.jawa.binary.BinaryNode;
import id.jawa.proto.Wa;
import id.jawa.signal.JaWaProtocolStore;
import id.jawa.signal.SessionBootstrap;
import id.jawa.store.AuthCreds;
import id.jawa.util.Jid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.protocol.CiphertextMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encrypts a {@link Wa.Message} for one-or-more recipient devices via libsignal's
 * {@link SessionCipher} and packs the result into a {@code <message>} stanza.
 *
 * <p>Stanza shape (ports {@code messages-send.ts:relayMessage}):
 * <pre>{@code
 * <message to="<recipient-bare-jid>" id="<msgId>" type="text" t="<unix-seconds>">
 *   <participants>
 *     <to jid="<recipient-device0-jid>">
 *       <enc v="2" type="pkmsg|msg">{ciphertext}</enc>
 *     </to>
 *     ...
 *   </participants>
 *   <device-identity>{advSignedDeviceIdentity bytes}</device-identity>  (if any pkmsg)
 * </message>
 * }</pre>
 */
public final class MessageSender {

    private static final Logger LOG = LoggerFactory.getLogger(MessageSender.class);

    private MessageSender() {}

    /**
     * Encrypt {@code message} for every device in {@code deviceJids} (skipping our own
     * device) and produce the full {@code <message>} stanza ready to {@code send()}.
     *
     * <p>Returns {@link Result#stanza()} and a {@code Map} of which device produced
     * which {@code CiphertextMessage} type.
     */
    public static Result buildStanza(JaWaProtocolStore store,
                                     AuthCreds creds,
                                     String msgId,
                                     String recipientBareJid,
                                     List<String> deviceJids,
                                     Wa.Message message) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(creds);
        Objects.requireNonNull(message);

        Jid myJid = Jid.parse(recipientBareJid.endsWith("@lid") && creds.meLid != null && !creds.meLid.isBlank() ? creds.meLid : creds.meJid);
        if (myJid == null) throw new IllegalStateException("creds.meJid/meLid invalid");
        SignalProtocolAddress myAddr = new SignalProtocolAddress(myJid.user(), myJid.device());
        String ownUser = myJid.user();

        // Foreign-recipient plaintext: the bare Wa.Message.
        // Own-companion plaintext: wrap in DeviceSentMessage{destinationJid, message} so the
        // recipient's phone knows which chat to attribute it to. Without this, the phone
        // Signal-decrypts but silently drops the payload — server still ACKs (transport
        // accepted) but no chat insert and no delivery receipt fires.
        byte[] msgPadded = MessageEncoder.encode(message);
        byte[] dsmPadded = null; // built lazily on first own-companion device

        List<BinaryNode> participants = new ArrayList<>();
        Map<SignalProtocolAddress, Integer> typeMap = new HashMap<>();
        boolean includeDeviceIdentity = false;

        for (String dj : deviceJids) {
            Jid jid = Jid.parse(dj);
            if (jid == null) continue;
            SignalProtocolAddress addr = SessionBootstrap.addressFor(jid);
            if (addr.equals(myAddr)) continue; // skip the exact sender device

            byte[] plaintext;
            if (jid.user().equals(ownUser)) {
                if (dsmPadded == null) {
                    dsmPadded = MessageEncoder.encode(
                        MessageEncoder.deviceSent(recipientBareJid, message));
                }
                plaintext = dsmPadded;
            } else {
                plaintext = msgPadded;
            }

            try {
                SessionCipher cipher = new SessionCipher(store, addr);
                CiphertextMessage ct = cipher.encrypt(plaintext);
                String type = ct.getType() == CiphertextMessage.PREKEY_TYPE ? "pkmsg" : "msg";
                if ("pkmsg".equals(type)) includeDeviceIdentity = true;
                typeMap.put(addr, ct.getType());

                BinaryNode enc = new BinaryNode("enc",
                    Map.of("v", "2", "type", type),
                    ct.serialize());
                BinaryNode to = new BinaryNode("to",
                    Map.of("jid", dj),
                    List.of(enc));
                participants.add(to);
            } catch (UntrustedIdentityException e) {
                LOG.warn("Encrypt failed for {}: {}", dj, e.toString());
            }
        }

        if (participants.isEmpty()) {
            throw new IllegalStateException("No recipients could be encrypted for; "
                + "did you bootstrap sessions first?");
        }

        List<BinaryNode> outer = new ArrayList<>();
        outer.add(new BinaryNode("participants", Map.of(), participants));
        if (includeDeviceIdentity && creds.account != null) {
            // Re-encode without accountSignatureKey (Baileys: encodeSignedDeviceIdentity false)
            byte[] deviceIdentity = encodeDeviceIdentityForSend(creds.account);
            outer.add(new BinaryNode("device-identity", Map.of(), deviceIdentity));
        }
        BinaryNode biz = BizNode.buildIfNeeded(message);
        if (biz != null) outer.add(biz);

        java.util.Map<String, String> attrs = new java.util.HashMap<>();
        attrs.put("to", recipientBareJid);
        attrs.put("id", msgId);
        attrs.put("type", "text");
        if (recipientBareJid.endsWith("@lid")) {
            attrs.put("addressing_mode", "lid");
        }
        BinaryNode stanza = new BinaryNode("message", attrs, outer);

        return new Result(stanza, typeMap);
    }

    /**
     * Build a stanza with dual-identity support: route via {@code wireDeviceJids} (PN)
     * but encrypt using Signal sessions from {@code encryptionDeviceJids} (LID).
     *
     * <p>This mirrors whatsmeow's {@code encryptMessageForDeviceAndWrap} pattern where
     * {@code <to jid="PN-device">} is used for routing but the Signal cipher uses
     * the LID address.
     */
    public static Result buildDualIdentityStanza(JaWaProtocolStore store,
                                                  AuthCreds creds,
                                                  String msgId,
                                                  String recipientBareJid,
                                                  List<String> wireDeviceJids,
                                                  List<String> encryptionDeviceJids,
                                                  Wa.Message message) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(creds);
        Objects.requireNonNull(message);

        Jid myJid = Jid.parse(creds.meJid);
        if (myJid == null) throw new IllegalStateException("creds.meJid invalid");
        SignalProtocolAddress myAddr = new SignalProtocolAddress(myJid.user(), myJid.device());
        String ownUser = myJid.user();

        // Also check own LID to skip own LID device
        String ownLidUser = null;
        if (creds.meLid != null && !creds.meLid.isBlank()) {
            Jid lidJid = Jid.parse(creds.meLid);
            if (lidJid != null) ownLidUser = lidJid.user();
        }

        byte[] msgPadded = MessageEncoder.encode(message);
        byte[] dsmPadded = null;

        List<BinaryNode> participants = new ArrayList<>();
        Map<SignalProtocolAddress, Integer> typeMap = new HashMap<>();
        boolean includeDeviceIdentity = false;

        // Build a mapping from wire device -> encryption device
        // They should be parallel lists of the same size
        for (int i = 0; i < wireDeviceJids.size(); i++) {
            String wireJid = wireDeviceJids.get(i);
            String encJid = (i < encryptionDeviceJids.size()) ? encryptionDeviceJids.get(i) : wireJid;

            Jid wireParsed = Jid.parse(wireJid);
            Jid encParsed = Jid.parse(encJid);
            if (wireParsed == null || encParsed == null) continue;

            SignalProtocolAddress encAddr = SessionBootstrap.addressFor(encParsed);
            if (encAddr.equals(myAddr)) continue; // skip the exact sender device

            // Also skip own LID device
            if (ownLidUser != null && encParsed.user().equals(ownLidUser)
                    && encParsed.device() == myJid.device()) continue;

            byte[] plaintext;
            boolean isOwnDevice = wireParsed.user().equals(ownUser)
                    || (ownLidUser != null && encParsed.user().equals(ownLidUser));
            if (isOwnDevice) {
                if (dsmPadded == null) {
                    dsmPadded = MessageEncoder.encode(
                        MessageEncoder.deviceSent(recipientBareJid, message));
                }
                plaintext = dsmPadded;
            } else {
                plaintext = msgPadded;
            }

            try {
                SessionCipher cipher = new SessionCipher(store, encAddr);
                CiphertextMessage ct = cipher.encrypt(plaintext);
                String type = ct.getType() == CiphertextMessage.PREKEY_TYPE ? "pkmsg" : "msg";
                if ("pkmsg".equals(type)) includeDeviceIdentity = true;
                typeMap.put(encAddr, ct.getType());

                BinaryNode enc = new BinaryNode("enc",
                    Map.of("v", "2", "type", type),
                    ct.serialize());
                // Wire identity: use the PN device JID for the <to jid="..."> node
                BinaryNode to = new BinaryNode("to",
                    Map.of("jid", wireJid),
                    List.of(enc));
                participants.add(to);
            } catch (UntrustedIdentityException e) {
                LOG.warn("Encrypt failed for {} (enc={}): {}", wireJid, encJid, e.toString());
            }
        }

        if (participants.isEmpty()) {
            throw new IllegalStateException("No recipients could be encrypted for; "
                + "did you bootstrap sessions first?");
        }

        List<BinaryNode> outer = new ArrayList<>();
        outer.add(new BinaryNode("participants", Map.of(), participants));
        if (includeDeviceIdentity && creds.account != null) {
            byte[] deviceIdentity = encodeDeviceIdentityForSend(creds.account);
            outer.add(new BinaryNode("device-identity", Map.of(), deviceIdentity));
        }
        BinaryNode biz = BizNode.buildIfNeeded(message);
        if (biz != null) outer.add(biz);

        java.util.Map<String, String> attrs = new java.util.HashMap<>();
        attrs.put("to", recipientBareJid);
        attrs.put("id", msgId);
        attrs.put("type", "text");
        if (recipientBareJid.endsWith("@lid")) {
            attrs.put("addressing_mode", "lid");
        }
        BinaryNode stanza = new BinaryNode("message", attrs, outer);

        return new Result(stanza, typeMap);
    }

    /**
     * Re-serialise {@link Wa.ADVSignedDeviceIdentity} without {@code accountSignatureKey}.
     * Package-visible so {@link GroupSender} can include it in the group-send stanza.
     */
    static byte[] encodeDeviceIdentityForSend(byte[] fullAccountBytes) {
        try {
            Wa.ADVSignedDeviceIdentity full = Wa.ADVSignedDeviceIdentity.parseFrom(fullAccountBytes);
            Wa.ADVSignedDeviceIdentity.Builder b = Wa.ADVSignedDeviceIdentity.newBuilder()
                .setDetails(full.getDetails())
                .setAccountSignature(full.getAccountSignature());
            if (full.hasDeviceSignature()) b.setDeviceSignature(full.getDeviceSignature());
            // intentionally omit accountSignatureKey
            return b.build().toByteArray();
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalStateException("invalid creds.account proto", e);
        }
    }

    public record Result(BinaryNode stanza, Map<SignalProtocolAddress, Integer> ciphertextTypes) {}
}
