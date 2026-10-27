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
  log4j 2.26.1, Guava 33.7.2-jre for the Ktor plugin; the runtime/test Guava floor is the `guava`
  catalog constraint, which also lifts the `-android` test-compile resolution). Each carries a comment naming the advisory; buildscript blocks
  use literals because the version catalog does not exist there yet. Then regenerate as above.
- **Reference:** `.github/workflows/ci.yml` (`gradle-vulnerability-scan`), `gradle/libs.versions.toml`
  (the `jackson-bom`/`scram`/`guava`/`netty`/`reactor-bom` notes).
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

## Rule: the image's OS layer, never an ignore file

- **Applies when:** the CI `image` job ("Container image") reports a fixed HIGH or CRITICAL
  vulnerability in the OS packages of the amd64 runtime image (it runs on every push/PR/merge group
  and on `workflow_dispatch`; a newly published advisory can turn it red with no code change - the
  OS-layer twin of the advisory-floors rule above).
- **Requirement:** move the runtime stage to the newest CLEAN base image for the SAME Java update,
  a respin tag (`21.0.12.N_M`) included: a new digest of the same tag is often not enough. Find
  candidates with `docker buildx imagetools inspect eclipse-temurin:<tag>-jre` (take the multi-arch
  INDEX digest) or the Docker Hub tag list, and re-scan the candidate locally with the script's Trivy
  flags before pinning it.
- **Requirement:** a later update (`21.0.13.x`) is a `mise.toml` / toolchain decision first (the JDK
  stage, mise and CI move together), never a runtime-only jump. The JDK build stage stays on the
  mise/CI Java build; only the runtime JRE may run a same-update respin ahead of it.
- **Requirement:** never a `.trivyignore`, a severity downgrade or a skipped job.
- **Reference:** `.github/scripts/image-check.sh`, `.github/workflows/ci.yml` (`image`), the Dockerfile.
- **Enforcement:** the `image` job is part of the **Quality gate**; `web/src/test/infrastructure.test.js`
  pins that the script builds both platforms, scans with `--pkg-types os --ignore-unfixed
  --exit-code 1` on the Trivy command line itself, that its exit status is captured and re-raised, that a
  scan with no `os-pkgs` target or no report fails, that it never pushes, and that it uses the same Trivy
  pin as the Gradle advisory scan.
- **Exception:** the gap where Ubuntu has published a fix but no Temurin image carries it yet.
  `--ignore-unfixed` filters on the distro's fix status, not on what a Temurin digest contains, so
  every PR stays red until Temurin republishes: wait for the republish, then re-run the job. A
  temporary `apt-get install --only-upgrade <pkg>` in the runtime stage is allowed ONLY by the
  maintainer's explicit decision and is removed with the next clean base image. Never a
  `.trivyignore`, never a skipped job. Separately, a failed Trivy DB download turns the job red with
  no report: re-run it. Only the OS layer is scanned (`--pkg-types os`): the application's Java
  dependencies are covered by the Gradle advisory scan over the lockfiles.

Hand-maintained pins: Dependabot bumps the Dockerfile's base images, the workflows' `uses:` actions (commit SHA
plus tag comment), the k8s PostgreSQL (`docker` ecosystem over `/` and `/k8s`) and the compose and SSO-overlay
images (`docker-compose` ecosystem over `/` and `/e2e`, 2.32.4), all tag@digest, but NOT images named inside `run:` steps or `.github/scripts/image-check.sh`. Bump these three by hand, tag and digest together (the multi-arch
index digest from `docker buildx imagetools inspect <image>:<tag>`): `ghcr.io/aquasecurity/trivy`
(the same pin is in `ci.yml`'s Gradle advisory scan; the infrastructure test demands they match),
`moby/buildkit` (the buildx builder) and `tonistiigi/binfmt` (the QEMU installer).

## Rule: the Dependabot fix-up is computed without privilege and applied by a person

- **Applies when:** a Dependabot PR on the `gradle` ecosystem (head branch `dependabot/gradle/...`)
  needs its lockfiles and `gradle/verification-metadata.xml` regenerated.
- **Requirement:** the `dependabot-gradle-fixup` workflow COMPUTES the regeneration and a maintainer
  APPLIES it; no workflow ever writes to a branch.
  - The workflow runs on `pull_request` (a Dependabot run gets a read-only token and no Actions
    secrets) and `workflow_dispatch` only, with `permissions: contents: read`, no cache and no
    write step. It checks out the PR head, runs the two-empty-home procedure above (generate, then
    the strict proof from a second home, then a `cmp` tripwire) and uploads ONLY the eight data
    files plus `manifest.json` as artifact `gradle-fixup-<head sha>` (7 days).
  - `.github/scripts/dependabot-gradle-fixup.sh apply <pr>` downloads the artifact for the PR's
    exact head SHA, validates it with `.github/scripts/gradle-fixup-validate.mjs` and commits it
    with the maintainer's own credentials by a plain fast-forward push (a rejected push means the
    head moved and the artifact is stale: re-run, never force). The artifact is untrusted data:
    the job ran the bumped dependency's build.
  - The validator allows only the eight files and `manifest.json`, metadata ADDITIONS only (the
    `<configuration>` block byte-identical, no trust/key/alternate-hash constructs, one `sha256` per
    artifact, no duplicates, the head's lines all still present) and lockfile grammar. It REFUSES an
    artifact that contradicts the spec: no lockfile resolves a bumped module's declared version, or an
    entry the artifact moved lands on an undeclared one (and `settings-gradle.lockfile` must pin the
    Ktor catalog coordinate `settings.gradle.kts` declares). Verdict `OK` applies; `REVIEW` applies
    only with `--accept-review` after a person read the listed changes; `REFUSED` never applies.
    REVIEW is everything the validator cannot explain from the spec diff: lockfile changes outside the
    requested bump (transitive deltas a grouped PR normally has), downgrades, configuration-only
    moves, a changed `empty=` configuration set, a bumped module a file keeps on an undeclared version
    (reached only transitively, or stale) or loses the declared one in, and checksum additions outside
    the bump's groups that no lockfile line explains. It never prunes obsolete entries.
- **Reference:** `.github/workflows/dependabot-gradle-fixup.yml`,
  `.github/scripts/dependabot-gradle-fixup.sh`, `.github/scripts/gradle-fixup-validate.mjs`,
  `.github/dependabot.yml` (`rebase-strategy: disabled`: a Dependabot rebase would force-push over
  the fix-up commit; use `gh pr update-branch` or `@dependabot recreate`, then re-run the fix-up).
- **Enforcement:** `web/src/test/infrastructure.test.js` pins the workflow's triggers, read-only
  permissions, absence of secrets/write commands/caches, the job guards, the head checkout, the two
  distinct Gradle homes, the strict proof and the artifact retention, plus the script's syntax and
  the Dependabot setting; `web/src/test/gradleFixupValidate.test.js` pins each refusal. The workflow
  is NOT a required check and not a **Quality gate** dependency; the strict CI build on the applied
  commit remains the proof.
- **Exception:** a bump that needs a human decision (paired versions such as the OpenTelemetry SDK
  and its Ktor instrumentation, an advisory floor) is edited by hand first. A Gradle wrapper bump that
  changes the metadata schema line fails closed (REFUSED, the frame around `<components>` must equal the
  head's): use `local`, or regenerate by hand. The script's `local <pr>` mode is the fallback when the
  workflow cannot run: it executes the two documented commands above verbatim, each from its own empty
  Gradle home (two separate directories under one temp directory, removed afterwards), in a detached
  worktree at the PR head, then takes the same validate, commit and push path. **`local` runs the bumped
  dependency's build on the maintainer's machine, with their credentials, SSH keys and Docker socket**;
  that build can tamper with the validator or the git configuration before validation, so the validator
  is no boundary in that mode (the manual procedure above carries the same exposure). Use it for a PR you
  trust; the workflow path keeps the build off your machine.

## Dependabot

What a Gradle PR contains: Dependabot edits `gradle/libs.versions.toml`, `settings.gradle.kts` (the
Ktor catalog coordinate), the `"group:name:version"` literals in the kts scripts and the Gradle
wrapper (with its `distributionSha256Sum` and `gradlew.bat`), and SOMETIMES `core/gradle.lockfile`
and `server/gradle.lockfile`. It never edits `settings-gradle.lockfile`, any
`buildscript-gradle.lockfile` or `gradle/verification-metadata.xml`, and a grouped PR normally also
needs transitive lockfile deltas. Every Gradle bump PR therefore needs the regeneration above before
the Quality gate can go green; the fix-up rule above is how it gets there (Flow documents the manual
step only; Lettuce dropped Gradle from Dependabot altogether). The Netty and Reactor families are
excluded from the grouped PR (they must move together; `checkDependencyAlignment` fails
otherwise). npm is different: the committed `package-lock.json` plus `npm ci` governs, and
Dependabot maintains it.
