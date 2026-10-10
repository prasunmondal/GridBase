---
paths:
  - "**/gridbase/RequestQueue.java"
  - "**/gridbase/APIRequestsQueue.java"
  - "**/gridbase/SheetRequest.java"
  - "**/gridbase/Queued.java"
  - "**/gridbase/Batch.java"
  - "**/RequestQueueTest.java"
  - "**/APIRequestsQueueTest.java"
---
# Request queues: invariants (ADR-006)

- The automatic queue has **one** daemon worker that sends one HTTP call at a time, in order.
- Schema operations (CREATE / CLEAR / ADD_COLUMNS) are never queued or combined.
- A non-retryable `ServerException` on a combined call → re-send each request alone. Failures belong
  to each request (`Queued` / future), never to the whole batch.
- Combined replies are cut with `ResponseParser.slice`. The **slice** is what gets cached.
- Calls made on the worker thread bypass the queue. Caller futures complete on
  `gridbase-async`, never on the worker. Never block the worker on a caller future.
- `execute()` awaits and rethrows the **original** exception.
- The explicit and automatic queues share `GridBase.sendCombined`. Fix behaviour there, once.

→ `.claude/context/domains/queueing.md`
