// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.protocol.crypto;

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


import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/** HKDF-SHA256 (RFC 5869) — extract-and-expand in one shot. */
public final class Hkdf {
    private Hkdf() {}

    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator g = new HKDFBytesGenerator(new SHA256Digest());
        g.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[length];
        g.generateBytes(out, 0, length);
        return out;
    }

    /** Convenience for Noise's HKDF where info is empty. */
    public static byte[] derive(byte[] ikm, byte[] salt, int length) {
        return derive(ikm, salt, new byte[0], length);
    }
}