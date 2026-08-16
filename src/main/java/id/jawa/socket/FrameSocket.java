/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.jawa.socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Native JDK 11+ WebSocket transport for WhatsApp 24-bit length-prefixed frame protocol.
 * Ported directly from whatsmeow/socket/framesocket.go.
 */
public final class FrameSocket implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FrameSocket.class);

    public static final String URL = "wss://web.whatsapp.com/ws/chat";
    public static final String ORIGIN = "https://web.whatsapp.com";
    public static final byte[] WA_CONN_HEADER = new byte[]{'W', 'A', 6, 3};
    public static final int FRAME_MAX_SIZE = 4 * 1024 * 1024;

    private final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean headerSent = new AtomicBoolean(false);
    private WebSocket ws;
    private BiConsumer<FrameSocket, Boolean> onDisconnect;

    public void setOnDisconnect(BiConsumer<FrameSocket, Boolean> onDisconnect) {
        this.onDisconnect = onDisconnect;
    }

    public synchronized void connect() throws Exception {
        if (ws != null) throw new IllegalStateException("socket already open");
        closed.set(false);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        CompletableFuture<WebSocket> wsFuture = client.newWebSocketBuilder()
                .header("Origin", ORIGIN)
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .buildAsync(URI.create(URL), new WsListener());

        this.ws = wsFuture.get(15, TimeUnit.SECONDS);
        LOG.debug("WebSocket connected to {}", URL);
    }

    public void sendFrame(byte[] data) {
        if (closed.get() || ws == null) throw new IllegalStateException("socket closed");
        if (data.length >= FRAME_MAX_SIZE) throw new IllegalArgumentException("frame too large");

        byte[] header = headerSent.compareAndSet(false, true) ? WA_CONN_HEADER : new byte[0];
        int dataLength = data.length;
        byte[] wholeFrame = new byte[header.length + 3 + dataLength];

        if (header.length > 0) {
            System.arraycopy(header, 0, wholeFrame, 0, header.length);
        }

        int pos = header.length;
        wholeFrame[pos] = (byte) ((dataLength >> 16) & 0xFF);
        wholeFrame[pos + 1] = (byte) ((dataLength >> 8) & 0xFF);
        wholeFrame[pos + 2] = (byte) (dataLength & 0xFF);

        System.arraycopy(data, 0, wholeFrame, pos + 3, dataLength);

        ws.sendBinary(ByteBuffer.wrap(wholeFrame), true);
    }

    public byte[] receiveFrame(long timeout, TimeUnit unit) throws InterruptedException {
        return frames.poll(timeout, unit);
    }

    public BlockingQueue<byte[]> framesQueue() {
        return frames;
    }

    public boolean isConnected() {
        return ws != null && !closed.get();
    }

    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            if (ws != null) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "normal close");
                ws = null;
            }
            if (onDisconnect != null) {
                onDisconnect.accept(this, true);
            }
        }
    }

    private class WsListener implements WebSocket.Listener {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            buffer.write(bytes, 0, bytes.length);

            if (last) {
                byte[] raw = buffer.toByteArray();
                buffer.reset();
                processIncomingData(raw);
            }
            webSocket.request(1);
            return null;
        }

        private void processIncomingData(byte[] msg) {
            int pos = 0;
            while (pos + 3 <= msg.length) {
                int length = ((msg[pos] & 0xFF) << 16) | ((msg[pos + 1] & 0xFF) << 8) | (msg[pos + 2] & 0xFF);
                if (pos + 3 + length > msg.length) break;

                byte[] frame = new byte[length];
                System.arraycopy(msg, pos + 3, frame, 0, length);
                frames.add(frame);
                pos += 3 + length;
            }
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.error("FrameSocket error", error);
            close();
        }
    }
}
