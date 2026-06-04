// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.signal;

import org.whispersystems.libsignal.groups.SenderKeyName;
import org.whispersystems.libsignal.groups.state.SenderKeyRecord;
import org.whispersystems.libsignal.groups.state.SenderKeyStore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory {@link SenderKeyStore} for libsignal's group {@code GroupCipher}.
 *
 * <p>libsignal-java ships the {@code SenderKeyStore} interface but no built-in
 * in-memory implementation (unlike its session/identity/pre-key counterparts), so JaWa
 * provides one. Each {@link SenderKeyName} (group + sender device) maps to one
 * {@link SenderKeyRecord} containing the sender-chain state.
 *
 * <p>Records are serialised back and forth to round-trip through libsignal — that gives
 * us value semantics (defensive copies on both load and store) and keeps the door open
 * for a future on-disk backend that just swaps the map for a key-value store.
 *
 * <p>Mirrors whatsmeow's {@code store.SenderKeys} memory backend.
 */
public final class InMemorySenderKeyStore implements SenderKeyStore {

    private final ConcurrentMap<SenderKeyName, byte[]> records = new ConcurrentHashMap<>();

    @Override
    public void storeSenderKey(SenderKeyName senderKeyName, SenderKeyRecord record) {
        records.put(senderKeyName, record.serialize());
    }

    @Override
    public SenderKeyRecord loadSenderKey(SenderKeyName senderKeyName) {
        byte[] serialized = records.get(senderKeyName);
        if (serialized == null) return new SenderKeyRecord();
        try {
            return new SenderKeyRecord(serialized);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("corrupt sender-key record for " + senderKeyName, e);
        }
    }

    /** Returns {@code true} when no sender-key record has been stored for {@code name}. */
    public boolean isEmpty(SenderKeyName name) {
        byte[] s = records.get(name);
        return s == null || s.length == 0;
    }
}
