package io.github.prasunmondal.hibernatesheets;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.prasunmondal.hibernatesheets.cache.CacheExpiry;
import io.github.prasunmondal.hibernatesheets.cache.CacheStrategy;
import io.github.prasunmondal.hibernatesheets.cache.SqliteResponseCache;
import io.github.prasunmondal.hibernatesheets.cache.SqliteResponseCache.SheetRef;
import io.github.prasunmondal.hibernatesheets.exception.HibernateSheetsException;
import io.github.prasunmondal.hibernatesheets.exception.ServerException;
import io.github.prasunmondal.hibernatesheets.exception.TransportException;
import io.github.prasunmondal.hibernatesheets.internal.Json;
import io.github.prasunmondal.hibernatesheets.internal.Log;
import io.github.prasunmondal.hibernatesheets.internal.RequestSerializer;
import io.github.prasunmondal.hibernatesheets.internal.ResponseParser;
import io.github.prasunmondal.hibernatesheets.mapping.Repository;
import io.github.prasunmondal.hibernatesheets.result.ExecutionResponse;
import io.github.prasunmondal.hibernatesheets.result.OperationResult;
import io.github.prasunmondal.hibernatesheets.spec.Operation;
import io.github.prasunmondal.hibernatesheets.spec.OperationType;
import io.github.prasunmondal.hibernatesheets.transport.HttpTransport;
import io.github.prasunmondal.hibernatesheets.transport.Transport;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Client for a deployed hibernate.sheets engine. Create one per engine deployment and share it:
 * instances are immutable and thread-safe.
 *
 * <pre>{@code
 * HibernateSheets db = HibernateSheets.builder()
 *         .endpoint("https://script.google.com/macros/s/<DEPLOYMENT_ID>/exec")
 *         .defaultSpreadsheetId("1C8rsAWa0Xfp...")
 *         .timeZone(ZoneId.of("Asia/Kolkata"))
 *         .build();
 *
 * List<Row> open = db.worksheet("Orders")
 *         .select()
 *         .where(Filters.eq("status", "OPEN"))
 *         .orderBy("createdAt", Sort.Direction.DESC)
 *         .limit(20)
 *         .fetch();
 * }</pre>
 */
public final class HibernateSheets {

    private static final Log LOG = Log.get(HibernateSheets.class);

    private static final ExecutorService ASYNC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hibernate-sheets-async");
        t.setDaemon(true);
        return t;
    });

    private final Transport transport;
    private final ObjectMapper mapper;
    private final ZoneId zone;
    private final String defaultSpreadsheetId;
    private final RetryPolicy retryPolicy;
    private final RequestSerializer serializer;
    private final ResponseParser parser;
    private final List<Consumer<NetworkCall>> preNetworkCallActions;
    private final List<Consumer<NetworkCallResult>> postNetworkCallActions;
    private final SqliteResponseCache cache;
    private final CacheStrategy cacheStrategy;
    private final CacheExpiry cacheExpiry;
    private final Clock clock;
    private final RequestQueue queue;

    private HibernateSheets(Builder b) {
        this.zone = b.zone;
        this.cache = b.cache;
        this.cacheStrategy = b.cacheStrategy;
        this.cacheExpiry = b.cacheExpiry;
        this.clock = b.clock;
        this.preNetworkCallActions = List.copyOf(b.preNetworkCallActions);
        this.postNetworkCallActions = List.copyOf(b.postNetworkCallActions);
        this.mapper = Json.mapper(b.objectMapper, b.zone);
        this.defaultSpreadsheetId = b.defaultSpreadsheetId;
        this.retryPolicy = b.retryPolicy;
        this.serializer = new RequestSerializer(mapper, zone);
        this.parser = new ResponseParser(mapper, zone);
        if (b.transport != null) {
            this.transport = b.transport;
        } else {
            if (b.endpoint == null) {
                throw new IllegalStateException("Either endpoint(...) or transport(...) is required");
            }
            this.transport = HttpTransport.builder(b.endpoint)
                    .connectTimeout(b.connectTimeout)
                    .requestTimeout(b.requestTimeout)
                    .accessToken(b.accessToken)
                    .build();
        }
        this.queue = b.queueWindow == null ? null
                : new RequestQueue(b.queueWindow, b.queueMaxOperations, this::sendCombined, ASYNC);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Worksheet in the default spreadsheet. */
    public Worksheet worksheet(String name) {
        if (defaultSpreadsheetId == null) {
            throw new IllegalStateException("No defaultSpreadsheetId configured; use worksheet(spreadsheetId, name)");
        }
        return new Worksheet(this, defaultSpreadsheetId, name);
    }

    public Worksheet worksheet(String spreadsheetId, String name) {
        return new Worksheet(this, spreadsheetId, name);
    }

    /** Object-style access for a class annotated with {@code @SheetTable}. */
    public <T> Repository<T> repository(Class<T> entityType) {
        return new Repository<>(this, entityType);
    }

    public Batch batch() {
        return new Batch(this);
    }

    /**
     * Low-level: sends operations as one request and returns the parsed response.
     * Applies the retry policy (reads only, unless configured otherwise). With a cache configured,
     * read-only requests go through it and writes invalidate the worksheets they touch.
     */
    public ExecutionResponse execute(List<Operation> operations) {
        return await(submit(operations, false, false));
    }

    /**
     * Like {@link #execute} but returns immediately. With a request queue configured, requests made
     * close together (from any thread) are sent as one HTTP call; callbacks run on a pool thread.
     */
    public CompletableFuture<ExecutionResponse> executeAsync(List<Operation> operations) {
        return submit(operations, true, false);
    }

    /** Reads skip the cache, go to the network, and replace the cached reply; no stale fallback. */
    ExecutionResponse executeRefreshing(List<Operation> operations) {
        return await(submit(operations, false, true));
    }

    CompletableFuture<ExecutionResponse> executeRefreshingAsync(List<Operation> operations) {
        return submit(operations, true, true);
    }

    /** Executes one operation asynchronously; see {@link #executeAsync}. */
    public <R extends OperationResult> CompletableFuture<R> executeOneAsync(Operation operation, Class<R> resultType) {
        return executeAsync(List.of(operation)).thenApply(r -> resultType.cast(r.results().get(0)));
    }

    /** The response cache, if one is configured. */
    public Optional<SqliteResponseCache> cache() {
        return Optional.ofNullable(cache);
    }

    private CompletableFuture<ExecutionResponse> submit(List<Operation> operations, boolean async, boolean refresh) {
        Objects.requireNonNull(operations, "operations");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("At least one operation is required");
        }
        List<Operation> ops = List.copyOf(operations);
        boolean readOnly = isReadOnly(ops);
        if (cache == null) {
            return fetch(ops, readOnly, async).thenApply(Reply::parsed);
        }
        if (!readOnly) {
            return fetch(ops, false, async)
                    .whenComplete((reply, failure) -> invalidateWrites(ops))
                    .thenApply(Reply::parsed);
        }
        CachedRead read = refresh ? refreshing(ops) : lookup(ops);
        if (read.hit()) {
            return now(() -> parser.parse(read.entry().orElseThrow().reply(), ops));
        }
        return fetch(ops, true, async).handle((reply, failure) -> finishRead(read, reply, failure));
    }

    /** A request for {@link #executeTogether}; {@code forceRefresh} as in {@link #executeRefreshing}. */
    record Planned(List<Operation> operations, boolean forceRefresh) {
    }

    /** One request's outcome when several are executed together: exactly one field is non-null. */
    record Outcome<T>(T value, RuntimeException failure) {

        static <T> Outcome<T> of(Supplier<T> work) {
            try {
                return new Outcome<>(work.get(), null);
            } catch (RuntimeException e) {
                return new Outcome<>(null, e);
            }
        }
    }

    /**
     * Executes several independent requests with as few HTTP calls as possible: fresh cache hits are
     * answered locally, the rest are combined (at most {@code maxOperationsPerCall} operations per
     * call, schema operations alone, order kept). Each request gets its own outcome.
     */
    List<Outcome<ExecutionResponse>> executeTogether(List<Planned> planned, int maxOperationsPerCall) {
        List<List<Operation>> requests = planned.stream().map(Planned::operations).toList();
        int n = requests.size();
        List<Outcome<ExecutionResponse>> outcomes = new ArrayList<>(Collections.nCopies(n, null));
        CachedRead[] reads = new CachedRead[n];
        List<Integer> toSend = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Operation> ops = requests.get(i);
            if (cache != null && isReadOnly(ops)) {
                CachedRead read = planned.get(i).forceRefresh() ? refreshing(ops) : lookup(ops);
                reads[i] = read;
                if (read.hit()) {
                    outcomes.set(i, Outcome.of(() -> parser.parse(read.entry().orElseThrow().reply(), ops)));
                    continue;
                }
            }
            toSend.add(i);
        }

        List<List<Integer>> calls = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int currentOps = 0;
        for (int i : toSend) {
            List<Operation> ops = requests.get(i);
            boolean alone = hasSchemaOperation(ops) || ops.size() >= maxOperationsPerCall;
            if (!current.isEmpty() && (alone || currentOps + ops.size() > maxOperationsPerCall)) {
                calls.add(current);
                current = new ArrayList<>();
                currentOps = 0;
            }
            if (alone) {
                calls.add(List.of(i));
            } else {
                current.add(i);
                currentOps += ops.size();
            }
        }
        if (!current.isEmpty()) {
            calls.add(current);
        }

        for (List<Integer> call : calls) {
            List<Outcome<Reply>> replies = sendCombined(call.stream().map(requests::get).toList());
            for (int k = 0; k < call.size(); k++) {
                int i = call.get(k);
                Outcome<Reply> reply = replies.get(k);
                List<Operation> ops = requests.get(i);
                if (cache != null && !isReadOnly(ops)) {
                    invalidateWrites(ops);
                }
                if (reads[i] != null) {
                    CachedRead read = reads[i];
                    outcomes.set(i, Outcome.of(() -> finishRead(read, reply.value(), reply.failure())));
                } else if (reply.failure() != null) {
                    outcomes.set(i, new Outcome<>(null, reply.failure()));
                } else {
                    outcomes.set(i, new Outcome<>(reply.value().parsed(), null));
                }
            }
        }
        return outcomes.stream()
                .map(o -> o.failure() == null ? o : new Outcome<ExecutionResponse>(null, rethrowable(o.failure())))
                .toList();
    }

    /**
     * Sends several requests as one HTTP call and splits the reply per request. If the engine rejects
     * the combined call it has written no row changes, so each request is re-sent alone and only the
     * faulty one fails. Transport failures fail them all.
     */
    List<Outcome<Reply>> sendCombined(List<List<Operation>> requests) {
        if (requests.size() == 1) {
            List<Operation> only = requests.get(0);
            return List.of(Outcome.of(() -> remote(only, isReadOnly(only))));
        }
        List<Operation> all = new ArrayList<>();
        requests.forEach(all::addAll);
        Reply combined;
        try {
            combined = remote(all, isReadOnly(all));
        } catch (ServerException e) {
            if (e.isRetryable()) {
                return requests.stream().map(r -> new Outcome<Reply>(null, e)).toList();
            }
            LOG.debug(() -> "hibernate.sheets combined call rejected ("
                    + e.getMessage() + "); re-sending " + requests.size() + " requests individually");
            return requests.stream().map(r -> Outcome.of(() -> remote(r, isReadOnly(r)))).toList();
        } catch (RuntimeException e) {
            return requests.stream().map(r -> new Outcome<Reply>(null, e)).toList();
        }
        List<Outcome<Reply>> out = new ArrayList<>();
        int offset = 0;
        for (List<Operation> r : requests) {
            int from = offset;
            out.add(Outcome.of(() -> slice(combined, from, r)));
            offset += r.size();
        }
        return out;
    }

    static boolean isReadOnly(List<Operation> operations) {
        return operations.stream().allMatch(op -> op.type().isReadOnly());
    }

    /** Applied by the engine immediately rather than at commit, so never re-sent as part of a retry-alone. */
    static boolean hasSchemaOperation(List<Operation> operations) {
        return operations.stream().anyMatch(op -> op.type() == OperationType.CREATE_WORKSHEET
                || op.type() == OperationType.CLEAR_WORKSHEET || op.type() == OperationType.ADD_COLUMNS);
    }

    private record CachedRead(List<Operation> operations, String key,
                              Optional<SqliteResponseCache.Entry> entry, boolean hit) {
    }

    private CachedRead lookup(List<Operation> operations) {
        String key = cacheKey(operations);
        Optional<SqliteResponseCache.Entry> read = quietly("read", () -> cache.get(key));
        Optional<SqliteResponseCache.Entry> entry = read != null ? read : Optional.empty();
        boolean hit = cacheStrategy == CacheStrategy.CACHE_FIRST
                && entry.isPresent() && entry.get().isFresh(clock.instant());
        if (hit) {
            LOG.debug(() -> "hibernate.sheets cache hit " + key);
        }
        return new CachedRead(operations, key, entry, hit);
    }

    /** No cache read and nothing to fall back to; the fresh reply is still stored. */
    private CachedRead refreshing(List<Operation> operations) {
        return new CachedRead(operations, cacheKey(operations), Optional.empty(), false);
    }

    /** Stores a successful read, or falls back to a stale entry (NETWORK_FIRST) when the network failed. */
    private ExecutionResponse finishRead(CachedRead read, Reply reply, Throwable failure) {
        if (failure == null) {
            Instant cachedAt = clock.instant();
            List<SheetRef> sheets = read.operations().stream()
                    .map(op -> new SheetRef(op.spreadsheetId(), op.worksheet())).distinct().toList();
            quietly("write", () -> {
                cache.put(read.key(), reply.body(), cachedAt, cacheExpiry.expiresAt(cachedAt), sheets);
                return null;
            });
            return reply.parsed();
        }
        Throwable cause = unwrap(failure);
        boolean networkFailure = cause instanceof TransportException
                || (cause instanceof HibernateSheetsException e && e.isRetryable());
        if (cacheStrategy == CacheStrategy.NETWORK_FIRST && networkFailure && read.entry().isPresent()) {
            SqliteResponseCache.Entry stale = read.entry().get();
            LOG.warning(() -> "hibernate.sheets request failed, serving cached reply from "
                    + stale.cachedAt() + ": " + cause.getMessage());
            return parser.parse(stale.reply(), read.operations());
        }
        throw rethrowable(cause);
    }

    /** Also on failure: a timed-out write may still have been committed by the engine. */
    private void invalidateWrites(List<Operation> operations) {
        operations.stream()
                .filter(op -> !op.type().isReadOnly())
                .map(op -> new SheetRef(op.spreadsheetId(), op.worksheet()))
                .distinct()
                .forEach(s -> quietly("invalidate", () -> cache.invalidate(s.spreadsheetId(), s.worksheet())));
    }

    private static RuntimeException rethrowable(Throwable t) {
        Throwable cause = unwrap(t);
        return cause instanceof RuntimeException r ? r : new CompletionException(cause);
    }

    /** Through the queue when there is one; otherwise on the caller's thread (sync) or a pool thread (async). */
    private CompletableFuture<Reply> fetch(List<Operation> operations, boolean readOnly, boolean async) {
        if (queue != null && queue.accepts(operations)) {
            return queue.submit(operations);
        }
        if (async) {
            return CompletableFuture.supplyAsync(() -> remote(operations, readOnly), ASYNC);
        }
        return now(() -> remote(operations, readOnly));
    }

    private Reply slice(Reply combined, int offset, List<Operation> operations) {
        String body = parser.slice(combined.body(), offset, operations.size());
        return new Reply(body, parser.parse(body, operations));
    }

    private static <T> CompletableFuture<T> now(Supplier<T> work) {
        try {
            return CompletableFuture.completedFuture(work.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** Waits and rethrows the original exception (e.g. {@code ServerException}), not a wrapper. */
    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new HibernateSheetsException(String.valueOf(cause.getMessage()), cause, false);
        }
    }

    private String cacheKey(List<Operation> operations) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(serializer.serialize("", operations).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Cache problems are logged and never fail the request; the network is the source of truth. */
    private <V> V quietly(String what, Supplier<V> work) {
        try {
            return work.get();
        } catch (HibernateSheetsException e) {
            LOG.warning(() -> "hibernate.sheets cache " + what + " failed: " + e.getMessage());
            return null;
        }
    }

    record Reply(String body, ExecutionResponse parsed) {
    }

    private Reply remote(List<Operation> operations, boolean readOnly) {
        String requestId = UUID.randomUUID().toString();
        String body = serializer.serialize(requestId, operations);

        for (int attempt = 1; ; attempt++) {
            try {
                LOG.debug(() -> "hibernate.sheets request " + requestId + ": " + body);
                String reply = send(new NetworkCall(requestId, body, attempt));
                LOG.trace(() -> "hibernate.sheets reply " + requestId + ": " + reply);
                return new Reply(reply, parser.parse(reply, operations));
            } catch (HibernateSheetsException e) {
                if (!retryPolicy.shouldRetry(e, attempt, readOnly)) {
                    throw e;
                }
                Duration delay = retryPolicy.delayAfter(attempt);
                int failedAttempt = attempt;
                LOG.warning(() -> "hibernate.sheets request " + requestId
                        + " failed (attempt " + failedAttempt + "), retrying in " + delay.toMillis() + " ms: "
                        + e.getMessage());
                sleep(delay);
            }
        }
    }

    /** Executes one operation and returns its result, typed. */
    public <R extends OperationResult> R executeOne(Operation operation, Class<R> resultType) {
        return resultType.cast(execute(List.of(operation)).results().get(0));
    }

    /** The mapper used for rows and entities (configured with the client's time zone). */
    public ObjectMapper objectMapper() {
        return mapper;
    }

    public ZoneId timeZone() {
        return zone;
    }

    public String defaultSpreadsheetId() {
        return defaultSpreadsheetId;
    }

    private String send(NetworkCall call) {
        preNetworkCallActions.forEach(action -> action.accept(call));
        long start = System.nanoTime();
        String reply = null;
        RuntimeException failure = null;
        try {
            reply = transport.send(call.requestBody());
            return reply;
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            NetworkCallResult result = new NetworkCallResult(call, reply, failure,
                    Duration.ofNanos(System.nanoTime() - start));
            postNetworkCallActions.forEach(action -> action.accept(result));
        }
    }

    private static void sleep(Duration d) {
        if (d.isZero() || d.isNegative()) {
            return;
        }
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new HibernateSheetsException("Interrupted while waiting to retry", ie, false);
        }
    }

    public static final class Builder {
        private URI endpoint;
        private Transport transport;
        private String defaultSpreadsheetId;
        private ZoneId zone = ZoneOffset.UTC;
        private ObjectMapper objectMapper;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(90);
        private Supplier<String> accessToken;
        private final List<Consumer<NetworkCall>> preNetworkCallActions = new ArrayList<>();
        private final List<Consumer<NetworkCallResult>> postNetworkCallActions = new ArrayList<>();
        private SqliteResponseCache cache;
        private CacheStrategy cacheStrategy;
        private CacheExpiry cacheExpiry;
        private Clock clock = Clock.systemUTC();
        private Duration queueWindow;
        private int queueMaxOperations;

        private Builder() {
        }

        /**
         * Queue requests for up to {@code window} and send everything queued as one HTTP call (at most
         * {@code maxOperations} operations per call). Off by default.
         */
        public Builder requestQueue(Duration window, int maxOperations) {
            this.queueWindow = Objects.requireNonNull(window, "window");
            this.queueMaxOperations = maxOperations;
            return this;
        }

        /** Cache read-only requests in {@code cache}; writes through this client invalidate affected worksheets. */
        public Builder cache(SqliteResponseCache cache, CacheStrategy strategy, CacheExpiry expiry) {
            this.cache = Objects.requireNonNull(cache, "cache");
            this.cacheStrategy = Objects.requireNonNull(strategy, "strategy");
            this.cacheExpiry = Objects.requireNonNull(expiry, "expiry");
            return this;
        }

        /** Clock used to decide cache freshness. Defaults to the system clock; mainly for tests. */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock);
            return this;
        }

        /** The web app's {@code /exec} URL. */
        public Builder endpoint(String url) {
            return endpoint(URI.create(url));
        }

        public Builder endpoint(URI url) {
            this.endpoint = Objects.requireNonNull(url);
            return this;
        }

        /** Replace the HTTP transport entirely (custom auth, testing). Timeouts/token settings are then ignored. */
        public Builder transport(Transport transport) {
            this.transport = Objects.requireNonNull(transport);
            return this;
        }

        public Builder defaultSpreadsheetId(String spreadsheetId) {
            this.defaultSpreadsheetId = spreadsheetId;
            return this;
        }

        /**
         * Time zone of the spreadsheet(s). Used to turn date cells into {@code LocalDate}/{@code LocalDateTime}
         * and to convert {@code LocalDate} filter values. Defaults to UTC.
         */
        public Builder timeZone(ZoneId zone) {
            this.zone = Objects.requireNonNull(zone);
            return this;
        }

        /** Base mapper for entity mapping (copied, then extended with java.time support). */
        public Builder objectMapper(ObjectMapper mapper) {
            this.objectMapper = mapper;
            return this;
        }

        public Builder retryPolicy(RetryPolicy policy) {
            this.retryPolicy = Objects.requireNonNull(policy);
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        /** OAuth access token supplier for deployments restricted to Google accounts. */
        public Builder accessToken(Supplier<String> tokenSupplier) {
            this.accessToken = tokenSupplier;
            return this;
        }

        /** Runs before every HTTP attempt (including retries), in registration order. */
        public Builder preNetworkCall(Consumer<NetworkCall> action) {
            this.preNetworkCallActions.add(Objects.requireNonNull(action));
            return this;
        }

        /** Runs after every HTTP attempt, whether it succeeded or failed, in registration order. */
        public Builder postNetworkCall(Consumer<NetworkCallResult> action) {
            this.postNetworkCallActions.add(Objects.requireNonNull(action));
            return this;
        }

        public HibernateSheets build() {
            return new HibernateSheets(this);
        }
    }
}
