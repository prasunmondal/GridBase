package io.github.prasunmondal.gridbase.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SqlResponseCache} through the JDBC driver; the Android driver runs the same SQL. */
class SqlResponseCacheTest {

    @TempDir
    Path dir;

    private SqliteResponseCache cache;

    @AfterEach
    void close() {
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    void repliesLargerThanOneChunkAreReadBackWhole() {
        // Android's CursorWindow can't hold a multi-MB row, so the reply is read in chunks.
        // Multibyte characters (é, 😀 as a surrogate pair) straddle chunk boundaries.
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (sb.length() < SqlResponseCache.READ_CHUNK_CHARS * 3 + 17) {
            sb.append("{\"row\":").append(i++).append(",\"name\":\"é😀ü\"},");
        }
        String reply = sb.toString();
        cache = SqliteResponseCache.open(dir.resolve("cache.db"));
        Instant now = Instant.ofEpochMilli(1_000);
        cache.put("big", reply, now, now.plusSeconds(60), Collections.emptyList());

        ResponseCache.Entry entry = cache.get("big").orElseThrow();
        assertEquals(reply, entry.reply());
        assertEquals(now, entry.cachedAt());
        assertEquals(now.plusSeconds(60), entry.expiresAt());
    }

    @Test
    void smallAndEmptyRepliesAndMisses() {
        cache = SqliteResponseCache.open(dir.resolve("cache.db"));
        Instant now = Instant.ofEpochMilli(1_000);
        cache.put("small", "{\"ok\":true}", now, now, Collections.emptyList());
        cache.put("empty", "", now, now, Collections.emptyList());

        assertEquals("{\"ok\":true}", cache.get("small").orElseThrow().reply());
        assertEquals("", cache.get("empty").orElseThrow().reply());
        assertTrue(cache.get("missing").isEmpty());
    }
}
