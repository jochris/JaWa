/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.jawa.domain.store;

import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.model.SignedPreKey;
import id.jawa.protocol.crypto.Bytes;
import id.jawa.protocol.crypto.KeyPair25519;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLite database persistence implementation for {@link AuthCreds} matching {@link AuthStore}.
 */
public final class SqliteAuthStore implements AuthStore, AutoCloseable {

    private final String jdbcUrl;

    public SqliteAuthStore(Path dbFile) {
        if (dbFile.getParent() != null) {
            try {
                Files.createDirectories(dbFile.getParent());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create directory for SQLite DB: " + dbFile, e);
            }
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        initDb();
    }

    public SqliteAuthStore(String dbPath) {
        this(Path.of(dbPath));
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void initDb() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS credentials (
                    key TEXT PRIMARY KEY,
                    value TEXT
                );
            """);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite database: " + jdbcUrl, e);
        }
    }

    @Override
    public synchronized AuthCreds load() throws IOException {
        Map<String, String> kv = readKv();
        if (kv.isEmpty() || kv.get("noiseKey.priv") == null) {
            return null;
        }

        AuthCreds c = new AuthCreds();
        c.noiseKey = new KeyPair25519(b64(kv.get("noiseKey.priv")), b64(kv.get("noiseKey.pub")));
        c.signedIdentityKey = new KeyPair25519(
            b64(kv.get("identityKey.priv")), b64(kv.get("identityKey.pub")));
        c.signedPreKey = new SignedPreKey(
            Integer.parseInt(kv.get("signedPreKey.id")),
            new KeyPair25519(b64(kv.get("signedPreKey.priv")), b64(kv.get("signedPreKey.pub"))),
            b64(kv.get("signedPreKey.sig")));
        c.registrationId   = Integer.parseInt(kv.get("registrationId"));
        c.advSecretKey     = b64(kv.get("advSecretKey"));
        c.nextPreKeyId     = parseIntOrDefault(kv.get("nextPreKeyId"), 1);
        c.firstUnuploadedPreKeyId = parseIntOrDefault(kv.get("firstUnuploadedPreKeyId"), 1);
        c.accountSyncCounter      = parseIntOrDefault(kv.get("accountSyncCounter"), 0);
        c.account  = b64Opt(kv.get("account"));
        c.meJid    = kv.get("meJid");
        c.meLid    = kv.get("meLid");
        c.pushName = kv.get("pushName");
        c.platform = kv.get("platform");
        return c;
    }

    @Override
    public synchronized void save(AuthCreds c) throws IOException {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("noiseKey.priv",     Bytes.toBase64(c.noiseKey.privateKey()));
        kv.put("noiseKey.pub",      Bytes.toBase64(c.noiseKey.publicKey()));
        kv.put("identityKey.priv",  Bytes.toBase64(c.signedIdentityKey.privateKey()));
        kv.put("identityKey.pub",   Bytes.toBase64(c.signedIdentityKey.publicKey()));
        kv.put("signedPreKey.id",   Integer.toString(c.signedPreKey.keyId()));
        kv.put("signedPreKey.priv", Bytes.toBase64(c.signedPreKey.keyPair().privateKey()));
        kv.put("signedPreKey.pub",  Bytes.toBase64(c.signedPreKey.keyPair().publicKey()));
        kv.put("signedPreKey.sig",  Bytes.toBase64(c.signedPreKey.signature()));
        kv.put("registrationId",    Integer.toString(c.registrationId));
        kv.put("advSecretKey",      Bytes.toBase64(c.advSecretKey));
        kv.put("nextPreKeyId",      Integer.toString(c.nextPreKeyId));
        kv.put("firstUnuploadedPreKeyId", Integer.toString(c.firstUnuploadedPreKeyId));
        kv.put("accountSyncCounter", Integer.toString(c.accountSyncCounter));
        if (c.account  != null) kv.put("account",  Bytes.toBase64(c.account));
        if (c.meJid    != null) kv.put("meJid",    c.meJid);
        if (c.meLid    != null) kv.put("meLid",    c.meLid);
        if (c.pushName != null) kv.put("pushName", c.pushName);
        if (c.platform != null) kv.put("platform", c.platform);
        writeKv(kv);
    }

    @Override
    public boolean isPaired() throws IOException {
        AuthCreds c = load();
        return c != null && c.account != null && c.meJid != null;
    }

    private Map<String, String> readKv() throws IOException {
        Map<String, String> kv = new LinkedHashMap<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT key, value FROM credentials")) {
            while (rs.next()) {
                kv.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (SQLException e) {
            throw new IOException("Failed to read credentials from SQLite: " + jdbcUrl, e);
        }
        return kv;
    }

    private void writeKv(Map<String, String> kv) throws IOException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO credentials (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                for (Map.Entry<String, String> e : kv.entrySet()) {
                    pstmt.setString(1, e.getKey());
                    pstmt.setString(2, e.getValue());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IOException("Failed to save credentials to SQLite: " + jdbcUrl, e);
            }
        } catch (SQLException e) {
            throw new IOException("Failed to connect to SQLite: " + jdbcUrl, e);
        }
    }

    private static byte[] b64(String s)    { return s == null ? null : Bytes.fromBase64(s); }
    private static byte[] b64Opt(String s) { return s == null ? null : Bytes.fromBase64(s); }
    private static int parseIntOrDefault(String s, int d) {
        return s == null ? d : Integer.parseInt(s);
    }

    @Override
    public void close() {
        /* SQLite JDBC connections in DriverManager close per operation */
    }
}
