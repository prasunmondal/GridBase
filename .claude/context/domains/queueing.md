---
status: VERIFIED
last_verified: 2026-11-02
sources:
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/RequestQueue.java
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/APIRequestsQueue.java
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/SheetRequest.java
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/GridBase.java
---
# Request queues (fewer HTTP calls)

## Purpose
Each Apps Script call is slow (a whole web-app round trip) and counts against quota. Combining requests that are made
close together into one HTTP call saves both. User docs: README "Request queue" and "Automatic
request queue". Decision: ADR-006.

## Explicit queue: `APIRequestsQueue`, `SheetRequest<T>`, `Queued`
- A `SheetRequest<T>` holds the operations, the client and an `ExecutionResponse → T` mapper. It is
  run with `execute()` or `queue(q)`.
- `APIRequestsQueue.execute()` groups requests by client and calls `GridBase.executeTogether`,
  which does a cache lookup per request and then calls `sendCombined` once per call. Calls are split
  around schema operations and at `maxOperationsPerCall`.
- Failures belong to each `Queued` handle. `execute()` throws `QueueExecutionException` at the end if
  any request failed.

## Automatic queue: `RequestQueue` (package-private)
- Opt-in: `SheetProperties.queueRequests(window)` or `GridBase.Builder.requestQueue(...)`.
- `execute` is built on `submit(ops, async)`, which returns `CompletableFuture`s. `execute` awaits
  and rethrows the **original** exception. Without a queue, sync calls run inline on the caller's
  thread.
- Requests are collected for up to `window` and then sent by **one daemon worker thread, one HTTP
  call at a time**. This preserves order and avoids racing other writes.
- **Schema operations are never queued**, because they apply immediately and cannot be re-sent
  safely.
- Calls made on the worker thread (from hooks) bypass the queue, to avoid deadlock. Caller futures
  complete on the `gridbase-async` pool, never on the worker.
- **Cache bookkeeping (write invalidation, read store) runs on the worker, per request in call order,
  before any future completes** (`submit(ops, onWorker)`). On callback threads a write's invalidation
  could land after a later read's store (fresh reply erased) or before an earlier read's store (stale
  reply kept). `executeTogether` already works this way for the explicit queue.

## Shared core: `GridBase.sendCombined`
- It makes one combined call, then `ResponseParser.slice` cuts the reply per request. Results are
  renumbered from `op-1`, and **the slice is what gets cached**.
- After a non-retryable `ServerException` on a combined call, every request is **re-sent alone**.
  This is safe because the engine wrote no row changes.

## Tests
`APIRequestsQueueTest`, `RequestQueueTest`, `cachingTests/CacheBatchingTest`, `cachingTests/QueueConsistencyTest`.
- **Callback-order races: `QueueCacheOrderingTest`.** It injects a callback executor that holds
  callbacks and runs them in reverse (package-private `Builder.queueCallbacks`), so a race fails every
  run, not by luck. Timing-based loops (`QueueConsistencyTest`) passed with the bug present; add new
  queue/cache ordering cases here, and check a new test fails against a mutant before trusting it.
