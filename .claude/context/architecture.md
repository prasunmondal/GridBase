---
status: VERIFIED
last_verified: 2026-10-10
sources: [pom.xml, gridbase/pom.xml, gridbase-android/pom.xml, gridbase/src/main/java/io/github/prasunmondal/gridbase/GridBase.java]
asserts:
  - { file: pom.xml, contains: "<module>gridbase-android</module>" }
---
# Architecture

## Modules
| Path | Artifact | Role |
|---|---|---|
| `gridbase/` | `gridbase` | The SDK: API, specs, serializer/parser, transport, cache, queues |
| `gridbase-android/` | `gridbase-android` | `AndroidSqliteResponseCache` only. It depends on `gridbase`; **`gridbase` never depends on it** (it is loaded by reflection, ADR-009) |
| `appscript/` | (deployed with clasp) | The engine: the single source of truth for the server (ADR-001) |
| `scripts/sync/` | — | Developer tooling: git-bundle sync between machines (see `domains/build-release.md`) |

## Request pipeline (nothing is sent until `execute()` / `fetch()`)
```
Worksheet / Repository<T> / SheetProperties     fluent API, entity mapping          → domains/client-api.md
  spec/*Spec → spec.Operation (immutable)       transport-neutral op                 → domains/wire-contract.md
  GridBase.execute(List<Operation>)      cache lookup, queue, retry, hooks    → domains/cache.md, queueing.md
  internal.RequestSerializer → JSON             exact contract of RequestParser.js   → domains/wire-contract.md
  transport.Transport (HttpTransport)           POST /exec, 302 → GET                → domains/wire-contract.md
  engine: parse → validate → lock? → execute → commitAll                             → domains/engine.md
  internal.ResponseParser → result.* / ServerException / TransportException
```

## Packages (`gridbase/src/main/java/io/github/prasunmondal/gridbase/`)
| Package | Owns |
|---|---|
| root | `GridBase` (client + execution core), `SheetProperties`, `Worksheet`, `Batch`/`Ref`, `SheetRequest`/`APIRequestsQueue`/`Queued`, `RequestQueue` (package-private), `RetryPolicy`, `NetworkCall*` |
| `spec` | Operation builders + `Operation` record |
| `query` | `Filters`, `Filter`, `Operator`, `Sort` |
| `internal` | `RequestSerializer`, `ResponseParser`, `JsCompat`, `Json`, `Compat`, `Log`: not public API |
| `cache` | `ResponseCache` and stores, `CacheBackend`, `CacheExpiry`, `CacheStrategy` |
| `mapping` | `Repository<T>`, `@SheetTable`, `@SheetKey`, `EntityMetadata` |
| `result`, `exception`, `transport` | Reply types, exception hierarchy, HTTP |

## Cross-domain dependencies
- client-api → wire-contract (every call becomes Operations)
- cache, queueing → hook into `GridBase.execute` / `executeTogether` / `sendCombined`
- queueing → wire-contract (`ResponseParser.slice`) and cache (slices are what get cached)
- wire-contract ⇄ engine (the JSON contract; filter semantics run in JavaScript)
- android → cache (`SqlResponseCache` base, `CacheBackend` reflection)
- build-release → all modules (version in 3 poms, animal-sniffer on both modules)

## Threading model
- Without a request queue, synchronous calls run on the caller's thread.
- With `queueRequests(window)`: one daemon worker sends one HTTP call at a time, and caller futures
  complete on the `gridbase-async` pool, never on the worker (ADR-006).
- Cache stores are shared per file within the JVM and are thread-safe.
- In the engine, write requests take a script lock and reads do not (ADR-007).
