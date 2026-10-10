package io.github.prasunmondal.hibernatesheets.cachingTests;

import io.github.prasunmondal.hibernatesheets.APIRequestsQueue;
import io.github.prasunmondal.hibernatesheets.Queued;
import io.github.prasunmondal.hibernatesheets.SheetProperties;
import io.github.prasunmondal.hibernatesheets.SheetRequest;
import io.github.prasunmondal.hibernatesheets.cache.CacheExpiry;
import io.github.prasunmondal.hibernatesheets.cache.CacheStrategy;
import io.github.prasunmondal.hibernatesheets.exception.TransportException;
import io.github.prasunmondal.hibernatesheets.mapping.Repository;
import io.github.prasunmondal.hibernatesheets.result.Row;
import io.github.prasunmondal.hibernatesheets.result.RowsResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.prasunmondal.hibernatesheets.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forcing a read to bypass the cache and refresh it: {@link SheetRequest#forceRefresh()} and friends. */
class CacheForceRefreshTest extends CachingTestBase {

    private static String name(Optional<Product> product) {
        return product.orElseThrow().name;
    }

    @Test
    @DisplayName("forceRefresh goes to the network even when a fresh entry exists")
    void bypassesFreshEntry() {
        SheetProperties p = cached();
        Repository<Product> repo = products(p);

        assertEquals("Tea", name(repo.findById("P1")));
        renameDirectly("P1", "Green Tea");
        assertEquals("Tea", name(repo.findById("P1")));                         // stale, from cache
        assertCalls(1);

        assertEquals("Green Tea", name(repo.requests().findById("P1").forceRefresh().execute()));
        assertCalls(2);
    }

    @Test
    @DisplayName("forceRefresh replaces the cached entry, so later normal reads get the fresh data without a call")
    void updatesTheCachedEntry() {
        SheetProperties p = cached();
        Repository<Product> repo = products(p);

        repo.findById("P1");
        renameDirectly("P1", "Green Tea");
        repo.requests().findById("P1").forceRefresh().execute();

        assertEquals("Green Tea", name(repo.findById("P1")));
        assertCalls(2);
    }

    @Test
    @DisplayName("forceRefresh restarts the expiry from the moment of the refresh")
    void restartsExpiry() {
        SheetProperties p = cached(b -> b.cacheExpiry(CacheExpiry.ttlMinutes(10)));
        SheetRequest<List<Row>> all = p.worksheet().select().request().map(RowsResult::rows);

        all.execute();                                    // t = 0, expires 10
        clock.advance(Duration.ofMinutes(8));
        all.forceRefresh().execute();                     // t = 8, expires 18
        clock.advance(Duration.ofMinutes(7));
        all.execute();                                    // t = 15, fresh
        assertCalls(2);
        clock.advance(Duration.ofMinutes(3));
        all.execute();                                    // t = 18, expired
        assertCalls(3);
    }

    @Test
    @DisplayName("a failed forceRefresh throws (no stale fallback, even with NETWORK_FIRST) and keeps the old entry")
    void failedRefreshThrowsAndKeepsEntry() {
        SheetProperties p = cached(b -> b.cacheStrategy(CacheStrategy.NETWORK_FIRST));
        SheetRequest<List<Row>> all = p.worksheet().select().request().map(RowsResult::rows);

        List<Row> original = all.execute();
        engine.goOffline();
        assertThrows(TransportException.class, () -> all.forceRefresh().execute());
        assertEquals(original, all.execute());           // NETWORK_FIRST fallback still has the old entry
    }

    @Test
    @DisplayName("after a failed forceRefresh, CACHE_FIRST keeps serving the old fresh entry")
    void failedRefreshCacheFirstKeepsServing() {
        SheetProperties p = cached();
        Repository<Product> repo = products(p);

        repo.findById("P1");
        engine.goOffline();
        assertThrows(TransportException.class, () -> repo.requests().findById("P1").forceRefresh().execute());
        engine.goOnline();
        assertEquals("Tea", name(repo.findById("P1")));
        assertCalls(2);                                   // first read + the failed refresh
    }

    @Test
    @DisplayName("forceRefresh works on spec requests, async execution, and survives map()")
    void specsAsyncAndMap() {
        SheetProperties p = cached();
        p.worksheet().select().where(eq("sku", "P2")).fetch();
        engine.editDirectly(SHEET, PRODUCTS, "sku", "P2", "notes", "single origin");

        SheetRequest<RowsResult> forced = p.worksheet().select().where(eq("sku", "P2")).request().forceRefresh();
        SheetRequest<String> notes = forced.map(r -> r.rows().get(0).getString("notes"));
        assertTrue(forced.isForceRefresh());
        assertTrue(notes.isForceRefresh());
        assertFalse(p.worksheet().select().request().isForceRefresh());

        assertEquals("single origin", notes.executeAsync().join());
        assertCalls(2);
    }

    @Test
    @DisplayName("in an APIRequestsQueue only the forced read is sent; normal reads are still cache hits")
    void forcedReadInQueue() {
        SheetProperties p = cached();
        Repository<Product> repo = products(p);
        repo.findById("P1");
        repo.findById("P2");
        renameDirectly("P1", "Green Tea");
        renameDirectly("P2", "Espresso");

        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<Optional<Product>> normal = repo.requests().findById("P1").queue(reqQ);
        Queued<Optional<Product>> forced = repo.requests().findById("P2").forceRefresh().queue(reqQ);
        reqQ.execute();

        assertCalls(3);
        assertEquals(1, engine.lastRequest().path("operations").size());
        assertEquals("Tea", name(normal.get()));                                 // still the cached value
        assertEquals("Espresso", name(forced.get()));
        assertEquals("Espresso", name(repo.findById("P2")));                     // cache refreshed
        assertCalls(3);
    }

    @Test
    @DisplayName("without a cache, forceRefresh simply fetches")
    void withoutCache() {
        SheetProperties p = track(base().build());
        SheetRequest<Optional<Product>> tea = products(p).requests().findById("P1").forceRefresh();

        assertEquals("Tea", name(tea.execute()));
        assertEquals("Tea", name(tea.execute()));
        assertCalls(2);
    }

    @Test
    @DisplayName("forceRefresh on a write is just a write: it is applied and invalidates the worksheet")
    void forceRefreshOnWrite() {
        SheetProperties p = cached();
        allProducts(p);

        p.worksheet().insert(Map.of("sku", "P6", "name", "Jam")).request().forceRefresh().execute();
        assertEquals(6, allProducts(p).size());
        assertCalls(3);
    }

    @Test
    @DisplayName("invalidate(spreadsheet, worksheet) forces the next read of that worksheet to the network")
    void manualInvalidation() {
        SheetProperties p = cached();
        nameOf(p, "P1");
        renameDirectly("P1", "Green Tea");

        p.cache().orElseThrow().invalidate(SHEET, PRODUCTS);
        assertEquals("Green Tea", nameOf(p, "P1"));
        assertCalls(2);
    }

    @Test
    @DisplayName("clear() drops every entry")
    void clearDropsEverything() {
        SheetProperties p = cached();
        allProducts(p);
        p.client().worksheet(SHEET, ORDERS).select().fetch();

        p.cache().orElseThrow().clear();
        allProducts(p);
        p.client().worksheet(SHEET, ORDERS).select().fetch();
        assertCalls(4);
    }

    @Test
    @DisplayName("NETWORK_FIRST refreshes on every read and keeps the latest reply for offline use")
    void networkFirstAlwaysRefreshes() {
        SheetProperties p = cached(b -> b.cacheStrategy(CacheStrategy.NETWORK_FIRST));

        assertEquals("Tea", nameOf(p, "P1"));
        renameDirectly("P1", "Green Tea");
        assertEquals("Green Tea", nameOf(p, "P1"));
        assertCalls(2);

        engine.goOffline();
        assertEquals("Green Tea", nameOf(p, "P1"));
    }
}
