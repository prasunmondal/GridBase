# ADR-011: Rename the Java API from hibernatesheets to gridbase (clean break)

## Status
Accepted

## Date
2026-10-10

## Context
The project was renamed from hibernate.sheets to GridBase, but users still saw the old name in
imports (`io.github.prasunmondal.hibernatesheets.*`), in the client class `HibernateSheets`, in
`HibernateSheetsException`, and in log lines, thread names and the default cache path. The Maven
coordinates (`io.github.prasunmondal:gridbase`) already used the new name.

## Decision
- Package `io.github.prasunmondal.hibernatesheets` → `io.github.prasunmondal.gridbase` (all
  subpackages, including `android`), `HibernateSheets` → `GridBase`, `HibernateSheetsException` →
  `GridBaseException`.
- User-visible strings use `gridbase`: log prefixes, threads `gridbase-async` / `gridbase-queue`,
  User-Agent `gridbase-sdk/...`, default cache `~/.gridbase/cache.db` (`<tmpdir>/gridbase/` on Android).
- **Clean break.** No deprecated aliases or forwarding package. The README has a migration table.
- Unchanged: the engine (`appscript/`) and the wire format, so old deployments keep working; the
  journal file magic `HSJ1`, so existing journal files can still be read.

## Alternatives considered
- Deprecated copies of the old types: about 60 public types duplicated, and the two packages' types
  would not be interchangeable anyway.
- Keeping the old package and renaming only the docs: users would still see the old name.

## Rationale
One name everywhere a user looks. The break costs consumers only a find/replace of imports.

## Consequences
- This is a breaking release for consumers' source code and for any ProGuard/R8 rules that name the
  old package.
- The old default cache file is left unused, so the first reads after upgrading go to the network.

## Constraints
- `CacheBackend.ANDROID_SQLITE_CLASS` and `gridbase-android.pro` name the new package (ADR-009).
- Do not reintroduce "hibernate" in public names, logs or paths.

## Related components
All of `gridbase/src/main/java/io/github/prasunmondal/gridbase/`, `gridbase-android/`, `README.md`
