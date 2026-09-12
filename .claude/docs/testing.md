### Testing

**Catalog history atomicity.** Inject a real PostgreSQL failure at history insertion and assert
that create, replace, sync, and soft delete roll back their entire file state with the event.
Check content, source reference, timestamps, sync baseline, and deletion flag, not just the
response status. Imports retain per-document transactions: a failed row has ERROR/no fileId,
while adjacent successful rows and their import-origin events persist. Preserve no-op updates,
always-recorded syncs, required actor identity, and free-text redaction. Exercise same-file
overlap with an observed database lock barrier to verify the event diff's actual predecessor.
Fault injection belongs only in tests, scoped to uniquely owned fixtures, with cancellation-safe
cleanup; never add production timing/failure hooks or alter applied migrations.

**Tag-category concurrency.** Exercise overlapping create/create, replace/replace, and
create/replace claims against real PostgreSQL connections. Use a held database lock and
observe blocked contenders before release, so the original check-then-write implementation
fails deterministically; repeated ungated async calls are not proof of contention. Assert one
successful claim and one conformant `409`, unchanged fields on a losing replacement, and
release of ownership after removal or soft deletion. Keep ordinary reads available while
writes wait, and verify rollback/failure releases locks. A cancelled blocked writer must
store nothing and leave later writes usable once the blocking lock is released; the current
R2DBC path does not guarantee immediate cancellation of a PostgreSQL lock wait. Use uniquely
owned fixtures and cancellation-safe lock cleanup in `finally`; capacity tests may
fill only free slots and remove only their fillers.
Do not add production timing hooks, sleep-based race assertions, or an in-process mutex.

**Graph-layout persistence.** Hook tests use deferred GET/PUT promises and controlled debounce
time to prove that edits wait for the full baseline, only one PUT is outstanding, and queued
changes collapse into the newest complete document. Pin unseen positions/collapsed ids,
same-turn functional updates, late GET responses, explicit retry after failure, and old
acknowledgements that must not mark newer edits saved. Include StrictMode, SPA unmount/remount,
and account/cache-clear boundaries; old work must not be sent using a new account's session.
Page tests expose the real layout-control/drag/fold contracts through the React Flow stub and
check loading, safe errors, and retry. Keep live drag frames separate from persisted positions.
The browser regression owns a throwaway user's layout and uses response gates to exercise
pending initialization and saves; it must never mutate the seed admin's layout. Response
predicates match the exact user path and method; assert status after receiving the response.
Match the saved document too when multiple writes overlap: a mode-only acknowledgement must
not satisfy a drag waiter. Confirm the request contains that node's position before reloading;
the checked Manual radio and moved canvas only prove local state, not completed persistence.

**Refresh crossing sign-out.** Use held responses to exercise the shared transport after an
old protected request receives 401: hold refresh, perform the real best-effort logout with
its revoke request failing, then release refresh. The browser session must remain cleared
and the old mutation must not be replayed. Cover a new login before a delayed 401, refresh
success/rejection/body completion, and concurrent requests in the same/new families. Do not
print token values in diagnostics or replace the server's authentication checks with JWT decoding.

**Bounded YAML diffs.** `yamlDiff.test.ts` pins detailed-comparison budget boundaries,
asymmetric inputs, repeated short lines, long lines, and the exact reconstruction of both
inputs (including empty/trailing lines). Large API descriptors must remain valid through
the real canonical generator and strict parser, with `spec.definition` within its 100,000
character limit. Assert the fallback choice and skipped matrix allocation deterministically;
do not rely on a tight elapsed-time assertion or try to allocate the former quadratic matrix.
The shared view and both confirmation-modal regressions must cover complete fallback text,
bounded text-block count, accessible scroll regions, and unchanged full replacement payloads
(including `sourceUrl` for ordinary PUT). Identical large documents still disable confirmation.

**Outbound-fetch destinations, deadlines, and cancellation.** URL-fetch regressions use
local HTTP/TLS fixtures and held resolution to exercise the single total deadline,
pre-header/TLS/body stalls, caller cancellation and enclosing timeouts, truncated-body I/O,
exact-limit/oversize bodies, and the route's conformant 502 response. Use started/release
gates and generous outer bounds; never depend on a slow public server or assume cancelling
a coroutine proves the HTTP exchange closed. Observe connection closure too. Keep the
production HTTPS/public-host guard intact; only internal test target resolution may allow
loopback fixtures. TLS fixtures trust a test-only CA and must still reject a wrong hostname
or untrusted certificate. Verify the logical Host/SNI and physical destination separately.

Pinning regressions must reject mixed public/private answers, use the validated address
snapshot without a second resolver lookup, and prevent system proxies or cross-fetch pool
reuse from bypassing it. Exercise first-status rejection (including `503 Retry-After: 0`),
worker/queue saturation, cancellation during submission and while queued, and recovery after
held native resolution is released. Native DNS that ignores interruption must not hold the
caller past its deadline or initiate late HTTP. Its remaining bounded-worker limitation is
documented in `security.md`; cancellation does not forcibly terminate native work. Fixture
cleanup must retain ownership across coroutine dispatcher cancellation, including setup.
For early status rejection, observe EOF/reset on the fixture socket's read side; a finite
write loop may fit entirely into Linux socket buffers before cancellation and never throw.
A raw incomplete chunked response makes the body stall independent of send-buffer capacity.

**Hosted timing and loading regressions.** Catalog editor save-flow fixtures use user-event
paste for complete text values; typing tests still exercise keystrokes. Do not spend the test
budget re-rendering the entire form/preview for every character of unrelated fixture setup.
Accessibility covers pending UI as well as completed pages: the Errors regression holds its
real report request, scans the named loading status, releases the request in `finally`, and
scans the completed report. This reproduced the previously intermittent `aria-prohibited-attr`
failure on the shared spinner without adding an axe waiver or accepting retries.
Mutation tests must wait for completed success UI (for example, the editor/confirmation
dialog closing), not just a fetch spy recording a request: the response callback may still
be updating modal state when the test otherwise tears down its DOM.

**Dialog entrance readiness.** A post-merge Lenses trace showed a click at y≈397 while the
confirm button slid toward y≈427; no second file DELETE was sent, and ~37 seconds remained.
`readyDialog(page, title)` in the E2E helpers waits for computed opacity one before callers
interact. Playwright visibility ignores opacity, and two stable frames can precede the deferred
entrance animation, so visibility/stability alone was insufficient. Never fix this with sleeps,
larger timeouts, repeated destructive clicks, or blanket animation suppression. The read-only
`dialog-readiness` journey holds a real unsaved lens editor transparent, verifies the helper
does not resolve, releases the hold in `finally`, then cancels. The Lenses journey additionally
matches exact resource URLs/methods, asserts status outside the response predicate (a failed
HTTP response must fail directly, not be ignored until timeout), and awaits completed UI.

**One-time password capture.** The shared E2E `createUserViaUi` helper must wait for the
named "User created" dialog to finish entering and for the reveal control to expose
"Hide password" with `aria-pressed=true` before reading its code block. A successful
Playwright click is not proof that the UI changed. The PR #6 Types trace captured the
16-character mask while the control was still unrevealed, then submitted it and received
the correct 401. Do not hide that failure with login retries or increased timeouts.
`password-reveal-readiness.spec.ts` suppresses one real Show-password click and proves
the shared capture helper rejects the still-masked state on its existing assertion timeout.
A subsequent intentional reveal captures a credential that signs in successfully. This
models the confirmed trace without a timing guess; `dialog-readiness.spec.ts` separately
pins entrance readiness. Do not add fixed frame counts or relax assertion timeouts.
The test owns a unique throwaway user and removes only that user in `finally`; diagnostics
must compare credential relationships without printing the values.

**Session lifecycle regressions (V25).** `AuthSessionServiceTest` injects a clock and tests
expiry, cross-instance revocation, stale-epoch issuance, competing credential writes, and
logout/renewal races. `SessionRevocationTest` checks current and superseded pairs, independent
logins, foreign-token logout bodies, account changes/deletion, and malformed family/identity
claims. MFA tests reject challenges predating a password change. No sleep is needed to cross
a JWT timestamp boundary: the account epoch is exact. Tests must never rely on a demoted
administrator's stale token to perform protected operations. The users browser journey checks
the required re-login after self password change, with its scenario/coverage-map entry updated.

**Automatic CI.** `.github/workflows/ci.yml` runs every push/PR, merge group, and manual dispatch without path filters. Backend: `./gradlew build :server:koverXmlReport`; frontend: locked `npm ci --legacy-peer-deps`, `lint:api`, `check:api`, build, lint, knip, and coverage. The reusable E2E workflow runs TypeScript/scenario parity and browser journeys. The aggregate **Quality gate** fails if any dependency fails, is cancelled, or is skipped; make it a required repository status after pushing (repository settings, not something YAML activates). E2E owns a disposable `toadie-ci` project on a fresh GitHub-hosted runner, waits for `/readyz` and Mailpit, collects diagnostics, and always removes only that project's volume. Never move it to a shared self-hosted Docker daemon. Retries collect evidence but `failOnFlakyTests` keeps flaky tests red; missing Mailpit fails CI instead of skipping MFA/reset coverage. `web/src/test/infrastructure.test.js` guards demo bindings and CI wiring; this Node filesystem test stays JavaScript so it needs no Node globals in the SPA's TypeScript configuration.

**OrbStack discovery.** If Docker CLI works but Testcontainers cannot find a daemon, set `DOCKER_HOST` to the endpoint printed by `docker context inspect --format '{{.Endpoints.docker.Host}}'` for that invocation. The CLI's selected context is not automatically inherited by Testcontainers. Never bake a developer's socket path into repository configuration.

Backend tests live flat in `server/src/test/kotlin/` (kotlin.test + `io.ktor.server.testing.testApplication`) and override the `postgres.*` config keys via `MapApplicationConfig` to point at a Testcontainers `PostgreSQLContainer("postgres:18-alpine")` started lazily by `PostgresTestSupport` and **shared across the whole suite**. Running tests requires a working Docker daemon (Docker Desktop, OrbStack, etc.). The container runs **all** Flyway migrations, so the V3 seed admin (`admin@toadie.local`) is present — tests scope their assertions with unique prefixes/filters (`uniqueEmail("marker")`) rather than asserting absolute counts.

**The `TestEnvironment.kt` harness** — use it instead of hand-rolling setup:

- `configureApp(vararg overrides)` points the app at the shared container (with CSRF off) WITHOUT starting it — tests that assert startup behavior (the fail-closed checks) add their overrides and call `startApplication()` themselves; `usePostgresTestcontainer()` is the configure-and-start shorthand.
- `jsonClient()` / `authedClient(email, password)` — the standard HTTP clients; both go through `toadieTestClientDefaults()` (JSON + `application/problem+json` negotiation + the `OpenApiConformance` plugin), and `authedClient` logs in and attaches the bearer on every request.
- `LogCapture(loggerName)` + `hasKeyValue` — a Logback `ListAppender` for asserting the audit trail (`ch.nokillswit.audit`); `awaitEvent` polls for asynchronously produced events. Detach in a finally.
- `TestUsers.seed(email, password, name, role)` (bcrypt cost 4 for speed; defaults to ADMIN — pass `UserRole.USER` for a non-privileged caller) — every seeded user funnels through `UserService.create`, so they carry the inverted-default MFA-disabled row and log in single-step and `TestUsers.softDelete(id)` (direct table update, bypassing the delete endpoint's guards) and `TestUsers.withSoloAdmins(ids) { }` (temporarily parks every other active admin — the last-admin-protection pin). `seededClient(prefix, role)` is the one-line seed+login fixture. `TestCatalogFiles.service` for direct service access (contracts the routes can't reach, e.g. blank-filter rules); catalog fixtures (`componentFile`, `groupFile`, …, `createCatalogFile`) live in `CatalogFixtures.kt`.
- `postJson`/`putJson` (the JSON body ceremony) + `HttpClient.login` (the raw login POST), `withAuditCapture { }` (attach/detach on the audit logger), `withSeedRestored { }` and `assertStartupFails(part) { }` for bootstrap/fail-closed tests — use these instead of re-rolling the blocks they replaced.
- `TestNamespaces` — the namespaces dictionary is SHARED suite state (like the seed admin): `ensure(vararg values)` appends unique throwaway values via an append-preserving whole-document replace keeping every default flag (returning their ids), `remove(...)` drops them, `rawRows()` reads soft-deleted rows too (soft-delete-over-removal asserts), `snapshotValues()`/`replaceDocument()` back document-mutating tests' restore-in-finally, and `withDefaultNamespace(value) { }` temporarily flips the DEFAULT flag (blank-namespace resolution tests) and restores it. Everything else only ever appends unique values.
- `TestLabels` — the label registry is SHARED suite state too: `ensure(key, values, kinds)` (create-if-missing, update-in-place otherwise) → id, `remove(vararg keys)` (soft-deletes), `rawRows()` (soft-deleted rows included). Tests only ever mint UNIQUE keys — `uniqueLabel(prefix, values, kinds)` in `CatalogFixtures.kt` is the one-line mint used by catalog tests applying labels to files.
- `TestTagCategories` — the tag-category registry is SHARED suite state too, same shape as `TestLabels`: `ensure(name, tags, kinds)` → id, `remove(vararg names)`, `rawRows()`. Catalog tests applying tags mint through `uniqueTagCategory(prefix, tags, kinds)` + `uniqueTag(prefix)` in `CatalogFixtures.kt`.
- The younger registry fixtures follow the same shapes: `TestLifecycles` (`ensure`/`remove` — the LIFECYCLE-dictionary sibling of `TestNamespaces`, append-only), `TestAnnotationKeys` (`ensure(key, kinds)`/`remove`/`rawRows` + `uniqueAnnotationKey(prefix, kinds)` in `CatalogFixtures.kt`), and `TestEntityTypes` (`rawRows`/`current` + `withKindTypes(kind, types) { }` — the type dictionaries are seeded SINGLETONS, so tests mutate a kind's list only inside that restore-in-finally wrapper). `TestLenses` (`rawRows` only — the soft-delete-over-removal asserts; lens tests mint unique names per test), and `TestRefTargets.ensure()` (no-arg, JVM-once) seeds the standard resolvable reference targets, while `TestCatalogFiles.overwriteContent(...)` plants legacy-invalid content past the write-time validators.
- `TestBlueprints` (`service`/`rawRows`/`remove(vararg identifiers)`) — the blueprint registry is SHARED suite state like the labels: every test mints unique `bp-<uuid8>` identifiers and removes what it creates; there is no seed to protect. `remove` skips `_`-prefixed identifiers (the V31 system rows are never deleted) and the new `restoreSystemBlueprints()` PUTs `_team`/`_user` back to their seeded shape (`hierarchyRelation: "parent"` on `_team`, none on `_user`) — used in `finally` by any test that extends them. `remove` first removes the blueprint's own active ENTITIES (via `TestEntities`) before soft-deleting the blueprint itself — the V28 delete-with-entities `409` would otherwise fail teardown.
- `TestEntities` (`service`/`rawRows`/`remove(vararg ids)`) — the entity registry is SHARED suite state too, the `TestBlueprints` shape one level down: every test mints unique identifiers within its own throwaway blueprint(s) and removes what it creates; there is no seed to protect.
- `TestSeedState.restoreSeedAccounts()` — bootstrap/production-mode tests rotate the seed admin's password in the SHARED container; call this afterwards so later tests (and re-runs) see the pristine V3 state. Production-mode boots must also override `"mail.transport" to "disabled"` — the dev-default `log` transport is refused in production (`MailTransportTest`).

**Coverage gates.** Backend Kover enforces line- and branch-coverage floors in `server/build.gradle.kts` (`minBound(97)` line, `minBound(78)` branch, wired into `check` via `koverVerify`). The floors sit just below current actuals — the convention is to **re-measure and raise** them as coverage improves, never to lower them for new code: `check` runs only `koverVerify`, so run `./gradlew :server:koverXmlReport` for fresh actuals. Frontend vitest enforces thresholds in `web/vite.config.ts` (`test.coverage.thresholds`: 97 lines / 95 statements / 92 functions / 91 branches, same re-measure convention — the current actuals are noted in a comment beside them); run `cd web && npm run test:coverage`. One class-scoped Kover exclusion exists (`server/build.gradle.kts`, commented in place): the fifteen pure-data blueprint DTOs in `blueprints/Blueprint.kt` plus the four logic-free entity DTOs in `entities/Entity.kt`, whose compiler/kotlinx-serialization synthetic constructors carry one unreachable branch per optional field — a logic-free @Serializable family is the ONLY thing that may join that list.

**Static analysis (detekt).** `./gradlew detekt` runs detekt over `core` + `server` (plain rule sets, no type resolution) and rides `check`, so `build` fails on any finding — the gate is zero findings with **no baseline file**. Repo tuning lives in `config/detekt/detekt.yml`, layered on the bundled defaults; every override there carries a one-line comment naming the deliberate idiom it protects (wildcard Ktor imports, the flat feature-package layout, declarative `*Routes.kt` registrars, the validation-throw convention, guard-clause returns). Fix new findings in code first; extend the config only for a genuinely deliberate idiom, and prefer a config override over `@Suppress` (a per-site `@Suppress` needs a one-line justifying comment). Runs in seconds, no Docker — safe to run anytime, unlike the test suite.

**Frontend static analysis (sonarjs + knip).** The SPA's counterpart, same zero-findings/no-baseline policy: `cd web && npm run lint` carries `eslint-plugin-sonarjs` (recommended set) plus core size/complexity backstops tuned generously for React's one-function-per-page architecture (`cognitive-complexity` 40, `complexity` 50 — backstops against future monsters, not targets); every override in `web/eslint.config.js` carries the idiom comment. `cd web && npm run knip` is the dead-code gate (unused files/exports/dependencies; test files count as entries, so a flagged export is unused even by tests) — the generated `src/api/schema.ts` is excluded from both (type-checked by `tsc`, not style-linted).

**Frontend tests.** Vitest + happy-dom + Testing Library, **co-located** next to the source (`Foo.test.tsx` beside `Foo.tsx`). `src/test/setup.ts` imports `../i18n` and forces `en`, so text assertions match the English resources; `src/test/render.tsx` is the shared wrapper and — like every file-local `MantineProvider` — must pass **`env="test"`** (since Mantine 9.4 the Popover/Combobox dropdown is `display: none` until Floating UI sees a real bounding box, which never happens in happy-dom, so Select-option clicks silently fail without it). `src/test/http.ts` holds the fetch-stubbing helpers. `locales/parity.test.ts` enforces EN↔PL key parity for every shipped language (auto-discovers language folders; also pins folders == `SUPPORTED_LANGUAGES`).

**Runtime OpenAPI conformance.** Every `/api/` interaction the server test suite produces is validated against `documentation.yaml` by the `OpenApiConformance` Ktor client plugin (`server/src/test/kotlin/OpenApiConformance.kt`), installed via the shared `toadieTestClientDefaults()` — so `jsonClient()`/`authedClient()` traffic is checked automatically and drift (undeclared endpoint/method/status, response-schema or content-type mismatch) fails the exercising test with the validation report. Response side is fully validated; request-side validation is ignored except unknown-path/method (tests deliberately send malformed payloads and missing tokens). The published OpenAPI **3.0.3** spec is fed to the validator verbatim, exactly as external consumers receive it; `OpenApiSpecTest` pins the version, nullable-response validation, and that the document stays 3.0-compatible (use `nullable:`, never `type: [..., "null"]`) plus static invariants (unique operationIds, 401/500 declared, paths under `/api/v1/`). `-Dopenapi.conformance=warn|off` relaxes enforcement for drift triage only (default `fail`). A coverage report (exercised vs. declared operation/status pairs) is written to `server/build/reports/openapi-conformance/coverage.md` after each test run — a report, not a gate; the one gap class left deliberately unexercised is every operation's declared `500` (the public API offers no honest way to force one — don't chase them). Tests that use `testApplication`'s default `client` bypass the plugin — prefer `jsonClient()`.

**Production-mode HTTP posture.** `ProductionHttpTest` boots the REAL Netty engine (`EngineMain.createServer` with `-P:` overrides, `ktor.development=false`, `mail.transport=disabled`, rotated seed password + strong JWT secret) because Ktor's test engine never surfaces the header-after-commit failure the HTTPS redirect used to hit: it pins the 301 with the security headers, `X-Forwarded-Proto: https` skipping the redirect (the k8s probe contract, incl. `/healthz` + `/readyz`), and that only `X-Forwarded-Host` may name the redirect target. `ForwardedHeadersTest` pins `http.proxyHops` (rate-limit buckets key on the LAST `X-Forwarded-For` value); `HealthTest` the two probe endpoints in development mode. Restore the seed accounts in `finally` (the production boot rotates the admin).

**Blueprints (V27).** `BlueprintValidationTest` is the pure rule table (one case per rule in
`.claude/docs/port-data-model.md`, no database); `BlueprintReferencesTest` pins the pure
target/rename helpers; `BlueprintTest` drives the routes with Port-shaped JSON bodies and pins
that a response carries NO `null` members (assert on the raw body, not a decoded DTO — the
feature-local `explicitNulls = false` serializer is what keeps the wire Port-native), the
unknown-key 400, the cascade rename observed on the dependent's GET, and the delete 409 naming
the referrers; `BlueprintConcurrencyTest` is the tag-category held-lock proof for a relation
create racing its target's delete (one wins, never a dangling target). `SampleBlueprintsTest`
is executable documentation for `sample-data/blueprints/` — since v1.25.3 the baseline
ontology in `.claude/docs/ontology.md`: it POSTs the eleven files in dependency order, pins the
round trip, and asserts the three contracts the set makes — every registry-mirroring enum
(per-kind types, lifecycles, label value lists, tag categories) equals the seeded registry read
back through the API, the `hierarchyRelation` map forms exactly the org and architecture trees,
and `owned_by → team` is required and single wherever Backstage requires `spec.owner`. (The
v1.23.1–v1.25.2 feature-showcase set and its union assertions were retired with it; the pure
rule tables carry that coverage.) `SampleEntitiesTest` is the same for `sample-data/entities/`
(59 entities, the catalog landscape re-told in the baseline ontology): it loads the blueprint
set, POSTs every entity in dependency order asserting `201` with ZERO findings on create and
re-GET, pins the `properties`/`relations` round trip, and asserts the coverage the README
promises — every property and relation of every blueprint used, every `type`/`lifecycle`
dictionary value used (a dictionary being one `(property, enum)` pair, so `lifecycle` counts
across service/library/api), both `team` shapes, a multi-element relation array, an explicit
`null` relation, and dependency-safe file numbering.

`SystemBlueprintTest` pins the V31 rows — present with `system: true` on a fresh database,
seeded definitions equal `SYSTEM_BLUEPRINT_BASES`, DELETE 409, PUT rename 400, PUT removing/retyping
`email` or removing/reshaping `_user.team` 400, PUT extending with a property + relation +
`hierarchyRelation` 204 and read back, POST `_foo` 400, and `blueprint.updated` carrying
`system: true`.

**Entities (V28).** `EntityValidationTest` is the pure rule table (one case per row in
`.claude/docs/port-data-model.md` "Entities", no database); `EntityReferencesTest` pins the
pure target/rename helpers; `EntityWireNamesTest` pins the wire field names (the Port shape,
not Kotlin defaults); `EntityOwnershipTest` (v1.26.0, phase 4 — 19 cases) is the pure ownership
and format validation: Direct and Inherited team rules on write and read, string/array shapes,
`_team` rename cascade observed on GET (including `format: team` properties), delete `409` then
`204`, `format: user` validation, Inherited computation (following paths, absent on `many: true`,
beyond `MAX_OWNERSHIP_HOPS`), the stale `TEAM_NOT_ALLOWED` after switching blueprints, the `400`
response body's `findings` array, and `teamValueMatches` (the in-memory twin of the SQL
`jsonStringOrArrayContainsFolded` team predicate); `EntityTest` drives the routes end to end — a
USER (no admin needed) creates/reads/updates/deletes, list filter/`q`/sort/paging including an
unknown `blueprint` answering an empty page, **team filter** matching Direct stored values,
`_team` identifiers, AND — v1.30.0 — the effective team of Inherited entities through a two-hop
path, on list and graph, with `total`/paging agreeing and the match following a parent's team
change, unknown-property/
required-missing/relation-target-missing/`TEAM_TARGET_MISSING`/`USER_TARGET_MISSING` `400`s,
the per-blueprint identifier `409` (case-insensitive, but the same identifier is reusable
across different blueprints), PUT changing `blueprint` `400`, delete-with-referrer `409`
naming the referrer from all three sources (relations, `team`, format-properties), rename
cascade observed on the referrer's own GET (covering team and format rewrites), blueprint
delete with active entities `409` naming the count, and pins that a response carries NO `null`
members (the `blueprintJson` posture, assert on the raw body) — plus the STALE case: PUT the
owning blueprint to add a required property, then confirm the entity's GET/list `findings` go
non-empty and its own next PUT is refused `400` until the missing property is supplied.
`SqlHelpersTest` covers `jsonStringOrArrayContainsFolded` rendering. `EntityConcurrencyTest`
is the two-table held-lock proof from `.claude/docs/persistence.md` "Entity targets under
concurrency (V28)": a relation create racing its target's delete (one wins, never a dangling
target), a blueprint delete racing an entity create against it (no orphaned entity survives),
and a `_team` delete racing an entity create against it (same rule applied to ownership).

**Computed properties (phase 5, v1.27.0).** `JqCalculationTest` is the pure jq-bridge suite
(`entities/JqCalculation.kt`, no database): a plain value, string interpolation, a JSON `null`
result → absent, `empty` → absent, the first-of-`.a, .b` win, `range(1e9)` aborting promptly
instead of iterating, a compile error / `error("x")` / a type error (`.a.b` on a number) →
absent, `def f: f; f` → absent, `env`/`$ENV` shadowed even though the real process environment
is non-empty (`System.getenv("PATH")` proving it), `include "x"; .` failing closed with no
module loader ever installed, one compile per distinct expression text regardless of how many
times it is evaluated, and an oversized result → absent, never truncated. `JqCalculationTest`
(v1.29.0) gains bounded-executor cases through `evaluateBounded` with an INJECTED executor —
**hold the executor with a latch, never run a slow jq expression**, so no test strands a real
worker or burns CPU: parity with the synchronous path through the real pool; a deadline miss →
absent and the expression quarantined; a quarantined text is never resubmitted while a
different text still runs; the quarantine WARN is logged once with the `<blueprint>.<propertyId>`
context and no input value; pool rejection → absent, never quarantined, ONE saturation WARN
across many rejections, reset after the next accepted submission; caller cancellation
propagates `CancellationException`, quarantines nothing, and removes the queued task; a deadline
miss while the task is still QUEUED (a 1-worker pool whose worker is latch-held) is absent but
NOT quarantined and logs the saturation WARN once; 200
concurrent coroutines over 8 expressions on the REAL pool yield correct values and
`cacheSize == 8`; and the cache clear-on-overflow case. `UrlFetchTest` pins the shared
`infra/concurrency/BoundedExecution.kt` bridge (`Executor.awaitBounded`) unchanged now that it
is extracted and reused by the jq evaluator. A boot pin: `computed.jq.deadlineMillis = 0` fails
startup, the `security.passwordReset.tokenTtlSeconds` range-validation idiom.
`EntityComputedTest`
is the pure mirror-walk/orchestration suite (`entities/EntityComputed.kt`): single-hop,
two-hop, and `many`-hop mirrors, one-level flattening, structural dedupe, an empty-but-resolved
`[]`, every broken-hop give-up case, the hop budget, a computed terminal of the landed
blueprint answering absent (never recursed into), each `$meta` terminal including the landed
row's own EFFECTIVE `$team`, the fan-out cap, one coercion case per declared `type`,
`computedPathBlueprints`' snapshot widening (including Inherited target ownership paths),
evaluation order (mirror → calculation → aggregation, each in its own declaration order), and
the stale-stored-key collision (computed wins). `EntityAggregationTest`
(`entities/EntityAggregation.kt`) covers inbound/outbound/self-targeting DIRECT candidates,
forward AND reverse `pathFilter` walks (including the `workload → service → system` reverse
shape), a foreign `fromBlueprint`, a malformed `pathFilter` entry, `count` answering `0` as a
value, `average` per period with an injected `now`, and each `property/<func>` including the
Long-vs-Double integral-result rule. `AggregationQueryTest` (`entities/AggregationQuery.kt`)
covers one hit/miss/absent case per operator, both combinators, nested rules, the depth cap,
and the always-array `$team` lookup. `EntityComputedTest`'s calculation cases run under
`runBlocking` (v1.29.0 — evaluation went through the bounded executor and became `suspend`).

`EntityTest` gains the route-level phase 5 cases: computed values appear identically on
create/GET/list, follow a related entity's later change, and are ignored by `q` and by sort; a
computed property id sent on POST or PUT is still `400 COMPUTED_PROPERTY` (unchanged since
phase 2); the `201` create response already carries computed values; Entity graph nodes never
carry any (the `computed = false` snapshot); `entity.created`'s audited `properties` count is
the STORED count, matching `entity.updated`, never the larger computed-inclusive response
count; and a dedicated case aggregates across roughly 200 target entities to prove correctness
at that scale, without asserting on timing.

**Entity graph (V29/V30).** `EntityGraphTest` is the pure builder (`entities/EntityGraph.kt`,
no database): the both-ends rule including a hidden or stale relation target dropped rather
than surfaced as a MISSING node, relation array-value expansion and edge dedupe, the
`hierarchy` flag set only for the edge whose `relation` equals the SOURCE entity's blueprint's
`hierarchyRelation`, the `"<blueprint>|<identifier>"` node-id grammar, and pass-through of
every other field. `EntityTest` carries the route-level graph cases: no filter, `blueprint` as
an IN with two values, an all-unknown `blueprint` list answering an empty graph rather than
`400`, `q` folding (`"Żółw"`/`"zolw"`), `401` for an anonymous caller, and `hierarchy: true`
observed end to end after PUTting a blueprint's `hierarchyRelation` — plus pins that the
response carries NO `null` members. Three cases join `BlueprintValidationTest`'s rule table for
`hierarchyRelation` itself: unset stays absent, naming an unknown relation is `400`, and naming
a `many: true` relation is `400`. `EntityGraphLayoutTest` is the `GraphLayoutTest` twin over the
V30 table PLUS one dedicated case proving the two layout documents are independent — a save
through `/entity-graph-layout` never appears on `/graph-layout` for the same user, and vice
versa. On the frontend, `web/src/test/reactFlowStub.tsx` is the shared React Flow stub
(extracted from `RenderGraph.test.tsx`) that both the Backstage `RenderGraph`/`Hierarchy` page
tests and the new `EntityGraph`/`EntityHierarchy` page tests drive, so the canvas contract is
pinned once rather than duplicated per page.

**Ontology import (v1.28.0).** `BlueprintImportPlanTest` and `EntityImportPlanTest` are the pure
planners (`blueprints/BlueprintImport.kt`/`entities/EntityImport.kt`, no database): one case per
rule in `.claude/docs/port-data-model.md` "Import and export" — decode/validate failures, the
reserved-identifier and unknown-blueprint rules, in-batch duplicates (`CONFLICT`), `EXISTS` vs
`UPDATED` (including a system-blueprint extension accepted/rejected), unknown relation/
aggregation targets, the registry/entity caps (including a cap slot freed by an unrelated
rejection), Kahn ordering with ties, cycle deferral into a two-pass write (a 2-cycle
relation/aggregation for blueprints; an optional back-edge across all three entity reference
sources — relations, `team`, `format: team|user` properties — for entities), and a MANDATORY
entity reference cycle rejected outright (`INVALID` "Circular required reference '<field>'
within the batch") rather than deferred. `BlueprintImportTest`/`EntityImportTest` drive the
routes end to end: the ADMIN guard-before-receive on blueprints versus the any-authenticated
entities gate, anonymous `401`, the 200-document cap and a non-object batch element both `400`,
a mixed batch landing a forward aggregation/relation cycle via pass 2 (observed on GET), a
PUT-shaped blueprint change on an entity staying `INVALID`, and — the load-bearing pin — **dry-run
parity**: `/import/check` returns the identical row set the real run would produce (an `id` only
on `EXISTS`/`UPDATED` rows), storing and auditing nothing, while the real run's `CREATED`/
`UPDATED` rows carry `import: true` on their `blueprint.*`/`entity.*` audit events.
`SampleBlueprintsTest` and `SampleEntitiesTest` each gain one case proving the baseline ontology
sample sets are valid import BATCHES, not just the sequential POST/PUT scripts the earlier cases
pin: the eleven blueprint files POSTed as ONE `/blueprints/import` batch with
`replaceExisting = true` answer `_team`/`_user` → `UPDATED` and the other nine → `CREATED`, with
every re-GET definition matching the two-pass `SampleData.loadBlueprints` shape; the 59 entity
files POSTed as ONE `/entities/import` batch answer all rows `CREATED` with empty `findings` on
re-GET, and an identical second run answers every row `EXISTS`.

**Dependency locking.** The Gradle build resolves against the committed lockfiles (`core/` + `server/gradle.lockfile`, the root `settings-` and `buildscript-gradle.lockfile`; enabled in the root `build.gradle.kts`, DEFAULT lock mode): a transitive version outside the lock state fails resolution. After a dependency change run `./gradlew build --write-locks` and commit the lockfiles; the Dockerfile copies them into the build stage, so a forgotten lockfile also fails the image build.

**Schemathesis (optional manual fuzz pass, not in CI).** Property-based fuzzing of the running stack from the spec: `docker compose up --build` (compose ships dev mode, so `/openapi` is exposed), grab a token — `TOKEN=$(curl -s -X POST localhost:8081/api/v1/login -H 'Content-Type: application/json' -d '{"email":"admin@toadie.local","password":"changeme"}' | jq -r .token)` — then `uvx schemathesis run -c all -H "Authorization: Bearer $TOKEN" --exclude-path /api/v1/logout http://localhost:8081/openapi/documentation.yaml --url http://localhost:8081`. The `/logout` exclusion is load-bearing: fuzzing it **revokes the bearer token** (everything after 401s). Login fuzzing also trips the per-account lockout for `admin@toadie.local` (the spec's example email) — in-memory, so `docker compose restart app` clears it. Expect residual noise from stateful invariants the spec cannot express (rate-limit 429s, TRACE probes); a **`Server error` count above zero is the real signal**. It complements, not replaces, the suite-piggybacked conformance layer above; fuzz junk lives only in the compose volume (`docker compose down -v` resets). Needs `uv` (or `pipx`); no Python dependency lives in the repo.

**E2E scenarios (design artifacts).** The Playwright suite in `e2e/` is governed by `e2e/README.md` (run recipes, the parallel state-ownership rulebook, the coverage map). Every spec has a **natural-language scenario file** in `e2e/scenarios/` — versioned, deliberately non-executable design artifacts (actors, owned state, numbered user-level steps, expected outcomes; `## Scenario:` headings equal the `test()` titles verbatim). `e2e/scenarios/README.md` holds the format and the **compiler contract** — the house rules any human/agent/tool must satisfy when turning a scenario into spec code. Same-commit rule: a new or behaviorally changed test lands with its scenario file and its one-line entry in the e2e README's coverage map; `cd e2e && npm run check:scenarios` enforces the parity mechanically (both directions, orphan files included; `accessibility.spec.ts` is the one registered template-title skip), and `npm run typecheck` covers what Playwright's transpile-only TS handling never checks.

### Reset-link regressions (V26)

`PasswordResetServiceTest` uses an injected clock (no expiry sleeps) and separate service
instances over the same Testcontainers database to exercise digest-only storage, expiration,
same/sibling-token races, credential/identity changes, deletion, and self-password CAS races.
`PasswordResetTest` and `PasswordResetConfirmationTest` use conformant HTTP clients and await
recipient-scoped delivery/audit events to test no mutation before confirmation, replay,
session/MFA revocation, password policy, throttles, missing configuration, and mail failures.
Frontend tests assert fragment removal, no GET-side API call, no JWT/refresh on confirmation,
no token persistence, inline validation/errors, and success-only local session cleanup.
The Mailpit journey uses a throwaway account with cleanup in `finally`; isolated stacks can
set `E2E_MAILPIT_URL` alongside `E2E_BASE_URL`. Never apply V26 to a user's running database
merely to verify a changeset: use Testcontainers and a disposable Compose project/volume.
