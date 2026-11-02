package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.prasunmondal.gridbase.cache.CacheExpiry;
import io.github.prasunmondal.gridbase.cache.CacheStrategy;
import io.github.prasunmondal.gridbase.cache.InMemoryResponseCache;
import io.github.prasunmondal.gridbase.cache.ResponseCache;
import io.github.prasunmondal.gridbase.exception.ServerException;
import io.github.prasunmondal.gridbase.exception.TransportException;
import io.github.prasunmondal.gridbase.result.Row;
import io.github.prasunmondal.gridbase.spec.Operation;
import io.github.prasunmondal.gridbase.spec.OperationType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The automatic queue keeps the response cache consistent no matter in which order caller callbacks
 * run. Callbacks are held and released in <b>reverse</b> order, which deterministically reproduces
 * the race where cache work ran on callback threads (a write's invalidation erasing a later read's
 * fresh reply, or an earlier read's stale reply stored after the invalidation).
 *
 * <p>The fake engine keeps a version per worksheet: every write bumps it, every read returns it, so
 * the assertions check data freshness, not only call counts.</p>
 */
class QueueCacheOrderingTest {

    private static final Duration WINDOW = Duration.ofMillis(100);

    /** Worksheet → version. Writes bump it; reads report it. "Missing" makes the engine reject the call. */
    private static final class VersionedEngine {
        final Map<String, Integer> versions = new ConcurrentHashMap<>();
        volatile boolean loseReplies;   // writes commit, then the reply is lost in transport
        volatile boolean down;

        void bump(String worksheet) {
            versions.merge(worksheet, 1, Integer::sum);
        }

        String reply(JsonNode req) {
            if (down) {
                throw new TransportException("connection reset", -1, null, true);
            }
            JsonNode ops = req.path("operations");
            for (JsonNode op : ops) {
                if (op.path("worksheet").asText().equals("Missing")) {
                    return "{\"success\":false,\"errors\":[{\"message\":\"Worksheet not found: Missing\"}]}";
                }
            }
            StringBuilder sb = new StringBuilder("{\"success\":true,\"requestId\":\"r\",\"results\":[");
            for (int i = 0; i < ops.size(); i++) {
                JsonNode op = ops.get(i);
                String ws = op.path("worksheet").asText();
                if (!OperationType.valueOf(op.path("type").asText()).isReadOnly()) {
                    bump(ws);
                }
                sb.append(i == 0 ? "" : ",")
                        .append("{\"operationId\":\"").append(op.path("id").asText())
                        .append("\",\"rowCount\":1,\"rows\":[{\"sheet\":\"").append(ws)
                        .append("\",\"v\":").append(versions.getOrDefault(ws, 0)).append("}]}");
            }
            if (loseReplies) {
                throw new TransportException("read timed out", -1, null, true);
            }
            return sb.append("]}").toString();
        }
    }

    /** Holds callbacks until released, then runs them newest first; later callbacks go to a pool. */
    private static final class ReversingExecutor implements Executor {
        private final List<Runnable> held = new ArrayList<>();
        private boolean released;

        @Override
        public synchronized void execute(Runnable r) {
            if (released) {
                ForkJoinPool.commonPool().execute(r);
            } else {
                held.add(r);
                notifyAll();
            }
        }

        /** Waits (no sleep) until {@code n} callbacks are held, then runs them in reverse order. */
        void releaseReversed(int n) throws InterruptedException {
            List<Runnable> toRun;
            synchronized (this) {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (held.size() < n) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        throw new AssertionError("only " + held.size() + " of " + n + " callbacks arrived");
                    }
                    wait(Math.max(1, left / 1_000_000));
                }
                toRun = new ArrayList<>(held);
                held.clear();
                released = true;
            }
            Collections.reverse(toRun);
            toRun.forEach(Runnable::run);
        }
    }

    private final VersionedEngine engine = new VersionedEngine();
    private final FakeTransport transport = new FakeTransport(engine::reply);
    private final ReversingExecutor callbacks = new ReversingExecutor();

    private GridBase client(int maxOperations, ResponseCache cache, CacheStrategy strategy) {
        return GridBase.builder()
                .transport(transport)
                .defaultSpreadsheetId("S")
                .retryPolicy(RetryPolicy.none())
                .requestQueue(WINDOW, maxOperations)
                .queueCallbacks(callbacks)
                .cache(cache, strategy, CacheExpiry.ttlMinutes(10))
                .build();
    }

    private GridBase client() {
        return client(50, new InMemoryResponseCache(), CacheStrategy.CACHE_FIRST);
    }

    private static int version(List<Row> rows) {
        return rows.get(0).getInteger("v");
    }

    private static Throwable failureOf(CompletableFuture<?> f) {
        return assertThrows(CompletionException.class, f::join).getCause();
    }

    // ---- RequestQueue contract ----------------------------------------------------------------

    @Test
    void onWorkerHooksRunInCallOrderOnTheWorkerBeforeAnyFutureCompletes() throws Exception {
        List<CompletableFuture<GridBase.Reply>> futures = Collections.synchronizedList(new ArrayList<>());
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        RequestQueue queue = new RequestQueue(WINDOW, 50, requests -> {
            List<GridBase.Outcome<GridBase.Reply>> out = new ArrayList<>();
            requests.forEach(r -> out.add(new GridBase.Outcome<>(new GridBase.Reply(r.get(0).worksheet(), null), null)));
            return out;
        }, callbacks);

        for (String ws : List.of("A", "B", "C")) {
            futures.add(queue.submit(List.of(select(ws)), (reply, failure) -> {
                boolean anyDone = futures.stream().anyMatch(CompletableFuture::isDone);
                seen.add(reply.body() + "@" + Thread.currentThread().getName() + (anyDone ? ":late" : ""));
                if (reply.body().equals("B")) {
                    throw new IllegalStateException("a broken hook must not stop the others");
                }
            }));
        }
        callbacks.releaseReversed(3);

        assertEquals(List.of("A@gridbase-queue", "B@gridbase-queue", "C@gridbase-queue"), seen);
        assertEquals(List.of("A", "B", "C"), futures.stream().map(f -> f.join().body()).toList());
    }

    @Test
    void onWorkerHooksSeeTheFailureWhenSendingThrows() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        RequestQueue queue = new RequestQueue(WINDOW, 50, requests -> {
            throw new TransportException("connection reset", -1, null, true);
        }, callbacks);

        CompletableFuture<GridBase.Reply> a = queue.submit(List.of(select("A")),
                (reply, failure) -> seen.add("A:" + failure.getClass().getSimpleName()));
        CompletableFuture<GridBase.Reply> b = queue.submit(List.of(select("B")),
                (reply, failure) -> seen.add("B:" + failure.getClass().getSimpleName()));
        callbacks.releaseReversed(2);

        assertEquals(List.of("A:TransportException", "B:TransportException"), seen);
        assertInstanceOf(TransportException.class, failureOf(a));
        assertInstanceOf(TransportException.class, failureOf(b));
    }

    private static Operation select(String worksheet) {
        return new Operation(OperationType.SELECT, "S", worksheet, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), false, -1, 0);
    }

    // ---- cache consistency through GridBase, callbacks reversed --------------------------------

    @Test
    void writeThenReadKeepsTheFreshReadCached() throws Exception {
        GridBase db = client();
        Worksheet a = db.worksheet("A");

        var write = a.insert(Map.of("x", 1)).executeAsync();
        var read = a.select().fetchAsync();
        callbacks.releaseReversed(2);
        write.join();
        assertEquals(1, version(read.join()));

        int calls = transport.requests.size();
        assertEquals(1, version(a.select().fetch()));
        assertEquals(calls, transport.requests.size(), "the post-write read was not cached");
    }

    @Test
    void readThenWriteDoesNotLeaveTheStaleReadCached() throws Exception {
        GridBase db = client();
        Worksheet a = db.worksheet("A");

        var read = a.select().fetchAsync();
        var write = a.insert(Map.of("x", 1)).executeAsync();
        callbacks.releaseReversed(2);
        assertEquals(0, version(read.join()));
        write.join();

        assertEquals(1, version(a.select().fetch()), "a stale cached reply was served");
    }

    @Test
    void writeToOneTabKeepsAReadOfAnotherTabCached() throws Exception {
        GridBase db = client();

        var write = db.worksheet("A").insert(Map.of("x", 1)).executeAsync();
        var read = db.worksheet("B").select().fetchAsync();
        callbacks.releaseReversed(2);
        write.join();
        assertEquals(0, version(read.join()));
        assertEquals(1, transport.requests.size());

        assertEquals(0, version(db.worksheet("B").select().fetch()));
        assertEquals(1, transport.requests.size(), "B was invalidated by a write to A");
        assertEquals(1, version(db.worksheet("A").select().fetch()));
    }

    @Test
    void rejectedCombinedCallLeavesTheCacheConsistentAfterTheResends() throws Exception {
        GridBase db = client();
        Worksheet a = db.worksheet("A");
        callbacks.releaseReversed(0);
        assertEquals(0, version(a.select().fetch()));          // cached at version 0

        var write = a.insert(Map.of("x", 1)).executeAsync();
        var missing = db.worksheet("Missing").select().fetchAsync();
        var projected = a.select("sheet", "v").fetchAsync();    // a different cache key than select()
        write.join();
        assertInstanceOf(ServerException.class, failureOf(missing));
        assertEquals(1, version(projected.join()));
        int calls = transport.requests.size();                 // 1 seed + combined + 3 alone
        assertEquals(5, calls);

        assertEquals(1, version(a.select().fetch()), "the pre-write entry survived the re-sent write");
        assertEquals(1, version(a.select("sheet", "v").fetch()));
        assertEquals(calls + 1, transport.requests.size(), "only the invalidated query goes to the network");
    }

    @Test
    void writeWhoseReplyIsLostInACombinedCallStillInvalidates() throws Exception {
        GridBase db = client();
        Worksheet a = db.worksheet("A");
        callbacks.releaseReversed(0);
        assertEquals(0, version(a.select().fetch()));

        engine.loseReplies = true;
        var write = a.insert(Map.of("x", 1)).executeAsync();
        var other = db.worksheet("B").select().fetchAsync();
        assertInstanceOf(TransportException.class, failureOf(write));
        assertInstanceOf(TransportException.class, failureOf(other));
        engine.loseReplies = false;

        assertEquals(1, version(a.select().fetch()), "the committed write's tab was not invalidated");
    }

    @Test
    void writeAndReadSplitAcrossCallsByMaxOperationsStayOrdered() throws Exception {
        GridBase db = client(1, new InMemoryResponseCache(), CacheStrategy.CACHE_FIRST);
        Worksheet a = db.worksheet("A");

        var write = a.insert(Map.of("x", 1)).executeAsync();
        var read = a.select().fetchAsync();
        callbacks.releaseReversed(2);
        write.join();
        assertEquals(1, version(read.join()));
        assertEquals(2, transport.requests.size());

        assertEquals(1, version(a.select().fetch()));
        assertEquals(2, transport.requests.size(), "the read from the second call was not cached");
    }

    @Test
    void forceRefreshThroughTheQueueReplacesTheCachedReply() throws Exception {
        GridBase db = client();
        Worksheet a = db.worksheet("A");
        callbacks.releaseReversed(0);
        assertEquals(0, version(a.select().fetch()));

        engine.bump("A");                                      // edited outside this client
        assertEquals(0, version(a.select().fetch()));          // still the cached reply
        assertEquals(1, version(a.select().request().forceRefresh().execute().rows()));
        int calls = transport.requests.size();
        assertEquals(1, version(a.select().fetch()));
        assertEquals(calls, transport.requests.size());
    }

    @Test
    void networkFirstFallsBackToTheCachedReplyThroughTheQueue() throws Exception {
        GridBase db = client(50, new InMemoryResponseCache(), CacheStrategy.NETWORK_FIRST);
        Worksheet a = db.worksheet("A");
        callbacks.releaseReversed(0);
        assertEquals(0, version(a.select().fetch()));

        engine.down = true;
        assertEquals(0, version(a.select().fetchAsync().join()), "no stale fallback through the queue");
        assertThrows(TransportException.class, () -> a.select().request().forceRefresh().execute());
    }

    @Test
    void aCacheThatThrowsUnexpectedlyFailsNoQueuedRequest() throws Exception {
        AtomicBoolean broken = new AtomicBoolean(true);
        InMemoryResponseCache inner = new InMemoryResponseCache();
        ResponseCache flaky = new ResponseCache() {
            @Override public Optional<Entry> get(String key) { return inner.get(key); }
            @Override public void put(String key, String reply, Instant cachedAt, Instant expiresAt,
                                      Collection<SheetRef> sheets) {
                if (broken.get()) {
                    throw new IllegalStateException("disk full");
                }
                inner.put(key, reply, cachedAt, expiresAt, sheets);
            }
            @Override public int invalidate(String spreadsheetId, String worksheet) {
                if (broken.get()) {
                    throw new IllegalStateException("disk full");
                }
                return inner.invalidate(spreadsheetId, worksheet);
            }
            @Override public int purgeExpired(Instant now) { return inner.purgeExpired(now); }
            @Override public void clear() { inner.clear(); }
            @Override public void close() { inner.close(); }
        };
        GridBase db = client(50, flaky, CacheStrategy.CACHE_FIRST);
        Worksheet a = db.worksheet("A");

        var write = a.insert(Map.of("x", 1)).executeAsync();
        var read = a.select().fetchAsync();
        var later = db.worksheet("B").select().fetchAsync();
        callbacks.releaseReversed(3);
        assertEquals(1, write.join().rows().get(0).getInteger("v"));
        assertEquals(1, version(read.join()));
        assertEquals(0, version(later.join()));
        assertEquals(1, transport.requests.size());

        broken.set(false);                                     // the queue worker survived the throws
        assertEquals(1, version(a.select().fetch()));
        assertEquals(1, version(a.select().fetch()));
        assertEquals(2, transport.requests.size());
    }
}
