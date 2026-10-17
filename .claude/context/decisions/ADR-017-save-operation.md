# ADR-017: `SAVE` operation replaces a worksheet's rows in one all-or-nothing step

## Status
Accepted. Supersedes ADR-016.

## Date
2026-10-17

## Context
Repository `save` / `saveAll` sent `CLEAR_WORKSHEET` then `INSERT`. CLEAR is a schema operation and
applies immediately, so an INSERT that failed (e.g. *Unknown column*) left the sheet empty. ADR-016
fixed this by staging CLEAR_WORKSHEET at commit, which changed an existing operation's semantics
instead of naming the intent.

## Decision
A new wire operation `SAVE` (`spreadsheetId`, `worksheet`, optional `rows`, `optionalColumns`; rows
in the INSERT format). In the engine it is a row operation: `ExecutionEngine` calls
`WorksheetData.clearRows()` and then runs the INSERT executor on its rows; `WorksheetCommitter`
clears rows 2..last (`clearContent`) before appending. The reply is a rows result like INSERT.
CLEAR_WORKSHEET is again a schema operation that applies immediately. The SDK adds
`OperationType.SAVE`, `SaveSpec` and `Worksheet.save(rows)`; `Repository.saveAll` sends one SAVE.

## Alternatives considered
- Staged CLEAR_WORKSHEET (ADR-016): replaced at the user's request.
- Falling back to CLEAR + INSERT on engines without SAVE: rejected, old deployments are not in use.

## Consequences
- `save` / `saveAll` need a redeployed engine; an old one fails with *Unsupported operation type: SAVE*.
- SAVE is not a schema operation, so request queues may combine it with other requests (a failed
  combined call writes nothing and is re-sent per request).
- A failure inside the commit itself (a Sheets service error) can still leave a partial write, as for
  other row operations.

## Constraints
- Changes staged before a SAVE on the same worksheet in the same request are discarded, not written.

## Related components
`appscript/backend/execution/ExecutionEngine.js`, `table/WorksheetData.js`, `commit/WorksheetCommitter.js`,
`parser/RequestValidator.js`, `spec/SaveSpec.java`, `mapping/Repository.java`, `RequestContractTest`, `SaveIT`
