# ADR-014: Drive access for GET_LAST_UPDATED is opt-in per deployment

## Status
Accepted. Supersedes ADR-013.

## Date
2026-10-13

## Context
ADR-013 made GET_LAST_UPDATED read Drive's `modifiedTime` and put the Advanced Drive service and an
explicit `drive.metadata.readonly` scope in the shipped `appsscript.json`. Apps Script checks every
declared scope before running anything, though. A deployment whose owner had not approved the new scope
would answer **every** request with an authorization page, not just GET_LAST_UPDATED. clasp deploys
never show the consent screen, so a routine redeploy could break all reads and writes.

## Decision
Still in force from ADR-013: GET_LAST_UPDATED returns Drive's `modifiedTime` of the whole spreadsheet
(epoch millis), read with the Advanced Drive service (v3) under `drive.metadata.readonly`. In the SDK it
is read-only (retryable, no lock) and never cached (`OperationType.isCacheable()`).

New: the shipped `appsscript.json` has **no** Drive service and no explicit scopes, so it needs only
Sheets, as before. A deployment opts in by enabling the service, listing the scopes explicitly and having
the owner approve them (README "Enabling `lastUpdated()`"). Without Drive, `executeGetLastUpdated` fails
only that operation, with a message saying how to enable it.

## Alternatives considered
- Drive on by default (ADR-013): one forgotten consent stops the whole engine.
- Engine-recorded per-tab time: no new scope, but blind to edits made in the Sheets UI.

## Rationale
Deploying the engine must never require a new approval. Only consumers who want the feature take on
the consent step.

## Consequences
- `lastUpdated()` needs a manual setup step per deployment.
- Live ITs: `SchemaOperationsIT.lastUpdatedAdvancesAfterWrite` fails on a deployment that has not opted
  in. The emulator has Drive on; `HS_DRIVE=off` emulates a deployment without it.

## Constraints
- The shipped `appsscript.json` must not require any scope beyond Sheets. An optional Google service
  stays opt-in and is guarded with `typeof <Service> === "undefined"`.
- On deployments that opted in, scopes are explicit: an engine change that uses another Google service
  must tell them to add its scope.
- Keep GET_LAST_UPDATED out of the response cache.

## Related components
`ExecutionService.executeGetLastUpdated`, `appsscript.json`, `SheetEngine.writes`,
`SchemaSpecs.LastUpdated`, `OperationType.isCacheable`, emulator `HS_DRIVE`, README "Enabling `lastUpdated()`"
