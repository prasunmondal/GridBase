package io.github.prasunmondal.hibernatesheets.cache;

import io.github.prasunmondal.hibernatesheets.exception.HibernateSheetsException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQLite store for engine replies to read-only requests. Each entry is tagged with the worksheets it
 * read, so a write through any client using the same file invalidates it. Writes made elsewhere
 * (the Sheets UI, other clients without this cache) are only picked up when entries expire, or after
 * {@link #invalidate} / {@link #clear}.
 *
 * <p>One instance (one JDBC connection) per database file per JVM, shared via {@link #open}.
 * Thread-safe.</p>
 */
public final class SqliteResponseCache implements AutoCloseable {

    private static final Map<Path, SqliteResponseCache> OPEN = new ConcurrentHashMap<>();

    private final Path file;
    private final Connection connection;

    /** A cached reply and when it stops being fresh. */
    public record Entry(String reply, Instant cachedAt, Instant expiresAt) {
        public boolean isFresh(Instant now) {
            return expiresAt.isAfter(now);
        }
    }

    /** A worksheet an entry was read from. */
    public record SheetRef(String spreadsheetId, String worksheet) {
    }

    private SqliteResponseCache(Path file) {
        this.file = file;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA busy_timeout = 5000");
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA foreign_keys = ON");
                s.execute("CREATE TABLE IF NOT EXISTS hs_response ("
                        + "cache_key TEXT PRIMARY KEY, reply TEXT NOT NULL, "
                        + "cached_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS hs_response_sheet ("
                        + "cache_key TEXT NOT NULL REFERENCES hs_response(cache_key) ON DELETE CASCADE, "
                        + "spreadsheet_id TEXT NOT NULL, worksheet TEXT NOT NULL, "
                        + "PRIMARY KEY (cache_key, spreadsheet_id, worksheet))");
                s.execute("CREATE INDEX IF NOT EXISTS hs_response_sheet_by_sheet "
                        + "ON hs_response_sheet (spreadsheet_id, worksheet)");
            }
        } catch (SQLException | IOException e) {
            throw new HibernateSheetsException("Cannot open response cache " + file + ": " + e.getMessage(), e, false);
        }
    }

    /** The shared cache for {@code file}, opened (and created) on first use. */
    public static SqliteResponseCache open(Path file) {
        Path key = file.toAbsolutePath().normalize();
        return OPEN.compute(key, (k, existing) -> existing != null && !existing.isClosed()
                ? existing : new SqliteResponseCache(k));
    }

    /** {@code ~/.hibernate-sheets/cache.db} */
    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".hibernate-sheets", "cache.db");
    }

    public Path file() {
        return file;
    }

    public synchronized Optional<Entry> get(String key) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT reply, cached_at, expires_at FROM hs_response WHERE cache_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Entry(rs.getString(1),
                        Instant.ofEpochMilli(rs.getLong(2)), Instant.ofEpochMilli(rs.getLong(3))));
            }
        } catch (SQLException e) {
            throw failure("read", e);
        }
    }

    public synchronized void put(String key, String reply, Instant cachedAt, Instant expiresAt,
                                 Collection<SheetRef> sheets) {
        inTransaction(() -> {
            try (PreparedStatement del = connection.prepareStatement("DELETE FROM hs_response WHERE cache_key = ?");
                 PreparedStatement ins = connection.prepareStatement(
                         "INSERT INTO hs_response (cache_key, reply, cached_at, expires_at) VALUES (?, ?, ?, ?)");
                 PreparedStatement tag = connection.prepareStatement(
                         "INSERT OR IGNORE INTO hs_response_sheet (cache_key, spreadsheet_id, worksheet) "
                                 + "VALUES (?, ?, ?)")) {
                del.setString(1, key);
                del.executeUpdate();
                ins.setString(1, key);
                ins.setString(2, reply);
                ins.setLong(3, cachedAt.toEpochMilli());
                ins.setLong(4, expiresAt.toEpochMilli());
                ins.executeUpdate();
                for (SheetRef sheet : sheets) {
                    tag.setString(1, key);
                    tag.setString(2, sheet.spreadsheetId());
                    tag.setString(3, sheet.worksheet());
                    tag.executeUpdate();
                }
            }
        }, "write");
    }

    /** Drops every entry that read {@code worksheet}. @return number of entries removed */
    public synchronized int invalidate(String spreadsheetId, String worksheet) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM hs_response WHERE cache_key IN (SELECT cache_key FROM hs_response_sheet "
                        + "WHERE spreadsheet_id = ? AND worksheet = ?)")) {
            ps.setString(1, spreadsheetId);
            ps.setString(2, worksheet);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("invalidate", e);
        }
    }

    /** Drops entries that expired before {@code now}. NETWORK_FIRST can no longer fall back to them. */
    public synchronized int purgeExpired(Instant now) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM hs_response WHERE expires_at <= ?")) {
            ps.setLong(1, now.toEpochMilli());
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("purge", e);
        }
    }

    public synchronized void clear() {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("DELETE FROM hs_response");
        } catch (SQLException e) {
            throw failure("clear", e);
        }
    }

    @Override
    public synchronized void close() {
        OPEN.remove(file, this);
        try {
            connection.close();
        } catch (SQLException e) {
            throw failure("close", e);
        }
    }

    private boolean isClosed() {
        try {
            return connection.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }

    private interface SqlWork {
        void run() throws SQLException;
    }

    private void inTransaction(SqlWork work, String what) {
        try {
            connection.setAutoCommit(false);
            try {
                work.run();
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw failure(what, e);
        }
    }

    private HibernateSheetsException failure(String what, SQLException e) {
        return new HibernateSheetsException("Response cache " + what + " failed (" + file + "): " + e.getMessage(),
                e, false);
    }
}
