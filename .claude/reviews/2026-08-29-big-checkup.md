# Big check-up — 2026-08-29

Full-repo health audit before the UI/UX-polish phase: fresh gate baseline, four parallel
deep reviews (registry-family consistency, security posture vs docs, docs-vs-code drift,
frontend patterns), the `/api-review` two-pass, and a dependency/platform audit. Policy:
fix small + report big; dependencies audit-only.

## Baseline (all green before any change)

Server 264/264 (Kover 96.68 line / 73.73 branch), detekt 0, web 422/422
(97.84/95.38/92.57/91.12) + lint + knip + build, e2e 33/33 + typecheck + 17 scenarios in
sync, Spectral 0 errors (only registered gaps), npm audit 0 vulnerabilities (web + e2e),
conformance 159/211 declared pairs exercised, working tree clean and in sync with origin.
Zero TODO/FIXME/@Suppress/skipped-test debt; no stray artifacts.

## Fixed in this check-up

**Security hardening** (all verified findings from the posture audit):

- **Login timing oracle** — unknown emails short-circuited before bcrypt; now they pay a
  full discarded verify against `TIMING_EQUALIZER_HASH` (`auth/Passwords.kt`).
- **Request-body ceiling** — `RequestBodyLimit` 10 MiB in `plugins/Http.kt`, 413 problem
  body via `plugins/ErrorHandling.kt`, declared on the catalog-file create, tested.
- **jti required** — a correctly-signed token without a `jti` (un-blocklistable) is now
  rejected by both the access verifier and `/refresh` (reason `malformed`); tested.
- **MFA challenge CAS** — single-use is now a compare-and-remove (a concurrent duplicate
  submission loses) and the attempt bump is atomic (`computeIfPresent`).
- **SSRF ranges** — the fetch guard additionally blocks CGNAT `100.64.0.0/10`,
  `192.0.0.0/24`, benchmarking `198.18.0.0/15`, and NAT64 `64:ff9b::/96` embedding a
  non-public IPv4; matrix-tested.
- **X-Request-Id cap** — client-supplied ids honored only when ≤64 URL-safe chars.
- **Reset audit split** — `password_reset.store_failed` (email delivered, hash NOT stored)
  now distinct from `send_failed`.

**Consistency (registry family + SPA)**:

- Tags update now checks existence before the tag-claim check (404 like every sibling,
  never a 409 on a missing row); tags PUT rename-onto-active-name 409 pinned.
- The "— define/adjust it on the X page" hint now appears on EVERY enforcement 400
  (namespace, label kind/value, tag kind, annotation kind joined types/lifecycles).
- All six editor pickers share one presentation: never disabled, load-failure as a hint,
  "none defined" hint only after loading resolves (no false flash), and the namespace
  picker gained its missing `noNamespacesDefined` hint.
- The four CRUD-registry save mappers gained the 403 `saveForbidden` vocabulary (the
  dictionary mappers already had it); tag-category name validation mirrors the server's
  control-char rejection.
- Seven concatenated aria-labels became interpolated i18n keys (EN output unchanged;
  Polish gets real word order); the Users/registry action-column headers gained accessible
  names (`common.table.operations`); the Namespaces page/nav icon is no longer the Tags
  icon; the catalog detail query key moved under the documented `["catalogFiles", …]`
  prefix; the dead `catalog.field.createdBy` key deleted (EN+PL); the two file-level
  eslint-disables gained their justifying comments.

**Conformance + floors + docs**:

- All 11 real conformance gaps closed with tests (the features PUT 401 anomaly, the MFA
  400/429, users DELETE 400, check/graph/export 400s, the four registry PUT 400s) —
  171/212 declared pairs now exercised; the only remaining gaps are every operation's
  deliberately-unexercised 500 (documented in testing.md).
- Coverage floors raised (rise-only): Kover 95→**96** line, 70→**73** branch; vitest
  statements 94→**95**, branches 89→**91**.
- Doc drift fixed: CLAUDE.md `Sql.kt` entry (dropped `requireValidReferences`, added
  `jsonArrayContains`) + infra module order (Mail first); persistence.md composition root
  (8 services, not 6); list-endpoints.md parser list; testing.md harness catalog
  (TestLifecycles/TestAnnotationKeys/TestEntityTypes/TestRefTargets/overwriteContent) +
  floors; web/CLAUDE.md — feature flags no longer "not yet ported" (they shipped), a
  Feature-flags page section, the 13-file i18n list; observability.md appender
  attribution + the new audit events; API-GUIDELINES known-gaps register Toadie-ized
  (the lost-update inventory described Lettuce's resources).

## /api-review result

Pass 1 (Spectral): **0 errors**; 2 warnings + 54 hints, all registered gaps. Pass 2
(checklist): all applicable rules pass — flat camelCase DTOs, the one list envelope,
201+Location on all six creates, 202 on the reset, epoch-millis instants, guard-before-
validate, bearer-only auth, 23505→409 central mapping, spec-with-route same-commit +
conformance-on. Registered gaps stand as registered (ETag/304, Cache-Control policy,
Retry-After/RateLimit-*, X-Request-Id echo, Idempotency-Key, HTTP/2, x-sla/ToS,
inline error duplicates). One checklist caveat: API-CONF "drift fails CI" is true only
locally — see CI below.

## Report items (the "big" backlog — user opt-in)

1. **No push/PR CI.** The only workflow is manual-dispatch e2e. Recommendation: one
   workflow running `./gradlew build` (Docker is present on ubuntu-latest for
   Testcontainers) + `cd web && npm ci --legacy-peer-deps && npm run lint && npm run
   knip && npm run test:coverage && npm run build` on push/PR; keep e2e manual.
2. **Dependency refresh** (audit-only this round; stack is otherwise current):
   Exposed 1.3.1 → **1.5.0** (the 1.4.0 R2DBC stall that forced the pin-back deserves a
   retry), Flyway 13.3.0 → 13.4.0, Gradle 9.7.0 → 9.7.1, mailpit v1.30 → v1.31,
   `@types/node` 24 → 26 (both workspaces), TypeScript ~6.0.3 (web) vs ^7.0.2 (e2e) —
   align, plus a handful of patch-level npm bumps. Deliberate pins stay right: detekt 2.x
   is still alpha-only; no non-alpha OTel-Ktor instrumentation exists. 0 npm vulns.
3. **Dockerfile JDK split** — builds on temurin-21-jdk, runs on temurin-25-jre. Works,
   but either align both at 21 or comment the deliberate newer-runtime choice.
4. **`CatalogFileFormFields.tsx` (626 lines)** — clean 3-way seam (registry pickers /
   labels+annotations row fieldsets with near-duplicate row logic / the rest). Fold the
   split into the next change that touches it; not worth a standalone refactor.
5. **Noted, no action**: the per-account lockout is a deliberate account-DoS trade-off
   (documented); DNS-rebinding resolve-vs-connect gap stays the accepted SSRF residual;
   CORS `allowHost` trusts both http and https schemes when `CORS_ALLOWED_HOSTS` is set
   (single-origin default makes it moot); `hasFeature()` awaits its first area-gating
   consumer; `isLastAdminConflict` sniffs the 409 detail substring (commented, covered);
   a forced sign-out keeps the query cache until the shell logout path clears it
   (registry caches are user-independent); the `core` module is a 25-line KMP placeholder.

## Verdict

The codebase is in unusually good health: the copy-adapt discipline across the six
registry features held (no correctness bugs found in the family), the security docs are
byte-accurate to the code, and the only real vulnerability-class finding (the login
timing oracle) was low-severity and is fixed. The drift found was doc-lag behind the
three newest features plus the one stale "not yet ported" bullet — all fixed here.
