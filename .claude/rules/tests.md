---
paths:
  - "gridbase/src/test/**"
---
# Tests: invariants

- `*IT` classes hit a **live** deployment and are not run by `mvn test` or `mvn verify` (there is no
  failsafe plugin). Run them with `mvn test -pl gridbase -Dtest='*IT'`, or against the emulator with
  `-Dhs.endpoint=http://127.0.0.1:8765/macros/s/LOCAL/exec`.
- ITs touch only `IT_*` worksheets. Keep `TestData.resetAll()` reseeding them and invalidating the
  cache.
- New test classes named `Test*`, `*Test` or `*Tests` run under `mvn test`, so they must not need
  the network.
- Caching tests: `FakeSheetsEngine` + `MutableClock`, **never sleep**. Assert on data freshness,
  not only call counts.
- Wire changes → `RequestContractTest`. Engine fixes → a regression IT plus a README "Engine issues"
  row.
- Use `FakeTransport` to stub the engine in unit tests.

→ `.claude/context/domains/testing.md`
