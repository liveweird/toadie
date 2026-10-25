# Current backlog

This is Toadie's single actionable backlog. It contains open work only; completed changes live in
Git history, release notes, tests, and the current reference docs. The API guideline
[known-gaps register](api-guidelines/API-GUIDELINES.md#appendix-known-gaps-register) is the
canonical record of accepted API deviations, not a second implementation plan. Promote a gap
here when it becomes scheduled work, and remove its register entry only when it is resolved.

Each top-level item carries one status tag: `[next]`, `[todo]`, `[new]` (proposed, not yet
confirmed), `[parked]` or `[blocked]`. A proposed item enters as `[new]`; only the maintainer
promotes it. A finished item is removed, not ticked.

## Deployment

- [new] **Replace the ingress-nginx reference with a maintained Traefik deployment.** Inventory
  other workloads using the current controller before cutover. Verify TLS, HTTP-to-HTTPS
  redirects, HSTS, forwarded-header trust and client IPs, rate limits, body limits, health
  probes, and rollback against the replacement. Set the public `MAIL_APP_URL`, configure
  production SMTP if reset/MFA email is needed, and test email links through that origin.
  Update `k8s/templates/app-ingress.yaml` and the run-stack guide together; do not retire a
  controller used by another application.
- [new] **Deploy an immutable application image.** Publish each release to a registry the target
  cluster can reach, deploy by digest instead of `toadie-app:latest`, and document the update
  and rollback procedure. Verify that the running pod's digest is the reviewed release.
- [new] **Set production recovery targets and capacity from real measurements.** An operator must
  choose RPO/RTO, monitor PostgreSQL size and growth, set alerts, and confirm the backup
  schedule and retention against those targets. The 10 GiB initial claim and isolated restore
  drill are already implemented; the procedure is in [k8s/BACKUP-RESTORE.md](k8s/BACKUP-RESTORE.md).

## Scaling constraint

- [new] **Remove instance-local state before allowing multiple app replicas.** Every one of these
  is per-process: MFA challenges; login/reset throttles; the token-blocklist cache (it can delay
  cross-instance revocation); the `IntegrationLimits` admission semaphore, per-client buckets and
  the retained-value ledger (`integration/IntegrationLimits.kt`); the Ktor per-IP buckets
  (login, refresh, password-reset, MFA and integration); the jq quarantine set
  (`entities/JqCalculation.kt`); and the read ledger (`infra/concurrency/ReadLedger.kt`). Move or
  redesign those stores, test cross-replica behavior, and only then change `k8s/app-deployment.yaml`
  from one replica and `Recreate`. Until then, one replica is an intentional deployment rule, not a broken rollout.

## Reliability

- [new] **Bound a statement already in flight when the database freezes.** 2.20.2 bounds every
  request that reaches the pool during a hang, but a statement already on the wire when PostgreSQL
  froze waits until it thaws (or TCP gives up), holding one pool slot (`.claude/docs/persistence.md`
  "A hung database is bounded", Exception (i)). A fix is a decorator below the pool: a first-signal
  timeout on every exchange, plus a poison flag that makes `validate()` fail so r2dbc-pool invalidates
  the connection on release. It needs a fake-connection test for every delegated r2dbc-spi method.
- [next] **Make pooled-connection release cancel-proof.** r2dbc-pool's `PooledConnection.close()` runs a
  validation and a rollback before `ref.release()`. If the awaiting coroutine is cancelled while that
  rollback waits on a frozen server, the release may never run and the slot stays acquired (read from
  the bytecode, not reproduced). Confirm with a held-statement/cancel regression; the fix would subscribe
  the release eagerly, or close under `NonCancellable`.
- [next] **Stop re-sending `SET statement_timeout` before every statement.** Exposed 1.6.0 R2DBC sends it
  before each statement from the transaction's `queryTimeout` (with 0 before 2.20.2, 30 s since),
  roughly doubling round trips. Flow's `StatementTimeoutCachingConnectionFactory` skips an unchanged
  value; measure the gain before porting it.
- [next] **Smoke the Kubernetes deployment on the new Netty/Reactor line.** 2.18.3 moved
  r2dbc-postgresql onto reactor-netty 1.3 / Netty 4.2, whose DNS resolver now answers the database
  hostname. Compose (Docker DNS, `postgres`) is covered by e2e; the k8s short name with search
  domains and `ndots` is not. Deploy to the OrbStack `toadie` namespace once and confirm `/readyz`.

## Performance

Found by the first capacity probe (2026-10-24: the Port sample replicated to 1k–10k entities, the
Backstage sample to 10k files, on a disposable stack).

- [new] **Draw only the visible part of very large graphs.** Since 2.20.0 the layout runs in a
  worker, but "Show everything" on 1,000 entities still costs two main-thread tasks (414 ms and
  230 ms) when React Flow mounts 1,000 nodes and 3,435 edges. Measure React Flow's
  `onlyRenderVisibleElements` (and its effect on the fit-view and fold behaviour) on both canvases.

## Product

- [new] **Decide whether import dry runs should validate batch `sourceUrl` like real imports.**
  `/files/import/check`, `/blueprints/import/check`, and `/entities/import/check` currently
  ignore an invalid batch-level source URL that the corresponding import rejects. The
  published contract documents this difference. If changed, update the shared preflight,
  OpenAPI/generated types, and focused regressions together. This is a product-consistency
  improvement, not an authorization or data-integrity defect.
- [next] **Sample saved queries for the newer Port blueprints.** The Port demo ships no saved
  queries for `product`, `dataset`, `api_adoption` or `dataset_adoption`; add a few PUBLIC ones
  (sample data only, under `sample-data/port/commerce-payments/`) that show the adoption
  relations and the regulatory flags on the Entity graph and hierarchy canvases.

## Tests

- [new] **Click through a successful blueprint sync in the browser.** The e2e suite stops at the
  loopback refusal by design, since the SSRF guard rejects every address a local fixture server
  could use. A success journey needs a fixture source the guard accepts and the browser suite can
  reach; until then the server suite owns the success path.

## Housekeeping

- [new] **Align Temurin with the `21.0.12.1` respin.** Dependabot proposed `eclipse-temurin:21.0.12.1_1`
  (#116, closed by Dependabot once the Dockerfile moved to tag@digest pins), but `mise.toml` and CI's
  `setup-java` must name the same build (`infrastructure.test.js` pins the parity) and mise does not
  list `21.0.12.1` yet. When Dependabot reopens the bump and mise lists the build, align `mise.toml`
  and `ci.yml` on that branch.
- [new] **Re-check the MCP transport facts on the first MCP Kotlin SDK bump.** The SDK is pinned
  at 0.15.0; `integration/Mcp.kt` relies on its stateless mode needing no `initialize`, on the
  Accept-header pair and on unknown tools being tool results. Re-verify those with the real
  TypeScript MCP client before merging the bump.
- [new] **Automate the Gradle Dependabot fix-up.** Dependabot's Gradle PRs edit only
  `libs.versions.toml`; the lockfiles and `gradle/verification-metadata.xml` must be regenerated
  by hand from an empty Gradle home ([dependency-reproducibility.md](.claude/docs/dependency-reproducibility.md))
  before strict verification passes. Consider a small fix-up workflow if the weekly cadence hurts.
