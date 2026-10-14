package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.prasunmondal.gridbase.cache.CacheExpiry;
import io.github.prasunmondal.gridbase.cache.CacheStrategy;
import io.github.prasunmondal.gridbase.cache.ResponseCache;
import io.github.prasunmondal.gridbase.cache.SqliteResponseCache;
import io.github.prasunmondal.gridbase.exception.GridBaseException;
import io.github.prasunmondal.gridbase.exception.QueueExecutionException;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.mapping.Repository;
import io.github.prasunmondal.gridbase.mapping.SheetKey;
import io.github.prasunmondal.gridbase.result.ExecutionResponse;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.result.RowsResult;
import io.github.prasunmondal.gridbase.spec.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class APIRequestsQueueTest {

    @TempDir
    Path dir;

    /** Entity whose key column is the worksheet the row came from. */
    public static class Echo {
        @SheetKey
        public String sheet;
        public Integer pos;
    }

    /** Each operation's single result row names the worksheet it targeted and its position in the call. */
    private static String echo(JsonNode req) {
        StringBuilder sb = new StringBuilder("{\"success\":true,\"requestId\":\"r\",\"results\":[");
        JsonNode ops = req.path("operations");
        for (int i = 0; i < ops.size(); i++) {
            sb.append(i == 0 ? "" : ",")
                    .append("{\"operationId\":\"").append(ops.get(i).path("id").asText())
                    .append("\",\"rowCount\":1,\"rows\":[{\"sheet\":\"").append(ops.get(i).path("worksheet").asText())
                    .append("\",\"pos\":").append(i).append("}]}");
        }
        return sb.append("]}").toString();
    }

    private static List<String> worksheets(JsonNode request) {
        List<String> names = new ArrayList<>();
        request.path("operations").forEach(op -> names.add(op.path("worksheet").asText()));
        return names;
    }

    private static GridBase client(FakeTransport t) {
        return GridBase.builder().transport(t).defaultSpreadsheetId("S").retryPolicy(RetryPolicy.none()).build();
    }

    private static String sheet(List<Row> rows) {
        return rows.get(0).getString("sheet");
    }

    @Test
    void nothingIsSentUntilExecuteThenEverythingGoesInOneCall() {
        FakeTransport t = new FakeTransport(APIRequestsQueueTest::echo);
        GridBase db = client(t);
        Repository<Echo> customers = new Repository<>(db, Echo.class);    // worksheet "Echo"

        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<List<Echo>> all = customers.requests().findAll().queue(reqQ);
        Queued<RowsResult> deliveries = db.worksheet("Deliveries").select().where(eq("day", "today")).queue(reqQ);
        Queued<Optional<Row>> first = db.worksheet("Orders").select().firstRequest().queue(reqQ);
        assertEquals(0, t.requests.size());
        assertEquals(3, reqQ.size());

        reqQ.execute();

        assertEquals(1, t.requests.size());
        assertEquals(List.of("Echo", "Deliveries", "Orders"), worksheets(t.requests.get(0)));
        assertEquals("Echo", all.get().get(0).sheet);
        assertEquals("Deliveries", sheet(deliveries.get().rows()));
        assertEquals(1, t.requests.get(0).path("operations").get(2).path("limit").asInt());
        assertEquals("Orders", first.get().orElseThrow().getString("sheet"));
    }

    @Test
    void sheetRequestsCanAlsoRunOnTheirOwn() {
        FakeTransport t = new FakeTransport(APIRequestsQueueTest::echo);
        GridBase db = client(t);
        SheetRequest<List<Row>> request = db.worksheet("A").select().request().map(RowsResult::rows);
        assertEquals("A", sheet(request.execute()));
        assertEquals("A", sheet(request.executeAsync().join()));
        assertEquals(2, t.requests.size());
    }

    @Test
    void handlesAreOnlyReadableAfterExecuteAndTheQueueIsSingleUse() {
        FakeTransport t = new FakeTransport(APIRequestsQueueTest::echo);
        GridBase db = client(t);
        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<RowsResult> a = db.worksheet("A").select().queue(reqQ);
        assertFalse(a.isDone());
        assertThrows(IllegalStateException.class, a::get);

        reqQ.execute();
        assertTrue(a.isDone());
        assertThrows(IllegalStateException.class, reqQ::execute);
        assertThrows(IllegalStateException.class, () -> db.worksheet("B").select().queue(reqQ));
    }

    @Test
    void oneFailingRequestDoesNotFailTheOthers() {
        Function<JsonNode, String> responder = req -> worksheets(req).contains("Missing")
                ? "{\"success\":false,\"errors\":[{\"message\":\"Worksheet not found: Missing\"}]}"
                : echo(req);
        FakeTransport t = new FakeTransport(responder);
        GridBase db = client(t);

        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<RowsResult> insert = db.worksheet("A").insert(Map.of("id", "1")).queue(reqQ);
        Queued<RowsResult> missing = db.worksheet("Missing").select().queue(reqQ);
        Queued<RowsResult> b = db.worksheet("B").select().queue(reqQ);

        QueueExecutionException e = assertThrows(QueueExecutionException.class, reqQ::execute);
        assertEquals(1, e.failures().size());
        assertEquals(3, e.requestCount());
        assertInstanceOf(ServerException.class, e.getCause());

        assertEquals("A", sheet(insert.get().rows()));
        assertTrue(missing.failed());
        assertThrows(ServerException.class, missing::get);
        assertEquals("B", sheet(b.get().rows()));
        assertEquals(4, t.requests.size());           // combined, then each alone
    }

    @Test
    void transportFailureFailsEveryRequestInTheCall() {
        FakeTransport t = new FakeTransport(req -> {
            throw new TransportException("connection reset", 0, null, true);
        });
        GridBase db = client(t);
        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<RowsResult> a = db.worksheet("A").select().queue(reqQ);
        Queued<RowsResult> b = db.worksheet("B").select().queue(reqQ);

        QueueExecutionException e = assertThrows(QueueExecutionException.class, reqQ::execute);
        assertEquals(2, e.failures().size());
        assertThrows(TransportException.class, a::get);
        assertThrows(TransportException.class, b::get);
        assertEquals(1, t.requests.size());
    }

    @Test
    void schemaOperationsGoInTheirOwnCallKeepingOrder() {
        FakeTransport t = new FakeTransport(APIRequestsQueueTest::echo);
        GridBase db = client(t);
        APIRequestsQueue reqQ = new APIRequestsQueue();
        db.worksheet("A").select().queue(reqQ);
        db.worksheet("B").select().queue(reqQ);
        db.worksheet("A").clear().queue(reqQ);
        db.worksheet("C").select().queue(reqQ);
        reqQ.execute();

        assertEquals(3, t.requests.size());
        assertEquals(List.of("A", "B"), worksheets(t.requests.get(0)));
        assertEquals("CLEAR_WORKSHEET", t.requests.get(1).path("operations").get(0).path("type").asText());
        assertEquals(List.of("C"), worksheets(t.requests.get(2)));
    }

    @Test
    void maxOperationsPerCallSplitsInOrder() {
        FakeTransport t = new FakeTransport(APIRequestsQueueTest::echo);
        GridBase db = client(t);
        APIRequestsQueue reqQ = new APIRequestsQueue(2);
        for (String s : List.of("A", "B", "C")) {
            db.worksheet(s).select().queue(reqQ);
        }
        reqQ.execute();
        assertEquals(List.of("A", "B"), worksheets(t.requests.get(0)));
        assertEquals(List.of("C"), worksheets(t.requests.get(1)));
    }

    @Test
    void eachClientGetsItsOwnCall() {
        FakeTransport t1 = new FakeTransport(APIRequestsQueueTest::echo);
        FakeTransport t2 = new FakeTransport(APIRequestsQueueTest::echo);
        GridBase one = client(t1);
        GridBase two = client(t2);
        APIRequestsQueue reqQ = new APIRequestsQueue();
        one.worksheet("A").select().queue(reqQ);
        two.worksheet("B").select().queue(reqQ);
        one.worksheet("C").select().queue(reqQ);
        reqQ.execute();
        assertEquals(List.of("A", "C"), worksheets(t1.requests.get(0)));
        assertEquals(List.of("B"), worksheets(t2.requests.get(0)));
    }

    @Test
    void multiOperationRequestsStayTogetherAndEmptyOnesNeedNoCall() {
        FakeTransport t = new FakeTransport(APIRequestsQueueTest::echo);
        GridBase db = client(t);
        Repository<Echo> repo = new Repository<>(db, Echo.class);
        Echo x = new Echo();
        x.sheet = "k1";
        Echo y = new Echo();
        y.sheet = "k2";

        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<List<Echo>> saved = repo.requests().upsertAll(List.of(x, y)).queue(reqQ);
        Queued<List<Echo>> none = repo.requests().insertAll(List.of()).queue(reqQ);
        Queued<Boolean> exists = repo.requests().existsById("k1").queue(reqQ);
        reqQ.execute();

        assertEquals(1, t.requests.size());
        assertEquals(3, t.requests.get(0).path("operations").size());
        assertEquals(2, saved.get().size());
        assertTrue(none.get().isEmpty());
        assertTrue(exists.get());
    }

    @Test
    void cacheHitsAreNotSentAndWritesInvalidate() {
        FakeTransport t = new FakeTransport(APIRequestsQueueTest::echo);
        SqliteResponseCache cache = SqliteResponseCache.open(dir.resolve("q.db"));
        try {
            GridBase db = GridBase.builder()
                    .transport(t)
                    .defaultSpreadsheetId("S")
                    .cache(cache, CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10))
                    .build();
            db.worksheet("A").select().fetch();
            assertEquals(1, t.requests.size());

            APIRequestsQueue reqQ = new APIRequestsQueue();
            Queued<RowsResult> a = db.worksheet("A").select().queue(reqQ);
            Queued<RowsResult> b = db.worksheet("B").select().queue(reqQ);
            reqQ.execute();
            assertEquals(2, t.requests.size());
            assertEquals(List.of("B"), worksheets(t.requests.get(1)));
            assertEquals("A", sheet(a.get().rows()));
            assertEquals("B", sheet(b.get().rows()));

            db.worksheet("B").select().fetch();       // cached by the queue
            assertEquals(2, t.requests.size());

            APIRequestsQueue writes = new APIRequestsQueue();
            db.worksheet("A").insert(Map.of("id", "2")).queue(writes);
            writes.execute();
            db.worksheet("A").select().fetch();       // invalidated → network
            assertEquals(4, t.requests.size());
        } finally {
            cache.close();
        }
    }

    @Test
    void addAcceptsSpecsDirectly() {
        FakeTransport t = new FakeTransport(APIRequestsQueueTest::echo);
        GridBase db = client(t);
        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<RowsResult> a = reqQ.add(db.worksheet("A").select());
        reqQ.execute();
        assertSame(RowsResult.class, a.get().getClass());
    }

    // ------------------------------------------------------------------ failures, boundaries, grouping

    private static final String LOCK_TIMEOUT =
            "{\"success\":false,\"error\":\"Lock timeout: another process was holding the lock for too long.\"}";

    private static String notFound(String worksheet) {
        return "{\"success\":false,\"error\":\"Worksheet not found: " + worksheet + "\"}";
    }

    /** A request of plain SELECTs, one per worksheet, as one engine request. */
    private static SheetRequest<ExecutionResponse> selects(GridBase db, String... worksheets) {
        List<Operation> ops = new ArrayList<>();
        for (String w : worksheets) {
            ops.add(db.worksheet(w).select().toOperation());
        }
        return SheetRequest.of(db, ops, r -> r);
    }

    @Test
    void retryableRejectionFailsEveryHandleOfThatCallOnly() {
        FakeTransport t = new FakeTransport(req -> worksheets(req).contains("A") ? LOCK_TIMEOUT : echo(req));
        GridBase db = client(t);
        APIRequestsQueue reqQ = new APIRequestsQueue(2);
        Queued<RowsResult> a = db.worksheet("A").select().queue(reqQ);
        Queued<RowsResult> b = db.worksheet("B").select().queue(reqQ);
        Queued<RowsResult> c = db.worksheet("C").select().queue(reqQ);

        QueueExecutionException e = assertThrows(QueueExecutionException.class, reqQ::execute);
        assertEquals(2, e.failures().size());
        ServerException failure = assertThrows(ServerException.class, a::get);
        assertTrue(failure.isRetryable());
        assertSame(failure, b.failure());
        assertEquals("C", sheet(c.get().rows()));
        assertEquals(2, t.requests.size());                  // [A, B] once, never re-sent alone; then [C]
        assertEquals(List.of("C"), worksheets(t.requests.get(1)));
    }

    @Test
    void queueExecutionExceptionListsEveryFailureAndSuccessesStayReadable() {
        FakeTransport t = new FakeTransport(req -> worksheets(req).contains("Gone1") ? notFound("Gone1")
                : worksheets(req).contains("Gone2") ? notFound("Gone2") : echo(req));
        GridBase db = client(t);
        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<RowsResult> a = db.worksheet("A").select().queue(reqQ);
        Queued<RowsResult> gone1 = db.worksheet("Gone1").select().queue(reqQ);
        Queued<RowsResult> b = db.worksheet("B").select().queue(reqQ);
        Queued<RowsResult> gone2 = db.worksheet("Gone2").select().queue(reqQ);
        Queued<RowsResult> c = db.worksheet("C").select().queue(reqQ);

        QueueExecutionException e = assertThrows(QueueExecutionException.class, reqQ::execute);
        assertEquals(2, e.failures().size());
        assertEquals(5, e.requestCount());
        assertSame(gone1.failure(), e.getCause());
        assertSame(gone2.failure(), e.getSuppressed()[0]);
        assertTrue(e.getMessage().startsWith("2 of 5 queued requests failed"));
        assertEquals("A", sheet(a.get().rows()));
        assertEquals("B", sheet(b.get().rows()));
        assertEquals("C", sheet(c.get().rows()));
        assertEquals(6, t.requests.size());                  // combined, then each of the 5 alone
    }

    @Test
    void aThrowingMapperFailsOnlyItsHandle() {
        FakeTransport t = new FakeTransport(APIRequestsQueueTest::echo);
        GridBase db = client(t);
        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<RowsResult> a = db.worksheet("A").select().queue(reqQ);
        Queued<String> broken = db.worksheet("B").select().request().<String>map(r -> {
            throw new IllegalArgumentException("cannot map row");
        }).queue(reqQ);
        Queued<RowsResult> c = db.worksheet("C").select().queue(reqQ);

        QueueExecutionException e = assertThrows(QueueExecutionException.class, reqQ::execute);
        assertEquals(1, e.failures().size());
        assertThrows(IllegalArgumentException.class, broken::get);
        assertEquals("A", sheet(a.get().rows()));
        assertEquals("C", sheet(c.get().rows()));
        assertEquals(1, t.requests.size());
    }

    @Test
    void maxOperationsBoundaries() {
        FakeTransport t = new FakeTransport(APIRequestsQueueTest::echo);
        GridBase db = client(t);

        APIRequestsQueue exactlyMax = new APIRequestsQueue(3);
        selects(db, "X").queue(exactlyMax);
        Queued<ExecutionResponse> big = selects(db, "Y1", "Y2", "Y3").queue(exactlyMax);
        selects(db, "Z").queue(exactlyMax);
        exactlyMax.execute();
        assertEquals(3, t.requests.size());
        assertEquals(List.of("X"), worksheets(t.requests.get(0)));
        assertEquals(List.of("Y1", "Y2", "Y3"), worksheets(t.requests.get(1)));
        assertEquals(List.of("Z"), worksheets(t.requests.get(2)));
        assertEquals("op-3", big.get().results().get(2).operationId());

        APIRequestsQueue sumIsMax = new APIRequestsQueue(3);
        selects(db, "A1", "A2").queue(sumIsMax);
        selects(db, "B").queue(sumIsMax);
        sumIsMax.execute();
        assertEquals(4, t.requests.size());
        assertEquals(List.of("A1", "A2", "B"), worksheets(t.requests.get(3)));

        APIRequestsQueue sumIsMaxPlusOne = new APIRequestsQueue(3);
        selects(db, "C1", "C2").queue(sumIsMaxPlusOne);
        selects(db, "D1", "D2").queue(sumIsMaxPlusOne);
        sumIsMaxPlusOne.execute();
        assertEquals(6, t.requests.size());
        assertEquals(List.of("C1", "C2"), worksheets(t.requests.get(4)));
        assertEquals(List.of("D1", "D2"), worksheets(t.requests.get(5)));
    }

    /** A cache that cannot even be read: the client's whole group fails before anything is sent. */
    private static final class UnreadableCache implements ResponseCache {
        @Override
        public Optional<Entry> get(String key) {
            throw new IllegalStateException("cache unreadable");
        }

        @Override
        public void put(String key, String reply, Instant cachedAt, Instant expiresAt, Collection<SheetRef> sheets) {
        }

        @Override
        public int invalidate(String spreadsheetId, String worksheet) {
            return 0;
        }

        @Override
        public int purgeExpired(Instant now) {
            return 0;
        }

        @Override
        public void clear() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    void oneClientGroupFailingDoesNotStopOtherClients() {
        FakeTransport t1 = new FakeTransport(APIRequestsQueueTest::echo);
        FakeTransport t2 = new FakeTransport(APIRequestsQueueTest::echo);
        GridBase broken = GridBase.builder().transport(t1).defaultSpreadsheetId("S")
                .cache(new UnreadableCache(), CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10)).build();
        GridBase healthy = client(t2);

        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<RowsResult> a1 = broken.worksheet("A1").select().queue(reqQ);
        Queued<RowsResult> b1 = healthy.worksheet("B1").select().queue(reqQ);
        Queued<RowsResult> a2 = broken.worksheet("A2").select().queue(reqQ);

        QueueExecutionException e = assertThrows(QueueExecutionException.class, reqQ::execute);
        assertEquals(2, e.failures().size());
        assertThrows(IllegalStateException.class, a1::get);
        assertThrows(IllegalStateException.class, a2::get);
        assertEquals("B1", sheet(b1.get().rows()));
        assertEquals(0, t1.requests.size());
    }

    /** Requests are grouped per client (first-seen order), not sent strictly in the order queued. */
    @Test
    void requestsOnOtherClientsAreNotOrderedAsQueued() {
        List<String> calls = new ArrayList<>();
        GridBase one = client(new FakeTransport(req -> {
            calls.add("one " + worksheets(req));
            return echo(req);
        }));
        GridBase two = client(new FakeTransport(req -> {
            calls.add("two " + worksheets(req));
            return echo(req);
        }));

        APIRequestsQueue reqQ = new APIRequestsQueue();
        one.worksheet("A1").select().queue(reqQ);
        two.worksheet("B1").select().queue(reqQ);
        one.worksheet("A2").select().queue(reqQ);
        reqQ.execute();

        assertEquals(List.of("one [A1, A2]", "two [B1]"), calls);
    }

    @Test
    void aFailedChunkDoesNotAffectOtherChunks() {
        FakeTransport t = new FakeTransport(req -> worksheets(req).contains("C") ? notFound("C") : echo(req));
        GridBase db = client(t);
        APIRequestsQueue reqQ = new APIRequestsQueue(2);
        List<Queued<RowsResult>> handles = new ArrayList<>();
        for (String s : List.of("A", "B", "C", "D", "E", "F")) {
            handles.add(db.worksheet(s).select().queue(reqQ));
        }

        QueueExecutionException e = assertThrows(QueueExecutionException.class, reqQ::execute);
        assertEquals(1, e.failures().size());
        assertTrue(handles.get(2).failed());
        for (int i : List.of(0, 1, 3, 4, 5)) {
            assertEquals(List.of("A", "B", "C", "D", "E", "F").get(i), sheet(handles.get(i).get().rows()));
        }
        assertEquals(List.of(List.of("A", "B"), List.of("C", "D"), List.of("C"), List.of("D"), List.of("E", "F")),
                t.requests.stream().map(APIRequestsQueueTest::worksheets).collect(java.util.stream.Collectors.toList()));
    }

    /** Answers each operation the way the engine would, echoing written values back. */
    private static String engineLike(JsonNode req) {
        StringBuilder sb = new StringBuilder("{\"success\":true,\"requestId\":\"r\",\"results\":[");
        JsonNode ops = req.path("operations");
        for (int i = 0; i < ops.size(); i++) {
            JsonNode op = ops.get(i);
            sb.append(i == 0 ? "" : ",").append("{\"operationId\":\"").append(op.path("id").asText()).append("\",");
            switch (op.path("type").asText()) {
                case "INSERT" -> sb.append("\"rowCount\":").append(op.path("rows").size())
                        .append(",\"rows\":").append(op.path("rows")).append('}');
                case "UPSERT" -> sb.append("\"rowCount\":1,\"rows\":[").append(op.path("values")).append("]}");
                case "DELETE" -> sb.append("\"rowCount\":1,\"rows\":[{\"sheet\":\"gone\"}]}");
                case "CLEAR_WORKSHEET" -> sb.append("\"worksheet\":\"").append(op.path("worksheet").asText())
                        .append("\",\"rowsCleared\":4,\"columnsCleared\":2}");
                default -> sb.append("\"rowCount\":1,\"rows\":[{\"sheet\":\"").append(op.path("worksheet").asText())
                        .append("\",\"pos\":").append(i).append("}]}");
            }
        }
        return sb.append("]}").toString();
    }

    private static Echo echoEntity(String key) {
        Echo e = new Echo();
        e.sheet = key;
        e.pos = 7;
        return e;
    }

    private static List<String> types(JsonNode request) {
        List<String> types = new ArrayList<>();
        request.path("operations").forEach(op -> types.add(op.path("type").asText()));
        return types;
    }

    @Test
    void repositoryRequestsAllQueueCorrectly() {
        FakeTransport t = new FakeTransport(APIRequestsQueueTest::engineLike);
        GridBase db = client(t);
        Repository<Echo> repo = new Repository<>(db, Echo.class);

        APIRequestsQueue reqQ = new APIRequestsQueue();
        Queued<Optional<Echo>> first = repo.requests().findFirstWhere(eq("pos", 1)).queue(reqQ);
        Queued<Integer> byId = repo.requests().deleteById("k0").queue(reqQ);
        Queued<Integer> byEntity = repo.requests().delete(echoEntity("k9")).queue(reqQ);
        Queued<List<Echo>> inserted = repo.requests().insertAll(List.of(echoEntity("k1"), echoEntity("k2"))).queue(reqQ);
        Queued<List<Echo>> upserted = repo.requests().upsertAll(List.of(echoEntity("k3"), echoEntity("k4"))).queue(reqQ);
        Queued<Integer> cleared = repo.requests().deleteAll().queue(reqQ);
        reqQ.execute();

        assertEquals(2, t.requests.size());
        assertEquals(List.of("SELECT", "DELETE", "DELETE", "INSERT", "UPSERT", "UPSERT"), types(t.requests.get(0)));
        assertEquals(List.of("CLEAR_WORKSHEET"), types(t.requests.get(1)));
        assertEquals("k9", t.requests.get(0).path("operations").get(2).path("where").get(0).path("value").asText());

        assertEquals("Echo", first.get().orElseThrow().sheet);
        assertEquals(1, byId.get());
        assertEquals(1, byEntity.get());
        assertEquals(List.of("k1", "k2"), inserted.get().stream().map(e -> e.sheet).collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of("k3", "k4"), upserted.get().stream().map(e -> e.sheet).collect(java.util.stream.Collectors.toList()));
        assertEquals(7, upserted.get().get(1).pos);
        assertEquals(4, cleared.get());
    }

    @Test
    void emptyQueueExecutesWithoutACall() {
        new APIRequestsQueue().execute();

        FakeTransport t = new FakeTransport(APIRequestsQueueTest::echo);
        APIRequestsQueue onlyCompleted = new APIRequestsQueue();
        Queued<List<Echo>> none = new Repository<>(client(t), Echo.class).requests().insertAll(List.of()).queue(onlyCompleted);
        onlyCompleted.execute();
        assertTrue(none.get().isEmpty());
        assertEquals(0, t.requests.size());
    }

    @Test
    void rejectsMaxOperationsBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> new APIRequestsQueue(0));
        assertThrows(IllegalArgumentException.class, () -> new APIRequestsQueue(-5));
    }

    @Test
    void nonRuntimeFailureIsWrapped() {
        Queued<String> handle = new Queued<>();
        IOException cause = new IOException("disk full");
        handle.fail(cause);

        assertTrue(handle.failed());
        GridBaseException e = assertThrows(GridBaseException.class, handle::get);
        assertSame(cause, e.getCause());
        assertEquals("disk full", e.getMessage());
        assertFalse(e.isRetryable());

        Queued<String> runtime = new Queued<>();
        IllegalStateException direct = new IllegalStateException("x");
        runtime.fail(direct);
        assertSame(direct, runtime.failure());
    }
}
