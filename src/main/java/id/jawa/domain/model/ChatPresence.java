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


/**
 * Represents composing states for chat presence indicator.
 */
public enum ChatPresence {
    /** Bot/User is typing text. */
    COMPOSING,
    /** Bot/User is recording audio/voice note. */
    RECORDING,
    /** Indicator is paused/idle. */
    PAUSED
}