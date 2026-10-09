# ADR-002: Android API 26 compatibility and a minimal dependency set

## Status
Accepted (recorded retroactively)

## Date
2026-10-07 (`ec71737` "use HttpURLConnection for Android", `d1a6d72` "update logs for android")

## Context
Android apps are a main consumer. Android's runtime lacks `java.net.http`, `System.Logger` and many
JDK 9+ library methods (`List.of`, `Stream.toList()`, `String.isBlank()`). Every extra dependency
also adds size and conflict risk for public consumers.

## Decision
- Main code in `gridbase` and `gridbase-android` must run on **Android API 26**, enforced by
  animal-sniffer (`android-api-level-26`, phase `process-classes`) on both modules.
- HTTP uses `java.net.HttpURLConnection`. Logging uses `java.util.logging` through `internal.Log`.
  Missing JDK helpers go in `internal.Compat`.
- Runtime dependencies are limited to `jackson-databind`, `jackson-datatype-jsr310` and `sqlite-jdbc`
  (optional at runtime, see ADR-010).

## Alternatives considered
- `java.net.http.HttpClient`: not available on Android.
- OkHttp or SLF4J: extra dependencies for every consumer.

## Rationale
One artifact works on both the JVM and Android, and the build catches violations instead of a
device crash.

## Consequences
- Language features (records, `var`, pattern `instanceof`) are fine, because D8 desugars them.
  Library APIs are not.
- A little more hand-written code in `Compat`.

## Constraints
- Do not use JDK 9+ library methods in main code. Use `internal.Compat` / `internal.Log`.
- Adding a runtime dependency needs a new ADR.

## Related components
`internal/Compat.java`, `internal/Log.java`, `transport/HttpTransport.java`, root `pom.xml`
