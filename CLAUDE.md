# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Gradle wrapper is at `./gradlew` (use `gradlew.bat` on Windows). JDK 21 toolchain is required (auto-provisioned via foojay-resolver; the local dev JDK is pinned in `mise.toml`).

- Build everything: `./gradlew build`
- Run the server (Ktor + Netty on port 8081): `./gradlew :server:run`
- Run all tests: `./gradlew test`
- Run server tests only: `./gradlew :server:test` (needs a Docker daemon — Testcontainers)
- Run a single test: `./gradlew :server:test --tests "ch.nokillswit.ServerTest.security headers are set on responses"`
- Static analysis (detekt, both Kotlin modules): `./gradlew detekt` — rides `check`/`build`, zero-findings gate (no baseline file). Rule tuning lives in `config/detekt/detekt.yml` ONLY, one commented override per deliberate repo idiom; never add an uncommented `@Suppress`.
- Dependency locking is ON (root `build.gradle.kts`): after ANY dependency change run `./gradlew build --write-locks` and commit the updated lockfiles (`core/gradle.lockfile`, `server/gradle.lockfile`, the root `settings-gradle.lockfile` + `buildscript-gradle.lockfile`); a "lock state" resolution error means the lock is stale — regenerate it, never delete the file.
- Package the server for deployment: `./gradlew :server:installDist`. **Never use `:server:buildFatJar`** — the fat JAR breaks Flyway's `ServiceLoader` discovery and NPEs at startup.
- JVM memory flags are pre-tuned in `server/build.gradle.kts` (`applicationDefaultJvmArgs`) — the rationale is commented in place.
- **Run the whole stack with one command: `docker compose up --build`** (only Docker required). See "Running the full stack" below.
- Frontend: `cd web && npm install --legacy-peer-deps`, then `npm run dev|build|lint|test|test:coverage|knip|gen:api` (details in `web/CLAUDE.md`).
- E2E: `cd e2e && npm ci && npx playwright install chromium && npm test` (plus `npm run typecheck` and `npm run check:scenarios`).

## Automated verification

Catalog mutations and their structural history events commit in one transaction. Keep event
insertion inside the service write, with a required actor; routes emit security audit logs only
after success. Imports retain one transaction per document. Fault-injection tests must prove
history failures roll back the full file state and leave adjacent successful import rows intact.
Preserve no-op PUT suppression, always-recorded syncs, redaction, and truthful concurrent diffs.

Tag-category create, replace, and soft-delete serialize in PostgreSQL before reading registry
state. The ownership check and mutation share one transaction, so overlapping claims cannot
assign a tag to different active categories. Preserve the complete losing category on `409`,
the concurrent 200-category limit, and existing authorization/validation/404 precedence.
See `.claude/docs/persistence.md` and `.claude/docs/testing.md` for locking and regression rules.

Graph-layout writes must wait for a successful baseline load and preserve the complete
`{mode, positions, collapsed}` document, including filtered-out node ids. The dedicated
persistence hook serializes saves, coalesces queued edits, and retains failed local changes
for explicit retry. Preserve ordinary SPA navigation, account isolation, and React Flow's
live drag state; see `web/CLAUDE.md` and `.claude/docs/testing.md` for the lifecycle rules.
Shared HTTP refresh must also respect login boundaries: late responses cannot restore a
signed-out session, replay an old mutation, or clear a newer login.

YAML comparisons in repo sync and Overwrite with YAML must bound both detailed LCS work
and rendered row count. Large inputs fall back to complete stored/replacement YAML panes,
with no truncation or change to confirmation payloads. Limits and regression expectations
are documented in `web/CLAUDE.md` and `.claude/docs/testing.md`.

Outbound catalog fetches have one 10-second deadline across validation/DNS, connection,
headers, and the complete bounded body. Caller cancellation must propagate unchanged;
only the fetch's own deadline and upstream failures become safe 502 problems. Resolve once,
reject any non-public address, and pin direct connections to that validated address snapshot
while retaining the URL hostname for TLS verification. Each fetch owns its connection pool;
proxies, redirects, and connection reuse between fetches must not bypass validation. The whole
exchange uses a bounded worker pool/queue. Native DNS can still ignore interruption and hold
one of those workers until the OS returns; cancellation must prevent a late HTTP request.
See `.claude/docs/security.md` and the regressions in `.claude/docs/testing.md` before changing
this path.

CI regressions include the Errors page with its real report request held pending: loading
spinners must expose a named `status`, not an `aria-label` on a generic span. Heavy catalog
save-flow test fixtures use paste events; keyboard behavior keeps real typing. Preserve the
existing timeouts, coverage floors, and fail-on-flaky policy (see `.claude/docs/testing.md`).

For entering Mantine dialogs, e2e uses `readyDialog(page, title)` to wait for computed opacity
one before interacting: DOM visibility alone can pass before a deferred slide starts. The
Lenses cleanup regression matches mutations to exact resource IDs, asserts their status, and
waits for modal/row disappearance before continuing. `dialog-readiness.spec.ts` holds a real
editor's entrance to pin that distinction without sleeps, click retries, or disabling animations.

One-time password capture also waits for the named creation dialog's entrance and the
reveal control's `aria-pressed=true` state before reading the code block. PR #6's Types
trace showed a completed click followed by capture of the mask and a correct login 401;
the shared helper's suppressed-click regression prevents this without changing authentication.

CI uses the complete Temurin version from `mise.toml` (`21.0.11+10.0.LTS`); keep the build/LTS suffix. `setup-java` cannot resolve the truncated `21.0.11+10` selector. The infrastructure regression suite pins CI/local version parity.

`.github/workflows/ci.yml` runs on pushes, PRs, merge queues, and manual dispatch. Its **Quality gate** requires backend build/coverage, frontend build/lint/knip/coverage + Spectral/schema-drift checks, and the reusable E2E workflow to succeed. After pushing, configure **Quality gate** as a required status in the GitHub repository ruleset; YAML alone cannot enforce merges. E2E owns a disposable `toadie-ci` Compose project on a fresh hosted runner, collects diagnostics, and removes only that project's volume even after setup failures. Local `npm test` preserves the development database. CI fails flaky browser tests and missing Mailpit. See `.claude/docs/testing.md`, `e2e/README.md`, and `HARDENING.md` for the staged follow-up work.

## Running the full stack

Compose publishes the app, PostgreSQL, and Mailpit on **127.0.0.1 only**. The demo JWT key, database password, and captured reset/MFA mail must not be exposed on a LAN. Changing `MAIL_APP_URL` changes links, not bindings or production security. Existing containers retain old bindings until recreated; keep their database volume.

`docker compose up --build` serves everything at `http://localhost:8081` (sign in as `admin@toadie.local` / `changeme`); local dev is `docker compose up postgres` (host port **5433**) + `./gradlew :server:run` + `cd web && npm run dev` (Vite on **5174**, proxying `/api` to :8081). The compose stack bundles **Mailpit** (`http://localhost:8026`) and wires the app's password-reset and MFA email to it (`MAIL_TRANSPORT=smtp`). Ports deliberately avoid Lettuce's 8080/5432/5173/8025 so both stacks can run side by side. Kubernetes (OrbStack) deployment targets the dedicated `toadie` namespace — see `k8s/templates/secret.yaml`'s header for the secret-creation command.

## Architecture

Toadie is a Backstage `catalog-info.yaml` helper (visual creation with validation, cross-file reference checks, combined rendering). **All three product pillars are implemented on top of the full stack + tooling + auth: visual creation of catalog files across the seven landscape kinds — Component, API, System, Domain, Resource, Group, User, each visually marked with its TIER (the fill-in priority 1–4: Domain/System/Group, Component, Resource/API, User — `KIND_TIERS` + `KindTierDot` in `web/`, a purely visual marker, never enforced) — (stored server-side, per-kind validation, full CRUD + list — filterable by name/namespace/kind/tag/type/lifecycle/owner/label, the same filter panel riding the Hierarchy, Graph, and Errors views — where the filters select what is SHOWN on Files/Hierarchy/Graph (the graph's stored nodes ARE the list's rows, and an edge is drawn only when both of its ends are shown) and what is REPORTED on Errors (whose references keep resolving workspace-wide), saveable as named **Lenses** — per-user filter snapshots with PRIVATE/PUBLIC visibility, applicable from any of the four views (`lenses/`) — live YAML preview/download) with namespaces constrained to the ADMIN-curated `namespaces` dictionary (the `/namespaces` page; strict server-side enforcement on every write, blank/omitted resolving to the entry flagged as DEFAULT) and labels constrained to the ADMIN-curated label registry (the `/labels` page; each label = a key + a CLOSED value list + the kinds it applies to, seeded by V22, strictly enforced on every write) and annotation KEYS constrained to the ADMIN-curated annotation-key registry (the `/annotations` page; each key + the kinds it applies to — values stay free strings, seeded by V22, strictly enforced on every write) and tags constrained to the ADMIN-curated tag categories (the `/tags` page; an internal grouping concept — each category = a name + its tags, each tag in exactly ONE category + the kinds they apply to, seeded by V22, strictly enforced on every write), and `spec.type` constrained to the ADMIN-curated per-kind type dictionaries (the `/types` page; one independent list per type-bearing kind — User has none — seeded by V15 and re-curated by V22, strictly enforced on every write), and `spec.lifecycle` constrained to the ADMIN-curated GLOBAL lifecycles dictionary (the `/lifecycles` page; one shared list for every lifecycle-bearing kind, seeded experimental/production/sunsetting/deprecated in that progression order, strictly enforced on every write), and entity references strictly enforced on every write (each reference — `spec.owner`, `spec.system`, `spec.dependsOn`, … — must RESOLVE to a stored entity of a kind the field allows, e.g. owner → Group/User, and never to the entity ITSELF; unresolved, wrong-kind, or self references are a `400`) — **all of these registry/reference rules are SOFT: strict by default, but `allowInvalid=true` on create/replace waives them and stores the document anyway (the editor's Save-anyway modal; import ALWAYS waives, reporting `CREATED_WITH_FINDINGS` rows), while the structural descriptor rules and namespace resolution stay hard** — cross-checking (the **Errors** report at `/errors` — EVERY error class in the stored files: references AND the registry checks, plus the report-only `STRUCTURE_INVALID`/`NAMESPACE_NOT_ALLOWED` verdicts over stored content whose rules stay HARD on writes; the shared filter set narrows what's reported and error-type pills filter client-side — + the live findings panel in the editor — which since v1.14 also paints the verdicts onto the fields and pills themselves: red for hard validation, orange for soft findings; soft findings arise from waived saves, deletions — dangling references appear that way by design — and registry rows removed after the fact, and every soft finding blocks the file's next STRICT save), rendering-together (the `/graph` relationship graph — React Flow + dagre over `GET …/graph`, nodes faced name + type with a hover tooltip and, when the visible graph spans several namespaces, clustered per namespace inside labelled frames, and since v1.18.0 **collapsible**: a node folds its containment descendants (the Hierarchy's definition, `buildHierarchy` reused) out of view and stands in for their relations as dashed, counted edges, the collapsed set persisted in the per-user layout document `{mode, positions, collapsed}` — nav label "Graph", plus the `/` **Hierarchy** view: the same graph data as collapsible containment trees with the Files operations per row — including **Pin**, which narrows the tree to one entity and its descendants on top of the filters until it is unpinned (persisted per view, dropping itself when its entity leaves the filtered set) — shaped client-side in `web/src/utils/hierarchy.ts`), plus the YAML round-trip (client-parsed multi-document import with per-row report-&-skip results at `/files/import`, one-file export, and the per-file **Overwrite with YAML** action — the sync modal's diff-and-confirm shape over pasted/picked YAML, writing through the ordinary PUT), **and source references & repo sync (v1.11.0): an optional per-file `sourceUrl` — the https address of the file's canonical copy in a GitLab/GitHub repo, envelope row state that never enters the document/export — set in the editor's Source fieldset or stamped automatically (synced, with baseline) by a fetch-from-URL import; the Files list's sortable Last-sync column (`lastSyncedAt`, 0 = never; DB-side drift = `updatedAt > lastSyncedAt`), the Sync-from-repo modal (Operations dropdown — fetch via the SSRF-guarded `POST …/fetch`, client-side YAML parse, side-attributed line diff against the stored `synced_content` baseline, confirm-required overwrite via `POST …/{id}/sync` which ALWAYS waives soft findings, the import posture); a missing reference is the report-only `SOURCE_MISSING` Errors finding, and DB→repo sync deliberately does not exist.** **and per-file change history (v1.15.0): every catalog mutation appends an immutable, STRUCTURAL event (`catalog_file_events`, Lettuce's `EventLogTable` machinery ported into `infra/db/EventLog.kt`) — created (the import loop marks its origin) / updated / synced / deleted, each UPDATED and SYNCED event carrying the FIELD-LEVEL diff of the document as `changed` plus `<path>.from/.to/.added/.removed` params; no rendered string is ever stored, and free text (`metadata.description`, `spec.definition`) is recorded as the bare FACT that it changed. Read back through the paged `GET …/{id}/events` (any authenticated user — whoever reads the file reads its history) and rendered in the viewer's language as the editor's History section (`components/CatalogFileHistory.tsx`); a no-op save records nothing, a sync records even when the repo copy matched, and the deletion event outlives the soft-deleted file it belongs to.** The architecture deliberately mirrors [Lettuce](https://github.com/liveweird/lettuce) — when adding another capability Lettuce already has (field encryption, notifications, teams/management chains…), port Lettuce's implementation rather than inventing a new one.

Multi-module Gradle build (Kotlin DSL) defined in `settings.gradle.kts` with two Kotlin modules plus a separate JS frontend in `web/`:

- **`core`** — Kotlin Multiplatform (JVM target only currently). Shared code consumed by `server`. Holds the OpenTelemetry SDK bootstrap (`getOpenTelemetry(serviceName)`).
- **`server`** — Kotlin/JVM. The Ktor application. Depends on `core`.
- **`web/`** — Vite + React + TypeScript SPA that consumes the server's HTTP API. Standalone npm workspace; Gradle does not touch it.
- **`e2e/`** — Playwright blackbox suite against the compose stack. Standalone npm workspace.
- **`sample-data/`** — a 34-entity multi-document `catalog-info.yaml` (plus its README) speaking the V22-seeded registry vocabulary, loaded BY HAND through the Import page or `POST …/import` to exercise the app; and `sample-data/blueprints/` — since v1.25.3 the eleven-blueprint BASELINE ontology itself (`01-team.json` … `11-workload.json`, dependency-ordered; designed in `.claude/docs/ontology.md`, v1.25.2; the earlier feature-showcase set is retired), loaded BY HAND via `sample-data/blueprints/load.sh` (`POST …/api/v1/blueprints` in two passes since v1.27.0 — aggregation properties whose target loads later are stripped on the first pass and PUT back on a second, once every target exists) or, since v1.28.0, the `/ontology/import` page (multi-select the eleven files, switch on Replace existing definitions first — `01-team.json`/`02-user.json` are `_team`/`_user` extensions); and `sample-data/entities/` — eleven numbered JSON files of `POST …/api/v1/entities` bodies, one per blueprint in the same dependency order, 59 entities re-telling the `catalog-info.yaml` landscape in the baseline ontology (every property, relation, type and lifecycle value used at least once, zero findings), loaded BY HAND via `sample-data/entities/load.sh` after the blueprint set, or the same Import page. Deliberately NOT seeded: no migration inserts catalog files or entity instances, so every environment starts with an empty workspace (except V31's two system blueprints `_team` and `_user`, which the sample only extends with extra properties/relations).

Group is `ch.nokillswit`, version `1.0.0-SNAPSHOT` (set in root `build.gradle.kts`). Dependency versions are centralized in `gradle/libs.versions.toml`; Ktor itself comes from a separate version catalog (`ktorLibs`) loaded from `io.ktor:ktor-version-catalog` in `settings.gradle.kts`.

### The Backstage descriptor format (the domain reference)

**`.claude/docs/backstage-descriptor-format.md` is the local offline reference for the `catalog-info.yaml` format** Toadie exists to create, cross-check, and render — the envelope, metadata validation rules, all kinds and their spec fields, entity-reference resolution defaults, substitutions, and well-known annotations. Consult it when designing any catalog feature instead of browsing; the upstream source it snapshots is <https://backstage.io/docs/features/software-catalog/descriptor-format/> — re-check upstream (and update the snapshot) when adding a new validation rule.

### The Port data model (the direction of travel)

**Toadie is moving from Backstage's fixed System Model to [Port.io](https://docs.port.io/context-lake/data-model/configure-data-model/)'s ontology in phases** — user-definable **blueprints** (typed properties, relations, mirror/calculation/aggregation properties, ownership) replace the seven predefined kinds, and **entities** are instances attached to a blueprint. Phase 1 (v1.23.0) is the `blueprints/` registry: create/edit/list/delete blueprint definitions in Port's native JSON shape, ADMIN-curated and readable by everyone, with nothing in the catalog/kinds machinery changed yet. Phase 2 (v1.24.0) is the `entities/` package: instances of blueprints — any authenticated user may create/read/update/delete any entity (the catalog-file shared-workspace rule, no admin gate), `properties` typed by the owning blueprint's `schema`, `relations` naming other entities of the target blueprints, and strict per-write validation (`entityFindings`) reused unchanged by every GET/list `findings` field so an entity left stale by a later blueprint edit is visibly flagged without a background job; `catalog/` itself is untouched by this phase. Phase 3 (v1.25.0): the Entity graph and Entity hierarchy pages under Port Ontology, driven by the Toadie-only `hierarchyRelation` — a per-blueprint pointer at one of its own `many: false` relations naming the entity hierarchy's parent link, stored beside the Port document (V29) so the document itself stays untouched; `GET /api/v1/entities/graph` renders entities and their relations together the way `catalog/Graph.kt` does one level down, with its own per-user layout persistence (V30) independent of the Backstage Graph page's. Phase 4 (v1.26.0): users and teams — Port's `_team`/`_user` SYSTEM blueprints seeded by V31 (protected, extendable) and `$team` ownership on every entity (the `team` field validated against `_team`, Inherited ownership computed from `ownership.path`, a `team` filter on the entity list/graph (since v1.30.0 matching the EFFECTIVE team, Inherited ownership included) and `$team` edges on the Entity graph); login accounts stay separate from `_user` entities, and ownership is informational — it never gates permissions. Phase 5 (v1.27.0): computed properties — `mirrorProperties`, `calculationProperties` (real jq 1.6 via `net.thisptr:jackson-jq` 1.3.0) and `aggregationProperties` are evaluated at entity READ time and merged into every GET/list/create response's `properties` (computed wins over a stale stored key; an unresolvable value is simply absent, never a finding), while `POST`/`PUT` still reject a computed property id as write input (`COMPUTED_PROPERTY`, unchanged since phase 2); evaluation runs OUTSIDE the database transaction (`entities/JqCalculation.kt`, `EntityComputed.kt`, `EntityAggregation.kt`, `AggregationQuery.kt`) so a pathological expression or a wide aggregation fan-out never pins a pooled connection or the entity write lock; since v1.29.0 each jq expression additionally runs on a bounded `entity-jq` worker pool under a per-expression deadline (default 500 ms), and an expression that misses it is quarantined until edited. Phase 6 (v1.28.0): ontology import and export — `POST /api/v1/blueprints/import`+`/import/check` (ADMIN) and `POST /api/v1/entities/import`+`/import/check` (any authenticated) accept a batch of up to 200 raw Port-shaped JSON documents, report-and-skip per row with ONE shared classification for the real run and its dry-run; a pure planner topologically orders each batch over its own sibling relation/aggregation/`team`/format-property references and defers what a document's first write cannot yet resolve into a second pass (an entity's MANDATORY reference cycle is rejected outright instead), and a `replaceExisting` switch turns an `EXISTS` row into a full `UPDATED` PUT (extending `_team`/`_user` too); the `/ontology/import` page (Port Ontology nav) and a client-side JSON export from the Blueprints/Entities pages complete the round trip — the exported shape is exactly what the import accepts back unchanged. **`.claude/docs/port-data-model.md` is the local offline reference for Port's blueprint AND entity rules** (every property type/format/colour, the relation/mirror/calculation/aggregation/ownership grammars, meta-properties, the identifier-charset assumption, and — since phase 2 — the entity wire shape, its value-validation rule table, and the lifecycle rules) — consult it when designing any blueprint/entity feature instead of browsing, and update it when a rule is added. **`.claude/docs/ontology.md` is the baseline ontology itself** (v1.25.2): the eleven blueprints under `sample-data/blueprints/`, their Backstage round-trip contract and the design decisions — the model every future catalog feature assumes, pinned by `SampleBlueprintsTest`.

### API guidelines (the authoritative API standard)

**`api-guidelines/API-GUIDELINES.md` is the single authoritative rulebook for API style** — document shape, URLs, versioning, list conventions, naming, data formats, status codes, errors, auth, caching, rate limiting, idempotency, security, and OpenAPI/conformance practice. Every rule has a stable ID (`API-LIST-002`); cite IDs when discussing API design. Validate spec changes with the `/api-review` skill (Spectral lint + LLM review checklist).

### Server bootstrap model

`server/src/main/kotlin/main.kt` just delegates to `io.ktor.server.netty.EngineMain`. The application is wired declaratively in `server/src/main/resources/application.yaml` under `ktor.application.modules` — each entry is a fully-qualified extension function on `Application` (e.g. `ch.nokillswit.plugins.HttpKt.configureHttp`). **Module order is load-bearing**: plugins → infra (Mail → Flyway → Database → Bootstrap; Database is the composition root that publishes every service into `Application.attributes` via `AttributeKey`s) → feature route modules → `RoutingKt.configureRouting` strictly last (the SPA catch-all). To add a cross-cutting concern, create a `configureXxx()` extension under `plugins/` and register it in `application.yaml`; do not call it from `main.kt`. There is no DI framework — services travel via `attributes`.

### Package layout

Source files sit flat under `server/src/main/kotlin/<area>/` but declare `package ch.nokillswit.<area>` (no `ch/nokillswit` directory nesting — a deliberate idiom, protected by the `InvalidPackageDeclaration` detekt override).

```
ch.nokillswit
├── main.kt
├── plugins/            cross-cutting Ktor wiring (configureXxx that only `install` plugins):
│                       Http, SecurityHeaders, Monitoring, Serialization, Security (JWT),
│                       ErrorHandling (RFC 7807), OpenTelemetry, AutoHeadResponse, Resources,
│                       Routing (SPA catch-all), Health (/healthz liveness + /readyz readiness —
│                       the k8s probes; unauthenticated, outside /api/, registered after the infra group)
├── infra/mail/         outbound email (Lettuce's, ported): Mailer/SmtpMailer/LogMailer +
│                       LocalizedText (the recipient-language content layer) +
│                       configureMail — MAIL_TRANSPORT log/smtp/disabled, the log-transport
│                       production refusal (fail-closed), null mailer = email features 503.
│                       Consumers: self-service password reset and email MFA
├── infra/db/           Flyway bootstrap + the R2DBC connection/composition root + the seed
│                       bootstrap (admin rotation, prod fail-closed) + Sql.kt (containsNormalized,
│                       jsonArrayContains, orVanished) + EventLog.kt/JsonParams.kt (Lettuce's
│                       shared per-record audit-event machinery — the EventLogTable base behind
│                       catalog_file_events, the first and so far only clone) + Locking.kt
│                       (lockingTransaction — the shared cooperating-writer table-lock helper
│                       behind BlueprintService/TagCategoryService/EntityService's V27/V11/V28
│                       protocols)
├── infra/paging/       the shared list-endpoint machinery (PageRequest/parsePaging/applyPaging/
│                       PageResponse + the strict query-param readers) — Lettuce's, ported verbatim
├── infra/validation/   cross-feature input helpers (sanitizeSingleLine — trim + control-char 400,
│                       requireNoDuplicates) + InvalidPayloadException, the one findings-bearing 400
│                       shape (catalog strict save, entity save) — ErrorHandling.kt renders it feature-free
├── infra/importing/    shared ontology bulk-import vocabulary (phase 6, v1.28.0 — the
│                       `catalog/CatalogFileImport.kt` precedent, one level up): ImportBatch.kt —
│                       `MAX_IMPORT_DOCUMENTS` (200), `OntologyImportStatus`, the per-row
│                       `decodeDocument`/`rawString`/`requireBatchSize` helpers, and
│                       `orderWithDeferral` (the Kahn topological-sort skeleton — the
│                       in-degree/queue loop and its input-order tie-break — shared by
│                       `blueprints/BlueprintImport.kt`'s `orderCandidates` and
│                       `entities/EntityImport.kt`'s `attemptOrdering`; the per-kind edge
│                       collection and the cycle policy stay in each caller) behind
│                       `blueprints/BlueprintImport.kt` and `entities/EntityImport.kt`
├── infra/concurrency/  BoundedExecution.kt — Executor.awaitBounded, the suspend-with-cancellation
│                       bridge to a bounded ThreadPoolExecutor, extracted from
│                       catalog/UrlFetch.kt; consumers: the URL fetch and the jq evaluator
├── audit/              security audit trail: `audit(event, fields…)` → AUDIT-marked structured logs
├── authz/              CallerPrincipal + guards (requireAdmin, requireSelfOrAdmin) + typed
│                       HTTP exceptions (401/403/404/409/429/502)
├── auth/               POST /api/v1/login (+ the email-MFA branch and /login/mfa second
│                       step — MfaChallenges/MfaEmail), /refresh, /logout + the self-service
│                       POST /api/v1/password-reset (uniform 202, async single-use link delivery)
│                       + /password-reset/confirm (atomic password/grant consumption, V26)
│                       + PasswordResetThrottle + token minting + password hashing
│                       + LoginThrottle + the revoked-token blocklist
├── users/              the user domain: ADMIN-only management CRUD (/api/v1/users list/create
│                       + {id} get/put/delete with the self-delete 403 and last-admin 409
│                       protections) + PUT /api/v1/users/{id}/password + the per-user feature
│                       flags (Feature enum + PUT {id}/features, the V12 disabled-set model;
│                       MFA is the inverted-default login-scoped flag) + the per-user
│                       language (V18: PUT {id}/language, self-or-admin — the ONE synced
│                       UI+email language) + the per-user Graph layout (V19: GET/PUT
│                       {id}/graph-layout, self-or-admin — the Graph page's Auto/Manual
│                       modes + dragged positions; GraphLayout.kt/GraphLayoutService.kt,
│                       deliberately unaudited) + its Phase 3 twin, the Entity graph's OWN
│                       layout (V30: GET/PUT {id}/entity-graph-layout, same shape/rules over
│                       an independent table — GraphLayoutService generalized to take its
│                       Exposed table object, GraphLayouts/EntityGraphLayouts) + Validation.kt
├── dictionaries/       admin-curated ordered value lists (Lettuce's dictionaries, single-
│                       valued — no translations): Dictionary.kt (the Dictionary enum whitelist
│                       + DTOs + validateDictionaryUpdate), Languages.kt (SUPPORTED_LANGUAGES —
│                       the V18 per-user-language whitelist), DictionaryService.kt (whole-document
│                       replace: soft-delete-first reconcile, positions rewritten from payload
│                       order — no reorder endpoint), DictionaryRoutes.kt —
│                       GET /api/v1/dictionaries/{slug} (any authenticated, unpaged) +
│                       PUT (ADMIN). Two dictionaries: NAMESPACE ("namespaces") — the
│                       allowlist every catalog-file write's namespace must be in; exactly
│                       one entry flagged isDefault (what blank namespaces resolve to) —
│                       and LIFECYCLE ("lifecycles") — the GLOBAL allowlist every write's
│                       non-blank spec.lifecycle must be in; NO default (flags rejected;
│                       the per-dictionary usesDefault branch in validateDictionaryUpdate)
├── labels/             the ADMIN-curated label registry (per-entity CRUD — the nested
│                       key+values+kinds shape doesn't fit the flat dictionary): Label.kt
│                       (DTOs + sanitizedLabelRequest + validateLabelRequest — key/value
│                       grammar borrowed from catalog's validators), LabelService.kt (one
│                       row = one label; allowed values/kinds as JSON arrays in TEXT),
│                       LabelRoutes.kt — GET /api/v1/labels (any authenticated, unpaged) +
│                       POST/PUT/DELETE (ADMIN). The whitelist every catalog-file write's
│                       metadata.labels is checked against: key registered, kind allowed,
│                       value in the label's closed list (strict, no grandfathering)
├── lenses/             saved filter sets (the labels/ CRUD template + catalog's created_by
│                       join): Lens.kt (LensVisibility PRIVATE/PUBLIC + the LensFilters
│                       payload — the nine shared filter slots, validated STRUCTURALLY
│                       only, never against the registries — + sanitized/validateLensRequest),
│                       LensService.kt (one row = one lens; filters as one JSON object in
│                       TEXT; list = own + everyone's PUBLIC; mutationVerdict decides
│                       creator-only mutations in-transaction), LensRoutes.kt —
│                       GET/POST /api/v1/lenses + PUT/DELETE {id} (ALL any-authenticated:
│                       no admin gate anywhere, ADMIN gets no special access; foreign
│                       PRIVATE/unknown → 404, foreign PUBLIC → 403 — the hybrid
│                       disclosure policy in authorization.md). Backs the LensPicker on
│                       the Hierarchy/Files/Graph/Errors views
├── annotations/        the ADMIN-curated annotation-key registry (the labels/ template
│                       minus the value dimension — annotation VALUES stay free strings):
│                       AnnotationKey.kt (DTOs + sanitized/validateAnnotationKeyRequest —
│                       key grammar from catalog's validateKey; server-written keys
│                       rejected), AnnotationKeyService.kt (one row = one key; kinds as a
│                       JSON array in TEXT), AnnotationKeyRoutes.kt —
│                       GET /api/v1/annotation-keys (any authenticated, unpaged) +
│                       POST/PUT/DELETE (ADMIN). The whitelist every catalog-file write's
│                       metadata.annotations KEYS are checked against: key registered, kind
│                       allowed (strict, no grandfathering; empty registry = no annotations)
├── tags/               the ADMIN-curated tag categories (an INTERNAL Toadie concept — not
│                       in the Backstage schema; the labels/ template): TagCategory.kt
│                       (DTOs + sanitized/validateTagCategoryRequest — tag grammar + kinds
│                       helpers borrowed from catalog's validators), TagCategoryService.kt
│                       (one row = one category; tags/kinds as JSON arrays in TEXT; the
│                       one-category-per-tag 409 enforced after a transaction-scoped PostgreSQL write lock),
│                       TagCategoryRoutes.kt — GET /api/v1/tag-categories (any
│                       authenticated, unpaged) + POST/PUT/DELETE (ADMIN). The whitelist
│                       every catalog-file write's metadata.tags is checked against: tag
│                       registered, its category's kinds allow the file's kind (strict)
├── types/              the ADMIN-curated per-kind type dictionaries (an INTERNAL Toadie
│                       constraint on the open `spec.type` field; the labels/tags template):
│                       EntityTypes.kt (DTOs + sanitized/validateEntityTypesRequest — the
│                       exact spec.type rule via catalog's validateSingleWord; only
│                       TYPE_BEARING_KINDS, i.e. all but User), EntityTypesService.kt (one
│                       row = one KIND's list; types as a JSON array in TEXT; kind unique
│                       among active rows, dictionaries INDEPENDENT — no cross-row check),
│                       EntityTypesRoutes.kt — GET /api/v1/entity-types (any authenticated,
│                       unpaged, ≤6 rows) + POST/PUT/DELETE (ADMIN). V15 seeds the
│                       well-known Backstage values per kind, V22 re-curates them. The
│                       whitelist every catalog-file write's spec.type is checked against
│                       (strict; a kind with no dictionary allows NO types)
├── blueprints/         Port.io-style user-definable entity kinds (v1.23.0, Toadie-first —
│                       Lettuce has none): Blueprint.kt (the Port-native wire DTOs — a typed
│                       skeleton with JsonElement only for Port's open sub-trees: `default`,
│                       `enum`, object `properties`/`patternProperties`/`additionalProperties`,
│                       aggregation `query.rules`/`pathFilter` — plus `blueprintJson`, the
│                       explicitNulls=false serializer used for BOTH the stored TEXT and the
│                       responses so unset optionals are ABSENT, never null),
│                       BlueprintValidation.kt + PropertyValidation.kt (the data-driven
│                       applicability table + one thrower per rule; every rule in
│                       .claude/docs/port-data-model.md), BlueprintReferences.kt (pure
│                       `blueprintTargets`/`withTargetRenamed`), BlueprintService.kt (one row =
│                       one blueprint: identity columns + one `definition` JSON; every mutation
│                       under the tag-category table lock — relation/aggregation targets must
│                       exist (self allowed), an identifier RENAME cascades into every row
│                       targeting it in the same transaction, deleting a targeted blueprint is
│                       409 naming the referrers, or that has active ENTITIES 409 naming the
│                       count (V28)); Phase 3's `hierarchyRelation` (V29 — a nullable column
│                       beside `definition`, so the stored Port document stays byte-identical)
│                       names one of the SAME row's `relations` with `many == false`, validated
│                       in BlueprintValidation.kt, read/written alongside `definition`,
│                       BlueprintRoutes.kt — GET /api/v1/blueprints
│                       + GET {id} (any authenticated, unpaged, ≤200) + POST/PUT/DELETE (ADMIN,
│                       guard-before-read). V31 (v1.26.0, phase 4 — `SystemBlueprints.kt`)
│                       seeds `_team` and `_user` system blueprints (flagged `system: true`,
│                       protected against deletion and base-shape removal); ADMIN may extend
│                       both. No other seed: custom blueprints start empty. BlueprintImport.kt
│                       (phase 6, v1.28.0) — `POST …/blueprints/import` + `/import/check`, ADMIN
│                       guard-before-receive: the pure ordering+deferral planner
│                       (`planBlueprintImport`, one Kahn topological sort over sibling
│                       relation/aggregation targets, cycle/forward-reference deferral into a
│                       two-pass write) plus its effectful `BlueprintService.import`/
│                       `importCheck` callers, reusing `create`/`update` per row under the same
│                       V27 lock
├── entities/           instances of a blueprint (v1.24.0, Phase 2 of the Port data-model
│                       move): Entity.kt (the wire DTOs — EntityRequest/Entity/EntityFinding,
│                       reusing `blueprintJson` for the stored `document` = {properties,
│                       relations} and for ABSENT-not-null responses), EntityValidation.kt
│                       (the blueprint-free shape rules — identifier/title/icon/team/document
│                       size — plus the pure `entityFindings`, the blueprint-dependent
│                       property/relation rule table in .claude/docs/port-data-model.md, reused
│                       unchanged by both the strict-save 400 and every GET/list `findings`),
│                       EntityOwnership.kt (v1.26.0, phase 4 — ownership rules: Direct/absent
│                       blueprints validate stored `team` against `_team` entities; Inherited
│                       blueprints compute team at read time via path-walking; `format: team|user`
│                       properties validated similarly; + `teamValueMatches`, the in-memory twin
│                       of `jsonStringOrArrayContainsFolded` behind the team filter's Inherited
│                       half), EntityReferences.kt (pure
│                       `entityTargets`/`withEntityTargetRenamed`), EntityFilter.kt (the shared
│                       list/graph filter set — the `catalog/CatalogFileFilter.kt` shape, one
│                       level down: `EntityFilter`/`EntityGraphFilter`, the `q`/blueprint-lookup
│                       helpers, and the team-match predicate + `inheritedTeamMatches`, the
│                       v1.30.0 in-memory Inherited-ownership resolver behind the `team` filter's
│                       SQL `id IN (…)` disjunct), EntityService.kt (one row =
│                       one entity, FK to blueprints.id; every mutation under the two-table
│                       `blueprints`-then-`entities` lock — .claude/docs/persistence.md "Entity
│                       targets under concurrency (V28)" — relation targets must be ACTIVE
│                       entities of the target blueprint, identifier unique per blueprint, a
│                       RENAME cascades into every referring entity's relations/team/format-props,
│                       deleting a targeted entity is 409 naming the referrers from all three
│                       sources), EntityGraph.kt (Phase 3, v1.25.0 — a pure builder + DTOs, no
│                       database: `buildEntityGraph` over the shown rows, one edge per relation
│                       value with the both-ends rule and no virtual/MISSING nodes, `hierarchy`
│                       flagged for the source blueprint's `hierarchyRelation`, `ownership: true`
│                       for `$team` edges to `_team` nodes, the `"<blueprint>|<identifier>"`
│                       node-id grammar), JqCalculation.kt (Phase 5, v1.27.0 — the jq bridge
│                       behind `calculationProperties`: `net.thisptr:jackson-jq` 1.3.0, a
│                       read-only root `Scope` with `env`/`$ENV` shadowed AFTER builtin loading,
│                       first-output-wins abort, a 64 KiB output cap, failures absent and logged
│                       at DEBUG without the input document — see
│                       `.claude/docs/security.md` "Computed-property evaluation (jq)". Since
│                       v1.29.0 a single shared, thread-safe `JqEvaluator` (owned by
│                       `EntityService`) runs every evaluation on the bounded `entity-jq` worker
│                       pool (4 workers, queue 64) under a per-expression deadline on the
│                       compiled expression (builtins loaded at boot, compile on the caller)
│                       (`computed.jq.deadlineMillis`, default 500 ms); an expression that
│                       misses it is quarantined until its text is edited),
│                       EntityComputed.kt (Phase 5 — the computed-property orchestrator: the
│                       mirror-property relation walk, `computedPathBlueprints` snapshot
│                       widening, and the mirror/calculation/aggregation merge into `properties`),
│                       EntityAggregation.kt (Phase 5 — aggregation candidate resolution via
│                       direct relations or `pathFilter` in either direction, plus
│                       `calculationSpec` reduction), AggregationQuery.kt (Phase 5 — Port's
│                       `combinator`+`rules` query syntax evaluated over one aggregation
│                       candidate), EntityRoutes.kt — GET /api/v1/entities (any
│                       authenticated, paged: identifier/title/updatedAt sort, blueprint/team/q
│                       filters) + GET/POST/PUT/DELETE {id} (any authenticated, no admin gate
│                       anywhere — the catalog-file shared-workspace rule) + GET …/entities/graph
│                       (any authenticated, unpaged — the same `blueprint`/`team` any-of/`q`
│                       filters, both-ends rule). No seed: the registry starts empty.
│                       EntityImport.kt (phase 6, v1.28.0) — `POST …/entities/import` +
│                       `/import/check`, any authenticated (no admin gate): the pure planner
│                       (`planEntityImport`, ordering over relation/`team`/format-property
│                       sibling references, a MANDATORY reference cycle rejected outright rather
│                       than deferred) plus its effectful `EntityService.import`/`importCheck`
│                       callers and the `importSnapshot()` read seam, reusing `create`/`update`
│                       per row under the same two-table V28 lock
└── catalog/            the catalog-file domain (THE feature reference implementation):
                        CatalogFile.kt (the wire DTOs: kind model + EntitySpec superset),
                        CatalogFileValidation.kt (the sanitizer + per-kind required/forbidden
                        tables + every descriptor-format validator),
                        CatalogFileFilter.kt (the shared list/graph/errors filter set:
                        the SQL predicate AND the in-memory matcher side by side — one
                        MATCHING semantics, incl. owner-reference resolution and the
                        labelValue IN param; what each view DOES with a match differs —
                        shown vs reported — plus allowsVirtualTarget, the kind+namespace
                        rule for a graph node with no document to match),
                        CatalogFileService.kt, CatalogFileRoutes.kt — /api/v1/files CRUD
                        + paginated list + the sync pair (GET/POST …/{id}/sync); shared
                        workspace (no admin gate on content);
                        CatalogFileImport.kt — the import pipeline (import/importCheck as
                        service extensions; ONE shared per-document classification, so the
                        real run and the dry-run cannot drift);
                        CatalogFileEvents.kt — the history vocabulary: the event types, the pure
                        descriptor builders, and `documentChanges` (the generic field-level diff
                        of two documents) + CatalogFileEventService.kt (the EventLogTable clone
                        behind GET …/{id}/events — the editor's History section);
                        Errors.kt — the reference resolver + registry/stored-content checks
                        behind GET …/errors (the filterable workspace Errors report: soft
                        findings + the report-only STRUCTURE_INVALID/NAMESPACE_NOT_ALLOWED)
                        and POST …/check (the editor's live document check);
                        + CatalogFileInvalidException/CatalogFileInvalidProblem (v1.31.0 — the strict
                        save's aggregated 400 carrying those findings, an InvalidPayloadException);
                        Graph.kt — the same resolution machinery as a node/edge graph
                        (GET …/graph, the /graph page's backend);
                        Import.kt — the round-trip DTOs (GET …/export ships structured
                        documents, POST …/import stores each independently, report & skip,
                        POST …/import/check is the store-nothing dry-run of the same
                        classification; YAML parsing/rendering stays a client concern);
                        UrlFetch.kt — POST …/fetch, the SSRF-guarded server-side fetch of a
                        catalog-info.yaml URL (guards documented in security.md)
```

**Feature template — copy `catalog/`**: `<feature>/<Entity>.kt` (request/response DTOs + `toResponse`) with the `validateX` free function enforced by route AND service (in the DTO file, or a sibling `<Entity>Validation.kt` once the rules outgrow it — the catalog split), `<Entity>Routes.kt` (`@Resource` typed routes under `/api/v1/...` + `configureXRoutes()` reading services from `attributes`, `audit(...)` on every mutation), `<Entity>Service.kt` (Exposed `object` table nested inside the service, `suspendTransaction`, soft-delete via `marked_as_deleted` + partial unique indexes, list = count + rows on one predicate), a `V<n>__description.sql` migration, spec paths in `openapi/documentation.yaml`, `cd web && npm run gen:api` (same commit), lazy pages + `NAV_SECTIONS` entries (`web/src/utils/navigation.ts`), and an e2e spec + scenario doc + coverage-map line. Domain rules for catalog features come from `.claude/docs/backstage-descriptor-format.md`. Extract when byte-identical (`validateAllowedKinds`, `lockingTransaction`, `requireNoDuplicates`, `useRegistryQuery`); copy when the feature differs where the docs say it does (the tag-category lock, the lens visibility verdict, the dictionary whole-document replace). A sixth registry does not warrant a CRUD base class until two of them are byte-identical end to end.

### Authentication session lifecycle (V25)

`auth/AuthSessionService.kt` owns persisted login families (`auth_sessions`); every JWT carries
their shared `sid`. The bearer verifier checks an active family and the user's current
`auth_version` in PostgreSQL on every request, without caching acceptance. Password changes,
bootstrap rotation, and email/role changes advance that version in the
mutation transaction. Deletion rejects the active-user check. Logout deletes the whole family,
including superseded refresh tokens; another login/device is unaffected. Renewal is never an
upsert and cannot resurrect logout. Pending MFA challenges retain the epoch verified with the
password, and issuance rechecks it under a user-row lock. Self password changes use compare-and-set
and the SPA clears tokens/query cache and returns to login. Existing in-flight requests may finish.

Deploying V25 invalidates pre-migration tokens: everyone must sign in again. `password_changed_at`
remains an audit timestamp, not the revocation boundary. MFA/throttle state remains instance-local,
so this is not permission to add replicas.

### Single-use password reset (V26)

`auth/PasswordResetRoutes.kt` owns the public request and confirmation endpoints; the latter
uses `PasswordResetService` to consume a SHA-256-digested, 256-bit random grant and update the
password/credential epoch in one user-row-locked transaction. The request never changes a
password or invalidates a session. Links expire after 15 minutes (configurable 1–3600 seconds),
are tied to the active user's captured epoch, and only one outstanding link can complete.
The public SPA `/reset-password/confirm` reads `#token=…`, removes the fragment from history,
and retains it only in memory. GET does not consume anything. Success requires normal sign-in,
keeps MFA enabled, revokes old sessions/challenges, and sends a best-effort notification without
secrets. Requesting requires mail and a valid `MAIL_APP_URL` origin (HTTPS in production, HTTP
allowed in development; no path prefix, userinfo, query, or fragment); otherwise 503. V26 itself
does not sign users out. See the security/persistence docs and `HARDENING.md` for deployment
and compatibility notes. Lettuce was inspected: its reset still emails a generated password,
so the existing mail/throttle infrastructure was retained but that weakness was not copied.

### The OpenAPI contract

`server/src/main/resources/openapi/documentation.yaml` is hand-maintained and authoritative: every endpoint change edits it in the same commit. The server test suite validates every test-client `/api/` interaction against it (`OpenApiConformance.kt`, default `-Dopenapi.conformance=fail`); the frontend derives its request/response types from it (`npm run gen:api` → committed `web/src/api/schema.ts` — regenerate in the same commit as a spec change). The file declares **OpenAPI 3.0.3**, matching its `nullable:` semantics. Both tools consume it verbatim — never relabel it in tests. `OpenApiSpecTest` pins the dialect and nullability; `cd web && npm run check:api` detects generated-type drift without changing files, and `npm run lint:api` runs the pinned Spectral CLI against the contract and reference fixture.

### Cross-cutting conventions

@.claude/docs/persistence.md
@.claude/docs/list-endpoints.md
@.claude/docs/security.md
@.claude/docs/authorization.md
@.claude/docs/observability.md
@.claude/docs/testing.md

### Frontend (`web/`)

See `web/CLAUDE.md` for the frontend conventions (flat directories, co-located tests, typed i18n with EN/PL parity, the transport layer, theming).
