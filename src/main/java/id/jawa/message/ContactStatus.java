// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

/**
 * Represents the WhatsApp registration status of a contact.
 */
public record ContactStatus(
    String jid,
    boolean isOnWhatsApp
) {}
