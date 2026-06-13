// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.appstate;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class LtHashTest {

    @Test
    void identityZeroBaseAddSubtractRoundTrip() {
        byte[] base = new byte[128];
        byte[][] mutation = { "hello world".getBytes() };

        byte[] withAdded = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(base, new byte[0][], mutation);
        byte[] reverted  = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(withAdded, mutation, new byte[0][]);

        assertThat(reverted).isEqualTo(base);
    }

    @Test
    void orderIndependence() {
        byte[][] a = { "a".getBytes() };
        byte[][] b = { "b".getBytes() };
        byte[][] c = { "c".getBytes() };

        byte[] x = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(new byte[128], new byte[0][], a);
        x = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(x, new byte[0][], b);
        x = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(x, new byte[0][], c);

        byte[] y = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(new byte[128], new byte[0][], c);
        y = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(y, new byte[0][], a);
        y = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(y, new byte[0][], b);

        assertThat(x).isEqualTo(y);
    }

    @Test
    void subtractCancelsAdd() {
        byte[][] mutations = new byte[5][];
        SecureRandom rng = new SecureRandom();
        for (int i = 0; i < 5; i++) { mutations[i] = new byte[16]; rng.nextBytes(mutations[i]); }

        byte[] state = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(new byte[128], new byte[0][], mutations);
        byte[] backToZero = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(state, mutations, new byte[0][]);

        assertThat(backToZero).isEqualTo(new byte[128]);
    }

    @Test
    void differentMutationsProduceDifferentHashes() {
        byte[] x = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(new byte[128], new byte[0][],
            new byte[][] { "alpha".getBytes() });
        byte[] y = LtHash.WA_PATCH_INTEGRITY.subtractThenAdd(new byte[128], new byte[0][],
            new byte[][] { "beta".getBytes() });
        assertThat(x).isNotEqualTo(y);
    }
}
