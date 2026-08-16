// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.store;

import id.jawa.types.Jid;
import id.jawa.util.Crypto;

import java.io.Serializable;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores device credentials, Noise keypair, identity keypair, and pre-keys.
 * Ported directly from whatsmeow store.Device.
 */
public class DeviceStore implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Crypto.KeyPair noiseKey;
    private final Crypto.KeyPair identityKey;
    private final int registrationId;
    private Jid me = Jid.EMPTY;
    private String pushName = "JaWa";

    private final Map<Integer, Crypto.KeyPair> preKeys = new ConcurrentHashMap<>();
    private Crypto.KeyPair signedPreKey;
    private int signedPreKeyId;

    public DeviceStore() {
        this.noiseKey = Crypto.generateCurve25519KeyPair();
        this.identityKey = Crypto.generateCurve25519KeyPair();
        this.registrationId = new SecureRandom().nextInt(16380) + 1;
        this.signedPreKey = Crypto.generateCurve25519KeyPair();
        this.signedPreKeyId = 1;
    }

    public Crypto.KeyPair noiseKey() { return noiseKey; }
    public Crypto.KeyPair identityKey() { return identityKey; }
    public int registrationId() { return registrationId; }

    public Jid me() { return me; }
    public void setMe(Jid me) { this.me = me; }

    public String pushName() { return pushName; }
    public void setPushName(String pushName) { this.pushName = pushName; }

    public Crypto.KeyPair signedPreKey() { return signedPreKey; }
    public int signedPreKeyId() { return signedPreKeyId; }

    public Map<Integer, Crypto.KeyPair> preKeys() { return preKeys; }

    public boolean isPaired() { return me != null && !me.isEmpty(); }
}
