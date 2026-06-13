// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.appstate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Persistent backing for {@link AppStateKey}s. One 32-byte file per key under
 * {@code <baseDir>/}, named {@code base64url(keyId).appkey}. The companion device
 * receives these once via {@code appStateSyncKeyShare} from the primary phone and
 * cannot re-fetch them, so persistence is non-negotiable.
 */
public final class FileAppStateKeyStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FileAppStateKeyStorage.class);
    private static final String SUFFIX = ".appkey";

    private final Path baseDir;
    private final ConcurrentMap<String, AppStateKey> cache = new ConcurrentHashMap<>();

    public FileAppStateKeyStorage(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create app-state key dir " + baseDir, e);
        }
        loadAll();
    }

    private void loadAll() {
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                .forEach(this::loadOne);
        } catch (IOException e) {
            LOG.warn("Failed listing app-state key dir {}: {}", baseDir, e.toString());
        }
        LOG.info("Loaded {} app-state key(s) from {}", cache.size(), baseDir);
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
        return cache.get(Base64.getUrlEncoder().withoutPadding().encodeToString(keyId));
    }

    public void put(AppStateKey key) {
        String idB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(key.keyId());
        cache.put(idB64, key);
        try {
            Path tmp = baseDir.resolve(idB64 + SUFFIX + ".tmp");
            Path dest = baseDir.resolve(idB64 + SUFFIX);
            Files.write(tmp, key.keyData());
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("Failed persisting app-state key {}: {}", idB64, e.toString());
        }
    }

    public int size() { return cache.size(); }
}
