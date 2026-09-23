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
- [x] Require **Quality gate** in GitHub repository settings (enabled 2026-09-18; see the
  GraphQL quality guardrails follow-up below).
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
- [x] Pin connections to validated DNS addresses and bound the complete exchange. Native DNS
  may still ignore interruption and occupy a bounded worker until the OS returns.
- [x] Bound YAML-diff cost with a size threshold/fallback; large valid document regression.
- [x] Protect graph-layout initialization, serialize/coalesce saves, show failure/retry state.
- [x] Make cross-category tag ownership concurrency-safe and test overlapping writes.
- [x] Make catalog mutations and product-history events atomic; fault-injection regression.

### Pinned outbound-fetch destinations batch (2026-09-06)

Starting state verified: clean `master` at `534d529` (PR #9 merged), displayed version
**1.22.1**, successful master CI, and the rebuilt development app running with its original
PostgreSQL volume. The atomic catalog-history batch is complete and deployed.

The JDK client previously resolved the hostname again after validation, leaving a rebinding
gap and a second native lookup outside the bounded validation pool. This batch uses the
already-present OkHttp 5.4.0 transport as a direct dependency. Each fetch resolves and validates
one address snapshot, connects directly to those addresses, retains logical hostname/TLS
verification, and owns a private connection pool. Both the HTTP client and physical sockets
explicitly bypass proxies; a regression exposed Java's SOCKS-selector lookup despite
OkHttp's direct-proxy setting, so the socket factory also uses `Socket(Proxy.NO_PROXY)`.
Redirects and retries remain refused; non-success responses cannot trigger an automatic
status retry or background body drain.

The complete exchange, including DNS, synchronous connection/TLS, and bounded body reads,
runs on four workers with sixteen queued tasks. One ten-second deadline includes queueing.
Cancellation removes queued work, closes an attached call, and prevents late DNS completion
from starting HTTP. Native DNS can still ignore interruption and hold a bounded worker until
the OS returns; this can exhaust fetch availability, but no second resolver or unbounded
connection executor escapes the worker cap.

Compatibility: this fetch path now requires direct HTTPS egress and ignores system HTTP
and SOCKS proxies. Normal certificate trust and hostname verification remain mandatory.
JSON/status contracts, the one-megabyte byte limit, uniform blocked-URL errors, and redacted
audits are preserved. No migration or displayed-version change is needed.

Verification:

- Final full Gradle build, detekt, coverage gates, and `:server:installDist` passed:
  **414 tests / 56 suites**, no failures or skips; **97.818% lines / 76.826% branches**.
  Coverage thresholds are unchanged. The **24 URL-fetch tests** include nine new regressions.
- The TLS success regression failed as expected in a disposable copy with the pinned resolver
  replaced by `Dns.SYSTEM`. The queue-submission race regression failed as expected when
  post-submission cancellation cleanup was removed. Both disposable source copies were removed.
- Frontend build, API generation/drift checks, E2E typecheck, and scenario parity passed
  (**28 spec/scenario pairs**). Spectral: **zero errors**, two registered warnings and
  71 registered hints; the reference fixture passed.
- Independent code/API review has no remaining findings. Manual API checklist:
  **40 pass, two N/A, 11 registered gaps, zero failures**. Review also tightened the race
  assertions and TLS fixture ownership so cancellation/failure cannot strand an accepted socket.
- All **48 Playwright tests** passed with four workers and zero retries against the separately
  built final production code in `toadie-dns-verify:local`. The disposable project's containers,
  network, and database volume were removed afterwards.
- Development users, catalog files, graph layouts, tag categories, and catalog history retained
  identical before/after checksums. The app image and `toadie_postgres-data` volume are unchanged.

These results record the pre-publication checkpoint: the batch was local and uncommitted,
hosted CI had not run, and no push, merge, development-container rebuild, or deployment had
been performed. Publication and deployment are verified separately.

### PR #10 verification follow-up: disconnect observation

The initial [branch backend run](https://github.com/liveweird/toadie/actions/runs/34058023912)
failed the new non-200 disconnect regression, while the same commit's PR backend passed.
The exact safe HTTP-404 failure was returned, but the test's finite 2 MiB writer could finish
inside Linux socket buffers without throwing; its disconnect signal then never completed.
The fix sends an incomplete chunked 404 through a raw socket and observes peer EOF/reset
on the read side. The two-second closure assertion, safe error assertion, and cancellation-safe
socket ownership remain intact. Production code and coverage thresholds are unchanged.

The revised regression passed **30 repetitions in a disposable Linux container**. The full
backend build, detekt, coverage gates, and installDist passed again (**414 tests**, no failures
or skips), and independent review found no remaining issue. The Linux verification container
was removed automatically; it never connected to the development database.

### PR #10 verification follow-up: graph save acknowledgement

The [PR browser run](https://github.com/liveweird/toadie/actions/runs/34058594170) caught a
flaky graph reload assertion, despite the parallel branch run passing. The trace showed the
drag waiter consuming an earlier Manual-mode PUT with empty positions. Reload began about
48 ms after mouse-up, before the 600 ms drag-save debounce; no position-bearing PUT had been
sent, so the restored computed position matched the document actually stored by the server.
The test now matches the exact user's layout path and expected request document, including
the dragged node's position, and asserts the response status. It owns a throwaway user instead
of clearing the seed admin's layout. The scenario and coverage map describe this ownership.
Production behavior, debounce, test timeouts, and fail-on-flaky policy are unchanged.

The corrected graph journey passed **ten repetitions** with retries disabled. The full
**48-test browser suite** then passed with four workers and zero retries on the disposable
stack; TypeScript, all 28 scenario mappings, whitespace checks, and independent review passed.
The disposable stack and data were removed after verification; development data was preserved.

### Atomic catalog-history batch (2026-09-06)

Starting state verified: clean `master` at `84f8276` (PR #8 merged), displayed version
**1.22.1**, master CI green, and the rebuilt development app running with its original
PostgreSQL volume and preserved data. The tag-ownership batch is complete and deployed.

Catalog writes previously committed before routes appended their product-history events in
separate transactions. An event failure could therefore leave a changed file without history.
This batch replaces that convention across create, replace, soft delete, repo sync, and import:
each file write and its required event commit or roll back together. Import retains independent
per-document results; a history failure rolls back its row and yields a safe ERROR, while other
documents continue. Replacement/sync lock the current row before deriving the history diff.

No-op PUT suppression, always-recorded syncs, actor identity, source/sync state, redaction,
soft deletion, and the existing validation/authorization rules remain part of the contract.
Security audit logs stay route-side after service success. Response delivery/read-back may
still fail after commit, and whole-document replacement remains last-write-wins. This is a
deliberate whole-feature change from Lettuce's split-transaction history convention; its event
storage has no existing caller-transaction helper to port. No migration is needed.

Four new HTTP regressions use real PostgreSQL event-insert failures and observed row-lock
contention. They verify failed create leaves neither file nor event; failed replace/sync/delete
preserve the complete stored row and event count; a success/failure/success URL import keeps
both successful files, sync baselines, and import-origin events; and overlapping updates
produce consecutive before/after diffs. Existing history coverage now explicitly checks an
identical sync still records an event. Test triggers and held connections have cancellation-safe
cleanup across dispatcher handoff.

The retained creation regression was run against the actual pre-fix code in a disposable
`git archive HEAD` checkout: after the forced event-insert failure, its zero-row assertion
failed with **one stored row**. The fixed implementation passes that regression with zero
rows and zero events. The disposable baseline checkout was removed.

Verification:

- Final full Gradle build, detekt, coverage verification, and `:server:installDist` passed:
  **405 tests / 56 suites**, no failures or skips; **97.849% lines / 77.030% branches**.
- Frontend build, API generation/drift checks, E2E typecheck, and scenario parity passed
  (**28 spec/scenario pairs**). Spectral: **zero errors**, two registered warnings and
  71 registered hints; the reference fixture passed.
- Independent code/API review has no remaining findings. Manual API checklist:
  **35 pass, seven N/A, 11 registered gaps, zero failures**.
- All **48 Playwright tests** passed with four workers and zero retries against the separately
  built `toadie-atomic-verify:local` app. Only the disposable project's containers, network,
  and database volume were removed afterwards.
- During pre-publication verification, development users, catalog files, graph layouts, tag
  categories, and catalog history retained identical before/after checksums. The development
  app image and database volume were unchanged; publication and deployment were separate steps.

### Tag-ownership concurrency batch (2026-09-06)

Starting state verified: clean `master` at `7f3fa8b` (PR #7 merged), displayed version
**1.22.1**, and the existing Compose app, PostgreSQL, and Mailpit running on loopback ports.
The graph-persistence batch's master CI and deployment checks passed with data preserved.

Tag-category writes previously checked ownership before writing under ordinary transaction
isolation; overlapping requests could both observe an unclaimed tag and both commit. The
fix serializes create/replace/delete in PostgreSQL before reading registry state, preserving
the existing JSON-array storage, soft deletion, `409` conflicts, and authorization/validation
precedence. The same lock also protects the 200-active-category capacity check. No migration
or new validation rule is needed. Concurrent replacements of one category still use
last-write-wins semantics; all tag writers must use the lock protocol, and existing duplicate
ownership is not silently repaired. Lettuce has no equivalent tag-category lock to port.

Seven new regressions use real PostgreSQL contention with a held lock and observed waiters:
create/create, replace/replace, create/replace, the 199→200 capacity boundary, delete with
concurrent reads, rollback, and cancellation. Losing replacements retain their complete
original document. The negative control against the original service deterministically
returned `201 + 201` where the regression requires `201 + 409`; the fixed source was restored
and all 18 focused tag tests passed. Existing tests retain remove-then-add and soft-delete
tag reuse coverage. The current R2DBC path may finish cancellation only after the blocking
lock clears; the regression verifies rollback and subsequent writes after that release.

Verification:

- Full Gradle build, detekt, coverage verification, and `:server:installDist` passed:
  **401 tests / 55 suites**, no failures or skips; **97.715% lines / 76.796% branches**.
- Frontend build, lint, knip, and coverage passed: **710 tests / 89 suites**;
  **97.47% lines / 95.12% statements / 92.86% functions / 91.50% branches**.
- API type generation/drift checks passed. Spectral: **0 errors**, two registered warnings,
  71 registered hints, and a clean reference fixture. Independent API review: **28 pass,
  16 N/A, nine registered gaps, zero failures**; no remaining code-review findings.
- E2E typecheck and scenario parity passed (**28 spec/scenario pairs**). All **48 Playwright
  tests** passed with four workers and zero retries against the separately built
  `toadie-tags-verify:local` image and its own database volume. Only that disposable project's
  containers, network, and volume were removed afterwards.
- During pre-publication verification, development users, catalog files, graph layouts, and
  tag categories retained identical before/after checksums; the existing app image and Compose
  deployment were unchanged. No publication or development deployment was part of those checks.

### Graph-layout persistence batch (2026-09-06)

Starting state verified: clean `master` at `fb62cec` (merged PR #6), displayed version
**1.22.1**, and the existing Compose app, PostgreSQL, and Mailpit running on loopback ports.
The previous batch's master CI was green. This batch changes frontend persistence only;
there is no API, migration, release-version, or backend change.

The graph waits for its complete per-user layout before allowing layout changes. A dedicated
hook owns initialization and a single-flight save queue: drag stops debounce for 600 ms,
immediate controls supersede that debounce, and overlapping edits coalesce into the latest
complete `{mode, positions, collapsed}` document. Filtered-out positions and folded ids are
preserved. Inline loading/save status and safe EN/PL errors make pending work and failures
visible; Retry resubmits the latest local document. Ordinary SPA navigation retains pending
work; session/account boundaries invalidate it. Browser termination and writes from separate
tabs/devices remain outside this client ordering guarantee.

Independent review also identified a shared-transport race: a successful refresh held across
best-effort sign-out could restore the old credentials and replay the graph mutation. This
batch therefore guards HTTP refresh/retry work at session boundaries as well as the graph
queue. A new login cannot inherit old in-flight refresh work or be cleared by its rejection.

Verification uses an isolated `toadie-graph-verify` Compose project on port **8091**, Mailpit
on **8036**, and its own `toadie-graph-verify_postgres-data` volume. It reuses the unchanged
backend image and mounts the locally built SPA; the development app and volume are untouched.
Final local verification:

- Frontend build, lint, Knip, and generated API type drift check: passed. Knip retains only
  its existing generated-schema configuration hint.
- Frontend coverage: **710 tests / 89 suites**, all passed; **97.47% lines, 95.12%
  statements, 92.86% functions, 91.50% branches**, above the unchanged floors.
- Full browser suite against the final built SPA: **48 passed**, four workers, retries
  disabled. Includes real graph folding, manual dragging/reset, authentication, and the new
  held-load/serialized-save/failure/retry journey. E2E type-check and **28** scenario pairs passed.
- Independent review: all confirmed findings resolved, including cross-tab account changes,
  token-first storage ordering, and refresh crossing sign-out; no remaining actionable findings.
- Screenshot inspection caught a flex-shrunk error banner; the final banner keeps the safe
  message and Retry button visible above the canvas. Screenshot: [layout save failure](e2e/screenshots/graph-layout-retry.png).
- Deterministic hook/transport regressions cover deferred responses, newest-document retry,
  navigation, cache cleanup, account/session changes, concurrent refreshes, and a failed revoke
  followed by a late refresh. No increased timeouts or lowered coverage gates.

Removed only the disposable project's containers/network/volume after verification. The
existing development stack and its data were preserved. Backend code, migrations, and API
contract were unchanged; backend tests were not rerun locally. These are pre-commit local
results; subsequent hosted CI results are tracked on the pull request. No release or
deployment was performed as part of local verification.

### Outbound-fetch deadline batch (2026-09-06)

`UrlFetcher` (then `CatalogUrlFetcher`, `catalog/UrlFetch.kt`) now uses one ten-second caller deadline spanning validation, DNS guard,
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

## Dependency updates

Dependabot (not Renovate, decision D4) opens weekly, minor/patch-grouped pull requests per
ecosystem — npm for `web/` and `e2e/`, Gradle for the root build, GitHub Actions, and Docker
(`.github/dependabot.yml`); majors stay ungrouped so a breaking bump still gets its own review.
Base images are pinned by TAG, not digest (`eclipse-temurin:21.0.12_8-jdk`/`-jre`, `node:24-alpine`
in `Dockerfile`) — paired with Dependabot's `docker` ecosystem watching those same tags, this
gives most of a digest pin's reproducibility without the churn of hand-editing a new digest on
every upstream patch release; it is a separate, narrower concern from Stage 4's still-open item
below (an immutable reference for the app's OWN released image at deploy time). Every Dependabot
PR still runs the ordinary quality gates before merge, and a Gradle bump PR must be checked for
regenerated lockfiles (see CLAUDE.md's "Dependency locking" line) before it can merge.
Major bumps of the two base images (`eclipse-temurin`, `node`) are ignored by the docker
ecosystem block: they are the toolchain, decided in `mise.toml` (and mirrored by the Dockerfile,
CI's `setup-java`/`setup-node`, and `jvmToolchain(21)`) — not something a dependency PR should
move on its own. Dependabot's first run (2026-09-12) proposed Temurin 24 and Node 26 against
the pinned 21/24; both were closed and the ignore rule added.

## Stage 4 — deployment

- [ ] Replace ingress-nginx with Traefik; verify TLS, HSTS, redirects, forwarded-header trust,
  real client IPs/rate limits, upload limits, probes, and rollback.
- [x] Right-size storage and exercise backup/restore without touching the dev volume. New claims
  start at 10 GiB; `k8s/BACKUP-RESTORE.md` defines measurement/alerting, expansion and retention
  guidance, production backup handling, and the disposable two-volume restore drill. The drill's
  guarded random Compose project publishes no ports, mounts no existing volumes, verifies a
  custom-format archive after restoring into a second empty PostgreSQL 18 volume, and removes
  only its own resources on completion, failure, or catchable interruption. Production capacity,
  RPO, and RTO still need measurement and an operator decision before deployment.
- [ ] Adopt immutable release image references with an intentional update process.
- [ ] Keep one app replica until instance-local authentication state is addressed — the
  Deployment's `strategy: Recreate` (k8s/app-deployment.yaml) only stops a rolling update from
  briefly running two pods; it does not itself let a second replica run.

Controller installation/cutover is a cluster-level action: inventory existing workloads first
(Lettuce may share the controller), and do not remove a controller another app still needs.

### Container posture

The runtime image drops root: `Dockerfile`'s `runtime` stage creates a dedicated `app` user
(uid 10001) after copying the server distribution and SPA assets, then switches to it with
`USER app` before `ENTRYPOINT` — the process never runs, and never needs to run, as root
(port 8081 is unprivileged and the app writes nowhere outside `/app`). Both the build-stage
JDK and the runtime-stage JRE are pinned to the exact `mise.toml` Temurin patch
(`eclipse-temurin:21.0.12_8-jdk`/`-jre`, not the floating `21-jdk`/`21-jre` tags), so the two
stages are provably the same Java build rather than whatever "21" resolves to on a given pull;
`web/src/test/infrastructure.test.js` pins both the non-root `USER` line and tag parity with
`mise.toml`. `docker-compose.yaml`'s `app` service gained a `healthcheck` against `/readyz`
(the same k8s readiness endpoint, `curl --fail`, 10s interval/12 retries/30s start period),
so `docker compose up` surfaces a slow or crash-looping app as an unhealthy container instead
of a silently-accepting-connections one.

The app pod now declares the full security context above (non-root uid 10001, read-only root filesystem with an emptyDir at /tmp, all capabilities dropped, no privilege escalation, RuntimeDefault seccomp), pinned by `web/src/test/infrastructure.test.js`. A CPU limit remains deliberately absent — Ktor's async runtime scales with available cores; a limit would throttle without protecting anything the memory limit does not already bound — re-examined 2026-09-20.

## Stage 5 — selective refactoring

- [x] Extract graph persistence into a dedicated hook as part of Stage 3.
- [ ] Split catalog service/form responsibilities where concrete changes repeatedly overlap.
- [x] Guard SPA catalog edits against stale writes. V40 adds per-file revisions; the editor,
  YAML overwrite, repo sync, and Files/Hierarchy deletion send their captured revision in
  `X-Expected-Revision`. A mismatch leaves file/history unchanged and returns a typed `409`;
  the SPA keeps the losing draft. The header is optional for released `/api/v1` compatibility:
  legacy API clients can still make unguarded writes, and a mandatory contract belongs in a
  future API version (registered under API-CACHE-003).

Do not introduce a generic registry framework or interfaces solely to satisfy a principle.
The modular-monolith architecture remains appropriate; the work is hardening, not a rewrite.

### 2026-09-14 — documentation review (Codex's DOCUMENTATION_QUALITY_REVIEW.md)

Codex's untracked `DOCUMENTATION_QUALITY_REVIEW.md` (reviewed master `2ff74eb`) was re-verified
finding by finding against master `afb75c2`. Applied: the `CLAUDE.md` template/lock-description
corrections (DQ-01/DQ-02b) with the blueprint/entity 404-before-400 note added to
`.claude/docs/authorization.md`; the 400-not-422 choice registered as `API-ERR-002` in
`api-guidelines/API-GUIDELINES.md`'s known-gaps register rather than "fixed" (DQ-02a); the
`AGENTS.md` descriptive-vs-normative disagreement split (DQ-03); the scenario checker now
enforces the coverage-map line it already promised, with `.claude/docs/testing.md`'s "E2E
scenarios" paragraph demonstrating the new structured rule format once (DQ-05a); Spectral's
`security-is-declared`/`security-schemes-defined` promoted `warn` → `error` since both fire
zero times today (DQ-05b); the "Where to read, by task" map + reference-examples table added
to `CLAUDE.md` (DQ-06/DQ-07); and DQ-08 (the transport's caller-`signal` override) fixed in
code via `web/src/api/http.ts`'s new `anySignal()`, composing a caller signal with the request
deadline instead of replacing it.

Declined: the color-contrast waiver (kept, e2e's `accessibility.spec.ts` documents it
consciously) and any wholesale documentation consolidation (the docs' redundancy is a
readability trade-off, not a defect). **DQ-04 remained open at this review checkpoint** —
requiring the **Quality gate** status in GitHub repository settings is not something a YAML/doc
change can enforce. The 2026-09-18 GraphQL quality guardrails follow-up below closes it through
the live ruleset. Prescriptions 1–6 in the review already had canonical homes and needed no new
artifact.

### 2026-09-18 — full quality review follow-up (2.6.2)

Reviewed baseline: `68914e1` / v2.6.1. This batch addresses the ten newly confirmed findings;
previously parked branch-rule, optimistic-concurrency, API-guideline, and contrast work stays
outstanding. Historical review documents above remain snapshots of their reviewed revisions.

- [x] Q01 — Partition entity-query draft/applied/open/picked state by authenticated account;
  discard ownerless legacy keys. A real PRIVATE-query browser journey switches between two
  users without clearing storage and verifies API visibility, blank foreign state, outgoing
  graph parameters, and restoration of the original user's state.
- [x] Q02 — Share password length/UTF-8 byte validation with bootstrap and explicitly reject
  burned seed/template passwords before an applicable rotation. Preserve existing passwords
  and allow restarts with obsolete unused bootstrap configuration.
- [x] Q03 — Derive identity/role, feature, and language audit deltas from the locked mutation
  transaction. Lock administrators before the target for demotion/deletion and check for a
  surviving other administrator. Held PostgreSQL writes reproduce stale audit snapshots and
  promotion races; the three audit regressions fail against the original implementation.
- [x] Q04 — Check restoration update counts and retain committed IDs on ERROR, including
  pass-two unique conflicts. Audit each successful pass-one write before subsequent work,
  independently of the final verdict, without duplicate pass-two events. Scoped database
  faults pin residual state and exact-once create/update audits. Logging remains post-commit,
  not a transactional outbox or process-crash delivery guarantee.
- [x] Q05 — Enforce an atomic 10,000-live-challenge MFA ceiling. Refuse excess admission with
  conformant 429 and an audit event before queuing mail; preserve existing challenges and
  recover capacity after expiry/consumption. Login throttling copy remains accurate in EN/PL.
- [x] Q06 — Restore pathname, query parameters, and fragment after password or MFA login,
  with internal-only return destinations.
- [x] Q07 — Update the compatible Redocly patch to 1.34.20 / js-yaml 4.3.2. Fresh npm audit
  reports zero vulnerabilities; API generation and drift checks pass without a major upgrade.
- [x] Q08 — Document strict import decoding consistently in OpenAPI and the Port reference;
  unknown/export-only metadata is INVALID for that row. Pin real/dry-run behavior with tests,
  regenerate the typed client, and clarify ERROR-row IDs and import audit timing.
- [x] Q09 — Refresh the README around both current product worlds, strict/waivable validation,
  entity queries, and database-backed session families. Keep AGENTS/Claude guidance aligned.
- [x] Q10 — Reconfigure the query editor's localized accessible label and placeholder without
  rebuilding its document; add localized-prop regressions.

Independent review found and corrected two issues before integration: validating an unused
legacy bootstrap setting could have blocked an upgrade restart; counting administrators before
a concurrent promotion could have falsely rejected a later safe demotion/deletion.

Integrated verification (2026-09-18):

- `./gradlew build :server:koverXmlReport --no-daemon` passed, including Detekt and
  coverage verification: 1,113 backend tests; 97.67% lines / 80.38% branches. API conformance
  exercised 76/76 operations and 318/407 operation/status pairs.
- Frontend build, lint, Knip, generated API drift, Spectral, and coverage gates passed:
  2,893 tests; 97.64% lines / 95.42% statements / 94.65% functions / 91.15% branches.
  Spectral retained only registered warnings/hints; no errors or lowered coverage floors.
- E2E type checking and scenario/coverage-map parity passed for 41 specs. The final full
  Playwright run passed all 71 journeys against an isolated Compose project. Additional
  browser probes verified deep-link search/fragment restoration and live Polish editor labels.
- The frontend npm dependency audit reported zero vulnerabilities. This is not a JVM or
  container-image vulnerability assessment.

Verification used disposable test data; the development stack and its data were preserved.
These results are the local pre-push baseline. During PR #70 verification, CI exposed a
login-test synchronization race: the location probe already existed before navigation completed.
The destination assertions now wait for the expected route (including MFA and rejected external
destinations), rather than only waiting for the probe element. No application behavior, timeout,
or coverage threshold changed.

### 2026-09-18 — read-only Port GraphQL integration (2.7.0, locally verified)

Reference: Lettuce's committed `8fbe241` snapshot; its live working directory was not modified.
Implementation details and future changes belong in `.claude/docs/integration-api.md` and
`api-guidelines/GRAPHQL-GUIDELINES.md`; the committed SDL is the executable GraphQL contract.

- [x] Add separately authenticated `/integration/graphql` and `/integration/graphql/schema`,
  disabled by default and enabled explicitly in the Compose demo. Expose only Port blueprints,
  entities, and ontology findings through existing domain services, including computed values
  and effective ownership. Saved queries, login accounts, Backstage, and mutations stay outside
  the schema. Disabled integration paths cannot fall through to SPA HTML.
- [x] Add ADMIN-only client management, one-time random API-key reveal, SHA-256 storage,
  race-safe terminal revocation, audits, and V36's dedicated client table. Ontology persistence
  is unchanged. Keep machine identities independent of their creating login account.
- [x] Bound authenticated body reading and JSON structure before decoding, parsing, query
  depth/cost, findings expansion, concurrent/rate admission, deadlines, retained values, and
  encoded responses. Preserve caller cancellation and sanitize unexpected failures.
- [x] Charge eager definitions/page documents, generated computed values/findings, and inherited
  team-filter snapshots before they accumulate. Ordinary REST reads retain their existing policy.
- [x] Add the full-width bilingual management screen, clear secrets on login-family replacement,
  reject stale mutation completions, and reveal a new key without waiting for list refresh.
  Long secrets and reveal/copy controls remain usable at a 390px viewport.
- [x] Update REST OpenAPI/generated types, SDL documentation, AGENTS/Claude references,
  README examples, and bilingual changelog together.

Independent review findings were resolved before completion: alias-driven result amplification,
pre-resolver allocation gaps, deep-JSON recursion, body-admission timeouts, raw decode failures,
nullable timestamps, stale-session key exposure, and an exact-Long floating-point boundary.

Integrated verification:

- `./gradlew build --write-locks :server:koverXmlReport --continue --no-daemon`: passed,
  including Detekt and coverage gates; 1,150 backend tests, 97.38% lines / 79.17% branches.
  REST conformance exercised 80/80 operations and 334/430 operation/status pairs.
- Frontend build, lint, Knip, API generation/drift, Spectral, and coverage gates: passed;
  2,940 tests, 97.62% lines / 95.36% statements / 94.46% functions / 91.09% branches.
  Spectral retained only registered warnings/hints. No coverage floor was lowered.
- E2E type checking and scenario/map parity passed for 42 specs. The final full browser run
  passed all 72 journeys with flaky tests treated as failures, including mobile key controls.
  Additional desktop/mobile inspection found no document-width overflow or browser exceptions.
- Distribution packaging and the Docker build passed. Verification used an isolated Compose
  project, separate ports/image/volume, and disposable data; the development stack was preserved.

Deployment/data verification follow-up:

- Added an optional `toadie-config.INTEGRATION_ENABLED` mapping to the Kubernetes deployment,
  preserving default-off behavior and documenting enable/disable/restart commands.
- Loaded the Port demo through its existing REST loaders into separate disposable Compose and
  Kubernetes databases: eleven blueprints and 59 entities in each. Compared all selected
  blueprint/entity fields against REST, including computed properties, inherited teams,
  relations, and findings. Both reports checked 59 entities / eleven blueprints with no findings;
  authenticated schema downloads exactly matched the committed SDL.
- Verified REST create/update visibility through GraphQL and introduced a required property on
  a temporary blueprint to produce real entity findings. GraphQL entity findings and the
  ontology errors root matched REST. Temporary entities/blueprints were removed and keys revoked.
- Ran the Kubernetes checks against each of two production-mode app replicas sharing the same
  dedicated PostgreSQL PVC (a deliberate verification exercise for the shared-database path only —
  the supported posture stays one replica, see the open item above). Both saw the same data mutations/findings and rejected a key revoked
  through the first replica. Repeated these checks after rolling both app pods, without reloading
  the sample, confirming persistence across pod replacement. Tests used pod-specific loopback
  port-forwards with the documented trusted proxy header; external ingress/TLS and a full
  Kubernetes browser suite were not tested.
- Compose and Kubernetes databases remain independent by default. This proves REST/GraphQL and
  app-replica sharing inside a deployment, not database sharing between the two deployments.

These are local verification results, not a pushed CI or production deployment claim.

### 2026-09-18 — v2.7.0 release completion

[PR #71](https://github.com/liveweird/toadie/pull/71) merged into `master` at
`e5b9f557b77ba94c1806a0d45b1219db0ae03499`. The
[master CI run](https://github.com/liveweird/toadie/actions/runs/35395215468) passed all gates.
The development Compose stack was rebuilt and verified healthy with v2.7.0 and V36 applied;
the original database volume and development content were preserved. Live GraphQL and UI
checks passed. This records the release after the local verification checkpoint above.

### 2026-09-18 — GraphQL quality guardrails follow-up

- [x] Enable the required **Quality gate** check in active default-branch ruleset `22261697`,
  bound to GitHub Actions app `15368`, with up-to-date branches required. Preserve the existing
  PR, deletion, and non-fast-forward rules and empty bypass list. Effective `master` rules
  were read back after the change. This closes Stage 1 / DQ-04; earlier entries describe
  their historical checkpoints.
- [x] Preserve seeded deployment verification in `e2e/scripts/graphql-smoke/`. Each run owns
  its image, deployment and database; it checks eleven blueprints / 59 entities, complete
  REST/GraphQL parity, fixed computed/ownership expectations, exact SDL, induced findings,
  persistence across app restart, and key revocation. The Compose target is wired into the
  reusable E2E workflow; the two-replica Kubernetes target requires explicit local OrbStack.
- [x] Add `:server:checkGraphqlCompatibility` to `check`. Compare against the pre-change Git
  revision in CI and fail on missing history/schema. Guard member removal, type/nullability,
  inputs/defaults, unused types, extensions and directive definitions. Keep the baseline and
  intentional-major-break policy in `api-guidelines/GRAPHQL-GUIDELINES.md`.
- [x] Remove `feat/port-graphql` locally and remotely after verifying its tip `cbaedf8` is
  an ancestor of freshly fetched `origin/master`. Remote deletion used an exact-tip lease.

Verification of this follow-up:

- Full Gradle build, Detekt, coverage gates and refreshed Kover report passed. After the final
  directive comparison was added, all seven focused compatibility tests, the compatibility
  task and Detekt passed again. A missing-baseline negative control failed with the expected
  actionable diagnostic. Independent review has no remaining actionable findings.
- All eight smoke-runner tests passed. Final live Compose and Kubernetes smoke runs passed,
  including restart and cross-replica revocation checks. Disposable resources were removed;
  the development stack and database were not modified by these runs.
- E2E type checking and all 42 scenario mappings passed; frontend infrastructure regressions
  (ten tests), frontend lint and whitespace checks passed. No production API behavior, SDL,
  dependency or coverage floor changed in this follow-up.

At this checkpoint the guardrail code, workflow and documentation changes are local and
uncommitted on `chore/graphql-quality-guardrails`; hosted CI has not run them yet. The GitHub
ruleset and merged-branch deletion are already applied. Kubernetes ingress/TLS, restore drills,
immutable release images, authentication scaling and other parked items above remain separate.
