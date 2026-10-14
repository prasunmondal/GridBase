package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.result.AddColumnsResult;
import io.github.prasunmondal.gridbase.result.ClearResult;
import io.github.prasunmondal.gridbase.result.ColumnsResult;
import io.github.prasunmondal.gridbase.result.LastUpdatedResult;
import io.github.prasunmondal.gridbase.result.RowsResult;
import io.github.prasunmondal.gridbase.result.WorksheetCreated;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Batch}, {@link Ref} and {@link BatchResult}: lifecycle, result types and retries. */
class BatchTest {

    private static final String ROWS = "\"rowCount\":1,\"rows\":[{\"id\":\"N1\"}]";

    private static GridBase client(FakeTransport t) {
        return GridBase.builder().transport(t).defaultSpreadsheetId("S").retryPolicy(RetryPolicy.none()).build();
    }

    @Test
    void failedExecuteLeavesBatchRetryable() {
        AtomicInteger calls = new AtomicInteger();
        FakeTransport ok = FakeTransport.replying(ROWS);
        FakeTransport t = new FakeTransport(req -> calls.incrementAndGet() == 1
                ? "{\"success\":false,\"error\":\"Lock timeout: try again later\"}"
                : ok.send(req.toString()));
        GridBase db = client(t);
        Batch batch = db.batch();
        Ref<RowsResult> selected = batch.add(db.worksheet("T").select());

        assertThrows(ServerException.class, batch::execute);
        assertThrows(IllegalStateException.class, selected::get);

        BatchResult result = batch.execute();                // a failed batch can be sent again
        assertEquals("N1", selected.get().rows().get(0).getString("id"));
        assertSame(result.get(selected), selected.get());
        assertThrows(IllegalStateException.class, batch::execute);
        assertEquals(2, calls.get());
    }

    @Test
    void everyResultTypeResolvesInABatch() {
        FakeTransport t = FakeTransport.replying(
                "\"worksheet\":\"T\",\"columns\":[\"id\",\"qty\"]",
                "\"worksheet\":\"New\",\"sheetId\":42",
                "\"worksheet\":\"New\",\"columns\":[\"a\"],\"skippedColumns\":[],\"startColumn\":1,\"count\":1",
                "\"worksheet\":\"T\",\"rowsCleared\":3,\"columnsCleared\":2",
                "\"spreadsheetId\":\"S\",\"lastUpdated\":1760000000000",
                "\"rowCount\":2,\"rows\":[{\"id\":\"1\"},{\"id\":\"2\"}]");
        GridBase db = client(t);
        Batch batch = db.batch();
        Ref<ColumnsResult> columns = batch.add(db.worksheet("T").columns());
        Ref<WorksheetCreated> created = batch.add(db.worksheet("New").create());
        Ref<AddColumnsResult> added = batch.add(db.worksheet("New").addColumns("a"));
        Ref<ClearResult> cleared = batch.add(db.worksheet("T").clear());
        Ref<LastUpdatedResult> updated = batch.add(db.worksheet("T").lastUpdated());
        Ref<RowsResult> deleted = batch.add(db.worksheet("U").delete().where(eq("id", "1")));
        batch.execute();

        assertEquals(List.of("id", "qty"), columns.get().columns());
        assertEquals(42L, created.get().sheetId());
        assertEquals("New", created.get().worksheet());
        assertEquals(List.of("a"), added.get().columns());
        assertEquals(3, cleared.get().rowsCleared());
        assertEquals(Instant.ofEpochMilli(1760000000000L), updated.get().lastUpdated());
        assertEquals(2, deleted.get().count());
        assertEquals(1, t.requests.size());
    }

    @Test
    void retryPolicyAppliesToTheWholeBatch() {
        AtomicInteger calls = new AtomicInteger();
        FakeTransport down = new FakeTransport(req -> {
            calls.incrementAndGet();
            throw new TransportException("connection reset", -1, null, true);
        });
        GridBase db = GridBase.builder().transport(down).defaultSpreadsheetId("S")
                .retryPolicy(RetryPolicy.of(3, Duration.ZERO)).build();

        Batch reads = db.batch();
        reads.add(db.worksheet("T").select());
        reads.add(db.worksheet("T").columns());
        reads.add(db.worksheet("T").lastUpdated());
        assertThrows(TransportException.class, reads::execute);
        assertEquals(3, calls.getAndSet(0));

        Batch withWrite = db.batch();
        withWrite.add(db.worksheet("T").select());
        withWrite.add(db.worksheet("T").insert(Map.of("id", "1")));
        assertThrows(TransportException.class, withWrite::execute);
        assertEquals(1, calls.getAndSet(0));

        GridBase optedIn = GridBase.builder().transport(down).defaultSpreadsheetId("S")
                .retryPolicy(RetryPolicy.of(3, Duration.ZERO).retryingWrites(true)).build();
        Batch retried = optedIn.batch();
        retried.add(optedIn.worksheet("T").select());
        retried.add(optedIn.worksheet("T").insert(Map.of("id", "1")));
        assertThrows(TransportException.class, retried::execute);
        assertEquals(3, calls.get());
    }

    @Test
    void schemaBatchBypassesTheAutomaticQueue() {
        List<String> threads = new ArrayList<>();
        FakeTransport t = new FakeTransport(req -> {
            threads.add(Thread.currentThread().getName());
            return FakeTransport.replying("\"worksheet\":\"New\",\"sheetId\":1", ROWS, ROWS).send(req.toString());
        });
        // A 10 s window: anything that went through the queue would not come back in time.
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S")
                .requestQueue(Duration.ofSeconds(10), 2).build();

        Batch schema = db.batch();
        schema.add(db.worksheet("New").create());
        schema.add(db.worksheet("New").select());
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            schema.execute();
        });

        Batch oversized = db.batch();
        oversized.add(db.worksheet("A").select());
        oversized.add(db.worksheet("B").select());
        oversized.add(db.worksheet("C").select());
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            oversized.execute();
        });

        assertEquals(2, t.requests.size());
        assertEquals(3, t.requests.get(1).path("operations").size());
        threads.forEach(name -> assertNotEquals("gridbase-queue", name));
    }

    @Test
    void emptyBatchAndAddAfterExecuteAreRejected() {
        GridBase db = client(FakeTransport.replying(ROWS));
        Batch batch = db.batch();
        IllegalStateException empty = assertThrows(IllegalStateException.class, batch::execute);
        assertEquals("Batch is empty", empty.getMessage());

        batch.add(db.worksheet("T").select());
        batch.execute();
        assertThrows(IllegalStateException.class, () -> batch.add(db.worksheet("T").select()));
    }

    @Test
    void foreignSpecAndForeignRefAreRejected() {
        GridBase one = client(FakeTransport.replying(ROWS));
        GridBase two = client(FakeTransport.replying(ROWS));
        assertThrows(IllegalArgumentException.class, () -> one.batch().add(two.worksheet("T").select()));

        Batch first = one.batch();
        first.add(one.worksheet("T").select());
        Batch second = one.batch();
        Ref<RowsResult> foreign = second.add(one.worksheet("T").select());
        BatchResult result = first.execute();
        assertThrows(IllegalArgumentException.class, () -> result.get(foreign));
    }

    @Test
    void resultsKeepAddOrderAndPassThroughMetadata() {
        FakeTransport t = FakeTransport.replying(
                "\"rowCount\":1,\"rows\":[{\"n\":1}]",
                "\"rowCount\":1,\"rows\":[{\"n\":2}]",
                "\"rowCount\":1,\"rows\":[{\"n\":3}]");
        GridBase db = client(t);
        Batch batch = db.batch();
        batch.add(db.worksheet("A").select());
        batch.add(db.worksheet("B").insert(Map.of("n", 2)));
        batch.add(db.worksheet("C").select());
        assertEquals(3, batch.size());

        BatchResult result = batch.execute();

        assertEquals(List.of("op-1", "op-2", "op-3"),
                result.results().stream().map(r -> r.operationId()).collect(java.util.stream.Collectors.toList()));
        assertEquals(3, ((RowsResult) result.results().get(2)).rows().get(0).getInteger("n"));
        assertEquals(t.requests.get(0).path("requestId").asText(), result.requestId());
        assertTrue(result.debug().isArray());
        assertEquals(12L, result.executionTimeMillis());
    }
}
