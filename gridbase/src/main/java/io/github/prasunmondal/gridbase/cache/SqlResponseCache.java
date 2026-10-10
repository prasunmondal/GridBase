package io.github.prasunmondal.gridbase.cache;

import io.github.prasunmondal.gridbase.exception.GridBaseException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

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
    }

    /** The error to throw when a database file can't be opened. */
    protected static GridBaseException cannotOpen(Path file, Throwable e) {
        return new GridBaseException("Cannot open response cache " + file + ": " + e.getMessage(), e, false);
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized Optional<Entry> get(String key) {
        try {
            return sql.queryRow("SELECT reply, cached_at, expires_at FROM hs_response WHERE cache_key = ?", key)
                    .map(r -> new Entry(r[0], Instant.ofEpochMilli(Long.parseLong(r[1])),
                            Instant.ofEpochMilli(Long.parseLong(r[2]))));
        } catch (RuntimeException e) {
            throw failure("read", e);
        }
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
