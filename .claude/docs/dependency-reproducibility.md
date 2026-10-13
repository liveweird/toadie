# Gradle dependency reproducibility

Ported from Lettuce and Flow (`.claude/docs/dependency-reproducibility.md` there). Two
complementary, fail-closed controls plus an advisory scan:

- **Dependency locking** pins the selected module versions: the root `gradle.lockfile` (the Kover
  agent/reporter configurations), `core/gradle.lockfile`,
  `server/gradle.lockfile`, the root `buildscript-gradle.lockfile` (the root plugin classpath —
  Kover and its reporter) and `settings-gradle.lockfile` (the imported Ktor version catalog).
- **Dependency verification** checks the SHA-256 of every resolved external artifact and its POM /
  Gradle module metadata against `gradle/verification-metadata.xml`. Gradle enables it
  automatically whenever the file exists, so the Docker build's `./gradlew :server:installDist`
  is covered without a flag.
- **The Gradle advisory scan** (CI job `gradle-vulnerability-scan`, `.claude/docs/testing.md`
  "Gradle advisory scan") runs Trivy over the lockfiles and fails on HIGH/CRITICAL.

**Applies when / Requirement / Enforcement.** Any change to `gradle/libs.versions.toml`, a
`build.gradle.kts` dependency or constraint, a plugin, or the Ktor catalog coordinate must commit
regenerated lockfiles AND verification metadata together. Enforcement: CI builds with
`--dependency-verification strict` and then `git diff --exit-code` over the six files; the
infrastructure test pins that wiring. Never run with `--dependency-verification lenient|off`, never
add `trusted-artifacts` / `ignored-keys` / component exclusions to hide a mismatch, and never accept a
changed checksum for an UNCHANGED coordinate as routine — treat it as a possible repository or
cache integrity incident and verify the publisher's artifact (its `.asc` signature, with the key
fetched by fingerprint from a keyserver rather than Maven Central).

## Regenerating after a dependency change

Use an EMPTY Gradle home so the metadata captures everything a fresh CI runner downloads (a warm
`~/.gradle` hides parent POMs and BOMs that a clean resolution needs), and name every task CI or
the Docker build resolves with (`build` for the compile/test/detekt/Kover classpaths,
`:server:koverXmlReport` for the Kover reporter, `:server:installDist` for the Docker build):

```bash
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock   # Testcontainers, OrbStack
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-locks --write-verification-metadata sha256 \
  build :server:koverXmlReport :server:installDist --no-daemon
```

Then prove it from a second empty home, in strict mode, with no write flags:

```bash
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --dependency-verification strict build :server:koverXmlReport :server:installDist --no-daemon
git diff --exit-code -- gradle/verification-metadata.xml gradle.lockfile settings-gradle.lockfile buildscript-gradle.lockfile core/gradle.lockfile server/gradle.lockfile
```

A clean pass there, not the generation run, is the real proof the metadata is complete. If a new
resolvable configuration appears (a new subproject, plugin, or task with its own classpath), add
its resolving task to the command, otherwise its artifacts never get a checksum and the first
clean CI build fails.

Review the generated diff: lockfile changes match the requested bump; checksum additions are
limited to the new coordinates; obsolete component entries are pruned in a focused review, not by
recreating the file from a warm cache.

## Advisory floors

A flagged module that Toadie reaches only transitively gets a version floor, never a gate
exception: the `jackson-bom` platform, the `scram` constraints and the `netty`/`reactor` BOM
alignment in `server/build.gradle.kts` (notes in `gradle/libs.versions.toml`), or the literal
`buildscript` constraint in the root `build.gradle.kts` for the plugin classpath (FreeMarker 2.3.35,
Kover's reporter). The version catalog is not visible inside a `buildscript` block, hence the
literals. The Ktor Gradle plugin's own classpath (`server/build.gradle.kts` plugins block) is not
locked and not scanned today.

## Dependabot

Dependabot regenerates the module lockfiles but NOT `settings-gradle.lockfile` and NOT
`gradle/verification-metadata.xml`. Every Gradle bump PR therefore needs the regeneration above
run on its branch by hand before the Quality gate can go green (Lettuce and Flow take the same
documented-manual-step approach; there is no fix-up workflow). npm ranges are different: the
committed `package-lock.json` plus `npm ci` governs, and Dependabot maintains it.
