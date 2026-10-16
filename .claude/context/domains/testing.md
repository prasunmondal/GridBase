---
status: VERIFIED
last_verified: 2026-10-16
sources: [gridbase/src/test/, gridbase/pom.xml]
---
# Testing

## Tiers
| Tier | Where | Runs under `mvn test` | Network |
|---|---|---|---|
| Unit | `gridbase/src/test/java/.../gridbase/*Test.java`, `cache/` | yes | none (`FakeTransport`; `HttpTransportTest` uses an in-process server) |
| Caching suite | `.../cachingTests/` | yes | none (`FakeSheetsEngine`) |
| Integration | `.../integrationTests/*IT.java` | **no**: run with `mvn test -pl gridbase -Dtest='*IT'` | **live** Apps Script deployment, or the emulator |
| Android | `gridbase-android` | no tests (Android stubs) | on-device checklist in `android.md` |

## Commands
```bash
mvn test                                                  # both modules
mvn test -pl gridbase -Dtest=RequestContractTest[#method]
mvn test -pl gridbase -Dtest='Cache*Test' [-Dhs.cacheBackend=JOURNAL|MEMORY|SQLITE]
mvn test -pl gridbase -Dtest=InsertIT [-Dhs.endpoint=... -Dhs.spreadsheetId=... -Dhs.timeZone=...]
node gridbase/src/test/emulator/engine-emulator.js appscript [port]   # then -Dhs.endpoint=http://127.0.0.1:8765/macros/s/LOCAL/exec
# HS_DRIVE=off: no Drive, like a deployment that has not opted in (only lastUpdated ITs fail)
```

## Conventions
- **There is no failsafe plugin.** `*IT` classes run only when selected with `-Dtest`. `mvn verify`
  does not run them, even though the `ItConfig` Javadoc suggests it does (`known-issues.md`).
- ITs default to a live deployment and spreadsheet (`ItConfig`). Override with `-Dhs.*` or the
  `HS_ENDPOINT` / `HS_SPREADSHEET_ID` / `HS_TIME_ZONE` environment variables. Every IT client logs
  its network calls as they happen (`NetworkLogger.defaults()`, stderr); turn off with
  `-Dhs.logNetwork=false` / `HS_LOG_NETWORK=false`. They touch only
  worksheets prefixed `IT_`. `TestData.resetAll()` reseeds `IT_Employees` / `IT_Departments` and
  invalidates `Employee`'s cache. `SchemaOperationsIT` leaves two `IT_Created_<timestamp>` sheets
  behind on every run, because the engine cannot delete worksheets.
- **Caching tests never sleep.** They advance a `MutableClock` passed through
  `SheetProperties.clock(...)`. `FakeSheetsEngine` holds real rows and supports `editDirectly`,
  `goOffline`, `failRequestsTouching` and `failNextWith(message)` (e.g. a retryable "Lock timeout"), so
  tests can check data freshness and not only call counts. Queue and batch tests that need real rows
  live there too (`QueueConsistencyTest`).
  Under `-Dhs.cacheBackend=MEMORY`, 3 file-based tests are expected to fail.
- Wire-contract changes update `RequestContractTest`. Engine bug fixes get a regression IT, listed in
  the README "Engine issues" table.
- Against an engine deployed before the fixes, these ITs are expected to fail: `SelectQueryIT`
  (IN/BETWEEN, raw numeric EQUALS), `DeleteIT` (deletes within a batch),
  `InsertIT.concurrentInsertsAllLand` and `SchemaOperationsIT.insertPastGridGrowsWorksheet`.
- ITs tagged `@Tag("live")` (`ConcurrencyIT`, `UpdateIT.updateKeepsFormulasInOtherColumns`) need a live
  deployment: the emulator is serial and does not evaluate formulas. `SchemaOperationsIT.lastUpdatedWithoutDrive…`
  aborts unless the engine lacks Drive (`HS_DRIVE=off`).
- `StressIT` (`@Tag("stress")`) is sized by `-Dhs.stress.clients|rounds|rows|writeAttempts` (defaults 8/5/500/8),
  prints p50/p95/max latencies to stderr, and retries writes only on retryable lock timeouts. It runs on the
  emulator, but contention only happens on a live deployment.
- `integrationTests/Employee.java` is the reference entity (its own `SheetProperties`, hooks, cache
  and queue).

## Related
- `cache.md`, `engine.md`, `android.md`
- `todo/tests.md`: the test plan these suites were written from (sections A–J)
