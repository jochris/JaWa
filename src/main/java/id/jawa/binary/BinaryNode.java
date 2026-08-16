// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.binary;

import id.jawa.types.Jid;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a WhatsApp binary XML Node, directly matching whatsmeow binary.Node.
 */
public record BinaryNode(
    String tag,
    Map<String, Object> attrs,
    Object content
) {
    public BinaryNode {
        if (tag == null) tag = "";
        if (attrs == null) attrs = Collections.emptyMap();
    }

    public static BinaryNode of(String tag) {
        return new BinaryNode(tag, Collections.emptyMap(), null);
    }

    public static BinaryNode of(String tag, Map<String, Object> attrs) {
        return new BinaryNode(tag, attrs, null);
    }

    public static BinaryNode of(String tag, Map<String, Object> attrs, Object content) {
        return new BinaryNode(tag, attrs, content);
    }

    public Object getAttr(String key) {
        return attrs.get(key);
    }

    public String getAttrString(String key) {
        Object val = attrs.get(key);
        if (val instanceof String s) return s;
        if (val instanceof Jid j) return j.toString();
        return val != null ? val.toString() : null;
    }

    public Jid getAttrJid(String key) {
        Object val = attrs.get(key);
        if (val instanceof Jid j) return j;
        if (val instanceof String s) return Jid.parse(s);
        return null;
    }

    public long getAttrLong(String key) {
        Object val = attrs.get(key);
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    @SuppressWarnings("unchecked")
    public List<BinaryNode> getChildren() {
        if (content instanceof List<?> list) {
            return (List<BinaryNode>) list;
        }
        return Collections.emptyList();
    }

    public List<BinaryNode> getChildrenByTag(String childTag) {
        return getChildren().stream().filter(n -> n.tag().equals(childTag)).toList();
    }

    public BinaryNode getChild(String... tags) {
        BinaryNode current = this;
        for (String t : tags) {
            BinaryNode found = null;
            for (BinaryNode child : current.getChildren()) {
                if (child.tag().equals(t)) {
                    found = child;
                    break;
                }
            }
            if (found == null) return null;
            current = found;
        }
        return current;
    }

    public byte[] getBytesContent() {
        if (content instanceof byte[] b) return b;
        return null;
    }

    public String getStringContent() {
        if (content instanceof String s) return s;
        if (content instanceof byte[] b) return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        return null;
    }

    public static Builder builder(String tag) {
        return new Builder(tag);
    }

    public static class Builder {
        private final String tag;
        private final Map<String, Object> attrs = new LinkedHashMap<>();
        private Object content;

        public Builder(String tag) {
            this.tag = tag;
        }

        public Builder attr(String key, Object value) {
            if (value != null) attrs.put(key, value);
            return this;
        }

        public Builder content(Object content) {
            this.content = content;
            return this;
        }

        public BinaryNode build() {
            return new BinaryNode(tag, attrs, content);
        }
    }
}
