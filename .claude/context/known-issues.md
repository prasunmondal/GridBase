---
status: VERIFIED
last_verified: 2026-10-14
sources: [appscript/backend/parser/RequestValidator.js, appscript/common/, appscript/deploy.bat, appscript/deploy.cmd, appscript/appsscript.json, appscript/backend/loader/ValueReader.js, appscript/backend/commit/WorksheetCommitter.js, gridbase/src/test/java/io/github/prasunmondal/gridbase/integrationTests/ItConfig.java]
---
# Known issues and technical debt

User-visible engine issues (fixed and open) are listed in README "Engine issues", which consumers
read. This file adds internal debt and the triage of the old engine review
(`history/engine-review.md`). Remove an entry when it is fixed, and move it to the README table if
users need to know.

## Open: engine
| Issue | Where | Notes |
|---|---|---|
| **No authentication.** Anyone with the URL can read and write every spreadsheet the script can reach. | `api/SheetEngine.js` | Also README "Open". `doPost` cannot read headers, so a secret has to travel in the body. Needs an ADR. |
| Validation for INSERT / UPDATE / DELETE / UPSERT / CLONE is `return true` | `parser/RequestValidator.js` | Bad input fails later, during execution, with less helpful messages (review #2). |
| `common/*.js` (8 files) are empty `myFunction()` placeholders | `appscript/common/` | Dead code: implement or delete (review #1). `State.js` is the only real file. |
| Every scan (SELECT / UPDATE / DELETE / UPSERT / CLONE) loads the whole worksheet in one `getDataRange()` read | `loader/ValueReader.js` | Time and memory grow with sheet size, even with `limit`. INSERT-only worksheets read just the header row (ADR-015; review #7, perf fix #1). |
| Reads take no lock, so they can see a half-applied commit | `api/SheetEngine.js` | By design (ADR-007). Since ADR-015 the commit goes out as one flushed batch, which shortens the window. |
| Scattered UPDATE / DELETE rows still cost one Sheets call per row | `commit/WorksheetCommitter.js` | Only adjacent rows are batched. SpreadsheetApp cannot write or delete scattered rows in one call. |
| The shipped `appsscript.json` enables Drive with explicit scopes (`f2a258a`), contrary to ADR-014 | `appscript/appsscript.json` | A redeploy then needs the owner's consent for the Drive scope. Either revert the manifest or supersede ADR-014. |

## Open: SDK, build and docs
| Issue | Where | Notes |
|---|---|---|
| The `ItConfig` Javadoc says `mvn verify` runs ITs and refers to `Test1`, which no longer exists | `integrationTests/ItConfig.java` | There is no failsafe plugin. Run ITs with `-Dtest='*IT'`. |
| `deploy.cmd` / `deploy.bat` push from the hard-coded `D:\Projects\GridBase\appscript`, not from the checkout | `appscript/deploy.cmd` | Deploying pushes whatever is in that folder. On a machine where the repo lives elsewhere (e.g. `C:\Projects\GridBase`), fix `PROJECT_DIR` first. |
| Automatic queue: a request's cache store (`finishRead`) and another request's invalidation (`invalidateWrites`) from the same combined call run on different `gridbase-async` threads, in any order | `GridBase.submit`, `RequestQueue` | Write-then-read in one window often erases the fresh read (extra network call; `QueueConsistencyTest.writeThenReadInOneWindowCachesThePostWriteRead` fails). Read-then-write could leave a stale entry; not reproduced. `executeTogether` does both in order on one thread. |
| A combined call's reply is parsed whole before it is split, so one missing result fails every request in the call | `GridBase.remote` / `sendCombined` | Pinned by `RequestQueueTest.aShortReplyFailsEveryRequestInTheCall`. |
| Combining a read with a write removes the read's retry; `APIRequestsQueue` groups by client, not queue order; a failed `Batch` can be re-executed | `GridBase.sendCombined`, `APIRequestsQueue`, `Batch` | Pinned by tests (A4, B6, C11) but not documented in the README. |
| GPG signing is bound to `verify` without a profile | root `pom.xml` | `mvn install` needs the key or `-Dgpg.skip`. Not yet checked whether JitPack builds are affected. |

## Engine review triage (`history/engine-review.md`, checked 2026-10-09)
| # | Item | Status |
|---|---|---|
| 1 | Empty utility files | **Open**, see above |
| 2 | Skipped validation | **Open**, see above |
| 3 | String coercion in EQUALS | **Fixed / by design.** `CompiledEqualsPredicate` compares `String(cell)` with `String(expected)`. The SDK sends text (ADR-003). |
| 4 | Singleton not thread-safe | **Not an issue.** Each Apps Script execution has its own global state, and the instance holds only `EngineConfig`. |
| 5 | No input sanitization / access control | **Open**, part of "No authentication" |
| 6 | Missing operation context in execution errors | Not verified |
| 7 | Whole worksheet loaded | **Open for scans**, fixed for INSERT (ADR-015), see above |
| 8 | No pagination on writes | Not verified |
| 9 | Column index brittleness | Not verified. Indexes are resolved per request, so the risk is limited to within one request. |
| 10 | No transactions | **Outdated.** `ExecutionService.execute` calls `commitAll()` only after every operation, so row changes are all-or-nothing per request. Schema operations still apply immediately. |
| 11–18 | Logging, magic numbers, null checks, long methods, typos, JSDoc | Code-quality notes, not verified |
| perf 1–8 | `history/engine-perf-fixes.md` | Ideas only. #1 (lazy loading) is the same as review #7 and is applied to INSERT only. ADR-015 (2026-10-14) also batched commits and shortened the lock. The other ideas were not re-checked against it. |
