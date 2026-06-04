// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import id.jawa.binary.BinaryNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds and parses the {@code <iq xmlns=w:g2><participating>} group-list query.
 *
 * <p>Wire shape (ports whatsmeow {@code GetJoinedGroups} / Baileys
 * {@code groupsParticipating}):
 *
 * <pre>{@code
 * <iq to="@g.us" xmlns="w:g2" type="get" id="...">
 *   <participating>
 *     <participants/>
 *     <description/>
 *   </participating>
 * </iq>
 *
 * <iq type="result" ...>
 *   <groups>
 *     <group jid="120363...@g.us" subject="..." s_t="<unix>" creation="<unix>" ...>
 *       <participant jid="6281...:0@s.whatsapp.net" lid="..." />
 *       ...
 *     </group>
 *     ...
 *   </groups>
 * </iq>
 * }</pre>
 */
public final class GroupListQuery {

    private GroupListQuery() {}

    /** A group the logged-in user belongs to. */
    public record GroupInfo(
        String jid,
        String subject,
        long creationTimestamp,
        long subjectTimestamp,
        String owner,
        List<String> participantJids
    ) {}

    public static BinaryNode buildQuery(String iqId) {
        BinaryNode participating = new BinaryNode("participating", Map.of(),
            List.of(
                new BinaryNode("participants", Map.of(), null),
                new BinaryNode("description",  Map.of(), null)
            ));
        return new BinaryNode("iq",
            Map.of(
                "to",    "@" + id.jawa.util.Jid.SERVER_GROUP,
                "xmlns", "w:g2",
                "type",  "get",
                "id",    iqId
            ),
            List.of(participating));
    }

    public static List<GroupInfo> parseResponse(BinaryNode iqResponse) {
        List<GroupInfo> out = new ArrayList<>();
        BinaryNode groups = iqResponse.child("groups");
        if (groups == null) return out;
        for (BinaryNode g : groups.childrenList()) {
            if (!"group".equals(g.tag())) continue;
            String groupId = g.attr("id");
            String creator = g.attr("creator");
            String subject = g.attr("subject", "");
            long creation = parseLong(g.attr("creation"), 0);
            long subjectTs = parseLong(g.attr("s_t"), 0);
            String groupJid = (groupId != null ? groupId : "") + "@" + id.jawa.util.Jid.SERVER_GROUP;
            List<String> participants = new ArrayList<>();
            for (BinaryNode p : g.childrenList()) {
                if (!"participant".equals(p.tag())) continue;
                String pj = p.attr("jid");
                if (pj != null) participants.add(pj);
            }
            out.add(new GroupInfo(groupJid, subject, creation, subjectTs, creator, participants));
        }
        return out;
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return fallback; }
    }
}
