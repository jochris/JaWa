// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.types;

import java.util.regex.Pattern;

/**
 * Represents a WhatsApp User ID (JID), ported directly from whatsmeow types.JID.
 */
public record Jid(
    String user,
    int rawAgent,
    int device,
    int integrator,
    String server
) {
    public static final String DEFAULT_USER_SERVER = "s.whatsapp.net";
    public static final String GROUP_SERVER = "g.us";
    public static final String LEGACY_USER_SERVER = "c.us";
    public static final String BROADCAST_SERVER = "broadcast";
    public static final String HIDDEN_USER_SERVER = "lid";
    public static final String NEWSLETTER_SERVER = "newsletter";
    public static final String HOSTED_SERVER = "hosted";
    public static final String HOSTED_LID_SERVER = "hosted.lid";
    public static final String BOT_SERVER = "bot";

    public static final int WHATSAPP_DOMAIN = 0;
    public static final int LID_DOMAIN = 1;
    public static final int HOSTED_DOMAIN = 128;
    public static final int HOSTED_LID_DOMAIN = 129;

    public static final Jid EMPTY = new Jid("", 0, 0, 0, "");
    public static final Jid GROUP_SERVER_JID = of("", GROUP_SERVER);
    public static final Jid SERVER_JID = of("", DEFAULT_USER_SERVER);
    public static final Jid BROADCAST_SERVER_JID = of("", BROADCAST_SERVER);
    public static final Jid STATUS_BROADCAST_JID = of("status", BROADCAST_SERVER);
    public static final Jid PSA_JID = of("0", DEFAULT_USER_SERVER);

    private static final Pattern BOT_USER_PATTERN = Pattern.compile("^1313555\\d{4}$|^131655500\\d{2}$");

    public Jid {
        if (user == null) user = "";
        if (server == null) server = "";
    }

    public static Jid of(String user, String server) {
        return new Jid(user, 0, 0, 0, server);
    }

    public static Jid ofUser(String user) {
        return of(user, DEFAULT_USER_SERVER);
    }

    public static Jid ofGroup(String groupCode) {
        return of(groupCode, GROUP_SERVER);
    }

    public static Jid ofAD(String user, int agent, int device) {
        String server = switch (agent) {
            case LID_DOMAIN -> HIDDEN_USER_SERVER;
            case HOSTED_DOMAIN -> HOSTED_SERVER;
            case HOSTED_LID_DOMAIN -> HOSTED_LID_SERVER;
            default -> DEFAULT_USER_SERVER;
        };
        int actualAgent = (agent == LID_DOMAIN || agent == HOSTED_DOMAIN || agent == HOSTED_LID_DOMAIN) ? 0 : agent;
        return new Jid(user, actualAgent, device, 0, server);
    }

    public int actualAgent() {
        return switch (server) {
            case DEFAULT_USER_SERVER -> WHATSAPP_DOMAIN;
            case HIDDEN_USER_SERVER -> LID_DOMAIN;
            case HOSTED_SERVER -> HOSTED_DOMAIN;
            case HOSTED_LID_SERVER -> HOSTED_LID_DOMAIN;
            default -> rawAgent;
        };
    }

    public long userInt() {
        try {
            return Long.parseUnsignedLong(user);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public Jid toNonAD() {
        return new Jid(user, 0, 0, integrator, server);
    }

    public boolean isGroup() { return GROUP_SERVER.equals(server); }
    public boolean isUser() { return DEFAULT_USER_SERVER.equals(server); }
    public boolean isNewsletter() { return NEWSLETTER_SERVER.equals(server); }
    public boolean isBroadcastList() { return BROADCAST_SERVER.equals(server) && !"status".equals(user); }
    public boolean isBot() {
        return (DEFAULT_USER_SERVER.equals(server) && BOT_USER_PATTERN.matcher(user).matches() && device == 0)
                || BOT_SERVER.equals(server);
    }
    public boolean isEmpty() { return server.isEmpty(); }

    public static Jid parse(String raw) {
        if (raw == null || raw.isBlank()) return EMPTY;
        String[] parts = raw.split("@", 2);
        if (parts.length == 1) return of("", parts[0]);

        String userPart = parts[0];
        String server = parts[1];
        String user = userPart;
        int agent = 0;
        int device = 0;

        if (userPart.contains(".")) {
            String[] userDotParts = userPart.split("\\.", 2);
            user = userDotParts[0];
            String ad = userDotParts[1];
            String[] adParts = ad.split(":", 2);
            agent = Integer.parseInt(adParts[0]);
            if (adParts.length == 2) device = Integer.parseInt(adParts[1]);
        } else if (userPart.contains(":")) {
            String[] userColonParts = userPart.split(":", 2);
            user = userColonParts[0];
            device = Integer.parseInt(userColonParts[1]);
        }

        return new Jid(user, agent, device, 0, server);
    }

    @Override
    public String toString() {
        if (server.isEmpty()) return "";
        if (rawAgent > 0) return String.format("%s.%d:%d@%s", user, rawAgent, device, server);
        if (device > 0) return String.format("%s:%d@%s", user, device, server);
        if (!user.isEmpty()) return String.format("%s@%s", user, server);
        return server;
    }
}
