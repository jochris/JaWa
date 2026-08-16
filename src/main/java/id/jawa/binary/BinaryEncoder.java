/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.jawa.binary;

import id.jawa.types.Jid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Encodes a {@link BinaryNode} into WhatsApp's binary stream format, matching whatsmeow binary.encoder.
 */
public final class BinaryEncoder {

    public byte[] encode(BinaryNode node) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        writeNode(out, node);
        return out.toByteArray();
    }

    private void writeNode(ByteArrayOutputStream out, BinaryNode node) {
        if ("0".equals(node.tag())) {
            out.write(WaTags.LIST_8);
            out.write(WaTags.LIST_EMPTY);
            return;
        }

        int attrCount = countAttributes(node.attrs());
        int hasContent = (node.content() != null) ? 1 : 0;
        int listSize = (2 * attrCount) + 1 + hasContent;

        writeListStart(out, listSize);
        writeString(out, node.tag());
        writeAttributes(out, node.attrs());

        if (node.content() != null) {
            writeContent(out, node.content());
        }
    }

    private int countAttributes(Map<String, Object> attrs) {
        int count = 0;
        for (Object val : attrs.values()) {
            if (val != null && !"".equals(val)) count++;
        }
        return count;
    }

    private void writeListStart(ByteArrayOutputStream out, int size) {
        if (size == 0) {
            out.write(WaTags.LIST_EMPTY);
        } else if (size < 256) {
            out.write(WaTags.LIST_8);
            out.write(size);
        } else {
            out.write(WaTags.LIST_16);
            out.write((size >> 8) & 0xFF);
            out.write(size & 0xFF);
        }
    }

    private void writeAttributes(ByteArrayOutputStream out, Map<String, Object> attrs) {
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            Object val = entry.getValue();
            if (val == null || "".equals(val)) continue;
            writeString(out, entry.getKey());
            writeContent(out, val);
        }
    }

    private void writeContent(ByteArrayOutputStream out, Object content) {
        switch (content) {
            case null -> out.write(WaTags.LIST_EMPTY);
            case Jid jid -> writeJid(out, jid);
            case String s -> writeString(out, s);
            case Boolean b -> writeString(out, Boolean.toString(b));
            case Number n -> writeString(out, n.toString());
            case byte[] b -> writeBytes(out, b);
            case List<?> list -> {
                writeListStart(out, list.size());
                for (Object item : list) {
                    if (item instanceof BinaryNode childNode) {
                        writeNode(out, childNode);
                    }
                }
            }
            default -> throw new IllegalArgumentException("unsupported content type: " + content.getClass());
        }
    }

    private void writeString(ByteArrayOutputStream out, String str) {
        if (str == null) {
            out.write(WaTags.LIST_EMPTY);
            return;
        }

        int[] tokenIndex = WaTokens.INDEX.get(str);
        if (tokenIndex != null) {
            if (tokenIndex[0] == -1) {
                out.write(tokenIndex[1]);
            } else {
                out.write(WaTags.DICTIONARY_0 + tokenIndex[0]);
                out.write(tokenIndex[1]);
            }
            return;
        }

        Jid jid = Jid.parse(str);
        if (jid != null && !jid.isEmpty() && !str.equals(jid.user())) {
            writeJid(out, jid);
            return;
        }

        writeStringRaw(out, str);
    }

    private void writeJid(ByteArrayOutputStream out, Jid jid) {
        if ((jid.isUser() || jid.server().equals(Jid.HIDDEN_USER_SERVER)) && jid.device() > 0
                || jid.server().equals(Jid.HOSTED_SERVER) || jid.server().equals(Jid.HOSTED_LID_SERVER)) {
            out.write(WaTags.AD_JID);
            out.write(jid.actualAgent());
            out.write(jid.device());
            writeString(out, jid.user());
        } else {
            out.write(WaTags.JID_PAIR);
            if (jid.user().isEmpty()) {
                out.write(WaTags.LIST_EMPTY);
            } else {
                writeContent(out, jid.user());
            }
            writeContent(out, jid.server());
        }
    }

    private void writeStringRaw(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeBytes(out, bytes);
    }

    private void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        int len = bytes.length;
        if (len < 256) {
            out.write(WaTags.BINARY_8);
            out.write(len);
        } else if (len < (1 << 20)) {
            out.write(WaTags.BINARY_20);
            out.write((len >> 16) & 0x0F);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(WaTags.BINARY_32);
            out.write((len >> 24) & 0xFF);
            out.write((len >> 16) & 0xFF);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        try {
            out.write(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write bytes", e);
        }
    }
}
