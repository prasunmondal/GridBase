package io.github.prasunmondal.gridbase.cache;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * Store for engine replies to read-only requests. Each entry is tagged with the worksheets it read, so
 * a write through any client sharing the store invalidates it. Writes made elsewhere (the Sheets UI,
 * other clients without this store) are only picked up when entries expire, or after
 * {@link #invalidate} / {@link #clear}.
 *
 * <p>Built-in implementations, chosen with {@link CacheBackend}: {@link SqliteResponseCache},
 * {@link JournalResponseCache} (pure Java, no native code — the Android default) and
 * {@link InMemoryResponseCache}. Implement this interface to plug in anything else (e.g. Room).
 * Implementations must be thread-safe and should throw
 * {@link io.github.prasunmondal.gridbase.exception.HibernateSheetsException} on I/O problems;
 * the client logs those and goes to the network instead.</p>
 */
public interface ResponseCache extends AutoCloseable {

    /** A cached reply and when it stops being fresh. */
    record Entry(String reply, Instant cachedAt, Instant expiresAt) {
        public boolean isFresh(Instant now) {
            return expiresAt.isAfter(now);
        }
    }

    /** A worksheet an entry was read from. */
    record SheetRef(String spreadsheetId, String worksheet) {
    }

    Optional<Entry> get(String key);

    /** Stores (or replaces) the reply for {@code key}, tagged with the worksheets it read. */
    void put(String key, String reply, Instant cachedAt, Instant expiresAt, Collection<SheetRef> sheets);

    /** Drops every entry that read {@code worksheet}. @return number of entries removed */
    int invalidate(String spreadsheetId, String worksheet);

    /** Drops entries that expired before {@code now}. NETWORK_FIRST can no longer fall back to them. */
    int purgeExpired(Instant now);

    void clear();

    @Override
    void close();
}
