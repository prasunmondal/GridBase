# CLAUDE.md

Guidance for Claude Code in this repository. This file is a **router**: details live in
`.claude/context/`, and path-scoped rules in `.claude/rules/` load by themselves when you open
matching files.

## What this is

**GridBase**: a public Java 17 library (Maven Central and JitPack) that is a typed client for the
**GridBase** Google Apps Script engine (Google Sheets used as a database). Multi-module
Maven build, parent `gridbase-parent` in the root `pom.xml`:
- `gridbase/`: the SDK, `io.github.prasunmondal:gridbase`
- `gridbase-android/`: `AndroidSqliteResponseCache` on the platform SQLite (no JVM tests, verify on a device)
- `appscript/`: the engine, the **single** source copy, deployed with clasp

## Hard constraints

- Main code must run on **Android API 26**. Use `internal.Compat` / `internal.Log`, not JDK 9+
  library methods, `System.Logger` or `java.net.http`. animal-sniffer fails the build otherwise.
- Runtime dependencies are only jackson-databind, jackson-datatype-jsr310 and sqlite-jdbc.
  sqlite-jdbc is optional at runtime and Android excludes it.
- `gridbase` never depends on `gridbase-android` (reflection only). Nothing outside `cache/`
  references `SqliteResponseCache`.
- `RequestSerializer` ⇄ `appscript/backend/parser/RequestParser.js` is one contract, pinned by
  `RequestContractTest`.
- Consumers run **their own, possibly old, engine deployments**. The SDK must keep working with
  them. Engine changes reach users only after a redeploy.
- Types outside `internal` are public API for third parties. Ask before breaking one.
- The version is in 3 poms (root + both modules' `<parent>`). Change them together.

## Commands

```bash
mvn test                                          # unit tests, both modules, no network
mvn test -pl gridbase -Dtest=RequestContractTest[#method]
mvn test -pl gridbase -Dtest='*IT'                # live integration tests (not run by mvn test/verify)
node gridbase/src/test/emulator/engine-emulator.js appscript   # local engine; ITs: -Dhs.endpoint=http://127.0.0.1:8765/macros/s/LOCAL/exec
mvn install -Dgpg.skip                            # into ~/.m2 (GPG signing runs at verify)
node scripts/knowledge/lint-context.js            # check the knowledge base against the repo
```

## Knowledge map (read on demand, start at `.claude/context/INDEX.md`)

| File | Holds |
|---|---|
| `product.md` | purpose, consumers, compatibility promises, glossary |
| `architecture.md` | modules, request pipeline, packages, threading |
| `domains/{client-api,wire-contract,engine,cache,queueing,android,build-release,testing}.md` | one per area |
| `known-issues.md` | open engine issues, debt, triage of the old engine review |
| `decisions/` | ADRs: **read the relevant ones before an architectural change** |
| `history/` | HISTORICAL. Do not load unless asked |

`README.md` is the user-facing documentation. Update it when public behaviour changes.

## Source of truth

1. The user's current instruction  2. Source code  3. Tests  4. poms and config  5. Accepted ADRs
6. `.claude/context/`  7. `history/` and README prose.
If knowledge contradicts the code, check the code, then fix the knowledge file (or mark it
`status: STALE`). If the code looks wrong, ask.

## Working protocol

**Start:** classify the task (domain, change type) → read the matching `INDEX.md` row's files and
ADRs, and nothing else → open the source → state the change scope (module → components → contract →
engine? → tests → README → release?) and stay inside it. Do not read every context file or unrelated
domains.

**Finish (only if files changed): knowledge check.** Did this change a business rule or public
promise, a module boundary or the architecture, the wire contract, engine behaviour, cache or queue
semantics, Android support, build or release, testing practice, or a known issue? Was an
architectural decision made?
- All no: leave `.claude/` alone. Renames, refactors and formatting are never recorded.
- Any yes: edit the affected section in place, add an ADR for a decision, set `last_verified` on
  files you checked, and run the lint.
