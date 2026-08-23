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

`docker compose up --build` serves everything at `http://localhost:8081` (sign in as `admin@toadie.local` / `changeme`); local dev is `docker compose up postgres` (host port **5433**) + `./gradlew :server:run` + `cd web && npm run dev` (Vite on **5174**, proxying `/api` to :8081). Ports deliberately avoid Lettuce's 8080/5432/5173 so both stacks can run side by side. Kubernetes (OrbStack) deployment targets the dedicated `toadie` namespace — see `k8s/secret.yaml`'s header for the secret-creation command.

## Architecture

Toadie is a Backstage `catalog-info.yaml` helper (visual creation with validation, cross-file reference checks, combined rendering). **All three product pillars are implemented on top of the full stack + tooling + auth: visual creation of catalog files across the seven landscape kinds — Component, API, System, Domain, Resource, Group, User — (stored server-side, per-kind validation, full CRUD + list, live YAML preview/download), cross-checking (a workspace report at `/cross-check` + a live reference panel in the editor; every stored kind resolves — UNVERIFIABLE means Location/Template/custom only; saves never blocked), rendering-together (the `/render` relationship graph — React Flow + dagre over `GET …/graph`), plus the YAML round-trip (client-parsed multi-document import with per-row report-&-skip results at `/catalog-files/import`, and one-file workspace export).** The architecture deliberately mirrors [Lettuce](https://github.com/liveweird/lettuce) — when adding a capability Lettuce already has (mail, encryption at rest, feature flags, MFA…), port Lettuce's implementation rather than inventing a new one.

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

`server/src/main/kotlin/main.kt` just delegates to `io.ktor.server.netty.EngineMain`. The application is wired declaratively in `server/src/main/resources/application.yaml` under `ktor.application.modules` — each entry is a fully-qualified extension function on `Application` (e.g. `ch.nokillswit.plugins.HttpKt.configureHttp`). **Module order is load-bearing**: plugins → infra (Flyway → Database → Bootstrap; Database is the composition root that publishes every service into `Application.attributes` via `AttributeKey`s) → feature route modules → `RoutingKt.configureRouting` strictly last (the SPA catch-all). To add a cross-cutting concern, create a `configureXxx()` extension under `plugins/` and register it in `application.yaml`; do not call it from `main.kt`. There is no DI framework — services travel via `attributes`.

### Package layout

Source files sit flat under `server/src/main/kotlin/<area>/` but declare `package ch.nokillswit.<area>` (no `ch/nokillswit` directory nesting — a deliberate idiom, protected by the `InvalidPackageDeclaration` detekt override).

```
ch.nokillswit
├── main.kt
├── plugins/            cross-cutting Ktor wiring (configureXxx that only `install` plugins):
│                       Http, SecurityHeaders, Monitoring, Serialization, Security (JWT),
│                       ErrorHandling (RFC 7807), OpenTelemetry, AutoHeadResponse, Resources,
│                       Routing (SPA catch-all)
├── infra/db/           Flyway bootstrap + the R2DBC connection/composition root + the seed
│                       bootstrap (admin rotation, prod fail-closed) + Sql.kt (containsNormalized,
│                       requireValidReferences, orVanished)
├── infra/paging/       the shared list-endpoint machinery (PageRequest/parsePaging/applyPaging/
│                       PageResponse + the strict query-param readers) — Lettuce's, ported verbatim
├── audit/              security audit trail: `audit(event, fields…)` → AUDIT-marked structured logs
├── authz/              CallerPrincipal + guards (requireAdmin, requireSelfOrAdmin) + typed
│                       HTTP exceptions (401/403/404/409/429)
├── auth/               POST /api/v1/login, /refresh, /logout + token minting + password
│                       hashing + LoginThrottle + the revoked-token blocklist
├── users/              the user domain: ADMIN-only management CRUD (/api/v1/users list/create
│                       + {id} get/put/delete with the self-delete 403 and last-admin 409
│                       protections) + PUT /api/v1/users/{id}/password + Validation.kt
└── catalog/            the catalog-file domain (THE feature reference implementation):
                        CatalogFile.kt (kind model + EntitySpec superset + the per-kind
                        required/forbidden validation tables),
                        CatalogFileService.kt, CatalogFileRoutes.kt — /api/v1/catalog-files CRUD
                        + paginated list; shared workspace (no admin gate on content);
                        CrossCheck.kt — the pure reference resolver behind GET …/cross-check
                        (workspace report) and POST …/check (the editor's live document check);
                        Graph.kt — the same resolution machinery as a node/edge graph
                        (GET …/graph, the /render page's backend);
                        Import.kt — the round-trip DTOs (GET …/export ships structured
                        documents, POST …/import stores each independently, report & skip;
                        YAML parsing/rendering stays a client concern)
```

**Feature template — copy `catalog/`**: `<feature>/<Entity>.kt` (request/response DTOs + `toResponse` + the `validateX` free function enforced by route AND service), `<Entity>Routes.kt` (`@Resource` typed routes under `/api/v1/...` + `configureXRoutes()` reading services from `attributes`, `audit(...)` on every mutation), `<Entity>Service.kt` (Exposed `object` table nested inside the service, `suspendTransaction`, soft-delete via `marked_as_deleted` + partial unique indexes, list = count + rows on one predicate), a `V<n>__description.sql` migration, spec paths in `openapi/documentation.yaml`, `cd web && npm run gen:api` (same commit), lazy pages + `NAV_ITEMS` entries, and an e2e spec + scenario doc + coverage-map line. Domain rules for catalog features come from `.claude/docs/backstage-descriptor-format.md`.

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
