# ADR-004: Retry only read-only requests by default

## Status
Accepted (recorded retroactively)

## Date
On or before 2026-10-01 (present since the first SDK commits)

## Context
Apps Script fails transiently: timeouts, HTTP 429/5xx, "too many times in a short time", lock
timeouts. A write that timed out on the client may already be committed on the server. A daily-quota
error will not recover within any reasonable back-off.

## Decision
`RetryPolicy.defaults()` makes up to 3 attempts (back-off 500 ms ×2, max 8 s), **only failures flagged
`isRetryable()`**, and **only read-only requests** (SELECT / GET_COLUMNS). Writes are retried only
with `retryingWrites(true)`. Daily-quota errors are never retried.

## Alternatives considered
- Retry everything: risks duplicate inserts.
- Idempotency keys in the engine: needs an engine change and storage.

## Rationale
Correct data matters more than availability for writes (`product.md` promise 4).

## Consequences
- Callers handle write failures themselves.
- The lock-timeout case is safe to retry (nothing ran), but writes still wait for the opt-in.

## Constraints
- New failure kinds must set `retryable` correctly in `HibernateSheetsException`.
- Do not make write retries the default.

## Related components
`RetryPolicy.java`, `exception/HibernateSheetsException.java`, `internal/ResponseParser.java`
