# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Java 17 Maven library (`io.github.prasunmondal:hibernate-sheets-client`) that is a typed client for
the **hibernate.sheets** Google Apps Script engine (Google Sheets used as a database). The engine
itself (the `appscript/` JS sources) lives in a separate repo; this repo only contains the SDK.
Runtime dependencies are deliberately limited to `jackson-databind` + `jackson-datatype-jsr310` +
`sqlite-jdbc` (response cache); HTTP uses the JDK `java.net.http.HttpClient`.

## Commands

```bash
mvn test                              # unit tests (no network)
mvn test -Dtest=RequestContractTest   # one test class
mvn test -Dtest=RequestContractTest#someMethod
mvn package                           # jar + sources jar
mvn install                           # into ~/.m2 for consuming projects
```

### Integration tests (`src/test/java/.../integrationTests/*IT.java`)

- There is **no failsafe plugin** in `pom.xml`, so `*IT` classes are not run by `mvn test` or
  `mvn verify` (despite what the `ItConfig` Javadoc says). Run them explicitly:
  `mvn test -Dtest=InsertIT` (or `-Dtest='*IT'`).
- They hit a **live** Apps Script deployment and spreadsheet by default (see `ItConfig`). Override with
  `-Dhs.endpoint=... -Dhs.spreadsheetId=... -Dhs.timeZone=...` or env vars
  `HS_ENDPOINT` / `HS_SPREADSHEET_ID` / `HS_TIME_ZONE`.
- They only touch worksheets prefixed `IT_`; `TestData.resetAll()` reseeds `IT_Employees` /
  `IT_Departments` before each test. `SchemaOperationsIT` leaves an `IT_Created_<timestamp>` sheet
  behind each run (the engine cannot delete worksheets).
- Local emulator: `node src/test/emulator/engine-emulator.js <path-to-appscript-dir> [port]` runs the
  real engine source in Node with in-memory Sheets; point tests at
  `-Dhs.endpoint=http://127.0.0.1:8765/macros/s/LOCAL/exec`. Needs the engine repo checked out.
- `integrationTests/Test1.java` matches surefire's default `Test*` include pattern, so it **does**
  run under plain `mvn test` and makes a live network call.

`KnownEngineIssuesIT` asserts the *buggy* engine behaviour on purpose (see "Engine issues" in
README.md). When an engine bug is fixed, the matching test fails; flip it to the "After the fix"
assertion in its comment and move it to the regular suite.

## Architecture

Request pipeline (nothing is sent until `execute()` / `fetch()`):

1. **Fluent API** — `Worksheet` (from `HibernateSheets.worksheet(name)`) and `mapping.Repository<T>`
   (from `db.repository(Entity.class)`, driven by `@SheetTable` / `@SheetKey` + Jackson property names).
   Alternatively an entity declares a `SheetProperties` constant (script URL, spreadsheet URL/id, tab,
   time zone, retry, timeouts, auth, pre/post network-call actions) and uses `PROPERTIES.repository(Entity.class)`;
   it owns a lazily built `HibernateSheets` client. See `integrationTests/Employee`.
2. **Specs** (`spec/*Spec`) — builders that each produce one immutable `spec.Operation` record, the
   transport-neutral description of an engine op. `UPDATE`/`DELETE` require `where(...)` unless `all()`.
3. **`internal.RequestSerializer`** — encodes operations into the exact JSON contract of the engine's
   `RequestParser.js`. `RequestContractTest` pins this contract; change both together.
4. **`HibernateSheets.execute(List<Operation>)`** — one HTTP request per call/batch, applies
   `RetryPolicy` (retries only read-only requests — SELECT / GET_COLUMNS — unless
   `retryingWrites(true)`; never retries daily-quota errors).
5. **`transport.Transport`** (single method; `HttpTransport` default) — POSTs to `/exec`, follows the
   Apps Script 302 to `script.googleusercontent.com` with a GET, and does not forward `Authorization`
   across hosts. Tests stub it with `FakeTransport`.
6. **`internal.ResponseParser`** — maps success to `result.*` types; `success:false` →
   `ServerException`; an HTML page instead of JSON → `TransportException`.

### Response cache (`cache/`)

Opt-in via `SheetProperties.shallCache(true)` (or `HibernateSheets.Builder.cache(...)`). In
`HibernateSheets.execute`, fully read-only requests are keyed by SHA-256 of the serialized operations
and the raw engine reply is stored in SQLite (`SqliteResponseCache`, one shared connection per file),
tagged with the worksheets read. Hits are re-parsed by `ResponseParser`, so POJO mapping is unchanged
and pre/post network actions don't fire. Any non-read-only request invalidates its worksheets (even if
it fails). Writes from clients **without** the same cache file are invisible until expiry — that's why
`TestData.resetAll()` invalidates `Employee`'s cache. `CacheExpiry` (ttl / dailyAt, combined with `or`)
sets freshness; `CacheStrategy.NETWORK_FIRST` falls back to stale entries only on transport/retryable
errors. Cache I/O failures are logged and never fail a request.

### Request queue (`RequestQueue`, package-private)

Opt-in via `SheetProperties.queueRequests(window)` / `HibernateSheets.Builder.requestQueue(...)`.
`execute` is built on `submit(ops, async)` returning `CompletableFuture`s; `execute` just `await`s and
rethrows the original exception. Without a queue, sync calls still run inline on the caller's thread.
With one, requests are collected for up to `window` and sent by a single daemon worker thread, one
HTTP call at a time (preserves order; avoids the engine's no-locking race). Combined replies are split
per caller with `ResponseParser.slice` (results renumbered from `op-1`), which is also what gets cached.
A non-retryable `ServerException` on a combined call → every request is re-sent alone (engine wrote no
row changes). Schema ops (CREATE/CLEAR/ADD_COLUMNS) are never queued because they apply immediately
and could not be re-sent safely. Calls made on the worker thread (from hooks) bypass the queue to
avoid deadlock; caller futures complete on the `hibernate-sheets-async` pool, never the worker.
`SheetProperties.toBuilder()` shares the parent's client (hence queue/cache) unless a setting other
than `tabName` changes — every other builder setter calls `detached()`.

`Batch` groups several specs into one request and hands back `Ref<T>` handles resolved after
`batch.execute()`. Engine semantics: row ops commit together at the end (all-or-nothing for row
changes), later ops see earlier ones, but `create` / `addColumns` / `clear` hit the sheet immediately.

### Value conversion (`internal.JsCompat`) — easy to break

The engine compares filters in JavaScript, so the SDK shapes values accordingly:
- `eq`/`ne`/`contains`/`startsWith`/`endsWith` → value sent as JS `String(value)` text (`5.0` → `"5"`);
  this also works around the engine bug where numeric EQUALS never matches.
- `gt`/`gte`/`lt`/`lte`/`between` → numbers as-is; `java.time` values as epoch millis.
- `in` → raw JSON values. `eq(col, null)` / `ne(col, null)` → `isNull` / `isNotNull`.
- Reading: empty cells arrive as `""` (typed getters/entity mapping → `null`); dates arrive as UTC
  instants and are converted to `LocalDate`/`LocalDateTime` using the client's configured `timeZone`.
