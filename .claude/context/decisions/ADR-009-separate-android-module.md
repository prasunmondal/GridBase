# ADR-009: Android SQLite cache in a separate module, loaded by reflection

## Status
Accepted (recorded retroactively)

## Date
2026-10-08 (`576aa3b` "make android specific jar")

## Context
On Android, `sqlite-jdbc` often fails ("dlopen failed: library libsqlitejdbc.so not found"). The
platform has its own SQLite (`android.database.sqlite`), but the core artifact must stay a plain
JVM jar for non-Android users.

## Decision
- A new module and artifact, `gridbase-android`, holds `AndroidSqliteResponseCache` on the platform
  SQLite. It depends on `gridbase`, never the reverse.
- `CacheBackend` (in the core) opens it **by reflection** through
  `ANDROID_SQLITE_CLASS` + `open(Path)`. `AUTO` on Android picks it when present, and otherwise
  falls back to `JOURNAL` with a one-time warning.
- It is compiled against `com.google.android:android:4.1.1.4` stubs (`provided`) and ships an R8
  keep rule in `META-INF/proguard/gridbase-android.pro`.

## Alternatives considered
- Only the pure-Java journal: works, but keeps everything in memory.
- An Android AAR built with Gradle: a second build system.
- A core dependency on the Android API: impossible for JVM users.

## Rationale
It gives a disk-backed, native-free cache on every device, keeps the core platform-neutral, and stays
within one Maven build.

## Consequences
- The module cannot have JVM tests (the stubs throw), so it is verified on a device
  (`domains/android.md`).
- Renaming the class or `open` silently disables it (AUTO falls back), so the name is part of the
  contract.

## Constraints
- Keep `ANDROID_SQLITE_CLASS`, the class, `open(Path)` and the R8 rule in sync.
- Do not add an import of `gridbase-android` in `gridbase`.

## Related components
`gridbase-android/`, `cache/CacheBackend.java`, `domains/android.md`
