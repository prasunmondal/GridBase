---
paths:
  - "appscript/**"
  - "gridbase/src/test/emulator/**"
---
# Engine (Apps Script): invariants

- `appscript/` is the **only** engine source. Do not create a second copy (ADR-001).
- Changes reach users only after a **redeploy**, and consumers may run old engines. Keep the SDK
  compatible, or document the new minimum.
- `deploy.sh` pushes from `/c/Projects/hibernate.gsheets`, not from this repo (`known-issues.md`).
- Every write path must run inside the script lock in `SheetEngine.handlePost`. Reads take no lock
  (ADR-007).
- Row operations commit together through `provider.commitAll()` at the end of a request. Schema
  operations (CREATE / CLEAR / ADD_COLUMNS) apply immediately. Do not change either without an ADR,
  because the SDK queues rely on them (ADR-006).
- Parser changes are wire-contract changes: update `RequestContractTest` too.
- Test with the emulator before deploying:
  `node gridbase/src/test/emulator/engine-emulator.js appscript`, then run the ITs with
  `-Dhs.endpoint=http://127.0.0.1:8765/macros/s/LOCAL/exec`.
- An engine bug fix gets a regression IT and a row in the README "Engine issues" table.

→ `.claude/context/domains/engine.md`
