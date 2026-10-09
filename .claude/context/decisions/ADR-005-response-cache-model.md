# ADR-005: Opt-in response cache keyed by operations, invalidated per worksheet

## Status
Accepted (recorded retroactively)

## Date
2026-10-01 (`50727d8` "add caching support in sqlLite"); stores added 2026-10-08 (`e0acf6e`)

## Context
Reads are slow Apps Script round trips and count against quota. Many apps reread the same data.
A cache must never break a request or serve data that a write through the same client has changed.

## Decision
- The cache is opt-in (`shallCache(true)`). Only fully read-only requests are cached.
- The key is the SHA-256 of the serialized operations. The value is the **raw engine reply**, tagged
  with the worksheets read, and re-parsed on a hit.
- Every non-read-only request invalidates its worksheets, **even if it fails**.
- Freshness comes from `CacheExpiry` (ttl / dailyAt). `NETWORK_FIRST` serves stale entries only on
  transport or retryable errors.
- Cache I/O failures are logged and never fail a request.
- Stores sit behind `ResponseCache`, are chosen by `CacheBackend` and are shared per path in the JVM.

## Alternatives considered
- Caching mapped objects: duplicates mapping logic and breaks when the POJO changes.
- Row-level invalidation: needs engine support to know which rows changed.

## Rationale
Storing raw replies keeps hits identical to network results, and worksheet-level invalidation is
simple and safe.

## Consequences
- Writes from other clients or processes, or manual edits, are invisible until expiry.
- Network hooks do not fire on hits.

## Constraints
- Do not cache a request that contains any write.
- Invalidate before or regardless of a write's success.
- Never let cache errors propagate.

## Related components
`cache/`, `HibernateSheets.java` (`invalidateWrites`, `executeRefreshing`), `cachingTests/`
