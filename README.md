# JaWa — Java WhatsApp Web Client Library

[![build](https://github.com/jochris/JaWa/actions/workflows/build.yml/badge.svg)](https://github.com/jochris/JaWa/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
[![java](https://img.shields.io/badge/java-compatible-orange)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.jochris/JaWa)](https://central.sonatype.com/artifact/io.github.jochris/JaWa)

> Pure **Java** library for the WhatsApp Web Multi-Device protocol.
> Features end-to-end Signal encryption, full binary node encoding/decoding, phone & QR pairing, interactive CTA buttons, carousels, lists, and group management.

---

## 📦 Installation

Add `JaWa` to your project build configuration:

### Maven

```xml
<dependency>
    <groupId>io.github.jochris</groupId>
    <artifactId>JaWa</artifactId>
    <version>0.0.3</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.jochris:JaWa:0.0.3'
```

### Standalone Executable Fat JAR

To build a self-contained executable JAR containing all dependencies:

```sh
# Build the standalone JAR
mvn clean package -DskipTests

# Run the standalone executable JAR directly
java -jar target/JaWa-0.0.3-standalone.jar
```

---

## 🏗️ Architecture Layout

`JaWa` is structured into clean, domain-driven packages:

```text
id.jawa
├── client/                     <-- Primary Client Facade & Event Listeners
├── protocol/                   <-- Wire Protocol, Codecs & Cryptography
│   ├── connection/             <-- WebSocket, Noise XX Handshake & Transport
│   ├── codec/                  <-- Binary XML Node Encoder / Decoder
│   └── crypto/                 <-- Cryptography & Byte Utilities
├── domain/                     <-- Core Domain Models & Store Interfaces
│   ├── model/                  <-- JID, Auth Credentials, Receipt & Protocol Models
│   └── store/                  <-- File & In-Memory Persistence Stores
└── feature/                    <-- Feature Subsystems
    ├── pairing/                <-- QR & Phone-Code Pairing Handshake
    ├── messaging/              <-- E2EE Messaging, Group Actions & USync Query
    ├── media/                  <-- Media Encrypt/Decrypt & Upload/Download
    ├── appstate/               <-- Multi-Device AppState Sync & LtHash
    └── signal/                 <-- E2EE Session Bootstrap & PreKey Management
```

---

## 🚀 Code Examples

### 1. Extract & Resolve Phone Number (PN)

Extract clean phone number digits from incoming sender JID, with automatic LID-to-PN resolution via `JaWaClient.LID_TO_PN_MAP`:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.store.FileAuthStore;
import id.jawa.feature.messaging.MessageReceiver;

import java.nio.file.Path;

public class PhoneNumberResolverExample {

    public static void main(String[] args) throws Exception {
        FileAuthStore store = new FileAuthStore(Path.of("sessions/bot.session"));
        JaWaClient client = new JaWaClient(store, Path.of("sessions/bot.signal")).autoReconnect(true);

        client.listener(new JaWaClient.Listener() {
            @Override
            public void onConnected() {
                System.out.println("Bot connected!");
            }

            @Override
            public void onMessage(MessageReceiver.Decoded d) {
                /* Get the sender JID (can be PN or LID format) */
                String senderJid = d.senderJid();

                /* Extract clean Phone Number digits */
                String phoneNumber = extractPhoneNumber(senderJid);

                System.out.println("Pesan dari nomor HP: +" + phoneNumber);
                System.out.println("Raw Sender JID     : " + senderJid);
            }
        });

        client.connect();
        client.join();
    }

    public static String extractPhoneNumber(String senderJid) {
        if (senderJid == null) return "";

        String userPart = senderJid.split("@")[0].split(":")[0];

        /* Check if senderJid is LID format and mapped to PN in LID_TO_PN_MAP */
        String mappedPn = JaWaClient.LID_TO_PN_MAP.get(userPart + "@lid");
        if (mappedPn != null) {
            return mappedPn.split("@")[0].split(":")[0].replaceAll("[^0-9]", "");
        }

        /* Return clean phone number digits */
        return userPart.replaceAll("[^0-9]", "");
    }
}
```

---

### 2. Phone Pairing Code Connection

Connect and pair using an 8-character pairing code sent directly to your phone number:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.store.FileAuthStore;

import java.nio.file.Path;
import java.util.List;

public class PhonePairingExample {

    public static void main(String[] args) throws Exception {
        FileAuthStore store = new FileAuthStore(Path.of("sessions/phone.session"));
        JaWaClient client = new JaWaClient(store, Path.of("sessions/phone.signal")).autoReconnect(true);

        client.listener(new JaWaClient.Listener() {
            @Override
            public void onQr(List<String> qrs) {
                /* Request an 8-character phone pairing code */
                String targetPhone = "6285607589072";
                client.requestPairingCode(targetPhone, null).whenComplete((code, err) -> {
                    if (err != null) {
                        System.err.println("Failed to get pairing code: " + err.getMessage());
                        return;
                    }
                    System.out.println("==================================================");
                    System.out.println("📱 Target Phone Number: +" + targetPhone);
                    System.out.println("🔑 Pairing Code      : " + code.substring(0, 4) + "-" + code.substring(4));
                    System.out.println("==================================================");
                });
            }

            @Override
            public void onConnected() {
                System.out.println("🎉 Connected and paired successfully via Phone Code!");
            }
        });

        client.connect();
        client.join();
    }
}
```

---

### 3. Terminal QR Code Pairing

Connect and pair by scanning an ASCII QR matrix in the terminal:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.store.FileAuthStore;
import id.jawa.feature.pairing.QrTerminal;

import java.nio.file.Path;
import java.util.List;

public class QrPairingExample {

    public static void main(String[] args) throws Exception {
        FileAuthStore store = new FileAuthStore(Path.of("sessions/qr.session"));
        JaWaClient client = new JaWaClient(store, Path.of("sessions/qr.signal")).autoReconnect(true);

        client.listener(new JaWaClient.Listener() {
            @Override
            public void onQr(List<String> qrs) {
                if (!qrs.isEmpty()) {
                    System.out.println("\n>>> Scan this QR code with WhatsApp Linked Devices:\n");
                    System.out.print(QrTerminal.render(qrs.get(0)));
                }
            }

            @Override
            public void onConnected() {
                System.out.println("🎉 Connected successfully via QR Scan!");
            }
        });

        client.connect();
        client.join();
    }
}
```

---

### 4. Interactive CTA Buttons & Selection Lists

Send rich interactive call-to-action buttons (URL link, copy promo code, quick reply) and single-selection dropdown lists:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.feature.messaging.MessageEncoder;
import id.jawa.feature.messaging.MessageEncoder.CtaButton;
import id.jawa.feature.messaging.MessageEncoder.ListRow;
import id.jawa.feature.messaging.MessageEncoder.ListSection;

import java.util.List;

public class ButtonsAndListExample {

    public static void sendCtaButtons(JaWaClient client, String chatJid) {
        List<CtaButton> buttons = List.of(
            CtaButton.url("🌐 Visit GitHub", "https://github.com/jochris/JaWa"),
            CtaButton.copy("📋 Copy Code", "JAWA-PROMO-2026"),
            CtaButton.quickReply("🏓 Ping Bot", "ping_cmd")
        );

        var msg = MessageEncoder.interactiveCtaButtons(
            "*Interactive CTA Buttons Example*",
            "Choose an action below:",
            buttons
        );
        client.sendMessage(chatJid, msg);
    }

    public static void sendDropdownList(JaWaClient client, String chatJid) {
        List<CtaButton> buttons = List.of(
            CtaButton.singleSelect("Open Menu 📋", List.of(
                new ListSection("Main Actions", List.of(
                    new ListRow("ping_cmd", "Ping Server", "Check server uptime & health"),
                    new ListRow("menu_cmd", "Show Menu", "Display main navigation")
                )),
                new ListSection("Demos", List.of(
                    new ListRow("buttons_cmd", "CTA Buttons", "Test interactive buttons"),
                    new ListRow("carousel_cmd", "Carousel Slider", "Test card carousel")
                ))
            ))
        );

        var msg = MessageEncoder.interactiveCtaButtons(
            "*Dropdown Selection List Example*",
            "Select an option from the menu:",
            buttons
        );
        client.sendMessage(chatJid, msg);
    }
}
```

---

### 5. Carousel Cards Slider

Send horizontal scrolling carousel cards featuring images, descriptions, and action buttons:

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.feature.messaging.MessageEncoder.CtaButton;

import java.util.List;

public class CarouselExample {

    public static void sendCarouselSlider(JaWaClient client, String chatJid, byte[] imageBytes) {
        List<JaWaClient.CarouselCardInput> cards = List.of(
            new JaWaClient.CarouselCardInput(
                "Card 1: Feature Overview",
                "Learn more about JaWa features",
                imageBytes,
                "image/jpeg",
                "Slide 1",
                List.of(CtaButton.quickReply("Select Card 1", "card_1_cmd"))
            ),
            new JaWaClient.CarouselCardInput(
                "Card 2: Documentation",
                "Read the official guide & docs",
                imageBytes,
                "image/jpeg",
                "Slide 2",
                List.of(CtaButton.quickReply("Select Card 2", "card_2_cmd"))
            )
        );

        client.sendCarousel(chatJid, "*Swipe cards horizontally:*", "Carousel Demo", cards, null);
    }
}
```

---

### 6. SQLite Database Session Storage

Save and restore WhatsApp credentials & session state using an SQLite database (`SqliteAuthStore`):

```java
package com.example;

import id.jawa.client.JaWaClient;
import id.jawa.domain.store.SqliteAuthStore;

import java.nio.file.Path;

public class SqliteSessionExample {

    public static void main(String[] args) throws Exception {
        /* SQLite database session store */
        SqliteAuthStore store = new SqliteAuthStore(Path.of("sessions/app.db"));
        JaWaClient client = new JaWaClient(store, Path.of("sessions/app.signal")).autoReconnect(true);

        client.listener(new JaWaClient.Listener() {
            @Override
            public void onConnected() {
                System.out.println("🎉 Connected using SQLite Database session store!");
            }
        });

        client.connect();
        client.join();
    }
}
```

---

## 📄 License

This project is licensed under the terms of the **GNU General Public License v3.0** (GPL-3.0-or-later). See [LICENSE](LICENSE) for details.
