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
  (login, refresh, password-reset, MFA, integration and SSO) and the per-user `telemetry` bucket
  (`TELEMETRY_RATE_LIMIT`, `auth/AuthRoutes.kt`); the jq quarantine set and compile cache
  (`entities/JqCalculation.kt`); the entity-query admission semaphore `querySlots`
  (`entities/EntityService.kt`); the read ledger (`infra/concurrency/ReadLedger.kt`); and, in
  `auth/sso/`, the SSO hand-off store (`SsoHandoffs`), seen-state set (`SsoSeenStates`) and the
  discovery/JWKS `StaleServingCache` copies (`SsoDiscovery`, `IdTokenVerifier`). Move or
  redesign those stores (`MfaChallenges`, `SsoHandoffs` and `SsoSeenStates` are thin policies
  over `infra/concurrency/BoundedTtlStore`, so a shared backend implements that one class; the
  two throttles — `LoginThrottle`, `PasswordResetThrottle` — are per-principal recency maps and
  a separate, smaller seam),
  test cross-replica behavior, and only then change `k8s/app-deployment.yaml`
  from one replica and `Recreate`. Until then, one replica is an intentional deployment rule, not a broken rollout.

## Ontology

- [new] **Adoption/relation consistency as Errors-report rows.** 2.36.0 ships the check as four PUBLIC sample saved queries (NOT EXISTS over the consumes_* relation and the adoption pair, both directions). What remains is surfacing a saved query's non-empty result on Port Errors without naming the ontology — which means EVALUATING chosen saved queries inside the report, against its never-evaluates rule and the entity-query budget/permits. Decide the mechanism (a "check" flag on saved queries + a per-report evaluation cap) before promoting.

- [new] **Review entity history growth with real numbers.** 2.37.0 keeps `entity_events` forever
  (immutable, like `catalog_file_events`): every event is bounded (40 paths, 4,000 characters of
  values) and a no-op save mints nothing, but a write loop that really changes values, repeated syncs
  and team renames (one event per referrer) all add rows. Watch "Entity history events" in the
  telemetry Workspace section and decide on retention (newest N per entity, or an age prune) only
  once there is data.

- [new] **An MCP `get_entity_history` tool.** 2.37.0 serves entity history over REST only
  (`GET /api/v1/entities/{id}/events`); the MCP catalogue is a fixed ten tools. Add a read-scope tool
  returning the same paged events when an agent needs to ask who changed what.

- [new] **Bulk change of one property across entities of the same blueprint.** Requested
  2026-11-16, to follow the entity change history. A "Bulk change" button on the Entities, Entity
  graph and Entity hierarchy views opens a new view shaped like Entity hierarchy, except that the
  Blueprints pill row allows exactly one blueprint and every row has a checkbox. "Confirm" opens a
  form asking which property to change and to what value; the value input follows that property's
  schema type. Each changed entity is an ordinary strict entity write, so it gets its own history
  event and audit line, and a row that fails validation is reported and skipped rather than failing
  the batch. To decide before promoting: whether computed or relation fields are offered, clearing a
  value, the batch cap (the import's 200?), and a dry-run preview of what will change.

## Housekeeping

- [new] **Re-check the MCP transport facts on the first MCP Kotlin SDK bump.** The SDK is pinned
  at 0.15.0; `integration/Mcp.kt` relies on its stateless mode needing no `initialize`, on the
  Accept-header pair and on unknown tools being tool results. Re-verify those with the real
  TypeScript MCP client before merging the bump.
