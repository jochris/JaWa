// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.feature.appstate;

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
 * App-state collection names. The five categories partition WhatsApp's per-account
 * state by sensitivity and cadence so patches can sync independently.
 */
public enum PatchName {
    /** Push name, locale, security notification settings. */
    CRITICAL_BLOCK("critical_block"),
    /** Contact list. */
    CRITICAL_UNBLOCK_LOW("critical_unblock_low"),
    /** Chat pin / archive state, time format, etc. */
    REGULAR_LOW("regular_low"),
    /** Mute, starred messages, status privacy, etc. */
    REGULAR_HIGH("regular_high"),
    /** Quick replies, label edits, broadcast jids, etc. */
    REGULAR("regular");

    public final String wire;

    PatchName(String wire) { this.wire = wire; }

    public static PatchName fromWire(String s) {
        for (PatchName p : values()) if (p.wire.equals(s)) return p;
        return null;
    }
}