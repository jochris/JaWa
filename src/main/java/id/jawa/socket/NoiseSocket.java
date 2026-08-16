// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.socket;

import id.jawa.util.Crypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Encrypted frame socket using AES-GCM write and read keys, matching whatsmeow/socket/noisesocket.go.
 * Leverages Java 21 Virtual Threads for non-blocking frame consumer pump.
 */
public final class NoiseSocket implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(NoiseSocket.class);

    private final FrameSocket fs;
    private final byte[] writeKey;
    private final byte[] readKey;
    private final Consumer<byte[]> frameHandler;
    private final AtomicInteger writeCounter = new AtomicInteger(0);
    private final AtomicInteger readCounter = new AtomicInteger(0);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    // Java 21 Virtual Thread Executor for processing incoming frame pump
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public NoiseSocket(FrameSocket fs, byte[] writeKey, byte[] readKey, Consumer<byte[]> frameHandler) {
        this.fs = fs;
        this.writeKey = writeKey;
        this.readKey = readKey;
        this.frameHandler = frameHandler;
        startPump();
    }

    private void startPump() {
        executor.submit(() -> {
            LOG.debug("Starting Virtual Thread frame pump...");
            while (!destroyed.get() && fs.isConnected()) {
                try {
                    byte[] frame = fs.receiveFrame(500, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        receiveEncryptedFrame(frame);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOG.error("Error processing encrypted frame", e);
                }
            }
        });
    }

    public synchronized void sendFrame(byte[] plaintext) {
        if (destroyed.get()) throw new IllegalStateException("NoiseSocket destroyed");
        int count = writeCounter.getAndIncrement();
        byte[] ciphertext = Crypto.aesGcmEncrypt(writeKey, generateIv(count), plaintext, null);
        fs.sendFrame(ciphertext);
    }

    private void receiveEncryptedFrame(byte[] ciphertext) {
        try {
            int count = readCounter.getAndIncrement();
            byte[] plaintext = Crypto.aesGcmDecrypt(readKey, generateIv(count), ciphertext, null);
            if (frameHandler != null) {
                frameHandler.accept(plaintext);
            }
        } catch (Exception e) {
            LOG.warn("Failed to decrypt frame: {}", e.getMessage());
        }
    }

    private static byte[] generateIv(int count) {
        byte[] iv = new byte[12];
        iv[8] = (byte) ((count >> 24) & 0xFF);
        iv[9] = (byte) ((count >> 16) & 0xFF);
        iv[10] = (byte) ((count >> 8) & 0xFF);
        iv[11] = (byte) (count & 0xFF);
        return iv;
    }

    @Override
    public synchronized void close() {
        if (destroyed.compareAndSet(false, true)) {
            executor.shutdownNow();
            fs.close();
        }
    }
}
