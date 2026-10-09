---
status: VERIFIED
last_verified: 2026-10-09
---
# Knowledge index

Read **one row**, then only the files it names. Rules in `.claude/rules/` load automatically when you
open matching files.

| Task mentions… | Read | Auto-loaded rule | Key sources | ADRs |
|---|---|---|---|---|
| Worksheet, Repository, entity, `SheetProperties`, Batch, hooks | `domains/client-api.md` | sdk-main | `HibernateSheets.java`, `SheetProperties.java`, `mapping/` | — |
| filter, operator, JSON, serializer, parser, value types, dates, HTTP, redirect, auth token, retry | `domains/wire-contract.md` | wire-contract | `internal/`, `spec/`, `query/`, `transport/`, `RetryPolicy.java` | 003, 004, 008 |
| engine, Apps Script, deploy, lock, predicate, `.js` | `domains/engine.md` | engine | `appscript/backend/` | 001, 007 |
| cache, expiry, stale, invalidation, SQLite, journal | `domains/cache.md` | cache | `cache/`, `HibernateSheets.java` | 005, 010 |
| Android, R8, `gridbase-android`, dlopen | `domains/android.md` | cache, sdk-main | `gridbase-android/`, `CacheBackend.java` | 002, 009, 010 |
| queue, async, combine, `executeAsync`, `APIRequestsQueue` | `domains/queueing.md` | queueing | `RequestQueue.java`, `APIRequestsQueue.java`, `HibernateSheets.java` | 006 |
| build, version, release, publish, JitPack, GPG, bundle sync | `domains/build-release.md` | build-release | `pom.xml` ×3, `jitpack.yml`, `scripts/sync/` | 002 |
| tests, IT, emulator, fake engine | `domains/testing.md` | tests | `gridbase/src/test/` | — |
| purpose, consumers, compatibility promise, terminology | `product.md` | — | `README.md` | — |
| where things are, module boundaries, threading | `architecture.md` | — | poms | 009 |
| bug triage, debt, "is X a known problem" | `known-issues.md` | — | — | — |
| why was X decided | `decisions/README.md` | — | — | all |

`history/` is HISTORICAL. Read it only when asked about the past.

## Domain dependencies
```
client-api ──► wire-contract ◄──► engine
     │              ▲
     ▼              │ slice
   cache ◄──── queueing
     ▲
  android            build-release ─► (all modules)
```
For a change that crosses domains, read each affected domain file and the ADRs listed for it, and
nothing else.
