# Current backlog

This is Toadie's single actionable backlog. It contains open work only; completed changes live in
Git history, release notes, tests, and the current reference docs. The API guideline
[known-gaps register](api-guidelines/API-GUIDELINES.md#appendix-known-gaps-register) is the
canonical record of accepted API deviations, not a second implementation plan. Promote a gap
here when it becomes scheduled work, and remove its register entry only when it is resolved.

## Deployment

- [ ] **Replace the ingress-nginx reference with a maintained Traefik deployment.** Inventory
  other workloads using the current controller before cutover. Verify TLS, HTTP-to-HTTPS
  redirects, HSTS, forwarded-header trust and client IPs, rate limits, body limits, health
  probes, and rollback against the replacement. Set the public `MAIL_APP_URL`, configure
  production SMTP if reset/MFA email is needed, and test email links through that origin.
  Update `k8s/templates/app-ingress.yaml` and the run-stack guide together; do not retire a
  controller used by another application.
- [ ] **Deploy an immutable application image.** Publish each release to a registry the target
  cluster can reach, deploy by digest instead of `toadie-app:latest`, and document the update
  and rollback procedure. Verify that the running pod's digest is the reviewed release.
- [ ] **Set production recovery targets and capacity from real measurements.** An operator must
  choose RPO/RTO, monitor PostgreSQL size and growth, set alerts, and confirm the backup
  schedule and retention against those targets. The 10 GiB initial claim and isolated restore
  drill are already implemented; the procedure is in [k8s/BACKUP-RESTORE.md](k8s/BACKUP-RESTORE.md).

## Scaling constraint

- [ ] **Remove instance-local authentication state before allowing multiple app replicas.** MFA
  challenges and login/reset throttles are per-process, and the extra token-blocklist cache
  can delay cross-instance revocation. Move or redesign those stores, test cross-replica
  behavior, and only then change `k8s/app-deployment.yaml` from one replica and `Recreate`.
  Until then, one replica is an intentional deployment rule, not a broken rollout.

## Unscheduled candidate

- [ ] **Decide whether import dry runs should validate batch `sourceUrl` like real imports.**
  `/files/import/check`, `/blueprints/import/check`, and `/entities/import/check` currently
  ignore an invalid batch-level source URL that the corresponding import rejects. The
  published contract documents this difference. If changed, update the shared preflight,
  OpenAPI/generated types, and focused regressions together. This is a product-consistency
  improvement, not an authorization or data-integrity defect.
