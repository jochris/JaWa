// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.signal;

import id.jawa.binary.BinaryEncoder;
import id.jawa.binary.BinaryDecoder;
import id.jawa.binary.BinaryNode;
import id.jawa.store.AuthCreds;
import id.jawa.util.crypto.KeyPair25519;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreKeyManagerTest {

    @Test
    void generateCreatesRequestedCountAndAdvancesCounter() {
        AuthCreds creds = AuthCreds.generate();
        InMemorySignalKeyStore store = new InMemorySignalKeyStore();

        int initialNext = creds.nextPreKeyId;
        Map<Integer, KeyPair25519> keys = PreKeyManager.generate(creds, store, 30);

        assertThat(keys).hasSize(30);
        assertThat(keys.keySet()).allMatch(id -> id >= creds.firstUnuploadedPreKeyId);
        assertThat(creds.nextPreKeyId).isEqualTo(initialNext + 30);
        assertThat(store.size()).isEqualTo(30);
    }

    @Test
    void generateReusesExistingUnuploadedKeys() {
        AuthCreds creds = AuthCreds.generate();
        InMemorySignalKeyStore store = new InMemorySignalKeyStore();

        PreKeyManager.generate(creds, store, 10);
        int afterFirst = creds.nextPreKeyId;

        // Second call asks for 10 more — but we already have 10 unuploaded, so 0 new keys generated
        PreKeyManager.generate(creds, store, 10);
        assertThat(creds.nextPreKeyId).isEqualTo(afterFirst);
        assertThat(store.size()).isEqualTo(10);
    }

    @Test
    void markUploadedAdvancesFirstUnuploaded() {
        AuthCreds creds = AuthCreds.generate();
        InMemorySignalKeyStore store = new InMemorySignalKeyStore();
        var keys = PreKeyManager.generate(creds, store, 30);
        int max = keys.keySet().stream().max(Integer::compareTo).orElseThrow();

        PreKeyManager.markUploaded(creds, keys);
        assertThat(creds.firstUnuploadedPreKeyId).isEqualTo(max + 1);
    }

    @Test
    void uploadStanzaHasExpectedShape() {
        AuthCreds creds = AuthCreds.generate();
        InMemorySignalKeyStore store = new InMemorySignalKeyStore();
        var keys = PreKeyManager.generate(creds, store, 3);

        BinaryNode iq = PreKeyManager.buildUploadStanza("abc123", creds, keys);

        assertThat(iq.tag()).isEqualTo("iq");
        assertThat(iq.attrs()).containsEntry("xmlns", "encrypt").containsEntry("type", "set");
        List<BinaryNode> kids = iq.childrenList();
        assertThat(kids.stream().map(BinaryNode::tag).toList())
            .containsExactly("registration", "type", "identity", "list", "skey");

        BinaryNode reg = kids.get(0);
        assertThat((byte[]) reg.content()).hasSize(4);                  // uint32 BE

        BinaryNode type = kids.get(1);
        assertThat((byte[]) type.content()).containsExactly(0x05);      // KEY_BUNDLE_TYPE

        BinaryNode ident = kids.get(2);
        assertThat((byte[]) ident.content()).hasSize(32);               // raw Curve25519 pub

        BinaryNode list = kids.get(3);
        assertThat(list.childrenList()).hasSize(3);                     // 3 pre-keys requested
        BinaryNode firstKey = list.childrenList().get(0);
        assertThat(firstKey.tag()).isEqualTo("key");
        assertThat(firstKey.childrenList().stream().map(BinaryNode::tag).toList())
            .containsExactly("id", "value");
        assertThat((byte[]) firstKey.childrenList().get(0).content()).hasSize(3); // uint24 BE id
        assertThat((byte[]) firstKey.childrenList().get(1).content()).hasSize(32);

        BinaryNode skey = kids.get(4);
        assertThat(skey.childrenList().stream().map(BinaryNode::tag).toList())
            .containsExactly("id", "value", "signature");
        assertThat((byte[]) skey.childrenList().get(2).content()).hasSize(64);     // XEdDSA sig
    }

    @Test
    void uploadStanzaRoundTripsThroughBinaryCodec() {
        AuthCreds creds = AuthCreds.generate();
        InMemorySignalKeyStore store = new InMemorySignalKeyStore();
        var keys = PreKeyManager.generate(creds, store, 5);
        BinaryNode iq = PreKeyManager.buildUploadStanza("rt-test", creds, keys);

        byte[] bytes = BinaryEncoder.encode(iq);
        BinaryNode decoded = BinaryDecoder.decode(bytes);

        assertThat(decoded.tag()).isEqualTo("iq");
        assertThat(decoded.attr("xmlns")).isEqualTo("encrypt");
        assertThat(decoded.child("list").childrenList()).hasSize(5);
        assertThat(decoded.child("skey").child("signature").bytesContent()).hasSize(64);
    }
}
