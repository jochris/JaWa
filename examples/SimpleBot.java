// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa;

import id.jawa.client.JaWaClient;
import id.jawa.types.Jid;

public class SimpleBot {
    public static void main(String[] args) {
        System.out.println("Starting JaWa (Java WhatsApp Web Client)...");
        try (JaWaClient client = new JaWaClient()) {
            client.addEventHandler(evt -> System.out.println("Event received: " + evt));
            System.out.println("Client initialized. Me JID: " + client.store().me());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
