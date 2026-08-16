// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.socket;

import id.jawa.util.Bytes;
import id.jawa.util.Crypto;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * NoiseHandshake state machine, directly matching whatsmeow/socket/noisehandshake.go.
 */
public final class NoiseHandshake {

    private byte[] hash;
    private byte[] salt;
    private byte[] key;
    private final AtomicInteger counter = new AtomicInteger(0);

    public void start(String pattern, byte[] header) {
        byte[] nameBytes = Bytes.utf8(pattern);
        if (nameBytes.length == 32) {
            this.hash = nameBytes;
        } else {
            this.hash = Crypto.sha256(nameBytes);
        }
        this.salt = this.hash;
        this.key = this.hash;
        if (header != null && header.length > 0) {
            authenticate(header);
        }
    }

    public void authenticate(byte[] data) {
        this.hash = Crypto.sha256(Bytes.concat(this.hash, data));
    }

    private byte[] generateIv(int count) {
        byte[] iv = new byte[12];
        iv[8] = (byte) ((count >> 24) & 0xFF);
        iv[9] = (byte) ((count >> 16) & 0xFF);
        iv[10] = (byte) ((count >> 8) & 0xFF);
        iv[11] = (byte) (count & 0xFF);
        return iv;
    }

    public byte[] encrypt(byte[] plaintext) {
        int count = counter.getAndIncrement();
        byte[] ciphertext = Crypto.aesGcmEncrypt(key, generateIv(count), plaintext, hash);
        authenticate(ciphertext);
        return ciphertext;
    }

    public byte[] decrypt(byte[] ciphertext) {
        int count = counter.getAndIncrement();
        byte[] plaintext = Crypto.aesGcmDecrypt(key, generateIv(count), ciphertext, hash);
        authenticate(ciphertext);
        return plaintext;
    }

    public void mixSharedSecretIntoKey(byte[] privKey, byte[] pubKey) {
        byte[] secret = Crypto.calculateAgreement(privKey, pubKey);
        mixIntoKey(secret);
    }

    public void mixIntoKey(byte[] data) {
        counter.set(0);
        var keys = extractAndExpand(salt, data);
        this.salt = keys.write();
        this.key = keys.read();
    }

    public record Keys(byte[] write, byte[] read) {}

    public Keys finish() {
        return extractAndExpand(salt, new byte[0]);
    }

    private Keys extractAndExpand(byte[] salt, byte[] ikm) {
        byte[] prk = Crypto.hkdfExtract(salt, ikm);
        byte[] write = Crypto.hkdfExpand(prk, null, 32);
        byte[] read = Crypto.hkdfExpand(prk, null, 64);
        byte[] readKey = Bytes.slice(read, 32, 64);
        return new Keys(write, readKey);
    }
}
