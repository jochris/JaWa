// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util;

/**
 * Crockford Base32 encoding for WhatsApp phone pairing code.
 * Alphabet: 123456789ABCDEFGHJKLMNPQRSTVWXYZ
 */
public final class Crockford {
    public static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTVWXYZ";

    private Crockford() {}

    public static String encode(byte[] data) {
        int value = 0;
        int bits = 0;
        StringBuilder sb = new StringBuilder();
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

    public static String formatCode(String code) {
        if (code.length() == 8) {
            return code.substring(0, 4) + "-" + code.substring(4);
        }
        return code;
    }
}
