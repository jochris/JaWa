/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.jawa.feature.appstate;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Persistent backing for AppStateKey instances. Supports both file and SQLite storage.
 */
public final class FileAppStateKeyStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FileAppStateKeyStorage.class);
    private static final String SUFFIX = ".appkey";

    private final Path baseDir;
    private final SqliteAuthStore sqliteStore;
    private final ConcurrentMap<String, AppStateKey> cache = new ConcurrentHashMap<>();

    public FileAppStateKeyStorage(Path baseDir) {
        this(baseDir, null);
    }

    public FileAppStateKeyStorage(SqliteAuthStore sqliteStore) {
        this(null, sqliteStore);
    }

    public FileAppStateKeyStorage(Path baseDir, SqliteAuthStore sqliteStore) {
        this.baseDir = baseDir;
        this.sqliteStore = sqliteStore;
        if (baseDir != null) {
            try {
                Files.createDirectories(baseDir);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create app-state key dir " + baseDir, e);
            }
        }
        loadAll();
    }

    private void loadAll() {
        if (sqliteStore != null) {
            try {
                Map<String, byte[]> dbAppKeys = sqliteStore.loadAppStateKeys();
                for (var entry : dbAppKeys.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && entry.getValue().length == 32) {
                        try {
                            byte[] keyId = Base64.getUrlDecoder().decode(entry.getKey());
                            cache.put(entry.getKey(), new AppStateKey(keyId, entry.getValue()));
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                LOG.info("Loaded {} app-state key(s) from SQLite database", cache.size());
            } catch (IOException e) {
                LOG.warn("Failed loading app-state keys from SQLite: {}", e.toString());
            }
            return;
        }

        if (baseDir != null && Files.isDirectory(baseDir)) {
            try (Stream<Path> files = Files.list(baseDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .forEach(this::loadOne);
            } catch (IOException e) {
                LOG.warn("Failed listing app-state key dir {}: {}", baseDir, e.toString());
            }
            LOG.info("Loaded {} app-state key(s) from {}", cache.size(), baseDir);
        }
    }

    private void loadOne(Path file) {
        String filename = file.getFileName().toString();
        String idB64 = filename.substring(0, filename.length() - SUFFIX.length());
        try {
            byte[] keyId = Base64.getUrlDecoder().decode(idB64);
            byte[] keyData = Files.readAllBytes(file);
            cache.put(idB64, new AppStateKey(keyId, keyData));
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Failed reading app-state key {}: {}", file, e.toString());
        }
    }

    public AppStateKey get(byte[] keyId) {
        if (keyId == null) return null;
        return cache.get(Base64.getUrlEncoder().withoutPadding().encodeToString(keyId));
    }

    public void put(AppStateKey key) {
        String idB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(key.keyId());
        cache.put(idB64, key);
        if (sqliteStore != null) {
            sqliteStore.saveAppStateKey(idB64, key.keyData());
            return;
        }
        if (baseDir != null) {
            try {
                Path tmp = baseDir.resolve(idB64 + SUFFIX + ".tmp");
                Path dest = baseDir.resolve(idB64 + SUFFIX);
                Files.write(tmp, key.keyData());
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOG.warn("Failed persisting app-state key {}: {}", idB64, e.toString());
            }
        }
    }

    public void delete(byte[] keyId) {
        if (keyId == null) return;
        String idB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(keyId);
        cache.remove(idB64);
        if (sqliteStore != null) {
            sqliteStore.deleteAppStateKey(idB64);
            return;
        }
        if (baseDir != null) {
            try {
                Files.deleteIfExists(baseDir.resolve(idB64 + SUFFIX));
            } catch (IOException e) {
                LOG.warn("Failed deleting app-state key file {}: {}", idB64, e.toString());
            }
        }
    }

    public int size() { return cache.size(); }
}