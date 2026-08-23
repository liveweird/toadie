# Quality checkup — 2026-08-23

Full-repo audit after the first eight feature slices (0.9.0): three parallel deep passes —
server Kotlin, web SPA, docs/spec/coverage — every finding verified against both sides of the
claim. Scope decision: **strictly behavior-preserving fixes only** (wire contract, DB schema,
rendered UI identical); anything that would change observable behavior is listed under
[Opt-in follow-ups](#opt-in-follow-ups) instead of being fixed silently.

Legend: **S** server, **W** web, **D** docs/spec, **C** coverage. Status: `fixed` (this pass)
/ `follow-up` (needs an explicit opt-in) / `noted` (accepted with rationale).

---

## High severity

| ID | Finding | Where | Status |
|----|---------|-------|--------|
| S-H1 | bcrypt (cost 12, ~200–400 ms CPU) runs on the request coroutine's dispatcher on every login/password change — no `Dispatchers` offload anywhere in the auth path | `auth/Passwords.kt`, call sites in `AuthRoutes.kt:133`, `UserRoutes.kt:100,216` | fixed |
| D-H1 | persistence.md claims "the skeleton has no DELETE endpoint yet" while two DELETE endpoints ship (and the same doc says otherwise 5 lines up) | `.claude/docs/persistence.md:28` | fixed |
| D-H2 | e2e README claims "the current two specs are both read-only, so no collision exists" — there are 9 specs, 6 of which own mutable server-side state | `e2e/README.md:46-48` | fixed |
| B-H1 | `GET /catalog-files/export` declares no `400`, but its `namespace` param goes through the strict reader that 400s a repeated key (graph/list declare it) | `openapi/documentation.yaml` export path | fixed |
| C-H1 | The documented guard-before-body idiom on `POST /users` ("a non-admin's malformed body stays 403") has no test — the 403 pair is never exercised | conformance coverage report | fixed (test added) |

## Medium severity

### Server — correctness / performance (behavior-preserving)

| ID | Finding | Where | Status |
|----|---------|-------|--------|
| S-M1 | Last-admin protection is a TOCTOU: read, `countActiveAdmins`, and the mutation run in three separate transactions — two concurrent demotes can both pass the count | `UserRoutes.kt:132-134,181-183` + `UserService.kt` | fixed |
| S-M2 | `importOne`'s `catch (e: Exception)` swallows `CancellationException` — a cancelled request becomes an ERROR row and the batch keeps running | `CatalogFileRoutes.kt` | fixed |
| S-M3 | Logout's nested `runCatching` swallows every `Throwable` silently — an unparsable body skips refresh revocation indistinguishably from "none sent" | `AuthRoutes.kt:200-206` | fixed |
| S-M4 | `check()` (the per-keystroke editor endpoint) loads and JSON-decodes every active file's full `content` just to build an identity set — the three identity columns are already denormalized | `CatalogFileService.kt:142-145,169-173` | fixed |
| S-M5 | `export()` applies its namespace filter in Kotlin after loading the whole workspace | `CatalogFileService.kt:160-167` | fixed |
| S-M6 | OTel `spanKindExtractor` maps POST→PRODUCER / else→CLIENT on **server** spans (unedited template default) | `plugins/OpenTelemetry.kt:19-25` | fixed |
| S-M7 | The Dropwizard `Slf4jReporter` is started and never stopped — a leaked thread per `testApplication` (96 of them) and beyond `ApplicationStopped` | `plugins/Monitoring.kt` | fixed |
| S-M8 | `isRevoked` DB round-trip inside JWT validation on every authenticated request | `plugins/Security.kt:76` | follow-up (a cache delays revocation visibility — a security-behavior change) |
| S-M9 | `REFRESH_REJECT_MESSAGES` expresses one reason as *absence from the map* — any typo in a reason silently yields the password-change wording | `AuthRoutes.kt:56-62` | fixed |

### Server — DRY / SRP

| ID | Finding | Where | Status |
|----|---------|-------|--------|
| S-M10 | Six copy-pasted `1..MAX` length checks | `CatalogFile.kt` | fixed (`requireLength`) |
| S-M11 | The entity-ref grammar is parsed twice (`validateEntityRef` vs `parseRef`) — a grammar change must land in two files or validation and cross-check diverge | `CatalogFile.kt` / `CrossCheck.kt` | fixed (validate over `parseRef`'s output) |
| S-M12 | Graph duplicates CrossCheck's target-resolution step despite its KDoc claiming shared machinery | `Graph.kt:98-106` vs `CrossCheck.kt:156-171` | fixed (shared `resolveTarget`) |
| S-M13 | User-create re-derives wire roles by hand instead of `toResponse` | `UserRoutes.kt:110-113` | fixed |
| S-M14 | The sortable whitelist is declared twice per feature (route set + service columns), agreement enforced only by a runtime 500 | routes vs `SORTABLE_COLUMNS` | fixed (single source) |
| S-M15 | `CatalogFile.kt` (484 lines) mixes wire DTOs, sanitizer, the per-kind table framework, the validation engine, and response DTOs | `catalog/CatalogFile.kt` | fixed (split `CatalogFileValidation.kt`) |
| S-M16 | `importOne` is import domain-orchestration living in the routes file (and forces `isUniqueViolation` to be `internal`) | `CatalogFileRoutes.kt` | fixed (moved to service) |
| S-M17 | The user-update handler hand-builds its audit delta imperatively — the only imperative audit construction in the repo | `UserRoutes.kt:123-170` | fixed (extracted) |
| S-M18 | `checkDocument`'s nullable counter-callback parameter smuggles a count to one caller | `CrossCheck.kt:134-153` | fixed (`DocumentCheckResult`) |

### Server — dead code

| ID | Finding | Where | Status |
|----|---------|-------|--------|
| S-M19 | `optionalUInt/Long/Boolean` have zero production callers — contradicting the file's own port-on-demand policy note | `infra/paging/QueryParams.kt:26-37` | fixed (deleted) |
| S-M20 | `requireValidReferences` has no production caller | `infra/db/Sql.kt:61-68` | fixed (deleted + doc) |
| S-M21 | `TestBlocklist` is never referenced by any test, yet testing.md advertises it | `TestEnvironment.kt:191-193` | fixed (deleted + doc) |

### Server — test-harness DRY

| ID | Finding | Where | Status |
|----|---------|-------|--------|
| S-M22 | Four byte-identical `userClient()` helpers; `LogCapture`+detach written 6×; `hasLong` defined twice in one file; the login POST block hand-written 31× across 11 files; catalog fixtures parked in a test class file consumed by 5 others | test suite | fixed (harness helpers + `CatalogFixtures.kt`) |

### Web

| ID | Finding | Where | Status |
|----|---------|-------|--------|
| W-M1 | 9 i18n key families duplicated across areas (×2 languages) despite the documented `common.*` rule; 2 orphan keys no code references | `locales/{en,pl}/*.json` | fixed |
| W-M2 | `PaginationBar.rowsPerPageLabelKey` prop exists only to choose between two identical strings | `components/PaginationBar.tsx` | fixed |
| W-M3 | ~150 lines of copy-pasted test scaffolding (TOKEN_KEY×12, fetch-mock beforeEach×10, `PathProbe`×5, `setupMocks`×2) | `*.test.tsx` | fixed (shared helpers) |
| W-M4 | The one-time password reveal modal duplicated verbatim | `Users.tsx` vs `CreateUser.tsx` | fixed (`OneTimePasswordModal`) |
| W-M5 | The edit-page load/not-found/back block duplicated | `EditCatalogFile.tsx` vs `EditUser.tsx` | fixed (shared component) |
| W-M6 | The catalog editor Grid+Paper+preview shell duplicated | `CreateCatalogFile.tsx` vs `EditCatalogFile.tsx` | fixed (`CatalogFileEditor`) |
| W-M7 | `catalogImport.KINDS` re-lists `ENTITY_KINDS` verbatim with no strict-mirror rationale | `utils/catalogImport.ts:25` | fixed |
| W-M8 | `Users.tsx` (339 lines) carries the whole reset-password feature bolted onto the list template | `pages/Users.tsx` | fixed (extracted) |
| W-M9 | `CatalogFileFormFields.tsx` (351 lines): 7 fieldsets + 2 closure renderers + 5 visibility ladders in one component | `components/CatalogFileFormFields.tsx` | fixed (split into fieldset components) |
| W-M10 | The same 409 handled two ways — `EditUser` sniffs the English detail for "administrator", `Users` maps every 409 to last-admin | `EditUser.tsx:16-19` / `Users.tsx:274-280` | fixed (shared helper; typed error code stays a follow-up) |
| W-M11 | Import-page comment claims invalidation reaches "list, identities, graph, cross-check", but `["catalogFiles"]` cannot match `["crossCheck"]` / `["catalogGraph"]` / `["catalogFileCheck"]` | `ImportCatalogFiles.tsx:78-79` | comment corrected; the re-keying itself is a follow-up (changes cache/refresh behavior) |
| W-M12 | `ReferenceCheckPanel` keys the query on the full serialized document — unbounded cache breadth, and re-parses what it just stringified | `ReferenceCheckPanel.tsx:25-26` | follow-up (cache-behavior change) |
| W-M13 | Export reads the un-debounced namespace filter while the table queries the debounced one — a click within 300 ms exports a different slice than shown | `CatalogFiles.tsx:103` | follow-up (behavior change, arguably a bug — recommend) |
| W-M14 | Export/download buttons have no loading state; double-clicks duplicate work; graph page has no first-load indicator | `CatalogFiles.tsx`, `RenderGraph.tsx` | follow-up (UI change) |
| W-M15 | Download/export failures always render the "network" message even for 403/404 | `CatalogFiles.tsx:88-108` | follow-up (UI text change) |
| W-M16 | Graph node: `aria-label` on a plain `div`, click-only, no keyboard path — the app's one a11y gap | `CatalogGraphNode.tsx:29-41` | follow-up (DOM/interaction change) |
| C-M1 | 21 web modules (all four hooks included) have no direct test — covered only transitively through pages, contradicting the co-location convention | `web/src` | fixed pragmatically (hooks + logic-bearing components; presentational ones stay transitive, accepted below) |

### Docs / spec (medium)

| ID | Finding | Where | Status |
|----|---------|-------|--------|
| D-M1 | CLAUDE.md package tree omits `infra/validation/` | `CLAUDE.md` | fixed |
| D-M2 | persistence.md composition-root list omits `CatalogFileServiceKey` | `.claude/docs/persistence.md:6` | fixed |
| D-M3 | authorization.md still calls user CRUD a "future management surface" | `.claude/docs/authorization.md:5` | fixed |
| D-M4 | authorization.md's "401 sweep" claim has two holes: `POST /users` and `PUT /users/{id}` have no 401 test | `.claude/docs/authorization.md:16` | fixed (tests added) |
| D-M5 | web/CLAUDE.md still frames the SPA as "the skeleton frontend" with 3 locale areas (7 exist, 12 routes ship) | `web/CLAUDE.md:3,68` | fixed |
| D-M6 | e2e README's residue-sweep rule still says "when creation surfaces arrive" — four creating specs ship, no sweep exists | `e2e/README.md:52-55` | fixed (rule restated as satisfied-by-self-cleanup) |
| D-M7 | e2e README's axe line omits `/cross-check` + `/render` which the spec scans | `e2e/README.md:67-69` | fixed |
| B-M1 | createCatalogFile spec description: "a Backstage Component entity", cross-checking "a future feature" | `documentation.yaml:349-353` | fixed |
| B-M2 | Spec `info` blurb predates users/cross-check/graph/round-trip/fetch | `documentation.yaml:8-13` | fixed |

### Coverage (medium)

| ID | Finding | Where | Status |
|----|---------|-------|--------|
| C-M2 | Never-exercised real pairs: users POST 401/403, PUT 401/400/404; fetch route 200/502; refresh 429; negative-id 400 beyond one path | conformance coverage report | fixed (tests added) |
| C-M3 | `ImportResultStatus.ERROR` and `AutoHeadResponse` have no test at all | server suite | fixed (NUL-byte 22021 row; HEAD probe) |

## Low severity (grouped)

**Server, fixed:** `hasSqlState`/`hasContentConvertCause` chain-walk dedupe; 7× hand-written
not-found throws → `orNotFound`; `ConflictException.instance` param never set (dropped);
`validate` alias; `COMPONENT_KIND` inlined; `orVanished.phase` kept (cheap, documented);
public `val database` → private ×3; empty `swaggerUI {}` lambda; redundant
`@Suppress("LongMethod")`; Lettuce-copied detekt.yml comments rewritten; `LoginThrottle`
triple `clock()` read; blocklist row-fetch → `count()`; `EntityProfile` rebuilt by
constructor inside a `copy()` chain; list `total` counted on the join; `BootstrapTest`
seed-restore and startup-fails helpers; V1 `BIGSERIAL` vs Exposed `UIntIdTable` documented
as the V1 exception. **Server, follow-up:** audit field naming drift (`userId`/`byUserId`,
`from`/`to` vs `nameFrom`/`nameTo`) and roles audited as-requested-not-as-stored (log-schema
changes); PUT /users validation order (409-before-400 differs from the sibling handlers — a
status-code change); refresh bucket hardcoded 30/min (new config); CSRF/behindProxy opposite
boolean idioms (noted); auth 200s without explicit status (fixed — cosmetic);
`Import.kt.message` vs `ProblemDetail.detail` naming (noted as deliberate: row results are
not RFC 7807); JS assets uncached (`CachingHeaders` covers only CSS — header change);
per-table 409 details (wire change); SecurityHeaders' header-presence sentinel (kept — it
guards double-install, comment clarified).

**Web, fixed:** orphan keys deleted; `usePagedSort.initialSortDir` / `ConfirmDeleteModal.confirmLabel`
/ `RevealablePassword.copyLabel`-fallback dead params; `CharCount` double-guard; Lettuce-inherited
comments in `api/http.ts` retargeted; `toCatalogFileRequest` computed twice per render;
`EMPTY_CATALOG_FILE_FORM` shared-reference hazard → factory; theme-covered `Table` props
dropped; `RELATION_FIELDS`/`SpecFieldName` exported (three hand-written unions collapsed);
`useCatalogIdentities` unread flags; `usePagedSort` eslint-disable pair removed;
`ChangePassword` constants moved to `utils/userForm.ts`; `App.tsx` bare async `onClick`;
`CrossCheck` `isError` shadow rename. **Web, follow-up:** Login page hand-rolled status
ladder + stock-blue alert; `loadErrorMessage` adoption in the two edit pages (error-text
changes); `Users.tsx` roles-badge shows ADMIN for any non-empty roles array (rendering
change; today's data can't hit it — ADMIN is the only role); `parseCatalogYaml` on every
keystroke (debounce = timing change); `columnCount` literals (derive or test).

**Docs/spec, fixed:** `EntityKind` self-contradictory description; `ImportFileResult.fileId`
int64→int32 (generated type unchanged: `number`); `buildQuery` "future list wrappers";
axe spec adds `/users` + `/catalog-files/import`; both coverage-floor comment blocks were
stale (server actuals 95.7/74.1 vs commented 95.2/72.2; web 97.9/95.1/92.8/89.5 vs
97.0/94.8/91.8/87.8) — re-measured and floors raised.

## Accepted / noted (no action)

- The 405 response is deliberately un-specced (Ktor surfaces no `Allow` set) — documented.
- Three one-`install()` plugin files — the documented declarative-module idiom.
- Presentational components without direct tests (EmptyState, TableLoadingRow, cards) —
  transitively covered by page tests; the co-location convention now names this exception.
- `SPEC_KEYS`/`SPEC_ORDER` re-listing spec fields — the documented strict-mirror pair.
- DNS-rebinding TOCTOU in the URL fetch — documented accepted residual risk (security.md).

## What's genuinely good (verified, not vibes)

- **Error handling** is centralized and complete: RFC 7807 everywhere including the paths
  outside StatusPages, bodiless 405/429 completed, SQLSTATE mappings, the negative-id
  interceptor — no route hand-rolls an error body; the SPA renders `error.message` nowhere.
- **Validation is single-sourced** and enforced at route AND service with identical
  sanitize→validate order at all catalog entry points; the per-kind rules are data.
- **Security posture**: two fail-closed startup checks, the full SSRF guard chain with its
  residual risk documented rather than hidden, secrets never logged, uniform-response
  discipline against enumeration.
- **The list/form/page templates hold** — the two list pages differ only where the domain
  differs; error mappers are used by 9/11 pages; zero `useEffect` in any page; only two
  `useMemo`, both justified.
- **i18n and a11y discipline**: 311 EN keys with exactly 2 orphans, full EN↔PL parity
  gating, typed keys, no hardcoded UI text; skip link, labelled icon controls, one gap total.
- **The conformance harness** validates every test-client `/api/` interaction against the
  spec; route↔spec coverage is exactly 1:1 with no phantom paths; docs cross-references
  resolve with zero phantoms (every "covered by X" names a real test).
- **Docs are unusually close to the code** — of hundreds of checked claims, the drift list
  above is the whole of it; the migration catalog and audit-event list matched perfectly.

## Outcomes (2026-08-24)

Every item marked `fixed` above landed in the eight checkup commits that follow this file in
git history (server S1/S2/S3, web W1/W2, docs+spec D1, e2e+floors E1); `follow-up` items are
unimplemented by design — each changes observable behavior and awaits an explicit go-ahead.

Final state, all gates green:

- **Server**: 147 tests (was 143 pre-checkup: 5 dead-helper tests removed with their subjects,
  9 coverage pins added), detekt zero findings, conformance coverage **75 of 98** declared
  (operation, status) pairs exercised (was 63; the remainder is dominated by the 20
  deliberately-unreachable 500s). Kover floors raised: line 92→**94** (actual 96.2), branch
  68→**70** (actual 73.5).
- **Web**: 301 tests across 40 files (was 232/29), lint + knip zero findings. Vitest floors
  raised: lines 95→**97** (actual 98.8), statements 92→**94** (96.4), functions 90→**92**
  (94.5), branches 84→**89** (91.9).
- **e2e**: 18 tests green (the axe smoke now scans 7 authenticated pages — `/users` and
  `/catalog-files/import` joined clean), scenario parity intact.
- Behavior preservation was proven mechanically: the OpenAPI conformance layer validated every
  server interaction against the unchanged wire contract, and the full e2e suite ran against
  the rebuilt containers. One regression was caught mid-flight by exactly that net (a
  narrowed logout catch missing the body-less case) and fixed before commit.
