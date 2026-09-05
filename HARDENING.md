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
- [x] Push the workflow to master.
- [ ] Require **Quality gate** in GitHub repository settings (not changed by this implementation).
- [x] Observe a successful hosted CI run: [master at 6e0962b](https://github.com/liveweird/toadie/actions/runs/33988678078),
  backend, frontend/contracts, browser journeys, and aggregate gate all passed.

Existing running demo containers are not automatically rebound by editing Compose. Apply
the new bindings at the next intentional recreation, preserving their database volume.

### Local verification of Stage 1 (2026-09-05)

The first hosted run exposed a setup-only failure: Temurin's exact version needs the full
`21.0.11+10.0.LTS` suffix, not `21.0.11+10`. The CI selector is corrected and a regression
test compares it with `mise.toml`. The subsequent master run linked above passed.

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
- [x] Prompt session invalidation on account deletion, password change, and privilege removal.
- [x] Session-family logout so superseded refresh tokens cannot outlive a logged-out session.
- [x] Reject MFA challenges issued before a credential/identity change; compare-and-set self
  password writes so overlapping requests cannot overwrite newer credentials.
- [ ] Regression coverage for revocation, reset-token replay/expiry, overlapping operations,
  and challenges issued before a credential change; update API, migrations, UI, EN/PL, and E2E.

These are intentional behavior changes, not merely refactors. Keep them in a separately
reviewable changeset. Inspect Lettuce before introducing shared capabilities, but do not
copy the reviewed weaknesses back into the implementation.

The first Stage 2 changeset adds V25 (`auth_sessions` + `users.auth_version`) and updates
the server, contract/generated types, self-password-change UI, EN/PL messages, regressions,
browser scenario, and Claude docs. Password, email, and role changes invalidate that
user's sessions; name-only/no-op profile edits preserve them. Applying V25 requires everyone to sign
in again; pre-migration tokens have no session family. Already-authorized requests may finish.
**Reset confirmation links are still pending**; the current emailed-password flow remains an
identified weakness, now with exact access/refresh revocation when the password is replaced.

### Local verification of the session changeset (2026-09-05)

- Full Gradle build, detekt, and coverage gates passed: 379 backend tests, no failures or
  skips; 97.75% line and 76.57% branch coverage. Coverage thresholds are unchanged.
- Frontend build, lint, knip, API drift/lint, and coverage gates passed: 668 tests across
  87 suites; 97.60% lines, 95.34% statements, 92.67% functions, 91.61% branches.
- Runtime OpenAPI conformance covers all 52 operations and 218 of 274 declared
  operation/status pairs. The last-admin regression now rejects the demoted actor's stale
  token instead of using it to reach a 409; the service's last-admin backstop remains tested.
  Spectral reports 0 errors, 2 registered-gap warnings, and 70 registered-gap hints.
- E2E type checking and all 25 spec/scenario pairs passed. The four focused auth/user
  journeys passed against the final rebuilt image without retries; an earlier build also
  passed those journeys twice (8 runs). The full 43-journey hosted result above is for
  committed Stage 1, not this session changeset.
- Verification used a separate Compose project, ports, and database volume. Its three
  containers, network, and disposable test-data volume were removed afterward; the existing
  development stack and database were preserved. At this verification checkpoint the changeset
  was not yet committed or deployed to that stack. V25 was verified on fresh test databases,
  not the development database.
- Read-only inspection of the active master rules found no required **Quality gate** status
  check. Repository settings were not changed; that Stage 1 task remains open.

### PR #2 verification follow-up

Hosted checks for the initial session commit found two blockers despite its local passes:
catalog-create tests exhausted their five-second budget, and an Errors accessibility scan
caught a labelled generic spinner while the report loaded (passing on retry still failed CI).
The follow-up uses user-event paste for unrelated editor fixture text while retaining keyboard
tests, and gives the shared spinner a named `status` role. A deterministic browser regression
holds the real report request and scans both pending and completed UI; it reproduced the exact
`aria-prohibited-attr` violation against the old image and passed twice without retries against
the fix. All 668 frontend tests and the build/lint/knip/API-drift gates passed locally;
coverage floors, test timeouts, axe waivers, and fail-on-flaky policy are unchanged. Successful
hosted verification of the follow-up is the merge criterion for PR #2, not local passes alone.
The slower Linux check also exposed a Labels modal teardown race; its mutation tests now wait
for the success UI to close the dialog, not merely for the request spy to be called.

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
