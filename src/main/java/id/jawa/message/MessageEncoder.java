// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import id.jawa.proto.Wa;
import id.jawa.util.Bytes;

/**
 * Encode a {@link id.jawa.proto.Wa.Message} for E2E transmission and decode it back.
 *
 * <p>WhatsApp wraps every message proto with a random-pad-max-16 scheme before
 * Signal encryption: append {@code N} bytes, each equal to {@code N}, where {@code N}
 * is uniformly random in {@code [1, 16]}.
 *
 * <p>This mirrors {@code writeRandomPadMax16} / {@code unpadRandomMax16} in Baileys'
 * {@code Utils/generics.ts}.
 */
public final class MessageEncoder {

    private MessageEncoder() {}

    /** Serialise {@code msg} and append random padding. */
    public static byte[] encode(Wa.Message msg) {
        byte[] body = msg.toByteArray();
        return pad(body);
    }

    /** Strip the trailing pad bytes from a decrypted message. */
    public static byte[] unpad(byte[] padded) {
        if (padded == null || padded.length == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        int n = padded[padded.length - 1] & 0xFF;
        if (n == 0 || n > padded.length || n > 16) {
            throw new IllegalArgumentException("invalid pad length: " + n);
        }
        return Bytes.slice(padded, 0, padded.length - n);
    }

    /** Append a random pad in {@code [1, 16]} bytes, each containing the pad length. */
    public static byte[] pad(byte[] body) {
        int n = (Bytes.random(1)[0] & 0x0F) + 1; // 1..16
        byte[] out = new byte[body.length + n];
        System.arraycopy(body, 0, out, 0, body.length);
        byte pad = (byte) n;
        for (int i = body.length; i < out.length; i++) out[i] = pad;
        return out;
    }

    /** Build a plain text {@code Wa.Message} with the given conversation string. */
    public static Wa.Message text(String body) {
        return Wa.Message.newBuilder().setConversation(body).build();
    }
}
