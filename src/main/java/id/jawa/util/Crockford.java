// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util;

/**
 * Crockford base32 alphabet — 32 symbols, no ambiguous chars (no I/L/O/U).
 * Used by WhatsApp to format the 8-character phone-number pairing code from 5 random bytes.
 */
public final class Crockford {

    /** 32 characters; same alphabet as Baileys' CROCKFORD_CHARACTERS. */
    public static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTVWXYZ";

    private Crockford() {}

    /** Encode raw bytes as a Crockford base32 string. 5 input bytes → 8 output chars (no padding). */
    public static String encode(byte[] data) {
        int value = 0;
        int bits = 0;
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((value >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((value << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }
}
