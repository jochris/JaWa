/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.jawa;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;

import id.jawa.domain.model.ChatPresence;
import id.jawa.client.JaWaClient;
import id.jawa.feature.messaging.MessageEncoder;
import id.jawa.feature.messaging.MessageEncoder.CtaButton;
import id.jawa.feature.messaging.MessageEncoder.ListSection;
import id.jawa.feature.messaging.MessageEncoder.ListRow;
import id.jawa.domain.store.FileAuthStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A simple, highly interactive WhatsApp Bot using the JaWa library.
 * Demonstrates:
 * 1. Ping command with real-time server and JVM memory info.
 * 2. CTA Buttons (URL, Copy, Call, and Quick Replies).
 * 3. Horizontal scrolling Carousels (with dynamically generated dummy image cards).
 * 4. List Messages (dropdown select options).
 * 5. Interactive callback responses (handling button/row clicks).
 * 6. Owner shell execution (> <cmd> or .exec <cmd>) with automatic LID-to-PN resolution.
 */
public final class SimpleBot {

    public static final String OWNER_NUMBER = System.getProperty("jawa.owner", "62895416602000");

    public static void main(String[] args) throws Exception {
        Path sessionFile = Path.of(System.getProperty("jawa.session", "sessions/default.session"));

        String suffix = ".session";
        String basePath = sessionFile.toString();
        String derivedSignalDir = basePath.endsWith(suffix)
            ? basePath.substring(0, basePath.length() - suffix.length()) + ".signal"
            : basePath + ".signal";
        Path signalDir = Path.of(System.getProperty("jawa.signal_dir", derivedSignalDir));

        System.out.println("=== JaWa Simple Interactive Bot ===");
        System.out.println("Session: " + sessionFile.toAbsolutePath());
        System.out.println("Signal : " + signalDir.toAbsolutePath());
        System.out.println("Owner  : +" + OWNER_NUMBER);

        FileAuthStore store = new FileAuthStore(sessionFile);
        JaWaClient client = new JaWaClient(store, signalDir).autoReconnect(true);

        client.listener(new JaWaClient.Listener() {
            @Override
            public void onPaired(String jid, String pushName, String platform) {
                System.out.println(">>> Bot paired successfully: " + jid + " (" + pushName + ")");
            }

            @Override
            public void onQr(List<String> qrs) {
                String phone = System.getProperty("jawa.phone");
                if (phone != null && !phone.isBlank()) {
                    client.requestPairingCode(phone, null).whenComplete((code, err) -> {
                        if (err != null) { System.err.println("Pair code error: " + err); return; }
                        System.out.println("\n>>> Pair Code: " + code.substring(0, 4) + "-" + code.substring(4) + "\n");
                    });
                    return;
                }
                if (!qrs.isEmpty()) {
                    System.out.println("\n>>> Scan this QR with Linked Devices:\n");
                    System.out.print(id.jawa.feature.pairing.QrTerminal.render(qrs.get(0)));
                }
            }

            @Override
            public void onConnected() {
                System.out.println(">>> Bot connected and listening...");
                client.sendPresence(true);
            }

            @Override
            public void onMessage(id.jawa.feature.messaging.MessageReceiver.Decoded d) {
                String chatJid = d.groupJid() != null ? d.groupJid() : d.senderJid();

                /* 1. Handle interactive response callback (button or row click) */
                if (d.interactive() != null) {
                    var ir = d.interactive();
                    System.out.println(">>> Interactive click: " + ir.kind() + " / ID: " + ir.selectedId());
                    handleCommand(client, chatJid, ir.selectedId(), d);
                    return;
                }

                /* 2. Handle plain text message commands */
                if (d.text() != null) {
                    String rawText = d.text().trim();
                    if (rawText.startsWith("> ")) {
                        handleExec(client, chatJid, rawText.substring(2).trim(), d);
                    } else if (rawText.startsWith(">")) {
                        handleExec(client, chatJid, rawText.substring(1).trim(), d);
                    } else if (rawText.toLowerCase().startsWith(".exec ")) {
                        handleExec(client, chatJid, rawText.substring(6).trim(), d);
                    } else {
                        String cmd = rawText.toLowerCase();
                        handleCommand(client, chatJid, cmd, d);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println(">>> Error occurred: " + t.getMessage());
            }
        });

        client.connect();
        client.join();
    }

    private static boolean isOwner(String senderJid) {
        if (senderJid == null) return false;

        String cleanOwner = OWNER_NUMBER.replaceAll("[^0-9]", "");
        String userPart = senderJid.split("@")[0].split(":")[0];
        String cleanSender = userPart.replaceAll("[^0-9]", "");

        /* 1. Direct PN check */
        if (cleanSender.equals(cleanOwner)) {
            return true;
        }

        /* 2. LID to PN resolution via JaWaClient.LID_TO_PN_MAP */
        String bareLid = userPart + "@lid";
        String mappedPn = JaWaClient.LID_TO_PN_MAP.get(bareLid);
        if (mappedPn != null) {
            String cleanMappedPn = mappedPn.split("@")[0].split(":")[0].replaceAll("[^0-9]", "");
            if (cleanMappedPn.equals(cleanOwner)) {
                return true;
            }
        }

        /* 3. Optional owner LID property check (-Djawa.owner_lid=...) */
        String ownerLid = System.getProperty("jawa.owner_lid");
        if (ownerLid != null && !ownerLid.isBlank()) {
            String cleanOwnerLid = ownerLid.split("@")[0].split(":")[0].replaceAll("[^0-9]", "");
            if (cleanSender.equals(cleanOwnerLid)) {
                return true;
            }
        }

        return false;
    }

    private static void handleCommand(JaWaClient client, String chatJid, String cmd, id.jawa.feature.messaging.MessageReceiver.Decoded d) {
        if (cmd.equals(".menu") || cmd.equals("menu") || cmd.equals("menu_cmd")) {
            sendMenu(client, chatJid, d);
        } else if (cmd.equals(".ping") || cmd.equals("ping") || cmd.equals("ping_cmd")) {
            sendPing(client, chatJid, d);
        } else if (cmd.equals(".buttons") || cmd.equals("buttons") || cmd.equals("buttons_cmd")) {
            sendCtaButtons(client, chatJid, d);
        } else if (cmd.equals(".carousel") || cmd.equals("carousel") || cmd.equals("carousel_cmd")) {
            sendCarousel(client, chatJid, d);
        } else if (cmd.equals(".list") || cmd.equals("list") || cmd.equals("list_cmd")) {
            sendList(client, chatJid, d);
        }
    }

    private static void handleExec(JaWaClient client, String chatJid, String commandStr, id.jawa.feature.messaging.MessageReceiver.Decoded d) {
        String senderJid = d != null ? d.senderJid() : null;
        if (!isOwner(senderJid)) {
            System.out.println(">>> Exec denied for sender: " + senderJid + " (Owner required: " + OWNER_NUMBER + ")");
            var msg = MessageEncoder.text("⚠️ *Akses Ditolak!* Perintah exec hanya dapat dijalankan oleh Owner bot (+" + OWNER_NUMBER + ").\n\nSender JID: `" + senderJid + "`");
            if (d != null) {
                String quotedSender = d.groupJid() != null ? d.senderJid() : null;
                msg = MessageEncoder.quote(msg, d.msgId(), quotedSender, d.text() != null ? d.text() : "");
            }
            client.sendMessage(chatJid, msg);
            return;
        }

        System.out.println(">>> Exec granted for owner " + senderJid + ": " + commandStr);
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);
        try {
            Process process = new ProcessBuilder("bash", "-c", commandStr)
                    .redirectErrorStream(true)
                    .start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                output.append("\n[Execution timed out after 10s]");
            }

            String resultText = output.toString().trim();
            if (resultText.isEmpty()) {
                resultText = "(Command executed successfully with no output)";
            }

            String response = "💻 *Exec Result:*\n```\n" + resultText + "\n```";
            var msg = MessageEncoder.text(response);
            if (d != null) {
                String quotedSender = d.groupJid() != null ? d.senderJid() : null;
                msg = MessageEncoder.quote(msg, d.msgId(), quotedSender, d.text() != null ? d.text() : "");
            }
            client.sendMessage(chatJid, msg);
        } catch (Exception e) {
            String response = "❌ *Exec Error:*\n```\n" + e.getMessage() + "\n```";
            var msg = MessageEncoder.text(response);
            if (d != null) {
                String quotedSender = d.groupJid() != null ? d.senderJid() : null;
                msg = MessageEncoder.quote(msg, d.msgId(), quotedSender, d.text() != null ? d.text() : "");
            }
            client.sendMessage(chatJid, msg);
        }
    }

    private static void sendMenu(JaWaClient client, String chatJid, id.jawa.feature.messaging.MessageReceiver.Decoded d) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);
        List<CtaButton> buttons = List.of(
            CtaButton.quickReply("🏓 Ping Bot", "ping_cmd"),
            CtaButton.quickReply("✨ Carousel Test", "carousel_cmd"),
            CtaButton.singleSelect("📋 Lainnya...", List.of(
                new ListSection("Pilihan Menu", List.of(
                    new ListRow("buttons_cmd", "CTA Buttons", "Tampilkan tombol URL, Copy, & Call"),
                    new ListRow("list_cmd", "Dropdown List", "Tampilkan daftar menu pilihan")
                )),
                new ListSection("Owner Commands", List.of(
                    new ListRow("exec_info", "Owner Exec Command", "Gunakan > <cmd> atau .exec <cmd>")
                ))
            ))
        );
        var msg = id.jawa.feature.messaging.MessageEncoder.interactiveCtaButtons(
            "*Hello! Silakan pilih menu di bawah ini:*", "JaWa Bot Menu (Owner: +" + OWNER_NUMBER + ")", buttons
        );
        if (d != null) {
            String quotedSender = d.groupJid() != null ? d.senderJid() : null;
            String quotedText = d.text() != null ? d.text() : "";
            msg = id.jawa.feature.messaging.MessageEncoder.quote(msg, d.msgId(), quotedSender, quotedText);
        }
        client.sendMessage(chatJid, msg);
    }

    private static void sendPing(JaWaClient client, String chatJid, id.jawa.feature.messaging.MessageReceiver.Decoded d) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);
        
        long totalMemory = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long freeMemory = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        
        String response = String.format(
            "*🏓 PONG!*\n\n" +
            "_*Server Info:*_\n" +
            "• *OS*: %s\n" +
            "• *CPU Cores*: %d\n" +
            "• *Java Version*: %s\n" +
            "• *Active Threads*: %d\n" +
            "• *Owner*: +%s\n\n" +
            "_*Memory Status:*_\n" +
            "• *Used Memory*: %d MB\n" +
            "• *Total Memory*: %d MB\n" +
            "• *Free Memory*: %d MB",
            System.getProperty("os.name") + " " + System.getProperty("os.version"),
            Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.version"),
            Thread.activeCount(),
            OWNER_NUMBER,
            usedMemory,
            totalMemory,
            freeMemory
        );

        var msg = id.jawa.feature.messaging.MessageEncoder.text(response);
        if (d != null) {
            String quotedSender = d.groupJid() != null ? d.senderJid() : null;
            String quotedText = d.text() != null ? d.text() : "";
            msg = id.jawa.feature.messaging.MessageEncoder.quote(msg, d.msgId(), quotedSender, quotedText);
        }
        client.sendMessage(chatJid, msg);
    }

    private static void sendCtaButtons(JaWaClient client, String chatJid, id.jawa.feature.messaging.MessageReceiver.Decoded d) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);
        List<CtaButton> buttons = List.of(
            CtaButton.url("🌐 Open Repository", "https://github.com/jochris/JaWa"),
            CtaButton.copy("📋 Copy Promo Code", "JAWA-BOT-2026"),
            CtaButton.quickReply("🏓 Ping Bot", "ping_cmd")
        );
        var msg = id.jawa.feature.messaging.MessageEncoder.interactiveCtaButtons(
            "*Pilih tombol interaksi di bawah ini:*", "JaWa Interactive CTA", buttons
        );
        if (d != null) {
            String quotedSender = d.groupJid() != null ? d.senderJid() : null;
            String quotedText = d.text() != null ? d.text() : "";
            msg = id.jawa.feature.messaging.MessageEncoder.quote(msg, d.msgId(), quotedSender, quotedText);
        }
        client.sendMessage(chatJid, msg);
    }

    private static void sendCarousel(JaWaClient client, String chatJid, id.jawa.feature.messaging.MessageReceiver.Decoded d) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);

        /* Generate dummy red and green images dynamically to satisfy WA's media header requirement */
        byte[] redImageBytes = getDummyImageBytes(0xFF0000);
        byte[] greenImageBytes = getDummyImageBytes(0x00FF00);

        List<JaWaClient.CarouselCardInput> cards = List.of(
            new JaWaClient.CarouselCardInput(
                "Red Option",
                "Deskripsi opsi merah",
                redImageBytes,
                "image/jpeg",
                "Kartu 1",
                List.of(CtaButton.quickReply("Pilih Merah", "ping_cmd"))
            ),
            new JaWaClient.CarouselCardInput(
                "Green Option",
                "Deskripsi opsi hijau",
                greenImageBytes,
                "image/jpeg",
                "Kartu 2",
                List.of(CtaButton.quickReply("Pilih Hijau", "ping_cmd"))
            )
        );

        id.jawa.proto.Wa.ContextInfo quoted = null;
        if (d != null) {
            id.jawa.proto.Wa.Message quotedStub = id.jawa.proto.Wa.Message.newBuilder()
                .setConversation(d.text() == null ? "" : d.text())
                .build();
            String quotedSender = d.groupJid() != null ? d.senderJid() : null;
            var qBuilder = id.jawa.proto.Wa.ContextInfo.newBuilder()
                .setStanzaId(d.msgId())
                .setQuotedMessage(quotedStub);
            if (quotedSender != null && !quotedSender.isBlank()) {
                qBuilder.setParticipant(quotedSender);
            }
            quoted = qBuilder.build();
        }

        client.sendCarousel(chatJid, "*Silakan geser kartu di bawah ini:*", "Carousel Test", cards, quoted)
            .whenComplete((id, err) -> {
                if (err != null) {
                    System.err.println("Failed to send carousel: " + err.getMessage());
                }
            });
    }

    private static void sendList(JaWaClient client, String chatJid, id.jawa.feature.messaging.MessageReceiver.Decoded d) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);
        List<CtaButton> buttons = List.of(
            CtaButton.singleSelect("Buka Dropdown 📋", List.of(
                new ListSection("Main Actions", List.of(
                    new ListRow("ping_cmd", "Ping", "Check server health and uptime"),
                    new ListRow("menu_cmd", "Show Menu", "Tampilkan navigasi menu bot")
                )),
                new ListSection("Demos", List.of(
                    new ListRow("buttons_cmd", "Interactive Buttons", "Demo CTA buttons"),
                    new ListRow("carousel_cmd", "Carousel Slider", "Demo horizontal scroll card list")
                ))
            ))
        );
        var msg = id.jawa.feature.messaging.MessageEncoder.interactiveCtaButtons(
            "*Pilih salah satu menu dari dropdown:*", "JaWa Dropdown", buttons
        );
        if (d != null) {
            String quotedSender = d.groupJid() != null ? d.senderJid() : null;
            String quotedText = d.text() != null ? d.text() : "";
            msg = id.jawa.feature.messaging.MessageEncoder.quote(msg, d.msgId(), quotedSender, quotedText);
        }
        client.sendMessage(chatJid, msg);
    }

    private static byte[] getDummyImageBytes(int rgbColor) {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 10; x++) {
                for (int y = 0; y < 10; y++) {
                    img.setRGB(x, y, rgbColor);
                }
            }
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "jpeg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}