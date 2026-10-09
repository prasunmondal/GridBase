---
paths:
  - "pom.xml"
  - "gridbase/pom.xml"
  - "gridbase-android/pom.xml"
  - "jitpack.yml"
  - "gridbase-android/src/main/resources/**"
---
# Build and release: invariants

- The version appears in 3 poms (the root `<version>` and each module's `<parent><version>`) and in
  the README "Install" snippets. Change them together. The lint checks the poms.
- animal-sniffer (Android API 26) must stay bound on **both** modules.
- Runtime dependencies are limited to jackson-databind, jackson-datatype-jsr310 and sqlite-jdbc.
  Anything new needs an ADR.
- `gridbase-android` depends on `gridbase`, never the reverse. The Android stubs stay `provided`.
- GPG signing runs at `verify`. Use `-Dgpg.skip` where the key `E017E18C9699E75C` is absent.
- Release checklist: `.claude/context/domains/build-release.md`.

→ `.claude/context/domains/build-release.md`
