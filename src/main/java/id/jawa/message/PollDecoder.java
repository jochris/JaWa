// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import id.jawa.util.crypto.AesGcm;
import id.jawa.util.crypto.Hkdf;
import id.jawa.util.crypto.Sha256;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decrypt an inbound {@code pollUpdateMessage}. Each voter encrypts their choice
 * locally with a key derived from the poll's per-message {@code messageSecret} so
 * even the WhatsApp server can't see who voted for what. Decryption requires the
 * messageSecret the original poll was sent with.
 *
 * <p>The decrypted plaintext is the concatenation of 32-byte {@code SHA-256(optionName)}
 * digests — one per selected option. We hash every original option once and look up
 * each chunk to map it back to its display string.
 *
 * <p>Mirrors the {@code DecryptPollVote} / {@code generateMsgSecretKey} flow in
 * whatsmeow's {@code msgsecret.go} (case {@code EncSecretPollVote}).
 */
public final class PollDecoder {

    private static final byte[] USE_CASE = "Poll Vote".getBytes(StandardCharsets.UTF_8);

    private PollDecoder() {}

    /**
     * @param encPayload      the {@code pollUpdateMessage.vote.encPayload} bytes
     * @param encIv           the {@code pollUpdateMessage.vote.encIv} bytes (12 byte GCM nonce)
     * @param messageSecret   32-byte secret from the original poll's
     *                        {@code messageContextInfo.messageSecret}
     * @param pollMsgId       id of the poll message we sent
     * @param pollSenderJid   bare JID of the poll author (us — without device suffix)
     * @param voterJid        bare JID of the voter (the user who sent this update)
     * @param originalOptions every {@code optionName} from the original
     *                        {@code pollCreationMessage}, used to match hashes back
     * @return the selected option names; empty if the voter cleared their pick
     * @throws IllegalStateException on GCM tag mismatch (wrong secret / tampering)
     */
    public static List<String> decryptVote(byte[] encPayload, byte[] encIv,
                                           byte[] messageSecret,
                                           String pollMsgId,
                                           String pollSenderJid,
                                           String voterJid,
                                           List<String> originalOptions) {
        byte[] idBytes        = pollMsgId.getBytes(StandardCharsets.UTF_8);
        byte[] pollSenderBytes = pollSenderJid.getBytes(StandardCharsets.UTF_8);
        byte[] voterBytes      = voterJid.getBytes(StandardCharsets.UTF_8);

        byte[] info = concat(idBytes, pollSenderBytes, voterBytes, USE_CASE);
        byte[] secretKey = Hkdf.derive(messageSecret, new byte[0], info, 32);

        byte[] additionalData = new byte[idBytes.length + 1 + voterBytes.length];
        System.arraycopy(idBytes,    0, additionalData, 0, idBytes.length);
        additionalData[idBytes.length] = 0;
        System.arraycopy(voterBytes, 0, additionalData, idBytes.length + 1, voterBytes.length);

        byte[] plaintext = AesGcm.decrypt(secretKey, encIv, encPayload, additionalData);
        if (plaintext.length % 32 != 0) {
            throw new IllegalStateException("plaintext length " + plaintext.length + " not a multiple of 32");
        }

        Map<String, String> hashToOption = new HashMap<>();
        for (String opt : originalOptions) {
            hashToOption.put(hex(Sha256.hash(opt.getBytes(StandardCharsets.UTF_8))), opt);
        }

        List<String> picked = new ArrayList<>();
        for (int i = 0; i < plaintext.length; i += 32) {
            String hashHex = hex(Arrays.copyOfRange(plaintext, i, i + 32));
            String opt = hashToOption.get(hashHex);
            if (opt != null) picked.add(opt);
        }
        return picked;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
