---
status: VERIFIED
last_verified: 2026-10-09
sources: [README.md, pom.xml]
asserts:
  - { file: pom.xml, contains: "<artifactId>gridbase-parent</artifactId>" }
---
# Product: GridBase / hibernate.sheets

## Purpose
A **public** Java/Android library that lets an application use Google Sheets as its database. The
application talks to a **self-hosted Apps Script engine** (the "hibernate.sheets" engine, `appscript/`)
through a typed, fluent API instead of hand-building JSON.

## Consumers
- Third parties, through Maven Central (`io.github.prasunmondal:gridbase`, `gridbase-android`) and
  JitPack (`com.github.prasunmondal.GridBase:*`).
- Both JVM apps and Android apps (API 26+). Android is a first-class target. It is the reason for the
  `gridbase-android` module, the journal cache and the animal-sniffer check.
- Each consumer deploys **their own copy** of the engine and redeploys it when they choose.

## Promises the code must keep (business rules)
1. **Compatibility with older engine deployments.** A newer SDK must keep working against an engine
   deployed before the latest fixes. This is why the SDK sends eq-family values as text (ADR-003). A
   feature that needs a newer engine must fail clearly against an older one.
2. **Android API 26 compatibility** of `gridbase` and `gridbase-android` (ADR-002).
3. **A small dependency footprint**: `jackson-databind`, `jackson-datatype-jsr310` and `sqlite-jdbc`.
   `sqlite-jdbc` is optional at runtime, and Android apps exclude it (ADR-010).
4. **Correct data before saving a network call.** Writes are not retried by default (ADR-004). The
   cache invalidates on every write, even a failed one, and a cache failure never fails a request
   (ADR-005).
5. **Public API stability.** Public types are everything outside `internal`. Changing them affects
   users, so it needs a version bump and a README update. A formal versioning policy (semver rules
   while the version is below 1.0) has **not been decided yet**, so ask before breaking one.

## Non-goals
- It is not an ORM with relations or joins. Each operation targets a single worksheet.
- There are no transactions across requests. Inside one request, row operations commit together
  (see `domains/engine.md`).
- There is no authentication in the engine yet (see `known-issues.md`).

## Glossary
| Term | Meaning |
|---|---|
| Engine | The Apps Script web app in `appscript/`, reached at its `/exec` URL |
| Spreadsheet / worksheet / tab | A Google file (`spreadsheetId`) / one sheet inside it (the engine calls it `worksheet`; `SheetProperties.tabName`) |
| Operation | One engine op (SELECT, INSERT, UPDATE, DELETE, UPSERT, CLONE, CREATE, CLEAR, ADD_COLUMNS, GET_COLUMNS), an immutable `spec.Operation` |
| Spec | A builder (`spec/*Spec`) that produces one Operation |
| Request | One HTTP POST carrying one or more Operations |
| Schema op | CREATE / CLEAR / ADD_COLUMNS: applied immediately, not part of the row commit |
| Combined call | Several callers' requests sent as one HTTP request (queues) |
| Slice | The part of a combined reply belonging to one caller (`ResponseParser.slice`) |
| Read-only request | A request containing only SELECT / GET_COLUMNS, so it is cacheable and retryable |

## Related
- `architecture.md`, `decisions/README.md`, README "Install" and "API tour"
