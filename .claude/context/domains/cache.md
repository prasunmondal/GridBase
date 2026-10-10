---
status: VERIFIED
last_verified: 2026-10-10
sources:
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/cache/
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/GridBase.java
  - gridbase-android/src/main/java/io/github/prasunmondal/gridbase/android/AndroidSqliteResponseCache.java
---
# Response cache

## Purpose
Spreadsheet reads are slow (each one is an Apps Script round trip) and count against quota. The cache serves repeated reads
locally without returning wrong data after a write made through the same cache. User-facing details
are in the README "Response cache" section. Decisions: ADR-005 and ADR-010.

## How it works
- Opt-in: `SheetProperties.shallCache(true)` (plus `cacheFile`, `cacheBackend`, `cacheStore` for a custom
  `ResponseCache`, `cacheExpiry`, `cacheStrategy`) or `GridBase.Builder.cache(store, strategy, expiry)`.
- In `GridBase.execute`, **only fully read-only requests** are cached. The key is the SHA-256
  of the serialized operations. The **raw engine reply** is stored, tagged with the worksheets read.
- Hits are re-parsed by `ResponseParser`, so POJO mapping is identical. Pre/post network actions do
  **not** fire on hits.
- **Every non-read-only request invalidates its worksheets, even when it fails**
  (`invalidateWrites`).
- `CacheExpiry` (`ttl` / `dailyAt`, combined with `or`) decides freshness. `CacheStrategy.NETWORK_FIRST`
  falls back to stale entries **only** on transport or retryable errors.
- `SheetRequest.forceRefresh()` → `GridBase.executeRefreshing` / `Planned.forceRefresh`
  skips the cache read, gives no stale fallback, and stores the fresh reply.
- Cache I/O failures are logged and **never fail a request**. The explicit erase calls
  (`Worksheet.clearCache`, `GridBase.clearAllCache` / `SheetProperties.clearAllCache`, `GridBaseTable.clearTableCache`/`clearAllCache`)
  are not requests: they throw store failures, and do nothing without a cache.

## Stores (`CacheBackend`)
| Backend | Class | Notes |
|---|---|---|
| `SQLITE` | `SqliteResponseCache` | JDBC (`org.sqlite`), one shared connection per file |
| `ANDROID_SQLITE` | `AndroidSqliteResponseCache` (in `gridbase-android`) | Opened **by reflection** (ADR-009). Same file format as `SQLITE` |
| `JOURNAL` | `JournalResponseCache` | Pure Java: entries in memory plus a CRC-framed append-only file, replayed on open and auto-compacted |
| `MEMORY` | `InMemoryResponseCache` | Nothing on disk |
| `AUTO` (default) | — | On the JVM: `SQLITE`. On Android: `ANDROID_SQLITE` if its class is present, else `JOURNAL` (with a one-time warning). Any `LinkageError` → journal at `<cacheFile>.journal` |

- Both SQL stores extend **`SqlResponseCache`, which owns the schema and every SQL statement**.
  Drivers only implement its small `Sql` interface, so SQL is changed there, once.
- Every store is shared per path within the JVM through its `open(Path)`.
- **Nothing outside `cache/` may reference `SqliteResponseCache`**, so `org.sqlite` loads only when
  SQLite is chosen. Android apps exclude `sqlite-jdbc`.
- Default file: `~/.gridbase/cache.db`. On Android, `<java.io.tmpdir>/gridbase/cache.db`
  (the app cache dir).

## Consistency limit
Writes from clients that **do not share the cache file** (another process, another device, edits in
the spreadsheet itself) stay invisible until expiry. That is why `TestData.resetAll()` invalidates
`Employee`'s cache.

## Tests
`cachingTests/*` with `FakeSheetsEngine` and `MutableClock` (see `testing.md`). Run
`mvn test -pl gridbase -Dtest='Cache*Test' [-Dhs.cacheBackend=JOURNAL|MEMORY|SQLITE]`. Under
`MEMORY`, 3 file-based tests are expected to fail. Also `ResponseCacheTest`, `CacheExpiryTest`,
`cache/JournalResponseCacheTest`. The Android store is verified on a device only (see `android.md`).
