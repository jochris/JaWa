/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.jawa.client;

import com.google.protobuf.ByteString;
import id.jawa.binary.BinaryDecoder;
import id.jawa.binary.BinaryEncoder;
import id.jawa.binary.BinaryNode;
import id.jawa.events.ConnectedEvent;
import id.jawa.events.MessageEvent;
import id.jawa.events.PairingCodeEvent;
import id.jawa.proto.Wa;
import id.jawa.socket.FrameSocket;
import id.jawa.socket.NoiseHandshake;
import id.jawa.socket.NoiseSocket;
import id.jawa.store.DeviceStore;
import id.jawa.types.Jid;
import id.jawa.util.Bytes;
import id.jawa.util.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * WhatsApp Multi-Device Client, directly ported and inspired by whatsmeow Client.
 * Leverages Java Virtual Threads for event dispatching.
 */
public final class JaWaClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JaWaClient.class);

    private final DeviceStore store;
    private final BinaryEncoder encoder = new BinaryEncoder();
    private final BinaryDecoder decoder = new BinaryDecoder();
    private final List<Consumer<Object>> eventHandlers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong msgCounter = new AtomicLong(System.currentTimeMillis());

    private FrameSocket frameSocket;
    private NoiseSocket noiseSocket;

    /** Virtual Thread Executor for event dispatching & node handling */
    private final ExecutorService eventExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public JaWaClient(DeviceStore store) {
        this.store = (store != null) ? store : new DeviceStore();
    }

    public JaWaClient() {
        this(new DeviceStore());
    }

    public synchronized void connect() throws Exception {
        if (connected.get()) return;
        LOG.info("Connecting JaWa client...");

        this.frameSocket = new FrameSocket();
        this.frameSocket.connect();

        doHandshake();

        this.connected.set(true);
        dispatchEvent(new ConnectedEvent());
        LOG.info("JaWa client connected and Noise XX Handshake completed!");
    }

    private void doHandshake() throws Exception {
        NoiseHandshake nh = new NoiseHandshake();
        nh.start("Noise_XX_25519_AESGCM_SHA256\0\0\0\0", FrameSocket.WA_CONN_HEADER);

        Crypto.KeyPair ephemeralKP = Crypto.generateCurve25519KeyPair();
        nh.authenticate(ephemeralKP.pubKey());

        Wa.HandshakeMessage handshakeMsg = Wa.HandshakeMessage.newBuilder()
                .setClientHello(Wa.HandshakeMessage.ClientHello.newBuilder()
                        .setEphemeral(ByteString.copyFrom(ephemeralKP.pubKey()))
                        .build())
                .build();

        frameSocket.sendFrame(handshakeMsg.toByteArray());

        byte[] resp = frameSocket.receiveFrame(20, TimeUnit.SECONDS);
        if (resp == null) {
            throw new IllegalStateException("Handshake timeout waiting for ServerHello");
        }

        Wa.HandshakeMessage handshakeResponse = Wa.HandshakeMessage.parseFrom(resp);
        Wa.HandshakeMessage.ServerHello serverHello = handshakeResponse.getServerHello();

        byte[] serverEphemeral = serverHello.getEphemeral().toByteArray();
        byte[] serverStaticCiphertext = serverHello.getStatic().toByteArray();
        byte[] certificateCiphertext = serverHello.getPayload().toByteArray();

        nh.authenticate(serverEphemeral);
        nh.mixSharedSecretIntoKey(ephemeralKP.privKey(), serverEphemeral);

        byte[] staticDecrypted = nh.decrypt(serverStaticCiphertext);
        nh.mixSharedSecretIntoKey(ephemeralKP.privKey(), staticDecrypted);

        byte[] certDecrypted = nh.decrypt(certificateCiphertext);

        byte[] encryptedPubkey = nh.encrypt(store.noiseKey().pubKey());
        nh.mixSharedSecretIntoKey(store.noiseKey().privKey(), serverEphemeral);

        NoiseHandshake.Keys keys = nh.finish();

        this.noiseSocket = new NoiseSocket(frameSocket, keys.write(), keys.read(), this::handleIncomingEncryptedFrame);
    }

    private void handleIncomingEncryptedFrame(byte[] plaintext) {
        try {
            BinaryNode node = decoder.decode(plaintext);
            if (node != null) {
                handleIncomingNode(node);
            }
        } catch (Exception e) {
            LOG.error("Failed to decode incoming binary node", e);
        }
    }

    public String pairPhone(String phoneNumber) {
        var result = PairingCode.generateCompanionEphemeralKey();
        LOG.info("Generated Phone Pairing Code: {}", result.formattedCode());

        if (connected.get()) {
            sendPairCodeRegistrationIQ(phoneNumber, result);
        }

        dispatchEvent(new PairingCodeEvent(result.formattedCode()));
        return result.formattedCode();
    }

    private void sendPairCodeRegistrationIQ(String phoneNumber, PairingCode.PairingResult result) {
        String cleanPhone = phoneNumber.replaceAll("[^0-9]", "");
        Jid targetJid = Jid.ofUser(cleanPhone);

        BinaryNode companionRegNode = BinaryNode.builder("link_code_companion_reg")
                .attr("jid", targetJid)
                .attr("stage", "companion_hello")
                .attr("should_show_push_notification", "true")
                .content(List.of(
                        BinaryNode.builder("link_code_pairing_wrapped_companion_ephemeral_pub").content(result.ephemeralKey()).build(),
                        BinaryNode.builder("companion_server_auth_key_pub").content(store.noiseKey().pubKey()).build(),
                        BinaryNode.builder("companion_platform_id").content("1").build(),
                        BinaryNode.builder("companion_platform_display").content("Chrome (Linux)").build(),
                        BinaryNode.builder("link_code_pairing_nonce").content(new byte[]{0}).build()
                ))
                .build();

        BinaryNode iqNode = BinaryNode.builder("iq")
                .attr("id", generateMessageId())
                .attr("xmlns", "md")
                .attr("type", "set")
                .attr("to", Jid.SERVER_JID)
                .content(List.of(companionRegNode))
                .build();

        sendNode(iqNode);
        LOG.info("Sent link_code_companion_reg IQ to WhatsApp server for target {}", cleanPhone);
    }

    public void sendMessage(Jid target, String text) {
        if (!connected.get()) throw new IllegalStateException("client not connected");
        String msgId = generateMessageId();
        BinaryNode msgNode = BinaryNode.builder("message")
                .attr("to", target)
                .attr("id", msgId)
                .attr("type", "text")
                .content(text)
                .build();
        sendNode(msgNode);
    }

    public void sendNode(BinaryNode node) {
        if (!connected.get()) throw new IllegalStateException("client not connected");
        byte[] payload = encoder.encode(node);
        if (noiseSocket != null) {
            noiseSocket.sendFrame(payload);
        } else {
            frameSocket.sendFrame(payload);
        }
    }

    public void handleIncomingNode(BinaryNode node) {
        if (node == null) return;
        if ("message".equals(node.tag())) {
            Jid from = node.getAttrJid("from");
            Jid participant = node.getAttrJid("participant");
            Jid sender = (participant != null) ? participant : from;
            String msgId = node.getAttrString("id");
            String text = node.getStringContent();

            if (from != null && text != null) {
                boolean isGroup = from.isGroup();
                long ts = node.getAttrLong("t");
                dispatchEvent(new MessageEvent(from, sender, msgId, text, isGroup, ts));
            }
        }
    }

    public String generateMessageId() {
        return "JAWA" + Bytes.toHex(Bytes.random(8)).toUpperCase();
    }

    public void addEventHandler(Consumer<Object> handler) {
        eventHandlers.add(handler);
    }

    public void dispatchEvent(Object event) {
        eventExecutor.submit(() -> {
            for (Consumer<Object> handler : eventHandlers) {
                try {
                    handler.accept(event);
                } catch (Exception e) {
                    LOG.error("Error in event handler", e);
                }
            }
        });
    }

    public DeviceStore store() { return store; }
    public boolean isConnected() { return connected.get(); }

    @Override
    public synchronized void close() {
        if (connected.compareAndSet(true, false)) {
            if (noiseSocket != null) noiseSocket.close();
            if (frameSocket != null) frameSocket.close();
            eventExecutor.shutdownNow();
            LOG.info("JaWa client disconnected");
        }
    }
}
