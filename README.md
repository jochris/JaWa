# JaWa — Java WhatsApp Web Library

[![build](https://github.com/jochris/JaWa/actions/workflows/build.yml/badge.svg)](https://github.com/jochris/JaWa/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.jochris/JaWa)](https://central.sonatype.com/artifact/io.github.jochris/JaWa)

Unofficial Java library for the WhatsApp Web multi-device protocol, ported from and modeled after [whatsmeow](https://github.com/tulir/whatsmeow) (Go).

---

## Disclaimer

This project is not affiliated with, authorized, or endorsed by WhatsApp. Use a dedicated test account. Using unofficial clients carries account suspension risk.

---

## Features

- **Native Protocol**: Direct WebSocket + Noise XX handshake without browser automation.
- **Whatsmeow Architecture**: Clean module structure ported directly from whatsmeow.
- **Minimal Dependencies**: Native HTTP/WebSocket transport and standard cryptography.
- **Phone Pairing Code**: Generate 8-character Crockford pairing codes (`XXXX-XXXX`) for phone linking without QR scanning.

---

## Architecture

```
id.jawa
├── types     — JID (Jabber ID) and domain records
├── util      — Byte, hex, base64, Crockford, and crypto utilities
├── binary    — WhatsApp binary XML nodes, tokens, encoder & decoder
├── socket    — FrameSocket, NoiseHandshake, and NoiseSocket transport
├── store     — Device state, keys, and session storage
└── client    — Main client facade, pairing code, and event handling
```

---

## Usage

### Dependency

```xml
<dependency>
    <groupId>io.github.jochris</groupId>
    <artifactId>JaWa</artifactId>
    <version>0.0.3</version>
</dependency>
```

### Quick Start Example

```java
import id.jawa.client.JaWaClient;

public class SimpleBot {
    public static void main(String[] args) throws Exception {
        try (JaWaClient client = new JaWaClient()) {
            client.addEventHandler(event -> System.out.println("Received event: " + event));
            client.connect();
        }
    }
}
```

### Phone Code Pairing Example

```java
import id.jawa.client.PairingCode;

public class PhonePairingExample {
    public static void main(String[] args) {
        // Generate Crockford pairing code for phone-number linking
        var pairResult = PairingCode.generateCompanionEphemeralKey();
        
        System.out.println("Pairing Code: " + pairResult.formattedCode()); // e.g. ABCD-1234
    }
}
```

---

## License

GNU General Public License v3.0 or later (GPL-3.0-or-later).
