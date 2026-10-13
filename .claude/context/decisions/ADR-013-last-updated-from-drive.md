# ADR-013: Spreadsheet last-updated time comes from Drive metadata, never cached

## Status
Superseded by ADR-014

## Date
2026-10-13

## Context
Consumers want to know whether cached data is stale, **including edits made directly in Google
Sheets**. Apps Script has no per-tab "last edited" time, and `SpreadsheetApp` exposes none at all.

## Decision
A new read-only operation, GET_LAST_UPDATED, returns Drive's `modifiedTime` of the spreadsheet file
(epoch millis on the wire). The engine reads it with the Advanced Drive service (v3) under the
`drive.metadata.readonly` scope, with the scopes listed explicitly in `appsscript.json`. In the SDK it is
read-only (retryable, no lock) but not cacheable (`OperationType.isCacheable()`).

## Alternatives considered
- Engine-recorded per-tab time (developer metadata stamped on each commit): per tab and no new scope,
  but blind to hand edits, which is the main reason for the feature.
- `DriveApp.getFileById().getLastUpdated()`: simpler, but auto-detection grants the full `drive` scope
  (every file in the deployer's Drive) on an unauthenticated endpoint.

## Rationale
Only Drive sees every edit. The metadata scope is the narrowest one that can read it.

## Consequences
- Each consumer must redeploy **and** consent to the new scope as the deploying account. An old engine
  rejects the operation with "Unsupported operation type", which is a clear failure.
- Spreadsheet-wide: an edit to any tab advances it (a safe false positive for staleness checks).
- In a batch it runs before the commit, so it excludes that batch's own row changes.

## Constraints
- `appsscript.json` scopes are explicit: an engine change that calls another Google service must add
  its scope there, or the web app fails authorization.
- Keep GET_LAST_UPDATED out of the response cache.

## Related components
`ExecutionService.executeGetLastUpdated`, `appsscript.json`, `SheetEngine.writes`,
`SchemaSpecs.LastUpdated`, `OperationType.isCacheable`, `GridBase.submit`/`executeTogether`,
`ResponseCacheTest.lastUpdatedIsNeverServedFromCache`, `SchemaOperationsIT.lastUpdatedAdvancesAfterWrite`
