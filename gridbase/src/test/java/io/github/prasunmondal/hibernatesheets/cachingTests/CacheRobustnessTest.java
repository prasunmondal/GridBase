package io.github.prasunmondal.hibernatesheets.cachingTests;

import io.github.prasunmondal.hibernatesheets.SheetProperties;
import io.github.prasunmondal.hibernatesheets.exception.ServerException;
import io.github.prasunmondal.hibernatesheets.result.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cache must never make results wrong or requests fail. */
class CacheRobustnessTest extends CachingTestBase {

    @Test
    @DisplayName("engine errors are not cached: once the problem is fixed the next read succeeds")
    void engineErrorsNotCached() {
        SheetProperties p = cached();
        engine.failRequestsTouching(PRODUCTS);
        assertThrows(ServerException.class, () -> allProducts(p));

        engine.stopFailing();
        assertEquals(5, allProducts(p).size());
        assertEquals(5, allProducts(p).size());
        assertCalls(2);
    }

    @Test
    @DisplayName("if the cache is closed underneath the client, requests still work (from the network)")
    void closedCacheFallsBackToNetwork() {
        SheetProperties p = cached();
        allProducts(p);
        p.cache().orElseThrow().close();

        assertEquals(5, allProducts(p).size());
        assertEquals(5, allProducts(p).size());
        assertCalls(3);
    }

    @Test
    @DisplayName("an unusable cache file disables caching instead of failing the client")
    void unusableCacheFileDisablesCaching() {
        SheetProperties p = cached(b -> b.cacheFile(dir));    // a directory, not a database file

        assertTrue(p.cache().isEmpty());
        assertEquals(5, allProducts(p).size());
        assertEquals(5, allProducts(p).size());
        assertCalls(2);
    }

    @Test
    @DisplayName("concurrent readers all get correct data and the entries end up cached")
    void concurrentReaders() throws Exception {
        SheetProperties p = cached();
        Map<String, String> expected = Map.of("P1", "Tea", "P2", "Coffee", "P3", "Sugar", "P4", "Milk", "P5", "Honey");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            done.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 25; i++) {
                    for (Map.Entry<String, String> e : expected.entrySet()) {
                        assertEquals(e.getValue(), nameOf(p, e.getKey()));
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();

        int calls = engine.calls();
        assertTrue(calls >= 5 && calls <= 8 * 5, "between one and eight misses per query, was " + calls);
        expected.keySet().forEach(sku -> nameOf(p, sku));
        assertCalls(calls);
    }

    @Test
    @DisplayName("large replies (3,000 rows) round-trip through the cache intact")
    void largeReply() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            rows.add(row("sku", "B" + i, "name", "Bulk item " + i, "stock", i, "price", i / 10.0,
                    "active", i % 2 == 0, "addedOn", "", "notes", "x".repeat(i % 50)));
        }
        engine.sheet(SHEET, "Big", PRODUCT_COLUMNS, rows);
        SheetProperties p = cached(b -> b.tabName("Big"));

        List<Row> network = p.worksheet().select().fetch();
        List<Row> cache = p.worksheet().select().fetch();
        assertCalls(1);
        assertEquals(3000, cache.size());
        assertEquals(network, cache);
    }

    @Test
    @DisplayName("unicode, quotes, newlines and backslashes survive the cache")
    void specialCharacters() {
        String tricky = "चाय \"special\" ☕ — it's\nline two \\ / , ; {json: [1]}";
        renameDirectly("P1", tricky);
        SheetProperties p = cached();

        assertEquals(tricky, nameOf(p, "P1"));
        assertEquals(tricky, nameOf(p, "P1"));
        assertCalls(1);
    }

    @Test
    @DisplayName("cached replies are re-parsed per client, so each client's time zone is applied correctly")
    void timeZonePerClient() {
        SheetProperties ist = cached();
        SheetProperties utc = cached(b -> b.timeZone(ZoneOffset.UTC));
        assertNotSame(ist.client(), utc.client());

        LocalDate inIst = products(ist).findById("P1").orElseThrow().addedOn;
        LocalDate inUtc = products(utc).findById("P1").orElseThrow().addedOn;   // served from ist's entry
        assertCalls(1);
        assertEquals(LocalDate.of(2026, 1, 15), inIst);
        assertEquals(LocalDate.of(2026, 1, 14), inUtc);
    }

    @Test
    @DisplayName("a missing worksheet error is not cached and does not affect other worksheets")
    void missingWorksheetIsolated() {
        SheetProperties p = cached();
        allProducts(p);

        assertThrows(ServerException.class, () -> p.client().worksheet(SHEET, "Nope").select().fetch());
        assertThrows(ServerException.class, () -> p.client().worksheet(SHEET, "Nope").select().fetch());
        allProducts(p);
        assertCalls(3);
    }
}
