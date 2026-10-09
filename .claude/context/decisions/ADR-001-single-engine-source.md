# ADR-001: `appscript/` is the single engine source; changes ship by redeploy

## Status
Accepted (recorded retroactively)

## Date
2026-10-08 (`ac264cb` "remove duplicate server code")

## Context
The engine was checked in twice, as `appscript/` (CRLF) and `server-appscript/` (LF), with identical
content that had to be kept in sync by hand. The duplication kept drifting and doubled every engine
diff.

## Decision
`appscript/` is the only copy of the engine source. `server-appscript/` was deleted. The engine
reaches users only when a deployment is redeployed (clasp push + deploy).

## Alternatives considered
- Keep both copies with a sync check: this was the old state, and it was error-prone.
- Move the engine to its own repository: this would split the wire contract across two repos.

## Rationale
The SDK and the engine share one JSON contract (`RequestContractTest` ⇄ `RequestParser.js`). Keeping
them in one repo, as one copy, keeps contract changes atomic.

## Consequences
- Every engine change must still be **redeployed** to take effect, and consumers run their own,
  possibly old, deployments (see ADR-003 and `product.md`).
- `appscript/deploy.sh` still pushes from an external folder (`known-issues.md`).

## Constraints
- Do not re-create a second copy of the engine.
- Engine changes are tested with the emulator (`node gridbase/src/test/emulator/engine-emulator.js appscript`)
  before deploying.

## Related components
`appscript/`, `gridbase/src/test/emulator/engine-emulator.js`, `domains/engine.md`
