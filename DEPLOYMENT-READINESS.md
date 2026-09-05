# Deployment readiness review

Audit date: 2026-09-05. Reviewed revision: `f97cba6`.

> Historical snapshot, not the current readiness status. Subsequent changes added isolated
> secret/ingress templates, production-compatible health probes, a TLS ingress template,
> Gradle dependency locks, and a JDK 21 runtime. The hardening changeset adds loopback-only
> Compose defaults and automatic verification. The original evidence below is preserved;
> see [HARDENING.md](HARDENING.md) for completed and remaining work.

Docker Compose works as a local demo. The supplied Kubernetes deployment flow has two
reproduced blockers. Dependency locking covers npm installs, but the complete build and
deployment environment is not yet reproducible. This report records recommendations;
the fixes below have not been implemented.

## Verified results

| Check | Result |
| --- | --- |
| `docker compose up --build -d` | Image built and all three services started; PostgreSQL was healthy and Flyway validated 24 migrations against the existing Compose database. |
| Compose addressing | SPA and login returned HTTP 200 through localhost and the host LAN IP. SPA and login also worked with a simulated DNS hostname. |
| Documented Kubernetes deployment flow | Failed: applying the manifest directory overwrote the valid Secret with placeholder values. |
| Kubernetes with valid credentials | App booted, but HTTP health probes followed the production HTTPS redirect to an unavailable pod port 443, causing repeated restarts. |
| Kubernetes with temporary local HTTP settings | Rolled out successfully with `KTOR_DEVELOPMENT=true` and `HTTP_BEHIND_PROXY=false`; remained healthy for over two hours with zero restarts. |
| Kubernetes addressing | SPA returned HTTP 200 through localhost port-forward, the LoadBalancer IP, and a simulated DNS hostname. Login succeeded through the LoadBalancer IP. |

The local environment was Docker 29.4.0, Compose 5.1.2, and OrbStack Kubernetes v1.35.6.
Kubernetes reused the locally built image through OrbStack's shared image store.

These were deployment and HTTP/API smoke checks. The image build reused cached dependency
and backend layers; it was not a fully uncached build. Public DNS, a real TLS ingress,
cross-platform builds, full browser journeys, and email delivery were not verified by this
audit. Successful HTTP checks do not establish full feature compatibility on every origin.

## Priority 1: fix the Kubernetes deployment blockers

### Keep placeholder secrets outside the applied manifest set

The run-stack instructions create a real `toadie-secrets` Secret, then run
`kubectl apply -f k8s/`. That directory includes [k8s/secret.yaml](k8s/secret.yaml), so the
second step replaces the valid values with placeholders. The app then fails its production
JWT check and enters `CrashLoopBackOff`.

PostgreSQL can also initialize its persistent volume with the placeholder database password.
Replacing the Kubernetes Secret afterwards does not change the password stored inside
PostgreSQL; the audit reproduced the resulting authentication failure too.

Recommendations:

- Move the Secret template outside the applied directory, or provide an explicit Kustomize
  resource list that excludes it.
- Keep real credentials out of version control and provision them separately.
- Update the run-stack instructions to use the exact safe resource selection.
- Document credential rotation separately from initial database provisioning. Recover an
  existing database by reconciling its credentials; do not delete its volume as a repair step.

### Make health probes work in production mode

The readiness and liveness probes in [k8s/app-deployment.yaml](k8s/app-deployment.yaml)
request HTTP `/` on port 8081. Production mode in
[plugins/Http.kt](server/src/main/kotlin/plugins/Http.kt) redirects that request to HTTPS
on port 443. Kubernetes follows the redirect, receives connection refused, and restarts
the application after repeated liveness failures.

Recommendations:

- Add explicit health endpoints that probes can reach without HTTPS redirection or login.
- Separate liveness from readiness: process health should not cause restart loops during
  a temporary database outage; readiness should reflect whether requests can be served.
- Add a startup probe or equivalent startup allowance for database initialization and migrations.
- Verify the probes with production mode enabled. Development mode was a diagnostic
  workaround in this audit, not the production fix.

## Priority 2: provide complete local and production deployment configurations

### Configure TLS and proxy trust together

The Kubernetes app enables `HTTP_BEHIND_PROXY=true` and expects TLS termination, but
[k8s/app-service.yaml](k8s/app-service.yaml) supplies only a LoadBalancer Service.
There is no ingress or TLS configuration in the provided manifests.

Recommendations:

- Provide documented local HTTP and production HTTPS configurations or overlays.
- For production, configure DNS, certificates, and a TLS-terminating ingress/reverse proxy.
- Have that proxy replace client-supplied forwarded headers and set the real client IP
  and request scheme. Restrict direct access to the backend that trusts those headers.
- Keep proxy trust off when clients connect directly to the application.
- Treat [docker-compose.yaml](docker-compose.yaml) as the demo configuration: production
  needs private credentials, seed-admin password rotation, HTTPS, and appropriate port exposure.
  Review the current externally bound PostgreSQL and Mailpit ports before exposing the host.

### Make the public application URL configurable

The SPA uses relative API URLs by default, which worked with the tested hostnames and IPs.
The current Compose file parameterizes `MAIL_APP_URL`, defaulting to `http://localhost:8081`.
That default is only appropriate for local users; remote recipients need the actual external
origin. V26 reset requests fail closed with 503 when the URL is missing/invalid (HTTPS in
production; no subpath, userinfo, query, or fragment) or outbound mail is disabled.

Recommendations:

- Set `MAIL_APP_URL` to the actual public HTTPS origin for a normal deployment; the Compose
  parameterization is implemented, but it does not choose the correct deployment value for you.
- Keep SPA and API on the same origin where possible. A split-origin deployment also needs
  review of the build-time API base, CORS, and the `connect-src 'self'` content-security policy.
- Configure real SMTP for password reset and email MFA. Kubernetes currently ships with
  mail disabled, so those email-dependent flows are unavailable.
- Test email links and browser operations through the intended production origin.

## Priority 3: make builds and releases reproducible

| Area | Current state | Recommendation |
| --- | --- | --- |
| Frontend and e2e npm packages | Committed lockfiles record exact resolved versions and integrity hashes. Manifests contain version ranges; Docker uses `npm ci --legacy-peer-deps`. | Use `npm ci` consistently, with the required peer-dependency option for `web/`; commit intentional lockfile updates. Version ranges alone do not make a locked `npm ci` install float. |
| Gradle dependencies/plugins | Direct versions are specified, but dependency locks and verification metadata are absent. | Add dependency locking for the relevant configurations and dependency verification metadata; validate them in CI. |
| Gradle wrapper | Distribution version is specified; `distributionSha256Sum` is absent. | Add the verified distribution checksum and retain wrapper validation in the build workflow. |
| Container images | Node, Temurin, PostgreSQL, Mailpit, and the Dockerfile frontend use mutable tags. | Pin the required images/frontend by digest and update them through a deliberate, tested process. |
| Application release image | Kubernetes uses `toadie-app:latest` with `IfNotPresent`. | Use an immutable release image digest. Publish to a registry accessible to the target cluster; document OrbStack's shared image store as a local convenience. |
| Local toolchains | `mise.toml` pins Java; Node/npm are not pinned. The build targets Java 21 while the image runs Java 25. | Pin Node/npm and choose/document the intended Java build/runtime combination; test that exact runtime in CI. The 21/25 combination passed these smoke checks but differs from local execution. |
| OS build packages | The Dockerfile installs Git through an unversioned `apk add`. | Account for OS package resolution in the reproducibility strategy, for example with a maintained builder image pinned by digest. |
| Source build context | The Dockerfile requires `COPY .git .git`. | Accept explicit build metadata or provide a fallback so source archives and CI contexts without `.git` can build. |

Pinning reduces dependency drift; it cannot eliminate differences in architecture, cluster
configuration, networking, storage, or external services. Keep security updates scheduled
and tested rather than freezing dependencies indefinitely.

## Acceptance checks after implementing the recommendations

- Build from a fresh checkout with clean dependency caches and the documented toolchain.
- Boot Compose against a fresh database and an existing migrated database.
- Deploy Kubernetes with production mode enabled and valid secrets; confirm repeated
  deployment does not overwrite secrets or change database credentials unexpectedly.
- Confirm readiness/liveness succeed without redirects and the deployment remains stable.
- Verify localhost, IP, and real DNS/TLS access: login, refresh, SPA deep-link reloads,
  static assets, representative catalog operations, and email links.
- Verify the deployed application digest matches the intended release and test each
  supported CPU architecture.

## Audit cleanup

No repository code or deployment manifests were changed during the audit. Compose containers
were stopped and removed while preserving the pre-existing database volume. The temporary
Kubernetes namespace, its disposable database volume, and the localhost port-forward were
removed; the test catalog was confirmed empty before cleanup.
