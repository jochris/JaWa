// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.appstate;

import id.jawa.proto.Wa;
import id.jawa.util.crypto.AesCbc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decrypt + verify {@code SyncdMutation}s coming back from {@code w:sync:app:state}.
 * Caller hands us the raw {@code SyncdMutation} (already parsed from the IQ response
 * via protobuf); we look up the key, AES-CBC decrypt the value blob, and unmarshal
 * the inner {@code SyncActionData}.
 *
 * <p>MAC validation is off by default. Turn it on for production via the
 * {@link #decode(Wa.SyncdMutation, boolean)} overload — verifies both the indexMAC
 * (HMAC-SHA256 over the action's JSON index) and the valueMAC (HMAC-SHA512[:32]
 * over operation || keyId || ciphertext || keyIdLen).
 */
public final class AppStateProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(AppStateProcessor.class);

    private final FileAppStateKeyStorage keyStorage;

    public AppStateProcessor(FileAppStateKeyStorage keyStorage) {
        this.keyStorage = keyStorage;
    }

    /** A single decoded mutation — what the bot consumer cares about. */
    public record Mutation(
        Wa.SyncdMutation.SyncdOperation operation,
        Wa.SyncActionData action,
        byte[] indexMac,
        byte[] valueMac
    ) {}

    public Mutation decode(Wa.SyncdMutation mutation) {
        return decode(mutation, false);
    }

    public Mutation decode(Wa.SyncdMutation mutation, boolean validateMacs) {
        byte[] keyId = mutation.getRecord().getKeyId().getId().toByteArray();
        AppStateKey key = keyStorage.get(keyId);
        if (key == null) {
            throw new IllegalStateException("missing app-state key id=" + hex(keyId));
        }
        AppStateKey.Expanded ek = key.expand();

        byte[] blob = mutation.getRecord().getValue().getBlob().toByteArray();
        if (blob.length < 32 + 16) {
            throw new IllegalStateException("blob too short for IV + MAC: len=" + blob.length);
        }

        byte[] valueMac = Arrays.copyOfRange(blob, blob.length - 32, blob.length);
        byte[] content  = Arrays.copyOfRange(blob, 0, blob.length - 32);

        if (validateMacs) {
            byte[] expected = generateContentMac(mutation.getOperation(), content, keyId, ek.valueMac());
            if (!constantTimeEquals(expected, valueMac)) {
                throw new IllegalStateException("value MAC mismatch");
            }
        }

        byte[] iv         = Arrays.copyOfRange(content, 0, 16);
        byte[] ciphertext = Arrays.copyOfRange(content, 16, content.length);

        byte[] plaintext = AesCbc.decrypt(ek.valueEnc(), iv, ciphertext);

        Wa.SyncActionData action;
        try {
            action = Wa.SyncActionData.parseFrom(plaintext);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalStateException("malformed SyncActionData", e);
        }

        byte[] indexMac = mutation.getRecord().getIndex().getBlob().toByteArray();
        if (validateMacs) {
            byte[] expected = hmacSha256(ek.index(), action.getIndex().toByteArray());
            if (!constantTimeEquals(expected, indexMac)) {
                throw new IllegalStateException("index MAC mismatch");
            }
        }

        return new Mutation(mutation.getOperation(), action, indexMac, valueMac);
    }

    /** Decode every mutation in a {@code SyncdPatch}, skipping any that fail to decrypt. */
    public List<Mutation> decodePatch(Wa.SyncdPatch patch, boolean validateMacs) {
        List<Mutation> out = new ArrayList<>();
        for (Wa.SyncdMutation m : patch.getMutationsList()) {
            try {
                out.add(decode(m, validateMacs));
            } catch (RuntimeException e) {
                LOG.warn("Skip un-decodable patch mutation: {}", e.toString());
            }
        }
        return out;
    }

    /** Decode every record in a {@code SyncdSnapshot}, wrapping each as a SET mutation. */
    public List<Mutation> decodeSnapshot(Wa.SyncdSnapshot snapshot, boolean validateMacs) {
        List<Mutation> out = new ArrayList<>();
        for (Wa.SyncdRecord rec : snapshot.getRecordsList()) {
            Wa.SyncdMutation wrap = Wa.SyncdMutation.newBuilder()
                .setOperation(Wa.SyncdMutation.SyncdOperation.SET)
                .setRecord(rec)
                .build();
            try {
                out.add(decode(wrap, validateMacs));
            } catch (RuntimeException e) {
                LOG.warn("Skip un-decodable snapshot record: {}", e.toString());
            }
        }
        return out;
    }

    private static byte[] generateContentMac(Wa.SyncdMutation.SyncdOperation op,
                                             byte[] content, byte[] keyId, byte[] macKey) {
        byte[] operationBytes = new byte[] { (byte) (op.getNumber() + 1) };
        byte[] keyDataLen = uint64Be(keyId.length + 1);
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(macKey, "HmacSHA512"));
            mac.update(operationBytes);
            mac.update(keyId);
            mac.update(content);
            mac.update(keyDataLen);
            byte[] full = mac.doFinal();
            return Arrays.copyOfRange(full, 0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA512 init failed", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 init failed", e);
        }
    }

    private static byte[] uint64Be(long value) {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return out;
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
