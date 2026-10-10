package io.github.prasunmondal.gridbase.cachingTests;

import io.github.prasunmondal.gridbase.SheetProperties;
import io.github.prasunmondal.gridbase.query.Sort;
import io.github.prasunmondal.gridbase.result.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static io.github.prasunmondal.gridbase.query.Filters.gt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Turning caching on and off, and what counts as "the same request". */
class CacheModeTest extends CachingTestBase {

    @Test
    @DisplayName("caching is off by default: every read goes to the network and no cache file is created")
    void offByDefault() {
        SheetProperties p = track(base().build());

        assertFalse(p.shallCache());
        assertTrue(p.cache().isEmpty());
        allProducts(p);
        allProducts(p);
        assertCalls(2);
        assertFalse(Files.exists(dir.resolve("cache.db")));
    }

    @Test
    @DisplayName("shallCache(false): every read goes to the network")
    void shallCacheFalse() {
        SheetProperties p = track(base().shallCache(false).build());

        for (int i = 0; i < 3; i++) {
            assertEquals("Tea", nameOf(p, "P1"));
        }
        assertCalls(3);
        assertTrue(p.cache().isEmpty());
    }

    @Test
    @DisplayName("shallCache(true): a repeated read is served from the SQLite cache")
    void shallCacheTrue() {
        SheetProperties p = cached();

        for (int i = 0; i < 3; i++) {
            assertEquals("Tea", nameOf(p, "P1"));
        }
        assertCalls(1);
        assertTrue(p.cache().isPresent());
        assertTrue(Files.exists(dir.resolve("cache.db")));
    }

    @Test
    @DisplayName("a cached reply maps to exactly the same rows and POJOs as the network reply")
    void cachedResultEqualsNetworkResult() {
        SheetProperties p = cached();

        List<Row> fromNetwork = allProducts(p);
        List<Row> fromCache = allProducts(p);
        assertCalls(1);
        assertEquals(fromNetwork, fromCache);

        List<Product> networkPojos = products(p).findAll();
        List<Product> cachedPojos = products(p).findAll();
        assertCalls(2);
        assertEquals(5, cachedPojos.size());
        for (int i = 0; i < networkPojos.size(); i++) {
            Product n = networkPojos.get(i);
            Product c = cachedPojos.get(i);
            assertEquals(n.sku, c.sku);
            assertEquals(n.name, c.name);
            assertEquals(n.stock, c.stock);
            assertEquals(0, n.price.compareTo(c.price));
            assertEquals(n.active, c.active);
            assertEquals(n.addedOn, c.addedOn);
            assertEquals(n.notes, c.notes);
        }
        Product tea = cachedPojos.get(0);
        assertEquals(new BigDecimal("4.5"), tea.price);
        assertEquals(LocalDate.of(2026, 1, 15), tea.addedOn);   // UTC instant → IST date
        assertEquals("", tea.notes);                             // blank String cell stays ""

        SheetProperties second = cached(b -> b.dbSheetUrl(OTHER_SHEET));
        assertNull(products(second).findAll().get(0).addedOn);   // blank date cell → null, also from cache
        assertNull(products(second).findAll().get(0).addedOn);
    }

    @Test
    @DisplayName("different queries are cached separately: filters, sort, limit, offset, columns, worksheet")
    void differentQueriesAreSeparateEntries() {
        SheetProperties p = cached();
        List<Supplier<Object>> queries = List.of(
                () -> p.worksheet().select().fetch(),
                () -> p.worksheet().select().where(eq("sku", "P1")).fetch(),
                () -> p.worksheet().select().where(eq("sku", "P2")).fetch(),
                () -> p.worksheet().select().where(gt("stock", 10)).fetch(),
                () -> p.worksheet().select().orderBy("name", Sort.Direction.DESC).fetch(),
                () -> p.worksheet().select().limit(2).fetch(),
                () -> p.worksheet().select().offset(1).limit(2).fetch(),
                () -> p.worksheet().select("sku", "name").fetch(),
                () -> p.client().worksheet(SHEET, ORDERS).select().fetch());

        List<Object> first = new ArrayList<>();
        queries.forEach(q -> first.add(q.get()));
        assertCalls(queries.size());

        List<Object> second = new ArrayList<>();
        queries.forEach(q -> second.add(q.get()));
        assertCalls(queries.size());
        assertEquals(first, second);
        assertNotEquals(first.get(1), first.get(2));
    }

    @Test
    @DisplayName("identical queries share one entry however they are built (spec, repository, rows or POJOs)")
    void identicalQueriesShareAnEntry() {
        SheetProperties p = cached();

        p.worksheet().select().where(eq("sku", "P1")).fetch();
        p.worksheet().select().where(eq("sku", "P1")).fetch(Product.class);
        products(p).findWhere(eq("sku", "P1"));
        products(p).requests().findWhere(eq("sku", "P1")).execute();
        assertCalls(1);
    }

    @Test
    @DisplayName("the same tab name in another spreadsheet is a different entry")
    void otherSpreadsheetIsSeparate() {
        SheetProperties p = cached();

        assertEquals("P1", p.worksheet().select().orderBy("sku").fetch().get(0).getString("sku"));
        assertEquals("X1", p.client().worksheet(OTHER_SHEET, PRODUCTS).select().fetch().get(0).getString("sku"));
        assertCalls(2);
        p.client().worksheet(OTHER_SHEET, PRODUCTS).select().fetch();
        assertCalls(2);
    }

    @Test
    @DisplayName("column lookups (GET_COLUMNS) are cached too")
    void columnLookupsAreCached() {
        SheetProperties p = cached();

        assertEquals(PRODUCT_COLUMNS, p.worksheet().columns().fetch());
        assertEquals(PRODUCT_COLUMNS, p.worksheet().columns().fetch());
        assertCalls(1);
    }

    @Test
    @DisplayName("a cache hit makes no network call, so pre/post network hooks do not run")
    void cacheHitsSkipNetworkHooks() {
        AtomicInteger pre = new AtomicInteger();
        AtomicInteger post = new AtomicInteger();
        SheetProperties p = cached(b -> b.preNetworkCall(c -> pre.incrementAndGet())
                .postNetworkCall(r -> post.incrementAndGet()));

        allProducts(p);
        allProducts(p);
        allProducts(p);
        assertEquals(1, pre.get());
        assertEquals(1, post.get());
    }

    @Test
    @DisplayName("the cache survives a restart: a new client on the same file reuses entries")
    void cacheSurvivesRestart() {
        SheetProperties first = cached();
        allProducts(first);
        first.cache().orElseThrow().close();             // e.g. the app shuts down

        SheetProperties second = cached();                // new instance, same cache file
        assertEquals("Tea", allProducts(second).get(0).getString("name"));
        assertCalls(1);
    }

    @Test
    @DisplayName("clients using different cache files do not share entries")
    void differentFilesAreIndependent() {
        SheetProperties a = cached(b -> b.cacheFile(dir.resolve("a.db")));
        SheetProperties b = cached(x -> x.cacheFile(dir.resolve("b.db")));

        allProducts(a);
        allProducts(b);
        assertCalls(2);
    }

    @Test
    @DisplayName("clients sharing a cache file share entries")
    void sharedFileSharesEntries() {
        SheetProperties a = cached();
        SheetProperties b = cached(x -> x.preNetworkCall(c -> { }));    // its own client, same file

        allProducts(a);
        allProducts(b);
        assertCalls(1);
    }

    @Test
    @DisplayName("while an entry is fresh, edits made outside the SDK are not visible (stale until expiry)")
    void externalEditsInvisibleWhileFresh() {
        SheetProperties p = cached();

        assertEquals("Tea", nameOf(p, "P1"));
        renameDirectly("P1", "Green Tea");
        assertEquals("Tea", nameOf(p, "P1"));
        assertCalls(1);
    }
}
