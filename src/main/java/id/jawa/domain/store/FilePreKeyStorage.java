/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.jawa.domain.store;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;

import id.jawa.protocol.crypto.KeyPair25519;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Persistent backing for pre-keys. Supports both file storage and pure SQLite storage.
 */
public final class FilePreKeyStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FilePreKeyStorage.class);

    private static final String SUFFIX = ".prekey";
    private static final int KEY_BYTES = 32;
    private static final int RECORD_BYTES = KEY_BYTES * 2;

    private final Path baseDir;
    private final SqliteAuthStore sqliteStore;
    private final ConcurrentMap<Integer, KeyPair25519> cache = new ConcurrentHashMap<>();

    public FilePreKeyStorage(Path baseDir) {
        this(baseDir, null);
    }

    public FilePreKeyStorage(SqliteAuthStore sqliteStore) {
        this(null, sqliteStore);
    }

    public FilePreKeyStorage(Path baseDir, SqliteAuthStore sqliteStore) {
        this.baseDir = baseDir;
        this.sqliteStore = sqliteStore;
        if (baseDir != null) {
            try {
                Files.createDirectories(baseDir);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create pre-key dir " + baseDir, e);
            }
        }
        loadAll();
    }

    private void loadAll() {
        if (sqliteStore != null) {
            try {
                Map<Integer, KeyPair25519> dbPreKeys = sqliteStore.loadPreKeys();
                cache.putAll(dbPreKeys);
                LOG.info("Loaded {} pre-key(s) from SQLite database", cache.size());
            } catch (IOException e) {
                LOG.warn("Failed loading pre-keys from SQLite: {}", e.toString());
            }
            return;
        }

        if (baseDir != null && Files.isDirectory(baseDir)) {
            try (Stream<Path> files = Files.list(baseDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .forEach(this::loadOne);
            } catch (IOException e) {
                LOG.warn("Failed listing pre-key dir {}: {}", baseDir, e.toString());
            }
            LOG.info("Loaded {} pre-key(s) from {}", cache.size(), baseDir);
        }
    }

    private void loadOne(Path file) {
        String name = file.getFileName().toString();
        String idStr = name.substring(0, name.length() - SUFFIX.length());
        int id;
        try { id = Integer.parseInt(idStr); }
        catch (NumberFormatException e) { return; }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length != RECORD_BYTES) {
                LOG.warn("Pre-key file {} has wrong length {} (expected {})", file, bytes.length, RECORD_BYTES);
                return;
            }
            byte[] priv = new byte[KEY_BYTES];
            byte[] pub  = new byte[KEY_BYTES];
            System.arraycopy(bytes, 0, priv, 0, KEY_BYTES);
            System.arraycopy(bytes, KEY_BYTES, pub, 0, KEY_BYTES);
            cache.put(id, new KeyPair25519(priv, pub));
        } catch (IOException e) {
            LOG.warn("Failed reading pre-key {}: {}", file, e.toString());
        }
    }

    public KeyPair25519 get(int id) { return cache.get(id); }

    public void put(int id, KeyPair25519 kp) {
        cache.put(id, kp);
        if (sqliteStore != null) {
            sqliteStore.savePreKey(id, kp);
            return;
        }
        if (baseDir != null) {
            try {
                byte[] bytes = new byte[RECORD_BYTES];
                System.arraycopy(kp.privateKey(), 0, bytes, 0, KEY_BYTES);
                System.arraycopy(kp.publicKey(), 0, bytes, KEY_BYTES, KEY_BYTES);
                Path tmp = baseDir.resolve(id + SUFFIX + ".tmp");
                Path dest = baseDir.resolve(id + SUFFIX);
                Files.write(tmp, bytes);
                Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOG.warn("Failed persisting pre-key {}: {}", id, e.toString());
            }
        }
    }

    public void remove(int id) {
        cache.remove(id);
        if (sqliteStore != null) {
            sqliteStore.deletePreKey(id);
            return;
        }
        if (baseDir != null) {
            try {
                Files.deleteIfExists(baseDir.resolve(id + SUFFIX));
            } catch (IOException e) {
                LOG.warn("Failed deleting pre-key file {}: {}", id, e.toString());
            }
        }
    }

    public Map<Integer, KeyPair25519> snapshot() {
        return new LinkedHashMap<>(cache);
    }

    public void pruneKeepHighest(int keepCount) {
        if (cache.size() <= keepCount) return;
        TreeMap<Integer, KeyPair25519> sorted = new TreeMap<>(cache);
        int toRemove = sorted.size() - keepCount;
        int removedCount = 0;
        for (var entry : sorted.entrySet()) {
            if (removedCount >= toRemove) break;
            remove(entry.getKey());
            removedCount++;
        }
        if (sqliteStore != null) {
            sqliteStore.prunePreKeys(keepCount);
        }
        LOG.info("Pruned {} old pre-keys (retained highest {})", removedCount, keepCount);
    }

    public int size() { return cache.size(); }
}