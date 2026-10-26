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

## Product

- [next] **Convert between the Backstage and Port worlds in both directions, driven by a mapping
  document.** No converter exists today; `.claude/docs/ontology.md` "Backstage export mapping" is prose
  and `SampleBlueprintsTest` pins only the vocabularies. Accepted design (2026-10-26):
  (1) a declarative mapping document: blueprint ↔ kind pairs of rules using seven codecs (`copy`,
  `valueMap`, `tagCategory`, `ref`, `derive`, `link`, `constant`), one rule set read both ways, with a
  one-directional `jq` codec reserved for later; nothing in code names a blueprint, kind, label,
  property or relation, and every schema field is mapped or listed `lossy`;
  (2) stored in an ADMIN-curated `conversion_mappings` registry (soft delete, validated against the
  live blueprints and registries);
  (3) the default mapping shipped as `sample-data/port/commerce-payments/mapping/backstage.json` +
  `load.sh`, never seeded by a migration;
  (4) the Backstage sample regenerated from the Port sample through the exporter, keeping its four
  deliberate findings (adds `description` to eight blueprints, `subcomponent_of` to `library`,
  registers `backstage.io/definition-at-location` for API);
  (5) `replaceExisting` on `POST /files/import` (+ `/check`) for updating catalog files;
  (6) pure conversion endpoints (`GET /conversion-mappings/{id}/catalog-documents`,
  `POST /conversion-mappings/{id}/entity-documents`); the SPA chains the existing imports and their
  dry runs, so Port-only data is preserved and no new writer exists;
  (7) a mapping broken by a later edit is refused on write, flagged on read and on the Port Errors
  report, vocabulary drift report-only;
  (8) v1 UI in the Port world only (Mappings page, Export-to-Backstage page, a "Backstage catalog"
  source on `/ontology/import`).
  Proof: CI exports the Port sample and must equal the Backstage sample, and imports the Backstage
  sample and must equal the Port sample, modulo an explicit deviation list. Phases, one PR each:
  mapping registry + validator + default mapping (V45, Opus review); exporter + sample
  reconciliation; `replaceExisting` on the catalog import (independent); importer (Opus review);
  SPA. Roughly 11 days in total. Shipped so far: (5) `replaceExisting` on the catalog import (2.23.0).
- [new] **Treat empty and absent blueprint maps as equal in the sync modal.** After a sync the stored
  blueprint renders empty `relations`, `mirrorProperties`, `calculationProperties` and
  `aggregationProperties` maps, so a source document that omits them never reads "in sync": reopening
  the modal still shows a diff and keeps Overwrite enabled (`canonicalBlueprintDocumentJson` over
  `blueprintExportDocument(detail)` vs. the picked source). The e2e fixture spells the four empty maps
  out to work around it. Normalise both sides the same way and pin it with a vitest.

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
