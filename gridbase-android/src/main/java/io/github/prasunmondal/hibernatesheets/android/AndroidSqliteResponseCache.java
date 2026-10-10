package io.github.prasunmondal.hibernatesheets.android;

import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import io.github.prasunmondal.hibernatesheets.cache.CacheBackend;
import io.github.prasunmondal.hibernatesheets.cache.SqlResponseCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Response cache in the Android platform's own SQLite ({@code android.database.sqlite}): nothing native
 * to ship, so it works on every device and ABI. Same schema and file format as
 * {@link io.github.prasunmondal.hibernatesheets.cache.SqliteResponseCache}.
 *
 * <p>Normally picked automatically: with this artifact on the classpath, {@link CacheBackend#AUTO}
 * uses it on Android. Select it explicitly with {@code .cacheBackend(CacheBackend.ANDROID_SQLITE)}.
 * No {@code Context} is needed, only a file path ({@code SheetProperties.cacheFile(...)}).</p>
 *
 * <p>One instance per database file per process, shared via {@link #open}. Thread-safe.</p>
 */
public final class AndroidSqliteResponseCache extends SqlResponseCache {

    private static final Map<Path, AndroidSqliteResponseCache> OPEN = new ConcurrentHashMap<>();

    private AndroidSqliteResponseCache(Path file) {
        super(file, connect(file));
    }

    /** The shared cache for {@code file}, opened (and created) on first use. */
    public static AndroidSqliteResponseCache open(Path file) {
        Path key = file.toAbsolutePath().normalize();
        return OPEN.compute(key, (k, existing) -> existing != null && !existing.isClosed()
                ? existing : new AndroidSqliteResponseCache(k));
    }

    @Override
    public synchronized void close() {
        OPEN.remove(file(), this);
        super.close();
    }

    private static Sql connect(Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            SQLiteDatabase db = SQLiteDatabase.openDatabase(file.toString(), null,
                    SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
            try {
                db.setForeignKeyConstraintsEnabled(true); // ON DELETE CASCADE of the sheet tags
            } catch (RuntimeException e) {
                db.close();
                throw e;
            }
            return new Platform(db);
        } catch (IOException | RuntimeException e) {
            throw cannotOpen(file, e);
        }
    }

    /** {@link Sql} over one {@link SQLiteDatabase}, which serialises access itself. */
    private static final class Platform implements Sql {
        private final SQLiteDatabase db;

        Platform(SQLiteDatabase db) {
            this.db = db;
        }

        @Override
        public void execute(String sql) {
            try {
                db.execSQL(sql);
            } catch (SQLException | IllegalStateException e) {
                throw new SqlFailure(e);
            }
        }

        @Override
        public int update(String sql, Object... args) {
            SQLiteStatement statement = null;
            try {
                statement = db.compileStatement(sql);
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Long l) {
                        statement.bindLong(i + 1, l);
                    } else {
                        statement.bindString(i + 1, (String) args[i]);
                    }
                }
                return statement.executeUpdateDelete();
            } catch (SQLException | IllegalStateException e) {
                throw new SqlFailure(e);
            } finally {
                if (statement != null) {
                    statement.close();
                }
            }
        }

        @Override
        public Optional<String[]> queryRow(String sql, Object... args) {
            String[] selection = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                selection[i] = String.valueOf(args[i]);
            }
            Cursor cursor = null;
            try {
                cursor = db.rawQuery(sql, selection);
                if (!cursor.moveToFirst()) {
                    return Optional.empty();
                }
                String[] row = new String[cursor.getColumnCount()];
                for (int i = 0; i < row.length; i++) {
                    row[i] = cursor.getString(i);
                }
                return Optional.of(row);
            } catch (SQLException | IllegalStateException e) {
                throw new SqlFailure(e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        @Override
        public void inTransaction(Runnable work) {
            try {
                db.beginTransaction();
                try {
                    work.run();
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction(); // rolls back unless marked successful
                }
            } catch (SQLException | IllegalStateException e) {
                throw new SqlFailure(e);
            }
        }

        @Override
        public boolean isClosed() {
            return !db.isOpen();
        }

        @Override
        public void close() {
            db.close();
        }
    }
}
