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

- [x] Single-use expiring reset links; do not change the password before mailbox confirmation.
- [x] Prompt session invalidation on account deletion, password change, and privilege removal.
- [x] Session-family logout so superseded refresh tokens cannot outlive a logged-out session.
- [x] Reject MFA challenges issued before a credential/identity change; compare-and-set self
  password writes so overlapping requests cannot overwrite newer credentials.
- [x] Regression coverage for revocation, reset-token replay/expiry, overlapping operations,
  and challenges issued before a credential change; update API, migrations, UI, EN/PL, and E2E.

These are intentional behavior changes, not merely refactors. Keep them in a separately
reviewable changeset. Inspect Lettuce before introducing shared capabilities, but do not
copy the reviewed weaknesses back into the implementation.

The first Stage 2 changeset adds V25 (`auth_sessions` + `users.auth_version`) and updates
the server, contract/generated types, self-password-change UI, EN/PL messages, regressions,
browser scenario, and Claude docs. Password, email, and role changes invalidate that
user's sessions; name-only/no-op profile edits preserve them. Applying V25 requires everyone to sign
in again; pre-migration tokens have no session family. Already-authorized requests may finish.
The second Stage 2 changeset replaces emailed passwords with single-use confirmation links
(V26). The request preserves credentials and sessions; only atomic confirmation consumes the
grant and increments the credential epoch. Mail origin is configured, not derived from Host;
reset tokens are never stored raw in PostgreSQL or browser storage. The existing Lettuce mail,
localization, and throttle primitives are reused; its current emailed-password design was
inspected and intentionally not copied. V26 is additive schema and does not itself force logout.

**Compatibility decision (API-VER-001/002):** the request's JSON/status contract remains unchanged,
but the email workflow deliberately changes within `/api/v1` as a security remediation. This
is a behavior-breaking exception to the additive-only guideline, not a claim of full compatibility.
Deploy server and SPA together, set `MAIL_APP_URL` to the external origin (HTTPS in production),
and tell users that new emails contain links, not passwords. Old generated passwords remain
ordinary credentials until changed; no insecure legacy reset endpoint is retained.

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

### Local verification of the reset-link changeset

- Full Gradle build, detekt, and unchanged coverage gates passed: **387 tests**, no failures
  or skips; **97.82% lines, 76.91% branches**. Expiry uses an injected clock; race tests use
  separate services against the same PostgreSQL database.
- Frontend build, lint, knip, contract drift/lint, and unchanged coverage gates passed:
  **680 tests / 88 suites**; 97.63% lines, 95.36% statements, 92.62% functions, 91.68% branches.
- Full browser suite: **45 passed**, no retries/skips, against a separate Compose image,
  port 8091, Mailpit 8036, and the disposable `toadie-reset-verify_postgres-data` volume.
  The reset journey checks preserved credentials on request/GET, signed-in confirmation,
  session revocation, chosen-password login, original-password rejection, and replay recovery.
  Desktop/mobile screenshots and axe covered the confirmation and missing-link states;
  the existing theme-wide contrast waiver is unchanged.
- API review: [two-pass reset review](api-guidelines/reviews/password-reset.md), including
  the explicit workflow-versioning and header-only-credential-wording deviations. Spectral:
  0 errors, 2 registered-gap warnings, 71 registered-gap hints. Runtime conformance covers
  53/53 operations and 222/279 operation/status pairs; no fuzzing run is claimed.
- V26 was applied only to Testcontainers/disposable verification databases. The regular
  port-8081 app, its volume, and Kubernetes resources were not changed by this batch.
  The disposable containers/network/volume were removed after verification (test data can
  be regenerated). The regular stack remains healthy on V25 with 155 users and 400 catalog files.
  These results were recorded before committing or deploying to the regular stack.

### Post-merge CI follow-up: Lenses dialog entrance (2026-09-06)

The [master run after PR #3](https://github.com/liveweird/toadie/actions/runs/33994155779)
failed the no-flaky gate: Lenses passed on retry after its second file-cleanup click missed
an entering confirmation button. The trace records a click at y≈397, the final button at
y≈427, no corresponding DELETE request, and ~37 seconds still available; it was not a slow
server DELETE or an exhausted journey budget. Backend, frontend, and reset-link journeys passed.

The focused follow-up adds `readyDialog` (computed opacity one), an entrance-hold regression,
exact resource/status response checks, and modal/row completion waits. App behavior, animations,
timeouts, retries, and coverage gates are unchanged. This does not mark the historical hosted
failure green or start the outbound-fetch work.

Verification against a disposable Compose stack using the unchanged merged app image:

- Ten repetitions each of Lenses and the entrance regression: **20 passed**, retries disabled.
- Full browser suite with four workers: **46 passed**, retries disabled.
- Negative control with a visibility-only helper: the regression failed on its readiness
  assertion as expected; the repository helper was not modified for this experiment.
- E2E type-check and scenario parity: passed (**26** spec/scenario pairs).
- Removed only the disposable verification containers/network/volume; its generated test data
  can be recreated. The regular app remained healthy with **155 users / 400 catalog files**.
  These are local pre-commit results; hosted CI has not yet run with this follow-up.

## Stage 3 — failure handling and concurrency

- [x] Bound the outbound caller's total wait, including DNS/body reads; stalled-body/cancellation tests.
- [ ] Separately evaluate connection-time DNS containment / address pinning: the JDK transport
  may resolve again after the public-host check, and native resolution can outlive cancellation.
  A rejecting bounded JDK executor is not a safe shortcut (selector rejection aborts the client).
- [x] Bound YAML-diff cost with a size threshold/fallback; large valid document regression.
- [ ] Protect graph-layout initialization, serialize/coalesce saves, show failure/retry state.
- [ ] Make cross-category tag ownership concurrency-safe and test overlapping writes.
- [ ] Make catalog mutations and product-history events atomic; fault-injection regression.

### Outbound-fetch deadline batch (2026-09-06)

`CatalogUrlFetcher` now uses one ten-second caller deadline spanning validation, DNS guard,
connection/headers, and complete body consumption. A bounded asynchronous subscriber keeps the
one-megabyte ceiling; failed HTTP statuses do not wait for their bodies. Timeout, connection/body
failure, and validation-capacity exhaustion preserve the safe 502 contract. Parent cancellation
propagates unchanged and requests cancellation of both HTTP exchange and body subscription.

Initial validation has four daemon workers and sixteen queue slots; cancelled queued tasks are
removed. A held native resolver cannot hold its caller or start a late request after cancellation.
Native resolution can still outlive interruption, and the JDK may perform a second lookup outside
that pool. **Not solved:** full transport-DNS containment and DNS rebinding. An attempted bounded
JDK executor was rejected during review because selector-side task rejection aborts the shared
client; no such executor is shipped. No dependencies, migrations, redirects, or SSRF allowances
were added. Lettuce was inspected and has no equivalent URL fetcher to port.

Verification:

- `./gradlew build :server:koverXmlReport :server:installDist`: passed, **394 tests**, no
  failures/skips and zero detekt findings. Kover: **97.69% lines / 76.75% branches**, above
  the unchanged 97% / 76% floors.
- Focused `UrlFetchTest`: **15 passed**, then **three more complete runs (45 passed)** with
  `--rerun-tasks`, without retries or failures. Full-suite coverage/count evidence was preserved
  before these focused runs replaced the local test report.
- Frontend build, API generation/drift check, and Spectral: passed. Spectral retains its
  **2 registered warnings / 71 hints**, with **0 errors**; no contract shape or status changed.
- Built a fresh isolated Compose image and ran the full browser suite with four workers and
  retries disabled: **46 passed**, including URL-import refusal and source-sync journeys.
- Independent review: no actionable findings in the final diff; **23 applicable API checks
  passed**. The pre-existing `/fetch` verb naming exception (`API-RES-003`) and registered
  global gaps are unchanged. The narrow cancellation-versus-subscription callback race is
  supported by JDK source inspection and integration coverage, not a dedicated deterministic
  callback-level test.

Removed only the disposable verification containers/network/volume afterwards; generated test
data can be recreated. The normal stack remained stopped and its volume was not touched;
Kubernetes was not changed. These are pre-commit local results, not hosted CI results.

### YAML-diff budget batch (2026-09-06)

Starting state verified: clean `master` at `d865aa0`, displayed version **1.22.1**, PR #5
merged, and its [master CI run](https://github.com/liveweird/toadie/actions/runs/34026699079)
successful. The existing Compose app, PostgreSQL, and Mailpit were running with loopback ports.

Both repo sync and Overwrite with YAML now use a bounded detailed comparison: at most
**200,000 combined UTF-16 code units**, **2,000 combined lines**, and **250,000 matrix
cells**, including sentinel rows/columns. Character size is checked before splitting;
every budget is checked before matrix allocation. Above any budget, the shared view shows
an EN/PL explanation and both complete canonical documents in visibly labeled, keyboard-
scrollable panes with one text block per document. No truncation, new dependencies, API
changes, or catalog validation limits were introduced. Canonical equality, side attribution,
soft findings, explicit confirmation, and full replacement payloads retain their semantics.

This bounds detailed LCS work and per-line DOM creation. YAML parsing, canonical generation,
and displaying complete fallback text still scale with document size; it is not a claim
that arbitrary input rendering has constant cost.

Verification includes exact/above-budget boundaries, asymmetric inputs, repeated short
lines, long lines, deterministic skipped-allocation checks, and valid API descriptor
round trips. Both modal regressions check complete replacement content; ordinary PUT
also preserves `sourceUrl`. Oversized identical documents keep confirmation disabled.

Browser verification used the edited Vite SPA against the existing backend, with a
**48,028-character API definition / 12,012 canonical YAML lines**. The file-picker overwrite
and source-sync dialogs showed complete text in two focusable blocks; actual PUT/sync POST
stored the full replacement and retained its source URL. Large exact matches disabled both
confirmations. Only the repo-fetch response was a browser fixture; sync persistence used
the real API. Desktop/mobile screenshots were inspected and no page errors were observed.
The two uniquely named test records were removed through the API, and active counts returned
to **35 catalog files / 2 users**, exactly as before the probe. Compose and its database
volume were not recreated. At this local-verification checkpoint, no commit, push, release,
or deployment had been performed. Screenshots: [desktop](e2e/screenshots/yaml-diff-desktop.png)
and [mobile](e2e/screenshots/yaml-diff-mobile.png).

Final local gates:

- Frontend build, lint, knip, and generated API type drift check: passed. Knip retains
  only its pre-existing generated-schema configuration hint.
- Frontend coverage: **684 tests / 88 suites**, all passed; **97.64% lines, 95.44%
  statements, 92.63% functions, 91.75% branches**, above the unchanged floors.
- Independent review: no actionable findings; the four focused suites also passed
  (**35 tests**). `git diff --check` passed.
- Backend code and contract were unchanged; backend tests and the full E2E suite were
  not rerun for this frontend batch. Browser evidence above is a focused local probe,
  not a hosted CI run for this changeset.

### PR #6 verification follow-up: one-time password capture

The YAML-diff [push run](https://github.com/liveweird/toadie/actions/runs/34027968335)
passed all gates, but the [PR run](https://github.com/liveweird/toadie/actions/runs/34027995378)
failed the no-flaky gate: Types passed only on retry. The trace confirms that the shared
user-creation helper read the 16-character mask after its Show-password click, while the
control still exposed `aria-pressed=false`. The following login submitted that mask rather
than the generated credential and correctly received 401. A dialog movement causing the
missed click is plausible but not proven by the trace; the confirmed defect is capture
without checking that the reveal completed.

The follow-up waits for the named creation dialog to finish entering and for the reveal
control to expose Hide password / `aria-pressed=true` before reading. A regression suppresses
one actual Show-password click and checks that capture rejects the masked state using its
existing assertion timeout. A subsequent intentional reveal captures a working credential.
An initial entrance-hold test was rejected because it also passed against the old helper;
the final test models the confirmed click-without-reveal failure directly. App behavior,
timeouts, retries, and fail-on-flaky policy remain unchanged. Test credentials are compared
without recording their plaintext here.

The final regression passed with the fixed helper and failed against the original click/read
behavior because that helper returned the mask instead of rejecting. Five repetitions each
of Types and the new regression then passed (**10 runs**, one worker, retries disabled).
E2E type-check and all **27** spec/scenario pairs passed. Independent review found no
actionable issues in the final helper, regression, or documentation.

The full browser suite also passed: **47 journeys**, four workers, retries disabled.
Verification used the existing app image with the newly built SPA mounted read-only in
the separate `toadie-pr6-verify` Compose project (app 8091, Mailpit 8036, no published DB
port). Its containers/network/disposable database volume were removed afterwards; the
regular development stack and volume were preserved. These are local follow-up results;
the historical PR failure remains recorded above pending hosted verification of the fix.

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
