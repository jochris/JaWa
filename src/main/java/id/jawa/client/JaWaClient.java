// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.client;

import id.jawa.binary.BinaryDecoder;
import id.jawa.binary.BinaryEncoder;
import id.jawa.binary.BinaryNode;
import id.jawa.socket.FrameSocket;
import id.jawa.socket.NoiseSocket;
import id.jawa.store.DeviceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * WhatsApp Multi-Device Client, directly ported and inspired by whatsmeow Client.
 * Leverages Java 21 Virtual Threads for event dispatching.
 */
public final class JaWaClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JaWaClient.class);

    private final DeviceStore store;
    private final BinaryEncoder encoder = new BinaryEncoder();
    private final BinaryDecoder decoder = new BinaryDecoder();
    private final List<Consumer<Object>> eventHandlers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);

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

        LOG.info("JaWa client connected to WhatsApp Web socket");
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
