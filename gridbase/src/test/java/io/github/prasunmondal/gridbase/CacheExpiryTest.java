package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.cache.CacheExpiry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CacheExpiryTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final CacheExpiry ONE_AM_OR_THREE_PM = CacheExpiry.dailyAt(IST, LocalTime.of(1, 0), LocalTime.of(15, 0));

    private static Instant ist(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(IST).toInstant();
    }

    @Test
    void ttlMinutes() {
        assertEquals(ist("2026-10-01T10:30"), CacheExpiry.ttlMinutes(30).expiresAt(ist("2026-10-01T10:00")));
    }

    @Test
    void dailyAtPicksNextOccurrence() {
        assertEquals(ist("2026-10-01T15:00"), ONE_AM_OR_THREE_PM.expiresAt(ist("2026-10-01T10:00")));
        assertEquals(ist("2026-10-02T01:00"), ONE_AM_OR_THREE_PM.expiresAt(ist("2026-10-01T16:00")));
        assertEquals(ist("2026-10-01T01:00"), ONE_AM_OR_THREE_PM.expiresAt(ist("2026-10-01T00:10")));
    }

    @Test
    void dailyAtExactlyOnTheBoundaryMovesToTheNextOne() {
        assertEquals(ist("2026-10-02T01:00"), ONE_AM_OR_THREE_PM.expiresAt(ist("2026-10-01T15:00")));
    }

    @Test
    void orTakesTheEarliest() {
        CacheExpiry rule = CacheExpiry.ttlMinutes(30).or(ONE_AM_OR_THREE_PM);
        assertEquals(ist("2026-10-01T10:30"), rule.expiresAt(ist("2026-10-01T10:00")));
        assertEquals(ist("2026-10-01T15:00"), rule.expiresAt(ist("2026-10-01T14:50")));
    }

    @Test
    void rejectsBadRules() {
        assertThrows(IllegalArgumentException.class, () -> CacheExpiry.ttlMinutes(0));
        assertThrows(IllegalArgumentException.class, () -> CacheExpiry.dailyAt(IST));
    }
}
