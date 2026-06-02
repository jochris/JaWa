// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.signal;

import id.jawa.util.crypto.KeyPair25519;

import java.util.Map;

/**
 * Ephemeral cryptographic state separate from the long-term {@link id.jawa.store.AuthCreds}.
 *
 * <p>Holds:
 * <ul>
 *   <li>One-time pre-keys (uploaded then consumed by peers)
 *   <li>(later) Signal sessions keyed by {@code SignalProtocolAddress}
 *   <li>(later) Sender keys for group messaging
 *   <li>(later) App-state sync keys
 * </ul>
 *
 * <p>This is the JaWa analogue of Baileys' {@code SignalKeyStore} type.
 */
public interface SignalKeyStore {

    /** Get a one-time pre-key by id, or {@code null} if absent / already consumed. */
    KeyPair25519 getPreKey(int id);

    /** Store a one-time pre-key. */
    void putPreKey(int id, KeyPair25519 kp);

    /** Mark a pre-key as consumed. */
    void removePreKey(int id);

    /** Return all pre-keys in {@code [from, toExclusive)} that are present (in id order). */
    Map<Integer, KeyPair25519> getPreKeysInRange(int from, int toExclusive);
}
