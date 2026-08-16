// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.events;

import id.jawa.types.Jid;

/**
 * Event fired when a WhatsApp text message is received.
 */
public record MessageEvent(
    Jid chat,
    Jid sender,
    String messageId,
    String text,
    boolean isGroup,
    long timestamp
) {}
