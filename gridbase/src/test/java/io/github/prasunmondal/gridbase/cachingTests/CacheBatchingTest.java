package io.github.prasunmondal.gridbase.cachingTests;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.prasunmondal.gridbase.APIRequestsQueue;
import io.github.prasunmondal.gridbase.Batch;
import io.github.prasunmondal.gridbase.Queued;
import io.github.prasunmondal.gridbase.Ref;
import io.github.prasunmondal.gridbase.SheetProperties;
import io.github.prasunmondal.gridbase.cache.CacheStrategy;
import io.github.prasunmondal.gridbase.exception.QueueExecutionException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.mapping.Repository;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.result.RowsResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several reads sent together (APIRequestsQueue, Batch, automatic queue) and how they are cached. */
class CacheBatchingTest extends CachingTestBase {

    private static List<String> skusFilteredIn(JsonNode request) {
        List<String> skus = new ArrayList<>();
        request.path("operations").forEach(op -> skus.add(op.path("where").path(0).path("value").asText()));
        return skus;
    }

    private static String name(Optional<Product> product) {
        return product.orElseThrow().name;
    }

    @Test
    @DisplayName("queued reads go out in one call and each one is cached on its own")
    void queuedReadsCachedIndividually() {
        SheetProperties p = cached();
        Repository<Product> repo = products(p);

        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<Optional<Product>> tea = repo.requests().findById("P1").queue(reqQ);
        Queued<Optional<Product>> coffee = repo.requests().findById("P2").queue(reqQ);
        Queued<RowsResult> orders = p.client().worksheet(SHEET, ORDERS).select().queue(reqQ);
        reqQ.execute();
        assertCalls(1);
        assertEquals(3, engine.lastRequest().path("operations").size());

        assertEquals(name(tea.get()), name(repo.findById("P1")));
        assertEquals(name(coffee.get()), name(repo.findById("P2")));
        assertEquals(orders.get().rows(), p.client().worksheet(SHEET, ORDERS).select().fetch());
        assertCalls(1);
    }

    @Test
    @DisplayName("only the reads that are not already cached are sent")
    void onlyMissesAreSent() {
        SheetProperties p = cached();
        Repository<Product> repo = products(p);
        repo.findById("P1");

        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<Optional<Product>> tea = repo.requests().findById("P1").queue(reqQ);
        repo.requests().findById("P2").queue(reqQ);
        repo.requests().findById("P3").queue(reqQ);
        reqQ.execute();

        assertCalls(2);
        assertEquals(List.of("P2", "P3"), skusFilteredIn(engine.lastRequest()));
        assertEquals("Tea", name(tea.get()));
    }

    @Test
    @DisplayName("a queue of reads that are all cached makes no network call")
    void allHitsNoCall() {
        SheetProperties p = cached();
        Repository<Product> repo = products(p);
        repo.findById("P1");
        repo.findAll();

        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<Optional<Product>> tea = repo.requests().findById("P1").queue(reqQ);
        Queued<List<Product>> all = repo.requests().findAll().queue(reqQ);
        reqQ.execute();

        assertCalls(2);
        assertEquals("Tea", name(tea.get()));
        assertEquals(5, all.get().size());
    }

    @Test
    @DisplayName("running the same queue again is served entirely from the cache")
    void repeatedQueueFromCache() {
        SheetProperties p = cached();
        for (int round = 0; round < 3; round++) {
            APIRequestsQueue reqQ = new APIRequestsQueue();
            products(p).requests().findAll().queue(reqQ);
            p.client().worksheet(SHEET, ORDERS).select().where(eq("status", "OPEN")).queue(reqQ);
            reqQ.execute();
        }
        assertCalls(1);
    }

    @Test
    @DisplayName("the same read queued twice: both handles get the result and it is cached")
    void duplicateReadsInOneQueue() {
        SheetProperties p = cached();
        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<Optional<Product>> a = products(p).requests().findById("P4").queue(reqQ);
        Queued<Optional<Product>> b = products(p).requests().findById("P4").queue(reqQ);
        reqQ.execute();

        assertEquals("Milk", name(a.get()));
        assertEquals("Milk", name(b.get()));
        products(p).findById("P4");
        assertCalls(1);
    }

    @Test
    @DisplayName("a failing read is not cached; the reads that succeeded are")
    void failingReadNotCachedOthersAre() {
        SheetProperties p = cached();
        engine.failRequestsTouching(ORDERS);

        APIRequestsQueue first = new APIRequestsQueue();
        Queued<List<Row>> all = p.worksheet().select().request().map(RowsResult::rows).queue(first);
        Queued<RowsResult> orders = p.client().worksheet(SHEET, ORDERS).select().queue(first);
        QueueExecutionException e = assertThrows(QueueExecutionException.class, first::execute);
        assertEquals(1, e.failures().size());
        assertEquals(5, all.get().size());
        assertTrue(orders.failed());
        assertCalls(3);                                   // combined call, then each request alone

        engine.stopFailing();
        APIRequestsQueue second = new APIRequestsQueue();
        p.worksheet().select().request().queue(second);
        p.client().worksheet(SHEET, ORDERS).select().queue(second);
        second.execute();
        assertCalls(4);
        assertEquals(List.of(ORDERS), engine.worksheetsIn(engine.lastRequest()));
    }

    @Test
    @DisplayName("NETWORK_FIRST queue while offline: cached reads fall back to their entry, uncached ones fail")
    void networkFirstQueueOffline() {
        SheetProperties p = cached(b -> b.cacheStrategy(CacheStrategy.NETWORK_FIRST));
        Repository<Product> repo = products(p);
        repo.findById("P1");
        engine.goOffline();

        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<Optional<Product>> cachedOne = repo.requests().findById("P1").queue(reqQ);
        Queued<Optional<Product>> uncached = repo.requests().findById("P2").queue(reqQ);
        QueueExecutionException e = assertThrows(QueueExecutionException.class, reqQ::execute);

        assertEquals(1, e.failures().size());
        assertEquals("Tea", name(cachedOne.get()));
        assertInstanceOf(TransportException.class, uncached.failure());
    }

    @Test
    @DisplayName("an explicit Batch of reads is one request, cached as one entry")
    void batchCachedAsOneEntry() {
        SheetProperties p = cached();
        for (int round = 0; round < 2; round++) {
            Batch batch = p.client().batch();
            Ref<RowsResult> tea = batch.add(p.worksheet().select().where(eq("sku", "P1")));
            Ref<RowsResult> coffee = batch.add(p.worksheet().select().where(eq("sku", "P2")));
            batch.execute();
            assertEquals("Tea", tea.get().rows().get(0).getString("name"));
            assertEquals("Coffee", coffee.get().rows().get(0).getString("name"));
        }
        assertCalls(1);

        p.worksheet().select().where(eq("sku", "P1")).fetch();   // a different request than the batch
        assertCalls(2);
    }

    @Test
    @DisplayName("a Batch that contains a write is never cached")
    void batchWithWriteNotCached() {
        SheetProperties p = cached();
        for (int round = 0; round < 2; round++) {
            Batch batch = p.client().batch();
            batch.add(p.client().worksheet(SHEET, ORDERS).update().set("status", "DONE").where(eq("orderId", "O1")));
            batch.add(p.worksheet().select());
            batch.execute();
        }
        assertCalls(2);
    }

    @Test
    @DisplayName("automatic queue: async reads made together go in one call and are cached one by one")
    void automaticQueueCachesEach() {
        SheetProperties p = cached(b -> b.queueRequests(Duration.ofMillis(100)));

        List<CompletableFuture<List<Row>>> futures = new ArrayList<>();
        for (String sku : List.of("P1", "P2", "P3")) {
            futures.add(p.worksheet().select().where(eq("sku", sku)).fetchAsync());
        }
        futures.forEach(CompletableFuture::join);
        assertCalls(1);

        for (String sku : List.of("P1", "P2", "P3")) {
            p.worksheet().select().where(eq("sku", sku)).fetch();
        }
        assertCalls(1);
    }

    @Test
    @DisplayName("entities sharing a client (derived by tabName) are queued into one call and both cached")
    void sharedClientOneCall() {
        SheetProperties base = cached();
        SheetProperties orders = track(base.toBuilder().tabName(ORDERS).build());

        APIRequestsQueue reqQ = new APIRequestsQueue();
        base.worksheet().select().queue(reqQ);
        orders.worksheet().select().queue(reqQ);
        reqQ.execute();
        assertCalls(1);

        base.worksheet().select().fetch();
        orders.worksheet().select().fetch();
        assertCalls(1);
    }

    @Test
    @DisplayName("clients sharing a cache file reuse each other's queued entries")
    void queueAcrossClientsSharingFile() {
        SheetProperties a = cached();
        SheetProperties b = cached(x -> x.preNetworkCall(c -> { }));

        APIRequestsQueue reqQ = new APIRequestsQueue();
        a.worksheet().select().queue(reqQ);
        b.client().worksheet(SHEET, ORDERS).select().queue(reqQ);
        reqQ.execute();
        assertCalls(2);                                   // one call per client

        b.worksheet().select().fetch();                   // cached by client a
        a.client().worksheet(SHEET, ORDERS).select().fetch();   // cached by client b
        assertCalls(2);
    }
}
