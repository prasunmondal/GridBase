package io.github.prasunmondal.hibernatesheets.cache;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * When a cached reply stops being fresh. Combine rules with {@link #or}; the earliest one wins:
 *
 * <pre>{@code
 * CacheExpiry.ttlMinutes(30)                                          // 30 minutes after caching
 * CacheExpiry.dailyAt(LocalTime.of(1, 0), LocalTime.of(15, 0))        // next 1:00 AM or 3:00 PM
 * CacheExpiry.ttlMinutes(30).or(CacheExpiry.dailyAt(LocalTime.of(1, 0)))
 * }</pre>
 */
@FunctionalInterface
public interface CacheExpiry {

    /** The instant a reply cached at {@code cachedAt} expires. */
    Instant expiresAt(Instant cachedAt);

    static CacheExpiry ttl(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return cachedAt -> cachedAt.plus(ttl);
    }

    static CacheExpiry ttlMinutes(long minutes) {
        return ttl(Duration.ofMinutes(minutes));
    }

    /** Expires at the next occurrence of any of {@code times} on the system default time zone's clock. */
    static CacheExpiry dailyAt(LocalTime... times) {
        return dailyAt(ZoneId.systemDefault(), times);
    }

    /** Expires at the next occurrence (strictly after caching) of any of {@code times} in {@code zone}. */
    static CacheExpiry dailyAt(ZoneId zone, LocalTime... times) {
        Objects.requireNonNull(zone, "zone");
        List<LocalTime> at = List.of(times);
        if (at.isEmpty()) {
            throw new IllegalArgumentException("At least one time is required");
        }
        return cachedAt -> {
            ZonedDateTime now = cachedAt.atZone(zone);
            Instant earliest = null;
            for (LocalTime t : at) {
                ZonedDateTime next = ZonedDateTime.of(now.toLocalDate(), t, zone);
                if (!next.toInstant().isAfter(cachedAt)) {
                    next = ZonedDateTime.of(now.toLocalDate().plusDays(1), t, zone);
                }
                if (earliest == null || next.toInstant().isBefore(earliest)) {
                    earliest = next.toInstant();
                }
            }
            return earliest;
        };
    }

    /** Expires at whichever of this rule and {@code other} comes first. */
    default CacheExpiry or(CacheExpiry other) {
        Objects.requireNonNull(other, "other");
        return cachedAt -> {
            Instant a = expiresAt(cachedAt);
            Instant b = other.expiresAt(cachedAt);
            return a.isBefore(b) ? a : b;
        };
    }
}
