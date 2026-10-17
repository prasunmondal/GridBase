---
status: VERIFIED
last_verified: 2026-10-17
sources: [appscript/backend/, appscript/deploy.bat, appscript/deploy.cmd, gridbase/src/test/emulator/engine-emulator.js]
---
# Engine (Apps Script, `appscript/`)

## Purpose
The server half: a SQL-like engine over Google Sheets, deployed as an Apps Script web app. **`appscript/`
is the only source copy** (ADR-001; the former LF mirror directory was removed in `ac264cb`).

## Request lifecycle
`backend/api/Code.js` (`doPost`) → `SheetEngine.handlePost` → `RequestParser.parse` →
`RequestValidator.validate` → if the request writes: open its spreadsheets, then **script lock** →
`ExecutionService.execute` (each operation through `execution/**` executors) → `provider.commitAll()`
→ `SpreadsheetApp.flush()` → release the lock → `ApiResponse` (ADR-015).

Layers: `parser/`, `compiler/` + `execution/predicate/compiled/` (filter compilation), `loader/`
(reads sheet values), `table/` and `model/` (in-memory rows), `execution/*` (one executor per op),
`commit/` and `write/` (write-back), `provider/GoogleSheetsProvider.js`, `core/EngineConfig.js`.

## Semantics consumers rely on
- **Row operations commit together** at the end of a request (`commitAll` runs after every
  operation). If an operation throws, no row changes are written. Later operations see earlier ones.
- **Schema operations** (CREATE / CLEAR / ADD_COLUMNS) touch the sheet immediately, so they are
  neither all-or-nothing nor safe to re-send. The SDK never queues them (ADR-006).
- **REPLACE_ALL_DATA is a row operation** (ADR-017): it drops the in-memory rows and changes staged
  before it, stages its `rows` as inserts, and the commit clears rows 2..last (`clearContent`)
  before appending. No rows just clears. If anything in the request fails, the sheet is untouched. Repository
  `replaceAllData` sends it; engines without it reject it as an unsupported operation type.
- **Write requests take `LockService.getScriptLock()`** for up to `EngineConfig.lockTimeoutMillis`
  (30 s). A timeout returns `success:false` "Lock timeout…", which the SDK treats as retryable. Reads
  take no lock and may see a half-applied commit (ADR-007). Buffered writes are flushed **before** the
  lock is released, otherwise the next writer could append over them (ADR-015).
- **Loading:** a worksheet that the request only INSERTs into reads just its header row. A worksheet
  that any operation scans is read once, in full (`ExecutionService.scannedWorksheets`). Without ORDER
  BY, a scan stops after `offset + limit` matches.
- **Commit:** appended rows go in one block, and the grid grows if needed. An UPDATE writes only the
  span of columns it changed, with adjacent rows in one block, so other cells keep formulas and text.
  Deletes run bottom-up, with adjacent rows in one `deleteRows`. A row added and then deleted in the
  same request is never written.
- Filters compare in JavaScript (e.g. `CompiledEqualsPredicate` compares `String(cell)` to
  `String(expected)`). The SDK shapes values for this (see `wire-contract.md`).
- The engine cannot delete worksheets.
- GET_LAST_UPDATED returns Drive's `modifiedTime` of the whole spreadsheet. ADR-014 makes Drive
  **opt-in per deployment**, but the manifest committed in `f2a258a` enables it again (see
  `known-issues.md`). Without Drive, only this operation fails. It takes no lock and runs before the
  request's commit.
- A value for a column listed in `optionalColumns` that the sheet lacks is dropped in
  `ExecutionCompiler`, not rejected. Other references to the column still fail (ADR-012).

## Deploying
- `appscript/deploy.cmd` (Windows) and `appscript/deploy.bat` (bash, despite the name) run `clasp push` +
  `clasp deploy` **from the hard-coded `PROJECT_DIR=D:\Projects\GridBase\appscript`**, not from wherever
  the repo is checked out. An empty `DEPLOYMENT_ID_TO_USE` creates a new deployment. `deploy.cmd` frees a
  slot when the limit of 20 versioned deployments is reached. Check `PROJECT_DIR` first (`known-issues.md`).
- A change reaches users only after **their** deployment is redeployed. Anything that needs a newer
  engine must be called out in the README and must fail clearly against old engines.
- `.md` files under `appscript/` are not pushed by clasp.

## Testing engine changes
- Emulator: `node gridbase/src/test/emulator/engine-emulator.js appscript [port]` runs the real
  engine source in Node with in-memory Sheets (stubs `SpreadsheetApp`, `ContentService`,
  `LockService`; requests are serial). Point ITs at `-Dhs.endpoint=http://127.0.0.1:8765/macros/s/LOCAL/exec`.
- Fixed engine bugs have regression ITs (README "Engine issues"). Against an engine deployed before
  those fixes, `SelectQueryIT` (IN/BETWEEN, raw numeric EQUALS), `DeleteIT` (deletes within a batch),
  `InsertIT.concurrentInsertsAllLand` and `SchemaOperationsIT.insertPastGridGrowsWorksheet` are
  expected to fail.
- The emulator applies writes at once and serves one request at a time. It cannot show the
  buffered-write race or lock contention: check those on a live deployment.

## Related
- `wire-contract.md`, `testing.md`, `known-issues.md`, ADR-001, ADR-007, ADR-015, ADR-017
