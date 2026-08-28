/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.jawa.domain.store;

import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.model.SignedPreKey;
import id.jawa.protocol.crypto.KeyPair25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class SqliteAuthStoreTest {

    @Test
    public void testSqliteAuthStoreSaveAndLoad(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("test_session.db");

        AuthCreds c = new AuthCreds();
        var kp1 = new KeyPair25519(new byte[32], new byte[32]);
        var kp2 = new KeyPair25519(new byte[32], new byte[32]);
        var kp3 = new KeyPair25519(new byte[32], new byte[32]);

        c.noiseKey = kp1;
        c.signedIdentityKey = kp2;
        c.signedPreKey = new SignedPreKey(1, kp3, new byte[64]);
        c.registrationId = 12345;
        c.advSecretKey = new byte[32];
        c.meJid = "62895416602000@s.whatsapp.net";
        c.meLid = "113112896245886@lid";
        c.pushName = "TestOwner";
        c.account = new byte[]{1, 2, 3, 4};
        c.platform = "android";

        try (SqliteAuthStore store = new SqliteAuthStore(dbPath)) {
            assertThat(store.load()).isNull();
            assertThat(store.isPaired()).isFalse();

            store.save(c);

            assertThat(store.isPaired()).isTrue();

            AuthCreds loaded = store.load();
            assertThat(loaded).isNotNull();
            assertThat(loaded.registrationId).isEqualTo(12345);
            assertThat(loaded.meJid).isEqualTo("62895416602000@s.whatsapp.net");
            assertThat(loaded.meLid).isEqualTo("113112896245886@lid");
            assertThat(loaded.pushName).isEqualTo("TestOwner");
            assertThat(loaded.account).containsExactly((byte)1, (byte)2, (byte)3, (byte)4);
        }
    }
}
