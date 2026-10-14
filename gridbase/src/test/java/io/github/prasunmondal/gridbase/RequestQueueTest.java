package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.prasunmondal.gridbase.cache.CacheExpiry;
import io.github.prasunmondal.gridbase.cache.CacheStrategy;
import io.github.prasunmondal.gridbase.cache.InMemoryResponseCache;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static GridBase queued(FakeTransport t, Duration window, int max) {
        return GridBase.builder()
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
        GridBase db = queued(t, Duration.ofMillis(100), 50);

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
        GridBase db = queued(t, Duration.ofMillis(100), 50);
        db.worksheet("A").select().fetchAsync();
        var second = db.worksheet("B").select().executeAsync().join();
        assertEquals("op-1", second.operationId());
        assertEquals(1, second.rows().get(0).getInteger("pos"));
    }

    @Test
    void concurrentSyncCallsFromManyThreadsAreCombined() throws Exception {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        GridBase db = queued(t, Duration.ofMillis(300), 50);
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
        GridBase db = queued(t, Duration.ofMillis(100), 2);
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
        GridBase db = queued(t, Duration.ofMillis(100), 50);

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
        GridBase db = queued(t, Duration.ofMillis(100), 50);
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
        GridBase db = queued(t, Duration.ofMillis(10), 50);
        String callbackThread = db.worksheet("A").select().fetchAsync()
                .thenApply(rows -> Thread.currentThread().getName()).join();
        assertEquals(List.of("gridbase-queue"), networkThreads);
        assertNotEquals("gridbase-queue", callbackThread);
    }

    @Test
    void withoutAQueueSyncCallsStayOnTheCallersThread() {
        List<String> networkThreads = new ArrayList<>();
        FakeTransport t = new FakeTransport(req -> {
            networkThreads.add(Thread.currentThread().getName());
            return echo(req);
        });
        GridBase db = GridBase.builder().transport(t).defaultSpreadsheetId("S").build();
        db.worksheet("A").select().fetch();
        assertEquals(List.of(Thread.currentThread().getName()), networkThreads);
        assertEquals("B", sheet(db.worksheet("B").select().fetchAsync().join()));
    }

    @Test
    void aHookThatQueriesTheSheetDoesNotDeadlock() {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        boolean[] once = {false};
        GridBase[] holder = new GridBase[1];
        holder[0] = GridBase.builder()
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
            GridBase db = GridBase.builder()
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

    // ------------------------------------------------------------------ ordering, isolation, failures

    private static final String LOCK_TIMEOUT =
            "{\"success\":false,\"error\":\"Lock timeout: another process was holding the lock for too long.\"}";

    /** Like {@link #echo}, but GET_LAST_UPDATED operations get a {@code lastUpdated} time. */
    private static String typedEcho(JsonNode req) {
        StringBuilder sb = new StringBuilder("{\"success\":true,\"requestId\":\"r\",\"results\":[");
        JsonNode ops = req.path("operations");
        for (int i = 0; i < ops.size(); i++) {
            JsonNode op = ops.get(i);
            sb.append(i == 0 ? "" : ",").append("{\"operationId\":\"").append(op.path("id").asText()).append("\",");
            if (op.path("type").asText().equals("GET_LAST_UPDATED")) {
                sb.append("\"lastUpdated\":1760000000000}");
            } else {
                sb.append("\"rowCount\":1,\"rows\":[{\"sheet\":\"").append(op.path("worksheet").asText())
                        .append("\",\"pos\":").append(i).append("}]}");
            }
        }
        return sb.append("]}").toString();
    }

    private static GridBase queued(FakeTransport t, Duration window, int max, RetryPolicy policy) {
        return GridBase.builder()
                .transport(t)
                .defaultSpreadsheetId("S")
                .retryPolicy(policy)
                .requestQueue(window, max)
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void requestsMadeDuringAnInFlightCallGoInTheNextCallInOrder() {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport t = new FakeTransport(req -> {
            if (worksheets(req).contains("R1")) {
                inFlight.countDown();
                await(release);
            }
            return echo(req);
        });
        GridBase db = queued(t, Duration.ofMillis(10), 50);

        var r1 = db.worksheet("R1").select().fetchAsync();
        await(inFlight);
        var r2 = db.worksheet("R2").select().fetchAsync();
        var r3 = db.worksheet("R3").select().fetchAsync();
        release.countDown();

        assertEquals("R1", sheet(r1.join()));
        assertEquals("R2", sheet(r2.join()));
        assertEquals("R3", sheet(r3.join()));
        assertEquals(2, t.requests.size());
        assertEquals(List.of("R1"), worksheets(t.requests.get(0)));
        assertEquals(List.of("R2", "R3"), worksheets(t.requests.get(1)));
    }

    @Test
    void neverTwoCallsAtOnce() throws Exception {
        AtomicInteger sending = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        FakeTransport t = new FakeTransport(req -> {
            mostAtOnce.accumulateAndGet(sending.incrementAndGet(), Math::max);
            try {
                Thread.yield();
                return echo(req);
            } finally {
                sending.decrementAndGet();
            }
        });
        GridBase db = queued(t, Duration.ofMillis(1), 7);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> threads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int thread = i;
            threads.add(pool.submit(() -> {
                start.await();
                List<CompletableFuture<List<Row>>> mine = new ArrayList<>();
                for (int j = 0; j < 10; j++) {
                    String name = "T" + thread + "_" + j;
                    if (thread % 2 == 0) {
                        assertEquals(name, sheet(db.worksheet(name).select().fetch()));
                    } else {
                        mine.add(db.worksheet(name).select().fetchAsync());
                    }
                }
                for (int j = 0; j < mine.size(); j++) {
                    assertEquals("T" + thread + "_" + j, sheet(mine.get(j).join()));
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : threads) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, mostAtOnce.get());
        assertEquals(200, t.requests.stream().mapToInt(r -> r.path("operations").size()).sum());
    }

    @Test
    void retryableRejectionOfACombinedCallFailsAllAndIsNotResentAlone() {
        FakeTransport t = new FakeTransport(req -> LOCK_TIMEOUT);
        GridBase db = queued(t, Duration.ofMillis(100), 50);

        var read = db.worksheet("A").select().fetchAsync();
        var write = db.worksheet("B").insert(Map.of("id", "1")).executeAsync();

        ServerException e = assertInstanceOf(ServerException.class, failureOf(read));
        assertTrue(e.isRetryable());
        assertSame(e, failureOf(write));
        assertEquals(1, t.requests.size());
        assertEquals(List.of("A", "B"), worksheets(t.requests.get(0)));
    }

    @Test
    void combinedCallWithAWriteIsNotRetriedSoItsReadsAreNotEither() {
        RetryPolicy threeTries = RetryPolicy.of(3, Duration.ZERO);
        Function<JsonNode, String> down = req -> {
            throw new TransportException("connection reset", -1, null, true);
        };

        FakeTransport mixed = new FakeTransport(down);
        GridBase db = queued(mixed, Duration.ofMillis(100), 50, threeTries);
        var read = db.worksheet("A").select().fetchAsync();
        var write = db.worksheet("B").insert(Map.of("id", "1")).executeAsync();
        assertInstanceOf(TransportException.class, failureOf(read));
        assertInstanceOf(TransportException.class, failureOf(write));
        assertEquals(1, mixed.requests.size(), "a read combined with a write loses its retries");

        FakeTransport readsOnly = new FakeTransport(down);
        db = queued(readsOnly, Duration.ofMillis(100), 50, threeTries);
        var a = db.worksheet("A").select().fetchAsync();
        var b = db.worksheet("B").select().fetchAsync();
        assertInstanceOf(TransportException.class, failureOf(a));
        assertInstanceOf(TransportException.class, failureOf(b));
        assertEquals(3, readsOnly.requests.size());
        assertEquals(List.of("A", "B"), worksheets(readsOnly.requests.get(2)));

        FakeTransport optedIn = new FakeTransport(down);
        db = queued(optedIn, Duration.ofMillis(100), 50, threeTries.retryingWrites(true));
        read = db.worksheet("A").select().fetchAsync();
        write = db.worksheet("B").insert(Map.of("id", "1")).executeAsync();
        assertInstanceOf(TransportException.class, failureOf(read));
        assertInstanceOf(TransportException.class, failureOf(write));
        assertEquals(3, optedIn.requests.size());
    }

    @Test
    void reachingMaxOperationsSendsWithoutWaitingForTheWindow() {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        GridBase db = queued(t, Duration.ofSeconds(10), 3);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var a = db.worksheet("A").select().fetchAsync();
            var b = db.worksheet("B").select().fetchAsync();
            var c = db.worksheet("C").select().fetchAsync();
            assertEquals("C", sheet(c.join()));
            a.join();
            b.join();
        });
        assertEquals(1, t.requests.size());
    }

    /** The whole combined reply is parsed before it is split, so one missing result fails every caller. */
    @Test
    void aShortReplyFailsEveryRequestInTheCall() {
        FakeTransport t = new FakeTransport(req -> echo(req).replace(
                ",{\"operationId\":\"op-3\"", ",{\"operationId\":\"op-x\""));
        GridBase db = queued(t, Duration.ofMillis(100), 50);
        var a = db.worksheet("A").select().fetchAsync();
        var b = db.worksheet("B").select().fetchAsync();
        var c = db.worksheet("C").select().fetchAsync();

        TransportException e = assertInstanceOf(TransportException.class, failureOf(c));
        assertTrue(e.getMessage().contains("op-3"));
        assertSame(e, failureOf(a));
        assertSame(e, failureOf(b));
        assertEquals(1, t.requests.size());
    }

    @Test
    void workerKeepsRunningAfterAFailedCall() {
        AtomicInteger calls = new AtomicInteger();
        FakeTransport t = new FakeTransport(req -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("transport bug");
            }
            return echo(req);
        });
        GridBase db = queued(t, Duration.ofMillis(50), 50);
        var a = db.worksheet("A").select().fetchAsync();
        var b = db.worksheet("B").select().fetchAsync();
        assertInstanceOf(IllegalStateException.class, failureOf(a));
        assertInstanceOf(IllegalStateException.class, failureOf(b));

        assertEquals("C", sheet(db.worksheet("C").select().fetchAsync().join()));
        assertEquals("D", sheet(db.worksheet("D").select().fetch()));
    }

    @Test
    void workerKeepsRunningWhenSendingItselfThrows() {
        AtomicInteger calls = new AtomicInteger();
        RequestQueue queue = new RequestQueue(Duration.ZERO, 5, requests -> {
            if (calls.incrementAndGet() == 1) {
                throw new AssertionError("send blew up");
            }
            List<GridBase.Outcome<GridBase.Reply>> out = new ArrayList<>();
            requests.forEach(r -> out.add(new GridBase.Outcome<>(new GridBase.Reply("ok", null), null)));
            return out;
        }, Runnable::run);
        Operation select = new Operation(OperationType.SELECT, "S", "A", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), false, -1, 0);

        assertInstanceOf(AssertionError.class, failureOf(queue.submit(List.of(select))));
        assertEquals("ok", queue.submit(List.of(select)).join().body());
    }

    @Test
    void syncCallRethrowsTheOriginalException() {
        GridBase rejected = queued(new FakeTransport(req -> "{\"success\":false,\"error\":\"Unknown column: x\"}"),
                Duration.ofMillis(10), 50);
        ServerException server = assertThrows(ServerException.class, () -> rejected.worksheet("A").select().fetch());
        assertEquals("Unknown column: x", server.getServerMessage());

        GridBase down = queued(new FakeTransport(req -> {
            throw new TransportException("connection reset", -1, null, true);
        }), Duration.ofMillis(10), 50);
        assertThrows(TransportException.class, () -> down.worksheet("A").insert(Map.of("id", 1)).execute());
    }

    @Test
    void hooksRunOncePerCombinedCall() {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        List<NetworkCall> before = Collections.synchronizedList(new ArrayList<>());
        List<NetworkCallResult> after = Collections.synchronizedList(new ArrayList<>());
        GridBase db = GridBase.builder()
                .transport(t)
                .defaultSpreadsheetId("S")
                .requestQueue(Duration.ofMillis(100), 50)
                .preNetworkCall(before::add)
                .postNetworkCall(after::add)
                .build();

        var a = db.worksheet("A").select().fetchAsync();
        var b = db.worksheet("B").select().fetchAsync();
        var c = db.worksheet("C").select().fetchAsync();
        a.join();
        b.join();
        c.join();

        assertEquals(1, before.size());
        assertEquals(1, after.size());
        assertEquals(List.of("A", "B", "C"), worksheets(FakeTransport.json(before.get(0).requestBody())));
        assertSame(before.get(0), after.get(0).call());
        assertTrue(after.get(0).succeeded());
    }

    @Test
    void twoClientsHaveIndependentQueues() throws Exception {
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeTransport first = new FakeTransport(req -> {
            inFlight.countDown();
            await(release);
            return echo(req);
        });
        FakeTransport second = new FakeTransport(RequestQueueTest::echo);
        GridBase one = queued(first, Duration.ofMillis(20), 50);
        GridBase two = queued(second, Duration.ofMillis(20), 50);

        var a = one.worksheet("A").select().fetchAsync();
        await(inFlight);
        var b = two.worksheet("B").select().fetchAsync();
        assertEquals("B", sheet(b.get(5, TimeUnit.SECONDS)));    // not held up by the other client's call
        release.countDown();
        assertEquals("A", sheet(a.join()));

        assertEquals(1, first.requests.size());
        assertEquals(List.of("A"), worksheets(first.requests.get(0)));
        assertEquals(List.of("B"), worksheets(second.requests.get(0)));
    }

    @Test
    void lastUpdatedIsQueuedButNeverCached() {
        FakeTransport t = new FakeTransport(RequestQueueTest::typedEcho);
        GridBase db = GridBase.builder()
                .transport(t)
                .defaultSpreadsheetId("S")
                .requestQueue(Duration.ofMillis(100), 50)
                .cache(new InMemoryResponseCache(), CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10))
                .build();

        var updated = db.worksheet("A").lastUpdated().executeAsync();
        var rows = db.worksheet("A").select().fetchAsync();
        assertEquals(1760000000000L, updated.join().lastUpdated().toEpochMilli());
        assertEquals("A", sheet(rows.join()));
        assertEquals(1, t.requests.size());
        assertEquals(2, t.requests.get(0).path("operations").size());

        db.worksheet("A").lastUpdated().fetch();
        assertEquals(2, t.requests.size());
        db.worksheet("A").select().fetch();
        assertEquals(2, t.requests.size());
    }

    @Test
    void rejectsBadQueueSettings() {
        Function<List<List<Operation>>, List<GridBase.Outcome<GridBase.Reply>>> send = requests -> List.of();
        assertThrows(IllegalArgumentException.class, () -> new RequestQueue(Duration.ofMillis(-1), 5, send, Runnable::run));
        assertThrows(IllegalArgumentException.class, () -> new RequestQueue(Duration.ZERO, 0, send, Runnable::run));
        assertThrows(IllegalArgumentException.class, () -> GridBase.builder()
                .transport(new FakeTransport(RequestQueueTest::echo)).requestQueue(Duration.ofMillis(-1), 5).build());
        assertThrows(IllegalArgumentException.class, () -> GridBase.builder()
                .transport(new FakeTransport(RequestQueueTest::echo)).requestQueue(Duration.ZERO, 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> SheetProperties.builder().queueRequests(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> SheetProperties.builder().queueMaxOperations(0));
    }

    @Test
    void zeroWindowSendsPromptly() {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        GridBase db = queued(t, Duration.ZERO, 50);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            List<CompletableFuture<List<Row>>> all = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                all.add(db.worksheet("T" + i).select().fetchAsync());
            }
            for (int i = 0; i < 20; i++) {
                assertEquals("T" + i, sheet(all.get(i).join()));
            }
            assertEquals("S", sheet(db.worksheet("S").select().fetch()));
        });
    }

    /** The same query, alone or as part of a combined call, is stored under one key. */
    @Test
    void sameQueryAloneOrSlicedFromACombinedCallSharesOneKey() {
        FakeTransport t = new FakeTransport(RequestQueueTest::echo);
        GridBase db = GridBase.builder()
                .transport(t)
                .defaultSpreadsheetId("S")
                .requestQueue(Duration.ofMillis(100), 50)
                .cache(new InMemoryResponseCache(), CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10))
                .build();

        db.worksheet("A").select().where(eq("id", "1")).fetch();               // alone: op-1 of its call
        var b = db.worksheet("B").select().fetchAsync();
        var a = db.worksheet("A").select().where(eq("id", "1")).fetchAsync();  // would be op-2: a cache hit
        b.join();
        assertEquals("A", sheet(a.join()));
        assertEquals(2, t.requests.size());
        assertEquals(List.of("B"), worksheets(t.requests.get(1)));

        var c = db.worksheet("C").select().fetchAsync();
        var b2 = db.worksheet("B").select().where(eq("id", "2")).fetchAsync(); // op-2 of a combined call
        c.join();
        b2.join();
        assertEquals(3, t.requests.size());
        db.worksheet("B").select().where(eq("id", "2")).fetch();               // alone: answered from the cache
        assertEquals(3, t.requests.size());
    }
}
