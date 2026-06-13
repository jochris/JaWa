// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.appstate;

import id.jawa.util.crypto.Hkdf;

import java.nio.charset.StandardCharsets;

/**
 * Summation-based hash that maintains data integrity across a series of mutations.
 * Add and subtract operations commute, so applying the same set of mutations in any
 * order produces the same final hash — exactly what WhatsApp needs to verify that
 * a client's view of an app-state collection matches the server's.
 *
 * <p>Each mutation contributes a 128-byte HKDF-SHA256 expansion of its raw bytes;
 * the running hash is the pointwise sum (mod 2^16) of every contribution. Removing
 * a mutation subtracts the same digest back out.
 */
public final class LtHash {

    /** WhatsApp's standard LTHash instance for app-state patch integrity. */
    public static final LtHash WA_PATCH_INTEGRITY = new LtHash(
        "WhatsApp Patch Integrity".getBytes(StandardCharsets.UTF_8), 128);

    private final byte[] hkdfInfo;
    private final int hkdfSize;

    public LtHash(byte[] hkdfInfo, int hkdfSize) {
        this.hkdfInfo = hkdfInfo.clone();
        this.hkdfSize = hkdfSize;
    }

    /** Return a fresh hash equal to {@code base} with {@code subtract} removed and {@code add} mixed in. */
    public byte[] subtractThenAdd(byte[] base, byte[][] subtract, byte[][] add) {
        byte[] out = base.clone();
        subtractThenAddInPlace(out, subtract, add);
        return out;
    }

    /** Mutate {@code base} in place: subtract every entry of {@code subtract}, then add every entry of {@code add}. */
    public void subtractThenAddInPlace(byte[] base, byte[][] subtract, byte[][] add) {
        for (byte[] item : subtract) {
            pointwise(base, expand(item), true);
        }
        for (byte[] item : add) {
            pointwise(base, expand(item), false);
        }
    }

    private byte[] expand(byte[] item) {
        return Hkdf.derive(item, new byte[0], hkdfInfo, hkdfSize);
    }

    private static void pointwise(byte[] base, byte[] input, boolean subtract) {
        for (int i = 0; i < base.length; i += 2) {
            int x = ((base[i] & 0xFF) | ((base[i + 1] & 0xFF) << 8));
            int y = ((input[i] & 0xFF) | ((input[i + 1] & 0xFF) << 8));
            int result = (subtract ? x - y : x + y) & 0xFFFF;
            base[i]     = (byte) (result & 0xFF);
            base[i + 1] = (byte) ((result >> 8) & 0xFF);
        }
    }
}
