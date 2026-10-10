package io.github.prasunmondal.hibernatesheets;

import io.github.prasunmondal.hibernatesheets.cache.CacheExpiry;
import io.github.prasunmondal.hibernatesheets.cache.CacheStrategy;
import io.github.prasunmondal.hibernatesheets.cache.SqliteResponseCache;
import io.github.prasunmondal.hibernatesheets.exception.ServerException;
import io.github.prasunmondal.hibernatesheets.exception.TransportException;
import io.github.prasunmondal.hibernatesheets.result.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.prasunmondal.hibernatesheets.query.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseCacheTest {

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T04:30:00Z"));
    private final List<SqliteResponseCache> opened = new ArrayList<>();
    private boolean networkDown;
    private int rowsVersion = 1;
    private final FakeTransport transport = new FakeTransport(req -> {
        if (networkDown) {
            throw new TransportException("connection refused", 0, null, true);
        }
        StringBuilder sb = new StringBuilder("{\"success\":true,\"requestId\":\"r\",\"results\":[");
        for (int i = 0; i < req.path("operations").size(); i++) {
            sb.append(i == 0 ? "" : ",").append("{\"operationId\":\"op-").append(i + 1)
                    .append("\",\"rowCount\":1,\"rows\":[{\"id\":\"1\",\"v\":").append(rowsVersion).append("}]}");
        }
        return sb.append("]}").toString();
    });

    @AfterEach
    void closeCaches() {
        opened.forEach(SqliteResponseCache::close);
    }

    private HibernateSheets client(CacheStrategy strategy, CacheExpiry expiry) {
        SqliteResponseCache cache = SqliteResponseCache.open(dir.resolve("cache.db"));
        opened.add(cache);
        return HibernateSheets.builder()
                .transport(transport)
                .defaultSpreadsheetId("S")
                .retryPolicy(RetryPolicy.none())
                .cache(cache, strategy, expiry)
                .clock(clock)
                .build();
    }

    private static int version(List<Row> rows) {
        return rows.get(0).getInteger("v");
    }

    @Test
    void cacheFirstServesRepeatedReadsWithoutNetwork() {
        HibernateSheets db = client(CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10));
        assertEquals(1, version(db.worksheet("Emp").select().fetch()));
        rowsVersion = 2;
        assertEquals(1, version(db.worksheet("Emp").select().fetch()));
        assertEquals(1, transport.requests.size());
    }

    @Test
    void differentQueriesAreCachedSeparately() {
        HibernateSheets db = client(CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10));
        db.worksheet("Emp").select().where(eq("id", "1")).fetch();
        db.worksheet("Emp").select().where(eq("id", "2")).fetch();
        db.worksheet("Emp").select().where(eq("id", "1")).fetch();
        assertEquals(2, transport.requests.size());
    }

    @Test
    void expiredEntriesAreRefetched() {
        HibernateSheets db = client(CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10));
        db.worksheet("Emp").select().fetch();
        clock.advance(Duration.ofMinutes(9));
        db.worksheet("Emp").select().fetch();
        assertEquals(1, transport.requests.size());

        rowsVersion = 2;
        clock.advance(Duration.ofMinutes(1));
        assertEquals(2, version(db.worksheet("Emp").select().fetch()));
        assertEquals(2, transport.requests.size());
    }

    @Test
    void dailyExpiryRule() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        HibernateSheets db = client(CacheStrategy.CACHE_FIRST,
                CacheExpiry.dailyAt(ist, java.time.LocalTime.of(15, 0)));
        db.worksheet("Emp").select().fetch();                  // 10:00 IST
        clock.set(Instant.parse("2026-10-01T09:29:59Z"));      // 14:59:59 IST
        db.worksheet("Emp").select().fetch();
        assertEquals(1, transport.requests.size());
        clock.set(Instant.parse("2026-10-01T09:30:00Z"));      // 15:00 IST
        db.worksheet("Emp").select().fetch();
        assertEquals(2, transport.requests.size());
    }

    @Test
    void writesInvalidateOnlyTheirWorksheet() {
        HibernateSheets db = client(CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10));
        db.worksheet("Emp").select().fetch();
        db.worksheet("Dept").select().fetch();
        db.worksheet("Emp").insert(Map.of("id", "2")).execute();
        assertEquals(3, transport.requests.size());

        db.worksheet("Emp").select().fetch();
        db.worksheet("Dept").select().fetch();
        assertEquals(4, transport.requests.size());
        assertEquals("Emp", transport.lastOperation().path("worksheet").asText());
    }

    @Test
    void networkFirstRefreshesAndFallsBackWhenOffline() {
        HibernateSheets db = client(CacheStrategy.NETWORK_FIRST, CacheExpiry.ttlMinutes(10));
        db.worksheet("Emp").select().fetch();
        rowsVersion = 2;
        assertEquals(2, version(db.worksheet("Emp").select().fetch()));
        assertEquals(2, transport.requests.size());

        networkDown = true;
        clock.advance(Duration.ofDays(1));
        assertEquals(2, version(db.worksheet("Emp").select().fetch()));
        assertThrows(TransportException.class, () -> db.worksheet("Other").select().fetch());
    }

    @Test
    void networkFirstDoesNotMaskEngineErrors() {
        boolean[] engineError = {false};
        FakeTransport t = new FakeTransport(req -> engineError[0]
                ? "{\"success\":false,\"errors\":[{\"message\":\"Column not found: v\"}]}"
                : transport.send(req.toString()));
        HibernateSheets db = HibernateSheets.builder()
                .transport(t)
                .defaultSpreadsheetId("S")
                .cache(track(SqliteResponseCache.open(dir.resolve("err.db"))), CacheStrategy.NETWORK_FIRST,
                        CacheExpiry.ttlMinutes(10))
                .build();
        db.worksheet("Emp").select().fetch();
        engineError[0] = true;
        assertThrows(ServerException.class, () -> db.worksheet("Emp").select().fetch());
    }

    @Test
    void cachePersistsAcrossConnections() {
        client(CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10)).worksheet("Emp").select().fetch();
        opened.remove(0).close();

        rowsVersion = 2;
        assertEquals(1, version(client(CacheStrategy.CACHE_FIRST, CacheExpiry.ttlMinutes(10))
                .worksheet("Emp").select().fetch()));
        assertEquals(1, transport.requests.size());
    }

    @Test
    void sheetPropertiesWiresTheCache() {
        SheetProperties props = SheetProperties.builder()
                .transport(transport)
                .dbSheetUrl("S")
                .tabName("Emp")
                .shallCache(true)
                .cacheExpiry(CacheExpiry.ttlMinutes(5))
                .cacheFile(dir.resolve("props.db"))
                .build();
        track((SqliteResponseCache) props.cache().orElseThrow());
        props.worksheet().select().fetch();
        props.worksheet().select().fetch();
        assertEquals(1, transport.requests.size());
        assertTrue(SheetProperties.builder().transport(transport).dbSheetUrl("S").build().cache().isEmpty());
    }

    private SqliteResponseCache track(SqliteResponseCache cache) {
        if (!opened.contains(cache)) {
            opened.add(cache);
        }
        return cache;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

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
