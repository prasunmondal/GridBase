---
status: VERIFIED
last_verified: 2026-10-10
sources:
  - gridbase-android/
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/cache/CacheBackend.java
asserts:
  - { file: gridbase-android/src/main/resources/META-INF/proguard/gridbase-android.pro, contains: "AndroidSqliteResponseCache" }
---
# Android support

## Purpose
Android apps are a primary consumer. `sqlite-jdbc` needs `libsqlitejdbc.so`, which is often missing
on devices ("dlopen failed"). Android therefore gets either the platform's own SQLite (through
`gridbase-android`) or the pure-Java journal (ADR-009, ADR-010).

## Rules for `gridbase` (the core) on Android
- API 26 is enforced by animal-sniffer on both modules. Use `internal.Compat` / `internal.Log`
  instead of JDK 9+ library methods, `System.Logger` or `java.net.http`. Language features are fine
  because D8 desugars them (ADR-002).
- **The core never references `gridbase-android` classes.** `CacheBackend` loads
  `io.github.prasunmondal.gridbase.android.AndroidSqliteResponseCache` by name
  (`ANDROID_SQLITE_CLASS`) and calls `open(Path)` reflectively. Renaming or moving that class or its
  `open` method breaks Android caching silently: `AUTO` falls back to the journal.
- Android is detected through `java.vm.name == Dalvik` or a `java.vendor` containing "Android"
  (`CacheBackend.isAndroid`).

## `gridbase-android` module
- One class, `AndroidSqliteResponseCache extends SqlResponseCache`, on `android.database.sqlite`.
  It needs no `Context`, only a path.
- Compiled against `com.google.android:android:4.1.1.4` stubs (`provided`). The stubs throw "Stub!"
  when run, so the module **has no JVM tests**.
- Ships its own R8 keep rule at `META-INF/proguard/gridbase-android.pro`. It keeps
  `open(java.nio.file.Path)`, and must be updated with any rename.
- Consumers may exclude `org.xerial:sqlite-jdbc` (README "Android").

## On-device check (run after changing `SqlResponseCache`, `CacheBackend` or this module)
1. Use an app with `gridbase` + `gridbase-android`, `shallCache(true)`, minified with R8.
2. Logcat must not show "add io.github.prasunmondal:gridbase-android" or "SQLite cache unavailable".
3. Read twice: the second read is a hit (no pre-network hook fires).
4. Write to the worksheet, then read: the result is fresh (invalidation works).
5. Kill and restart the app, then read: a hit (the cache persisted).
6. Repeat without `gridbase-android`: expect the one-time warning and a working journal cache.

## Related
- `cache.md`, `build-release.md`, ADR-002, ADR-009, ADR-010
