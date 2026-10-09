---
paths:
  - "gridbase/src/main/java/io/github/prasunmondal/hibernatesheets/cache/**"
  - "gridbase-android/**"
  - "gridbase/src/test/java/io/github/prasunmondal/hibernatesheets/cachingTests/**"
  - "gridbase/src/test/java/io/github/prasunmondal/hibernatesheets/cache/**"
  - "**/ResponseCacheTest.java"
  - "**/CacheExpiryTest.java"
---
# Response cache: invariants (ADR-005, ADR-009, ADR-010)

- Cache only fully read-only requests. Store the raw engine reply. Hits are re-parsed by
  `ResponseParser`.
- Every non-read-only request invalidates its worksheets, **even when it fails**.
- Cache I/O failures are logged and never fail a request.
- `NETWORK_FIRST` serves stale entries only on transport or retryable errors. `forceRefresh` never
  serves stale entries.
- All SQL and the schema live in `SqlResponseCache`. Drivers implement only `Sql`.
- Nothing outside `cache/` references `SqliteResponseCache` or `org.sqlite`.
- Stores are shared per path through `open(Path)` and must be thread-safe.
- `CacheBackend.ANDROID_SQLITE_CLASS`, `AndroidSqliteResponseCache.open(Path)` and
  `META-INF/proguard/gridbase-android.pro` must stay in sync. A rename silently disables Android
  caching.
- `gridbase-android` has no JVM tests. After changes, run the device checklist in `domains/android.md`.
- Tests use `MutableClock`, never sleep. Run them against other stores with
  `-Dhs.cacheBackend=JOURNAL|MEMORY|SQLITE`.

→ `.claude/context/domains/cache.md`, `.claude/context/domains/android.md`
