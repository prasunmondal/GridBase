package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.prasunmondal.gridbase.cache.CacheExpiry;
import io.github.prasunmondal.gridbase.cache.CacheStrategy;
import io.github.prasunmondal.gridbase.cache.SqliteResponseCache;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.spec.Operation;
import io.github.prasunmondal.gridbase.spec.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import static io.github.prasunmondal.gridbase.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestQueueTest {

    @TempDir
    Path dir;

    /** Each operation's single result row says which worksheet it read and where it sat in the request. */
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

    private static HibernateSheets queued(FakeTransport t, Duration window, int max) {
        return HibernateSheets.builder()
                .transport(t)
                .defaultSpreadsheetId("S")
                .retryPolicy(RetryPolicy.none())
                .requestQueue(window, max)
                .build();
    }

    private static String sheet(List<Row> rows) {
        return rows.get(0).getString("sheet");
    }

    private static Throwable failureOf(CompletableFuture<?> f) {
        CompletionException e = assertThrows(CompletionException.class, f::join);
        return e.getCause();
    }

    @Test
    void asyncRequestsWithinTheWindowGoInOneCall() {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        HibernateSheets db = queued(t, Duration.ofMillis(100), 50);

        CompletableFuture<List<Row>> emp = db.worksheet("Emp").select().fetchAsync();
        CompletableFuture<List<Row>> dept = db.worksheet("Dept").select().fetchAsync();
        CompletableFuture<List<Row>> one = db.worksheet("Emp").select().where(eq("id", "1")).fetchAsync();

        assertEquals("Emp", sheet(emp.join()));
        assertEquals("Dept", sheet(dept.join()));
        assertEquals("Emp", sheet(one.join()));
        assertEquals(1, t.requests.size());
        assertEquals(List.of("Emp", "Dept", "Emp"), worksheets(t.requests.get(0)));
    }

    @Test
    void eachCallerSeesResultsNumberedAsIfSentAlone() {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        HibernateSheets db = queued(t, Duration.ofMillis(100), 50);
        db.worksheet("A").select().fetchAsync();
        var second = db.worksheet("B").select().executeAsync().join();
        assertEquals("op-1", second.operationId());
        assertEquals(1, second.rows().get(0).getInteger("pos"));
    }

    @Test
    void concurrentSyncCallsFromManyThreadsAreCombined() throws Exception {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        HibernateSheets db = queued(t, Duration.ofMillis(300), 50);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String name = "T" + i;
            results.add(pool.submit(() -> {
                start.await();
                return sheet(db.worksheet(name).select().fetch());
            }));
        }
        start.countDown();
        for (int i = 0; i < 5; i++) {
            assertEquals("T" + i, results.get(i).get());
        }
        pool.shutdown();
        assertEquals(1, t.requests.size());
        assertEquals(5, t.requests.get(0).path("operations").size());
    }

    @Test
    void maxOperationsSplitsIntoOrderedCalls() {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        HibernateSheets db = queued(t, Duration.ofMillis(100), 2);
        List<CompletableFuture<List<Row>>> all = new ArrayList<>();
        for (String s : List.of("A", "B", "C", "D", "E")) {
            all.add(db.worksheet(s).select().fetchAsync());
        }
        all.forEach(CompletableFuture::join);
        assertEquals(3, t.requests.size());
        assertEquals(List.of("A", "B"), worksheets(t.requests.get(0)));
        assertEquals(List.of("C", "D"), worksheets(t.requests.get(1)));
        assertEquals(List.of("E"), worksheets(t.requests.get(2)));
    }

    @Test
    void aRejectedCombinedCallIsResentPerRequestSoOthersStillSucceed() {
        Function<JsonNode, String> responder = req -> worksheets(req).contains("Missing")
                ? "{\"success\":false,\"errors\":[{\"message\":\"Worksheet not found: Missing\"}]}"
                : echo(req);
        FakeTransport t = new FakeTransport(responder);
        HibernateSheets db = queued(t, Duration.ofMillis(100), 50);

        var emp = db.worksheet("Emp").insert(java.util.Map.of("id", "9")).executeAsync();
        var missing = db.worksheet("Missing").select().fetchAsync();
        var dept = db.worksheet("Dept").select().fetchAsync();

        assertEquals("Emp", emp.join().rows().get(0).getString("sheet"));
        assertInstanceOf(ServerException.class, failureOf(missing));
        assertEquals("Dept", sheet(dept.join()));
        assertEquals(4, t.requests.size());           // combined + one per request
        assertEquals(List.of("Emp"), worksheets(t.requests.get(1)));
    }

    @Test
    void transportFailureFailsEveryQueuedRequest() {
        FakeTransport t = new FakeTransport(req -> {
            throw new TransportException("connection reset", 0, null, true);
        });
        HibernateSheets db = queued(t, Duration.ofMillis(100), 50);
        var a = db.worksheet("A").select().fetchAsync();
        var b = db.worksheet("B").insert(java.util.Map.of("x", 1)).executeAsync();
        assertInstanceOf(TransportException.class, failureOf(a));
        assertInstanceOf(TransportException.class, failureOf(b));
        assertEquals(1, t.requests.size());
        assertThrows(TransportException.class, () -> db.worksheet("A").select().fetch());
    }

    @Test
    void schemaOperationsAndOversizedRequestsAreNotQueued() {
        RequestQueue queue = new RequestQueue(Duration.ZERO, 2, requests -> List.of(), Runnable::run);
        Operation select = new Operation(OperationType.SELECT, "S", "A", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), false, -1, 0);
        Operation clear = new Operation(OperationType.CLEAR_WORKSHEET, "S", "A", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), false, -1, 0);
        assertTrue(queue.accepts(List.of(select, select)));
        assertFalse(queue.accepts(List.of(select, select, select)));
        assertFalse(queue.accepts(List.of(clear)));
        assertFalse(queue.accepts(List.of(select, clear)));
    }

    @Test
    void networkRunsOnTheQueueThreadButCallbacksDoNot() {
        List<String> networkThreads = new ArrayList<>();
        FakeTransport t = new FakeTransport(req -> {
            networkThreads.add(Thread.currentThread().getName());
            return echo(req);
        });
        HibernateSheets db = queued(t, Duration.ofMillis(10), 50);
        String callbackThread = db.worksheet("A").select().fetchAsync()
                .thenApply(rows -> Thread.currentThread().getName()).join();
        assertEquals(List.of("hibernate-sheets-queue"), networkThreads);
        assertNotEquals("hibernate-sheets-queue", callbackThread);
    }

    @Test
    void withoutAQueueSyncCallsStayOnTheCallersThread() {
        List<String> networkThreads = new ArrayList<>();
        FakeTransport t = new FakeTransport(req -> {
            networkThreads.add(Thread.currentThread().getName());
            return echo(req);
        });
        HibernateSheets db = HibernateSheets.builder().transport(t).defaultSpreadsheetId("S").build();
        db.worksheet("A").select().fetch();
        assertEquals(List.of(Thread.currentThread().getName()), networkThreads);
        assertEquals("B", sheet(db.worksheet("B").select().fetchAsync().join()));
    }

    @Test
    void aHookThatQueriesTheSheetDoesNotDeadlock() {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        boolean[] once = {false};
        HibernateSheets[] holder = new HibernateSheets[1];
        holder[0] = HibernateSheets.builder()
                .transport(t)
                .defaultSpreadsheetId("S")
                .requestQueue(Duration.ofMillis(10), 50)
                .postNetworkCall(result -> {
                    if (!once[0]) {
                        once[0] = true;
                        holder[0].worksheet("Audit").select().fetch();
                    }
                })
                .build();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> holder[0].worksheet("A").select().fetch());
        assertEquals(2, t.requests.size());
    }

    @Test
    void queuedReadsAreCachedPerRequest() {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        SqliteResponseCache cache = SqliteResponseCache.open(dir.resolve("q.db"));
        try {
            HibernateSheets db = HibernateSheets.builder()
                    .transport(t)
                    .defaultSpreadsheetId("S")
                    .requestQueue(Duration.ofMillis(100), 50)
                    .cache(cache, CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10))
                    .build();
            var a = db.worksheet("A").select().fetchAsync();
            var b = db.worksheet("B").select().fetchAsync();
            a.join();
            b.join();
            assertEquals(1, t.requests.size());

            assertEquals("B", sheet(db.worksheet("B").select().fetch()));
            assertEquals("A", sheet(db.worksheet("A").select().fetch()));
            assertEquals(1, t.requests.size());
        } finally {
            cache.close();
        }
    }

    @Test
    void propertiesDerivedByTabNameShareOneClientAndQueue() {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        SheetProperties base = SheetProperties.builder()
                .transport(t)
                .dbSheetUrl("S")
                .queueRequests(Duration.ofMillis(100))
                .build();
        SheetProperties emp = base.toBuilder().tabName("Emp").build();
        SheetProperties dept = base.toBuilder().tabName("Dept").build();
        SheetProperties hooked = base.toBuilder().tabName("Emp").preNetworkCall(c -> { }).build();

        assertSame(emp.client(), dept.client());
        assertNotSame(emp.client(), hooked.client());

        var e = emp.worksheet().select().fetchAsync();
        var d = dept.worksheet().select().fetchAsync();
        assertEquals("Emp", sheet(e.join()));
        assertEquals("Dept", sheet(d.join()));
        assertEquals(1, t.requests.size());
    }
}
