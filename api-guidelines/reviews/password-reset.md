# Reset-link API review

Scope: changed `POST /api/v1/password-reset`, new `POST /api/v1/password-reset/confirm`,
their DTOs, route/service behavior, and first-party SPA. This is a changeset review, not
a fresh certification of the whole API.

## Findings / explicit deviations

1. **Medium — API-VER-001/002:** the email workflow changes meaning within `/api/v1`:
   recipients choose a password through a link instead of receiving a generated password.
   The request body and 202 remain compatible, but the workflow is not additive. This is an
   intentional security remediation, documented in `HARDENING.md`; deploy server/SPA together
   and configure `MAIL_APP_URL`. No unsafe legacy reset implementation is retained.
2. **Low — API-AUTH-002:** literal header-only credential wording does not fit this public
   exchange's `{token,password}` JSON body (nor existing login/refresh exchanges). Protected
   APIs remain JWT-bearer-only. The grant authorizes no other operation and never travels in
   an HTTP path/query or browser storage; the email fragment is removed from history.
   Document this exception instead of claiming the literal rule passes. A future rulebook
   clarification should distinguish protected-resource auth from public credential exchanges.

No additional implementation defects found in the scoped review. Relevant implementation:
`server/src/main/kotlin/auth/PasswordResetRoutes.kt`, `PasswordResetService.kt`,
`PasswordResetEmail.kt`, and `web/src/pages/ConfirmPasswordReset.tsx`.

## Pass 1 — mechanical

`npm run lint:api`: 0 errors, 2 registered-gap warnings, 71 registered-gap hints;
the conformant reference fixture passes. `npm run check:api`: generated types match.
No rules were weakened. Existing whole-API gaps remain registered. This change closes
no-store/concurrent-write protection for reset only, not the whole-API register rows.

## Pass 2 — all 19 checklist items, scoped to this change

| Rules | Result | Evidence / applicability |
|---|---|---|
| API-STRUCT-003/004 | Pass | Flat camelCase request objects; no lists/references to embed. |
| API-RES-004/007 | Pass | GET loads the SPA only; consumption is POST. No PUT/DELETE added; deleted accounts cannot use grants. |
| API-VER-001/002/003/004 | Fail | Compatibility deviation above; strict unknown-body-member handling retained. No deprecated operation. |
| API-LIST-001/002 | N-A | No collection reads. |
| API-LIST-003/004/005 / API-NAME-004 | N-A | No sorting/filtering/search. |
| API-NAME-001 | Pass | `email`, `token`, `password`. |
| API-DATA-001/002/003 | N-A | No timestamp/decimal/enum wire fields; internal expiry is epoch millis. |
| API-OK-001..005 | Pass | Deferred request 202; completed mutation 204 without a body. Other operation classes absent. |
| API-ERR-002/003/006/007 | Pass | Uniform token 401, password/schema 400, configuration 503; no enumeration or duplicate-resource conflict. |
| API-ERR-004 | Registered gap | No new correlation-header implementation. |
| API-ERR-005 / API-SEC-003 | Pass | Errors declared, grant checked before password validation, byte ceiling, strict schemas, parameterized SQL. |
| API-AUTH-001..004 | Fail | API-AUTH-002 wording deviation above; explicit public security and least-privilege grant checks pass. |
| API-CACHE-001..004 | Pass | Reset no-store includes pre-handler failures; row lock, expiry/epoch recheck, and atomic consumption prevent competing writes. No cacheable reads/polling added. |
| API-RATE-001/002 | Registered gap | Request/confirmation buckets and problem 429 tested/documented; standard retry/rate headers remain a shared gap. |
| API-IDEM-001/002 | Pass | Documented single-use domain equivalent: replay cannot mutate twice. Lost-response recovery documented; no PUT/DELETE added. |
| API-SEC-001/002 | Pass | Production HTTPS/HSTS/trusted-proxy controls retained; configured origin, parameterized SQL, React escaping. |
| API-HTTP-001 | Registered gap | No edge protocol change. |
| API-META-001/002 | Registered gap | No SLA/terms introduced. |
| API-DOC-001/004 / API-CONF-001..004 | Pass | Contract/types/tests synchronized; shared errors, drift/runtime conformance gates, coverage report. Optional fuzzing not run. |

Totals: **10 pass, 2 fail (explicit deviations), 3 N-A, 4 registered gap**.
Full-suite runtime conformance: **53/53 operations, 222/279 operation/status pairs**;
confirmation covers 204, 400, 401, and 429. This does not claim every declared 500 was induced.
