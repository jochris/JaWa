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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.groups.SenderKeyName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Persistent backing for libsignal SenderKeyRecord instances. Supports both file and SQLite storage.
 */
public final class FileSenderKeyStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FileSenderKeyStorage.class);
    private static final String SUFFIX = ".senderkey";

    private final Path baseDir;
    private final SqliteAuthStore sqliteStore;
    private final ConcurrentMap<SenderKeyName, byte[]> cache = new ConcurrentHashMap<>();

    public FileSenderKeyStorage(Path baseDir) {
        this(baseDir, null);
    }

    public FileSenderKeyStorage(SqliteAuthStore sqliteStore) {
        this(null, sqliteStore);
    }

    public FileSenderKeyStorage(Path baseDir, SqliteAuthStore sqliteStore) {
        this.baseDir = baseDir;
        this.sqliteStore = sqliteStore;
        if (baseDir != null) {
            try {
                Files.createDirectories(baseDir);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create sender-key dir " + baseDir, e);
            }
        }
        loadAll();
    }

    private void loadAll() {
        if (sqliteStore != null) {
            try {
                Map<String, byte[]> dbSenderKeys = sqliteStore.loadSenderKeys();
                for (var entry : dbSenderKeys.entrySet()) {
                    SenderKeyName name = decodeFilename(entry.getKey());
                    if (name != null && entry.getValue() != null) {
                        cache.put(name, entry.getValue());
                    }
                }
                LOG.info("Loaded {} sender-key record(s) from SQLite database", cache.size());
            } catch (IOException e) {
                LOG.warn("Failed loading sender-keys from SQLite: {}", e.toString());
            }
            return;
        }

        if (baseDir != null && Files.isDirectory(baseDir)) {
            try (Stream<Path> files = Files.list(baseDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .forEach(this::loadOne);
            } catch (IOException e) {
                LOG.warn("Failed listing sender-key dir {}: {}", baseDir, e.toString());
            }
            LOG.info("Loaded {} sender-key record(s) from {}", cache.size(), baseDir);
        }
    }

    private void loadOne(Path file) {
        String name = file.getFileName().toString();
        try {
            SenderKeyName skName = decodeFilename(name);
            if (skName == null) return;
            byte[] bytes = Files.readAllBytes(file);
            cache.put(skName, bytes);
        } catch (IOException e) {
            LOG.warn("Failed reading sender key {}: {}", file, e.toString());
        }
    }

    public byte[] get(SenderKeyName name) {
        return cache.get(name);
    }

    public void put(SenderKeyName name, byte[] record) {
        cache.put(name, record);
        String key = encodeFilename(name);
        if (sqliteStore != null) {
            sqliteStore.saveSenderKey(key, record);
            return;
        }
        if (baseDir != null) {
            try {
                Path tmp = baseDir.resolve(key + ".tmp");
                Path dest = baseDir.resolve(key);
                Files.write(tmp, record);
                Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOG.warn("Failed persisting sender key for {}: {}", name, e.toString());
            }
        }
    }

    public void delete(SenderKeyName name) {
        cache.remove(name);
        String key = encodeFilename(name);
        if (sqliteStore != null) {
            sqliteStore.deleteSenderKey(key);
            return;
        }
        if (baseDir != null) {
            try {
                Files.deleteIfExists(baseDir.resolve(key));
            } catch (IOException e) {
                LOG.warn("Failed deleting sender key file for {}: {}", name, e.toString());
            }
        }
    }

    public int size() { return cache.size(); }

    private static String encodeFilename(SenderKeyName name) {
        String safeGroup = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(name.getGroupId().getBytes(StandardCharsets.UTF_8));
        String safeSender = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(name.getSender().getName().getBytes(StandardCharsets.UTF_8));
        return safeGroup + "__" + safeSender + "__" + name.getSender().getDeviceId() + SUFFIX;
    }

    private static SenderKeyName decodeFilename(String filename) {
        if (!filename.endsWith(SUFFIX)) return null;
        String base = filename.substring(0, filename.length() - SUFFIX.length());
        String[] parts = base.split("__");
        if (parts.length != 3) return null;
        try {
            String groupId = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String senderName = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            int deviceId = Integer.parseInt(parts[2]);
            return new SenderKeyName(groupId, new SignalProtocolAddress(senderName, deviceId));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}