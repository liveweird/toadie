# Toadie

Toadie will help with Backstage `catalog-info.yaml` files:

- **create them visually** — the only allowed format, with automatic validation,
- **cross-check them** — one file can reference another,
- **render them altogether** — a combined view built from the content of several files.

All three pillars are implemented, on top of the full stack, tooling, quality gates, and a
working authentication surface with admin-managed accounts (user CRUD with a one-time
generated-password reveal, self-service password change):

- **visual creation** of `catalog-info.yaml` files across the seven landscape kinds
  (Component, API, System, Domain, Resource, Group, User) — validated per kind against the
  Backstage descriptor format, stored server-side with full CRUD and a paginated list, live
  YAML preview, one-click download, and reference pickers suggesting the stored entities,
- **cross-checking**: the **Errors** report — every error class in the stored files
  (unresolved/wrong-kind/self references — owners, systems, APIs, domains, groups and users
  included — registry violations, structurally drifted legacy rows, removed namespaces),
  filterable like the Files list plus error-type pills — plus a live reference panel in the
  editor; findings never block saving,
- **source references & repo sync** (v1.11.0): tag a file with an optional `sourceUrl` — its
  canonical copy's https address in a GitLab/GitHub repo — set by hand or stamped
  automatically by a fetch-from-URL import, tracked by the Files list's sortable Last-sync
  column, and reconciled through the Sync-from-repo modal's fetch-diff-confirm overwrite;
  a missing reference is a report-only Errors finding, and DB→repo sync does not exist,
- **per-file change history** (v1.15.0): every catalog mutation appends an immutable,
  field-level event to the file's own trail, read back paged and rendered in the viewer's
  language as the editor's History section — a no-op save records nothing, a sync always
  records something, and a deleted file's history outlives it,
- **blueprints** (the first step toward [Port.io](https://docs.port.io/context-lake/data-model/configure-data-model/)
  compatibility): define your own entity kinds at `/blueprints` — typed properties with
  formats, enums and colours, relations between blueprints, mirror/calculation/aggregation
  properties and ownership — in a full-page editor with a live preview of the Port-native
  JSON; ADMIN-curated, readable by everyone,
- **entities** (Port migration phase 2): instances of a blueprint at `/entities` — properties
  typed by the owning blueprint's schema, relations naming other entities, and live validation
  findings on every read so an entity a later blueprint edit made stale is visibly flagged;
  any authenticated user may create, edit, and delete them, the same shared-workspace rule as
  catalog files. Phase 3 renders them together: the `/entity-graph` relationship graph and the
  `/entity-hierarchy` containment tree, reusing the catalog's own dagre layout, folding, and
  per-user manual-layout persistence, with each blueprint able to name one of its own
  single relations per hierarchy in its `hierarchyRelations` map — the Toadie-only extension that decides an
  entity's parent for the tree/fold,
- **users and teams** (Port migration phase 4, v1.26.0): the seeded `_team`/`_user` system
  blueprints give every entity real `$team` ownership — Direct or computed Inherited along a
  blueprint-defined path — with a team filter on the entity list and graph; ownership is
  informational and never gates permissions,
- **computed properties** (Port migration phase 5, v1.27.0): mirror, calculation (real jq),
  and aggregation properties are evaluated at entity read time and merged into every
  GET/list/create response, never stored and never accepted back as write input,
- **ontology import and export** (Port migration phase 6, v1.28.0): bulk-import up to 200
  raw Port-shaped JSON documents for blueprints or entities with one shared dry-run/real-run
  classification and automatic dependency ordering, plus a client-side JSON export that the
  same importer accepts back unchanged,
- **lenses**: save the current filter set under a name and re-apply it from a combo box on
  any of the Hierarchy, Files, Graph, and Errors views — each lens private (only you) or
  public (visible to everyone, changeable only by its creator),
  and
- **rendering together**: the `/graph` relationship graph — every stored file and the
  reference edges between them (missing and external targets drawn as virtual nodes), with a
  namespace filter and per-relation toggles, and
- **the YAML round-trip**: import existing (multi-document) `catalog-info.yaml` files —
  pasted, picked, or **fetched from a URL** (server-side and SSRF-guarded, so GitHub, GitLab,
  and self-hosted Git all work; blob links are converted to raw automatically) — parsed
  client-side, each document imported independently with a per-row result report
  (created / invalid / already-exists; nothing overwritten) — and export the workspace (or
  one namespace) back as a single `---`-separated `catalog-info.yaml`.

## The stack

- **Backend**: Kotlin + [Ktor](https://ktor.io) (Netty), JWT auth with refresh tokens and a
  server-side revocation blocklist, PostgreSQL with [Flyway](https://flywaydb.org) migrations
  and [Exposed](https://github.com/JetBrains/Exposed) (R2DBC), OpenTelemetry, RFC 7807
  problem-detail errors, Swagger UI at `/openapi` (development mode).
- **Frontend**: [Vite](https://vite.dev) + React 19 + TypeScript + [Mantine](https://mantine.dev),
  react-i18next (English + Polish), typed API client generated from the OpenAPI contract.
- **Quality gates**: detekt (zero findings), Kover coverage floors, ESLint + sonarjs, knip,
  Vitest coverage floors, runtime OpenAPI conformance in the server test suite, Playwright e2e
  with axe accessibility scans.

## Running the whole stack (one command)

```bash
docker compose up --build
```

Then open <http://localhost:8081> and sign in as `admin@toadie.local` / `changeme`.
Swagger UI: <http://localhost:8081/openapi>.

Compose binds the app, PostgreSQL, and Mailpit ports to **127.0.0.1 only**. This is a local
HTTP demo with known credentials, not a LAN/public deployment. `MAIL_APP_URL` changes email
links, not network exposure or security. Recreate existing containers to apply changed port
bindings, keeping the database volume.

Ports are chosen to coexist with [Lettuce](https://github.com/liveweird/lettuce) on the same
machine: the app is on **8081**, Postgres is host-mapped to **5433**, and the Vite dev server
uses **5174**.

The database comes up with the admin-curated registries already filled in — namespaces,
lifecycles, per-kind types, labels, tag categories and annotation keys — but with an **empty
catalog**. To get something to look at, load [`sample-data/`](sample-data/README.md): a
34-entity landscape covering all seven kinds, pasted or picked on the **Import** page. It
speaks only the seeded vocabulary, and carries four deliberately broken references so the
Errors report and the Graph have something to show. The blueprint registry (`/blueprints`)
starts empty too — load [`sample-data/blueprints/`](sample-data/README.md#blueprints-port)
with `sample-data/blueprints/load.sh` for the eleven-blueprint baseline ontology the catalog is
built on ([`.claude/docs/ontology.md`](.claude/docs/ontology.md)), then
[`sample-data/entities/`](sample-data/README.md#entities-port) with its own `load.sh` for the
same landscape as Port entities.

## Running on Kubernetes (local)

With a local cluster that shares the Docker image store (e.g. OrbStack) and an ingress-nginx
controller (install recipe, host choice and the full walkthrough in
`.claude/skills/run-stack/SKILL.md`):

```bash
docker build -t toadie-app:latest .
kubectl create namespace toadie
# out-of-band config FIRST — the templates under k8s/templates/ carry placeholders and are never applied:
#   the toadie-secrets Secret   — command in k8s/templates/secret.yaml
#   the toadie-tls TLS Secret   — self-signed recipe in k8s/templates/tls-secret.yaml
#   the toadie-config ConfigMap — kubectl -n toadie create configmap toadie-config --from-literal=MAIL_APP_URL=https://$HOST
kubectl apply -f k8s/            # non-recursive on purpose: k8s/templates/ is skipped, so this is safe to re-run
sed "s#toadie.example.com#$HOST#g" k8s/templates/app-ingress.yaml | kubectl apply -f -
```

The app runs in production mode behind the TLS-terminating Ingress; its probes hit the dedicated
`/healthz` (liveness) and `/readyz` (readiness) endpoints.

Self-service password reset requires outbound SMTP and `MAIL_APP_URL` set to the external
origin (HTTPS in production; HTTP also allowed in development; no subpath/query/fragment).
Without either, requests return 503; Kubernetes defaults to disabled mail. Reset emails carry
single-use links, valid for 15 minutes by default (`PASSWORD_RESET_TOKEN_TTL_SECONDS`, 1–3600).
Requesting/opening a link does not change credentials. Only confirmation sets the chosen
password and signs out existing sessions; MFA stays enabled. Deploy the matching server and
SPA together. V26 adds the reset-grant table without otherwise signing users out.

## Local development

Three processes:

```bash
docker compose up postgres          # PostgreSQL on localhost:5433
./gradlew :server:run               # API on localhost:8081
cd web && npm install --legacy-peer-deps && npm run dev   # SPA on localhost:5174 (proxies /api)
```

The local JDK is managed by [mise](https://mise.jdx.dev) (`mise.toml`, Temurin 21).

## Automated quality gates

Pushes, PRs, and merge queues run `.github/workflows/ci.yml`: backend build/tests/coverage,
frontend build/lint/dead-code/coverage, OpenAPI lint and generated-type drift, and browser
journeys against a fresh disposable Compose stack. CI fails flaky journeys and requires the
Mailpit email tests. Reports are retained for seven days.

After pushing the workflow, configure **Quality gate** as a required repository status check.
Committed workflow YAML alone does not prevent unchecked merges.

Local equivalents: `./gradlew build :server:koverXmlReport`; in `web/`, `npm ci --legacy-peer-deps`
followed by `npm run check:api`, `npm run lint:api`, `npm run build`, `npm run lint`,
`npm run knip`, and `npm run test:coverage`; in `e2e/`, `npm ci`, `npm run typecheck`,
`npm run check:scenarios`, and `npm test` (the local suite still preserves the dev database).

The published API contract is **OpenAPI 3.0.3**, validated without rewriting its version.
See [HARDENING.md](HARDENING.md) for the remaining staged work.

## Useful Gradle tasks

| Task | What it does |
| ---- | ------------ |
| `./gradlew build` | Compiles everything and runs every gate: detekt, tests (Testcontainers), Kover verify |
| `./gradlew :server:run` | Runs the API against the compose Postgres |
| `./gradlew :server:test` | Server test suite (needs a Docker daemon for Testcontainers) |
| `./gradlew detekt` | Static analysis only |
| `./gradlew :server:installDist` | Builds the runnable distribution (used by the Docker image; never `buildFatJar`) |

Frontend: `cd web && npm run build | lint | test | test:coverage | knip | gen:api`.
E2E: `cd e2e && npm ci && npx playwright install chromium && npm test`.

## License

MIT — see [LICENSE](LICENSE).
