package io.github.prasunmondal.hibernatesheets.cachingTests;

import io.github.prasunmondal.hibernatesheets.SheetProperties;
import io.github.prasunmondal.hibernatesheets.cache.CacheExpiry;
import io.github.prasunmondal.hibernatesheets.cache.CacheStrategy;
import io.github.prasunmondal.hibernatesheets.cache.ResponseCache;
import io.github.prasunmondal.hibernatesheets.exception.TransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Entries expire exactly when their {@link CacheExpiry} rule says, measured on the client's clock. */
class CacheExpiryBehaviourTest extends CachingTestBase {

    private static final CacheExpiry ONE_AM_OR_THREE_PM =
            CacheExpiry.dailyAt(IST, LocalTime.of(1, 0), LocalTime.of(15, 0));

    @Test
    @DisplayName("TTL: fresh until the last instant before the TTL, refetched exactly at the TTL")
    void ttlBoundary() {
        SheetProperties p = cached(b -> b.cacheExpiry(CacheExpiry.ttlMinutes(10)));

        allProducts(p);
        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        allProducts(p);
        assertCalls(1);

        clock.advance(Duration.ofSeconds(1));
        allProducts(p);
        assertCalls(2);
    }

    @Test
    @DisplayName("TTL: a refetch caches the new reply with a new TTL counted from the refetch")
    void refetchStartsNewTtl() {
        SheetProperties p = cached(b -> b.cacheExpiry(CacheExpiry.ttlMinutes(10)));

        allProducts(p);                                   // t = 0
        clock.advance(Duration.ofMinutes(10));
        allProducts(p);                                   // t = 10, expired → refetch
        clock.advance(Duration.ofMinutes(9));
        allProducts(p);                                   // t = 19, still fresh
        assertCalls(2);
        clock.advance(Duration.ofMinutes(1));
        allProducts(p);                                   // t = 20
        assertCalls(3);
    }

    @Test
    @DisplayName("default expiry is 10 minutes")
    void defaultExpiryIsTenMinutes() {
        SheetProperties p = cached();

        allProducts(p);
        clock.advance(Duration.ofMinutes(9));
        allProducts(p);
        assertCalls(1);
        clock.advance(Duration.ofMinutes(1));
        allProducts(p);
        assertCalls(2);
    }

    @Test
    @DisplayName("after expiry the refetch returns the current data, not the old cached data")
    void expiredEntryReturnsFreshData() {
        SheetProperties p = cached(b -> b.cacheExpiry(CacheExpiry.ttlMinutes(5)));

        assertEquals("Tea", nameOf(p, "P1"));
        renameDirectly("P1", "Green Tea");
        assertEquals("Tea", nameOf(p, "P1"));
        clock.advance(Duration.ofMinutes(5));
        assertEquals("Green Tea", nameOf(p, "P1"));
    }

    @Test
    @DisplayName("daily at 1:00 AM and 3:00 PM: cached at 10:00 → expires 15:00; cached at 15:00 → expires 01:00")
    void dailyOneAmAndThreePm() {
        SheetProperties p = cached(b -> b.cacheExpiry(ONE_AM_OR_THREE_PM));

        allProducts(p);                                           // 10:00
        clock.set(ist("2026-10-05T14:59:59"));
        allProducts(p);
        assertCalls(1);

        clock.set(ist("2026-10-05T15:00:00"));
        allProducts(p);                                           // refetched, next expiry 01:00
        assertCalls(2);

        clock.set(ist("2026-10-06T00:59:59"));
        allProducts(p);
        assertCalls(2);
        clock.set(ist("2026-10-06T01:00:00"));
        allProducts(p);
        assertCalls(3);
    }

    @Test
    @DisplayName("daily expiry crosses midnight: cached 23:30 with a 01:00 rule stays fresh until 01:00")
    void dailyExpiryAcrossMidnight() {
        clock.set(ist("2026-10-05T23:30:00"));
        SheetProperties p = cached(b -> b.cacheExpiry(CacheExpiry.dailyAt(IST, LocalTime.of(1, 0))));

        allProducts(p);
        clock.set(ist("2026-10-06T00:30:00"));
        allProducts(p);
        assertCalls(1);
        clock.set(ist("2026-10-06T01:00:00"));
        allProducts(p);
        assertCalls(2);
    }

    @Test
    @DisplayName("TTL or daily time: whichever comes first wins")
    void ttlOrDailyEarliestWins() {
        CacheExpiry rule = CacheExpiry.ttlMinutes(30).or(CacheExpiry.dailyAt(IST, LocalTime.of(15, 0)));
        SheetProperties p = cached(b -> b.cacheExpiry(rule));

        allProducts(p);                                           // 10:00 → TTL wins, expires 10:30
        clock.set(ist("2026-10-05T10:29:00"));
        allProducts(p);
        assertCalls(1);
        clock.set(ist("2026-10-05T10:30:00"));
        allProducts(p);
        assertCalls(2);

        clock.set(ist("2026-10-05T14:50:00"));
        allProducts(p);                                           // 14:50 → 15:00 beats 15:20
        assertCalls(3);
        clock.set(ist("2026-10-05T14:59:59"));
        allProducts(p);
        assertCalls(3);
        clock.set(ist("2026-10-05T15:00:00"));
        allProducts(p);
        assertCalls(4);
    }

    @Test
    @DisplayName("a custom expiry rule (any lambda) is honoured")
    void customRule() {
        SheetProperties p = cached(b -> b.cacheExpiry(cachedAt -> cachedAt.plusSeconds(5)));

        allProducts(p);
        clock.advance(Duration.ofSeconds(4));
        allProducts(p);
        assertCalls(1);
        clock.advance(Duration.ofSeconds(1));
        allProducts(p);
        assertCalls(2);
    }

    @Test
    @DisplayName("CACHE_FIRST never serves an expired entry: offline after expiry → the request fails")
    void cacheFirstDoesNotServeExpiredWhenOffline() {
        SheetProperties p = cached(b -> b.cacheExpiry(CacheExpiry.ttlMinutes(10)));

        allProducts(p);
        clock.advance(Duration.ofMinutes(11));
        engine.goOffline();
        assertThrows(TransportException.class, () -> allProducts(p));
    }

    @Test
    @DisplayName("NETWORK_FIRST serves an expired entry when the network is down, then refreshes once it is back")
    void networkFirstFallsBackToExpiredEntry() {
        SheetProperties p = cached(b -> b.cacheStrategy(CacheStrategy.NETWORK_FIRST)
                .cacheExpiry(CacheExpiry.ttlMinutes(10)));

        assertEquals("Tea", nameOf(p, "P1"));
        renameDirectly("P1", "Green Tea");
        clock.advance(Duration.ofDays(3));
        engine.goOffline();
        assertEquals("Tea", nameOf(p, "P1"));            // stale, but better than nothing

        engine.goOnline();
        assertEquals("Green Tea", nameOf(p, "P1"));
    }

    @Test
    @DisplayName("NETWORK_FIRST with nothing cached and the network down fails")
    void networkFirstWithoutEntryOfflineFails() {
        SheetProperties p = cached(b -> b.cacheStrategy(CacheStrategy.NETWORK_FIRST));
        engine.goOffline();
        assertThrows(TransportException.class, () -> allProducts(p));
    }

    @Test
    @DisplayName("purgeExpired removes only entries that have expired")
    void purgeExpiredRemovesOnlyExpired() {
        SheetProperties p = cached(b -> b.cacheExpiry(CacheExpiry.ttlMinutes(10)));
        ResponseCache cache = p.cache().orElseThrow();

        nameOf(p, "P1");                                          // t = 0, expires t = 10
        clock.advance(Duration.ofMinutes(8));
        nameOf(p, "P2");                                          // t = 8, expires t = 18
        clock.advance(Duration.ofMinutes(4));                     // t = 12

        assertEquals(1, cache.purgeExpired(clock.instant()));
        nameOf(p, "P2");
        assertCalls(2);
        nameOf(p, "P1");
        assertCalls(3);
    }

    @Test
    @DisplayName("purged entries can no longer serve as the NETWORK_FIRST offline fallback")
    void purgedEntryIsNoFallback() {
        SheetProperties p = cached(b -> b.cacheStrategy(CacheStrategy.NETWORK_FIRST)
                .cacheExpiry(CacheExpiry.ttlMinutes(10)));

        allProducts(p);
        clock.advance(Duration.ofMinutes(30));
        p.cache().orElseThrow().purgeExpired(clock.instant());
        engine.goOffline();
        assertThrows(TransportException.class, () -> allProducts(p));
    }
}
