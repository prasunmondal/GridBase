# ADR-003: eq-family filter values sent as JS `String(value)`

## Status
Accepted (recorded retroactively)

## Date
2026-10-01 (`0698c8f` "fix engine errors")

## Context
The engine compares filter values in JavaScript. Older deployments compared
`String(cell) === expected`, so a JSON number (`5`) never matched. Consumers run their own
deployments and may never redeploy. Java renders numbers differently from JS (`5.0` vs `5`).

## Decision
`internal.JsCompat` shapes values per operator:
- `eq` / `ne` / `contains` / `startsWith` / `endsWith` → JS `String(value)` text (`5.0` → `"5"`)
- `gt` / `gte` / `lt` / `lte` / `between` → numbers as-is; `java.time` values → epoch millis
- `in` → raw JSON values; `eq` / `ne` with `null` → `isNull` / `isNotNull`

The engine was also fixed (`CompiledEqualsPredicate` compares with `String(expectedValue)`), but the
SDK does not rely on that fix.

## Alternatives considered
- Send raw JSON and rely on the engine fix: breaks every un-redeployed engine.
- Type-aware comparison in the engine (the engine-review suggestion): needs a redeploy, and changes
  semantics for existing users.

## Rationale
Correct results against **both** old and new engines, without a coordinated redeploy.

## Consequences
- Equality is textual: `"5"` and `5` are equal, `"05"` and `5` are not.
- `JsCompatTest` and `SelectQueryIT` pin this behaviour.

## Constraints
- A new operator must define its encoding in `JsCompat` and work against old engines, or fail
  clearly against them.
- Do not switch eq-family operators to raw values.

## Related components
`internal/JsCompat.java`, `internal/RequestSerializer.java`, `appscript/backend/execution/predicate/compiled/`
