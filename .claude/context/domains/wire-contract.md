---
status: VERIFIED
last_verified: 2026-10-10
sources:
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/internal/
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/spec/
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/transport/
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/RetryPolicy.java
  - appscript/backend/parser/
---
# Wire contract, value conversion, transport, retries

## Purpose
This is how an `Operation` becomes an HTTP request the engine understands, and how the reply comes
back. It is the most compatibility-sensitive area, because consumers run engines of different ages
(see `product.md` promise 1).

## Contract
- `spec/*Spec` → `spec.Operation` (an immutable record, transport-neutral).
- `internal.RequestSerializer` encodes the exact JSON that `appscript/backend/parser/RequestParser.js`
  reads. **`RequestContractTest` pins it: change the serializer, the parser and the test together.**
  `RequestValidator.js` checks predicates on the engine side.
- `internal.ResponseParser`: success → `result.*`; `success:false` → `ServerException` (engine
  message, JS type, stack, debug); an HTML page instead of JSON → `TransportException` naming the
  page title. `slice(body, offset, count)` cuts a combined reply per caller and renumbers results
  from `op-1`.

## Value conversion (`internal.JsCompat`), easy to break (ADR-003)
The engine compares filter values in JavaScript:
- `eq` / `ne` / `contains` / `startsWith` / `endsWith` send the value as JS `String(value)` text
  (`5.0` → `"5"`). This also works against old engines where numeric EQUALS never matched.
- `gt` / `gte` / `lt` / `lte` / `between` send numbers as-is and `java.time` values as epoch millis.
- `in` sends raw JSON values. `eq(col, null)` / `ne(col, null)` become `isNull` / `isNotNull`.
- Reading: empty cells arrive as `""`, which typed getters and entity mapping turn into `null`.
  Dates arrive as UTC instants and are converted to `LocalDate` / `LocalDateTime` with the client's
  `timeZone`.
Tests: `JsCompatTest`, `SelectQueryIT`.

## Transport (`transport/`)
- `Transport` has a single method. `HttpTransport` (on `HttpURLConnection`, ADR-002) POSTs to
  `/exec` and follows Apps Script's 302 to `script.googleusercontent.com` with a GET. It **never
  forwards `Authorization` to another host** (ADR-008).
- Auth is optional: an OAuth bearer token comes from a supplier. Deployments with "Anyone" access
  need none.
- Tests stub the transport with `FakeTransport`. `HttpTransportTest` runs the redirect flow against
  an in-process server.

## Retries (`RetryPolicy`, ADR-004)
- Only `isRetryable()` failures are retried: I/O errors, timeouts, HTTP 408/429/5xx, Apps Script
  "too many times" and lock-timeout errors. A **daily-quota error is never retried.**
- By default only read-only requests (SELECT / GET_COLUMNS) are retried. Writes are retried only
  with `retryingWrites(true)`, because a timed-out write may already be committed.

## Related
- `engine.md` (parser and predicates), `queueing.md` (slice), ADR-003, ADR-004, ADR-008
