// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.domain.model;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;


import id.jawa.protocol.crypto.Curve25519;
import id.jawa.protocol.crypto.KeyPair25519;

/**
 * A Signal-style signed pre-key: a Curve25519 keypair with a signature over its
 * (KEY_BUNDLE_TYPE-prefixed) public key produced by the long-term identity key.
 */
public record SignedPreKey(int keyId, KeyPair25519 keyPair, byte[] signature) {

    public static SignedPreKey generate(int keyId, KeyPair25519 identityKey) {
        KeyPair25519 pre = Curve25519.generateKeyPair();
        byte[] toSign = Curve25519.prependType(pre.publicKey());
        byte[] sig = Curve25519.sign(identityKey.privateKey(), toSign);
        return new SignedPreKey(keyId, pre, sig);
    }
}