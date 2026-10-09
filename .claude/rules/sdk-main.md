---
paths:
  - "gridbase/src/main/**"
  - "gridbase-android/src/main/**"
---
# SDK main code: invariants

- Must run on **Android API 26**. Do not use `List.of`, `Stream.toList()`, `String.isBlank()`, other
  JDK 9+ library methods, `System.Logger` or `java.net.http`. Use `internal.Compat` / `internal.Log`.
  animal-sniffer fails the build otherwise. Records, `var` and pattern `instanceof` are fine. (ADR-002)
- No new runtime dependency without an ADR. The allowed ones are jackson-databind, jackson-datatype-jsr310
  and sqlite-jdbc.
- `gridbase` must never import `gridbase-android`. The Android cache is loaded by reflection. (ADR-009)
- Nothing outside `cache/` references `SqliteResponseCache` or `org.sqlite`. (ADR-010)
- Nothing is sent before `execute()` / `fetch()`. Specs only build `Operation`s.
- `Repository` behaviour belongs in `Repository.Requests`. Sync methods just call `.execute()`.
- A new `SheetProperties.Builder` setter (other than `tabName`) must call `detached()`.
- Types outside `internal` are **public API** for third parties. Breaking one needs a version bump
  and a README update, so ask first.

→ `.claude/context/architecture.md`, `.claude/context/domains/client-api.md`
