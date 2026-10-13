package io.github.prasunmondal.gridbase.cache;

import io.github.prasunmondal.gridbase.exception.GridBaseException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ResponseCache} in a SQLite database, independent of the driver: the schema and every statement
 * live here, and each driver only implements {@link Sql}. Used by {@link SqliteResponseCache} (JDBC) and
 * by {@code AndroidSqliteResponseCache} in {@code gridbase-android} (the platform's
 * {@code android.database.sqlite}), so both read and write the same file format. Thread-safe.
 */
public abstract class SqlResponseCache implements ResponseCache {

    /**
     * The few database operations the cache needs. Arguments are {@link String} or {@link Long}.
     * Driver errors are thrown as {@link SqlFailure}.
     */
    public interface Sql {

        /** Runs a statement without arguments (DDL). */
        void execute(String sql);

        /** Runs an INSERT / UPDATE / DELETE. @return number of rows changed */
        int update(String sql, Object... args);

        /** The first row of a query, every column read as text; empty if there is none. */
        Optional<String[]> queryRow(String sql, Object... args);

        /** Runs {@code work} in one transaction, rolled back if it throws. */
        void inTransaction(Runnable work);

        boolean isClosed();

        void close();
    }

    /** A driver error, wrapped so {@link Sql} methods need no checked exceptions. */
    public static final class SqlFailure extends RuntimeException {
        public SqlFailure(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    private final Path file;
    private final Sql sql;

    /** Creates the schema if needed; closes {@code sql} and throws if that fails. */
    protected SqlResponseCache(Path file, Sql sql) {
        this.file = file;
        this.sql = sql;
        try {
            sql.execute("CREATE TABLE IF NOT EXISTS hs_response ("
                    + "cache_key TEXT PRIMARY KEY, reply TEXT NOT NULL, "
                    + "cached_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)");
            sql.execute("CREATE TABLE IF NOT EXISTS hs_response_sheet ("
                    + "cache_key TEXT NOT NULL REFERENCES hs_response(cache_key) ON DELETE CASCADE, "
                    + "spreadsheet_id TEXT NOT NULL, worksheet TEXT NOT NULL, "
                    + "PRIMARY KEY (cache_key, spreadsheet_id, worksheet))");
            sql.execute("CREATE INDEX IF NOT EXISTS hs_response_sheet_by_sheet "
                    + "ON hs_response_sheet (spreadsheet_id, worksheet)");
        } catch (RuntimeException e) {
            try {
                sql.close();
            } catch (RuntimeException ignored) {
                // reporting the original failure
            }
            throw cannotOpen(file, e);
        }
        OpenStores.add(this);
    }

    /** The error to throw when a database file can't be opened. */
    protected static GridBaseException cannotOpen(Path file, Throwable e) {
        return new GridBaseException("Cannot open response cache " + file + ": " + e.getMessage(), e, false);
    }

    public Path file() {
        return file;
    }

    /**
     * Longest piece of a reply read by one query. Android's {@code CursorWindow} (2 MB by default) must hold
     * a whole result row, so a large reply read in one piece fails with "Row too big to fit into
     * CursorWindow". 256K characters stay under 1 MB of UTF-8 even at 4 bytes per character.
     */
    static final int READ_CHUNK_CHARS = 256 * 1024;

    @Override
    public synchronized Optional<Entry> get(String key) {
        try {
            AtomicReference<Entry> found = new AtomicReference<>();
            // one transaction, so every chunk comes from the same version of the row
            sql.inTransaction(() -> sql.queryRow("SELECT length(reply), cached_at, expires_at, substr(reply, 1, ?) "
                            + "FROM hs_response WHERE cache_key = ?", (long) READ_CHUNK_CHARS, key)
                    .ifPresent(r -> found.set(new Entry(readRest(key, Long.parseLong(r[0]), r[3]),
                            Instant.ofEpochMilli(Long.parseLong(r[1])),
                            Instant.ofEpochMilli(Long.parseLong(r[2]))))));
            return Optional.ofNullable(found.get());
        } catch (RuntimeException e) {
            throw failure("read", e);
        }
    }

    /**
     * The whole reply of {@code length} characters, given its first chunk. SQLite's {@code length} and
     * {@code substr} count code points, not Java {@code char}s, so the offset is kept in code points.
     */
    private String readRest(String key, long length, String first) {
        long read = first.codePointCount(0, first.length());
        if (read >= length) {
            return first;
        }
        StringBuilder reply = new StringBuilder((int) Math.min(Integer.MAX_VALUE, length)).append(first);
        while (read < length) {
            String chunk = sql.queryRow("SELECT substr(reply, ?, ?) FROM hs_response WHERE cache_key = ?",
                            read + 1, (long) READ_CHUNK_CHARS, key)
                    .map(r -> r[0])
                    .orElse("");
            if (chunk.isEmpty()) {
                throw new IllegalStateException("cached reply ended after " + read + " of " + length
                        + " characters");
            }
            reply.append(chunk);
            read += chunk.codePointCount(0, chunk.length());
        }
        return reply.toString();
    }

    @Override
    public synchronized void put(String key, String reply, Instant cachedAt, Instant expiresAt,
                                 Collection<SheetRef> sheets) {
        try {
            sql.inTransaction(() -> {
                sql.update("DELETE FROM hs_response WHERE cache_key = ?", key);
                sql.update("INSERT INTO hs_response (cache_key, reply, cached_at, expires_at) VALUES (?, ?, ?, ?)",
                        key, reply, cachedAt.toEpochMilli(), expiresAt.toEpochMilli());
                for (SheetRef sheet : sheets) {
                    sql.update("INSERT OR IGNORE INTO hs_response_sheet (cache_key, spreadsheet_id, worksheet) "
                            + "VALUES (?, ?, ?)", key, sheet.spreadsheetId(), sheet.worksheet());
                }
            });
        } catch (RuntimeException e) {
            throw failure("write", e);
        }
    }

    @Override
    public synchronized int invalidate(String spreadsheetId, String worksheet) {
        try {
            return sql.update("DELETE FROM hs_response WHERE cache_key IN (SELECT cache_key FROM hs_response_sheet "
                    + "WHERE spreadsheet_id = ? AND worksheet = ?)", spreadsheetId, worksheet);
        } catch (RuntimeException e) {
            throw failure("invalidate", e);
        }
    }

    @Override
    public synchronized int purgeExpired(Instant now) {
        try {
            return sql.update("DELETE FROM hs_response WHERE expires_at <= ?", now.toEpochMilli());
        } catch (RuntimeException e) {
            throw failure("purge", e);
        }
    }

    @Override
    public synchronized void clear() {
        try {
            sql.update("DELETE FROM hs_response");
        } catch (RuntimeException e) {
            throw failure("clear", e);
        }
    }

    /** Closes the database. Subclasses that share instances per file also forget this one. */
    @Override
    public synchronized void close() {
        OpenStores.remove(this);
        try {
            sql.close();
        } catch (RuntimeException e) {
            throw failure("close", e);
        }
    }

    protected synchronized boolean isClosed() {
        try {
            return sql.isClosed();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private GridBaseException failure(String what, RuntimeException e) {
        if (e instanceof GridBaseException h) {
            return h;
        }
        return new GridBaseException("Response cache " + what + " failed (" + file + "): " + e.getMessage(),
                e instanceof SqlFailure ? e.getCause() : e, false);
    }
}
