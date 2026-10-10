package io.github.prasunmondal.gridbase.cache;

import io.github.prasunmondal.gridbase.exception.GridBaseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend selection. {@code gridbase-android} is not on this module's test classpath. */
class CacheBackendTest {

    @TempDir
    Path dir;

    private final List<ResponseCache> opened = new ArrayList<>();
    private final String vmName = System.getProperty("java.vm.name");

    @AfterEach
    void restore() {
        System.setProperty("java.vm.name", vmName);
        opened.forEach(ResponseCache::close);
    }

    private ResponseCache open(CacheBackend backend, Path file) {
        ResponseCache c = backend.open(file);
        opened.add(c);
        return c;
    }

    @Test
    void androidSqliteWithoutTheModuleExplainsWhatToAdd() {
        assertFalse(CacheBackend.androidSqliteAvailable());
        GridBaseException e = assertThrows(GridBaseException.class,
                () -> CacheBackend.ANDROID_SQLITE.open(dir.resolve("cache.db")));
        assertTrue(e.getMessage().contains("gridbase-android"), e.getMessage());
    }

    @Test
    void autoOnAndroidWithoutTheModuleUsesTheJournal() {
        System.setProperty("java.vm.name", "Dalvik");
        ResponseCache c = open(CacheBackend.AUTO, dir.resolve("cache.db"));
        assertTrue(c instanceof JournalResponseCache);
        assertEquals(dir.resolve("cache.db.journal").toAbsolutePath(), ((JournalResponseCache) c).file());
    }

    @Test
    void autoOnTheJvmUsesSqlite() {
        assertTrue(open(CacheBackend.AUTO, dir.resolve("cache.db")) instanceof SqliteResponseCache);
    }
}
