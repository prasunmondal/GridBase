---
status: VERIFIED
last_verified: 2026-10-09
sources: [gridbase/src/test/, gridbase/pom.xml]
---
# Testing

## Tiers
| Tier | Where | Runs under `mvn test` | Network |
|---|---|---|---|
| Unit | `gridbase/src/test/java/.../hibernatesheets/*Test.java`, `cache/` | yes | none (`FakeTransport`; `HttpTransportTest` uses an in-process server) |
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
```

## Conventions
- **There is no failsafe plugin.** `*IT` classes run only when selected with `-Dtest`. `mvn verify`
  does not run them, even though the `ItConfig` Javadoc suggests it does (`known-issues.md`).
- ITs default to a live deployment and spreadsheet (`ItConfig`). Override with `-Dhs.*` or the
  `HS_ENDPOINT` / `HS_SPREADSHEET_ID` / `HS_TIME_ZONE` environment variables. They touch only
  worksheets prefixed `IT_`. `TestData.resetAll()` reseeds `IT_Employees` / `IT_Departments` and
  invalidates `Employee`'s cache. `SchemaOperationsIT` leaves an `IT_Created_<timestamp>` sheet
  behind on every run, because the engine cannot delete worksheets.
- **Caching tests never sleep.** They advance a `MutableClock` passed through
  `SheetProperties.clock(...)`. `FakeSheetsEngine` holds real rows and supports `editDirectly`,
  `goOffline` and `failRequestsTouching`, so tests can check data freshness and not only call counts.
  Under `-Dhs.cacheBackend=MEMORY`, 3 file-based tests are expected to fail.
- Wire-contract changes update `RequestContractTest`. Engine bug fixes get a regression IT, listed in
  the README "Engine issues" table.
- Against an engine deployed before the fixes, these ITs are expected to fail: `SelectQueryIT`
  (IN/BETWEEN, raw numeric EQUALS), `DeleteIT` (deletes within a batch) and
  `InsertIT.concurrentInsertsAllLand`.
- `integrationTests/Employee.java` is the reference entity (its own `SheetProperties`, hooks, cache
  and queue).

## Related
- `cache.md`, `engine.md`, `android.md`
