---
status: VERIFIED
last_verified: 2026-10-10
---
# Architecture Decision Records

Read the ADRs that relate to an area **before** changing its architecture. ADRs are never rewritten:
when a decision changes, add a new ADR with `Supersedes ADR-N` and set the old one's status to
`Superseded by ADR-M`. Typos and broken links may be fixed in place.

**A new ADR is needed for** a change to the wire contract or engine semantics, a new runtime
dependency, module or cache backend, a threading or consistency model, a public API break, or a
compatibility or release policy. Routine features and bug fixes do not need one.

ADR-001 to ADR-010 were **recorded retroactively** on 2026-10-09 from the code, the README and git
history. Their "Date" is the commit that introduced the decision, and the maintainer should
confirm their rationale once.

| ADR | Title | Status |
|---|---|---|
| [001](ADR-001-single-engine-source.md) | `appscript/` is the single engine source; changes ship by redeploy | Accepted |
| [002](ADR-002-android-api26-minimal-deps.md) | Android API 26 compatibility and a minimal dependency set | Accepted |
| [003](ADR-003-js-string-filter-values.md) | eq-family filter values sent as JS `String(value)` | Accepted |
| [004](ADR-004-retry-read-only-by-default.md) | Retry only read-only requests by default | Accepted |
| [005](ADR-005-response-cache-model.md) | Opt-in response cache keyed by operations, invalidated per worksheet | Accepted |
| [006](ADR-006-single-worker-request-queue.md) | Automatic queue: one worker, no schema ops, re-send alone on error | Accepted |
| [007](ADR-007-engine-script-lock-for-writes.md) | Engine takes a script lock for write requests | Accepted |
| [008](ADR-008-no-auth-across-redirect.md) | `Authorization` not forwarded across the redirect host | Accepted |
| [009](ADR-009-separate-android-module.md) | Android SQLite cache in a separate module, loaded by reflection | Accepted |
| [010](ADR-010-shared-sql-cache-base.md) | SQL stores share `SqlResponseCache`; `org.sqlite` isolated | Accepted |
| [011](ADR-011-rename-to-gridbase.md) | Java API renamed from `hibernatesheets` to `gridbase`, clean break | Accepted |
| [012](ADR-012-optional-columns-dropped-by-engine.md) | `@OptionalColumn` values are dropped by the engine when the sheet lacks the column | Accepted |
| [013](ADR-013-last-updated-from-drive.md) | Spreadsheet last-updated time comes from Drive metadata, never cached | Superseded by ADR-014 |
| [014](ADR-014-drive-opt-in.md) | Drive access for `lastUpdated()` is opt-in per deployment; the shipped manifest needs only Sheets | Accepted |
| [015](ADR-015-engine-short-lock-batched-io.md) | Engine holds the lock briefly, flushes before releasing it, and batches sheet I/O | Accepted |
| [016](ADR-016-staged-clear-worksheet.md) | CLEAR_WORKSHEET is staged and committed with the request's row changes | Superseded by ADR-017 |
| [017](ADR-017-replace-all-data-operation.md) | `REPLACE_ALL_DATA` operation replaces a worksheet's rows in one all-or-nothing step | Accepted |

## Template (`ADR-NNN-kebab-title.md`, keep it under about 60 lines)
```markdown
# ADR-NNN: Title
## Status
Proposed | Accepted | Superseded by ADR-M | Deprecated
## Date
YYYY-MM-DD
## Context
## Decision
## Alternatives considered
## Rationale
## Consequences
## Constraints (what future changes must respect)
## Related components
```
