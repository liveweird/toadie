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
- [parked] **Deploy an immutable application image.** Publish each release to a registry the target
  cluster can reach, deploy by digest instead of `toadie-app:latest`, and document the update
  and rollback procedure. Verify that the running pod's digest is the reviewed release. Parked
  2026-10-25 until a real (non-laptop) cluster is planned: on compose/OrbStack the shared local image
  store makes it overhead. The multi-arch build and the image OS-layer scan already ship in the
  Quality gate; a plan exists (GHCR, release-only write-once tags, attested provenance, Lettuce's
  fail-closed digest renderer ported into `k8s/templates/`, a DEPLOY-ROLLBACK runbook).
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

## Housekeeping

- [new] **Align Temurin with the `21.0.12.1` respin.** The runtime JRE already runs
  `eclipse-temurin:21.0.12.1_1-jre` (its image is the one the OS-layer scan passes), while the build
  JDK, `mise.toml` and CI's `setup-java` stay on `21.0.12+8`: `infrastructure.test.js` requires the
  JDK stage to equal mise exactly and lets the runtime run a same-update respin ahead. mise does not
  list `21.0.12.1` yet, so a Dependabot bump of the JDK stage to the respin goes red on that parity
  test (intended). When mise lists the build, move `mise.toml`, `ci.yml` and the JDK stage together.
- [new] **Re-check the MCP transport facts on the first MCP Kotlin SDK bump.** The SDK is pinned
  at 0.15.0; `integration/Mcp.kt` relies on its stateless mode needing no `initialize`, on the
  Accept-header pair and on unknown tools being tool results. Re-verify those with the real
  TypeScript MCP client before merging the bump.
