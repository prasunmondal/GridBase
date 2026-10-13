package io.github.prasunmondal.gridbase.cachingTests;

import io.github.prasunmondal.gridbase.GridBase;
import io.github.prasunmondal.gridbase.GridBaseTable;
import io.github.prasunmondal.gridbase.SheetProperties;
import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.cache.CacheBackend;
import io.github.prasunmondal.gridbase.cache.InMemoryResponseCache;
import io.github.prasunmondal.gridbase.cache.ResponseCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Erasing cached data on demand: one tab ({@code clearCache} / {@code clearTableCache}), everything in one
 * store ({@code clearAllCache}) or every store in the process ({@code GridBase.deleteAllCache}).
 */
class CacheClearTest extends CachingTestBase {

    private String userHome;

    /** {@code deleteAllCache} also clears the default cache file: keep it away from the real one. */
    @BeforeEach
    void isolateDefaultFile() {
        userHome = System.getProperty("user.home");
        System.setProperty("user.home", dir.resolve("home").toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty("user.home", userHome);
    }

    private static final class Products extends GridBaseTable<Product> {
        Products(SheetProperties properties) {
            super(properties, Product.class);
        }
    }

    private void readEverything(SheetProperties p) {
        nameOf(p, "P1");
        p.client().worksheet(SHEET, ORDERS).select().fetch();
        p.client().worksheet(OTHER_SHEET, PRODUCTS).select().fetch();
    }

    @Test
    @DisplayName("Worksheet.clearCache erases only that tab of that spreadsheet")
    void worksheetClearCacheErasesOneTab() {
        SheetProperties p = cached();
        readEverything(p);
        renameDirectly("P1", "Green Tea");

        assertTrue(p.worksheet().clearCache() > 0);
        assertEquals("Green Tea", nameOf(p, "P1"));                       // fresh from the network
        assertCalls(4);

        readEverything(p);                                                 // Orders and SS2/Products still cached
        assertCalls(4);
    }

    @Test
    @DisplayName("clearAllCache erases every tab of every spreadsheet")
    void clearAllCacheErasesEverything() {
        SheetProperties p = cached();
        readEverything(p);
        renameDirectly("P1", "Green Tea");

        p.clearAllCache();
        readEverything(p);
        assertCalls(6);
        assertEquals("Green Tea", nameOf(p, "P1"));
        assertCalls(6);
    }

    @Test
    @DisplayName("GridBaseTable.clearTableCache erases its tab; clearAllCache erases the rest too")
    void gridBaseTable() {
        SheetProperties p = cached();
        Products products = new Products(p);
        products.repository().findById("P1");
        readEverything(p);                                                 // its P1 read is the same query: a hit
        assertCalls(3);
        renameDirectly("P1", "Green Tea");

        assertTrue(products.clearTableCache() > 0);
        assertEquals("Green Tea", products.repository().findById("P1").orElseThrow().name);
        p.client().worksheet(SHEET, ORDERS).select().fetch();              // untouched
        assertCalls(4);

        products.clearAllCache();
        p.client().worksheet(SHEET, ORDERS).select().fetch();
        assertCalls(5);
    }

    @Test
    @DisplayName("without a cache, clearing does nothing")
    void withoutCache() {
        SheetProperties p = track(base().build());
        Worksheet products = p.worksheet();

        assertEquals(0, products.clearCache());
        p.clearAllCache();
        assertEquals(0, new Products(p).clearTableCache());
        assertCalls(0);
    }

    @Test
    @DisplayName("GridBase.deleteAllCache erases every open store: any file, backend or custom store")
    void deleteAllCacheErasesEveryStore() {
        SheetProperties first = cached();
        SheetProperties second = cached(b -> b.cacheBackend(CacheBackend.JOURNAL).cacheFile(dir.resolve("other.journal")));
        SheetProperties memory = cached(b -> b.cacheBackend(CacheBackend.MEMORY).cacheFile(dir.resolve("memory")));
        SheetProperties custom = cached(b -> b.cacheStore(new CustomStore()));
        List<SheetProperties> all = List.of(first, second, memory, custom);
        all.forEach(p -> nameOf(p, "P1"));
        int calls = engine.calls();
        all.forEach(p -> nameOf(p, "P1"));
        assertCalls(calls);                                                // all cached
        renameDirectly("P1", "Green Tea");

        GridBase.deleteAllCache();
        for (SheetProperties p : all) {
            assertEquals("Green Tea", nameOf(p, "P1"));
        }
        assertCalls(calls + all.size());
    }

    @Test
    @DisplayName("GridBase.deleteAllCache erases the default cache file even before it is opened")
    void deleteAllCacheErasesDefaultFile() {
        Path file = CacheBackend.defaultFile();
        ResponseCache store = CacheBackend.AUTO.open(file);
        store.put("k", "{}", Instant.EPOCH, Instant.EPOCH.plusSeconds(1), List.of());
        store.close();

        GridBase.deleteAllCache();
        ResponseCache reopened = CacheBackend.AUTO.open(file);
        try {
            assertFalse(reopened.get("k").isPresent());
        } finally {
            reopened.close();
        }
    }

    @Test
    @DisplayName("GridBase.deleteAllCache without any cache does nothing")
    void deleteAllCacheWithoutCache() {
        SheetProperties p = track(base().build());
        nameOf(p, "P1");
        GridBase.deleteAllCache();
        nameOf(p, "P1");
        assertCalls(2);
    }

    /** A store the SDK knows nothing about, as an app would plug in (e.g. Room). */
    private static final class CustomStore implements ResponseCache {
        private final InMemoryResponseCache inner = new InMemoryResponseCache();

        @Override public Optional<Entry> get(String key) { return inner.get(key); }
        @Override public void put(String key, String reply, Instant cachedAt, Instant expiresAt,
                                  Collection<SheetRef> sheets) { inner.put(key, reply, cachedAt, expiresAt, sheets); }
        @Override public int invalidate(String spreadsheetId, String worksheet) { return inner.invalidate(spreadsheetId, worksheet); }
        @Override public int purgeExpired(Instant now) { return inner.purgeExpired(now); }
        @Override public void clear() { inner.clear(); }
        @Override public void close() { inner.close(); }
    }
}
