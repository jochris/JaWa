// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.client;

import id.jawa.binary.BinaryDecoder;
import id.jawa.binary.BinaryEncoder;
import id.jawa.binary.BinaryNode;
import id.jawa.events.ConnectedEvent;
import id.jawa.events.MessageEvent;
import id.jawa.events.PairingCodeEvent;
import id.jawa.socket.FrameSocket;
import id.jawa.socket.NoiseSocket;
import id.jawa.store.DeviceStore;
import id.jawa.types.Jid;
import id.jawa.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    // Virtual Thread Executor for event dispatching & node handling
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
        this.connected.set(true);

        dispatchEvent(new ConnectedEvent());
        LOG.info("JaWa client connected to WhatsApp Web socket");
    }

    public String pairPhone(String phoneNumber) {
        var result = PairingCode.generateCompanionEphemeralKey();
        LOG.info("Generated Phone Pairing Code: {}", result.formattedCode());
        dispatchEvent(new PairingCodeEvent(result.formattedCode()));
        return result.formattedCode();
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
