// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util;

import org.bouncycastle.crypto.generators.X25519KeyPairGenerator;
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.math.ec.rfc7748.X25519;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import java.security.MessageDigest;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Consolidated cryptographic operations for WhatsApp Web protocol (SHA-256, HMAC, HKDF, AES-GCM, Curve25519).
 */
public final class Crypto {
    private Crypto() {}

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) if (p != null) md.update(p);
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 failed", e);
        }
    }

    public static byte[] hmacSha256(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) if (p != null) mac.update(p);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
        byte[] actualSalt = (salt == null || salt.length == 0) ? new byte[32] : salt;
        return hmacSha256(actualSalt, ikm);
    }

    public static byte[] hkdfExpand(byte[] prk, byte[] info, int outputLen) {
        byte[] result = new byte[outputLen];
        byte[] t = new byte[0];
        int generated = 0;
        byte counter = 1;
        byte[] actualInfo = (info == null) ? new byte[0] : info;

        while (generated < outputLen) {
            byte[] input = Bytes.concat(t, actualInfo, new byte[]{counter});
            t = hmacSha256(prk, input);
            int todo = Math.min(t.length, outputLen - generated);
            System.arraycopy(t, 0, result, generated, todo);
            generated += todo;
            counter++;
        }
        return result;
    }

    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int outputLen) {
        byte[] prk = hkdfExtract(salt, ikm);
        return hkdfExpand(prk, info, outputLen);
    }

    public static byte[] aesGcmEncrypt(byte[] key, byte[] iv, byte[] plaintext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            if (aad != null && aad.length > 0) cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    public static byte[] aesGcmDecrypt(byte[] key, byte[] iv, byte[] ciphertext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            if (aad != null && aad.length > 0) cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }

    public record KeyPair(byte[] pubKey, byte[] privKey) {}

    public static KeyPair generateCurve25519KeyPair() {
        X25519KeyPairGenerator gen = new X25519KeyPairGenerator();
        gen.init(new X25519KeyGenerationParameters(new SecureRandom()));
        var pair = gen.generateKeyPair();
        byte[] pub = ((X25519PublicKeyParameters) pair.getPublic()).getEncoded();
        byte[] priv = ((X25519PrivateKeyParameters) pair.getPrivate()).getEncoded();
        return new KeyPair(pub, priv);
    }

    public static byte[] calculateAgreement(byte[] privKey, byte[] pubKey) {
        byte[] shared = new byte[32];
        X25519.calculateAgreement(privKey, 0, pubKey, 0, shared, 0);
        return shared;
    }
}
