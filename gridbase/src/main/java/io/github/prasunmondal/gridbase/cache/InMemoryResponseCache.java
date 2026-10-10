package io.github.prasunmondal.gridbase.cache;

import io.github.prasunmondal.gridbase.exception.GridBaseException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replies kept in memory only: fastest, nothing survives the process. Stores opened with the same
 * name via {@link #open} are shared within the JVM, so clients using the same name see each other's
 * invalidations (as with a shared cache file). Thread-safe.
 */
public final class InMemoryResponseCache implements ResponseCache {

    private static final Map<Path, InMemoryResponseCache> OPEN = new ConcurrentHashMap<>();

    private final Path name;
    private final Map<String, Stored> entries = new HashMap<>();
    private final Map<SheetRef, Set<String>> keysBySheet = new HashMap<>();
    private boolean closed;

    /** An entry plus the worksheets it read. */
    record Stored(Entry entry, List<SheetRef> sheets) {
    }

    /** A private store, not shared with anyone. */
    public InMemoryResponseCache() {
        this(null);
    }

    private InMemoryResponseCache(Path name) {
        this.name = name;
    }

    /** The shared store called {@code name}, created on first use. */
    public static InMemoryResponseCache open(Path name) {
        Path key = name.toAbsolutePath().normalize();
        return OPEN.compute(key, (k, existing) -> existing != null && !existing.isClosed()
                ? existing : new InMemoryResponseCache(k));
    }

    @Override
    public synchronized Optional<Entry> get(String key) {
        ensureOpen();
        Stored s = entries.get(key);
        return s == null ? Optional.empty() : Optional.of(s.entry());
    }

    @Override
    public synchronized void put(String key, String reply, Instant cachedAt, Instant expiresAt,
                                 Collection<SheetRef> sheets) {
        put(key, new Stored(new Entry(reply, cachedAt, expiresAt), new ArrayList<>(new LinkedHashSet<>(sheets))));
    }

    synchronized void put(String key, Stored stored) {
        ensureOpen();
        remove(key);
        entries.put(key, stored);
        for (SheetRef sheet : stored.sheets()) {
            keysBySheet.computeIfAbsent(sheet, s -> new HashSet<>()).add(key);
        }
    }

    @Override
    public synchronized int invalidate(String spreadsheetId, String worksheet) {
        ensureOpen();
        Set<String> keys = keysBySheet.get(new SheetRef(spreadsheetId, worksheet));
        if (keys == null) {
            return 0;
        }
        List<String> doomed = new ArrayList<>(keys);
        doomed.forEach(this::remove);
        return doomed.size();
    }

    @Override
    public synchronized int purgeExpired(Instant now) {
        ensureOpen();
        List<String> doomed = new ArrayList<>();
        for (Map.Entry<String, Stored> e : entries.entrySet()) {
            if (!e.getValue().entry().expiresAt().isAfter(now)) {
                doomed.add(e.getKey());
            }
        }
        doomed.forEach(this::remove);
        return doomed.size();
    }

    @Override
    public synchronized void clear() {
        ensureOpen();
        entries.clear();
        keysBySheet.clear();
    }

    @Override
    public synchronized void close() {
        closed = true;
        entries.clear();
        keysBySheet.clear();
        if (name != null) {
            OPEN.remove(name, this);
        }
    }

    synchronized boolean isClosed() {
        return closed;
    }

    /** Live entries, for writing a compacted snapshot. */
    synchronized Map<String, Stored> snapshot() {
        return new HashMap<>(entries);
    }

    synchronized int size() {
        return entries.size();
    }

    private void ensureOpen() {
        if (closed) {
            throw new GridBaseException("Response cache closed");
        }
    }

    private void remove(String key) {
        Stored old = entries.remove(key);
        if (old == null) {
            return;
        }
        for (SheetRef sheet : old.sheets()) {
            Set<String> keys = keysBySheet.get(sheet);
            if (keys != null) {
                keys.remove(key);
                if (keys.isEmpty()) {
                    keysBySheet.remove(sheet);
                }
            }
        }
    }
}
