---
status: VERIFIED
last_verified: 2026-10-09
sources: [appscript/backend/, appscript/deploy.sh, gridbase/src/test/emulator/engine-emulator.js]
---
# Engine (Apps Script, `appscript/`)

## Purpose
The server half: a SQL-like engine over Google Sheets, deployed as an Apps Script web app. **`appscript/`
is the only source copy** (ADR-001; the former LF mirror directory was removed in `ac264cb`).

## Request lifecycle
`backend/api/Code.js` (`doPost`) → `SheetEngine.handlePost` → `RequestParser.parse` →
`RequestValidator.validate` → **script lock if the request writes** → `ExecutionService.execute`
(each operation through `execution/**` executors) → `provider.commitAll()` → `ApiResponse`.

Layers: `parser/`, `compiler/` + `execution/predicate/compiled/` (filter compilation), `loader/`
(reads sheet values), `table/` and `model/` (in-memory rows), `execution/*` (one executor per op),
`commit/` and `write/` (write-back), `provider/GoogleSheetsProvider.js`, `core/EngineConfig.js`.

## Semantics consumers rely on
- **Row operations commit together** at the end of a request (`commitAll` runs after every
  operation). If an operation throws, no row changes are written. Later operations see earlier ones.
- **Schema operations** (CREATE / CLEAR / ADD_COLUMNS) touch the sheet immediately, so they are
  neither all-or-nothing nor safe to re-send. The SDK never queues them (ADR-006).
- **Write requests take `LockService.getScriptLock()`** for up to `EngineConfig.lockTimeoutMillis`
  (30 s). A timeout returns `success:false` "Lock timeout…", which the SDK treats as retryable. Reads
  take no lock and may see a half-applied commit (ADR-007).
- Filters compare in JavaScript (e.g. `CompiledEqualsPredicate` compares `String(cell)` to
  `String(expected)`). The SDK shapes values for this (see `wire-contract.md`).
- The engine cannot delete worksheets.

## Deploying
- `appscript/deploy.sh` runs `npx clasp push` + `clasp deploy` **from `PROJECT_DIR=/c/Projects/hibernate.gsheets`,
  not from this repo**, and updates the hard-coded `DEPLOYMENT_ID_TO_USE`. Copy or sync `appscript/`
  there first, or change `PROJECT_DIR` (see `known-issues.md`).
- A change reaches users only after **their** deployment is redeployed. Anything that needs a newer
  engine must be called out in the README and must fail clearly against old engines.
- `.md` files under `appscript/` are not pushed by clasp.

## Testing engine changes
- Emulator: `node gridbase/src/test/emulator/engine-emulator.js appscript [port]` runs the real
  engine source in Node with in-memory Sheets (stubs `SpreadsheetApp`, `ContentService`,
  `LockService`; requests are serial). Point ITs at `-Dhs.endpoint=http://127.0.0.1:8765/macros/s/LOCAL/exec`.
- Fixed engine bugs have regression ITs (README "Engine issues"). Against an engine deployed before
  those fixes, `SelectQueryIT` (IN/BETWEEN, raw numeric EQUALS), `DeleteIT` (deletes within a batch)
  and `InsertIT.concurrentInsertsAllLand` are expected to fail.

## Related
- `wire-contract.md`, `testing.md`, `known-issues.md`, ADR-001, ADR-007
