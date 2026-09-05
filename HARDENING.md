# Hardening plan

This is the implementation tracker for the review of revision `63d6fd4` (2026-09-05).
It is not a production-readiness certificate. Keep Claude's cross-cutting docs and focused
regression tests synchronized with each stage; do not lower coverage thresholds.

## Stage 1 — verification foundation

- [x] Automatic push/PR/merge-queue backend, frontend, contract, and browser gates.
- [x] Disposable CI database, diagnostics and cleanup independent of Playwright setup success.
- [x] CI fails flaky journeys and missing email-test infrastructure.
- [x] Loopback-only Compose demo ports, guarded by a regression test.
- [x] Honest OpenAPI 3.0.3; no validator-only relabeling; nullability regression test.
- [x] Pinned Spectral CLI, schema validation, and read-only generated-type drift check.
- [ ] Push the workflow and require **Quality gate** in GitHub repository settings.
- [ ] Observe a successful hosted CI run (local equivalents are not a hosted-run result).

Existing running demo containers are not automatically rebound by editing Compose. Apply
the new bindings at the next intentional recreation, preserving their database volume.

### Local verification of Stage 1 (2026-09-05)

- `./gradlew build :server:koverXmlReport --no-daemon`: passed, including detekt and
  coverage gates; 371 tests, no failures or skips. Testcontainers used OrbStack through a
  per-invocation `DOCKER_HOST`, not a repository-specific socket setting.
- Node 24 clean `npm ci --legacy-peer-deps`, API drift/lint, frontend build, lint, knip,
  and coverage: passed; 667 tests across 87 suites. Coverage: 97.59% lines, 95.33%
  statements, 92.67% functions, 91.61% branches. Knip retains its pre-existing generated-file
  configuration hint; no unused-code findings.
- API-DOC-002: the published dialect and nullability tests pass; regenerating frontend types
  produces no diff. Spectral: 0 errors, 2 registered-gap warnings, 70 registered-gap hints;
  the conformant reference fixture also passes. Runtime conformance exercises all 52
  operations (219 of 274 declared operation/status pairs).
- E2E TypeScript, 25 spec/scenario pairs, and discovery of all 43 browser tests: passed.
  Full browser journeys and hosted workflow execution have **not** been verified in this
  changeset; the running development stack and its data were left untouched.
- `docker compose config --format json` resolves all three published ports to `127.0.0.1`;
  infrastructure regression tests guard the bindings and workflow wiring. No containers
  were recreated and no Kubernetes resources were changed.

## Stage 2 — authentication lifecycle

- [ ] Single-use expiring reset links; do not change the password before mailbox confirmation.
- [ ] Prompt session invalidation on account deletion, password change, and privilege removal.
- [ ] Session-family logout so superseded refresh tokens cannot outlive a logged-out session.
- [ ] Regression coverage for revocation, reset-token replay/expiry, overlapping operations,
  and challenges issued before a credential change; update API, migrations, UI, EN/PL, and E2E.

These are intentional behavior changes, not merely refactors. Keep them in a separately
reviewable changeset. Inspect Lettuce before introducing shared capabilities, but do not
copy the reviewed weaknesses back into the implementation.

## Stage 3 — failure handling and concurrency

- [ ] Bound the entire outbound fetch, including DNS/body reads; stalled-body/cancellation tests.
- [ ] Bound YAML-diff cost with a size threshold/fallback; large valid document regression.
- [ ] Protect graph-layout initialization, serialize/coalesce saves, show failure/retry state.
- [ ] Make cross-category tag ownership concurrency-safe and test overlapping writes.
- [ ] Make catalog mutations and product-history events atomic; fault-injection regression.

## Stage 4 — deployment

- [ ] Replace ingress-nginx with Traefik; verify TLS, HSTS, redirects, forwarded-header trust,
  real client IPs/rate limits, upload limits, probes, and rollback.
- [ ] Right-size storage; document and exercise backup/restore without touching the dev volume.
- [ ] Adopt immutable release image references with an intentional update process.
- [ ] Keep one app replica until instance-local authentication state is addressed.

Controller installation/cutover is a cluster-level action: inventory existing workloads first
(Lettuce may share the controller), and do not remove a controller another app still needs.

## Stage 5 — selective refactoring

- [ ] Extract graph persistence into a dedicated hook as part of Stage 3.
- [ ] Split catalog service/form responsibilities where concrete changes repeatedly overlap.
- [ ] Add optimistic concurrency for catalog edits before relying on multi-user editing.

Do not introduce a generic registry framework or interfaces solely to satisfy a principle.
The modular-monolith architecture remains appropriate; the work is hardening, not a rewrite.
