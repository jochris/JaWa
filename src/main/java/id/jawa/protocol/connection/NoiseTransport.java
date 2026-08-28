// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.protocol.connection;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;


import id.jawa.protocol.crypto.AesGcm;

/**
 * Post-handshake Noise AEAD wrapper — two independent {@code AES-256-GCM} keys with
 * monotonically-increasing 32-bit BE counters embedded in the IV. AAD is empty.
 */
public final class NoiseTransport {

    private final byte[] writeKey;
    private final byte[] readKey;
    private long writeCounter = 0;
    private long readCounter  = 0;

    NoiseTransport(byte[] writeKey, byte[] readKey) {
        if (writeKey.length != 32 || readKey.length != 32) {
            throw new IllegalArgumentException("transport keys must be 32 bytes");
        }
        this.writeKey = writeKey;
        this.readKey  = readKey;
    }

    public synchronized byte[] encrypt(byte[] plaintext) {
        byte[] iv = AesGcm.noiseIv(writeCounter++);
        return AesGcm.encrypt(writeKey, iv, plaintext, null);
    }

    public synchronized byte[] decrypt(byte[] ciphertext) {
        byte[] iv = AesGcm.noiseIv(readCounter++);
        return AesGcm.decrypt(readKey, iv, ciphertext, null);
    }

    public long writeCounter() { return writeCounter; }
    public long readCounter()  { return readCounter; }
}