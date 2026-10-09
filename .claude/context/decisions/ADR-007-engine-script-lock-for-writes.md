# ADR-007: Engine takes a script lock for write requests

## Status
Accepted (recorded retroactively)

## Date
2026-10-01 (`0698c8f` "fix engine errors")

## Context
Concurrent requests appending at `getLastRow() + 1` overwrote each other (`InsertIT.concurrentInsertsAllLand`).
Standalone Apps Script has no per-spreadsheet lock.

## Decision
`SheetEngine.handlePost` takes `LockService.getScriptLock()` for any request that writes. It waits
up to `EngineConfig.lockTimeoutMillis` (30 s) and releases the lock in `finally`. Read-only requests
take no lock. A timeout returns `success:false` "Lock timeout…", which the SDK treats as retryable.

## Alternatives considered
- Document lock (only available to container-bound scripts).
- No lock, with client-side serialization only: does not help across clients.

## Rationale
It is the only lock Apps Script offers for a standalone web app, and it fixes lost writes.

## Consequences
- Writes to **different spreadsheets** through the same deployment are serialized too.
- Reads can see a half-applied commit.

## Constraints
- Every new write path must run inside the lock.
- Do not make reads wait for the lock without a new ADR.

## Related components
`appscript/backend/api/SheetEngine.js`, `appscript/backend/core/EngineConfig.js`, `RetryPolicy.java`
