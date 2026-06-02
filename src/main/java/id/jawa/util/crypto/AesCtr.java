// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/** AES-256-CTR. */
public final class AesCtr {
    private AesCtr() {}

    public static byte[] encrypt(byte[] key, byte[] iv, byte[] plaintext) {
        return run(Cipher.ENCRYPT_MODE, key, iv, plaintext);
    }

    public static byte[] decrypt(byte[] key, byte[] iv, byte[] ciphertext) {
        return run(Cipher.DECRYPT_MODE, key, iv, ciphertext);
    }

    private static byte[] run(int mode, byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-CTR failed", e);
        }
    }
}
