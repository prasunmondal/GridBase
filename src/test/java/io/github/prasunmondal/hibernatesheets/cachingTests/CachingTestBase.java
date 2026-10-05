package io.github.prasunmondal.hibernatesheets.cachingTests;

import io.github.prasunmondal.hibernatesheets.RetryPolicy;
import io.github.prasunmondal.hibernatesheets.SheetProperties;
import io.github.prasunmondal.hibernatesheets.cache.SqliteResponseCache;
import io.github.prasunmondal.hibernatesheets.mapping.Repository;
import io.github.prasunmondal.hibernatesheets.result.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static io.github.prasunmondal.hibernatesheets.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared fixture: a seeded {@link FakeSheetsEngine}, a controllable clock and helpers that build
 * {@link SheetProperties} the way an application would.
 *
 * <p>Seed data — spreadsheet {@code SS}: {@code Products} (P1..P5) and {@code Orders} (O1..O3);
 * spreadsheet {@code SS2}: a different {@code Products} tab (X1). The clock starts at
 * 2026-10-05 10:00 Asia/Kolkata.</p>
 */
abstract class CachingTestBase {

    static final String SHEET = "SS";
    static final String OTHER_SHEET = "SS2";
    static final String PRODUCTS = "Products";
    static final String ORDERS = "Orders";
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final List<String> PRODUCT_COLUMNS = List.of("sku", "name", "stock", "price", "active", "addedOn", "notes");

    @TempDir
    Path dir;

    FakeSheetsEngine engine;
    MutableClock clock;
    private final List<SheetProperties> created = new ArrayList<>();

    @BeforeEach
    void seed() {
        engine = new FakeSheetsEngine()
                .sheet(SHEET, PRODUCTS, PRODUCT_COLUMNS, List.of(
                        row("sku", "P1", "name", "Tea", "stock", 40, "price", 4.5, "active", true,
                                "addedOn", "2026-01-14T18:30:00.000Z", "notes", ""),
                        row("sku", "P2", "name", "Coffee", "stock", 12, "price", 9.99, "active", true,
                                "addedOn", "2026-02-01T18:30:00.000Z", "notes", "decaf"),
                        row("sku", "P3", "name", "Sugar", "stock", 0, "price", 1.2, "active", false,
                                "addedOn", "2026-03-10T18:30:00.000Z", "notes", ""),
                        row("sku", "P4", "name", "Milk", "stock", 25, "price", 2.25, "active", true,
                                "addedOn", "2026-04-05T18:30:00.000Z", "notes", "chilled"),
                        row("sku", "P5", "name", "Honey", "stock", 7, "price", 6.5, "active", true,
                                "addedOn", "2026-05-20T18:30:00.000Z", "notes", "")))
                .sheet(SHEET, ORDERS, List.of("orderId", "sku", "qty", "status"), List.of(
                        row("orderId", "O1", "sku", "P1", "qty", 2, "status", "OPEN"),
                        row("orderId", "O2", "sku", "P2", "qty", 1, "status", "SHIPPED"),
                        row("orderId", "O3", "sku", "P4", "qty", 5, "status", "OPEN")))
                .sheet(OTHER_SHEET, PRODUCTS, PRODUCT_COLUMNS, List.of(
                        row("sku", "X1", "name", "Other Tea", "stock", 1, "price", 1, "active", true,
                                "addedOn", "", "notes", "")));
        clock = new MutableClock(ist("2026-10-05T10:00:00"));
    }

    @AfterEach
    void closeCaches() {
        for (SheetProperties p : created) {
            try {
                p.cache().ifPresent(SqliteResponseCache::close);
            } catch (RuntimeException ignored) {
                // already closed by the test
            }
        }
    }

    /** Properties as an application would configure them, caching off unless the test turns it on. */
    SheetProperties.Builder base() {
        return SheetProperties.builder()
                .transport(engine)
                .dbSheetUrl(SHEET)
                .tabName(PRODUCTS)
                .timeZone(IST)
                .retryPolicy(RetryPolicy.none())
                .clock(clock)
                .cacheFile(dir.resolve("cache.db"));
    }

    SheetProperties cached() {
        return track(base().shallCache(true).build());
    }

    SheetProperties cached(UnaryOperator<SheetProperties.Builder> customize) {
        return track(customize.apply(base().shallCache(true)).build());
    }

    SheetProperties track(SheetProperties properties) {
        created.add(properties);
        return properties;
    }

    // ------------------------------------------------------------------ reads used across tests

    static List<Row> allProducts(SheetProperties p) {
        return p.worksheet().select().orderBy("sku").fetch();
    }

    static String nameOf(SheetProperties p, String sku) {
        return p.worksheet().select().where(eq("sku", sku)).fetchFirst().orElseThrow().getString("name");
    }

    static Repository<Product> products(SheetProperties p) {
        return p.repository(Product.class);
    }

    void assertCalls(int expected) {
        assertEquals(expected, engine.calls(), "network calls made so far");
    }

    void renameDirectly(String sku, String name) {
        engine.editDirectly(SHEET, PRODUCTS, "sku", sku, "name", name);
    }

    static Instant ist(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(IST).toInstant();
    }

    static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
