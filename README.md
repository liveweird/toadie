# Toadie

Toadie will help with Backstage `catalog-info.yaml` files:

- **create them visually** — the only allowed format, with automatic validation,
- **cross-check them** — one file can reference another,
- **render them altogether** — a combined view built from the content of several files.

Implemented so far: the full stack, tooling, quality gates, a working authentication surface,
and the first two catalog features —

- **visual creation** of Component `catalog-info.yaml` files (validated against the Backstage
  descriptor format, stored server-side with full CRUD and a paginated list, live YAML
  preview, one-click download), and
- **cross-checking**: a workspace report resolving every entity reference between stored
  files (missing targets and kind-less `dependsOn` entries as errors; kinds Toadie doesn't
  store yet listed as not-checkable-yet), plus a live reference panel in the editor.
  Findings never block saving.

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
