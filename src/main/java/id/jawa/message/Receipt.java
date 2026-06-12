// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import id.jawa.binary.BinaryNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed {@code <receipt>} stanza. WhatsApp uses these to signal lifecycle events
 * for our outgoing messages (delivered, read, played by peer) as well as some
 * meta-traffic (server retry hints, server-side errors).
 *
 * <p>Possible {@link #type} values:
 * <ul>
 *   <li>{@code null} — delivery receipt (single grey tick on the sender's side)</li>
 *   <li>{@code "read"} — recipient opened the chat (blue ticks)</li>
 *   <li>{@code "played"} — recipient played a voice note</li>
 *   <li>{@code "retry"} — peer couldn't decrypt; expects us to re-encrypt with fresh prekeys</li>
 *   <li>{@code "server-error"} — server-side decrypt failure</li>
 *   <li>{@code "sender"} — confirmation from sender's own group-send relay</li>
 * </ul>
 *
 * <p>For batched receipts (multiple ids in one stanza), {@link #msgIds} carries the
 * full list — the receipt's top-level {@code id} attr is the first entry, the rest
 * come from {@code <list><item id=.../>...</list>} children.
 */
public record Receipt(
    String type,
    String chatJid,
    String senderJid,
    String recipientJid,
    List<String> msgIds,
    long timestamp
) {

    /** Parse a {@code <receipt>} BinaryNode. Returns {@code null} if required attrs are missing. */
    public static Receipt parse(BinaryNode node) {
        String id = node.attr("id");
        String from = node.attr("from");
        if (id == null || from == null) return null;

        List<String> ids = new ArrayList<>();
        ids.add(id);
        BinaryNode list = node.child("list");
        if (list != null) {
            for (BinaryNode item : list.childrenList()) {
                if (!"item".equals(item.tag())) continue;
                String iid = item.attr("id");
                if (iid != null) ids.add(iid);
            }
        }

        long ts = 0L;
        String tAttr = node.attr("t");
        if (tAttr != null) {
            try { ts = Long.parseLong(tAttr); }
            catch (NumberFormatException ignored) {}
        }

        return new Receipt(
            node.attr("type"),
            from,
            node.attr("participant"),
            node.attr("recipient"),
            List.copyOf(ids),
            ts
        );
    }
}
