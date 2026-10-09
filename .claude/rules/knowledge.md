---
paths:
  - ".claude/**"
  - "CLAUDE.md"
---
# Editing the knowledge base

- **Record intent, constraints, reasons and pointers to code.** Never copy method lists or
  signatures that the code already shows.
- Context files keep their frontmatter: `status` (VERIFIED | NEEDS_REVIEW | STALE | HISTORICAL),
  `last_verified`, `sources`, and optional `asserts`. Set `last_verified` only after actually checking
  the content against the code.
- If a file contradicts the code, fix the file or mark it `STALE`. Never leave it silently wrong.
- Budgets: CLAUDE.md ≤ 90 lines, each rule ≤ 35 lines, each context file ≤ 150 lines. Split a file
  rather than exceed its budget.
- Every rule needs a `paths:` list. Without one it loads in every session.
- ADRs are never rewritten. Supersede them with a new ADR and update `decisions/README.md`.
- User-facing usage belongs in `README.md`. Link to it, do not copy it.
- Run `node scripts/knowledge/lint-context.js` after editing.
