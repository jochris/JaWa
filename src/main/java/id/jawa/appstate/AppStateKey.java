// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.appstate;

import id.jawa.util.crypto.Hkdf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A 32-byte app-state key shared between our linked devices via
 * {@code Wa.Message.protocolMessage.appStateSyncKeyShare}. The key data itself
 * never appears on the wire after the initial share; instead it's expanded via
 * HKDF into five 32-byte sub-keys that authenticate / decrypt each mutation.
 */
public record AppStateKey(byte[] keyId, byte[] keyData) {

    public AppStateKey {
        if (keyData != null && keyData.length != 32) {
            throw new IllegalArgumentException("app-state keyData must be 32 bytes, got " + keyData.length);
        }
    }

    /** Bundle of sub-keys derived from a single 32-byte app-state key. */
    public record Expanded(byte[] index, byte[] valueEnc, byte[] valueMac,
                           byte[] snapshotMac, byte[] patchMac) {}

    /**
     * HKDF-SHA256 expand the 32-byte raw key into 160 bytes, split into five 32-byte
     * sub-keys: index, valueEnc, valueMAC, snapshotMAC, patchMAC.
     */
    public Expanded expand() {
        byte[] info = "WhatsApp Mutation Keys".getBytes(StandardCharsets.UTF_8);
        byte[] out = Hkdf.derive(keyData, new byte[0], info, 160);
        return new Expanded(
            Arrays.copyOfRange(out, 0,   32),
            Arrays.copyOfRange(out, 32,  64),
            Arrays.copyOfRange(out, 64,  96),
            Arrays.copyOfRange(out, 96,  128),
            Arrays.copyOfRange(out, 128, 160));
    }
}
