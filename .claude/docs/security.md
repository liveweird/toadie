### Security posture: development vs production mode

**Local-demo network boundary.** Compose publishes the app (:8081), PostgreSQL (:5433), and Mailpit (:8026) on `127.0.0.1` only. Do not widen those bindings casually: the demo has burned credentials, HTTP, and an unauthenticated mailbox containing reset/MFA emails. Remote deployment needs a separate production configuration with private secrets, TLS, and private database/mail services. Existing containers retain old bindings until recreated; editing YAML does not secure a running container retroactively.

`ktor.development` is env-overridable (`$KTOR_DEVELOPMENT:true`): local `:server:run`/tests default to development mode; **the Docker image ships `KTOR_DEVELOPMENT=false`** (production mode), and `docker-compose.yaml` explicitly sets it back to `true` because it is the local plain-HTTP demo. Production mode activates HSTS + HTTPS redirect and three **fail-closed startup checks**:

- **JWT secret** (`plugins/Security.kt`): blank, the placeholder `"secret"`, the repo-committed compose demo key (`dev-only-00366d…`), or the `k8s/templates/secret.yaml` template placeholder (`CHANGE-ME-openssl-rand-hex-32`) → warn in development, **refuse to start** in production. Set a strong private `JWT_SECRET`.
- **Seed passwords** (`infra/db/Bootstrap.kt`, module `configureBootstrap`, runs after `configureDatabase`): in production mode, if any active account still carries the well-known `changeme` bcrypt hash the app **refuses to start**. Setting `ADMIN_INITIAL_PASSWORD` (config `bootstrap.adminInitialPassword`) rotates the V3 seed admin's password at startup — idempotent: only applied while the admin still has the seed hash, so an admin-chosen password is never overwritten. Covered by `BootstrapTest`; seed-mutating tests restore state via `TestSeedState.restoreSeedAccounts()` (`TestEnvironment.kt`).

- **Mail transport** (`infra/mail/Mail.kt`): `mail.transport=log` writes outbound email (including reset links and MFA codes) into the application log — permitted with a warning in development, **refuse to start** in production; `smtp` with a blank host refuses to start in **any** mode. See "Outbound email" below.

(Lettuce has one more fail-closed check — the data-encryption key; it arrives with field encryption at rest, see "Not yet ported" below.)

**JWT/session model** (`auth/Tokens.kt`, `auth/AuthSessionService.kt`, `plugins/Security.kt`): every access/refresh pair carries a random `jti` and a shared `sid` identifying one login's database-backed family (`auth_sessions`, V25). The verifier requires signature/issuer/audience/expiry, access `typ`, a non-revoked `jti`, and a LIVE family belonging to the token's non-wrapping UInt user id. Every request checks the family against the active user's monotonic `auth_version` in PostgreSQL — acceptance is never cached. Password changes (including bootstrap/reset) and email/role changes advance that version atomically; deletion fails the active-user check. Same-clock-tick changes invalidate old access AND refresh tokens, and restoring a role never resurrects an old session. Logout deletes the entire family, including every superseded refresh generation, without requiring a body; unrelated logins/devices stay alive. Renewal updates an existing row only, cannot resurrect logout, and never shortens the family's expiry. The older per-jti blocklist/cache remains an additional defense, not the account/session revocation boundary. Already-authorized in-flight requests may finish. **Upgrade:** tokens without `sid` are rejected; deploying V25 requires everyone to sign in again. Session revocation works across instances; MFA challenges and throttles still require the documented single-replica posture.

**SPA session boundaries.** Graph controllers and HTTP refresh work belong to one login
family. Decode `sid` only to partition local work, never as proof of authentication; the
server remains the verifier. Refresh within a family preserves pending graph edits, while
sign-out, a different login (including the same user id), and cross-tab session replacement
invalidate them. A delayed 401 or refresh response from an old session must neither restore
its credentials, replay its request, nor clear a newer login. Keep refresh single-flight
within its owning session, and preserve transient-failure behavior. Already-dispatched,
authorized server requests may still finish; client cancellation is not transaction rollback.

**Token storage & response headers (the XSS posture).** The SPA keeps BOTH tokens in `localStorage` under `toadie.auth.*` (`web/src/api/session.ts` — access, refresh, roles, userId, disabledFeatures; no password is ever persisted; everything else in localStorage is view state). A deliberate trade-off, documented rather than hidden: any script running on the origin can read the REFRESH token, i.e. a renewable session unbound to device/IP revoked by session logout or a credential/identity change — so the real control is keeping foreign script out. That control is `plugins/SecurityHeaders.kt`, installed unconditionally and pinned by `ServerTest`: a strict CSP (`script-src 'self'`, `object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`. Consequences: never weaken `script-src` (no CDN scripts, no `unsafe-inline` — Vite bundles everything same-origin, fonts included), and the SPA must stay free of `dangerouslySetInnerHTML`/`eval` sinks (currently zero). Moving tokens to httpOnly cookies would trade this for CSRF machinery — revisit only with that full picture.

**Login timing equalizer** (`auth/AuthRoutes.kt` + `TIMING_EQUALIZER_HASH` in `auth/Passwords.kt`): an unknown email pays a full, discarded bcrypt verify against a fixed cost-12 hash so its 401 takes as long as a wrong password's — without it, response timing is an account-enumeration oracle (the reset path equalizes via async processing; login equalizes in-line).

**Per-account login lockout** (`auth/LoginThrottle.kt`, wired in `configureAuthRoutes`): after `security.lockout.threshold` (default 5, `$LOGIN_LOCKOUT_THRESHOLD`) consecutive failures for one submitted email, `/login` answers `429` for `security.lockout.durationSeconds` (default 900, `$LOGIN_LOCKOUT_DURATION_SECONDS`) — even with the correct password, and regardless of whether the account exists (no enumeration signal). A success resets the counter. In-memory and per-instance by design (single-replica deployment; a restart only resets the throttle). Complements the per-IP `RateLimit` bucket, which rotating hosts sidestep. The lockout 429 is **thrown** (`TooManyRequestsException`), never `respondProblem`ed directly — StatusPages' generic 429 status handler rewrites any non-StatusPages 429, so only the exception path keeps the specific detail. Tests: `LoginThrottleTest` (unit, injected clock) + `LoginLockoutTest` (route). The SPA maps the 429 to `auth.accountLocked`.

**Self-service password reset** (`auth/PasswordResetRoutes.kt`, V26): public
`POST /api/v1/password-reset` accepts `{email}` and returns uniform `202`; active-account lookup,
token issuance, and email delivery happen asynchronously, with no password/session mutation.
Unknown and deleted accounts send nothing. Each link has a cryptographically random 256-bit,
43-character base64url token; only its SHA-256 digest, user id, captured credential epoch, and
expiry are persisted. TTL is `PASSWORD_RESET_TOKEN_TTL_SECONDS` (default 900, range 1–3600).
Multiple requested links may coexist; requesting another must not revoke a usable link.
Failed delivery revokes the new grant only. Email uses the recipient's stored EN/PL language.

The link is `MAIL_APP_URL/reset-password/confirm#token=…`. Only configured origins are accepted:
HTTPS in production, HTTP also allowed in development, no path prefix/userinfo/query/fragment.
Missing/invalid origin or disabled mail yields `503`. Never trust the request Host header.
The fragment never reaches HTTP access logs; the SPA removes it from history and holds the grant
only in component memory. Loading/opening the page does not validate or consume a grant.

Public `POST /api/v1/password-reset/confirm` takes `{token,password}`, checks the grant before
password validation/bcrypt, then locks the active user and rechecks expiry/epoch in PostgreSQL.
Grant consumption, password/hash/epoch update, and sibling-grant deletion are ONE transaction
(`PasswordResetService.complete` + `users.updatePasswordInTransaction`). Competing confirmations
have one winner across instances. Password/email/role changes and deletion invalidate stale grants.
Malformed/unknown/expired/used/epoch-stale grants all return `401`; invalid passwords return
`400` without consumption (minimum 10 characters, maximum 71 UTF-8 bytes). Success is `204`
with no auto-login: all existing sessions and pending MFA challenges become invalid, while MFA
remains enabled for normal sign-in. A best-effort localized notification contains no password
or reset token; delivery failure cannot undo success. If the response is lost, try signing in
with the chosen password or request another link, never treat retry's `401` as proof of failure.

Request throttling remains per email (default 60 seconds, `PASSWORD_RESET_MIN_INTERVAL_SECONDS`)
plus per IP (5/min production, 100/min development, `PASSWORD_RESET_RATE_LIMIT_PER_MINUTE`);
confirmation has a separate 10/min per-IP bucket in every mode. Throttles/MFA are still
instance-local: do not scale replicas on the strength of persisted reset grants alone.
The feature's Setup interceptor sets `Cache-Control: no-store`, including pre-handler 429s. Audit events record requests, link delivery/failure,
confirmation/rejection, and notification failure; never raw tokens/passwords or mail-provider
exception messages (which can contain message bodies). Development log mail deliberately reveals
link credentials and MUST remain inaccessible outside the local demo.

Tests: `PasswordResetServiceTest` (injected-clock expiry, digest storage, concurrency, epoch/CAS),
`PasswordResetTest`/`PasswordResetConfirmationTest` (routes, delivery/notification failure,
revocation, MFA, throttles), `LocalizedEmailTest`, frontend confirmation tests, and the Mailpit
browser journey. The design follows the [OWASP reset guidance](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).
Lettuce was inspected but still has the replaced emailed-password design; retain the existing
mail/localization/throttle primitives, not that weakness.

**Per-IP login bucket** (`security.rateLimit.loginPerMinute`, `$LOGIN_RATE_LIMIT_PER_MINUTE`, registered in `configureAuthRoutes`): blank **follows the mode** — 10/min in production, 1000/min in development — and an explicit number pins it in either mode (the `http.exposeOpenApi` idiom). Development is lifted because the e2e suite drives its logins from one host and would otherwise sleep out the bucket; **the per-account lockout above is the actual brute-force defence and is identical in both modes**. The sibling `refresh` bucket defaults to 30/min in both modes and is pinnable via `security.rateLimit.refreshPerMinute` (`$REFRESH_RATE_LIMIT_PER_MINUTE`). `RateLimitResponseTest` and `LoginTest` pin the value to `10` explicitly, since tests run in development mode.

**Email MFA (opt-in)** (`auth/MfaChallenges.kt` + `auth/MfaEmail.kt`, wired in `configureAuthRoutes`): a per-user second factor behind the `MFA` feature flag — **the one inverted-default flag**: `V13` seeds every existing user as MFA-disabled and `UserService.create` inserts the disabled row for every new user (admin create and test seeds both funnel through it), so MFA is OFF until an admin removes the row via the features PUT (the per-user editor or the `/feature-flags` bulk screen; mind the wholesale-replace semantics — a PUT whose disabled set omits `MFA` ENABLES it, including the "empty array re-enables everything" idiom). With MFA enabled, `POST /login` with correct credentials answers `200 MfaChallengeResponse {mfaRequired, challengeId, expiresAt}` instead of tokens (the branch runs AFTER `verifyPassword`, so nothing about enumeration or lockout changes; `login.success` is deliberately NOT emitted); a 6-digit code (`generateMfaCode`, `auth/Passwords.kt`) is held in the in-memory challenge store (config `security.mfa.codeTtlSeconds` default 300 `$MFA_CODE_TTL_SECONDS`, `security.mfa.maxAttempts` default 5 `$MFA_MAX_ATTEMPTS`; per-instance like the throttles — a restart just means signing in again) and emailed in the user's stored language (V18, EN fallback); a delivery failure logs only
the exception CLASS NAME (`login.mfa_send_failed`'s `errorType` field and the paired log line —
the reset path's rule, `.claude/docs/observability.md`), never the exception object or its
message, since the message body carries the 6-digit code. `POST /api/v1/login/mfa` `{challengeId, code}` (own per-IP bucket `"mfa"`, hardcoded 10/min in every mode) exchanges it for the ordinary `LoginResponse` — one fresh `userService.read` first, checking the challenge's captured `auth_version` (password or identity/role changes and deletion invalidate the pending challenge with the same uniform 401); session registration checks that epoch again under a row lock; every failure mode (unknown/expired/wrong code/attempt cap) is a **uniform 401**, reasons live only in the `login.mfa_failure` audit event; challenges are single-use. **Mail-less deployments fail closed**: on `MAIL_TRANSPORT=disabled` an MFA-enabled login answers `503` (`login.mfa_unavailable`) — don't enable MFA flags on the k8s default `disabled` transport; the features PUT keeps working, so an admin can always flip a flag back. SPA: the Login card's second step (`PinInput`, `auth.mfa*` keys). Tests: `MfaChallengesTest` (pure store) + `MfaLoginTest` (route matrix); e2e `mfa.spec.ts` drives the emailed code through Mailpit.

**Swagger/OpenAPI gate** (`plugins/Http.kt`): `/openapi` (UI + spec) is served only in development mode, or when `http.exposeOpenApi` (`$HTTP_EXPOSE_OPENAPI`) is explicitly `"true"` — blank follows the mode, `"false"` hides it even in dev. Bearer auth cannot protect a browser-loaded UI (page loads carry no `Authorization` header), hence a gate rather than `authenticate {}`.

**Accounts are admin-managed.** There is no self-signup: administrators create accounts at `/users/new`. The initial password is generated CLIENT-side (`web/src/utils/password.ts`, 96 bits) and shown to the admin exactly once in the reveal modal — the server stores only the bcrypt hash and no response ever carries plaintext; users rotate it via the Change password page (the existing `PUT /users/{id}/password` rules). Deletion is the soft-delete convention plus two guards: no self-delete (403) and the last active administrator can be neither deleted nor demoted (409).

**Canonical email identity.** Emails are folded to `trim().lowercase()` (`canonicalEmail` in `users/Validation.kt`) at EVERY entry point — login and `findWithIdByEmail` itself (defense-in-depth) today, and every future create/update/import — so one mailbox is one account and a padded/case-variant login matches (and shares one lockout bucket). The V1 partial unique index stays byte-wise (all writes are canonical).

**Request body ceiling.** `RequestBodyLimit` (installed in `plugins/Http.kt`, `MAX_REQUEST_BODY_BYTES` = 10 MiB) rejects oversized bodies with a 413 problem (mapped in `plugins/ErrorHandling.kt`) before any receive/validation work — a memory-DoS backstop, not a business rule; field-level `maxLength` validation rejects oversized values far earlier on ordinary payloads. Declared in the spec on the body-heavy catalog operations (create, replace, import, sync) and covered by `PayloadValidationTest`.

**Request payload validation (convention — API-SEC-003/API-ERR-005).** Mutating routes validate payloads up-front and throw `BadRequestException` (→ `400` + `ProblemDetail`) instead of letting oversized/blank values die in the DB as `500`s. One cross-cutting example: password ≥ `MIN_PASSWORD_LENGTH` (10) chars AND ≤ 71 UTF-8 bytes (`validatePassword` in `users/UserRoutes.kt` + `MAX_PASSWORD_BYTES` in `auth/Passwords.kt` — **the bcrypt ceiling**: longer input makes bcrypt throw, and the 500-vs-401 split would be an account-enumeration oracle, so login's `verifyPassword` guards it too). Declare limits as `maxLength` in the OpenAPI spec. A validation failure that must name EVERY violated rule (the catalog strict save, the entity save) throws a subclass of `infra/validation/InvalidPayloadException` — the one findings-bearing 400 shape, RFC 7807 plus a `findings` array in a feature-typed problem DTO (`CatalogFileInvalidProblem`, `EntityInvalidProblem`) — so `plugins/ErrorHandling.kt` renders both through one handler and imports no feature package. Keep new validators feature-local and enforce them **after** the authz guard (403 wins over 400). Covered by `PayloadValidationTest`.

**YAML comparison resource bounds.** A valid API definition can contain enough short lines
to make an unrestricted LCS matrix exhaust the browser's memory. The SPA gates detailed
diffs by combined characters, combined lines, and full matrix cells before allocating it
(`web/src/utils/yamlDiff.ts`; exact budgets in `web/CLAUDE.md`). Oversized comparisons show
both complete canonical documents as plain React text in two scrollable blocks, without
per-line DOM creation. This is a presentation fallback, not a validation bypass or an
upload limit: parsing/rendering the full document still costs work proportional to its
size, and the normal validation and explicit overwrite confirmation remain in force.

**Outbound URL fetch (SSRF posture).** `POST /api/v1/files/fetch` (`catalog/UrlFetch.kt`)
serves the import page and repo-sync modal. Guards, in order: absolute `https` only, no
userinfo, non-blank host, URL ≤ 2048 chars (`parseFetchUrl`); then EVERY resolved address
must be public (`resolveFetchTarget` / `requirePublicAddresses`). Loopback, site-local, link-local, any-local, multicast,
IPv6 ULA `fc00::/7`, CGNAT `100.64.0.0/10`, `192.0.0.0/24`, benchmarking `198.18.0.0/15`,
and NAT64 `64:ff9b::/96` embedding a non-public IPv4 are refused, as are unresolvable hosts.
Guard rejections retain the uniform **400** `FETCH_URL_INVALID_DETAIL`. Audits remain
`catalog_file.fetch_blocked` / `catalog_file.fetched`, with scheme/host ONLY, never the
full URL or upstream exception text (either may contain query-string credentials).

**Validated destinations, including connection time.** The OkHttp transport resolves the
canonical URL hostname once and rejects an empty result or any non-public address. It copies
the approved address bytes into a per-fetch snapshot; its `Dns` hook supplies only that
snapshot and rejects an unexpected hostname. The request keeps the logical URL hostname for
TLS certificate verification, SNI, and Host. Numeric literals must pass the same address
checks; they cannot introduce a different connection-time destination. Each fetch owns a
private zero-idle connection pool, evicted on completion, so pooling/coalescing cannot reuse
another fetch's authorization. Both redirect settings are disabled, and direct connections
(`Proxy.NO_PROXY`) prevent a system proxy from resolving a different destination. The
physical socket is also created with `Socket(Proxy.NO_PROXY)`: disabling only OkHttp's
proxy setting still lets Java's default socket consult a SOCKS proxy. Its socket factory
supports only unconnected socket creation; unused connected overloads fail closed. No global
resolver or permissive TLS verifier is installed. The transport is the already-present,
lockfile-pinned OkHttp version, now declared as a direct dependency.

**One deadline, including the body.** The fetch's 10-second budget covers queueing,
validation/DNS, connection/TLS/headers, and completion of the entire body; it is not a fresh
budget per stage or chunk. Synchronous transport and body reads run in the same bounded
worker task as DNS. Reads enforce **1,048,576 bytes**, accept exactly that limit, and abort
on overflow. `Accept-Encoding: identity` disables transparent decompression, keeping the
limit on the upstream representation. Non-200 responses are rejected before follow-up or
body draining; failure and cancellation close the exchange. Own-deadline expiry, exhausted
worker capacity, connection/body I/O failures, redirects, non-200 status, and oversize remain
safe `BadGatewayException` → **502**. Caller cancellation, including an enclosing timeout,
propagates unchanged. Application retries and automatic connection retries are disabled;
`503 Retry-After: 0` must not trigger a hidden status retry. No partial success is returned.

**Native DNS limitation and bounded containment.** The entire exchange uses a shared daemon
pool capped at four workers and sixteen queued tasks, with rejection mapped to 502.
Cancellation interrupts the task, removes queued work, cancels any attached HTTP call, and
releases the waiting coroutine. A native resolver may ignore interruption and occupy one of
those four workers until the OS returns; this can exhaust fetch availability, but cannot
create an unbounded population of stranded resolver workers. Cancelled resolution must never
initiate a later HTTP request. Address pinning closes the previous resolve-check/connect
rebinding gap and removes the JDK transport's second DNS lookup; it does not claim Java can
forcibly terminate native DNS. Do not replace the pinned resolver with `Dns.SYSTEM`, share
connection pools between fetches, or move body/connection work onto an unbounded executor.

**Compatibility.** Hosts requiring a system HTTP or SOCKS proxy now fail safely because
this path requires direct outbound HTTPS. Normal TLS trust and hostname checks remain in force. Stored
`source_url` is still served IN FULL to all authenticated users in this shared workspace, so
do not embed secrets in source references. Safe 502 details retain the upstream HTTP status
for diagnosis, only after the public-host check. Internal target-resolution, timeout,
executor, and client-customization seams support local fixtures and a test-only trusted CA;
production uses the full default guard chain and ten-second budget.

**CORS is off by default** (`plugins/Http.kt`): the plugin is installed only when `http.corsHosts` (`$CORS_ALLOWED_HOSTS`, comma-separated hosts) is non-empty. Production is single-origin (Ktor serves the SPA) and dev goes through the Vite proxy, so no cross-origin caller exists by default — no `anyHost()`. **Reverse proxy**: set `HTTP_BEHIND_PROXY=true` (config `http.behindProxy`) when TLS terminates at an ingress/proxy — it installs `XForwardedHeaders` so rate-limit buckets key on the real client IP and the HTTPS redirect sees the real scheme and host; the proxy must set (and overwrite client-supplied) `X-Forwarded-For`/`X-Forwarded-Proto`/`X-Forwarded-Host` — `k8s/templates/app-ingress.yaml` is the reference contract. Off by default because honoring those headers from direct clients lets them spoof both. Two hardenings ported from Lettuce (v1.22.0): the trusted header list is NARROWED to those canonical three (Ktor's default also honours `X-Forwarded-Server`/`-Protocol`/`-SSL`/`Front-End-Https`, which a proxy that sets only the canonical ones passes through from the client untouched — `ProductionHttpTest` pins that `X-Forwarded-Server` cannot name the redirect target), and `X-Forwarded-For` is read from the END of the list (`useLastProxy()`; `HTTP_PROXY_HOPS`, default 1, = trusted proxies that APPEND — `ForwardedHeadersTest` pins the bucket keying). The security headers are appended in the `Setup` pipeline phase so the production 301 carries them too (appending after `HttpsRedirect` commits the response throws on Netty). The Kubernetes probes send `X-Forwarded-Proto: https` for the same reason — without it production mode redirects the plain-HTTP probe and the pod crash-loops (see `k8s/app-deployment.yaml`).

CSRF install is gated behind `security.csrf.enabled` (default **`false`** in `application.yaml`, env-overridable via `SECURITY_CSRF_ENABLED`); the configured `originMatchesHost()` + `allowOrigin("http://localhost:8081")` + `checkHeader("X-CSRF-Token")` combo is unsatisfiable from both the Ktor test client and the dev SPA on `:5174`, and CSRF protection is anyway moot for this app's bearer-JWT auth model — browsers do not auto-attach `Authorization` headers, so cross-site forms cannot forge an authenticated request. Re-enable only if you move to cookie-based session auth and fix the allow-list accordingly.

**Default admin.** Migration `V3__seed_admin.sql` inserts a single bootstrap administrator on first boot: `admin@toadie.local` / `changeme` (role `ADMIN`), idempotent via `ON CONFLICT DO NOTHING`. The migration is kept **unchanged** (dev + e2e depend on it; checksums must not change) — production neutralizes it at startup via the bootstrap above. There are no demo seed users (Lettuce's V9 demo org was not ported). **Kubernetes secrets** live in the `toadie-secrets` Secret in the `toadie` namespace (`k8s/templates/secret.yaml` is a placeholder template kept OUT of the applied directory — create the real one out-of-band with the `kubectl create secret generic` command in its header; the app deployment consumes it via `secretKeyRef`).

**Outbound email** (`infra/mail/`, ported from Lettuce): `configureMail` (registered at the top of the infrastructure group, before Flyway) publishes `MailerKey` holding a `Mailer` or null. `mail.transport` (`$MAIL_TRANSPORT`) selects `log` (dev default — the full message, **including reset links and MFA codes**, goes to the `ch.nokillswit.mail` logger; **production mode refuses to start on it**), `smtp` (Jakarta/Angus Mail over `mail.smtp.*` / `$SMTP_HOST` etc.; a blank host refuses startup in any mode), or `disabled` (the Docker image default via `ENV MAIL_TRANSPORT=disabled` — email features answer 503 through `respondMailUnavailable`). The compose demo wires `smtp` → the bundled Mailpit (`http://localhost:8026` — 8025 is Lettuce's); k8s ships `disabled` with `SMTP_USER`/`SMTP_PASSWORD` read (optional) from `toadie-secrets`. Consumers: self-service password reset and email MFA. `LocalizedText` lives beside the transports; feature-owned `PasswordResetEmail` and `MfaEmail` compose recipient-language content. Tests: `MailTransportTest` (the transport matrix + both refusals + LogMailer delivery via the `ch.nokillswit.mail` LogCapture); production-mode boot tests must override `mail.transport` with `"disabled"`, since the dev-default `log` transport is refused in production.

### Computed-property evaluation (jq)

Admin-authored `calculationProperties` expressions (`.claude/docs/port-data-model.md` "Computed
properties", phase 5, v1.27.0) run server-side, IN PROCESS, on EVERY entity read — every
`GET`/list/create for every authenticated reader (the entities shared-workspace rule: any
authenticated user evaluates whatever ANY admin wrote onto ANY blueprint). `net.thisptr:
jackson-jq` 1.3.0 (jq 1.6 semantics) is **not a sandbox**: it enforces no sub-tree isolation, no
module loading, and no timeout of its own. Admin-trusted BY DECISION, not by construction — an
expression that never emits (`def f: f; f`) blocks its calling worker until the JVM stack
overflows, and jackson-jq cannot be interrupted mid-evaluation. Two mitigations shipped with
phase 5: evaluation runs OUTSIDE the entity's database transaction (`list`/`read`/`create`
materialize rows and the snapshot inside `suspendTransaction`/`writeTransaction`, then evaluate
AFTER it closes — `.claude/docs/persistence.md`), so a pathological expression pins a
request-handling coroutine, never a pooled R2DBC connection or the entity write lock; and the
FIRST jq output wins, aborting the instant it is emitted (`range(1e9)` yields `0` instead of
iterating a billion times) — a `StackOverflowError` from unbounded recursion is caught, not left
to crash the worker.

**Bounded executor and per-expression deadline (v1.29.0).** `EntityService` owns ONE shared,
thread-safe `JqEvaluator` (constructed in `infra/db/Database.kt`), so the compile cache
(`ConcurrentHashMap`, capped at 4096 entries, clear-on-overflow — hygiene only, admin-authored
expressions never approach it) is process-lifetime rather than per-request. Every evaluation runs
on a bounded daemon worker pool named `entity-jq` (`ThreadPoolExecutor`, 4 workers, queue 64,
`AbortPolicy`) — the same idiom as the outbound URL fetch's pool ("Native DNS limitation and
bounded containment" below), but a SEPARATE pool so a stranded jq worker never costs a URL
fetch; the coroutine bridge (`Executor.awaitBounded`, suspend-with-cancellation over a bounded
`ThreadPoolExecutor`) was extracted from `catalog/UrlFetch.kt` into
`infra/concurrency/BoundedExecution.kt` and is now shared by both consumers. Each evaluation is
also bounded by a **per-expression deadline** — `computed.jq.deadlineMillis`
(`$JQ_DEADLINE_MILLIS`, default **500 ms**, valid range 1..60000; boot fails outside it, the
`security.passwordReset.tokenTtlSeconds` idiom) covering QUEUE WAIT plus EVALUATION of the
ALREADY-COMPILED expression, enforced via `withTimeoutOrNull` around the pool submission on the
caller's coroutine. Compiling the expression runs on the CALLER, before this clock starts (a
compile failure answers absent immediately — already logged at DEBUG — and never touches the
executor or the quarantine set), and the jq 1.6 builtins are loaded exactly ONCE per process, at
`JqEvaluator` construction (`configureDatabase`, at boot, well before any request) rather than
lazily on the first evaluation. On 2026-09-12 a cold CI JVM's first-ever calculation — a
trivially cheap expression — was wrongly quarantined because loading the builtins alone (class
loading plus parsing jq's own builtin definitions) exceeded the 500 ms default on a slow hosted
runner: the deadline was meant to bound the EXPRESSION, not JVM warm-up, so `JqEvaluator`'s
constructor now forces the builtin load and one throwaway compile+evaluation synchronously on
the constructing thread. An expression TEXT that misses the deadline is **quarantined until
edited**: recorded in a process-lifetime set, so every later evaluation of that exact text
answers absent IMMEDIATELY without touching a worker — recovery is either an admin edit (new
text = new cache key) or a server restart. This bounds the blast radius to at most ONE stranded
worker per distinct bad expression, ever: jackson-jq does not observe `Thread.interrupt()`, so
the cancelled task's worker stays busy until the expression finishes or overflows the stack —
the same class of residual as native DNS ignoring interruption, below. Pool saturation (4 busy +
64 queued) answers absent for that read without quarantining anything — a transient load spike,
not a property of the expression. The same distinction governs the deadline itself: it covers
queue wait + evaluation of the compiled expression (never compiling or the one-time builtin
load), but only a miss AFTER the task began running quarantines the text — a miss while the
task was still queued (the workers stranded by OTHER expressions) is treated as saturation, so
four bad expressions can strand four workers but can never quarantine the good expressions
waiting behind them.

`env/0` is a jackson-jq BUILTIN (`EnvFunction`, backed by `System.getenv`) that would otherwise
let an admin-authored calculation read `JWT_SECRET`, the database password, or any other
process environment variable and leak it into `properties` for every authenticated reader.
`entities/JqCalculation.kt`'s root `Scope` loads the jq 1.6 builtins via
`BuiltinFunctionLoader` FIRST, then immediately shadows `env` (a zero-arg function that emits
nothing) and `$ENV` (an empty object) — pinned by `JqCalculationTest`'s "env and ENV are
shadowed even though the process environment is non-empty" case, which checks
`System.getenv("PATH")` is non-null while both jq forms yield nothing. No `Scope.setModuleLoader`
is ever installed, so `import`/`include` fail rather than reading the filesystem (also pinned).
Every evaluation runs in its own `Scope.newChildScope` of that read-only root, so a
mid-expression `def`/`as` binding never leaks across entities or requests.

Output is bounded two ways: the first-emission abort above, and a **64 KiB**
(`MAX_CALCULATION_OUTPUT_CHARS`) cap on the serialized winning result — an oversized result is
absent, never truncated. jq's regex builtins (`test`/`match`/`capture`/`scan`/`sub`/`gsub`) run
on `joni` (the same engine backing JRuby); an admin-authored catastrophic-backtracking pattern
is the same ReDoS class as any regex engine and is not specially guarded against here — treat
`calculationProperties` regexes with the same care as any other admin-authored regex
(`.claude/docs/port-data-model.md`'s string `pattern` property already carries the same
caveat). Evaluation failures — compile errors, runtime errors, type mismatches, recursion
overflow — are logged at DEBUG on `ch.nokillswit.entities.computed` with the property id,
blueprint identifier, and `Throwable.toString()` ONLY; the INPUT document (the entity's
`properties`/`relations`, which may carry sensitive business data) is NEVER logged. Two more
events on the same logger, both WARN, both context-only (no input value): **once per
quarantine** ("jq calculation exceeded its {}ms deadline and is quarantined ({})" with the
`<blueprint>.<propertyId>` context) and **once per saturation episode** (the first pool
rejection — "jq worker pool saturated, calculation absent ({})" — or the first queued-miss
timeout — "jq calculation timed out while queued, calculation absent ({})" — logs WARN, later
ones in the same episode DEBUG, reset by the next accepted submission).

### Not yet ported from Lettuce

Each of these is a fully worked-out Lettuce subsystem (implementation + tests + docs); port it rather than redesigning, and restore its section of Lettuce's security doc alongside:

- **Field encryption at rest** (`infra/crypto/FieldCipher.kt`, `DATA_ENCRYPTION_KEY` + fail-closed burned-key check, the `EncryptedAtRest` rotation-backfill registry, never filter/sort encrypted columns in SQL).
