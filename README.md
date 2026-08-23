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
- **cross-checking**: a workspace report resolving every entity reference between stored
  files — owners, systems, APIs, domains, groups and users included (missing targets and
  kind-less `dependsOn` entries as errors; only Location/Template/custom kinds stay
  not-checkable) — plus a live reference panel in the editor; findings never block saving,
  and
- **rendering together**: the `/render` relationship graph — every stored file and the
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

Ports are chosen to coexist with [Lettuce](https://github.com/liveweird/lettuce) on the same
machine: the app is on **8081**, Postgres is host-mapped to **5433**, and the Vite dev server
uses **5174**.

## Running on Kubernetes (local)

With a local cluster that shares the Docker image store (e.g. OrbStack):

```bash
docker build -t toadie-app:latest .
kubectl create namespace toadie
# create the toadie-secrets Secret — see the header comment in k8s/secret.yaml
kubectl apply -f k8s/
```

## Local development

Three processes:

```bash
docker compose up postgres          # PostgreSQL on localhost:5433
./gradlew :server:run               # API on localhost:8081
cd web && npm install --legacy-peer-deps && npm run dev   # SPA on localhost:5174 (proxies /api)
```

The local JDK is managed by [mise](https://mise.jdx.dev) (`mise.toml`, Temurin 21).

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
