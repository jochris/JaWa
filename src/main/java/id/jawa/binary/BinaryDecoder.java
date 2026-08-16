// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.binary;

import id.jawa.types.Jid;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes WhatsApp binary protocol bytes into a {@link BinaryNode}, directly matching whatsmeow binary.decoder.
 */
public final class BinaryDecoder {

    public BinaryNode decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        ByteBuffer buf = ByteBuffer.wrap(data);

        // Skip leading zero byte if present
        if (buf.hasRemaining() && buf.get(0) == 0) {
            buf.get();
        }

        return readNode(buf);
    }

    private BinaryNode readNode(ByteBuffer buf) {
        int listSize = readListSize(buf);
        String tag = readString(buf);
        if (tag == null) return null;

        int attrCount = (listSize - 1) / 2;
        Map<String, Object> attrs = new LinkedHashMap<>(attrCount);
        for (int i = 0; i < attrCount; i++) {
            String key = readString(buf);
            Object val = readContent(buf);
            if (key != null && val != null) attrs.put(key, val);
        }

        Object content = null;
        if (listSize % 2 == 0) {
            int tagType = buf.get() & 0xFF;
            if (isListTag(tagType)) {
                int childCount = readListSizeWithTag(buf, tagType);
                List<BinaryNode> children = new ArrayList<>(childCount);
                for (int i = 0; i < childCount; i++) {
                    children.add(readNode(buf));
                }
                content = children;
            } else if (tagType == WaTags.BINARY_8 || tagType == WaTags.BINARY_20 || tagType == WaTags.BINARY_32) {
                content = readBytesWithTag(buf, tagType);
            } else {
                buf.position(buf.position() - 1);
                content = readContent(buf);
            }
        }

        return new BinaryNode(tag, attrs, content);
    }

    private Object readContent(ByteBuffer buf) {
        if (!buf.hasRemaining()) return null;
        int tag = buf.get() & 0xFF;

        if (tag == WaTags.LIST_EMPTY) return null;
        if (tag >= 1 && tag < WaTokens.SINGLE.length) {
            return WaTokens.SINGLE[tag];
        }
        if (tag >= WaTags.DICTIONARY_0 && tag <= WaTags.DICTIONARY_3) {
            int dict = tag - WaTags.DICTIONARY_0;
            int idx = buf.get() & 0xFF;
            if (dict < WaTokens.DOUBLE.length && idx < WaTokens.DOUBLE[dict].length) {
                return WaTokens.DOUBLE[dict][idx];
            }
        }
        if (tag == WaTags.JID_PAIR) {
            String user = readString(buf);
            String server = readString(buf);
            return Jid.of(user != null ? user : "", server != null ? server : Jid.DEFAULT_USER_SERVER);
        }
        if (tag == WaTags.AD_JID) {
            int agent = buf.get() & 0xFF;
            int device = buf.get() & 0xFF;
            String user = readString(buf);
            return Jid.ofAD(user != null ? user : "", agent, device);
        }
        if (tag == WaTags.BINARY_8 || tag == WaTags.BINARY_20 || tag == WaTags.BINARY_32) {
            return readBytesWithTag(buf, tag);
        }

        return null;
    }

    private int readListSize(ByteBuffer buf) {
        int tag = buf.get() & 0xFF;
        return readListSizeWithTag(buf, tag);
    }

    private int readListSizeWithTag(ByteBuffer buf, int tag) {
        return switch (tag) {
            case WaTags.LIST_EMPTY -> 0;
            case WaTags.LIST_8 -> buf.get() & 0xFF;
            case WaTags.LIST_16 -> buf.getShort() & 0xFFFF;
            default -> throw new IllegalArgumentException("invalid list tag: " + tag);
        };
    }

    private boolean isListTag(int tag) {
        return tag == WaTags.LIST_EMPTY || tag == WaTags.LIST_8 || tag == WaTags.LIST_16;
    }

    private String readString(ByteBuffer buf) {
        Object res = readContent(buf);
        if (res instanceof String s) return s;
        if (res instanceof Jid j) return j.toString();
        if (res instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        return null;
    }

    private byte[] readBytesWithTag(ByteBuffer buf, int tag) {
        int len = switch (tag) {
            case WaTags.BINARY_8 -> buf.get() & 0xFF;
            case WaTags.BINARY_20 -> ((buf.get() & 0x0F) << 16) | ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
            case WaTags.BINARY_32 -> buf.getInt();
            default -> throw new IllegalArgumentException("invalid binary tag: " + tag);
        };
        byte[] b = new byte[len];
        buf.get(b);
        return b;
    }
}
