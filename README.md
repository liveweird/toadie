# Toadie

Toadie is a shared workspace for two catalog models: **Backstage** descriptors and a
**Port-style ontology**. Switch between the two worlds in the sidebar. A fresh session opens
Port's Entity hierarchy; later visits remember your last world.

- **Backstage catalog:** visually create, validate, import, export, and cross-reference the
  seven `catalog-info.yaml` kinds (Component, API, System, Domain, Resource, Group, User).
  Files, Hierarchy, Graph, and Errors share filters and private/public saved lenses.
  Structural and namespace errors block saves. Registry/reference findings normally block
  saves too, but an explicit **Save anyway** can waive those soft findings; catalog import
  reports them while storing structurally valid documents.
- **Source synchronization and history:** an optional credential-free HTTPS `sourceUrl`
  enables SSRF-guarded, one-way repository-to-database synchronization. Review the YAML diff
  and confirm before overwriting. Every catalog mutation maintains immutable per-file
  structural history; no-op saves do not add events, while syncs always do.
- **Port blueprints and entities:** administrators define typed schemas, relations, and
  computed properties; any authenticated user can manage entity instances. Entity saves
  must satisfy the current blueprint and have no catalog-style validation waiver. The
  protected `_team` and `_user` blueprints support direct and inherited ownership, which is
  informational and does not grant permissions.
- **Parallel hierarchies and graphs:** name hierarchies such as composition, ownership, or
  cost center, then map each to a blueprint's single-valued parent relation. Entity Graph
  and Entity hierarchy support filtering, folding, and per-user graph layouts. Mirror,
  jq calculation, and aggregation properties are computed on reads.
- **Entity queries:** use the bounded, read-only openCypher-shaped subset or its guided
  builder to select entities without memorizing syntax. Queries support saved PRIVATE/PUBLIC
  definitions and canvas actions such as expansion, ancestors, and descendants. This is
  an entity-set query language, not full Cypher or a projected-row reporting engine; see the
  [language reference](.claude/docs/entity-query-language.md).
- **Ontology import/export:** import up to 200 blueprint or entity JSON documents, preview
  a dry-run report, and optionally replace existing records. Dependency ordering and
  supported optional cycles are handled automatically. Exports produced by Toadie can be
  imported again; arbitrary upstream metadata must be removed before import. See the
  [Port model reference](.claude/docs/port-data-model.md) and [sample-data guide](sample-data/README.md).
- **Shared application services:** administrator-managed login accounts and feature flags,
  one-time generated password reveals, self-service password changes and reset links,
  opt-in email MFA, synchronized English/Polish language, and the shared application shell.
  Administrator privileges govern management features, not ownership of shared catalog data.

For contributor conventions and the task-specific documentation map, start with
[AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md). [HARDENING.md](HARDENING.md) records completed
hardening work as well as explicitly outstanding items.

## The stack

- **Backend**: Kotlin + [Ktor](https://ktor.io) (Netty), JWT auth with refresh tokens, database-backed
  session-family revocation and per-user credential epochs (plus token blocklist checks), PostgreSQL with [Flyway](https://flywaydb.org) migrations
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

The database comes up with the admin-curated registries already filled in, but catalog content
is not seeded. [`sample-data/`](sample-data/README.md) offers two independent commerce/payments
demos: a [Backstage software catalog](sample-data/backstage/commerce-payments/README.md) with 34
documents and four intentional reference findings, and a
[Port ontology](sample-data/port/commerce-payments/README.md) with eleven blueprint definitions
and 59 entity instances. Each guide names its own import page and loader workflow.

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
`/healthz` (liveness) and `/readyz` (readiness) endpoints. GraphQL defaults to disabled in
Kubernetes; opt in through `toadie-config` as described in the
[integration deployment guide](.claude/docs/integration-api.md#deployment-and-limits).
REST and GraphQL share the deployment's database; the Compose and Kubernetes databases are
independent by default.

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

## Integration API (read-only, for other apps)

Toadie also exposes Port blueprints, entities, and ontology errors through a separate GraphQL
API, following Lettuce's machine-client architecture. It is disabled by default; set
`INTEGRATION_ENABLED=true` to enable it (the local Compose demo already does). An administrator
creates a client under **Integration clients**, copies the key from its one-time reveal,
and can revoke it there later. Treat the key as read access to the entire Port workspace.

```sh
curl http://localhost:8081/integration/graphql \
  -H "Authorization: Bearer $TOADIE_INTEGRATION_KEY" \
  -H 'Content-Type: application/json' \
  --data '{"query":"{ blueprints(pageSize: 10) { items { id identifier title } total } entities(pageSize: 10) { items { id identifier properties } total } errors { checkedEntities checkedBlueprints } }"}'
```

Use the same key for `GET /integration/graphql/schema` or GraphQL introspection. Lists use
one-based pages (default 20, maximum 100). Entity reads include computed properties and effective
ownership. Errors expose entity/blueprint findings; login accounts, Backstage data, saved queries,
mutations and subscriptions are outside this API. IDs are GraphQL decimal strings and dynamic
Port fields are JSON values. Query/response budgets may refuse expensive reads; request smaller
pages. Key administration is JWT-authenticated REST and remains available while GraphQL is disabled.

See the [integration reference](.claude/docs/integration-api.md),
[GraphQL rules](api-guidelines/GRAPHQL-GUIDELINES.md), and
[committed schema](server/src/main/resources/graphql/schema.graphqls) for the full contract.

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
