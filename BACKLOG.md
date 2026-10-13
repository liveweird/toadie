# Current backlog

This is Toadie's single actionable backlog. It contains open work only; completed changes live in
Git history, release notes, tests, and the current reference docs. The API guideline
[known-gaps register](api-guidelines/API-GUIDELINES.md#appendix-known-gaps-register) is the
canonical record of accepted API deviations, not a second implementation plan. Promote a gap
here when it becomes scheduled work, and remove its register entry only when it is resolved.

Each top-level item carries one status tag: `[next]`, `[todo]`, `[new]` (proposed, not yet
confirmed), `[parked]` or `[blocked]`. A proposed item enters as `[new]`; only the maintainer
promotes it. A finished item is removed, not ticked.

## Deployment

- [new] **Replace the ingress-nginx reference with a maintained Traefik deployment.** Inventory
  other workloads using the current controller before cutover. Verify TLS, HTTP-to-HTTPS
  redirects, HSTS, forwarded-header trust and client IPs, rate limits, body limits, health
  probes, and rollback against the replacement. Set the public `MAIL_APP_URL`, configure
  production SMTP if reset/MFA email is needed, and test email links through that origin.
  Update `k8s/templates/app-ingress.yaml` and the run-stack guide together; do not retire a
  controller used by another application.
- [new] **Deploy an immutable application image.** Publish each release to a registry the target
  cluster can reach, deploy by digest instead of `toadie-app:latest`, and document the update
  and rollback procedure. Verify that the running pod's digest is the reviewed release.
- [new] **Set production recovery targets and capacity from real measurements.** An operator must
  choose RPO/RTO, monitor PostgreSQL size and growth, set alerts, and confirm the backup
  schedule and retention against those targets. The 10 GiB initial claim and isolated restore
  drill are already implemented; the procedure is in [k8s/BACKUP-RESTORE.md](k8s/BACKUP-RESTORE.md).

## Scaling constraint

- [new] **Remove instance-local state before allowing multiple app replicas.** Every one of these
  is per-process: MFA challenges; login/reset throttles; the token-blocklist cache (it can delay
  cross-instance revocation); the `IntegrationLimits` admission semaphore, per-client buckets and
  the retained-value ledger (`integration/IntegrationLimits.kt`); the Ktor per-IP buckets
  (login, refresh, password-reset, MFA and integration); the jq quarantine set
  (`entities/JqCalculation.kt`); and the entity read ledger. Move or redesign those stores, test
  cross-replica behavior, and only then change `k8s/app-deployment.yaml` from one replica and `Recreate`.
  Until then, one replica is an intentional deployment rule, not a broken rollout.

## Security hardening

Found by the Flow project's audit of Toadie at `3d975a5` (2026-09-26); each was re-checked as
still open on 2026-10-14. Flow's and Lettuce's fixes are the reference to port.

- [new] **Drop the vulnerable FreeMarker from the build classpath.** `buildscript-gradle.lockfile`
  pins `org.freemarker:freemarker:2.3.32` (through Kover), which carries a path-traversal CVE.
  Build-time only, never in the runtime image. Covenant's fix: a root `buildscript` constraint on
  2.3.35 plus `./gradlew buildEnvironment --write-locks`.
- [new] **Scan dependencies for advisories in CI.** Add a job that runs Trivy over the Gradle
  lockfiles and fails on HIGH/CRITICAL, as Covenant and Flow do (it caught the FreeMarker CVE there).
- [new] **Verify Gradle dependencies.** Add `gradle/verification-metadata.xml` generated from an
  empty `GRADLE_USER_HOME`, run CI with `--dependency-verification strict`, and fail on lock or
  verification drift, following Lettuce's `quality.yml` and its dependency-reproducibility doc.

## Product

- [new] **Decide whether import dry runs should validate batch `sourceUrl` like real imports.**
  `/files/import/check`, `/blueprints/import/check`, and `/entities/import/check` currently
  ignore an invalid batch-level source URL that the corresponding import rejects. The
  published contract documents this difference. If changed, update the shared preflight,
  OpenAPI/generated types, and focused regressions together. This is a product-consistency
  improvement, not an authorization or data-integrity defect.
- [new] **Sample saved queries for the newer Port blueprints.** The Port demo ships no saved
  queries for `product`, `dataset`, `api_adoption` or `dataset_adoption`; add a few PUBLIC ones
  (sample data only, under `sample-data/port/commerce-payments/`) that show the adoption
  relations and the regulatory flags on the Entity graph and hierarchy canvases.

## Tests

- [new] **Click through a successful blueprint sync in the browser.** The e2e suite stops at the
  loopback refusal by design, since the SSRF guard rejects every address a local fixture server
  could use. A success journey needs a fixture source the guard accepts and the browser suite can
  reach; until then the server suite owns the success path.

## Housekeeping

- [new] **Align Temurin with the `21.0.12.1` respin (Dependabot #116).** The PR bumps both Docker
  stages to `eclipse-temurin:21.0.12.1_1`, but `mise.toml` and CI's `setup-java` must name the same
  build (`infrastructure.test.js` pins the parity) and mise does not list `21.0.12.1` yet. Once it
  does, push the `mise.toml` + `ci.yml` alignment onto the Dependabot branch.
- [new] **Re-check the MCP transport facts on the first MCP Kotlin SDK bump.** The SDK is pinned
  at 0.15.0; `integration/Mcp.kt` relies on its stateless mode needing no `initialize`, on the
  Accept-header pair and on unknown tools being tool results. Re-verify those with the real
  TypeScript MCP client before merging the bump.
