// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.pair;

import id.jawa.util.Bytes;
import id.jawa.util.Crockford;
import id.jawa.util.crypto.AesCtr;
import id.jawa.util.crypto.Pbkdf2;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PairingCodeHelpersTest {

    @Test
    void crockfordEncodes5BytesTo8Chars() {
        byte[] in = Bytes.fromHex("0102030405");
        String code = Crockford.encode(in);
        assertThat(code).hasSize(8);
        for (int i = 0; i < code.length(); i++) {
            assertThat(Crockford.ALPHABET.indexOf(code.charAt(i))).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void crockfordAlphabetMatchesBaileys() {
        // WA-flavoured Crockford: 1-9 (no 0), A-Z minus I, O, U (L kept!).
        // Anchored as a regression — bytesToCrockford in Baileys uses this exact 32-char set.
        assertThat(Crockford.ALPHABET).isEqualTo("123456789ABCDEFGHJKLMNPQRSTVWXYZ");
        assertThat(Crockford.ALPHABET).hasSize(32);
        // The ambiguous chars dropped by WA:
        assertThat(Crockford.ALPHABET).doesNotContain("0", "I", "O", "U");
    }

    @Test
    void crockfordIsDeterministic() {
        byte[] in = Bytes.random(5);
        assertThat(Crockford.encode(in)).isEqualTo(Crockford.encode(in));
    }

    @Test
    void pbkdf2DeterministicAcrossRuns() {
        byte[] salt = Bytes.fromHex("0102030405060708");
        byte[] k1 = Pbkdf2.deriveSha256("WAJAWA123", salt, 1000, 32);
        byte[] k2 = Pbkdf2.deriveSha256("WAJAWA123", salt, 1000, 32);
        assertThat(k1).hasSize(32).containsExactly(k2);
    }

    @Test
    void pbkdf2DifferentSaltsProduceDifferentKeys() {
        byte[] k1 = Pbkdf2.deriveSha256("hello", Bytes.fromHex("0102030405060708"), 100, 16);
        byte[] k2 = Pbkdf2.deriveSha256("hello", Bytes.fromHex("0908070605040302"), 100, 16);
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void aesCtrRoundTrip() {
        byte[] key = Bytes.random(32);
        byte[] iv = Bytes.random(16);
        byte[] pt = Bytes.utf8("the quick brown fox jumps over the lazy dog");
        byte[] ct = AesCtr.encrypt(key, iv, pt);
        assertThat(ct).hasSize(pt.length); // CTR doesn't pad
        assertThat(AesCtr.decrypt(key, iv, ct)).containsExactly(pt);
    }

    @Test
    void aesCtrEncryptsExactly32BytesEphemeralPub() {
        // Wire-shape check: pairing flow encrypts a 32-byte Curve25519 pub
        byte[] key = Bytes.random(32);
        byte[] iv = Bytes.random(16);
        byte[] pub = Bytes.random(32);
        byte[] ct = AesCtr.encrypt(key, iv, pub);
        assertThat(ct).hasSize(32);
        assertThat(AesCtr.decrypt(key, iv, ct)).containsExactly(pub);
    }
}
