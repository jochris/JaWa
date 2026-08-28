// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.domain.store;

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


import java.io.IOException;

/** Pluggable persistence for {@link AuthCreds}. */
public interface AuthStore {
    /** Load existing creds or {@code null} if none. */
    AuthCreds load() throws IOException;

    /** Persist creds atomically. */
    void save(AuthCreds c) throws IOException;

    /** {@code true} if creds exist and contain a paired account (no QR needed). */
    boolean isPaired() throws IOException;
}