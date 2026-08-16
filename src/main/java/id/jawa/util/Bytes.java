// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** Minimal byte manipulation utilities. */
public final class Bytes {
    private static final SecureRandom RNG = new SecureRandom();

    private Bytes() {}

    public static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) if (p != null) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            if (p != null) {
                System.arraycopy(p, 0, out, pos, p.length);
                pos += p.length;
            }
        }
        return out;
    }

    public static byte[] slice(byte[] src, int from, int to) {
        return Arrays.copyOfRange(src, from, to);
    }

    public static byte[] random(int length) {
        byte[] b = new byte[length];
        RNG.nextBytes(b);
        return b;
    }

    public static String toHex(byte[] b) { return HexFormat.of().formatHex(b); }
    public static byte[] fromHex(String s) { return HexFormat.of().parseHex(s); }

    public static String toBase64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    public static byte[] fromBase64(String s) { return Base64.getDecoder().decode(s); }

    public static String toBase64Url(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static byte[] fromBase64Url(String s) { return Base64.getUrlDecoder().decode(s); }

    public static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    public static String fromUtf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
