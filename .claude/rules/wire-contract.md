---
paths:
  - "gridbase/src/main/java/io/github/prasunmondal/gridbase/internal/**"
  - "gridbase/src/main/java/io/github/prasunmondal/gridbase/spec/**"
  - "gridbase/src/main/java/io/github/prasunmondal/gridbase/query/**"
  - "gridbase/src/main/java/io/github/prasunmondal/gridbase/transport/**"
  - "gridbase/src/main/java/io/github/prasunmondal/gridbase/RetryPolicy.java"
  - "appscript/backend/parser/**"
  - "**/RequestContractTest.java"
  - "**/JsCompatTest.java"
---
# Wire contract: invariants

- `RequestSerializer` ⇄ `appscript/backend/parser/RequestParser.js` is one contract, pinned by
  `RequestContractTest`. Change all three together.
- The SDK must keep working against **older engine deployments**. A new feature that needs a newer
  engine must fail clearly against an old one, and the README must say so.
- `JsCompat` encodings (ADR-003): eq/ne/contains/startsWith/endsWith → JS `String(value)` text;
  gt/gte/lt/lte/between → number, or epoch millis for `java.time`; `in` → raw JSON;
  `eq/ne(null)` → isNull/isNotNull. Do not switch eq-family operators to raw values.
- Retries: only `isRetryable()` failures, only read-only requests unless `retryingWrites(true)`,
  never daily-quota errors (ADR-004). Set `retryable` correctly on any new exception path.
- `HttpTransport` never sends `Authorization` to a different host (ADR-008).
- `ResponseParser`: an HTML reply → `TransportException`; `success:false` → `ServerException`.

→ `.claude/context/domains/wire-contract.md`
