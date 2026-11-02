---
status: VERIFIED
last_verified: 2026-11-02
sources: [pom.xml, gridbase/pom.xml, gridbase-android/pom.xml, jitpack.yml, code-sync/]
asserts:
  - { file: pom.xml, contains: "central-publishing-maven-plugin" }
  - { file: pom.xml, contains: "<keyname>E017E18C9699E75C</keyname>" }
---
# Build, release and developer workflow

## Build
- Maven multi-module: parent `gridbase-parent` (root `pom.xml`) → `gridbase`, `gridbase-android`.
  Java 17 (`jitpack.yml`: `openjdk17`).
- **animal-sniffer** (`android-api-level-26`, phase `process-classes`) runs on both modules and fails
  the build on API use not available on Android 26 (ADR-002).
- Sources and javadoc jars are attached. **`maven-gpg-plugin` signs at `verify` in the default build
  (no profile)**, so `mvn verify` / `mvn install` need the GPG key `E017E18C9699E75C`. Elsewhere,
  pass `-Dgpg.skip`. The root pom also has a `publish` profile (added in `4cc48e0`) that declares the
  same `maven-gpg-plugin` and `central-publishing-maven-plugin` again. It changes nothing while the
  default build has them, so `-Ppublish` is not needed.
- A Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) pins Maven 3.9.9. Plain `mvn` works too.
- Runtime dependencies are limited to `jackson-databind`, `jackson-datatype-jsr310` and `sqlite-jdbc`.
  Adding one is a public-facing decision that needs an ADR (see `product.md` promise 3).

## Version
The version lives in **three places**: the root `pom.xml` `<version>` and the `<parent><version>` of
`gridbase/pom.xml` and `gridbase-android/pom.xml`. Change all three together. The lint checks that
they agree. The README "Install" snippets repeat the version too.

## Release checklist
1. `node scripts/knowledge/lint-context.js` and `mvn test` both pass.
2. Bump the version in the 3 poms and the README "Install" snippets.
3. If the release depends on engine changes, say so in the README and make sure the SDK fails
   clearly against older engines (`product.md` promise 1).
4. Run `mvn deploy`. The `central-publishing-maven-plugin` (`autoPublish=true`, server id `central`)
   publishes the parent and both modules as one bundle to Maven Central.
5. Tag the commit. JitPack builds tags as `com.github.prasunmondal.GridBase:<module>:<tag>` (up to
   0.1.x it was `com.github.prasunmondal:GridBase:<tag>`).
6. Device check for `gridbase-android` if the cache or Android code changed (`android.md`).

Steps 4–5 are reconstructed from `pom.xml` and the README and are not yet confirmed by the
maintainer.

## Developer workflow: git-bundle sync (`code-sync/`)
The maintainer moves the repository between machines (`C:\Projects\GridBase` ⇄ `D:\Projects\GridBase`)
through **git bundles in a Google Drive folder**. Run `code-sync/sync.bat`, which asks Upload or Download
and calls `_internal.bat UPLOAD|DOWNLOAD` (named so that it is not mistaken for the entry point).
`config.bat` derives the repo (the folder above `code-sync`), the bundle name `<repo>.bundle`, the
upload folder (the repo's parent, outside the work tree), `%USERPROFILE%\Downloads` for downloads, and
the Drive folder URL. It can be copied into any repository unchanged. These are personal machine
settings, so do not "fix" them. The older copies under scripts/sync were removed in `03b8378`.

## Related
- `testing.md`, `android.md`, `known-issues.md`
