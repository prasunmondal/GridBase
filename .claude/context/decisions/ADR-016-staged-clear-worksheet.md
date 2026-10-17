# ADR-016: CLEAR_WORKSHEET is staged and committed with the request's row changes

## Status
Superseded by ADR-017 (CLEAR_WORKSHEET applies immediately again; save uses a REPLACE_ALL_DATA operation)

## Date
2026-10-17

## Context
Repository `save` / `saveAll` send `CLEAR_WORKSHEET` then `INSERT` in one request. The clear ran
immediately as a schema operation, so an INSERT that failed later in the request (e.g. *Unknown
column*) left the sheet empty.

## Decision
`ClearWorksheetExecutor` no longer touches the sheet. It loads the worksheet header-only and calls
`WorksheetData.clearRows()`, which drops the in-memory rows, indexes and the changes staged earlier
in the request, and sets `cleared`. `WorksheetCommitter.commit` clears rows 2..last
(`clearContent`, rows and formatting kept) before appending the created rows. The wire format and
the response (`rowsCleared`, `columnsCleared`) are unchanged, so no SDK change is needed.

## Alternatives considered
- A new `SAVE` / `REPLACE` operation: a wire-contract change that old engines reject, and the SDK
  would need to detect the engine version.
- Validate the INSERT before running the clear: covers only the failures validation can predict.

## Rationale
It reuses the existing all-or-nothing commit, works for any operations after the clear (a later
SELECT sees an empty sheet) and needs only a redeploy.

## Consequences
- On a current engine, any failing operation in the request leaves the sheet untouched.
- Old deployments keep the old behaviour until redeployed. The SDK still sends clears in a call of
  their own (ADR-006).
- A failure inside the commit itself (a Sheets service error) can still leave a partial write, as
  for other row operations.

## Constraints
- Changes staged before a clear in the same request are discarded, not written.

## Related components
`appscript/backend/execution/delete/ClearWorksheetExecutor.js`, `table/WorksheetData.js`,
`commit/WorksheetCommitter.js`, `SaveIT.unknownColumnLeavesSheetUntouched`
