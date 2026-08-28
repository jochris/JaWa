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
import org.whispersystems.libsignal.state.SessionRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Persistent backing for libsignal {@link SessionRecord}s. Supports both file-backed
 * storage under baseDir and pure SQLite database storage via {@link SqliteAuthStore}.
 */
public final class FileSessionStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FileSessionStorage.class);

    private final Path baseDir;
    private final SqliteAuthStore sqliteStore;
    private final ConcurrentMap<SignalProtocolAddress, byte[]> cache = new ConcurrentHashMap<>();

    public FileSessionStorage(Path baseDir) {
        this(baseDir, null);
    }

    public FileSessionStorage(SqliteAuthStore sqliteStore) {
        this(null, sqliteStore);
    }

    public FileSessionStorage(Path baseDir, SqliteAuthStore sqliteStore) {
        this.baseDir = baseDir;
        this.sqliteStore = sqliteStore;
        if (baseDir != null) {
            try {
                Files.createDirectories(baseDir);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot create session dir " + baseDir, e);
            }
        }
        loadAll();
    }

    private void loadAll() {
        if (sqliteStore != null) {
            try {
                Map<String, byte[]> dbSessions = sqliteStore.loadSessions();
                for (var entry : dbSessions.entrySet()) {
                    SignalProtocolAddress addr = decodeFilename(entry.getKey());
                    if (addr != null && entry.getValue() != null) {
                        cache.put(addr, entry.getValue());
                    }
                }
                LOG.info("Loaded {} session record(s) from SQLite database", cache.size());
            } catch (IOException e) {
                LOG.warn("Failed loading session records from SQLite: {}", e.toString());
            }
            return;
        }

        if (baseDir != null && Files.isDirectory(baseDir)) {
            try (Stream<Path> files = Files.list(baseDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".session"))
                    .forEach(this::loadOne);
            } catch (IOException e) {
                LOG.warn("Failed listing session dir {}: {}", baseDir, e.toString());
            }
            LOG.info("Loaded {} session record(s) from {}", cache.size(), baseDir);
        }
    }

    private void loadOne(Path file) {
        String name = file.getFileName().toString();
        try {
            SignalProtocolAddress addr = decodeFilename(name);
            if (addr == null) return;
            byte[] bytes = Files.readAllBytes(file);
            cache.put(addr, bytes);
        } catch (IOException e) {
            LOG.warn("Failed reading session {}: {}", file, e.toString());
        }
    }

    public SessionRecord get(SignalProtocolAddress address) {
        byte[] bytes = cache.get(address);
        if (bytes == null) return new SessionRecord();
        try {
            return new SessionRecord(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("corrupt session record for " + address, e);
        }
    }

    public boolean contains(SignalProtocolAddress address) {
        byte[] bytes = cache.get(address);
        return bytes != null && bytes.length > 0;
    }

    public void put(SignalProtocolAddress address, SessionRecord record) {
        byte[] bytes = record.serialize();
        cache.put(address, bytes);
        String key = encodeFilename(address);
        if (sqliteStore != null) {
            sqliteStore.saveSession(key, bytes);
            return;
        }
        if (baseDir != null) {
            try {
                Path tmp = baseDir.resolve(key + ".tmp");
                Path dest = baseDir.resolve(key);
                Files.write(tmp, bytes);
                Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                LOG.warn("Failed persisting session for {}: {}", address, e.toString());
            }
        }
    }

    public void delete(SignalProtocolAddress address) {
        cache.remove(address);
        String key = encodeFilename(address);
        if (sqliteStore != null) {
            sqliteStore.deleteSession(key);
            return;
        }
        if (baseDir != null) {
            try {
                Files.deleteIfExists(baseDir.resolve(key));
            } catch (IOException e) {
                LOG.warn("Failed deleting session file for {}: {}", address, e.toString());
            }
        }
    }

    public void deleteAll(String name) {
        Map<SignalProtocolAddress, byte[]> toDelete = new HashMap<>();
        for (SignalProtocolAddress addr : cache.keySet()) {
            if (addr.getName().equals(name)) toDelete.put(addr, null);
        }
        for (SignalProtocolAddress addr : toDelete.keySet()) delete(addr);
    }

    public List<Integer> subDeviceIds(String name) {
        return cache.keySet().stream()
            .filter(a -> a.getName().equals(name))
            .map(SignalProtocolAddress::getDeviceId)
            .collect(Collectors.toList());
    }

    public int size() { return cache.size(); }

    private static String encodeFilename(SignalProtocolAddress address) {
        String safeName = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(address.getName().getBytes(StandardCharsets.UTF_8));
        return safeName + "__" + address.getDeviceId() + ".session";
    }

    private static SignalProtocolAddress decodeFilename(String filename) {
        if (!filename.endsWith(".session")) return null;
        String base = filename.substring(0, filename.length() - ".session".length());
        int sep = base.lastIndexOf("__");
        if (sep < 0) return null;
        try {
            String name = new String(Base64.getUrlDecoder().decode(base.substring(0, sep)),
                StandardCharsets.UTF_8);
            int deviceId = Integer.parseInt(base.substring(sep + 2));
            return new SignalProtocolAddress(name, deviceId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}