package io.github.prasunmondal.gsheetdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.prasunmondal.gsheetdb.exception.GSheetDBException;
import io.github.prasunmondal.gsheetdb.internal.Json;
import io.github.prasunmondal.gsheetdb.internal.RequestSerializer;
import io.github.prasunmondal.gsheetdb.internal.ResponseParser;
import io.github.prasunmondal.gsheetdb.mapping.Repository;
import io.github.prasunmondal.gsheetdb.result.ExecutionResponse;
import io.github.prasunmondal.gsheetdb.result.OperationResult;
import io.github.prasunmondal.gsheetdb.spec.Operation;
import io.github.prasunmondal.gsheetdb.transport.HttpTransport;
import io.github.prasunmondal.gsheetdb.transport.Transport;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client for a deployed hibernate.sheets engine. Create one per engine deployment and share it:
 * instances are immutable and thread-safe.
 *
 * <pre>{@code
 * GSheetDB db = GSheetDB.builder()
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
public final class SheetDB {

    private static final System.Logger LOG = System.getLogger(SheetDB.class.getName());

    private final Transport transport;
    private final ObjectMapper mapper;
    private final ZoneId zone;
    private final String defaultSpreadsheetId;
    private final RetryPolicy retryPolicy;
    private final RequestSerializer serializer;
    private final ResponseParser parser;

    private SheetDB(Builder b) {
        this.zone = b.zone;
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
     * Applies the retry policy (reads only, unless configured otherwise).
     */
    public ExecutionResponse execute(List<Operation> operations) {
        Objects.requireNonNull(operations, "operations");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("At least one operation is required");
        }
        boolean readOnly = operations.stream().allMatch(op -> op.type().isReadOnly());
        String requestId = UUID.randomUUID().toString();
        String body = serializer.serialize(requestId, operations);

        for (int attempt = 1; ; attempt++) {
            try {
                LOG.log(System.Logger.Level.DEBUG, () -> "hibernate.sheets request " + requestId + ": " + body);
                String reply = transport.send(body);
                LOG.log(System.Logger.Level.TRACE, () -> "hibernate.sheets reply " + requestId + ": " + reply);
                return parser.parse(reply, operations);
            } catch (GSheetDBException e) {
                if (!retryPolicy.shouldRetry(e, attempt, readOnly)) {
                    throw e;
                }
                Duration delay = retryPolicy.delayAfter(attempt);
                int failedAttempt = attempt;
                LOG.log(System.Logger.Level.WARNING, () -> "hibernate.sheets request " + requestId
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

    private static void sleep(Duration d) {
        if (d.isZero() || d.isNegative()) {
            return;
        }
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new GSheetDBException("Interrupted while waiting to retry", ie, false);
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

        private Builder() {
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

        public SheetDB build() {
            return new SheetDB(this);
        }
    }
}
