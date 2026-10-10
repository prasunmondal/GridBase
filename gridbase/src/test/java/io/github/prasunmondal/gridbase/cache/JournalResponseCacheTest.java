package io.github.prasunmondal.gridbase.cache;

import io.github.prasunmondal.gridbase.cache.ResponseCache.SheetRef;
import io.github.prasunmondal.gridbase.exception.GridBaseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalResponseCacheTest {

    @TempDir
    Path dir;

    private static final Instant T0 = Instant.parse("2026-10-01T04:30:00Z");
    private static final SheetRef EMP = new SheetRef("S", "Emp");
    private static final SheetRef DEPT = new SheetRef("S", "Dept");
    private final List<ResponseCache> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        opened.forEach(ResponseCache::close);
    }

    private JournalResponseCache open() {
        JournalResponseCache c = JournalResponseCache.open(dir.resolve("cache.journal"));
        opened.add(c);
        return c;
    }

    /** Simulates an app restart: drops the JVM-wide instance and replays the file. */
    private JournalResponseCache reopen(JournalResponseCache c) {
        c.close();
        return open();
    }

    private static void put(ResponseCache c, String key, String reply, Instant expiresAt, SheetRef... sheets) {
        c.put(key, reply, T0, expiresAt, List.of(sheets));
    }

    @Test
    void entriesSurviveReopen() {
        JournalResponseCache c = open();
        put(c, "k1", "{\"v\":1}", T0.plusSeconds(60), EMP);
        put(c, "k2", "ünïcödé ✓", T0.plusSeconds(60), EMP, DEPT);

        c = reopen(c);
        assertEquals("{\"v\":1}", c.get("k1").orElseThrow().reply());
        assertEquals("ünïcödé ✓", c.get("k2").orElseThrow().reply());
        assertEquals(T0.plusSeconds(60), c.get("k1").orElseThrow().expiresAt());
        assertEquals(1, c.invalidate("S", "Dept"));
    }

    @Test
    void replacingAKeyKeepsOnlyTheNewReplyAndTags() {
        JournalResponseCache c = open();
        put(c, "k", "old", T0.plusSeconds(60), EMP);
        put(c, "k", "new", T0.plusSeconds(60), DEPT);

        c = reopen(c);
        assertEquals("new", c.get("k").orElseThrow().reply());
        assertEquals(0, c.invalidate("S", "Emp"));
        assertEquals(1, c.invalidate("S", "Dept"));
    }

    @Test
    void invalidatePurgeAndClearArePersisted() {
        JournalResponseCache c = open();
        put(c, "emp", "e", T0.plusSeconds(600), EMP);
        put(c, "dept", "d", T0.plusSeconds(600), DEPT);
        put(c, "old", "o", T0.plusSeconds(5), DEPT);
        assertEquals(1, c.invalidate("S", "Emp"));
        assertEquals(1, c.purgeExpired(T0.plusSeconds(10)));

        c = reopen(c);
        assertTrue(c.get("emp").isEmpty());
        assertTrue(c.get("old").isEmpty());
        assertEquals("d", c.get("dept").orElseThrow().reply());

        c.clear();
        c = reopen(c);
        assertTrue(c.get("dept").isEmpty());
    }

    @Test
    void tornTailIsDroppedAndEarlierEntriesSurvive() throws Exception {
        JournalResponseCache c = open();
        put(c, "k1", "first", T0.plusSeconds(60), EMP);
        put(c, "k2", "second", T0.plusSeconds(60), EMP);
        c.close();
        Path file = dir.resolve("cache.journal");
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(raf.length() - 3); // app killed mid-write
        }

        c = open();
        assertEquals("first", c.get("k1").orElseThrow().reply());
        assertTrue(c.get("k2").isEmpty());
        put(c, "k3", "after", T0.plusSeconds(60), EMP); // appends cleanly after the cut
        c = reopen(c);
        assertEquals("after", c.get("k3").orElseThrow().reply());
    }

    @Test
    void corruptedRecordStopsReplayThere() throws Exception {
        JournalResponseCache c = open();
        put(c, "k1", "first", T0.plusSeconds(60), EMP);
        long goodBytes = c.journalBytes();
        put(c, "k2", "second", T0.plusSeconds(60), EMP);
        c.close();
        Path file = dir.resolve("cache.journal");
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(goodBytes + 12);
            raf.write(0x7F); // flip a byte inside the second record's body
        }

        c = open();
        assertEquals("first", c.get("k1").orElseThrow().reply());
        assertTrue(c.get("k2").isEmpty());
        assertEquals(goodBytes, Files.size(file));
    }

    @Test
    void compactionKeepsLiveEntriesAndShrinksTheFile() {
        JournalResponseCache c = open();
        String big = "x".repeat(64 * 1024);
        for (int i = 0; i < 40; i++) {       // ~2.5 MB of superseded replies for one key
            put(c, "hot", big + i, T0.plusSeconds(60), EMP);
        }
        put(c, "keep", "kept", T0.plusSeconds(60), DEPT);

        assertTrue(c.journalBytes() < JournalResponseCache.MIN_COMPACT_BYTES, "compacted automatically");
        c = reopen(c);
        assertEquals(big + 39, c.get("hot").orElseThrow().reply());
        assertEquals("kept", c.get("keep").orElseThrow().reply());
        assertEquals(1, c.invalidate("S", "Emp"));
    }

    @Test
    void sharedPerFileWithinTheJvm() {
        JournalResponseCache a = open();
        JournalResponseCache b = open();
        assertTrue(a == b);
        put(a, "k", "v", T0.plusSeconds(60), EMP);
        assertEquals(1, b.invalidate("S", "Emp"));
    }

    @Test
    void refusesFilesThatAreNotJournals() throws Exception {
        Path sqlite = dir.resolve("cache.db");
        Files.write(sqlite, "SQLite format 3\0".getBytes());
        assertThrows(GridBaseException.class, () -> JournalResponseCache.open(sqlite));
        assertThrows(GridBaseException.class, () -> JournalResponseCache.open(dir));
    }

    @Test
    void closedCacheFailsInsteadOfServingNothing() {
        JournalResponseCache c = open();
        c.close();
        assertThrows(GridBaseException.class, () -> c.get("k"));
        assertThrows(GridBaseException.class, () -> put(c, "k", "v", T0.plusSeconds(60), EMP));
    }

    @Test
    void memoryBackendSharesByNameButPersistsNothing() {
        ResponseCache a = CacheBackend.MEMORY.open(dir.resolve("mem"));
        opened.add(a);
        put(a, "k", "v", T0.plusSeconds(60), EMP);
        assertEquals("v", CacheBackend.MEMORY.open(dir.resolve("mem")).get("k").orElseThrow().reply());
        a.close();
        ResponseCache fresh = CacheBackend.MEMORY.open(dir.resolve("mem"));
        opened.add(fresh);
        assertFalse(fresh.get("k").isPresent());
        assertFalse(Files.exists(dir.resolve("mem")));
    }

    @Test
    void autoUsesSqliteOnTheJvm() {
        ResponseCache c = CacheBackend.AUTO.open(dir.resolve("auto.db"));
        opened.add(c);
        assertTrue(c instanceof SqliteResponseCache);
    }
}
