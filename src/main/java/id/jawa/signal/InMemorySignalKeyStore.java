// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.signal;

import id.jawa.util.crypto.KeyPair25519;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link SignalKeyStore}.
 *
 * <p>Persistence is a TODO — pluggable via subclassing or a future file-backed
 * implementation. For now, sessions only survive process lifetime.
 */
public final class InMemorySignalKeyStore implements SignalKeyStore {

    private final Map<Integer, KeyPair25519> preKeys = new ConcurrentHashMap<>();

    @Override
    public KeyPair25519 getPreKey(int id) { return preKeys.get(id); }

    @Override
    public void putPreKey(int id, KeyPair25519 kp) { preKeys.put(id, kp); }

    @Override
    public void removePreKey(int id) { preKeys.remove(id); }

    @Override
    public Map<Integer, KeyPair25519> getPreKeysInRange(int from, int toExclusive) {
        Map<Integer, KeyPair25519> out = new TreeMap<>();
        for (int id = from; id < toExclusive; id++) {
            KeyPair25519 kp = preKeys.get(id);
            if (kp != null) out.put(id, kp);
        }
        return new LinkedHashMap<>(out); // stable insertion order from TreeMap iteration
    }

    public int size() { return preKeys.size(); }
}
