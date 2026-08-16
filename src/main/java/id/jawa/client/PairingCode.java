/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.jawa.client;

import id.jawa.util.Bytes;
import id.jawa.util.Crockford;
import id.jawa.util.Crypto;

/**
 * Handles companion ephemeral key and phone pairing code generation, matching whatsmeow pair-code.go.
 */
public final class PairingCode {

    private PairingCode() {}

    public record PairingResult(
        Crypto.KeyPair keyPair,
        byte[] ephemeralKey,
        String formattedCode
    ) {}

    public static PairingResult generateCompanionEphemeralKey() {
        Crypto.KeyPair keyPair = Crypto.generateCurve25519KeyPair();
        byte[] salt = Bytes.random(32);
        byte[] iv = Bytes.random(16);
        byte[] codeBytes = Bytes.random(5);

        String rawCode = Crockford.encode(codeBytes);
        byte[] linkCodeKey = Crypto.pbkdf2HmacSha256(rawCode, salt, 2 << 16, 32);
        byte[] encryptedPubkey = Crypto.aesCtrEncrypt(linkCodeKey, iv, keyPair.pubKey());

        byte[] ephemeralKey = Bytes.concat(salt, iv, encryptedPubkey);
        String formattedCode = Crockford.formatCode(rawCode);

        return new PairingResult(keyPair, ephemeralKey, formattedCode);
    }
}
