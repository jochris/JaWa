/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.jawa.domain.store;

import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.model.SignedPreKey;
import id.jawa.feature.appstate.AppStateKey;
import id.jawa.feature.appstate.FileAppStateKeyStorage;
import id.jawa.protocol.crypto.KeyPair25519;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.groups.SenderKeyName;
import org.whispersystems.libsignal.state.SessionRecord;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class SqliteAuthStoreTest {

    @Test
    public void testPureSqliteSessionStoreAllTables(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("pure_session.db");

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
            /* 1. Auth Credentials in SQLite */
            assertThat(store.load()).isNull();
            assertThat(store.isPaired()).isFalse();

            store.save(c);

            assertThat(store.isPaired()).isTrue();
            AuthCreds loaded = store.load();
            assertThat(loaded).isNotNull();
            assertThat(loaded.registrationId).isEqualTo(12345);

            /* 2. Signal Session Storage in SQLite */
            FileSessionStorage sessionStorage = new FileSessionStorage(store);
            SignalProtocolAddress addr = new SignalProtocolAddress("628123456789", 0);
            SessionRecord sessionRecord = new SessionRecord();
            sessionStorage.put(addr, sessionRecord);
            assertThat(sessionStorage.contains(addr)).isTrue();
            assertThat(sessionStorage.size()).isEqualTo(1);

            /* 3. Pre-Key Storage in SQLite */
            FilePreKeyStorage preKeyStorage = new FilePreKeyStorage(store);
            KeyPair25519 preKey = new KeyPair25519(new byte[32], new byte[32]);
            preKeyStorage.put(101, preKey);
            assertThat(preKeyStorage.get(101)).isNotNull();
            assertThat(preKeyStorage.size()).isEqualTo(1);

            /* 4. Sender Key Storage in SQLite */
            FileSenderKeyStorage senderKeyStorage = new FileSenderKeyStorage(store);
            SenderKeyName senderKeyName = new SenderKeyName("group123@g.us", addr);
            senderKeyStorage.put(senderKeyName, new byte[]{10, 20, 30});
            assertThat(senderKeyStorage.get(senderKeyName)).containsExactly((byte)10, (byte)20, (byte)30);
            assertThat(senderKeyStorage.size()).isEqualTo(1);

            /* 5. App-State Key Storage in SQLite */
            FileAppStateKeyStorage appStateKeyStorage = new FileAppStateKeyStorage(store);
            byte[] appKeyId = new byte[]{1, 2, 3, 4};
            AppStateKey appStateKey = new AppStateKey(appKeyId, new byte[32]);
            appStateKeyStorage.put(appStateKey);
            assertThat(appStateKeyStorage.get(appKeyId)).isNotNull();
            assertThat(appStateKeyStorage.size()).isEqualTo(1);
        }

        /* Verify persistence when reopened */
        try (SqliteAuthStore store2 = new SqliteAuthStore(dbPath)) {
            assertThat(store2.isPaired()).isTrue();

            FileSessionStorage sessionStorage2 = new FileSessionStorage(store2);
            SignalProtocolAddress addr = new SignalProtocolAddress("628123456789", 0);
            assertThat(sessionStorage2.contains(addr)).isTrue();

            FilePreKeyStorage preKeyStorage2 = new FilePreKeyStorage(store2);
            assertThat(preKeyStorage2.get(101)).isNotNull();

            FileSenderKeyStorage senderKeyStorage2 = new FileSenderKeyStorage(store2);
            SenderKeyName senderKeyName = new SenderKeyName("group123@g.us", addr);
            assertThat(senderKeyStorage2.get(senderKeyName)).containsExactly((byte)10, (byte)20, (byte)30);

            FileAppStateKeyStorage appStateKeyStorage2 = new FileAppStateKeyStorage(store2);
            byte[] appKeyId = new byte[]{1, 2, 3, 4};
            assertThat(appStateKeyStorage2.get(appKeyId)).isNotNull();
        }
    }
}
