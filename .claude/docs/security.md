### Security posture: development vs production mode

**Local-demo network boundary.** Compose publishes the app (:8081), PostgreSQL (:5433), and Mailpit (:8026) on `127.0.0.1` only. Do not widen those bindings casually: the demo has burned credentials, HTTP, and an unauthenticated mailbox containing reset/MFA emails. Remote deployment needs a separate production configuration with private secrets, TLS, and private database/mail services. Existing containers retain old bindings until recreated; editing YAML does not secure a running container retroactively.

`ktor.development` is env-overridable (`$KTOR_DEVELOPMENT:true`): local `:server:run`/tests default to development mode; **the Docker image ships `KTOR_DEVELOPMENT=false`** (production mode), and `docker-compose.yaml` explicitly sets it back to `true` because it is the local plain-HTTP demo. Production mode activates HSTS + HTTPS redirect and three **fail-closed startup checks**:

- **JWT secret** (`plugins/Security.kt`): blank, the placeholder `"secret"`, the repo-committed compose demo key (`dev-only-00366d…`), or the `k8s/templates/secret.yaml` template placeholder (`CHANGE-ME-openssl-rand-hex-32`) → warn in development, **refuse to start** in production. Set a strong private `JWT_SECRET`.
- **Seed passwords** (`infra/db/Bootstrap.kt`, module `configureBootstrap`, runs after `configureDatabase`): in production mode, if any active account still carries the well-known `changeme` bcrypt hash the app **refuses to start**. Setting `ADMIN_INITIAL_PASSWORD` (config `bootstrap.adminInitialPassword`) rotates the V3 seed admin's password at startup — idempotent: only applied while the admin still has the seed hash, so an admin-chosen password is never overwritten. An applicable nonblank bootstrap value is checked before hashing: the shared minimum of 10 characters and maximum of 71 UTF-8 bytes apply, and the burned `changeme` / `CHANGE-ME` values are explicitly refused. A new salted hash must never disguise the public seed password. Invalid values fail startup in every mode when rotation is needed. Once the seed has already been rotated, obsolete bootstrap configuration is ignored; an upgrade must not reject an unused legacy initial password. Covered by `BootstrapTest`; seed-mutating tests restore state via `TestSeedState.restoreSeedAccounts()` (`TestEnvironment.kt`).

- **Mail transport** (`infra/mail/Mail.kt`): `mail.transport=log` writes outbound email (including reset links and MFA codes) into the application log — permitted with a warning in development, **refuse to start** in production; `smtp` with a blank host refuses to start in **any** mode. See "Outbound email" below.

(Lettuce has one more fail-closed check — the data-encryption key; it arrives with field encryption at rest, see "Not yet ported" below.)

**JWT/session model** (`auth/Tokens.kt`, `auth/AuthSessionService.kt`, `plugins/Security.kt`): every access/refresh pair carries a random `jti` and a shared `sid` identifying one login's database-backed family (`auth_sessions`, V25). The verifier requires signature/issuer/audience/expiry, access `typ`, a non-revoked `jti`, and a LIVE family belonging to the token's non-wrapping UInt user id. Every request checks the family against the active user's monotonic `auth_version` in PostgreSQL — acceptance is never cached. Password changes (including bootstrap/reset) and email/role changes advance that version atomically; deletion fails the active-user check. Same-clock-tick changes invalidate old access AND refresh tokens, and restoring a role never resurrects an old session. Logout deletes the entire family, including every superseded refresh generation, without requiring a body; unrelated logins/devices stay alive. Renewal updates an existing row only, cannot resurrect logout, and never shortens the family's expiry. The older per-jti blocklist/cache remains an additional defense, not the account/session revocation boundary. Already-authorized in-flight requests may finish. **Upgrade:** tokens without `sid` are rejected; deploying V25 requires everyone to sign in again. Session revocation works across instances; MFA challenges and throttles still require the documented single-replica posture. Configuration: `JWT_SECRET` (above), `JWT_ISSUER` (default `http://0.0.0.0:8081/`), `JWT_AUDIENCE` (`toadie-api`), `JWT_REALM` (`toadie-api`), `JWT_ACCESS_EXPIRES_IN_SECONDS` (900 — the short-lived bearer the SPA silently refreshes) and `JWT_REFRESH_EXPIRES_IN_SECONDS` (3600 — pure-sliding: each refresh mints a fresh pair, so an idle session ends after this window), all in `application.yaml`'s `jwt:` block.

**An outage reads as 500, never 401** (2.18.2; ported from Lettuce v3.16.2 and Flow).
- **Applies when:** changing the JWT `validate`/`challenge` pair in `plugins/Security.kt`, or any lookup that runs inside it (`TokenBlocklistService.isRevoked`, `AuthSessionService.isActive`).
- **Requirement:** a structurally invalid token (wrong audience/`typ`, no `jti`, no `userId`/`sid`) is rejected BEFORE any database call and answers 401. A revocation or session lookup that THROWS (database unreachable, pool acquire timeout) is an outage, not an invalid token. Ktor's JWT provider turns an ordinary throw out of `validate` into a plain challenge (it rethrows cancellation; it also catches JVM `Error`s, which therefore still read as a 401 — the wrapper does not change that), so `catchingFailures` (`infra/Failures.kt` — the cancellation-safe catch for this path; `UrlFetch` and the import loops carry their own justified ones) stashes an ordinary `Exception` under `BlocklistFailureKey` on the call, `validate` returns null, and `challenge` answers the catch-all's `500` problem+json via `respondInternalError` (logged once at ERROR with the cause, like every unhandled exception) instead of the 401 the SPA reads as session expiry. `CancellationException` is rethrown untouched. No new audit event: the outage is an operational failure, not a security decision.
- **Reference:** `plugins/Security.kt`, `infra/Failures.kt`, `plugins/ErrorHandling.kt` (`respondInternalError`); the 500 is the spec's already-declared cross-cutting response, so OpenAPI conformance needs nothing new.
- **Enforcement:** `BlocklistOutageTest` (failing blocklist and failing session lookup answer 500; refresh-token, jti-less and malformed bearers stay 401 during the outage) and `CatchingFailuresTest`.
- **Exception:** none.

**SPA session boundaries.** Graph controllers and HTTP refresh work belong to one login
family. Decode `sid` only to partition local work, never as proof of authentication; the
server remains the verifier. Refresh within a family preserves pending graph edits, while
sign-out, a different login (including the same user id), and cross-tab session replacement
invalidate them. A delayed 401 or refresh response from an old session must neither restore
its credentials, replay its request, nor clear a newer login. Keep refresh single-flight
within its owning session, and preserve transient-failure behavior. Already-dispatched,
authorized server requests may still finish; client cancellation is not transaction rollback.

**Token storage & response headers (the XSS posture).** The SPA keeps BOTH tokens in `localStorage` under `toadie.auth.*` (`web/src/api/session.ts` — access, refresh, roles, userId, disabledFeatures; no password is ever persisted; everything else in localStorage is view state). A deliberate trade-off, documented rather than hidden: any script running on the origin can read the REFRESH token, i.e. a renewable session unbound to device/IP revoked by session logout or a credential/identity change — so the real control is keeping foreign script out. That control is `plugins/SecurityHeaders.kt`, installed unconditionally and pinned by `ServerTest`: a strict CSP (`script-src 'self'`, `object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`. Consequences: never weaken `script-src` (no CDN scripts, no `unsafe-inline` — Vite bundles everything same-origin, fonts included), and the SPA must stay free of `dangerouslySetInnerHTML`/`eval` sinks (currently zero). The dagre graph-layout Web Worker (`web/src/utils/graphLayoutWorker.ts`, 2.20.0) is a separate hashed same-origin ES-module chunk, which `script-src 'self'` already covers (no `worker-src`/`child-src` is set, so workers fall back to it): never add `worker-src 'none'`, and never inline it (`?worker&inline` builds a `blob:` worker the policy blocks). Moving tokens to httpOnly cookies would trade this for CSRF machinery — revisit only with that full picture.

**Login timing equalizer** (`auth/AuthRoutes.kt` + `TIMING_EQUALIZER_HASH` in `auth/Passwords.kt`): an unknown email pays a full, discarded bcrypt verify against a fixed cost-12 hash so its 401 takes as long as a wrong password's — without it, response timing is an account-enumeration oracle (the reset path equalizes via async processing; login equalizes in-line).

**Per-account login lockout** (`auth/LoginThrottle.kt`, wired in `configureAuthRoutes`): after `security.lockout.threshold` (default 5, `$LOGIN_LOCKOUT_THRESHOLD`) consecutive failures for one submitted email, `/login` answers `429` for `security.lockout.durationSeconds` (default 900, `$LOGIN_LOCKOUT_DURATION_SECONDS`) — even with the correct password, and regardless of whether the account exists (no enumeration signal). A success resets the counter. In-memory and per-instance by design (single-replica deployment; a restart only resets the throttle). Complements the per-IP `RateLimit` bucket, which rotating hosts sidestep. The lockout 429 is **thrown** (`TooManyRequestsException`), never `respondProblem`ed directly — StatusPages' generic 429 status handler rewrites any non-StatusPages 429, so only the exception path keeps the specific detail. Tests: `LoginThrottleTest` (unit, injected clock) + `LoginLockoutTest` (route). The SPA maps login 429s to a generic localized sign-in-throttling message (`auth.accountLocked`), covering account lockout, IP limits, and MFA capacity. An email longer than 254 characters is rejected with a plain `400` before the lockout logic ever runs (`auth/AuthRoutes.kt`, sharing `users/MAX_EMAIL_LENGTH`) — no stored account can exceed that length, so this carries no enumeration signal.

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
mail/localization/throttle primitives, not that weakness. `POST /api/v1/password-reset`
likewise rejects an over-254-character email with a plain `400` before the per-email throttle
ever sees it (sharing `users/MAX_EMAIL_LENGTH`); `LoginThrottle` and `PasswordResetThrottle`
also replaced their "prune only once already oversized" pruning with a fixed-256-call
maintenance cadence plus a hard `MAX_TRACKED` capacity eviction (locked-out `LoginThrottle`
entries are never evicted to make room).

**Per-IP login bucket** (`security.rateLimit.loginPerMinute`, `$LOGIN_RATE_LIMIT_PER_MINUTE`, registered in `configureAuthRoutes`): blank **follows the mode** — 10/min in production, 1000/min in development — and an explicit number pins it in either mode (the `http.exposeOpenApi` idiom). Development is lifted because the e2e suite drives its logins from one host and would otherwise sleep out the bucket; **the per-account lockout above is the actual brute-force defence and is identical in both modes**. The sibling `refresh` bucket defaults to 30/min in both modes and is pinnable via `security.rateLimit.refreshPerMinute` (`$REFRESH_RATE_LIMIT_PER_MINUTE`). `RateLimitResponseTest` and `LoginTest` pin the value to `10` explicitly, since tests run in development mode.

**Per-IP integration bucket** (`security.rateLimit.integrationPerMinute`, `$INTEGRATION_RATE_LIMIT_PER_MINUTE`, registered beside the login bucket in `configureAuthRoutes` — Ktor installs `RateLimit` once — and applied to `/integration/graphql`, `/integration/graphql/schema` and `/integration/mcp` in `integration/Integration.kt`): the same mode-following rule — blank is 300/min in production, 3000/min in development (the e2e GraphQL smoke and the Playwright suite drive from one host), a number pins it. It fronts authentication, so a bearer-less flood is throttled per client address before it can write more than its `integration.auth_failed` lines. The 429 is Ktor's, not audited; limits, the NAT caveat and the admission-after-authentication order live in `integration-api.md` "Deployment and limits". `RateLimitResponseTest` pins the bucket and the resolution rule (`integrationRateLimit`).

**Email MFA (opt-in)** (`auth/MfaChallenges.kt` + `auth/MfaEmail.kt`, wired in `configureAuthRoutes`): a per-user second factor behind the `MFA` feature flag — **the one inverted-default flag**: `V13` seeds every existing user as MFA-disabled and `UserService.create` inserts the disabled row for every new user (admin create and test seeds both funnel through it), so MFA is OFF until an admin removes the row via the features PUT (the per-user editor or the `/feature-flags` bulk screen; mind the wholesale-replace semantics — a PUT whose disabled set omits `MFA` ENABLES it, including the "empty array re-enables everything" idiom). With MFA enabled, `POST /login` with correct credentials answers `200 MfaChallengeResponse {mfaRequired, challengeId, expiresAt}` instead of tokens (the branch runs AFTER `verifyPassword`, so nothing about enumeration or lockout changes; `login.success` is deliberately NOT emitted); a 6-digit code (`generateMfaCode`, `auth/Passwords.kt`) is held in the in-memory challenge store (config `security.mfa.codeTtlSeconds` default 300 `$MFA_CODE_TTL_SECONDS`, `security.mfa.maxAttempts` default 5 `$MFA_MAX_ATTEMPTS`; per-instance like the throttles — a restart just means signing in again) and emailed in the user's stored language (V18, EN fallback); a delivery failure logs only
the exception CLASS NAME (`login.mfa_send_failed`'s `errorType` field and the paired log line —
the reset path's rule, `.claude/docs/observability.md`), never the exception object or its
message, since the message body carries the 6-digit code. `POST /api/v1/login/mfa` `{challengeId, code}` (own per-IP bucket `"mfa"`, hardcoded 10/min in every mode) exchanges it for the ordinary `LoginResponse` — one fresh `userService.read` first, checking the challenge's captured `auth_version` (password or identity/role changes and deletion invalidate the pending challenge with the same uniform 401); session registration checks that epoch again under a row lock; every failure mode (unknown/expired/wrong code/attempt cap) is a **uniform 401**, reasons live only in the `login.mfa_failure` audit event; challenges are single-use. **Bounded admission:** at most 10,000 live challenges exist per instance. Expiry cleanup, capacity checking, and insertion form one atomic admission operation. At capacity, login returns conformant `429`, audits `login.mfa_capacity_rejected`, and queues no email; existing challenges remain valid and expired or consumed entries free capacity. **Mail-less deployments fail closed**: on `MAIL_TRANSPORT=disabled` an MFA-enabled login answers `503` (`login.mfa_unavailable`) — don't enable MFA flags on the k8s default `disabled` transport; the features PUT keeps working, so an admin can always flip a flag back. SPA: the Login card's second step (`PinInput`, `auth.mfa*` keys). Tests: `MfaChallengesTest` (pure store) + `MfaLoginTest` (route matrix); e2e `mfa.spec.ts` drives the emailed code through Mailpit.

**Swagger/OpenAPI gate** (`plugins/Http.kt`): `/openapi` (UI + spec) is served only in development mode, or when `http.exposeOpenApi` (`$HTTP_EXPOSE_OPENAPI`) is explicitly `"true"` — blank follows the mode, `"false"` hides it even in dev. Bearer auth cannot protect a browser-loaded UI (page loads carry no `Authorization` header), hence a gate rather than `authenticate {}`.

**Accounts are admin-managed.** There is no self-signup: administrators create accounts at `/users/new`. The initial password is generated CLIENT-side (`web/src/utils/password.ts`, 96 bits) and shown to the admin exactly once in the reveal modal — the server stores only the bcrypt hash and no response ever carries plaintext; users rotate it via the Change password page (the existing `PUT /users/{id}/password` rules). Deletion is the soft-delete convention plus two guards: no self-delete (403) and the last active administrator can be neither deleted nor demoted (409). Service accounts (V41, 2.15.0) are the one exception: created only alongside an integration client, never able to sign in, and hidden from the management surface — `.claude/docs/authorization.md` "Service accounts (V41)".

**Canonical email identity.** Emails are folded to `trim().lowercase()` (`canonicalEmail` in `users/Validation.kt`) at EVERY entry point — login and `findWithIdByEmail` itself (defense-in-depth) today, and every future create/update/import — so one mailbox is one account and a padded/case-variant login matches (and shares one lockout bucket). The V1 partial unique index stays byte-wise (all writes are canonical).

**Request body ceiling.** `RequestBodyLimit` (installed in `plugins/Http.kt`, `MAX_REQUEST_BODY_BYTES` = 10 MiB) rejects oversized bodies with a 413 problem (mapped in `plugins/ErrorHandling.kt`) before any receive/validation work — a memory-DoS backstop, not a business rule; field-level `maxLength` validation rejects oversized values far earlier on ordinary payloads. Declared in the spec on the body-heavy catalog operations (create, replace, import, sync) and covered by `PayloadValidationTest`.

**Request payload validation (convention — API-SEC-003/API-ERR-005).** Mutating routes validate payloads up-front and throw `BadRequestException` (→ `400` + `ProblemDetail`) instead of letting oversized/blank values die in the DB as `500`s. One cross-cutting example: password ≥ `MIN_PASSWORD_LENGTH` (10) chars AND ≤ 71 UTF-8 bytes (`validatePassword` + `MIN_PASSWORD_LENGTH` / `MAX_PASSWORD_BYTES` in `auth/Passwords.kt` — **the bcrypt ceiling**: longer input makes bcrypt throw, and the 500-vs-401 split would be an account-enumeration oracle, so login's `verifyPassword` guards it too). Declare limits as `maxLength` in the OpenAPI spec. A validation failure that must name EVERY violated rule (the catalog strict save, the entity save) throws a subclass of `infra/validation/InvalidPayloadException` — the one findings-bearing 400 shape, RFC 7807 plus a `findings` array in a feature-typed problem DTO (`CatalogFileInvalidProblem`, `EntityInvalidProblem`) — so `plugins/ErrorHandling.kt` renders both through one handler and imports no feature package. Keep new validators feature-local and enforce them **after** the authz guard (403 wins over 400). Covered by `PayloadValidationTest`. These findings-bearing semantic rejections answer `400`, never `422` — a deliberate, registered gap (`API-ERR-002` in `api-guidelines/API-GUIDELINES.md`'s known-gaps register).

**YAML comparison resource bounds.** A valid API definition can contain enough short lines
to make an unrestricted LCS matrix exhaust the browser's memory. The SPA gates detailed
diffs by combined characters, combined lines, and full matrix cells before allocating it
(`web/src/utils/yamlDiff.ts`; exact budgets in `web/CLAUDE.md`). Oversized comparisons show
both complete canonical documents as plain React text in two scrollable blocks, without
per-line DOM creation. This is a presentation fallback, not a validation bypass or an
upload limit: parsing/rendering the full document still costs work proportional to its
size, and the normal validation and explicit overwrite confirmation remain in force.

**Outbound URL fetch (SSRF posture).** `infra/fetch/UrlFetch.kt` (relocated from `catalog/` in
2.9.0 when entities gained their own source references — one shared bounded fetcher/pool behind
`POST /api/v1/files/fetch`, `POST /api/v1/entities/fetch`, and, since 2.10.0, the ADMIN-only
`POST /api/v1/blueprints/fetch`) serves the catalog import page/repo-sync modal, the ontology
import page/entity sync modal, and the Blueprints list's sync modal alike. Guards, in order:
absolute `https` only, no userinfo, non-blank host, URL ≤ 2048 chars (`parseFetchUrl`); then
EVERY resolved address must be public (`resolveFetchTarget` / `requirePublicAddresses`). Loopback, site-local, link-local, any-local, multicast,
IPv6 ULA `fc00::/7`, CGNAT `100.64.0.0/10`, `192.0.0.0/24`, benchmarking `198.18.0.0/15`,
and NAT64 `64:ff9b::/96` embedding a non-public IPv4 are refused, as are unresolvable hosts.
Guard rejections retain the uniform **400** `FETCH_URL_INVALID_DETAIL`. Audits remain
`catalog_file.fetch_blocked` / `catalog_file.fetched` for the files route, `entity.fetch_blocked` /
`entity.fetched` for the entities route (since 2.9.0), and `blueprint.fetch_blocked` /
`blueprint.fetched` for the blueprints route (since 2.10.0) — with scheme/host ONLY, never
the full URL or upstream exception text (either may contain query-string credentials). Like
`catalog_files.source_url` and `entities.source_url`, `blueprints.source_url` is served IN FULL
to every authenticated user in this shared workspace (the blueprint registry's own "ADMIN-curated,
readable by everyone" rule) — do not embed secrets in any of the three kinds of source reference.

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

**Operator-configured outbound HTTP.** `infra/fetch/OperatorHttp.kt` is the second, deliberately narrow outbound client: the SSO issuer's discovery, JWKS and token endpoints are deployment configuration (like the SMTP host), may sit on a private network, and so cannot go through `UrlFetch`'s public-address guard.

- **Applies when:** the server calls an endpoint named by operator configuration or by the configured issuer's own discovery document (today only `auth/sso/`), never one a user or request supplied.
- **Requirement:** such calls use `OperatorHttp` only. It has no address rule, so it is never handed a URL taken from a request, and `UrlFetch`'s address rule is never widened to admit private hosts. Scheme: `https` only, plus `http` when constructed with `allowHttp = true` (the caller passes it in development mode only); userinfo or a blank host is refused. Hardening mirrors `UrlFetch`: no redirects (a 3xx is returned as a status, `Location` ignored), `Proxy.NO_PROXY` and a direct socket, no cookie jar, no retries, a fresh zero-idle connection pool per call, `Accept-Encoding: identity`, the 1,048,576-byte cap on `Content-Length` and while streaming, one 10-second deadline over the whole exchange, caller cancellation propagating unchanged. Its own daemon pool (`operator-http`, 2 workers, 8 queued, `AbortPolicy`) keeps a stalled IdP from starving user fetches and the reverse. Any HTTP status is a normal `OperatorResponse` (the token endpoint answers a failed exchange as a JSON 400); every other failure is one `OperatorHttpException(kind, scheme, host)` (`INVALID_URL`, `TIMEOUT`, `UNAVAILABLE`, `TOO_LARGE`, `BUSY`) whose message never carries a path, query, form field, credential or body. `postForm`'s `client_secret_basic` header is `Basic base64(urlencode(id):urlencode(secret))` (RFC 6749 section 2.3.1). The class logs nothing and emits no audit event; the SSO caller audits, with scheme and host only.
- **Reference:** `infra/fetch/OperatorHttp.kt`; the user-URL counterpart above ("Outbound URL fetch (SSRF posture)").
- **Enforcement:** `OperatorHttpTest` (redirect not followed, cap by `Content-Length` and chunked, deadline, scheme/userinfo refusal, pool saturation, urlencoded Basic header, no secret in failures or logs); the construction-site pin in `SsoConfigTest` (SSO change) asserts `OperatorHttp` is constructed only under `auth/sso/`.
- **Exception:** none.

**CORS is off by default** (`plugins/Http.kt`): the plugin is installed only when `http.corsHosts` (`$CORS_ALLOWED_HOSTS`, comma-separated hosts) is non-empty. Production is single-origin (Ktor serves the SPA) and dev goes through the Vite proxy, so no cross-origin caller exists by default — no `anyHost()`. **Reverse proxy**: set `HTTP_BEHIND_PROXY=true` (config `http.behindProxy`) when TLS terminates at an ingress/proxy — it installs `XForwardedHeaders` so rate-limit buckets key on the real client IP and the HTTPS redirect sees the real scheme and host; the proxy must set (and overwrite client-supplied) `X-Forwarded-For`/`X-Forwarded-Proto`/`X-Forwarded-Host` — `k8s/templates/app-ingress.yaml` is the reference contract. Off by default because honoring those headers from direct clients lets them spoof both. Two hardenings ported from Lettuce (v1.22.0): the trusted header list is NARROWED to those canonical three (Ktor's default also honours `X-Forwarded-Server`/`-Protocol`/`-SSL`/`Front-End-Https`, which a proxy that sets only the canonical ones passes through from the client untouched — `ProductionHttpTest` pins that `X-Forwarded-Server` cannot name the redirect target), and `X-Forwarded-For` is read from the END of the list (`HTTP_PROXY_HOPS`, default 1, = trusted proxies that APPEND — `ForwardedHeadersTest` pins the bucket keying). Since 2.18.2 (ported from Flow) that selection is Toadie's own, not the plugin's: Ktor's `XForwardedHeaders` reads `headers.get("X-Forwarded-For")`, the FIRST header LINE only, so a proxy that APPENDS a separate line (HAProxy `option forwardedfor`) would leave the client-supplied first line as the rate-limit key. A `Setup`-phase interceptor (`resolveForwardedForOrigin`, `plugins/Http.kt`) folds every line via `getAll` in hop order, applies the same trust-from-the-end rule and writes `mutableOriginConnectionPoint`; the plugin's `forHeaders` is cleared so it never overwrites that result. `portHeaders` is cleared too: a client `X-Forwarded-Port: x` made Ktor's handler throw a `NumberFormatException` from CallSetup (a 500 before any route) and nothing reads it. `RawForwardedForLinesTest` pins the wire shape with raw socket bytes against the real engine (ktor-client folds repeated headers into one line, so it cannot reproduce it); `ForwardedHeadersTest` pins the junk port header. The security headers are appended in the `Setup` pipeline phase so the production 301 carries them too (appending after `HttpsRedirect` commits the response throws on Netty). The Kubernetes probes send `X-Forwarded-Proto: https` for the same reason — without it production mode redirects the plain-HTTP probe and the pod crash-loops (see `k8s/app-deployment.yaml`).

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

**The read-only `calculationVerdict` seam (2.5.0).** The Port-world Errors report
(`.claude/docs/port-data-model.md` "Computed-property health") needs to say whether a
`calculationProperties` expression compiles and whether it is currently quarantined, WITHOUT
evaluating it — the report never runs a jq expression against entity data. `JqEvaluator.
calculationVerdict` answers this by checking quarantine-set membership, then compiling (and
caching) the expression on the CALLER's own coroutine — never submitted to the `entity-jq`
executor, so a report read never occupies a worker or risks stranding one. Because the
quarantine set is process-lifetime and INSTANCE-local (the same caveat as the deadline
quarantine above), the report's `CALCULATION_QUARANTINED` finding describes only what happened
on the instance serving that request; a second instance not yet handed the same expression
would see `Ok` or `CompileFailed` for a text this one already gave up on.

### Entity query evaluation (phase 7, v2.0.0)

`GET /api/v1/entities/graph?query=` and `POST /api/v1/entities/query/check` evaluate USER-TYPED
text in process, for any authenticated user (`.claude/docs/entity-query-language.md`). Unlike the
admin-authored jq calculations above this is NOT a general-purpose language: a hand-written
parser accepts a fixed grammar (patterns, `WHERE` comparisons, `RETURN` of node variables), the
evaluator is a pure Kotlin loop over an in-memory snapshot — no code execution, no filesystem,
no environment, no regex engine (`=~` is rejected precisely because `java.util.regex` cannot
honour the deadline), no functions. Resource bounds are structural and per request: the caps are listed in `.claude/docs/entity-query-language.md` (the reference), and a cooperative deadline (`entityQuery.deadlineMillis`,
`$ENTITY_QUERY_DEADLINE_MILLIS`, default 2000 ms, boot-validated 1..60000 — the
`computed.jq.deadlineMillis` idiom) observed at EVERY checkpoint, i.e. once per produced
candidate (`DEADLINE_EXCEEDED`). The budget is cooperative — nothing preempts a candidate
mid-way, and an outer `withTimeout` could only cancel at the same checkpoint, so there is none —
which is why per-candidate work is itself capped: `CONTAINS`/`STARTS WITH`/`ENDS WITH` answer
UNKNOWN past 16 384 haystack / 256 needle characters (`MAX_STRING_OPERAND_CHARS`/
`MAX_STRING_NEEDLE_CHARS`; `String.contains` is O(haystack × needle) and two 256 KiB properties
would otherwise be minutes of uninterruptible work per candidate). Evaluation
runs on the dedicated `entity-query` daemon pool (4 threads),
NEVER on `Dispatchers.Default`, which bcrypt (`auth/Passwords.kt`) and the request pipeline
share — a slow query can cost query capacity, never a login. In-flight evaluations are bounded by
4 permits (`tryAcquire` after validation and before the workspace read — a
`429` problem when none is free, a refused query costing none), because each holds the decoded
workspace on the 256 MiB heap for the length of its evaluation; the decode itself and the
parse/validate step run OUTSIDE the read transactions (the schema is read in one short
transaction, the workspace rows in a second, both closed before any QUERY CPU work — the shown
rows' own decode and effective-team resolution stay inside the second, as in the query-less
graph), so nothing query-driven holds a pooled connection or the entity lock. Caller cancellation
propagates unchanged; nothing is quarantined or cached across requests. A budget miss is a
semantic `400` for THIS query and logs ONE DEBUG line on `ch.nokillswit.entityquery` with the
code and the query LENGTH — the SERVER never logs the text, which may carry business data
(entity identifiers, property values); the text does ride the GET request line, so like `q` it
reaches access/proxy logs and browser history, and an OTel trace exporter that captures
`url.query` would carry it too (none is configured by default). Diagnostics echo only the
user's own unknown name plus a suggestion drawn from blueprint identifiers/titles and relation/
property keys every authenticated user can already read. Neither endpoint audits (pure reads,
the `/check`/`/errors`/export rule). `EntityQueryRouteTest` pins the deadline, cancellation and
saturation paths through injected clock/permit seams.

**The 16 KiB request line.** `ktor.deployment.maxInitialLineLength: 16384` (`application.yaml`;
Ktor 3.5.2's `EngineMain` reads it into Netty's `HttpServerCodec`) raises Netty's 4096-byte
default so a 2000-character query — roughly 6 KB URL-encoded on the graph GET — is parsed rather
than answered with a connection-level 414 (2000 non-ASCII characters can encode to 12–18 KB — CJK
text hits the ceiling and gets a 414 without diagnostics, accepted). Still a hard ceiling: a
longer request line is refused by Netty before any handler runs, `maxHeaderSize`/`maxChunkSize` keep their defaults, the 10 MiB
body ceiling is unchanged, and ingress-nginx's default `large_client_header_buffers 4 8k` fits
the same 6 KB. `ProductionHttpTest` pins that a ~6 KB request line reaches the JWT challenge.

### Entity read memory budget (2.4.0)

Since 2.19.0 the same ledger also admits the Backstage catalog's whole-workspace reads (`graph`,
`errors`, `export`) — see "Whole-workspace content reads are admitted through the one read
ledger" below; the mechanics described here are shared.

The entity registry caps (2,000 entities per blueprint, 10,000 total, 256 KiB per document) can
in the worst case exceed the 256 MB heap budget when a single graph/query read materializes many
large documents at once. `infra/concurrency/ReadLedger.kt` bounds this with a process-wide,
in-flight-bytes ledger (`READ_BUDGET_BYTES` = 64 MiB, derived from `-Xmx256m`
— `server/build.gradle.kts`'s JVM-args comment cross-references this constant; change the two
together) every `list`/`read`/`create`/`update`/`graph`/`errors` read charges through ONE consolidated
read set (`entities/EntityWorkspaceRead.kt`'s `loadReadSet`, `.claude/docs/persistence.md`
"Entity read memory budget and the consolidated read set"):

- **Heap layout.** `-Xmx256m` leaves roughly 170 MiB of old-generation headroom once the JVM's
  own baseline (class metadata, thread stacks, the JIT, GC bookkeeping) is subtracted — measured
  at ~21 MiB idle. The 64 MiB ledger is well inside that remaining headroom, leaving slack for
  request-handling overhead outside the ledger's own accounting (HTTP buffers, JSON encoding of
  the RESPONSE, computed-property evaluation's own working set). The 512 MiB container stays at
  ~270 MiB RSS under light load (a near-empty workspace, not the 10k/10k probe below).
  **Probe numbers (the one canonical home; the Gradle, compose
  and k8s comments link here):** the 2026-10-24 probe (10k files/10k entities, 10 concurrent
  whole-workspace reads, master 62db09a, measured BEFORE the catalog reads joined the ledger)
  read VmRSS 518624 kB (506 MiB) / VmHWM 532780 kB (520 MiB) from the app process against the
  512 MiB container limit — at the limit, not comfortably under it — and the heap OOMed because
  the catalog reads were unbudgeted. `infra/concurrency/ReadLedger.kt` bounds the heap side.
- **Native memory (measured 2026-10-25).** The remainder was NOT Netty/R2DBC direct
  buffers: `-XX:NativeMemoryTracking=summary` on the loaded process (10k/10k workspace, master
  2031e33) put the whole malloc side at ~39 MiB and `Other` (where `DirectByteBuffer`s are
  counted) at ~3 MiB, while `/proc/1/smaps` showed ~106 per-thread glibc malloc arenas
  (64 MiB-aligned mappings, ~100 JVM threads on an 18-core host: arena cap = 8 x cores), each
  resident as one 2 MiB transparent huge page — ~210 MiB of the ~500 MiB RSS, mostly empty. The
  image therefore sets `ENV MALLOC_ARENA_MAX=2` (`Dockerfile` runtime stage; compose and k8s
  inherit it; `web/src/test/infrastructure.test.js` pins it). Probe, same 10k/10k workspace, concurrency 1,2,3,5,10, every
  configuration 47 x `200` / 58 x `429` / 0 x `5xx`, 0 restarts, 0 `OutOfMemoryError` lines:

  | App configuration | RSS before reads | RSS / HWM after reads |
  |---|---:|---:|
  | baseline (run A / run B) | 417 / 428 MiB | 494 / 519 and 506 / 521 MiB |
  | `-XX:MaxDirectMemorySize=64m` | 425 MiB | 495 / 521 MiB |
  | `MaxDirectMemorySize=32m` + `-Dio.netty.maxDirectMemory=32 MiB` | 427 MiB | 507 / 520 MiB |
  | `MALLOC_ARENA_MAX=2` (run A / run B) | 243 / 241 MiB | 419 / 419 and 379 / 418 MiB |
  | `MALLOC_ARENA_MAX=2` + `MaxDirectMemorySize=64m` | 241 MiB | 372 / 418 MiB |
  | shipped image (`ENV MALLOC_ARENA_MAX=2`, no overrides) | 242 MiB | 405 / 414 MiB |

  The arena cap lowers the high-water mark by ~100 MiB (HWM ~520 -> ~418 MiB, ~94 MiB under the
  limit instead of ~0) with the same status mix and no latency penalty (K=1 p50 per endpoint
  within the run-to-run noise of 230-470 ms; K=10 `entities/graph` p50 ~160-170 ms vs ~220 ms).
  Direct-buffer caps changed nothing (the numbers are run-to-run noise of ~+-10 MiB) and were
  NOT adopted: a cap that never binds only adds an `OutOfDirectMemoryError` path. The heap
  (`-Xmx256m`) and `READ_BUDGET_BYTES` are unchanged. Re-measure with
  `cd e2e && npm run probe:capacity -- --entities 10000 --files 10000 --app-env KEY=VALUE`
  (`e2e/README.md` "Capacity probe").
- **Prepaid admission, then draw-down (2.19.0).** (1) An up-front, all-or-nothing RESERVATION:
  `loadReadSet` reads `id` + `octet_length(document)` + `octet_length(team)` per row of the
  combined row set off PostgreSQL BEFORE a single document crosses the wire and calls
  `ReadLedger.Reservation.reserve(shownRaw x ENTITY_SHOWN_ROW_RATIO + targetOnlyRaw x
  ENTITY_TARGET_ADMISSION_RATIO)` — one CAS adds the whole estimate or refuses it, so nothing is
  held while a read is being refused. The ratios are measured on the Port sample (raw 1 +
  relations/team 1.52 + properties 4.51 = 7.03, rounded up to 8 for a SHOWN row, which is decoded
  in full and therefore prepaid; a target-only row is admitted at raw x 1 because most targets are
  never decoded, and the ones that are decode at up to raw 1 + relations/team 1.52 = 2.52,
  `ENTITY_TARGET_ROW_RATIO` = 3, charged as overflow — `EntityReadBudget.kt`, pinned by
  `EntityReadBudgetTest`). (2) A per-row, per-tier DECODE charge — `estimatedHeapBytes`
  of the relations/team (`WorkspaceRow.skeleton()`) or additionally the properties
  (`WorkspaceRow.full()`), once each, memoized — that DRAWS DOWN the prepaid pool: the shared
  total does not move. Only the excess beyond the pool is reserved on top (and can still be
  refused). N concurrent whole-workspace reads therefore admit exactly floor(capacity /
  estimate) and the rest answer `429` at once, instead of every read discovering the overflow row
  by row and all of them failing. Everything releases at once via
  `ReadLedger.Reservation.close()`.
- **Refusal semantics.** `ReadBudgetExceeded.ownRequest` distinguishes "this read's own combined
  estimate is too large to ever fit" (`true` — a `400`, or `EntityQueryInvalidException`
  `WORKSPACE_TOO_LARGE` for `graph`) from "other in-flight reads currently hold the room" (`false`
  — a `429`, the same shape as the entity-query evaluation permit's contention response). `list`/
  `read` throw a plain `BadRequestException`/`TooManyRequestsException` (`throwReadBudgetHttp`);
  `graph` throws the query-shaped `EntityQueryInvalidException`/`TooManyRequestsException` pair —
  WITH or WITHOUT a `query` present, since the plain graph is itself an unpaged, workspace-scale
  read. There is deliberately NO server-side wait queue: the admission read runs inside the read
  transaction, so a queue would hold pooled connections (the repo already chose `tryAcquire` for
  the entity-query permits); waiting belongs on the client, which retries a `429`
  (`web/CLAUDE.md`). Each refusal logs one operational INFO line (`.claude/docs/observability.md`
  "Not audit events"); nothing is audited.
- **Writers are exempt.** `create`/`update` pass `reservation = null` through `loadReadSet` —
  already serialized one-at-a-time by the V28 two-table lock (`.claude/docs/persistence.md`), so
  admission control adds nothing there; only the six READ paths listed above (2.5.0 adds
  `errors`, the Port-world Errors report — `.claude/docs/port-data-model.md` "Computed-property
  health") open a `readLedger.open()` reservation.
- **Documented residuals.** (1) A single `WorkspaceRow.full()` decode is charged transiently but
  its memoized field lives for the reservation's whole lifetime (the request), so a graph read's
  peak includes EVERY shown row's `properties` even though only one is "in scope" at any instant
  — which is exactly what the shown-row ratio prepays. (2) A workspace whose rows are unusually
  DENSE JSON (deeply nested, many short keys — `estimatedHeapBytes`'s per-scalar/per-entry
  overhead dominates) decodes past its prepaid estimate; the OVERFLOW is still charged at the
  estimated heap cost (and refused when it no longer fits), never silently admitted. With the
  calibrated ratios the overflow is ~zero for sample-shaped data; it is the one place a read can
  still fail after admission. (3) A `team` filter (REST and integration alike)
  runs its Inherited-ownership prologue (`inheritedTeamMatches` → `rowLookupFor` → `loadReadSet`)
  before the main read set on the SAME reservation, so such a read is a bounded two-step: the
  prologue `reserve`s its lookup targets and charges the skeletons it decodes, then the main read
  `reserve`s its own set — a deliberate double reservation that favors refusing sooner. The
  prologue's scan of the Inherited blueprints' own rows decodes each row transiently and retains
  only `(id, effective team)` pairs. (4) A target-only row whose `properties` are later decoded
  (an aggregation or mirror reaching into it) is charged beyond its admission as overflow.
  (5) The reservation closes when the service call returns; the RESPONSE is serialized by Ktor
  AFTER that, from the returned DTO, so a slow client keeps the response tree (the catalog
  `export` and graph payloads are the largest) alive on the heap beyond what the ledger counts —
  the response-encoding slack the "Heap layout" bullet leaves headroom for, not bounded by the
  ledger. The catalog `export`, whose response is the whole workspace, is therefore admitted at
  `CATALOG_EXPORT_FOOTPRINT_RATIO` (×6) rather than ×3: the 2026-10-25 capacity probe showed three
  ×3-admitted exports of a 10k-file workspace exhausting the heap (one `500`).

Rule of thumb: change `READ_BUDGET_BYTES` together with `-Xmx` (`server/build.gradle.kts`)
— raising one without the other either starves ordinary requests of heap or lets the ledger admit
more than the heap can actually hold.

**Whole-workspace content reads are admitted through the one read ledger** (2.19.0).
- **Applies when:** adding or changing any read that materializes every active content row of `catalog_files` or `entities` (or a filtered subset) in application memory — the catalog `graph`/`errors`/`export` and the entity `graph`/`errors` paths today. The entity `list`/`read` paths reserve their lookup-target rows through the same ledger.
- **Requirement:** the read opens a `ReadLedger` reservation (`infra/concurrency/ReadLedger.kt`) and reserves its whole conservative estimate in ONE all-or-nothing step, inside its read transaction and before any document crosses the wire; a refusal is `throwReadBudgetHttp` — `400` when the read is too large on its own, `429` when other reads hold the room — and audits nothing (one operational INFO line). The catalog estimate is `SUM(octet_length(content))` x `CATALOG_READ_FOOTPRINT_RATIO` (`catalog/CatalogFileService.kt`'s `readWorkspace`); the entity estimate is the per-row ratios above. One ledger for the whole process: the composition root (`infra/db/Database.kt`) passes the SAME `ReadLedger` to both services. The conversion export (`conversion/ConversionExportService.kt`) opens ONE reservation and holds it across everything that keeps its decoded snapshot alive — the entity read (`entities/EntityExportRead.kt`: the Entity graph's `shownScope` + `loadReadSet`, shown rows of mapped blueprints prepaid at the graph ratio, other context rows at the target ratio), the catalog read when merging (`CatalogFileService.readActiveSources`, narrowed to the identities the export will emit, at the export footprint ratio), the conversion and the response encoding — so the two reads' costs ADD on that reservation (a ledger that fits the larger read but not their sum refuses the merge), and it is released only when the answer is built; it has no ledger of its own. The conversion import (`conversion/ConversionImportService.kt`) reads only identities outside the ledger (`CatalogFileService.readIdentities`, `EntityService.importSnapshot`) and admits its merge read (`entities/EntityMergeRead.kt`, at most 200 rows at the shown-row ratio from their known stored sizes) on its OWN single reservation, held until the response body is encoded.
- **Reference:** the bullets above; `.claude/docs/persistence.md` "Catalog whole-workspace reads (2.19.0)".
- **Enforcement:** `CatalogFileReadBudgetTest`, `ReadLedgerTest`, and the entity suites (`EntityReadBudgetTest`, `EntityQueryRouteTest`, `EntityErrorsTest`, `OntologyIntegrationFilterBudgetTest`), and `ConversionRoutesTest` for the conversion export (400 own-request, 429 contention, and the two reads ADDING on one reservation) and `ConversionImportRoutesTest` for the conversion import (its merge read, the conversion and the response encoding share one reservation).
- **Exception:** writers under a lock (`create`/`update` pass no reservation), the bounded page itself of a paged list (its page is capped by `pageSize`; only its lookup targets are reserved), identity-only reads (the telemetry storage collector's `count(*)` and catalog size reads, and the conversion export's `CatalogFileService.readStoredRevisions` id + revision lookup, select no content column, so they reserve nothing), and registries capped at <= 200 rows.

### Telemetry

The in-app telemetry sampler (`.claude/docs/observability.md` "Telemetry") stores figures about the process in
`telemetry_processes`/`telemetry_samples`; the panel's ADMIN-only read API (`GET /api/v1/telemetry/current` and `/history`, whose
authorization rule lives in `authorization.md` and whose shapes in `observability.md` "Telemetry", "Read API") is the only reader
besides the database itself.

- **Applies when:** adding or changing a telemetry metric, label, collector or seam.
- **Requirement:** what is recorded is numbers and fixed-vocabulary labels only - the process identity (pod/container name, JVM
  start, optional build version/commit), memory and CPU figures, the pool, per-route request counts, latencies, 5xx and 429 counts,
  pressure figures (read-ledger bytes, permits, queue depths, refusal counts), the database and table-group sizes, and the active
  row counts of six workspace tables plus all rows of `catalog_file_events`. The never-recorded list lives only in
  `.claude/docs/observability.md` "Telemetry". Cardinality is bounded by construction, because a registered metric name is never
  evicted and a label is a stored row: route labels come from the `isLabelled` shape rule over the real routing tree
  (`observability.md` lists them) and `*`; Ktor's per-route instruments are admitted by `TelemetryRegistry` only for ROUTED calls
  and never when the name holds a `?` (a flood of unrouted or rate-limited URLs registers nothing), capped at 2,000 names; table
  labels come from the fixed `COUNTED_TABLES` and group names, never from input; `Reading` truncates every label to 120
  characters. The pressure seams count, they never decide: `ReadLedger.snapshot()`/`refusals`, `EntityService.pressure()`/
  `queryRefusals`, `JqEvaluator.poolStats()`, `UrlFetcher.poolStats()` and `IntegrationPressureKey` (`IntegrationLimits.inUse`/
  `refusals`, `IntegrationRetainedLedger.inFlightBytes`) add counters and read-only snapshots; no limit, executor size or
  accounting moves to add one, and reading one cannot admit, refuse or queue anything.
- **Reference:** `.claude/docs/observability.md` "Telemetry" (the collectors and the label rule), `persistence.md` "Telemetry (V46)".
- **Enforcement:** `LatencyCollectorTest` (the label rule against the real routing tree, no query string in a label, templates only),
  `TelemetryRegistryTest` (the `?` and cap bounds), `StorageCollectorsTest` (every table in exactly one group; the counted tables are grouped),
  `PressureCollectorTest` and the seam tests (`ReadLedgerTest`, `IntegrationLimitsTest`, `JqCalculationTest`, `UrlFetchTest`);
  manual review for the rest - in particular that no collector selects a content column, and that a new metric or label is a
  number or a fixed-vocabulary label.
- **Exception:** none.

The read API's throttle is its own rule:

- **Applies when:** adding or changing a telemetry read route, or the `telemetry` rate bucket.
- **Requirement:** every telemetry read is mounted `authenticate { rateLimit(RateLimitName(TELEMETRY_RATE_LIMIT)) { … } }` - the
  bucket INSIDE `authenticate`, so its key is the verified user id (`user:<id>` from the JWT's `userId` claim), never the client
  address: an administrator behind a shared proxy keeps their own allowance and cannot spend someone else's. The bucket is 30 requests
  a minute per user in every mode, shared by all telemetry reads (the panel polls `current` every 60 s and reads one history per
  open section), is registered in `auth/AuthRoutes.kt`'s single `install(RateLimit)` (Ktor installs the plugin once) and answers the
  generic `429` problem. It runs before the ADMIN guard, so a non-ADMIN's refused requests spend their own bucket too. The address
  fallback of the key is unreachable behind `authenticate` and only keeps a mis-mounted bucket from throwing.
- **Reference:** `auth/AuthRoutes.kt` (`TELEMETRY_RATE_LIMIT`), `telemetry/TelemetryRoutes.kt`, `authorization.md` (the route bullet).
- **Enforcement:** `TelemetryRoutesTest` (both routes share one bucket of 30; the bucket is per user, not per address; a USER's 403s
  spend their own), `RateLimitResponseTest` (the exhausted bucket answers a problem+json `429`).
- **Exception:** none.

### Not yet ported from Lettuce

Each of these is a fully worked-out Lettuce subsystem (implementation + tests + docs); port it rather than redesigning, and restore its section of Lettuce's security doc alongside:

- **Field encryption at rest** (`infra/crypto/FieldCipher.kt`, `DATA_ENCRYPTION_KEY` + fail-closed burned-key check, the `EncryptedAtRest` rotation-backfill registry, never filter/sort encrypted columns in SQL).

### Port GraphQL integration (2.7.0)

The separate machine API is disabled by default and uses dedicated revocable keys. Its
limits apply in addition to existing entity read/computation budgets; it never loads private
saved queries. Exact credential, admission, parser, execution, and response rules and their
regression boundaries live in [integration-api.md](integration-api.md).
