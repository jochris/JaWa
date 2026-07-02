// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.core;

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
