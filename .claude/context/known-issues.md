---
status: VERIFIED
last_verified: 2026-10-10
sources: [appscript/backend/parser/RequestValidator.js, appscript/common/, appscript/deploy.sh, gridbase/src/test/java/io/github/prasunmondal/gridbase/integrationTests/ItConfig.java]
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
| Every read loads the whole worksheet (`getRange(1,1,lastRow,lastCol)`) | `loader/ValueReader.js` | Memory and time grow with sheet size, even with `limit` (review #7, perf fix #1). |
| Reads take no lock, so they can see a half-applied commit | `api/SheetEngine.js` | By design (ADR-007). Document it rather than fix it. |

## Open: SDK, build and docs
| Issue | Where | Notes |
|---|---|---|
| The `ItConfig` Javadoc says `mvn verify` runs ITs and refers to `Test1`, which no longer exists | `integrationTests/ItConfig.java` | There is no failsafe plugin. Run ITs with `-Dtest='*IT'`. |
| `deploy.sh` pushes from `/c/Projects/hibernate.gsheets`, not from this repo | `appscript/deploy.sh` | Deploying runs whatever is in that folder. Sync `appscript/` there first. |
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
| 7 | Whole worksheet loaded | **Open**, see above |
| 8 | No pagination on writes | Not verified |
| 9 | Column index brittleness | Not verified. Indexes are resolved per request, so the risk is limited to within one request. |
| 10 | No transactions | **Outdated.** `ExecutionService.execute` calls `commitAll()` only after every operation, so row changes are all-or-nothing per request. Schema operations still apply immediately. |
| 11–18 | Logging, magic numbers, null checks, long methods, typos, JSDoc | Code-quality notes, not verified |
| perf 1–8 | `history/engine-perf-fixes.md` | Ideas only. None measured or applied. #1 (lazy loading) is the same as review #7. |
