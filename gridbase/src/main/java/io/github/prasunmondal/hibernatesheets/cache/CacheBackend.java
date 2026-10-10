package io.github.prasunmondal.hibernatesheets.cache;

import io.github.prasunmondal.hibernatesheets.exception.HibernateSheetsException;
import io.github.prasunmondal.hibernatesheets.internal.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/** Which {@link ResponseCache} implementation stores cached replies. */
public enum CacheBackend {

    /**
     * On Android: {@link #ANDROID_SQLITE} when {@code gridbase-android} is on the classpath, otherwise
     * {@link #JOURNAL}. Elsewhere: {@link #SQLITE}. Whenever the chosen SQLite can't be loaded at all
     * ({@link LinkageError}, e.g. a missing native library) it falls back to {@link #JOURNAL} at
     * {@code <cacheFile>.journal}. Other failures (e.g. an unusable file) are thrown as for the chosen store.
     */
    AUTO,

    /** {@link SqliteResponseCache}: needs {@code sqlite-jdbc} and its native library for the platform. */
    SQLITE,

    /**
     * {@code AndroidSqliteResponseCache} from {@code io.github.prasunmondal:gridbase-android}: the Android
     * platform's own SQLite ({@code android.database.sqlite}), so no native library is shipped. Same
     * file format as {@link #SQLITE}. Android only.
     */
    ANDROID_SQLITE,

    /** {@link JournalResponseCache}: pure Java, persistent, in-memory reads. Works on any Android device. */
    JOURNAL,

    /** {@link InMemoryResponseCache}: nothing written to disk, lost when the process ends. */
    MEMORY;

    private static final Log LOG = Log.get(CacheBackend.class);
    static final String ANDROID_SQLITE_CLASS = "io.github.prasunmondal.hibernatesheets.android.AndroidSqliteResponseCache";
    private static final AtomicBoolean WARNED_NO_ANDROID_MODULE = new AtomicBoolean();

    /**
     * Opens (or reuses) the store at {@code file}; stores are shared per file within the JVM.
     *
     * @throws HibernateSheetsException if it can't be opened
     * @throws LinkageError for {@link #SQLITE} / {@link #ANDROID_SQLITE} when the driver or its native library
     *         is missing ({@link #AUTO} falls back to {@link #JOURNAL} instead)
     */
    public ResponseCache open(Path file) {
        switch (this) {
            case SQLITE:
                return SqliteResponseCache.open(file);
            case ANDROID_SQLITE:
                return openAndroidSqlite(file);
            case JOURNAL:
                return JournalResponseCache.open(file);
            case MEMORY:
                return InMemoryResponseCache.open(file);
            default:
                if (isAndroid() && !androidSqliteAvailable()) {
                    Path journal = journalSibling(file);
                    if (WARNED_NO_ANDROID_MODULE.compareAndSet(false, true)) {
                        LOG.warning(() -> "hibernate.sheets: add io.github.prasunmondal:gridbase-android to cache in "
                                + "the platform's SQLite; using " + journal);
                    }
                    return JournalResponseCache.open(journal);
                }
                try {
                    return isAndroid() ? openAndroidSqlite(file) : SqliteResponseCache.open(file);
                } catch (LinkageError e) { // e.g. UnsatisfiedLinkError: dlopen failed: library "libsqlitejdbc.so" not found
                    Path journal = journalSibling(file);
                    LOG.warning(() -> "hibernate.sheets SQLite cache unavailable (" + e.getMessage()
                            + "); using " + journal);
                    return JournalResponseCache.open(journal);
                }
        }
    }

    /**
     * {@code ~/.hibernate-sheets/cache.db}; on Android, which has no usable home directory,
     * {@code <java.io.tmpdir>/hibernate-sheets/cache.db} (Android points {@code java.io.tmpdir} at the
     * app's cache dir).
     */
    public static Path defaultFile() {
        if (isAndroid()) {
            return Paths.get(System.getProperty("java.io.tmpdir"), "hibernate-sheets", "cache.db");
        }
        return Paths.get(System.getProperty("user.home"), ".hibernate-sheets", "cache.db");
    }

    static boolean isAndroid() {
        return "Dalvik".equals(System.getProperty("java.vm.name"))
                || String.valueOf(System.getProperty("java.vendor")).contains("Android");
    }

    static boolean androidSqliteAvailable() {
        try {
            Class.forName(ANDROID_SQLITE_CLASS);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Reflection, because the class lives in {@code gridbase-android}, which depends on this module. */
    private static ResponseCache openAndroidSqlite(Path file) {
        Method open;
        try {
            open = Class.forName(ANDROID_SQLITE_CLASS).getMethod("open", Path.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new HibernateSheetsException("CacheBackend.ANDROID_SQLITE needs the "
                    + "io.github.prasunmondal:gridbase-android dependency", e, false);
        }
        try {
            return (ResponseCache) open.invoke(null, file);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw SqlResponseCache.cannotOpen(file, cause);
        } catch (IllegalAccessException e) {
            throw SqlResponseCache.cannotOpen(file, e);
        }
    }

    private static Path journalSibling(Path file) {
        return file.resolveSibling(file.getFileName() + ".journal");
    }
}
