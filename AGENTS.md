# Repository Guidelines

## Sources of Truth

This file is the Codex entry point. Before changing code, also read the relevant sections of
`CLAUDE.md`; when working under `web/`, read `web/CLAUDE.md` as well. `CLAUDE.md` uses Claude's
`@...` import syntax to reference the cross-cutting conventions in `.claude/docs/` (persistence,
list endpoints, security, authorization, observability, testing); Codex must open the applicable
files directly. Together those files contain the detailed, actively maintained domain, security,
persistence, UI, and testing conventions shared by the project. For API work,
`api-guidelines/API-GUIDELINES.md` is authoritative and its stable rule IDs should be cited in
reviews. If documentation and executable configuration disagree, the configuration and code win;
update the affected guidance in the same change.

Toadie deliberately mirrors [Lettuce](https://github.com/liveweird/lettuce). The implemented
surface includes authentication/session handling, admin-managed users and feature flags, synced
user language, per-user graph layouts, email MFA and password reset, shared paging, saved filter
lenses, catalog-file CRUD across the seven landscape kinds, curated namespace, label, annotation,
tag, type, and lifecycle registries, the Errors report and live checks, hierarchy and relationship-graph
views, multi-document YAML import/export with a dry run, repository-source synchronization, and
per-file change history. When adding another capability Lettuce already has (encryption at rest,
notifications, teams/management chains…), port Lettuce's implementation rather than inventing a
new one — the docs above mark each such capability as a "port from Lettuce" note.

`.claude/docs/backstage-descriptor-format.md` is the local domain reference for the catalog
descriptor envelope, metadata validation, kinds, entity-reference defaults, substitutions, and
well-known annotations. Consult it before designing catalog behavior; re-check its linked
upstream Backstage documentation when introducing a new validation rule.

The playbooks in `.claude/skills/` are useful repository-local references even outside Claude:
`api-review` covers the two-pass OpenAPI review, `run-stack` covers packaging/deployment, and
`verify` covers browser verification and cleanup.

## Project Structure & Architecture

This is a Kotlin/Gradle backend plus a separate React frontend:

- `core/` is Kotlin Multiplatform (currently JVM-targeted) and owns the shared OpenTelemetry SDK
  bootstrap.
- `server/` is the Kotlin/JVM Ktor application. Feature packages live directly under
  `server/src/main/kotlin/`: `auth`, `users`, `catalog`, `dictionaries`, `labels`, `annotations`,
  `tags`, `types`, and `lenses`. `catalog` is the feature reference implementation: seven
  Backstage kinds (Component, API, System, Domain, Resource, Group, User), full CRUD + paginated
  list, validation/checking, the workspace Errors report, graph, import/export and dry-run import,
  SSRF-guarded URL fetch/repo sync, and immutable per-file events. `users` also owns synced
  language and per-user Graph layout settings. Cross-cutting wiring and policy live in `plugins/`,
  `audit/`, and `authz/`; database, mail, paging, and shared validation infrastructure live in
  `infra/`.
- `server/src/main/resources/application.yaml` declaratively registers application modules.
  `main.kt` only starts `EngineMain`; do not wire features from it. Module order matters because
  modules publish and consume Ktor application attributes.
- PostgreSQL is the only database. Flyway migrations under
  `server/src/main/resources/db/migration/` are the schema source of truth; Exposed over R2DBC is
  used for runtime queries. Never introduce runtime DDL such as `SchemaUtils.create`, and never
  edit an applied migration, including its comments: Flyway checksums are pinned by
  `MigrationChecksumTest`; add a new migration instead.
- `server/src/main/resources/openapi/documentation.yaml` is the hand-maintained API contract.
- `web/` is a standalone Vite + React 19 + TypeScript SPA. Gradle does not build it. Source is
  organized into `pages/`, `components/`, `hooks/`, `utils/`, `api/`, `changelog/`, and bilingual
  resources under `locales/{en,pl}/`. It includes catalog editing, quick view, import/export and
  source sync, hierarchy/graph/Errors views, lenses, all six catalog registries, catalog history,
  user/feature administration, MFA, password reset, command palette, and changelog.
- `sample-data/` contains the hand-imported multi-document example workspace. Migrations never
  seed catalog files; a new environment deliberately starts with an empty catalog workspace.
- Backend tests are in `server/src/test/kotlin/`, colocated frontend tests use `*.test.ts(x)`, and
  Playwright journeys are in `e2e/tests/*.spec.ts` with their design artifacts in
  `e2e/scenarios/*.md`.

Routing is feature-local. Cross-cutting Ktor wiring functions are named `configureXxx` and must be
registered in `application.yaml`. `plugins/Routing.kt` is only the final SPA/static-file catch-all.

## Build, Test, and Development Commands

- `docker compose up --build`: build and run PostgreSQL, the API, and the SPA at
  `http://localhost:8081` (sign in as `admin@toadie.local` / `changeme`); Mailpit captures reset
  and MFA email at `http://localhost:8026`.
- `docker compose up postgres`: start only the development database (host port **5433**, not
  5432 — Lettuce may occupy 5432 on the same machine).
- `./gradlew build`: compile and verify the Gradle modules with the JDK 21 toolchain (the local
  dev JDK is pinned in `mise.toml`).
- `./gradlew :server:run`: start Ktor/Netty on port 8081.
- `./gradlew test` or `./gradlew :server:test`: run Kotlin tests; Docker is required for
  Testcontainers.
- `./gradlew :server:test --tests "<fully-qualified test name>"`: run one backend test.
- `./gradlew detekt`: static analysis over `core` + `server` — zero-findings gate, no baseline.
- `cd web && npm run dev`: start Vite on port 5174, proxying `/api` to Ktor on :8081.
- `cd web && npm run build && npm run lint && npm test`: type-check, bundle, lint, and run Vitest.
- `cd web && npm run test:coverage`: run frontend coverage gates. `npm run knip`: dead-code gate.
- `cd web && npm run gen:api`: regenerate `web/src/api/schema.ts` from the OpenAPI contract.
- `cd e2e && npm test`: run Playwright against the full stack on port 8081;
  `npm run typecheck` and `npm run check:scenarios` are the Docker-free static gates.

For a clean frontend install, use `cd web && npm install --legacy-peer-deps`;
`openapi-typescript` declares a TypeScript 5 peer while the project uses TypeScript 6. Keep the
Gradle and npm toolchains disjoint.

Package deployments with `./gradlew :server:installDist`. Never use `buildFatJar`: merging Flyway
service descriptors breaks plugin discovery at runtime. JVM runtime flags are intentionally set in
`server/build.gradle.kts`; consult `.claude/skills/run-stack/SKILL.md` before changing them.

## API and Backend Conventions

Follow `api-guidelines/API-GUIDELINES.md` for resource naming, pagination, filtering, sorting,
errors, statuses, auth, and conformance. All error bodies are RFC 7807
`application/problem+json`. Keep authorization checks before resource-dependent validation so
callers cannot infer inaccessible state (403 wins over 400). List endpoints use the
`{items, page, pageSize, total}` envelope and the already-ported `infra/paging` machinery; copy
the catalog-file list implementation rather than parsing pagination, filters, or sorting again
(see `.claude/docs/list-endpoints.md`).

When an API changes, update all of the following in the same change:

1. Route/service behavior and focused tests.
2. `server/src/main/resources/openapi/documentation.yaml`.
3. The generated `web/src/api/schema.ts` via `npm run gen:api`.
4. API guideline conformance, using the Spectral ruleset and review checklist described in
   `.claude/skills/api-review/SKILL.md`.

Use `V<number>__description.sql` for migrations. Business entities follow the established
soft-delete convention (`marked_as_deleted`, active-row filtering on every read/count/mutation,
and partial unique indexes where deleted values may be reused); follow the detailed pattern in
`.claude/docs/persistence.md` rather than inventing a variant. Emit structured `audit(...)`
events for security-relevant mutations and denials, and never log passwords or tokens. The
per-user graph-layout PUT is the documented exception: it is high-frequency view state and is
deliberately unaudited. Catalog-file product history is separate from the security audit trail;
catalog mutations extend both where applicable.

Catalog content is a shared authenticated workspace; ADMIN has no extra content privilege.
Catalog validation has two classes. Structural descriptor rules and namespace resolution are
hard: namespaces must exist in the `NAMESPACE` dictionary (blank resolves to its flagged default)
and cannot be waived. Registry and reference rules are soft: labels, annotation keys, tags,
per-kind types, global lifecycles, and references must normally be allowed/resolvable, of an
allowed kind, and non-self-referential, but create/replace may explicitly use `allowInvalid=true`.
The editor exposes that waiver as Save anyway; import always waives soft findings and reports
`CREATED_WITH_FINDINGS`, while `/files/import/check` performs the same classification without
storing anything. The Errors report also includes report-only structural, namespace, and missing
source verdicts. Keep server validators, OpenAPI schemas, kind-aware form rules, live-field finding
routing, and the YAML generator/strict inverse parser synchronized.

An optional catalog `sourceUrl` is envelope state, not part of the Backstage document or YAML
export. It must be an absolute credential-free HTTPS URL and enables one-way repo-to-database
sync through the SSRF-guarded fetch path. Ordinary PUT is a full replace, so clients must carry
`sourceUrl` forward intentionally; sync/import-from-URL update the stored baseline and sync time.
Every catalog mutation also maintains the immutable structural event history; free-text changes
record only the fact of change, not the sensitive text itself.

Use four-space indentation, preserve existing package boundaries, PascalCase for Kotlin types,
and camelCase for functions and variables. Name backend test classes `*Test`.

## Frontend Conventions

Use two-space indentation, PascalCase for React components, and the existing shared
components/hooks instead of cloning transport, error-mapping, or session logic.
The design system is owned by `web/src/theme.ts` and `web/src/theme.module.css`: the brand amber
(`toadie` tuple) is reserved for primary actions, active navigation, and focus; don't reintroduce
stock-blue actions or stock-green success states (success is teal). `KindBadge` is the single kind
surface and its tier marker is visual only. Keep accessibility roles, labels, and semantic tables
stable because tests and Playwright use them as contracts.

All user-facing strings must use react-i18next. Keep English and Polish resources in parity
(enforced by `locales/parity.test.ts`); Polish uses inclusive slash forms. Errors render inline
as red Alerts; follow `web/CLAUDE.md` for the exact transport, i18n, and theming patterns.

Catalog forms are kind-aware and use the shared identity, namespace, label, and tag hooks; do not
replace their constrained pickers with free-form clones. Keep `utils/catalogYaml.ts` and the
strict inverse parser in `utils/catalogImport.ts` in lockstep. Soft server findings remain
presentational orange warnings and must not be put into Mantine's hard-error form state; hard
client validation stays red and blocks submission.

Files, Hierarchy, Graph, and Errors share `CatalogToolbar`, the nine-slot catalog filter state,
always-visible kind pills, and saved lenses. Files/Hierarchy/Graph interpret filters as entities
shown; Errors interprets them as files reported while resolving references workspace-wide. The
quick-view drawer is URL-carried via `?file=<id>`; ordinary view filters remain per-view local
storage. Whole-file operations are centralized and PUT callers must preserve full-replace fields.

The `/graph` page keeps React Flow and dagre in its lazy chunk. It supports Auto and Manual modes,
with mode, dragged positions, and collapsed node IDs persisted server-side in the current user's
single graph-layout document. React Flow owns live drag state; persist accumulated positions only
after a drag ends, merge with the full stored map rather than pruning filtered-out nodes, and keep
mode/fold/reset saves as wholesale document replacements. Filtering runs before folding; folding
uses `buildHierarchy`'s containment rules, and namespace frames derive from live node positions.
Do not let a drag navigate, do not refit the viewport on drags/mode/reset, and keep fold controls
outside the node's interactive face.

Pages are lazy and use shared `PageHeader` chrome; navigation is defined once in
`utils/navigation.ts` for the sidebar, user menu, and command palette. User creation/reset
passwords are generated client-side and revealed exactly once; the server never returns plaintext
passwords. The selected UI language is also stored on the user and drives server-composed email.

`web/src/changelog/version.ts` (`APP_VERSION`) is the sole source of the displayed app version;
the Gradle snapshot version is unrelated. A release adds the newest bilingual markdown entry to
`web/src/changelog/entries.ts` and bumps `APP_VERSION` in the same change; tests pin their parity.

## Testing and Verification

Use Kotlin Test/Ktor Test Host, Vitest with Testing Library, and Playwright for cross-stack
journeys. Add focused regression coverage for behavioral changes. Backend tests boot PostgreSQL
through Testcontainers, apply every Flyway migration, and include the V3 seed admin; use unique
markers (`uniqueEmail(...)`) instead of asserting global counts.

Every `/api/` interaction made through the shared backend test clients is checked against OpenAPI.
Prefer `jsonClient()`/`authedClient()` so tests do not bypass conformance validation. `check`
enforces Kover floors of 97% lines and 76% branches. Frontend coverage floors in
`web/vite.config.ts` are 97% lines, 95% statements, 92% functions, and 91% branches. Re-measure and
raise floors as coverage improves; do not lower them to accommodate new code. Any test-local
Mantine provider must set `env="test"` so popovers and selects work under happy-dom.

A new or behaviorally changed e2e test lands with its scenario file in `e2e/scenarios/` and its
coverage-map line in `e2e/README.md` in the same commit (`npm run check:scenarios` enforces the
parity); scenario headings and Playwright test titles must match exactly. For nontrivial cross-stack
behavior, verify through the SPA using the workflow in `.claude/skills/verify/SKILL.md`, and clean
up any records created in the development database.

## Commit, Documentation, and Security

Use Conventional Commit subjects such as `feat:`, `fix:`, `fix(e2e):`, and `docs:`. PRs should
explain behavior and risk, list verification commands, link issues, and include screenshots for UI
changes. Keep migrations, API contract, generated schema, tests, and both translations
synchronized when applicable.

Never commit production JWT or database secrets. Committed `changeme` values and development keys
are burned demo credentials; production mode deliberately refuses them (the JWT fail-closed check
in `plugins/Security.kt` and the seed-password check in `infra/db/Bootstrap.kt`). Mail transport is
also fail-closed: production refuses the `log` transport, and SMTP with a blank host fails in every
mode. The compose demo uses SMTP through Mailpit; the image defaults to disabled mail, where reset
and MFA-dependent flows return 503.
