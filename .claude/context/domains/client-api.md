---
status: VERIFIED
last_verified: 2026-10-10
sources:
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/SheetProperties.java
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/Worksheet.java
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/Batch.java
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/GridBaseTable.java
  - gridbase/src/main/java/io/github/prasunmondal/gridbase/mapping/
---
# Client API

## Purpose
This is the public surface that users code against. The README "API tour", "Entities",
"`SheetProperties`" and "Network hooks" sections are the user documentation. This file covers only
intent and invariants.

## Entry points
- `GridBase.builder()...build()` → `db.worksheet(name)` gives a `Worksheet`, which offers
  untyped `Row` access through fluent specs.
- `db.repository(Entity.class)` gives a `mapping.Repository<T>`. It is driven by `@SheetTable` /
  `@SheetKey` plus Jackson property names (`EntityMetadata`).
- **`SheetProperties`**: one constant per entity that holds the script URL, the spreadsheet URL or
  id, the tab, the time zone, retries, timeouts, auth, cache, queue and pre/post network-call
  actions. `PROPERTIES.repository(Entity.class)` uses a lazily built, owned `GridBase`. The
  reference example is `integrationTests/Employee.java`.
- **`GridBaseTable<T>`**: optional base class (`object X : GridBaseTable<E>(props, E::class.java)`)
  that supplies `properties()` / `repository()` / `worksheet()`. Its methods are `final` and few on
  purpose: every method added later can clash with names in consumers' subclasses. The constructor
  must stay side-effect free (Kotlin `object` init order); the repository is built lazily.

## Invariants
- Nothing is sent until `execute()` / `fetch()`. Specs only build Operations.
- `UPDATE` / `DELETE` require `where(...)` unless `all()` is called, as a guard against
  accidentally rewriting the whole sheet.
- **`Repository` builds every operation as a `SheetRequest` in `Repository.Requests`.** The sync
  methods just call `.execute()`, so behaviour is changed there, once, and never duplicated in the
  sync path.
- `SheetProperties.toBuilder()` **shares the parent's client** (and therefore its queue and cache)
  unless a setting other than `tabName` changes. Every other builder setter calls `detached()`, and a
  new setter must do the same.
- Pre/post network-call actions run around every HTTP **attempt**. They do not fire on cache hits.
- Reading: empty cells become `null` in typed getters and entities. Dates are converted with the
  client's `timeZone` (see `wire-contract.md`).

## Batch
`Batch` groups several specs into one request and returns `Ref<T>` handles that resolve after
`batch.execute()`. Engine semantics: row operations commit together at the end, later operations see
earlier ones, and schema operations hit the sheet immediately (see `engine.md`).

## Related
- `wire-contract.md` (how specs become JSON), `cache.md`, `queueing.md`
- Tests: `RepositoryTest`, `SheetPropertiesTest`, `integrationTests/RepositoryIT`
