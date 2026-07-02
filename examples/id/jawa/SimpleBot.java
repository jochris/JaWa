// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa;

import id.jawa.core.ChatPresence;
import id.jawa.core.JaWaClient;
import id.jawa.message.MessageEncoder;
import id.jawa.message.MessageEncoder.CtaButton;
import id.jawa.message.MessageEncoder.ListSection;
import id.jawa.message.MessageEncoder.ListRow;
import id.jawa.store.FileAuthStore;

import java.nio.file.Path;
import java.util.List;

/**
 * A simple, highly interactive WhatsApp Bot using the JaWa library.
 * Demonstrates:
 * 1. Ping command with real-time server and JVM memory info.
 * 2. CTA Buttons (URL, Copy, Call, and Quick Replies).
 * 3. Horizontal scrolling Carousels (with dynamically generated dummy image cards).
 * 4. List Messages (dropdown select options).
 * 5. Interactive callback responses (handling button/row clicks).
 */
public final class SimpleBot {

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
                    System.out.print(id.jawa.util.QrTerminal.render(qrs.get(0)));
                }
            }

            @Override
            public void onConnected() {
                System.out.println(">>> Bot connected and listening...");
                client.sendPresence(true);
            }

            @Override
            public void onMessage(id.jawa.message.MessageReceiver.Decoded d) {
                String chatJid = d.groupJid() != null ? d.groupJid() : d.senderJid();

                // 1. Handle interactive response callback (button or row click)
                if (d.interactive() != null) {
                    var ir = d.interactive();
                    System.out.println(">>> Interactive click: " + ir.kind() + " / ID: " + ir.selectedId());
                    handleCommand(client, chatJid, ir.selectedId(), d);
                    return;
                }

                // 2. Handle plain text message commands
                if (d.text() != null) {
                    String cmd = d.text().trim().toLowerCase();
                    handleCommand(client, chatJid, cmd, d);
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

    private static void handleCommand(JaWaClient client, String chatJid, String cmd, id.jawa.message.MessageReceiver.Decoded d) {
        if (cmd.equals(".menu") || cmd.equals("menu") || cmd.equals("menu_cmd")) {
            sendMenu(client, chatJid);
        } else if (cmd.equals(".ping") || cmd.equals("ping") || cmd.equals("ping_cmd")) {
            sendPing(client, chatJid, d);
        } else if (cmd.equals(".buttons") || cmd.equals("buttons") || cmd.equals("buttons_cmd")) {
            sendCtaButtons(client, chatJid);
        } else if (cmd.equals(".carousel") || cmd.equals("carousel") || cmd.equals("carousel_cmd")) {
            sendCarousel(client, chatJid);
        } else if (cmd.equals(".list") || cmd.equals("list") || cmd.equals("list_cmd")) {
            sendList(client, chatJid);
        }
    }

    private static void sendMenu(JaWaClient client, String chatJid) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);
        List<CtaButton> buttons = List.of(
            CtaButton.quickReply("🏓 Ping Bot", "ping_cmd"),
            CtaButton.quickReply("✨ Carousel Test", "carousel_cmd"),
            CtaButton.singleSelect("📋 Lainnya...", List.of(
                new ListSection("Pilihan Menu", List.of(
                    new ListRow("buttons_cmd", "CTA Buttons", "Tampilkan tombol URL, Copy, & Call"),
                    new ListRow("list_cmd", "Dropdown List", "Tampilkan daftar menu pilihan")
                ))
            ))
        );
        client.sendCtaButtons(chatJid, "*Hello! Silakan pilih menu di bawah ini:*", "JaWa Bot Menu", buttons);
    }

    private static void sendPing(JaWaClient client, String chatJid, id.jawa.message.MessageReceiver.Decoded d) {
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
            "• *Active Threads*: %d\n\n" +
            "_*Memory Status:*_\n" +
            "• *Used Memory*: %d MB\n" +
            "• *Total Memory*: %d MB\n" +
            "• *Free Memory*: %d MB",
            System.getProperty("os.name") + " " + System.getProperty("os.version"),
            Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.version"),
            Thread.activeCount(),
            usedMemory,
            totalMemory,
            freeMemory
        );

        client.sendMessage(chatJid, id.jawa.message.MessageEncoder.text(response));
    }

    private static void sendCtaButtons(JaWaClient client, String chatJid) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);
        List<CtaButton> buttons = List.of(
            CtaButton.url("🌐 Open Repository", "https://github.com/jochris/JaWa"),
            CtaButton.copy("📋 Copy Promo Code", "JAWA-BOT-2026"),
            CtaButton.quickReply("🏓 Ping Bot", "ping_cmd")
        );
        client.sendCtaButtons(chatJid, "*Pilih tombol interaksi di bawah ini:*", "JaWa Interactive CTA", buttons);
    }

    private static void sendCarousel(JaWaClient client, String chatJid) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);

        // Generate dummy red and green images dynamically to satisfy WA's media header requirement
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

        client.sendCarousel(chatJid, "*Silakan geser kartu di bawah ini:*", "Carousel Test", cards)
            .whenComplete((id, err) -> {
                if (err != null) {
                    System.err.println("Failed to send carousel: " + err.getMessage());
                }
            });
    }

    private static void sendList(JaWaClient client, String chatJid) {
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
        client.sendCtaButtons(chatJid, "*Pilih salah satu menu dari dropdown:*", "JaWa Dropdown", buttons);
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
