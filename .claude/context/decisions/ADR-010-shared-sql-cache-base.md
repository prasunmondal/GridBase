# ADR-010: SQL stores share `SqlResponseCache`; `org.sqlite` isolated

## Status
Accepted (recorded retroactively)

## Date
2026-10-08 (`e0acf6e` "add multiple caching stores", `576aa3b`)

## Context
There are two SQLite drivers (JDBC `sqlite-jdbc`, and Android's platform SQLite) for the same cache
schema and file format. Android apps want to exclude `sqlite-jdbc` entirely.

## Decision
- `cache.SqlResponseCache` owns **the schema and every SQL statement**. Drivers implement only its
  small `Sql` interface (`SqliteResponseCache`, `AndroidSqliteResponseCache`).
- **Nothing outside `cache/` references `SqliteResponseCache`**, so `org.sqlite` is loaded only when
  SQLite is actually chosen. A `LinkageError` under `AUTO` falls back to the journal.
- `sqlite-jdbc` stays a dependency of `gridbase` but is optional at runtime.

## Alternatives considered
- Duplicate the SQL in each driver: the two would drift and the file format would diverge.
- Make `sqlite-jdbc` `optional` in Maven: JVM users would then have to add it themselves.

## Rationale
One schema means one file format. Lazy class loading makes the exclusion safe.

## Consequences
- SQL changes are made once, in `SqlResponseCache`, and affect both stores. Android must be
  verified on a device.

## Constraints
- Do not put SQL in a driver class.
- Do not reference `SqliteResponseCache` or `org.sqlite` outside `cache/`.

## Related components
`cache/SqlResponseCache.java`, `cache/SqliteResponseCache.java`, `cache/CacheBackend.java`, `gridbase-android/`
