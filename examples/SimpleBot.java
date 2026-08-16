// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa;

import id.jawa.client.JaWaClient;
import id.jawa.events.ConnectedEvent;
import id.jawa.events.MessageEvent;
import id.jawa.events.PairingCodeEvent;

public class SimpleBot {
    public static void main(String[] args) {
        System.out.println("Starting JaWa (Java WhatsApp Web Client)...");

        try (JaWaClient client = new JaWaClient()) {
            client.addEventHandler(event -> {
                if (event instanceof ConnectedEvent) {
                    System.out.println("✅ Connected to WhatsApp Web socket!");
                } else if (event instanceof PairingCodeEvent pairing) {
                    System.out.println("🔑 Pairing Code: " + pairing.code());
                } else if (event instanceof MessageEvent msg) {
                    System.out.printf("[%s] Message from %s: %s%n",
                            msg.isGroup() ? "GROUP " + msg.chat() : "PRIVATE",
                            msg.sender(), msg.text());

                    String text = msg.text().trim();
                    if (text.equalsIgnoreCase(".ping")) {
                        System.out.println("Replying pong to " + msg.chat());
                        client.sendMessage(msg.chat(), "pong 🏓");
                    } else if (text.equalsIgnoreCase(".menu")) {
                        String menuText = """
                                📋 *JaWa Bot Menu*
                                
                                .ping - Cek respon bot
                                .menu - Tampilkan daftar perintah
                                """;
                        client.sendMessage(msg.chat(), menuText);
                    }
                }
            });

            client.connect();

            String targetPhone = "62895416602000";
            String phoneCode = client.pairPhone(targetPhone);
            System.out.println("==================================================");
            System.out.println("📱 Target Phone Number: +" + targetPhone);
            System.out.println("🔑 Pairing Code      : " + phoneCode);
            System.out.println("==================================================");

            // Keep main thread alive
            Thread.currentThread().join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
