---
name: run-stack
description: How to run, package, and deploy the toadie stack — docker compose, local dev (gradle + vite), local Kubernetes (OrbStack), installDist packaging, and JVM memory tuning.
---

# Running, packaging, and deploying the toadie stack

## Packaging

Package the server for deployment with `./gradlew :server:installDist` (output under `server/build/install/server/`, launcher `bin/server`). **Do not use `:server:buildFatJar`** — the shadow plugin collapses the duplicate `META-INF/services/org.flywaydb.core.extensibility.Plugin` descriptors and the fat JAR NPEs at startup inside Flyway's plugin registry. `installDist` keeps each dependency JAR separate, so Flyway's `ServiceLoader` discovery works exactly as under `:server:run`.

## JVM footprint tuning

JVM footprint tuning is baked into the `application {}` block in `server/build.gradle.kts` via `applicationDefaultJvmArgs` = `-XX:+UseSerialGC -Xmx256m -XX:TieredStopAtLevel=1`, so it flows into both `bin/server` (→ Docker image) and `:server:run` (the Gradle `test` task is unaffected). Measured (in Lettuce, the same stack) on a 512 MiB Linux container: baseline G1 drifts **~345→410 MiB RSS** as it grows its heap, vs a steady, deterministic **~270 MiB** with these flags (**~25% lower and predictable**); startup is ~1.6 s either way, so the win is memory, not startup. SerialGC removes G1's per-heap overhead (~75 MiB); `-Xmx256m` caps a heap that holds no large caches (drop to `192m` to trim ~25 MiB more); C1-only (`TieredStopAtLevel=1`) trims code-cache + C2-compiler memory (~50 MiB) at the cost of peak CPU-bound throughput (irrelevant here — **remove that flag if the service ever runs hot**). Override per-deploy with `JAVA_OPTS`/`SERVER_OPTS` (the launcher appends both). Container ceilings match: `mem_limit: 512m` in `docker-compose.yaml`, `resources.limits.memory: 512Mi` (request `320Mi`) in `k8s/app-deployment.yaml`. This was evaluated (in Lettuce) instead of a GraalVM native-image migration, which the reflection/ServiceLoader-heavy stack (Ktor config modules, Flyway, Exposed, OTel, Logback, java-jwt) makes costly for little benefit on a long-running internal service.

## Running the full stack

The demo publishes the app, PostgreSQL, and Mailpit on **127.0.0.1 only**. This is a security boundary: development credentials and captured reset/MFA emails must not be exposed on a LAN. Production addressing belongs in a separately secured deployment. Recreate existing containers to apply changed bindings, keeping their database volume.

CI is separate: `.github/workflows/e2e.yml` owns a disposable `toadie-ci` project on a fresh hosted runner, waits for readiness, runs browser journeys, collects diagnostics, and removes only that project's volume even after setup failures. Never move it to a shared self-hosted daemon. Ordinary local E2E commands retain their preserve-data behavior.

Two ways to run, sharing the same `docker-compose.yaml`. All ports deliberately avoid Lettuce's (8080/5432/5173), so both stacks can run side by side on one machine:

- **One command (clone & run / demo):** `docker compose up --build` builds the SPA, builds the server, starts PostgreSQL, runs Flyway on boot, and serves everything at `http://localhost:8081` (Swagger at `/openapi`; sign in as `admin@toadie.local` / `changeme`). Tear down with `docker compose down` (add `-v` to drop the DB volume).
- **Local development (hot reload):** `docker compose up postgres` (host port **5433** → in-container 5432) + `./gradlew :server:run` + `cd web && npm run dev` (Vite on `:5174`, proxying `/api` → `:8081`).
- **Local Kubernetes (OrbStack):** the deployment is written for a TLS-terminating **ingress-nginx** front door at a real hostname (`k8s/templates/app-ingress.yaml`). ⚠ **Community ingress-nginx was RETIRED in March 2026** (https://kubernetes.io/blog/2026/01/29/ingress-nginx-statement/) — no further security patches; this manifest is the LOCAL-PROOF reference only, and any real production deployment MUST migrate to a maintained controller or the Gateway API (a behavior migration — re-verify the proxy contract: `HTTP_PROXY_HOPS`, the narrowed `XForwardedHeaders` list, the 308/HSTS/XFF-replacement assumptions, and `ProductionHttpTest`). Order: (1) `docker build -t toadie-app:latest .`; (2) install the controller once — `kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.15.1/deploy/static/provider/cloud/deploy.yaml` then `kubectl wait -n ingress-nginx --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=180s` (its admission webhook rejects Ingresses until the pod is Ready; a Lettuce deployment on the same cluster already has it — it is shared); read its LoadBalancer EXTERNAL-IP (`kubectl get svc -n ingress-nginx ingress-nginx-controller` — OrbStack hands every LB the node IP) and pick the host `toadie.<that-ip>.nip.io` (resolves without touching /etc/hosts); (3) `kubectl create namespace toadie`, then create the out-of-band config BEFORE applying — the Secret `toadie-secrets` (command in `k8s/templates/secret.yaml`), the TLS cert `toadie-tls` (self-signed recipe with the host as SAN in `k8s/templates/tls-secret.yaml`; cert-manager in real deployments), and the ConfigMap that carries the public URL: `kubectl -n toadie create configmap toadie-config --from-literal=MAIL_APP_URL=https://$HOST`; (4) apply the host-agnostic workloads with `kubectl apply -f k8s/` (deployment/service/postgres/PVC — **non-recursive on purpose so `k8s/templates/` is skipped**: `kubectl apply` over an existing Secret REPLACES its values, so re-applying the placeholder template would swap the real `JWT_SECRET` for the burned one and fail the app closed on its next restart — the 2026-09-05 audit reproduced exactly that with the old flat layout, plus a postgres volume initialised with the placeholder password), then the Ingress from its template: `sed "s#toadie.example.com#$HOST#g" k8s/templates/app-ingress.yaml | kubectl apply -f -`. **The Ingress lives in `k8s/templates/` on purpose** so the directory apply can never revert its substituted host, and the deployment carries NO baked hostname (`MAIL_APP_URL` comes from the `toadie-config` ConfigMap) — so `kubectl apply -f k8s/` is safe to re-run. The three per-deployment values that must agree: the Ingress host (twice), the `toadie-config` `MAIL_APP_URL`, and the cert SAN. Then browse `https://$HOST/` (self-signed → accept the warning; curl `-k`). The `k8s/` manifests are kompose-derived from `docker-compose.yaml` with these fixes baked in: the app image is the locally built `toadie-app:latest` with `imagePullPolicy: IfNotPresent` (OrbStack's cluster shares the Docker image store — no push needed), the postgres healthcheck is a proper argv-list exec probe doubling as the readiness gate, the app Service is `ClusterIP` behind the Ingress (was `LoadBalancer` until v1.22.0 — switching a LIVE Service can be rejected because of the allocated nodePort: `kubectl -n toadie delete svc app` first, then apply; `kubectl -n toadie port-forward svc/app 8081:8081` for pod-direct debugging), and the app exposes dedicated health endpoints (`plugins/Health.kt`): **`/healthz`** (liveness — process-alive only, never hits the DB, so a DB outage does not restart-storm the pod) and **`/readyz`** (readiness — a cheap DB round-trip over the namespaces dictionary, single-flight and bounded to 2 s (`READYZ_TIMEOUT_MS`), so an unreachable DB answers a 503 under the probe's `timeoutSeconds: 3` instead of hanging the probe until the OS gives up on the connect — Toadie's addition over Lettuce's copy, measured 2–7 s unbounded). A **`startupProbe` on `/healthz`** gives JVM boot + Flyway up to 150s before the liveness clock starts — on a fresh namespace Flyway also waits for PostgreSQL (`POSTGRES_CONNECT_RETRIES`) instead of crash-looping, so a first `kubectl apply -f k8s/` should show `RESTARTS 0` on the app pod (`.claude/docs/persistence.md` "Cold start: Flyway waits for PostgreSQL"); a wrong database password takes about a minute to fail. For local dev, `POSTGRES_CONNECT_RETRIES=0 ./gradlew :server:run` without postgres up fails fast instead of waiting. **Every probe sends `X-Forwarded-Proto: https`** — the header the ingress sets; without it production mode answers the plain-HTTP probe with a 301 to `https://<pod-ip>/` that the kubelet FOLLOWS (same host) into a refused `:443`, so probes never pass and the pod crash-loops (the audit's second reproduced blocker — the old probes hit `/` bare). After rebuilding the image, `kubectl -n toadie rollout restart deployment/app` picks it up. **What the ingress does for you (ingress-nginx defaults):** plain `http://$HOST/` answers a 308 to https at the edge (the app's own 301 fires pod-direct only), `X-Forwarded-For` is REPLACED with the real client IP (raise `HTTP_PROXY_HOPS` only for an APPENDING chain — see `application.yaml` `http.proxyHops`), and `Strict-Transport-Security` is overwritten by the controller (HSTS policy = its configmap). **Pod-direct access in production mode** (port-forward) gets the app's 301 to `https://<host>/` — for API checks send `-H 'X-Forwarded-Proto: https'` (trusted because of `HTTP_BEHIND_PROXY=true`). **Running the e2e suite against the cluster** needs a DEVELOPMENT-mode app with the pristine seed admin (a production boot rotates it whenever `ADMIN_INITIAL_PASSWORD` is set): `kubectl -n toadie set env deployment/app KTOR_DEVELOPMENT=true ADMIN_INITIAL_PASSWORD-` on a fresh database, keep `docker compose` DOWN (the suite reuses a stack it finds on :8081), then `cd e2e && E2E_BASE_URL=https://$HOST E2E_INSECURE_TLS=1 npm test` (the mail specs need Mailpit and skip without it). Back to production: re-apply the deployment from the manifest (restores the `ADMIN_INITIAL_PASSWORD` secretKeyRef) and `kubectl -n toadie rollout status deployment/app`. **Teardown:** `kubectl delete -f k8s/` removes the workloads *including the DB volume* (the `local-path` storageclass reclaim policy is Delete) — the Secrets, ConfigMap, Ingress and the controller stay (`kubectl -n toadie delete ingress app secret toadie-secrets toadie-tls configmap toadie-config`; leave the controller if Lettuce uses it); to stop the workloads but keep the data, `kubectl -n toadie scale deployment app postgres --replicas=0` instead. **Credentials — provisioning vs rotation:** the database password lives INSIDE postgres once the volume is initialised; a later mismatch between the Secret and the database is fixed by rotating in both places (`ALTER ROLE toadie PASSWORD` + a new Secret + an app restart), never by deleting the volume, which is the workspace. **Secrets:** the deployments consume the `toadie-secrets` Secret via `secretKeyRef` (`JWT_SECRET`, `POSTGRES_USER`/`POSTGRES_PASSWORD`, `ADMIN_INITIAL_PASSWORD`, optional `SMTP_USER`/`SMTP_PASSWORD`); never apply the templates (the JWT placeholder is on the burned list in `plugins/Security.kt`). The app deployment runs production mode (`KTOR_DEVELOPMENT=false`, `HTTP_BEHIND_PROXY=true`, `HTTP_PROXY_HOPS=1`, `MAIL_APP_URL` from the ConfigMap); for throwaway plain-HTTP browser testing without the ingress, flip `KTOR_DEVELOPMENT` to `"true"` in `k8s/app-deployment.yaml` (HTTPS redirect/HSTS off, seed credentials tolerated, exactly like the compose demo) and port-forward the ClusterIP service.

The 10 GiB PVC default needs one extra check on an existing deployment: before reapplying `k8s/`
to a claim created with the old 100 MiB request, follow `k8s/BACKUP-RESTORE.md`, confirm that its
StorageClass permits expansion, and take a verified backup. A failed expansion must be resolved
through the documented new-claim restore procedure, never by deleting the workspace claim.

The root `Dockerfile` is a 3-stage build: (1) `node` builds `web/dist` (`npm ci --legacy-peer-deps`), (2) `eclipse-temurin:21.0.12.1_1-jdk` runs `:server:installDist` (the committed `gradle.lockfile`s are copied in beside the build scripts — dependency locking is on, so a missing lockfile fails the image build), (3) `eclipse-temurin:21.0.12.1_1-jre` runtime (the same Temurin build as the build stage, `mise.toml` and `jvmToolchain(21)`, which may later run a same-update respin ahead; tag-and-digest pinned) bundles the install image **and** the built SPA, sets `WEB_STATIC_DIR=/app/web` and `KTOR_DEVELOPMENT=false`, and runs `bin/server`. The `app` Compose service points the `POSTGRES_*` env vars at the `postgres` service host (`postgres:5432` in-network) and waits on its healthcheck. `.dockerignore` keeps build outputs / `node_modules` out of the build context; `.git` is **included** on purpose — the SPA build stage reads the commit sha/timestamp from it for the version stamp (see "Build version stamp" in `web/CLAUDE.md`). Note the volume mount: postgres 18+ images keep data in a versioned subdir of `/var/lib/postgresql`, so the compose volume mounts that path (the pre-18 `/var/lib/postgresql/data` path makes the container unhealthy).

## GraphQL deployment opt-in

Compose enables the machine API for its local demo. Kubernetes leaves it disabled unless the
`toadie-config` ConfigMap contains `INTEGRATION_ENABLED: "true"`; the deployment reads this
optional key. Patch that key without replacing `MAIL_APP_URL`, then restart the app (commands
in `.claude/docs/integration-api.md`). Reapplying `k8s/` preserves the opt-in. REST and GraphQL
share PostgreSQL within each deployment; separate Compose and Kubernetes volumes do not share
sample data automatically.

## Enable Okta SSO

SSO is opt-in (`SSO_ENABLED`, default `false`) and adds a "Sign in with Okta" button above the password
form; password login keeps working. The server contract, the boot refusals and the accepted risks live in
`.claude/docs/security.md` "SSO login (OIDC)" - read it before changing anything below.

**1. Okta admin console.** Applications -> Applications -> Create App Integration -> **OIDC - OpenID
Connect** -> **Web Application**. Then:

- Grant type: **Authorization Code** (nothing else needed).
- Under Client Authentication / proof of possession, enable **Require PKCE** (the server always sends S256).
- **Sign-in redirect URI**: `https://<host>/api/v1/login/sso/callback` - exactly that path, no trailing
  slash, the same origin as `MAIL_APP_URL`. Production requires https; plain http is accepted only with
  `KTOR_DEVELOPMENT=true`.
- **Sign-out redirect URI**: `https://<host>/login` (or the value of `SSO_POST_LOGOUT_REDIRECT_URI` when you
  set it). Toadie sends the browser to Okta's end-session endpoint on sign-out; an unregistered redirect URI
  makes Okta show an error page there (Toadie's own session is already gone by then).
- **Assignments** decide who can sign in at all. Toadie creates an account on first sign-in for ANY user the
  application admits, so assign deliberately and ALSO set `SSO_ALLOWED_EMAIL_DOMAINS` (comma list; blank =
  any domain, which the app logs as a boot WARN).
- Copy the client id and client secret from the application's General tab.

**2. Issuer.** `https://<org>.okta.com/oauth2/default` for the default custom authorization server, or
`https://<org>.okta.com` for the org authorization server. It must equal the `issuer` in
`<issuer>/.well-known/openid-configuration` byte for byte (no trailing slash).

**3. Configuration.** Required once enabled: `SSO_ISSUER`, `SSO_CLIENT_ID`, `SSO_CLIENT_SECRET`,
`SSO_REDIRECT_URI` (or a correct `MAIL_APP_URL`); optional `SSO_LABEL` (default `Okta`), `SSO_SCOPES`
(default `openid email profile`), `SSO_ALLOWED_EMAIL_DOMAINS`, `SSO_POST_LOGOUT_REDIRECT_URI` (where Okta
returns the browser after sign-out; default `<origin of SSO_REDIRECT_URI>/login`). An incomplete set makes the app refuse to
start - read the boot log, do not work around it.

- **Compose:** add the variables to the `app` service's `environment` (or an untracked override file).
  Never commit the client secret.
- **Kubernetes:** the Deployment already reads the keys, all optional. The non-secret ones come from the
  `toadie-config` ConfigMap - patch without replacing `MAIL_APP_URL`:

  ```bash
  kubectl -n toadie patch configmap toadie-config --type merge -p \
    '{"data":{"SSO_ENABLED":"true","SSO_ISSUER":"https://<org>.okta.com/oauth2/default","SSO_CLIENT_ID":"<client-id>","SSO_REDIRECT_URI":"https://<host>/api/v1/login/sso/callback","SSO_ALLOWED_EMAIL_DOMAINS":"example.com"}}'
  kubectl -n toadie patch secret toadie-secrets --type merge -p \
    "{\"stringData\":{\"SSO_CLIENT_SECRET\":\"<client-secret>\"}}"
  kubectl -n toadie rollout restart deployment/app
  ```

**4. Verify.** `curl -s https://<host>/api/v1/login/options` answers `{"sso":{"label":"Okta"}}` when it is on
and `{"sso":null}` when it is off. Then sign in through the button with a test user assigned to the
application. Administrator accounts and accounts with email MFA are never linked or taken over by an
Okta email - they keep password login. Keep at least one ADMIN with a password so admin access survives an Okta outage
(`.claude/docs/security.md` "SSO login (OIDC)", accepted risks).

**Sign-out check (manual, against the real tenant - the stub cannot prove Okta's own behaviour).** Signing out
of Toadie also ends the Okta session (RP-initiated logout; `.claude/docs/security.md` "SSO login (OIDC)"). With
the sign-out redirect URI registered, check all four:
1. Sign in through Okta, then **Sign out** in the account menu: you land on Toadie's `/login` with the
   "You've been signed out" banner, and the next **Sign in with Okta** PROMPTS for credentials (no silent
   sign-in).
2. The same after the ID token expired: stay signed in for more than an hour (Okta ID tokens live one hour),
   then sign out - Okta must still honour the expired `id_token_hint` and end the session.
3. Signing out of Toadie when the Okta session already ended elsewhere (sign out of Okta in another tab, or
   let it time out) lands cleanly on `/login`, with no Okta error page.
4. If a DIFFERENT Okta user is signed in in that browser, note what Okta shows at the end-session step
   (a confirmation page, or it ignores the hint) - the sign-out must still end on `/login` or at a page
   from which "Sign in with Okta" works.

**Unlinking an identity.** In the Users list, the account's Operations menu has **Unlink SSO identity**
(not on your own row; a passwordless account gets an extra warning). It clears the stored Okta subject and
ends the account's sessions. The three cases:
- *New Okta subject, same email* (the Okta user was recreated): unlink; the next Okta sign-in links the
  account again by email.
- *Detach for good*: FIRST enable email MFA on the account (MFA accounts are never linked by an Okta email)
  or unassign the person from the Okta application, THEN unlink - otherwise the next sign-in re-links it.
  A passwordless administrator or MFA-enabled account cannot be unlinked until it has a password (`409`:
  such accounts never re-link by email, so the unlink would lock them out); set one first.
- *Re-admit an identity blocked by deleting its user*: find the refused attempt's `login.sso_failure` event
  (`reason=account_deleted`, it carries `userId`) in the server log, then
  `curl -X DELETE -H "Authorization: Bearer <admin access token>" https://<host>/api/v1/users/<userId>/sso`
  (204). There is no UI for this; the next Okta sign-in creates a fresh account.

**Local end-to-end proof without Okta.** `e2e/compose.sso.yaml` is an overlay (e2e/CI only, never the
long-lived stack) that adds a stub OIDC provider (`e2e/stub-idp/server.mjs`, port 18090) and turns SSO on.
Run it from the repo root, with the base file first, on a disposable project:

```bash
docker compose -p toadie-check -f docker-compose.yaml -f e2e/compose.sso.yaml up -d --build
cd e2e && npx playwright test tests/sso.spec.ts
docker compose -p toadie-check -f docker-compose.yaml -f e2e/compose.sso.yaml down --volumes --remove-orphans
```

(`sso.spec.ts` skips itself when SSO is off and fails under `CI`.) The base file's fixed container names
and ports mean a second project cannot run beside the long-lived `toadie` stack unchanged: add an extra
untracked override file that renames the containers and shifts the published ports (and set
`STUB_IDP_PORT` / `SSO_REDIRECT_URI` / `SSO_POST_LOGOUT_REDIRECT_URI` (the stub accepts only this registered
sign-out redirect, e.g. `http://localhost:18081/login`) plus `E2E_BASE_URL` to match), and never `down -v` the default project.

## Reproducibility — what is pinned, and what deliberately is not

Pinned: direct dependency versions (`gradle/libs.versions.toml`, `web/package.json` + `package-lock.json` installed with `npm ci`), the transitive Gradle graph (dependency locking, `gradle.lockfile` and `buildscript-gradle.lockfile` in each of `core/` and `server/`, plus the root `gradle.lockfile`, `settings-gradle.lockfile` and `buildscript-gradle.lockfile`), the SHA-256 of every resolved Gradle artifact and POM (`gradle/verification-metadata.xml`, enforced automatically by Gradle whenever the file exists — CI runs `--dependency-verification strict`, and the Docker build's `installDist` is checked too) — both regenerated together from an empty `GRADLE_USER_HOME` after a dependency change, see `.claude/docs/dependency-reproducibility.md`; HIGH/CRITICAL advisories in the locks fail the CI Gradle advisory scan, the Gradle distribution checksum (`distributionSha256Sum` in `gradle-wrapper.properties`), the toolchains (`mise.toml`: Temurin 21 + Node 26, matching the Dockerfile and the e2e workflow), the Dockerfile's three base images by tag AND multi-arch digest (`node:26-alpine@sha256:…`, `eclipse-temurin:21.0.12.1_1-jdk/-jre@sha256:…`, the JDK on mise/CI's exact build, the runtime on the same update; Dependabot bumps each), every remote GitHub Action in the three workflows by full commit SHA with its release tag as a trailing comment (`infrastructure.test.js` pins the shape; Dependabot's github-actions ecosystem bumps both), and compose's `postgres:18-alpine` / `axllent/mailpit` (its minor tracked by Dependabot, so this line names no version), the SSO overlay's `node:26-alpine` and the k8s PostgreSQL by tag AND multi-arch digest (2.32.4; Dependabot's `docker-compose` ecosystem over `/` and `/e2e`, its `docker` ecosystem over `/k8s`; PostgreSQL majors ignored — a major is a `pg_upgrade`, not a dependency PR; `infrastructure.test.js` pins the shape and one PostgreSQL tag across compose and k8s). Two `postgres:18-alpine` references stay tag-only on purpose, both throwaway test databases: the Testcontainers image in `PostgresTestSupport` and the backup drill's compose document in `e2e/scripts/backup-restore/drill.mjs`. The Dockerfile cross-builds (amd64+arm64) and the CI "Container image" job scans its OS layer; the rules and hand-maintained pins live in `.claude/docs/dependency-reproducibility.md`.

Current policy: Dependabot proposes weekly updates through six update entries over five ecosystems, while CI and
dependency locks verify each merged change. The Dockerfile, compose and k8s images are digest-pinned (see above);
pinning the Docker build's `apk add git` package is not planned.
This is separate from the **application release image**: production registry publishing and
deployment by immutable digest remain open (parked) in [BACKLOG.md](../../../BACKLOG.md). The
OrbStack shared image store and `toadie-app:latest` are only the local proof path.

`.git`-less source-archive builds are not planned. `COPY .git .git` feeds the SPA version stamp today; if archive builds become a
supported target, `web/vite.config.ts` already accepts `GIT_SHA` and `GIT_COMMIT_TIME` from
the environment, and an explicit build-argument path can be designed then.
