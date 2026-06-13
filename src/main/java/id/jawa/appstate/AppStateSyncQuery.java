// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.appstate;

import com.google.protobuf.InvalidProtocolBufferException;
import id.jawa.binary.BinaryNode;
import id.jawa.proto.Wa;
import id.jawa.util.Jid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build the {@code <iq xmlns="w:sync:app:state" type="set">} stanza that asks the
 * server for snapshot + patches for a given collection, and parse the response into
 * a {@link PatchList}.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link #buildSnapshotRequest(String, PatchName)} — first sync, fetches a
 *       full snapshot (server may inline or hand us an {@code ExternalBlobReference}).</li>
 *   <li>{@link #buildPatchRequest(String, PatchName, long)} — incremental sync from
 *       a known {@code version}; fetches just the patches since then.</li>
 * </ul>
 */
public final class AppStateSyncQuery {

    private AppStateSyncQuery() {}

    public static BinaryNode buildSnapshotRequest(String iqId, PatchName name) {
        return buildIq(iqId, name, /* fromVersion= */ -1L, /* returnSnapshot= */ true);
    }

    public static BinaryNode buildPatchRequest(String iqId, PatchName name, long fromVersion) {
        return buildIq(iqId, name, fromVersion, false);
    }

    private static BinaryNode buildIq(String iqId, PatchName name, long fromVersion, boolean returnSnapshot) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("name", name.wire);
        attrs.put("return_snapshot", returnSnapshot ? "true" : "false");
        if (!returnSnapshot) attrs.put("version", Long.toString(fromVersion));

        BinaryNode collection = new BinaryNode("collection", attrs, null);
        BinaryNode sync = new BinaryNode("sync", Map.of(), List.of(collection));
        return new BinaryNode("iq",
            Map.of(
                "id",    iqId,
                "type",  "set",
                "xmlns", "w:sync:app:state",
                "to",    Jid.SERVER_WHATSAPP
            ),
            List.of(sync));
    }

    /** Parsed response — what {@link AppStateProcessor} consumes. */
    public record PatchList(
        PatchName name,
        boolean hasMorePatches,
        Wa.SyncdSnapshot snapshot,          // null when none / external-only
        List<Wa.SyncdPatch> patches,
        Wa.ExternalBlobReference externalSnapshot  // non-null when the snapshot lives off-IQ
    ) {}

    public static PatchList parseResponse(BinaryNode iqResult) {
        BinaryNode sync = iqResult.child("sync");
        if (sync == null) return null;
        BinaryNode collection = sync.child("collection");
        if (collection == null) return null;

        PatchName name = PatchName.fromWire(collection.attr("name"));
        boolean hasMore = "true".equals(collection.attr("has_more_patches"));

        Wa.SyncdSnapshot snapshot = null;
        Wa.ExternalBlobReference externalSnapshot = null;
        BinaryNode snapshotNode = collection.child("snapshot");
        if (snapshotNode != null && snapshotNode.bytesContent() != null) {
            try {
                Wa.ExternalBlobReference ref = Wa.ExternalBlobReference.parseFrom(snapshotNode.bytesContent());
                if (!ref.getDirectPath().isEmpty()) externalSnapshot = ref;
                else snapshot = Wa.SyncdSnapshot.parseFrom(snapshotNode.bytesContent());
            } catch (InvalidProtocolBufferException ignored) {
                try {
                    snapshot = Wa.SyncdSnapshot.parseFrom(snapshotNode.bytesContent());
                } catch (InvalidProtocolBufferException ignored2) { /* fall through */ }
            }
        }

        List<Wa.SyncdPatch> patches = new ArrayList<>();
        BinaryNode patchesNode = collection.child("patches");
        if (patchesNode != null) {
            for (BinaryNode patchNode : patchesNode.childrenList()) {
                if (!"patch".equals(patchNode.tag())) continue;
                byte[] bytes = patchNode.bytesContent();
                if (bytes == null) continue;
                try { patches.add(Wa.SyncdPatch.parseFrom(bytes)); }
                catch (InvalidProtocolBufferException ignored) { /* skip */ }
            }
        }

        return new PatchList(name, hasMore, snapshot, patches, externalSnapshot);
    }
}
