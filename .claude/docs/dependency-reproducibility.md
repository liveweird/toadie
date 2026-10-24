# Gradle dependency reproducibility

Ported from Lettuce and Flow (`.claude/docs/dependency-reproducibility.md` there). Two
complementary, fail-closed controls plus an advisory scan:

- **Dependency locking** pins the selected module versions. Project classpaths: the root
  `gradle.lockfile` (the Kover agent/reporter configurations), `core/gradle.lockfile`,
  `server/gradle.lockfile`. Plugin classpaths: `buildscript-gradle.lockfile` in the root, `core/` and
  `server/` (each project's `buildscript` block activates locking for its own `classpath`). The
  imported Ktor version catalog: `settings-gradle.lockfile`.
- **Dependency verification** checks the SHA-256 of every resolved external artifact and its POM /
  Gradle module metadata against `gradle/verification-metadata.xml`. Gradle enables it
  automatically whenever the file exists, so the Docker build's `./gradlew :server:installDist`
  is covered without a flag.
- **The Gradle advisory scan** (CI job `gradle-vulnerability-scan`, below) runs Trivy over every
  lockfile and fails on HIGH/CRITICAL.

Never run with `--dependency-verification lenient|off`, never add `trusted-artifacts` /
`ignored-keys` / component exclusions to hide a mismatch, and never accept a changed checksum for an
UNCHANGED coordinate as routine - treat it as a possible repository or cache integrity incident and
verify the publisher's artifact (its `.asc` signature, with the key fetched by fingerprint from a
keyserver rather than Maven Central).

## Rule: regenerate locks and checksums together

- **Applies when:** any change to `gradle/libs.versions.toml`, a `build.gradle.kts` dependency,
  constraint or plugin, or the Ktor catalog coordinate in `settings.gradle.kts`.
- **Requirement:** commit regenerated lockfiles AND `gradle/verification-metadata.xml` in the same
  change, produced by the command below from an EMPTY Gradle home.
- **Reference:** this document; `.github/workflows/ci.yml` (Backend job).
- **Enforcement:** CI builds with `--dependency-verification strict` (a missing or mismatched
  checksum fails) and `web/src/test/infrastructure.test.js` pins that wiring and that every
  `*gradle.lockfile` in the repo is in the CI tripwire, the Trivy copy list and the Dockerfile `COPY`.
  The `git diff --exit-code` step after the build is only a tripwire (a plain build never writes
  locks or checksums); the strict build is the proof.
- **Exception:** none.

## Regenerating after a dependency change

Use an EMPTY Gradle home so the metadata captures everything a fresh CI runner downloads (a warm
`~/.gradle` hides parent POMs and BOMs that a clean resolution needs), and name every task CI or
the Docker build resolves with (`build` for the compile/test/detekt/Kover classpaths,
`:server:koverXmlReport` for the Kover reporter, `:server:installDist` for the Docker build):

```bash
# Testcontainers needs a daemon; on OrbStack see .claude/docs/testing.md "OrbStack discovery".
export DOCKER_HOST=$(docker context inspect --format '{{.Endpoints.docker.Host}}')
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-locks --write-verification-metadata sha256 \
  build :server:koverXmlReport :server:installDist --no-daemon
```

Then prove it from a second empty home, in strict mode, with no write flags:

```bash
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --dependency-verification strict build :server:koverXmlReport :server:installDist --no-daemon
git diff --exit-code -- gradle/verification-metadata.xml gradle.lockfile settings-gradle.lockfile buildscript-gradle.lockfile core/gradle.lockfile core/buildscript-gradle.lockfile server/gradle.lockfile server/buildscript-gradle.lockfile
```

A clean pass there, not the generation run, is the real proof the metadata is complete. If a new
resolvable configuration appears (a new subproject, plugin, or task with its own classpath), add
its resolving task to the command, otherwise its artifacts never get a checksum and the first
clean CI build fails.

Review the generated diff: lockfile changes match the requested bump; checksum additions are
limited to the new coordinates; obsolete component entries are pruned in a focused review, not by
recreating the file from a warm cache.

The Ktor Gradle plugin constrains `commons-lang3` to the open range `[3.18.0,)`. Without the
`strictly("3.21.0")` constraint in `server/build.gradle.kts`'s `buildscript` block, the first
upstream release after the recorded checksum would fail every cold resolution (Docker build, fresh
clone, CI after its dynamic-version cache expires) under strict verification - Lettuce hit exactly
this on 2026-09-30. Move that constraint deliberately, together with a regeneration.

## Rule: advisory floors, never gate exceptions

- **Applies when:** the Gradle advisory scan reports a HIGH or CRITICAL advisory (it runs on every
  push/PR/merge group and on `workflow_dispatch`; a newly published advisory can turn it red with
  no code change - that is the point).
- **Requirement:** a flagged module that Toadie reaches only transitively gets a version FLOOR,
  never a gate exception: a `platform`/`constraints` entry in `server/build.gradle.kts` for the
  runtime/test classpaths (the `jackson-bom` platform, the `scram` constraints, the
  `netty-bom`/`reactor-bom` platforms), or a literal `buildscript` constraint for a plugin classpath
  (root: FreeMarker 2.3.35 for Kover's reporter; `server/`: Jackson 2.22.4, plexus-utils 4.1.0,
  log4j 2.26.1 for the Ktor plugin). Each carries a comment naming the advisory; buildscript blocks
  use literals because the version catalog does not exist there yet. Then regenerate as above.
- **Reference:** `.github/workflows/ci.yml` (`gradle-vulnerability-scan`), `gradle/libs.versions.toml`
  (the `jackson-bom`/`scram`/`netty`/`reactor-bom` notes).
- **Enforcement:** the CI job (Trivy pinned by tag AND digest, `--include-dev-deps`, which also fails
  if any expected lockfile is missing from Trivy's scanned targets) is part of the **Quality gate**;
  `:server:checkDependencyAlignment` (in `check`) fails when a floor leaves a Netty, Jackson or
  Reactor family on several versions, because `platform()` only floors.
- **Exception:** the scan reads lockfile coordinates only, so a library a jar SHADES is invisible
  to it and to every floor. Today: the PostgreSQL JDBC driver 42.7.14 (Flyway's boot connection)
  bundles scram-client 3.2 under `org/postgresql/shaded/` (CVE-2026-53712 is fixed only on the R2DBC
  path); the exposure needs TLS to PostgreSQL (the compose and Kubernetes stacks connect in plain
  text in-cluster). On every driver bump, look in the jar's `META-INF/licenses/com.ongres.scram/`
  folder and drop this note once it bundles >= 3.3.

Run the scan locally with the CI image: copy the lockfiles into a temp tree (`gradle.lockfile`,
`settings-gradle.lockfile`, `buildscript-gradle.lockfile`, and the `core/` and `server/` pairs) and
`docker run --rm -v <tree>:/scan:ro ghcr.io/aquasecurity/trivy:<tag>@sha256:<digest> fs --scanners vuln --include-dev-deps /scan`
(tag and digest from `ci.yml`).

## Dependabot

Dependabot edits `gradle/libs.versions.toml` only: expect NO lockfile updates in a Gradle bump PR
(not even the module lockfiles) and no `verification-metadata.xml` update. Every Gradle bump PR
therefore needs the regeneration above run on its branch by hand before the Quality gate can go
green. Flow takes the same documented-manual-step approach; Lettuce instead dropped Gradle from
Dependabot altogether and bumps through the same process on its own schedule. There is no fix-up
workflow here. The Netty and Reactor families are excluded from the grouped PR (they must move
together; `checkDependencyAlignment` fails otherwise). npm is different: the committed
`package-lock.json` plus `npm ci` governs, and Dependabot maintains it.
