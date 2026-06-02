// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.signal;

import id.jawa.binary.BinaryNode;
import id.jawa.core.WaConstants;
import id.jawa.store.AuthCreds;
import id.jawa.util.Jid;
import id.jawa.util.crypto.Curve25519;
import id.jawa.util.crypto.KeyPair25519;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates and packages one-time pre-keys for upload to the server.
 *
 * <p>Wire format of the upload IQ (ports {@code getNextPreKeysNode} from Baileys'
 * {@code Utils/signal.ts}):
 *
 * <pre>{@code
 * <iq xmlns="encrypt" type="set" to="s.whatsapp.net" id="...">
 *   <registration>(regId, 4 bytes BE)</registration>
 *   <type>(KEY_BUNDLE_TYPE = 0x05)</type>
 *   <identity>(signedIdentityKey.public, 32 bytes)</identity>
 *   <list>
 *     <key>
 *       <id>(preKeyId, 3 bytes BE)</id>
 *       <value>(pub, 32 bytes)</value>
 *     </key>
 *     ... (N pre-keys)
 *   </list>
 *   <skey>
 *     <id>(signedPreKey.keyId, 3 bytes BE)</id>
 *     <value>(signedPreKey.pub, 32 bytes)</value>
 *     <signature>(signedPreKey.sig, 64 bytes)</signature>
 *   </skey>
 * </iq>
 * }</pre>
 */
public final class PreKeyManager {

    private PreKeyManager() {}

    /**
     * Ensure {@code count} unuploaded pre-keys exist (generate any missing), store them in the
     * given {@link SignalKeyStore}, advance {@link AuthCreds#nextPreKeyId} accordingly, and
     * return the slice ready for upload.
     */
    public static Map<Integer, KeyPair25519> generate(AuthCreds creds, SignalKeyStore store, int count) {
        int available = creds.nextPreKeyId - creds.firstUnuploadedPreKeyId;
        int remaining = count - available;
        if (remaining > 0) {
            for (int i = 0; i < remaining; i++) {
                int id = creds.nextPreKeyId + i;
                store.putPreKey(id, Curve25519.generateKeyPair());
            }
            creds.nextPreKeyId += remaining;
        }
        Map<Integer, KeyPair25519> out = new LinkedHashMap<>(count);
        for (int id = creds.firstUnuploadedPreKeyId; id < creds.firstUnuploadedPreKeyId + count; id++) {
            KeyPair25519 kp = store.getPreKey(id);
            if (kp != null) out.put(id, kp);
        }
        return out;
    }

    /**
     * Build the {@code <iq xmlns=encrypt>} upload stanza. {@code id} is the stanza id; the
     * caller must remember it so the {@code <iq type=result>} response can be correlated.
     */
    public static BinaryNode buildUploadStanza(String id, AuthCreds creds, Map<Integer, KeyPair25519> preKeys) {
        java.util.List<BinaryNode> keyList = new java.util.ArrayList<>(preKeys.size());
        for (var e : preKeys.entrySet()) {
            keyList.add(new BinaryNode("key", Map.of(), List.of(
                new BinaryNode("id",    Map.of(), encodeUintBE(e.getKey(), 3)),
                new BinaryNode("value", Map.of(), e.getValue().publicKey())
            )));
        }

        BinaryNode listNode = new BinaryNode("list", Map.of(), keyList);

        BinaryNode skey = new BinaryNode("skey", Map.of(), List.of(
            new BinaryNode("id",        Map.of(), encodeUintBE(creds.signedPreKey.keyId(), 3)),
            new BinaryNode("value",     Map.of(), creds.signedPreKey.keyPair().publicKey()),
            new BinaryNode("signature", Map.of(), creds.signedPreKey.signature())
        ));

        return new BinaryNode("iq",
            Map.of(
                "xmlns", "encrypt",
                "type",  "set",
                "to",    Jid.SERVER_WHATSAPP,
                "id",    id
            ),
            List.of(
                new BinaryNode("registration", Map.of(), encodeUintBE(creds.registrationId, 4)),
                new BinaryNode("type",         Map.of(), new byte[] { Curve25519.KEY_BUNDLE_TYPE }),
                new BinaryNode("identity",     Map.of(), creds.signedIdentityKey.publicKey()),
                listNode,
                skey
            ));
    }

    /** Mark the freshly-uploaded pre-keys as consumed (server will hand them out to peers). */
    public static void markUploaded(AuthCreds creds, Map<Integer, KeyPair25519> uploaded) {
        if (uploaded.isEmpty()) return;
        int maxId = 0;
        for (Integer id : uploaded.keySet()) if (id > maxId) maxId = id;
        creds.firstUnuploadedPreKeyId = maxId + 1;
    }

    @SuppressWarnings("unused")
    private static byte[] encodeUintBE(int value, int width) {
        byte[] out = new byte[width];
        for (int i = 0; i < width; i++) {
            out[width - 1 - i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
        return out;
    }
}
