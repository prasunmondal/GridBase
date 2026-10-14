package io.github.prasunmondal.gridbase.cachingTests;

import io.github.prasunmondal.gridbase.Batch;
import io.github.prasunmondal.gridbase.Ref;
import io.github.prasunmondal.gridbase.SheetProperties;
import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.result.RowsResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static io.github.prasunmondal.gridbase.query.Filters.ne;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The automatic request queue and {@link Batch} against an engine that holds real rows: combined calls
 * keep the cache consistent with the sheet, and a batch stays all-or-nothing.
 */
class QueueConsistencyTest extends CachingTestBase {

    private static List<String> types(com.fasterxml.jackson.databind.JsonNode request) {
        List<String> types = new ArrayList<>();
        request.path("operations").forEach(op -> types.add(op.path("type").asText()));
        return types;
    }

    private static Throwable failureOf(CompletableFuture<?> f) {
        return assertThrows(CompletionException.class, f::join).getCause();
    }

    /**
     * The read runs before the write in the combined call, so its reply is stale once the write commits.
     * Storing it and invalidating the tab happen on different callback threads; the store must not win.
     */
    @Test
    void readThenWriteInOneWindowLeavesNoStaleCacheEntry() {
        SheetProperties p = cached(b -> b.queueRequests(Duration.ofMillis(2)));
        Worksheet products = p.worksheet();
        for (int i = 0; i < 200; i++) {
            String note = "n" + i;
            // A different cache key every round, so the read is never answered from an earlier round.
            CompletableFuture<List<Row>> read = products.select().where(eq("sku", "P1"), ne("name", "x" + i)).fetchAsync();
            CompletableFuture<RowsResult> write = products.update().set("notes", note).where(eq("sku", "P1")).executeAsync();
            read.join();
            write.join();

            int calls = engine.calls();
            Row after = products.select().where(eq("sku", "P1"), ne("name", "x" + i)).fetchFirst().orElseThrow();
            assertEquals(note, after.getString("notes"), "round " + i + " served a stale cached reply");
            assertEquals(calls + 1, engine.calls(), "round " + i);
        }
    }

    @Test
    void writeThenReadInOneWindowCachesThePostWriteRead() {
        SheetProperties p = cached(b -> b.queueRequests(Duration.ofMillis(2)));
        Worksheet products = p.worksheet();
        for (int i = 0; i < 50; i++) {
            String sku = "Q" + i;
            CompletableFuture<RowsResult> write = products.insert(row("sku", sku, "name", "New " + i)).executeAsync();
            CompletableFuture<List<Row>> read = products.select().where(eq("sku", sku)).fetchAsync();
            write.join();
            assertEquals("New " + i, read.join().get(0).getString("name"));

            int calls = engine.calls();
            assertEquals("New " + i, products.select().where(eq("sku", sku)).fetch().get(0).getString("name"));
            assertEquals(calls, engine.calls(), "round " + i + ": the post-write read was not cached");
        }
    }

    @Test
    void batchThroughTheQueueStaysAllOrNothing() {
        SheetProperties p = track(base().queueRequests(Duration.ofMillis(100)).build());
        Worksheet products = p.worksheet();
        Worksheet orders = p.client().worksheet(SHEET, ORDERS);

        // Another caller's faulty request shares the call: the batch is re-sent alone, as one request.
        CompletableFuture<RowsResult> faulty = products.cloneRows().where(eq("sku", "NOPE")).set("sku", "Z").executeAsync();
        Batch batch = p.client().batch();
        Ref<RowsResult> inserted = batch.add(products.insert(row("sku", "P9", "name", "Salt")));
        Ref<RowsResult> updated = batch.add(orders.update().set("status", "CLOSED").where(eq("orderId", "O1")));
        batch.execute();

        assertInstanceOf(ServerException.class, failureOf(faulty));
        assertEquals("P9", inserted.get().rows().get(0).getString("sku"));
        assertEquals("CLOSED", updated.get().rows().get(0).getString("status"));
        assertCalls(3);
        assertEquals(List.of("CLONE", "INSERT", "UPDATE"), types(engine.request(0)));
        assertEquals(List.of("CLONE"), types(engine.request(1)));
        assertEquals(List.of("INSERT", "UPDATE"), types(engine.request(2)));
        assertEquals(6, engine.rowCount(SHEET, PRODUCTS));       // P9 written exactly once
        assertEquals("CLOSED", orders.select().where(eq("orderId", "O1")).fetch().get(0).getString("status"));

        // A faulty batch fails on its own and writes none of its rows; the other caller still succeeds.
        CompletableFuture<List<Row>> other = orders.select().where(eq("orderId", "O2")).fetchAsync();
        Batch broken = p.client().batch();
        broken.add(products.insert(row("sku", "P8", "name", "Pepper")));
        broken.add(orders.update().set("status", "LOST").where(eq("orderId", "O3")));
        broken.add(products.cloneRows().where(eq("sku", "NOPE")).set("sku", "Y"));
        assertThrows(ServerException.class, broken::execute);

        assertEquals("SHIPPED", other.join().get(0).getString("status"));
        assertEquals(6, engine.rowCount(SHEET, PRODUCTS));
        assertEquals("OPEN", orders.select().where(eq("orderId", "O3")).fetch().get(0).getString("status"));
    }

    @Test
    void writeBatchInvalidatesEveryTouchedWorksheetEvenOnFailure() {
        SheetProperties p = cached();
        Worksheet products = p.worksheet();
        Worksheet orders = p.client().worksheet(SHEET, ORDERS);
        products.select().fetch();
        orders.select().fetch();
        products.select().fetch();
        orders.select().fetch();
        assertCalls(2);

        renameDirectly("P1", "Assam");
        engine.editDirectly(SHEET, ORDERS, "orderId", "O1", "status", "PACKED");
        Batch batch = p.client().batch();
        batch.add(products.update().set("stock", 1).where(eq("sku", "P2")));
        batch.add(orders.update().set("qty", 9).where(eq("orderId", "O2")));
        engine.failNextWith("Lock timeout: another process was holding the lock for too long.");
        ServerException e = assertThrows(ServerException.class, batch::execute);
        assertTrue(e.isRetryable());
        assertCalls(3);

        // The same queries that were cached: both tabs were invalidated, and nothing was written.
        List<Row> productRows = products.select().fetch();
        List<Row> orderRows = orders.select().fetch();
        assertCalls(5);
        assertEquals("Assam", productRows.get(0).getString("name"));
        assertEquals(12, productRows.get(1).getInteger("stock"));
        assertEquals("PACKED", orderRows.get(0).getString("status"));
        assertEquals(1, orderRows.get(1).getInteger("qty"));
    }
}
