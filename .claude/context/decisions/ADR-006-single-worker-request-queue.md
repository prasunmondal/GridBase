# ADR-006: Automatic queue: one worker, no schema ops, re-send alone on error

## Status
Accepted (recorded retroactively)

## Date
2026-10-01 (`3bdc8cc` "queue"); combined sending 2026-10-05 (`96187fe`)

## Context
Many small requests mean many slow, quota-counted calls. Concurrent writes also raced in the engine
before it had a lock (ADR-007). Combining requests saves calls, but one bad request must not fail
the others.

## Decision
- An opt-in time window (`queueRequests(window)`) collects requests. **One daemon worker** sends
  them, **one HTTP call at a time**, in order.
- Combined replies are cut per caller with `ResponseParser.slice`, and the slices are what get
  cached.
- After a non-retryable `ServerException` on a combined call, each request is re-sent alone. Row
  changes are all-or-nothing per request, so nothing was written.
- **Schema operations are never queued.** They apply immediately, so a re-send could repeat them.
- Calls made on the worker thread bypass the queue. Futures complete on the `gridbase-async`
  pool, never on the worker.
- The explicit `APIRequestsQueue` shares the same `sendCombined` path.

## Alternatives considered
- A worker pool: loses ordering and brings the write race back.
- Failing the whole combined call: one bad request would fail unrelated callers.

## Rationale
It preserves order and isolation while reducing calls, and is simple to reason about.

## Consequences
- Throughput is one call at a time per client, and the added latency is at most `window`.

## Constraints
- Do not queue an operation that takes effect before the commit.
- Never block the worker on a caller future.

## Related components
`RequestQueue.java`, `APIRequestsQueue.java`, `GridBase.java` (`sendCombined`, `executeTogether`)
