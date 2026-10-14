# ADR-015: Engine holds the lock briefly, flushes before releasing it, and batches sheet I/O

## Status
Accepted. Refines ADR-007 (script lock for writes is unchanged).

## Date
2026-10-14

## Context
Sheets service calls dominate engine latency, and the data a full read moves grows with the sheet.
Every row operation read the whole worksheet, even an INSERT. Commits wrote one `setValues` per updated
row and one `deleteRow` per deleted row. The script lock serializes every write in the deployment, and
it was held while the response was serialized. It was also released **before** Apps Script sent the
buffered writes (which happens when the script ends), so the next writer could read the old
`getLastRow()` and overwrite the rows just appended. An append past the sheet's last grid row failed.

## Decision
- **Lock** (`SheetEngine.handlePost`): `openById` runs before `waitLock`. After the commit,
  `SpreadsheetApp.flush()` runs, then `releaseLock()`, and only then is the response built. A failed
  request flushes in `finally` before releasing.
- **Load**: a worksheet that no operation of the request scans (INSERT only) reads just the header row.
  Otherwise one `getDataRange().getValues()` read, kept without copying (`ExecutionService.scannedWorksheets`).
- **Scan**: without ORDER BY, the scan stops after `offset + limit` matches.
- **Commit** (`WorksheetCommitter`): an UPDATE writes only the span of columns it changed, with adjacent
  rows in one block. Adjacent deleted rows go in one `deleteRows`, bottom-up. A row added and then
  deleted in one request is never written, and an added row is written once, with its final values. The
  grid grows with `insertRowsAfter` when an append passes `getMaxRows()`.

## Alternatives considered
- Cache sheet values across requests (CacheService): it would hide edits made in the Sheets UI and
  needs invalidation across executions.
- TextFinder for EQUALS lookups: it matches the displayed text, not `String(cell)`, so formatted
  numbers and dates would be missed.
- Rewrite the rows below the first deleted row instead of `deleteRows`: one call, but formats, notes and
  formulas stay behind, and rewritten text is re-parsed (`"007"` becomes 7).
- Per-worksheet leases so that writers to different sheets run in parallel: not atomic without the
  script lock, and it adds service calls to every request. That would need an ADR superseding ADR-007.
- Reads under the lock: this would serialize every read (ADR-007).

## Rationale
Fewer and smaller service calls, and a shorter lock hold, raise write throughput for the whole
deployment without changing what a request can observe.

## Consequences
- UPDATE no longer rewrites cells outside the changed span, so formulas and text such as `"00042"` in
  other columns survive. Before, `setValues` re-parsed them.
- INSERT cost no longer grows with the sheet. Scans still read the whole sheet.
- Reads remain lock-free. The commit now goes out as one flushed batch, so the window where a read sees
  it half-applied is shorter, but it is not gone.
- The emulator implements `flush`, `getDataRange`, `deleteRows` and `insertRowsAfter`.

## Constraints
- Release the lock only in `SheetEngine.handlePost`, and only after `SpreadsheetApp.flush()`.
- A write request must not read sheet contents before it holds the lock. Only `openById` may run earlier.
- A commit must never rewrite rows the request did not change.
- An operation type that reads existing rows must be listed in `ExecutionService.scannedWorksheets`.
  Otherwise it sees only the rows that the request itself added.

## Related components
`SheetEngine.js`, `ExecutionService.js`, `GoogleSheetsProvider.js`, `loader/`, `WorksheetCommitter.js`,
`DataRow.js`, `ExecutionExecutor.js`, `engine-emulator.js`
