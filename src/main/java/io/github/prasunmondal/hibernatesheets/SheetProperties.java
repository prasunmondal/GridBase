package io.github.prasunmondal.hibernatesheets;

import io.github.prasunmondal.hibernatesheets.cache.CacheExpiry;
import io.github.prasunmondal.hibernatesheets.cache.CacheStrategy;
import io.github.prasunmondal.hibernatesheets.cache.SqliteResponseCache;
import io.github.prasunmondal.hibernatesheets.mapping.Repository;
import io.github.prasunmondal.hibernatesheets.transport.Transport;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything needed to talk to one worksheet: engine script URL, spreadsheet, tab, time zone, retry,
 * timeouts, auth and network hooks. An entity class typically declares one as a constant and builds
 * its repository from it:
 *
 * <pre>{@code
 * public class Customer {
 *     public static final SheetProperties PROPERTIES = SheetProperties.builder()
 *             .scriptUrl("https://script.google.com/macros/s/<DEPLOYMENT_ID>/exec")
 *             .dbSheetUrl("https://docs.google.com/spreadsheets/d/<SPREADSHEET_ID>/edit")
 *             .tabName("Customers")
 *             .retryPolicy(RetryPolicy.defaults())
 *             .preNetworkCall(call -> log.debug("-> {}", call.requestId()))
 *             .postNetworkCall(result -> metrics.record(result.elapsed()))
 *             .build();
 *
 *     public static Repository<Customer> repository() {
 *         return PROPERTIES.repository(Customer.class);
 *     }
 *     ...
 * }
 * }</pre>
 *
 * <p>Immutable and thread-safe. The underlying {@link HibernateSheets} client is created on first use
 * and shared by everything built from this instance.</p>
 */
public final class SheetProperties {

    private static final Pattern SPREADSHEET_URL = Pattern.compile("/spreadsheets/d/([a-zA-Z0-9_-]+)");

    private final String scriptUrl;
    private final Transport transport;
    private final String spreadsheetId;
    private final String tabName;
    private final ZoneId timeZone;
    private final RetryPolicy retryPolicy;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Supplier<String> accessToken;
    private final List<Consumer<NetworkCall>> preNetworkCallActions;
    private final List<Consumer<NetworkCallResult>> postNetworkCallActions;
    private final boolean shallCache;
    private final CacheStrategy cacheStrategy;
    private final CacheExpiry cacheExpiry;
    private final Path cacheFile;

    private volatile HibernateSheets client;

    private SheetProperties(Builder b) {
        if (b.scriptUrl == null && b.transport == null) {
            throw new IllegalStateException("scriptUrl(...) is required");
        }
        if (b.spreadsheetId == null) {
            throw new IllegalStateException("dbSheetUrl(...) is required");
        }
        this.scriptUrl = b.scriptUrl;
        this.transport = b.transport;
        this.spreadsheetId = b.spreadsheetId;
        this.tabName = b.tabName;
        this.timeZone = b.timeZone;
        this.retryPolicy = b.retryPolicy;
        this.connectTimeout = b.connectTimeout;
        this.requestTimeout = b.requestTimeout;
        this.accessToken = b.accessToken;
        this.preNetworkCallActions = List.copyOf(b.preNetworkCallActions);
        this.postNetworkCallActions = List.copyOf(b.postNetworkCallActions);
        this.shallCache = b.shallCache;
        this.cacheStrategy = b.cacheStrategy;
        this.cacheExpiry = b.cacheExpiry;
        this.cacheFile = b.cacheFile;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-filled with these properties, e.g. to derive per-entity properties from a shared base. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.scriptUrl = scriptUrl;
        b.transport = transport;
        b.spreadsheetId = spreadsheetId;
        b.tabName = tabName;
        b.timeZone = timeZone;
        b.retryPolicy = retryPolicy;
        b.connectTimeout = connectTimeout;
        b.requestTimeout = requestTimeout;
        b.accessToken = accessToken;
        b.preNetworkCallActions.addAll(preNetworkCallActions);
        b.postNetworkCallActions.addAll(postNetworkCallActions);
        b.shallCache = shallCache;
        b.cacheStrategy = cacheStrategy;
        b.cacheExpiry = cacheExpiry;
        b.cacheFile = cacheFile;
        return b;
    }

    /** The client configured from these properties (created once, then reused). */
    public HibernateSheets client() {
        HibernateSheets c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    client = c = buildClient();
                }
            }
        }
        return c;
    }

    /** The configured tab of the configured spreadsheet. */
    public Worksheet worksheet() {
        if (tabName == null) {
            throw new IllegalStateException("No tabName configured");
        }
        return client().worksheet(spreadsheetId, tabName);
    }

    /** Object-style access; the tab is {@link #tabName()} if set, otherwise taken from the entity class. */
    public <T> Repository<T> repository(Class<T> entityType) {
        return new Repository<>(this, entityType);
    }

    public String scriptUrl() {
        return scriptUrl;
    }

    public String spreadsheetId() {
        return spreadsheetId;
    }

    /** {@code null} when the tab name comes from the entity class instead. */
    public String tabName() {
        return tabName;
    }

    public ZoneId timeZone() {
        return timeZone;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public List<Consumer<NetworkCall>> preNetworkCallActions() {
        return preNetworkCallActions;
    }

    public List<Consumer<NetworkCallResult>> postNetworkCallActions() {
        return postNetworkCallActions;
    }

    public boolean shallCache() {
        return shallCache;
    }

    public CacheStrategy cacheStrategy() {
        return cacheStrategy;
    }

    public CacheExpiry cacheExpiry() {
        return cacheExpiry;
    }

    public Path cacheFile() {
        return cacheFile;
    }

    /** The response cache when {@link #shallCache()} is on, e.g. to {@code invalidate} after external edits. */
    public Optional<SqliteResponseCache> cache() {
        return client().cache();
    }

    private HibernateSheets buildClient() {
        HibernateSheets.Builder b = HibernateSheets.builder()
                .defaultSpreadsheetId(spreadsheetId)
                .timeZone(timeZone)
                .retryPolicy(retryPolicy)
                .connectTimeout(connectTimeout)
                .requestTimeout(requestTimeout)
                .accessToken(accessToken);
        if (transport != null) {
            b.transport(transport);
        } else {
            b.endpoint(scriptUrl);
        }
        preNetworkCallActions.forEach(b::preNetworkCall);
        postNetworkCallActions.forEach(b::postNetworkCall);
        if (shallCache) {
            b.cache(SqliteResponseCache.open(cacheFile), cacheStrategy, cacheExpiry);
        }
        return b.build();
    }

    static String spreadsheetIdOf(String urlOrId) {
        Matcher m = SPREADSHEET_URL.matcher(urlOrId);
        return m.find() ? m.group(1) : urlOrId.trim();
    }

    public static final class Builder {
        private String scriptUrl;
        private Transport transport;
        private String spreadsheetId;
        private String tabName;
        private ZoneId timeZone = ZoneOffset.UTC;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(90);
        private Supplier<String> accessToken;
        private final List<Consumer<NetworkCall>> preNetworkCallActions = new ArrayList<>();
        private final List<Consumer<NetworkCallResult>> postNetworkCallActions = new ArrayList<>();
        private boolean shallCache;
        private CacheStrategy cacheStrategy = CacheStrategy.CACHE_FIRST;
        private CacheExpiry cacheExpiry = CacheExpiry.ttlMinutes(10);
        private Path cacheFile = SqliteResponseCache.defaultFile();

        private Builder() {
        }

        /**
         * Cache replies to reads (SELECT, GET_COLUMNS) in SQLite and reuse them. Writes through any
         * client sharing the cache file invalidate the worksheets they touch. Off by default.
         */
        public Builder shallCache(boolean shallCache) {
            this.shallCache = shallCache;
            return this;
        }

        /** Defaults to {@link CacheStrategy#CACHE_FIRST}. */
        public Builder cacheStrategy(CacheStrategy strategy) {
            this.cacheStrategy = Objects.requireNonNull(strategy);
            return this;
        }

        /**
         * When cached replies go stale, e.g. {@code CacheExpiry.ttlMinutes(30)} or
         * {@code CacheExpiry.dailyAt(LocalTime.of(1, 0), LocalTime.of(15, 0))}, combinable with {@code or}.
         * Defaults to 10 minutes.
         */
        public Builder cacheExpiry(CacheExpiry expiry) {
            this.cacheExpiry = Objects.requireNonNull(expiry);
            return this;
        }

        /** SQLite database file. Defaults to {@code ~/.hibernate-sheets/cache.db}. */
        public Builder cacheFile(Path file) {
            this.cacheFile = Objects.requireNonNull(file);
            return this;
        }

        /** The engine web app's {@code /exec} URL. */
        public Builder scriptUrl(String url) {
            this.scriptUrl = Objects.requireNonNull(url);
            return this;
        }

        /** Replace the HTTP transport (custom auth, testing); {@link #scriptUrl} and timeouts are then unused. */
        public Builder transport(Transport transport) {
            this.transport = Objects.requireNonNull(transport);
            return this;
        }

        /** The spreadsheet: its full URL ({@code https://docs.google.com/spreadsheets/d/<id>/...}) or bare id. */
        public Builder dbSheetUrl(String urlOrId) {
            this.spreadsheetId = spreadsheetIdOf(Objects.requireNonNull(urlOrId));
            return this;
        }

        /** Worksheet (tab) name. If unset, the entity's {@code @SheetTable} or simple class name is used. */
        public Builder tabName(String tabName) {
            this.tabName = tabName;
            return this;
        }

        /** Time zone of the spreadsheet, used for date conversion. Defaults to UTC. */
        public Builder timeZone(ZoneId zone) {
            this.timeZone = Objects.requireNonNull(zone);
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

        public SheetProperties build() {
            return new SheetProperties(this);
        }
    }
}
