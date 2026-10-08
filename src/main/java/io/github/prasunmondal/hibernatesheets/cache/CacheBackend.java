package io.github.prasunmondal.hibernatesheets.cache;

import io.github.prasunmondal.hibernatesheets.exception.HibernateSheetsException;
import io.github.prasunmondal.hibernatesheets.internal.Log;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Which {@link ResponseCache} implementation stores cached replies. */
public enum CacheBackend {

    /**
     * {@link #JOURNAL} on Android; elsewhere {@link #SQLITE}, falling back to {@link #JOURNAL} (at
     * {@code <cacheFile>.journal}) when the SQLite driver or its native library can't be
     * loaded. Other failures (e.g. an unusable file) are thrown as for {@link #SQLITE}.
     */
    AUTO,

    /** {@link SqliteResponseCache}: needs {@code sqlite-jdbc} and its native library for the platform. */
    SQLITE,

    /** {@link JournalResponseCache}: pure Java, persistent, in-memory reads. Works on any Android device. */
    JOURNAL,

    /** {@link InMemoryResponseCache}: nothing written to disk, lost when the process ends. */
    MEMORY;

    private static final Log LOG = Log.get(CacheBackend.class);

    /**
     * Opens (or reuses) the store at {@code file}; stores are shared per file within the JVM.
     *
     * @throws HibernateSheetsException if it can't be opened
     * @throws LinkageError for {@link #SQLITE} when the driver or its native library is missing
     */
    public ResponseCache open(Path file) {
        switch (this) {
            case SQLITE:
                return SqliteResponseCache.open(file);
            case JOURNAL:
                return JournalResponseCache.open(file);
            case MEMORY:
                return InMemoryResponseCache.open(file);
            default:
                if (isAndroid()) {
                    return JournalResponseCache.open(journalSibling(file));
                }
                try {
                    return SqliteResponseCache.open(file);
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

    private static Path journalSibling(Path file) {
        return file.resolveSibling(file.getFileName() + ".journal");
    }
}
