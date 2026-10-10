package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.prasunmondal.gridbase.cache.CacheExpiry;
import io.github.prasunmondal.gridbase.cache.CacheStrategy;
import io.github.prasunmondal.gridbase.cache.SqliteResponseCache;
import io.github.prasunmondal.gridbase.exception.QueueExecutionException;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.mapping.Repository;
import io.github.prasunmondal.gridbase.mapping.SheetKey;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.result.RowsResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
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
}
