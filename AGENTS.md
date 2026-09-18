# Repository Guidelines

## Sources of Truth

This file is the Codex entry point. Before changing code, also read the relevant sections of
`CLAUDE.md`; when working under `web/`, read `web/CLAUDE.md` as well. `CLAUDE.md` uses Claude's
`@...` import syntax to reference the cross-cutting conventions in `.claude/docs/` (persistence,
list endpoints, security, authorization, observability, testing); Codex must open the applicable
files directly. Together those files contain the detailed, actively maintained domain, security,
persistence, UI, and testing conventions shared by the project. For REST API work,
`api-guidelines/API-GUIDELINES.md` is authoritative and its stable rule IDs should be cited in
reviews. `CLAUDE.md`'s "Where to read, by task" table near its top is the map into all of this;
consult it before searching by hand. If documentation and executable configuration disagree, the
resolution depends on what kind of claim is wrong: for DESCRIPTIVE facts (versions, ports,
registered modules, which file does what), the configuration and code win — update the affected
guidance in the same change; for NORMATIVE guarantees (security/authorization invariants,
persistence rules, the API contract/guidelines), the disagreement is a FINDING, not a typo —
investigate, decide which artifact is actually wrong, add a regression where the code was wrong,
and never edit a doc or test merely to bless the code's current behavior.

Toadie has two product worlds: the Backstage catalog and the Port-style ontology. Both share
authentication/session handling, admin-managed users and feature flags, synced language, email
MFA/password reset, and the application shell. Backstage includes seven-kind catalog-file CRUD,
curated registries, Errors/live checks, hierarchy/graph views, saved filter lenses, YAML
import/export and dry run, repository-source synchronization, and per-file history. Port includes
blueprints, entities, system teams/users, direct/inherited ownership, computed properties,
parallel named hierarchies, JSON import/export and dry run, entity graph/hierarchy views, a
bounded entity query language, saved queries, and query-generating canvas actions.

Toadie deliberately mirrors [Lettuce](https://github.com/liveweird/lettuce) for shared capabilities.
When adding a capability Lettuce already has (encryption at rest, notifications…), inspect and
port its implementation while preserving Toadie's documented security rules. Port-specific
ontology/query features are Toadie implementations; follow their local domain references.

`.claude/docs/backstage-descriptor-format.md` is the local domain reference for the catalog
descriptor envelope, metadata validation, kinds, entity-reference defaults, substitutions, and
well-known annotations. Consult it before designing catalog behavior; re-check its linked
upstream Backstage documentation when introducing a new validation rule.

For blueprint/entity work, read `.claude/docs/port-data-model.md` (Port wire shapes, validation,
ownership, computed properties, lifecycle, import/export, and Toadie extensions) and
`.claude/docs/ontology.md` (the eleven-blueprint baseline and Backstage round-trip decisions).
For query work, also read `.claude/docs/entity-query-language.md`: it defines the implemented
subset, semantics, budgets, diagnostics, saved queries, and canvas actions. Update the relevant
reference in the same change. `api-guidelines/GRAPHQL-GUIDELINES.md` and
`.claude/docs/integration-api.md` govern the separate
read-only Port GraphQL API, its SDL contract, machine keys, limits, and admin management.
`HARDENING.md` contains completed work and outstanding follow-ups;
it is a historical implementation tracker, not a list of only unfinished tasks.

The playbooks in `.claude/skills/` are useful repository-local references even outside Claude:
`api-review` covers the two-pass OpenAPI review, `run-stack` covers packaging/deployment, and
`verify` covers browser verification and cleanup.

## Project Structure & Architecture

This is a Kotlin/Gradle backend plus a separate React frontend:

- `core/` is Kotlin Multiplatform (currently JVM-targeted) and owns the shared OpenTelemetry SDK
  bootstrap.
- `server/` is the Kotlin/JVM Ktor application. Feature packages live directly under
  `server/src/main/kotlin/`: `auth`, `users`, `catalog`, `dictionaries`, `labels`, `annotations`,
  `tags`, `types`, `lenses`, `blueprints`, `entities`, `entityquery`, and `integration`. `catalog` is the feature
  reference implementation: seven Backstage kinds (Component, API, System, Domain, Resource,
  Group, User), full CRUD + paginated
  list, validation/checking, the workspace Errors report, graph, import/export and dry-run import,
  SSRF-guarded URL fetch/repo sync, and immutable per-file events. `blueprints` owns the schema
  registry; `entities` owns instances, ownership/computation, graph data, and entity import;
  `entityquery` owns the pure query engine and persisted saved queries. `integration` owns
  the read-only Port GraphQL adapter and separate admin-managed machine clients. `dictionaries` also
  owns the Port `HIERARCHY` dictionary. `users` owns synced language and separate per-user
  Backstage/Port graph layouts. Cross-cutting wiring and policy live in `plugins/`,
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
  user/feature administration, MFA, password reset, command palette, and changelog, plus the
  Port blueprint/entity editors, hierarchy registry, ontology import, and query-enabled canvases.
- `sample-data/backstage/commerce-payments/catalog-info.yaml` is the hand-imported Backstage
  demo; `sample-data/port/commerce-payments/blueprints/` contains the eleven-blueprint baseline
  and its sibling `entities/` directory contains the Port instances. Use the Port `load.sh`
  scripts or each paradigm's matching import UI. Migrations never
  seed catalog files or entity instances. V31 seeds only the protected `_team`/`_user` system
  blueprints, which the sample blueprint import extends.
  Start with the [sample-data guide](sample-data/README.md) for each demo
  and its prerequisites, loading instructions, expected results, and cleanup.
- Backend tests are in `server/src/test/kotlin/`, colocated frontend tests use `*.test.ts(x)`, and
  Playwright journeys are in `e2e/tests/*.spec.ts` with their design artifacts in
  `e2e/scenarios/*.md`.

Routing is feature-local. Cross-cutting Ktor wiring functions are named `configureXxx` and must be
registered in `application.yaml`. `plugins/Routing.kt` is only the final SPA/static-file catch-all.
`infra/db/Database.kt` publishes services through application attributes; there is no DI framework.
Packages declare `ch.nokillswit.<area>` while files stay directly under `<area>/`, without a
`ch/nokillswit/` directory prefix.

## Build, Test, and Development Commands

- `docker compose up --build`: build and run PostgreSQL, the API, and the SPA at
  `http://localhost:8081` (sign in as `admin@toadie.local` / `changeme`); Mailpit captures reset
  and MFA email at `http://localhost:8026`. All three published ports are loopback-only;
  do not expose this development-mode configuration on a LAN or public interface.
- `docker compose up postgres`: start only the development database (host port **5433**, not
  5432 — Lettuce may occupy 5432 on the same machine).
- `./gradlew build`: compile and verify the Gradle modules with the JDK 21 toolchain (the local
  dev JDK is pinned in `mise.toml`).
- `./gradlew :server:run`: start Ktor/Netty on port 8081.
- `./gradlew test` or `./gradlew :server:test`: run Kotlin tests; Docker is required for
  Testcontainers.
- `./gradlew :server:test --tests "<fully-qualified test name>"`: run one backend test.
- `./gradlew detekt`: static analysis over `core` + `server` — zero-findings gate, no baseline.
- After a Gradle dependency change, run `./gradlew build --write-locks` and include the updated
  module/root lockfiles. Never delete a lockfile to bypass a stale-lock resolution failure.
- `cd web && npm run dev`: start Vite on port 5174, proxying `/api` to Ktor on :8081.
- `cd web && npm run build && npm run lint && npm test`: type-check, bundle, lint, and run Vitest.
- `cd web && npm run test:coverage`: run frontend coverage gates. `npm run knip`: dead-code gate.
- `cd web && npm run gen:api`: regenerate `web/src/api/schema.ts` from the OpenAPI contract.
- `cd web && npm run check:api && npm run lint:api`: read-only generated-type drift check and
  lockfile-pinned Spectral lint of the published contract and reference fixture.
- `cd e2e && npm test`: run Playwright against the full stack on port 8081;
  `npm run typecheck` and `npm run check:scenarios` are the Docker-free static gates.

For a clean frontend install, use `cd web && npm install --legacy-peer-deps`;
`openapi-typescript` declares a TypeScript 5 peer while the project uses TypeScript 6. Keep the
Gradle and npm toolchains disjoint.
Keep `mise.toml`, Docker base-image toolchains, and CI selectors aligned. CI's Temurin selector
uses the complete version including its build/LTS suffix. Detekt overrides belong in
`config/detekt/detekt.yml` with a rationale; do not add unexplained suppressions.

Package deployments with `./gradlew :server:installDist`. Never use `buildFatJar`: merging Flyway
service descriptors breaks plugin discovery at runtime. JVM runtime flags are intentionally set in
`server/build.gradle.kts`; consult `.claude/skills/run-stack/SKILL.md` before changing them.

## API and Backend Conventions

Follow `api-guidelines/API-GUIDELINES.md` for REST resource naming, pagination, filtering, sorting,
errors, statuses, auth, and conformance. REST error bodies are RFC 7807
`application/problem+json`. Keep authorization checks before resource-dependent validation so
callers cannot infer inaccessible state (403 wins over 400). List endpoints use the
`{items, page, pageSize, total}` envelope and the already-ported `infra/paging` machinery; copy
the catalog-file list implementation rather than parsing pagination, filters, or sorting again
(see `.claude/docs/list-endpoints.md`).

When a REST API changes, update all of the following in the same change:

1. Route/service behavior and focused tests.
2. `server/src/main/resources/openapi/documentation.yaml`.
3. The generated `web/src/api/schema.ts` via `npm run gen:api`.
4. API guideline conformance, using the Spectral ruleset and review checklist described in
   `.claude/skills/api-review/SKILL.md`.

GraphQL changes update the committed SDL, resolver/transport regressions, and integration
reference instead; follow `api-guidelines/GRAPHQL-GUIDELINES.md`. GraphQL execution errors use
the documented `data`/`errors` envelope; transport failures retain ProblemDetail.

Use `V<number>__description.sql` for migrations. Business entities follow the established
soft-delete convention (`marked_as_deleted`, active-row filtering on every read/count/mutation,
and partial unique indexes where deleted values may be reused); follow the detailed pattern in
`.claude/docs/persistence.md` rather than inventing a variant. Emit structured `audit(...)`
events for security-relevant mutations and denials, and never log passwords or tokens. The
per-user graph-layout PUT is the documented exception: it is high-frequency view state and is
deliberately unaudited. Catalog-file product history is separate from the security audit trail;
catalog mutations extend both where applicable. File changes and history events commit in one
transaction with a required actor; routes audit only after success. Imports retain one transaction
per document. Preserve no-op PUT suppression, always-recorded syncs, redaction, and concurrent
diff accuracy. Tag-category writes serialize the ownership check and mutation in the same
transaction; a conflict must preserve the complete losing category.

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

Outbound catalog fetches use one 10-second deadline including validation/DNS and the full
response body, with a 1 MB cap and no redirects. Propagate caller cancellation; translate only
the fetch's own expiry and upstream failures to safe 502 problems. Pin direct connections to
one validated DNS snapshot, preserving hostname/certificate verification; never resolve again,
use a proxy, or reuse another fetch's connection. Bound the entire exchange's worker pool and
queue. Native DNS may ignore interruption and retain a bounded worker until the OS returns;
cancelled work must never initiate a late HTTP request. See `.claude/docs/security.md` for the
transport invariants and remaining native-DNS limitation.

Use four-space indentation, preserve existing package boundaries, PascalCase for Kotlin types,
and camelCase for functions and variables. Name backend test classes `*Test`.

## Port Ontology and Entity Queries

Blueprint definitions are ADMIN-managed and readable by any authenticated user; entity CRUD is
a shared authenticated workspace with no ADMIN content privilege. `_team` and `_user` are
protected, extendable system blueprints, distinct from login accounts. Ownership is informational,
never an authorization rule. Direct ownership stores a team string/array; Inherited ownership
resolves `ownership.path` at read time. List/graph team filters match effective ownership,
including inherited teams; graph relation/ownership resolution remains byte-exact.

PostgreSQL remains the only store: blueprint `definition` and entity `document` (`properties` +
`relations`) are JSON serialized into TEXT with `blueprintJson`; unset optional response fields
are absent, not null. Entity rows reference blueprint IDs. Preserve caps and strict validation
from `Blueprint.kt`/`Entity.kt` and reuse `entityFindings` for save rejection and read-time
findings. Do not apply Backstage's `allowInvalid` waiver to Port entity writes. Blueprint renames
cascade target definitions; entity renames cascade relation, team, and team/user-format property
references. Deletes reject active referrers. Keep the documented cooperating-writer locks:
blueprints first, then entities for entity mutations; see `.claude/docs/persistence.md`.
Blueprint edits do not rewrite or revalidate existing instances on write: subsequent reads
recompute findings against the current definition, and the next entity save must fix them.

`hierarchyRelations` is the current extension: an optional map from active `HIERARCHY` dictionary
values to the blueprint's own single-valued (`many: false`) relation keys. It is stored beside
the Port definition (V34 replaces V29's singular `hierarchyRelation`). One relation may serve
several hierarchies; an entity without a selected hierarchy's parent link is a root. Graph edges
carry `hierarchies: string[]`; `$team` ownership edges are separate and never hierarchy parents.
Hierarchy dictionary replacement shares the blueprint write lock and refuses removal of a value
still referenced by an active blueprint. These are relation-based forests, not arbitrary
multi-parent ownership trees. The renderer guards cycles and promotes disconnected cycle
islands to roots; the hierarchy marker does not impose acyclicity on stored entity relations.

Mirrors, jq calculations, and aggregations are computed on reads and merged into response
properties; computed IDs are rejected as write input. Unresolvable computed values are absent,
not findings. Materialize dependencies inside the read transaction, then evaluate outside it.
The shared `JqEvaluator` uses the bounded `entity-jq` worker pool, output cap, per-expression
deadline (default 500 ms), and quarantine for timed-out expression text. Preserve cancellation,
environment/module restrictions, and input-free logging; consult the security/testing docs.

Ontology import accepts up to 200 JSON documents per batch and shares classification with its
dry run. Blueprint import is ADMIN-only; entity import accepts any authenticated user. Preserve
dependency ordering, optional-reference deferral, mandatory entity-cycle rejection, per-row
report-and-skip behavior, and `replaceExisting` full replacement. Reuse the existing planners
and service writes; keep exports compatible with import, including Toadie hierarchy mappings.

The entity query language is an openCypher-shaped read-only subset, not full Cypher. Its pure
parser/validator/evaluator live in `entityquery/`; saved-query persistence/routes share that
package. `GET /api/v1/entities/graph?query=...` evaluates over the whole active workspace, then
intersects returned entities with ordinary blueprint/q/team filters. Hidden intermediate nodes
remain traversable; rendered edges still require both endpoints to be shown. `RETURN` yields a
deduplicated entity set, not projected rows; `LIMIT` applies after deduplication. Predicates see
stored properties and documented metadata, including effective `$team`, not computed properties.
Preserve the language's three-valued comparisons and level-set BFS semantics; do not conflate
them with Port aggregation-filter semantics. Hierarchy identifiers also resolve as virtual
child-to-parent edge types; consult the language reference for precedence and direction rules.

Parse/validate outside database transactions before reading the entity workspace. Evaluation
runs outside the transaction on the dedicated `entity-query` pool with four permits, a
cooperative deadline (default 2 seconds), and binding/hop/input budgets. Saturation returns 429;
semantic/budget refusals return 400 with diagnostics; caller cancellation propagates. The live
`POST /api/v1/entities/query/check` validates without evaluation. Keep diagnostics, grammar,
completion, generated query templates, OpenAPI, and tests synchronized; there is no cross-request
query snapshot cache. Saved queries (`/api/v1/entity-queries`, V35) follow lenses' PRIVATE/PUBLIC
visibility and creator-only mutations, soft deletion, and authorization-before-validation.
Save requires syntactic validity; schema validity is checked when applied against current data.

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
Reuse `useCatalogFileSave`/`useEntitySave` and their finding-to-field helpers; entity save findings
are hard errors with no waiver. Route transport failures through `saveErrorMessage` and
`loadErrorMessage`; never expose raw exception messages as UI copy. Keep stored/stale findings
and rejected-save findings visible on the appropriate fields.

Files, Hierarchy, Graph, and Errors share `CatalogToolbar`, the nine-slot catalog filter state,
always-visible kind pills, and saved lenses. Files/Hierarchy/Graph interpret filters as entities
shown; Errors interprets them as files reported while resolving references workspace-wide. The
quick-view drawer is URL-carried via `?file=<id>`; ordinary view filters remain per-view local
storage. Whole-file operations are centralized and PUT callers must preserve full-replace fields.

Repo sync and Overwrite with YAML share `utils/yamlDiff.ts`/`components/YamlDiffView.tsx`.
Bound detailed LCS work and rendered row count before allocating the matrix. Large documents
fall back to complete stored/replacement YAML panes, never truncated content. Preserve canonical
equality, side attribution, explicit confirmation, and the exact mutation payload; see
`web/CLAUDE.md` for the budgets and fallback accessibility rules.

The `/graph` and `/entity-graph` pages keep React Flow/dagre in lazy chunks and use shared
layout, cluster-frame, fold, and persistence machinery. They have separate per-user server-side
`{mode, positions, collapsed}` documents selected by `useGraphLayout(userId, view)`. A successful
baseline load must precede writes. The persistence controller serializes saves, coalesces queued
edits, and retains failed local changes for explicit retry across ordinary SPA navigation.
Preserve account/view isolation and full documents, including filtered-out node IDs. React Flow
owns live drag state; persist drag positions only after drag end. Do not refit on drags/mode/reset
or let a drag navigate; keep fold controls outside the node's interactive face. Filtering precedes folding;
Backstage uses `buildHierarchy` containment and namespace frames, while Port uses
`buildEntityHierarchy(graph, hierarchyId)` and blueprint frames derived from live positions.

The entity graph and hierarchy share `HierarchyPicker`, the query bar, and the graph API.
Hierarchy selection is stored per view; an obsolete selection falls back to the first dictionary
value, or no hierarchy when empty. Only the selected hierarchy controls folding and hierarchy
edge styling. The entity graph's persisted collapsed-node list is intentionally shared across
hierarchy selections, not a separate layout per hierarchy. Relation chips remain unpersisted.
`useEntityQuery` shares one account-scoped draft/applied query between both canvases; validation is debounced,
but evaluation occurs on Run/Mod+Enter, saved-query selection, or a canvas query action. Reuse
`queryTemplates.ts` for Expand/Ancestors/Descendants/Owned-by actions; preserve the anchor via
OPTIONAL MATCH and use the selected hierarchy for ancestor/descendant traversal.

Follow `web/CLAUDE.md`'s "Responsive data layouts" rules for full-width tables, locally
contained scrolling, readable dynamic columns, and narrow-screen controls.

Pages are lazy and use shared `PageHeader` chrome; navigation is defined once in
`utils/navigation.ts` for the sidebar, user menu, and command palette. `NavSection.world` assigns
sections to Backstage, Port, or global; derive the active world from the route and remember it
on global pages. `/` redirects to the last world's home, defaulting to Port: `/entity-hierarchy`
for Port, `/hierarchy` for Backstage. Preserve cross-world deep links and the shared world switch.

Administrator-created/reset passwords are generated client-side and revealed exactly once;
self-service reset links let the recipient choose a password. The server never returns plaintext
passwords. The selected UI language is also stored on the user and drives server-composed email.
Shared HTTP refresh must respect login boundaries: late responses cannot restore a signed-out
session, replay an old mutation, or clear a newer login.

`web/src/changelog/version.ts` (`APP_VERSION`) is the sole source of the displayed app version;
the Gradle snapshot version is unrelated. A release adds the newest bilingual markdown entry to
`web/src/changelog/entries.ts` and bumps `APP_VERSION` in the same change; tests pin their parity.

## Testing and Verification

Authentication uses database-backed session families (`auth/AuthSessionService.kt`, V25).
Every bearer must match a live family and the active user's monotonic credential epoch; never
cache a successful account/session verdict. Password, email, and role changes
invalidate existing sessions, deletion blocks them, and logout revokes every token generation
from that login. Renewal must not recreate a revoked family. MFA challenges capture the epoch
verified with the password; self password changes compare-and-set it and require re-login.
Deploying V25 requires everyone to sign in again. V26 adds expiring reset grants stored only as
SHA-256 digests. Reset requests preserve credentials; only POST `/api/v1/password-reset/confirm`
consumes the grant and changes the password/epoch atomically, invalidating sibling links,
sessions, and pending MFA challenges without disabling MFA. Never consume a grant on GET,
email passwords, log raw reset tokens, or persist them in browser storage. The confirmation
page reads a URL fragment and removes it from history. Reset requests need configured outbound
mail and a valid `MAIL_APP_URL` origin (HTTPS in production); otherwise they return 503.

Pushes, PRs, and merge groups run `.github/workflows/ci.yml`. Require its **Quality gate**
status in the repository ruleset after pushing: backend, frontend/contract, and reusable E2E
jobs must all succeed. E2E owns a disposable `toadie-ci` project on a fresh hosted runner;
cleanup removes only its data. Local E2E preserves the dev volume. CI fails flaky browser
tests and missing Mailpit rather than silently reducing coverage.

For animated modal actions, use the E2E `readyDialog(page, title)` helper: visible does not
mean the entrance transition has finished. Match mutation responses to their exact resource,
assert the returned status, then wait for completed UI before navigating or cleaning another row.

The published contract is OpenAPI **3.0.3**. Both the conformance validator and frontend generator
consume the same bytes; never relabel the version in tests to accommodate a tool.

Use Kotlin Test/Ktor Test Host, Vitest with Testing Library, and Playwright for cross-stack
journeys. Add focused regression coverage for behavioral changes. Backend tests boot PostgreSQL
through Testcontainers, apply every Flyway migration, and include the V3 seed admin; use unique
markers (`uniqueEmail(...)`) instead of asserting global counts.

Every `/api/` interaction made through the shared backend test clients is checked against OpenAPI.
Prefer `jsonClient()`/`authedClient()` so tests do not bypass conformance validation. `check`
enforces Kover floors of 97% lines and 78% branches (`server/build.gradle.kts`). Frontend floors in
`web/vite.config.ts` are 97% lines, 95% statements, 92% functions, and 91% branches. Re-measure and
raise floors as coverage improves; do not lower them to accommodate new code. Any test-local
Mantine provider must set `env="test"` so popovers and selects work under happy-dom, and use
`TEST_THEME`/`respectReducedMotion: true` with the shared reduced-motion mock so transitions
finish synchronously rather than leaving timers after cleanup. Browser journeys retain real
animations and use dialog-readiness helpers. Use paste for heavy editor fixture setup, retaining
real typing where keyboard/preview behavior is under test.

The pure entity-query parser/validator/evaluator tests run without Docker; route and persistence
tests still require PostgreSQL/Testcontainers. Scale tests establish correctness, not a production
latency guarantee. Run `:server:koverXmlReport` when measuring current backend coverage;
`check` runs the verification gate without refreshing that report.

A new or behaviorally changed e2e test lands with its scenario file in `e2e/scenarios/` and its
coverage-map line in `e2e/README.md` in the same commit; `npm run check:scenarios` enforces all
three mechanically (spec ↔ scenario file existence, `## Scenario:` headings == `test()` titles,
and spec ↔ coverage-map bullet, both directions). For nontrivial cross-stack
behavior, verify through the SPA using the workflow in `.claude/skills/verify/SKILL.md`, and clean
up any records created in the development database.

## Commit, Documentation, and Security

Use Conventional Commit subjects such as `feat:`, `fix:`, `fix(e2e):`, and `docs:`. PRs should
explain behavior and risk, list verification commands, link issues, and include screenshots for UI
changes. Keep migrations, API contract, generated schema, tests, and both translations
synchronized when applicable.

The repository is a shared workspace: before committing, verify the current worktree/branch and
review the staged diff, stage task-owned files by name rather than a blanket `add`, and use
separate worktrees for concurrent independent changes rather than interleaving them in one.

Never commit production JWT or database secrets. Committed `changeme` values and development keys
are burned demo credentials; production mode deliberately refuses them (the JWT fail-closed check
in `plugins/Security.kt` and the seed-password check in `infra/db/Bootstrap.kt`). Mail transport is
also fail-closed: production refuses the `log` transport, and SMTP with a blank host fails in every
mode. The compose demo uses SMTP through Mailpit; the image defaults to disabled mail, where reset
and MFA-dependent flows return 503.
