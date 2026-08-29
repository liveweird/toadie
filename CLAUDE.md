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
- Package the server for deployment: `./gradlew :server:installDist`. **Never use `:server:buildFatJar`** — the fat JAR breaks Flyway's `ServiceLoader` discovery and NPEs at startup.
- JVM memory flags are pre-tuned in `server/build.gradle.kts` (`applicationDefaultJvmArgs`) — the rationale is commented in place.
- **Run the whole stack with one command: `docker compose up --build`** (only Docker required). See "Running the full stack" below.
- Frontend: `cd web && npm install --legacy-peer-deps`, then `npm run dev|build|lint|test|test:coverage|knip|gen:api` (details in `web/CLAUDE.md`).
- E2E: `cd e2e && npm ci && npx playwright install chromium && npm test` (plus `npm run typecheck` and `npm run check:scenarios`).

## Running the full stack

`docker compose up --build` serves everything at `http://localhost:8081` (sign in as `admin@toadie.local` / `changeme`); local dev is `docker compose up postgres` (host port **5433**) + `./gradlew :server:run` + `cd web && npm run dev` (Vite on **5174**, proxying `/api` to :8081). The compose stack bundles **Mailpit** (`http://localhost:8026`) and wires the app's password-reset and MFA email to it (`MAIL_TRANSPORT=smtp`). Ports deliberately avoid Lettuce's 8080/5432/5173/8025 so both stacks can run side by side. Kubernetes (OrbStack) deployment targets the dedicated `toadie` namespace — see `k8s/secret.yaml`'s header for the secret-creation command.

## Architecture

Toadie is a Backstage `catalog-info.yaml` helper (visual creation with validation, cross-file reference checks, combined rendering). **All three product pillars are implemented on top of the full stack + tooling + auth: visual creation of catalog files across the seven landscape kinds — Component, API, System, Domain, Resource, Group, User — (stored server-side, per-kind validation, full CRUD + list — filterable by name/namespace/kind/tag/type/lifecycle/owner/label, the same filter panel riding the Hierarchy and Graph views — live YAML preview/download) with namespaces constrained to the ADMIN-curated `namespaces` dictionary (the `/namespaces` page; strict server-side enforcement on every write, blank/omitted resolving to the entry flagged as DEFAULT) and labels constrained to the ADMIN-curated label registry (the `/labels` page; each label = a key + a CLOSED value list + the kinds it applies to, strictly enforced on every write) and annotation KEYS constrained to the ADMIN-curated annotation-key registry (the `/annotations` page; each key + the kinds it applies to — values stay free strings, strictly enforced on every write) and tags constrained to the ADMIN-curated tag categories (the `/tags` page; an internal grouping concept — each category = a name + its tags, each tag in exactly ONE category + the kinds they apply to, strictly enforced on every write), and `spec.type` constrained to the ADMIN-curated per-kind type dictionaries (the `/types` page; one independent list per type-bearing kind — User has none — seeded with the well-known Backstage values, strictly enforced on every write), and `spec.lifecycle` constrained to the ADMIN-curated GLOBAL lifecycles dictionary (the `/lifecycles` page; one shared list for every lifecycle-bearing kind, seeded with the well-known values, strictly enforced on every write), and entity references strictly enforced on every write (each reference — `spec.owner`, `spec.system`, `spec.dependsOn`, … — must RESOLVE to a stored entity of a kind the field allows, e.g. owner → Group/User, and never to the entity ITSELF; unresolved, wrong-kind, or self references are a `400`) — **all of these registry/reference rules are SOFT: strict by default, but `allowInvalid=true` on create/replace waives them and stores the document anyway (the editor's Save-anyway modal; import ALWAYS waives, reporting `CREATED_WITH_FINDINGS` rows), while the structural descriptor rules and namespace resolution stay hard** — cross-checking (a workspace report at `/cross-check` covering references AND the registry checks + the live findings panel in the editor; findings arise from waived saves, deletions — dangling references appear that way by design — and registry rows removed after the fact, and every finding blocks the file's next STRICT save), rendering-together (the `/graph` relationship graph — React Flow + dagre over `GET …/graph` — nav label "Graph", plus the `/` **Hierarchy** view: the same graph data as collapsible containment trees with the Files operations per row, shaped client-side in `web/src/utils/hierarchy.ts`), plus the YAML round-trip (client-parsed multi-document import with per-row report-&-skip results at `/files/import`, and one-file workspace export).** The architecture deliberately mirrors [Lettuce](https://github.com/liveweird/lettuce) — when adding another capability Lettuce already has (field encryption, history/event logs, teams/management chains…), port Lettuce's implementation rather than inventing a new one.

Multi-module Gradle build (Kotlin DSL) defined in `settings.gradle.kts` with two Kotlin modules plus a separate JS frontend in `web/`:

- **`core`** — Kotlin Multiplatform (JVM target only currently). Shared code consumed by `server`. Holds the OpenTelemetry SDK bootstrap (`getOpenTelemetry(serviceName)`).
- **`server`** — Kotlin/JVM. The Ktor application. Depends on `core`.
- **`web/`** — Vite + React + TypeScript SPA that consumes the server's HTTP API. Standalone npm workspace; Gradle does not touch it.
- **`e2e/`** — Playwright blackbox suite against the compose stack. Standalone npm workspace.

Group is `ch.nokillswit`, version `1.0.0-SNAPSHOT` (set in root `build.gradle.kts`). Dependency versions are centralized in `gradle/libs.versions.toml`; Ktor itself comes from a separate version catalog (`ktorLibs`) loaded from `io.ktor:ktor-version-catalog` in `settings.gradle.kts`.

### The Backstage descriptor format (the domain reference)

**`.claude/docs/backstage-descriptor-format.md` is the local offline reference for the `catalog-info.yaml` format** Toadie exists to create, cross-check, and render — the envelope, metadata validation rules, all kinds and their spec fields, entity-reference resolution defaults, substitutions, and well-known annotations. Consult it when designing any catalog feature instead of browsing; the upstream source it snapshots is <https://backstage.io/docs/features/software-catalog/descriptor-format/> — re-check upstream (and update the snapshot) when adding a new validation rule.

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
│                       Routing (SPA catch-all)
├── infra/mail/         outbound email (Lettuce's, ported): Mailer/SmtpMailer/LogMailer +
│                       configureMail — MAIL_TRANSPORT log/smtp/disabled, the log-transport
│                       production refusal (fail-closed), null mailer = email features 503.
│                       Consumers: self-service password reset and email MFA
├── infra/db/           Flyway bootstrap + the R2DBC connection/composition root + the seed
│                       bootstrap (admin rotation, prod fail-closed) + Sql.kt (containsNormalized,
│                       jsonArrayContains, orVanished)
├── infra/paging/       the shared list-endpoint machinery (PageRequest/parsePaging/applyPaging/
│                       PageResponse + the strict query-param readers) — Lettuce's, ported verbatim
├── infra/validation/   cross-feature input helpers (sanitizeSingleLine — trim + control-char 400)
├── audit/              security audit trail: `audit(event, fields…)` → AUDIT-marked structured logs
├── authz/              CallerPrincipal + guards (requireAdmin, requireSelfOrAdmin) + typed
│                       HTTP exceptions (401/403/404/409/429)
├── auth/               POST /api/v1/login (+ the email-MFA branch and /login/mfa second
│                       step — MfaChallenges/MfaEmail), /refresh, /logout + the self-service
│                       POST /api/v1/password-reset (uniform 202, async send-before-store,
│                       PasswordResetThrottle) + token minting + password hashing/generation
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
│                       deliberately unaudited) + Validation.kt
├── dictionaries/       admin-curated ordered value lists (Lettuce's dictionaries, single-
│                       valued — no translations): Dictionary.kt (the Dictionary enum whitelist
│                       + DTOs + validateDictionaryUpdate), DictionaryService.kt (whole-document
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
│                       one-category-per-tag 409 enforced service-side in-transaction),
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
│                       well-known Backstage values per kind. The whitelist every
│                       catalog-file write's spec.type is checked against (strict; a kind
│                       with no dictionary allows NO types)
└── catalog/            the catalog-file domain (THE feature reference implementation):
                        CatalogFile.kt (the wire DTOs: kind model + EntitySpec superset),
                        CatalogFileValidation.kt (the sanitizer + per-kind required/forbidden
                        tables + every descriptor-format validator),
                        CatalogFileFilter.kt (the shared list/graph filter set: the SQL
                        predicate AND the in-memory matcher side by side — one semantics,
                        incl. owner-reference resolution and the labelValue IN param),
                        CatalogFileService.kt, CatalogFileRoutes.kt — /api/v1/files CRUD
                        + paginated list; shared workspace (no admin gate on content);
                        CrossCheck.kt — the pure reference resolver behind GET …/cross-check
                        (workspace report) and POST …/check (the editor's live document check);
                        Graph.kt — the same resolution machinery as a node/edge graph
                        (GET …/graph, the /graph page's backend);
                        Import.kt — the round-trip DTOs (GET …/export ships structured
                        documents, POST …/import stores each independently, report & skip,
                        POST …/import/check is the store-nothing dry-run of the same
                        classification; YAML parsing/rendering stays a client concern);
                        UrlFetch.kt — POST …/fetch, the SSRF-guarded server-side fetch of a
                        catalog-info.yaml URL (guards documented in security.md)
```

**Feature template — copy `catalog/`**: `<feature>/<Entity>.kt` (request/response DTOs + `toResponse`) with the `validateX` free function enforced by route AND service (in the DTO file, or a sibling `<Entity>Validation.kt` once the rules outgrow it — the catalog split), `<Entity>Routes.kt` (`@Resource` typed routes under `/api/v1/...` + `configureXRoutes()` reading services from `attributes`, `audit(...)` on every mutation), `<Entity>Service.kt` (Exposed `object` table nested inside the service, `suspendTransaction`, soft-delete via `marked_as_deleted` + partial unique indexes, list = count + rows on one predicate), a `V<n>__description.sql` migration, spec paths in `openapi/documentation.yaml`, `cd web && npm run gen:api` (same commit), lazy pages + `NAV_ITEMS` entries, and an e2e spec + scenario doc + coverage-map line. Domain rules for catalog features come from `.claude/docs/backstage-descriptor-format.md`.

### The OpenAPI contract

`server/src/main/resources/openapi/documentation.yaml` is hand-maintained and authoritative: every endpoint change edits it in the same commit. The server test suite validates every test-client `/api/` interaction against it (`OpenApiConformance.kt`, default `-Dopenapi.conformance=fail`); the frontend derives its request/response types from it (`npm run gen:api` → committed `web/src/api/schema.ts` — regenerate in the same commit as a spec change). The file says `openapi: 3.1.0` but must use only 3.0-compatible constructs (the conformance harness relabels it in memory; `OpenApiSpecTest` guards this).

### Cross-cutting conventions

@.claude/docs/persistence.md
@.claude/docs/list-endpoints.md
@.claude/docs/security.md
@.claude/docs/authorization.md
@.claude/docs/observability.md
@.claude/docs/testing.md

### Frontend (`web/`)

See `web/CLAUDE.md` for the frontend conventions (flat directories, co-located tests, typed i18n with EN/PL parity, the transport layer, theming).
