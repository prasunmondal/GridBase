# ADR-012: Optional columns are dropped by the engine, not the SDK

## Status
Accepted

## Date
2026-10-12

## Context
Users want an entity property (`@OptionalColumn`) to be written when the sheet has its column and
silently skipped when it does not, instead of the whole write failing with "Unknown column".

## Decision
The SDK sends the names of optional columns per operation (`"optionalColumns": [...]`). The engine's
`ExecutionCompiler` drops a value or insert-row value whose column is optional and missing from the
schema. Filters, sorting and projections still require the column.

## Alternatives considered
- SDK-side: read the header (GET_COLUMNS) before each write and strip missing columns. It works
  with old engines, but costs an extra round trip per write (or a header cache that goes stale), races
  with concurrent schema changes, and does not fit `Batch` / queue requests built ahead of time.

## Rationale
The engine already holds the schema while it compiles, so the check is free, atomic and one request.

## Consequences
- Needs a redeployed engine. `RequestParser` ignores unknown keys, so an old engine behaves as before
  (writes when the column exists, "Unknown column" when it does not). It does not break silently.
- `spec.Operation` gained an `optionalColumns` component; the old 12-argument constructor is kept.

## Constraints
- Keep the old `Operation` constructor while it is public API.
- Paths that take a `Map` cannot see annotations (e.g. `Repository` upsert's `withoutKey`); they must
  pass the columns via `optionalColumns(...)`.

## Related components
`mapping/OptionalColumn.java`, `internal/OptionalColumns.java`, `spec/OperationSpec.java`,
`RequestSerializer`, `RequestParser.js`, `ExecutionCompiler.js`, `RequestContractTest`, `InsertIT`
