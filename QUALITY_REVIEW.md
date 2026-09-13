# Toadie quality review — 13 September 2026

Reviewed `master` at `8668b0ba874727bb41ee43156d1cc42d6339883d` (v2.3.1). The repository was clean before and after the review. This was a read-only audit: no fixes, commits, pushes, migrations against the development database, or deployments were performed. Temporary regression probes used disposable Testcontainers databases.

The review covered backend and frontend behavior, Port and Backstage modelling, authentication, imports, graph/query execution, shared infrastructure, the OpenAPI contract, repository guidance, and CI. Independent reviewers covered the backend/API, Port ontology/query engine, and frontend; a review lead validated the findings and removed duplicates and unsupported hypotheses.

This document is a historical audit snapshot of the reviewed commit, not the current outstanding-work list. Later fixes do not rewrite its findings or verification results.

## Assessment

The modular monolith remains a reasonable architecture. Feature-local routing and services, shared paging/problem responses, pure validation/query components, and shared UI infrastructure provide a coherent base. The evidence supports targeted corrections rather than a broad rewrite.

Three high-priority issues need attention: private query-cache data crossing an automatic sign-out boundary, oversized identifiers retained by failed-login tracking, and graph materialization whose supported payload size exceeds the configured heap. There are also reproducible rename defects, import/query correctness issues, and a world-switch race reflected in current master's failed browser CI.

Priority P1 means address promptly; P2 is a substantive correctness or reliability fix; P3 is a smaller consistency or robustness improvement. Runtime reproductions and code-derived findings are distinguished below. No memory-exhaustion attack was attempted.

## Findings

### 1. P1 — Automatic sign-out leaves private cached data available to the next account

**Trigger and impact:** Account A loads private saved entity queries or lenses. A definitive refresh rejection signs A out. Account B signs in within the cache freshness window in the same SPA. The unpartitioned query cache can return A's private query text or filters without fetching B's data.

**Evidence:** [Automatic refresh rejection](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/web/src/api/http.ts#L143) clears session state and notifies auth listeners but does not clear the singleton QueryClient. [Login](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/web/src/pages/Login.tsx#L33) also does not clear it. [Saved-query keys](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/web/src/hooks/useSavedEntityQueries.ts#L5) do not include the user, and [registry queries](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/web/src/hooks/useRegistryQuery.ts#L29) remain fresh for five minutes. Explicit logout does clear the client; the automatic path differs.

**Validation:** Code tracing plus an isolated reproduction using the installed TanStack Query library: B's `ensureQueryData` returned a seeded A-private object, with B's query function never invoked. This was not a full browser account-switch reproduction.

**Suggested correction:** Centralize cleanup for every authentication-family transition and/or partition private cache keys by account. Test rejected refresh followed by another account's login using the same QueryClient. Include pending requests and stale logout completions in the lifecycle design.

### 2. P1 — Failed-login tracking retains unbounded, attacker-sized identifiers

**Trigger and impact:** Unauthenticated callers submit distinct oversized email values. Login canonicalizes the full strings and stores them as failed-login throttle keys. The global request-body limit still permits multi-megabyte identifiers, so retained keys can consume a material part of the 256 MiB JVM heap with relatively few requests.

**Evidence:** [Login processing](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/auth/AuthRoutes.kt#L214) reaches account lookup and failure recording without an identifier-length bound. [Canonicalization](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/users/Validation.kt#L13) only trims and lowercases. [LoginThrottle](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/auth/LoginThrottle.kt#L22) stores complete strings. Its cleanup starts only above 10,000 keys and removes stale entries; it does not impose a strict capacity, and smaller populations of abandoned keys are not periodically reclaimed.

**Validation:** A disposable-server regression probe submitted one synthetic 2 MiB email and received the ordinary 401 failure response. Combined with the code path, this confirms oversized identifiers reach failure tracking. No heap-exhaustion run was performed and no exact crash threshold is claimed. A uniform 401 for malformed or unknown identities is not itself the defect.

**Suggested correction:** Bound identifier processing and retention before expensive work, preserve the intended non-enumerating response policy, and impose bounded capacity plus predictable expiry cleanup. Similar throttle/challenge structures warrant a separate capacity audit; their prerequisites differ, so they are not all equivalent unauthenticated attacks.

### 3. P1 — Supported ontology documents can exceed graph-read memory capacity

**Trigger and impact:** The service accepts up to 2,000 entities per blueprint, 10,000 overall, with documents up to 256 KiB. A graph request materializes full documents. Around 2,000 documents of 250 KiB represent approximately 488 MiB of payload alone, exceeding the configured 256 MiB JVM heap before object overhead.

**Evidence:** [Entity limits](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/entities/Entity.kt#L29); [JVM configuration](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/build.gradle.kts#L20); [graph materialization](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/entities/EntityService.kt#L626). Query-filtered graph processing also loads a workspace snapshot separately. Decoding/materialization occurs before the evaluator's first budget checkpoint. Query permits bound concurrent query count, not bytes; the plain graph path does not use those query permits.

**Validation:** Code-derived capacity mismatch, not a measured OOM or production load test. Time limits do not make an allocation safe once the required live data exceeds the heap.

**Suggested correction:** Use bounded graph projections, an aggregate byte budget, and a single reusable workspace snapshot where full documents are necessary. Include materialization in the execution budget and size concurrency to memory use. Reverse aggregation should also stop collecting once its result bound is reached rather than collecting all inbound candidates before truncation.

### 4. P2 — Rename validation accepts references to the identity being removed

**Trigger and impact:** A replacement changes an entity's identity while retaining a relation to its old identity. Validation sees the old row as an existing target; the replacement then removes that identity and leaves a dangling reference.

**Evidence:** [Port replacement](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/entities/EntityService.kt#L858) validates against the pre-update snapshot. Rename cascading can repair stored references, but the subsequent full document write restores the request's stale self-reference. [Catalog replacement](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/catalog/CatalogFileService.kt#L365) similarly validates before removing the old identity from the effective workspace.

**Validation:** Two actual route/Testcontainers probes reproduced the behavior. Port: rename plus relation to the old identifier returned 204, followed by `RELATION_TARGET_MISSING`. Backstage: rename plus `subcomponentOf` pointing at the old identity returned 204; repeating the identical PUT returned 400.

**Suggested correction:** Validate against the post-mutation identity set. For Port, consistently rewrite or reject old self-references in the incoming replacement document. Cover identifier/name, namespace, and kind changes as applicable, preserving each world's intentional self-reference and waiver rules.

### 5. P2 — Ontology replacement import can strip a valid stored property

**Trigger and impact:** An existing blueprint declares a computed property. A mixed replacement import changes that field to an ordinary stored property and includes an entity value for it. The importer combines old and incoming computed-property IDs, incorrectly strips the valid new value, and may lose optional data or cause required-property validation to fail.

**Evidence:** [Computed-ID construction](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/web/src/utils/ontologyImport.ts#L163) seeds incoming definitions and then unions registry computed IDs even for blueprints redefined in the batch. [Stripping](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/web/src/utils/ontologyImport.ts#L224) uses that union. The nearby comment describes registry fallback only when the batch does not redefine the blueprint, which differs from the implementation.

**Validation:** Code-derived input-path finding. The UI does show a stripping notice, so this is not silent loss; the notice incorrectly classifies the now-stored field as computed.

**Suggested correction:** Make an incoming replacement blueprint authoritative. Use the stored definition only for blueprints absent from the batch, with a regression covering computed-to-stored conversion.

### 6. P2 — Hierarchy/relation name collisions produce incomplete graph-query traversal

**Trigger and impact:** A blueprint has an ordinary relation named `composition` and maps hierarchy `composition` to another relation such as `parent`. Outgoing traversal resolves the hierarchy branch exclusively and drops the ordinary relation's edges. Untyped traversal is also affected when it resolves the ordinary key through that branch.

**Evidence:** [Outgoing resolution](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/entityquery/QueryEvaluator.kt#L344) differs from [incoming resolution](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/entityquery/QueryEvaluator.kt#L369), which unions the candidates. The [language documentation](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/.claude/docs/entity-query-language.md#L134) promises union semantics.

**Validation:** Code-derived valid-model counterexample.

**Suggested correction:** Share edge-type resolution between both directions and deduplicate the resulting edges. Test name collisions for incoming, outgoing, and untyped traversal.

### 7. P2 — World-switch persistence races with navigation; current master CI is red

**Trigger and impact:** Switching worlds and immediately reloading or navigating to `/` can restore the previous world. The URL can already show the selected world while persistence still reflects the old route.

**Evidence:** [useWorld](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/web/src/hooks/useWorld.ts#L28) both persists an explicit selection and passively synchronizes storage from route state. An intermediate render can reconcile the old route back into storage before navigation completes.

**Validation:** The exact reviewed master commit's [CI run 34772758514](https://github.com/liveweird/toadie/actions/runs/34772758514) failed its browser/Quality gate. `world-switch.spec.ts` expected `/hierarchy` after navigating to `/`, but got `/entity-hierarchy`. It passed on retry; the suite reported 64 passed and one flaky test. The trace shows the hard navigation starting about 34 ms after the hierarchy URL assertion completed. Existing hook tests await settled effects and miss this window.

**Suggested correction:** Coordinate pending explicit selection with route reconciliation so old route state cannot overwrite the new preference. Keep the immediate-navigation regression; adding a sleep would conceal the race.

### 8. P2 — A delayed logout completion can erase a newer session

**Trigger and impact:** A logout request for account A remains pending. Another request definitively expires A, and B signs in. Completion of A's old logout then unconditionally clears B's local session; the caller also clears query state and navigates to login.

**Evidence:** [Logout completion](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/web/src/api/auth.ts#L83) clears session state after awaiting revocation without checking whether it still owns the active authentication boundary.

**Validation:** Code-derived interleaving, not a browser reproduction. Ordinary slow logout alone is insufficient: the UI normally awaits revocation. The finding requires a newer login to occur through another expiration path while the old request is pending.

**Suggested correction:** Make logout/session cleanup ownership-aware and prevent an old operation from clearing a newer session. Address with finding 1 and test held logout → expiration → new login → old completion.

### 9. P2 — Both sample loaders expose passwords in a child process's arguments

**Trigger and impact:** Running either loader puts `TOADIE_PASSWORD` in `jq --arg password ...`, making it observable through process arguments on the local machine. This contradicts the scripts' assertion that secrets never touch argv. Their separate curl-header file protection does not cover jq.

**Evidence:** [Blueprint loader](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/sample-data/blueprints/load.sh#L61), [entity loader](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/sample-data/entities/load.sh#L46).

**Validation:** Both actual scripts were executed with mock jq/curl commands and a synthetic password; both passed it in child argv. No real credentials or network calls were used.

**Suggested correction:** Supply the secret through a channel that does not place it in argv, and share the corrected authentication helper to avoid maintaining the same security-sensitive implementation twice.

### 10. P2 — Import pass-two storage failures can escape per-item results

**Trigger and impact:** During a two-pass cyclic import, concurrent identity changes can cause a pass-two unique-constraint failure after pass one has already committed rows. Unlike the first pass, the second pass catches only validation exceptions. The storage error escapes as a top-level failure, losing the normal per-item result summary and bypassing route-level batch auditing.

**Evidence:** [Blueprint pass two](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/blueprints/BlueprintImport.kt#L358), [entity pass two](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/entities/EntityImport.kt#L484).

**Concrete schedule:** Import mutually related A and B; pass one temporarily omits A's forward reference and stores both rows. Another request renames A to C and creates a new A. Pass two replaces the original row using identifier A and its now-resolvable relation to B, triggering an identity uniqueness conflict.

**Validation:** Code-derived concurrent schedule, not a runtime race reproduction. A zero-row update alone was not treated as proof of this finding.

**Suggested correction:** Give both passes the same cancellation-aware per-item storage-error classification and preserve committed-row IDs/results. Add a controlled concurrency regression.

## Smaller consistency and robustness findings

1. **P3 — Query validation uses overly broad property candidates for reused variables.** [QueryValidator](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/entityquery/QueryValidator.kt#L104) can validate an unlabelled occurrence against all blueprints before applying labels already bound to that variable. For example, `MATCH (a:service), (a {groupOnly: 'x'}) RETURN a` can be accepted when only another blueprint defines `groupOnly`. Reuse effective binding labels for property validation, as relation validation already does.
2. **P3 — Lens sanitization precedes resource authorization.** [LensRoutes](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/lenses/LensRoutes.kt#L72) can reject control characters with 400 before the service's 403/404 verdict for a non-owned public/private lens. This contradicts the documented ordering (API-ERR-005 / API-SEC-003), but is not evidence of an existence leak. Move resource-dependent validation after the verdict and extend the existing ordering tests to sanitizer failures.
3. **P3 — Additional-role request semantics differ from OpenAPI.** [UserRole in the contract](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/resources/openapi/documentation.yaml#L2777) permits only `ADMIN`, whereas [runtime role conversion](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/users/User.kt#L110) accepts the baseline `USER` value and normalizes it away. Request conformance is therefore weaker than the declared enum (API-VER-003 / API-DOC-001). Align the request type and behavior or intentionally revise the contract.
4. **P3 — Registry capacity checks are not atomic.** [Label creation](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/labels/LabelService.kt#L56) and [annotation-key creation](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/annotations/AnnotationKeyService.kt#L54) count before inserting without serializing the limit check. At 199 entries, two concurrent creates can exceed the 200-entry cap. This is admin-only and low priority. Tag categories provide an existing locking pattern and concurrency test to follow.
5. **P3 — A few guidance statements are stale.** [CLAUDE.md](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/CLAUDE.md#L76) names Temurin 21.0.11+10 while the executable toolchain/CI/image use 21.0.12+8. [CatalogFile documentation](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/kotlin/catalog/CatalogFile.kt#L7) describes older reference-validation behavior. The [OpenAPI introduction](https://github.com/liveweird/toadie/blob/8668b0ba874727bb41ee43156d1cc42d6339883d/server/src/main/resources/openapi/documentation.yaml#L5) describes the Backstage surface and user authorization too narrowly for the current implementation. Historical release/hardening records should remain historical; update statements that claim to describe current behavior.

## Verification and limits

| Check | Result |
| --- | --- |
| Fresh backend tests (`:server:test --rerun`) | 95 suites, 1,002 tests; zero failures, errors, or skips |
| Gradle build/static analysis and Kover verification | Passed |
| Backend coverage | 97.52% lines, 79.88% branches; current floors passed |
| Frontend build, ESLint, Knip | Passed |
| Frontend coverage test run | 149 suites, 2,526 tests passed |
| Frontend coverage | 97.78% lines, 95.46% statements, 94.31% functions, 91.24% branches; all floors passed |
| Generated API type drift | Passed |
| Spectral published contract and reference fixture | Passed with 0 errors; 2 registered warnings and 100 registered hints |
| Runtime response conformance | All 75 operations observed; 316 of 400 declared operation/status pairs observed |
| E2E typecheck and scenario parity | Passed; 37 spec/scenario pairs |
| Exact-master hosted CI | Backend/frontend passed; browser and Quality gate failed on world-switch flakiness |
| Isolated additional probes | Both rename probes and oversized-login probe passed assertions of the defective current behavior |
| Working tree and whitespace | Clean; `git diff --check` passed |

The 84 unobserved response-status pairs are a coverage gap, not 84 proven defects. Response conformance does not automatically prove request-schema enforcement. Knip/detekt/manual review did not identify actionable dead code, but those checks cannot prove the absence of every unused runtime path.

Manual review of the API checklist was summarized into 19 groups: 8 passed, 3 had findings, 7 were mixed/registered exceptions, and 1 represented a documented scaling assumption. The three finding groups cover request-validation consistency, authorization/error precedence, and contract fidelity. Existing registered exceptions include correlation/problem-instance metadata, caching/conditional requests, rate-limit metadata, idempotency, HTTP conventions, and service metadata. Documented unpaged saved collections are an accepted design choice; their growth assumptions warrant monitoring, not a new pagination violation.

The full browser suite was not rerun locally: the exact-master hosted run and its trace were used. The development database was preserved. Temporary probes subsequently replaced ignored local test output with focused subsets; the full-run reports were saved beforehand. The following artifacts are temporary local evidence, are not included in the repository, and may disappear when the temporary directory is cleaned. The findings and results above remain recorded in this document:

- [Full runtime API coverage](/tmp/toadie-quality-full-reports/api-coverage.md)
- [Full Kover XML](/tmp/toadie-quality-full-reports/kover.xml)
- [Fresh backend test log](/tmp/toadie-quality-backend-fresh.log)
- [Frontend check/test log](/tmp/toadie-quality-frontend.log)
- [Rename reproduction log](/tmp/toadie-quality-rename-probes.log)
- [Oversized-login reproduction log](/tmp/toadie-quality-login-probe.log)
- [Hosted CI failure log](/tmp/toadie-quality-ci-failure.log)

## SRP, DRY, SOLID, complexity, and coherence

These principles are useful diagnostic tools, not binary repository-wide gates. The concrete findings identify where abstraction boundaries are currently failing:

- **Authentication lifecycle:** Session-ending behavior is duplicated between explicit logout, refresh rejection, and UI callbacks. Centralizing ownership and cleanup addresses actual privacy and stale-completion defects.
- **Workspace materialization:** EntityService combines replacement/cascade logic, snapshots, filtering, computed-property evaluation, and graph/query materialization. Its size alone is not a defect; repeated workspace loads and missing aggregate memory accounting justify extracting a bounded snapshot/materialization responsibility.
- **Graph semantics:** Incoming and outgoing type-resolution implementations diverged. A shared semantic resolver is more useful than merely reducing line count.
- **Imports:** Pass-one and pass-two error boundaries differ. Share the classification and result-preservation policy while retaining their different dependency-resolution jobs.
- **Small duplicated security-sensitive helpers:** The two loader authentication blocks contain the same argv issue. This is a concrete DRY opportunity.
- **Registry capacity:** Reuse the established serialized check-and-insert pattern rather than creating a generic CRUD framework.

The shared UI transport, query/registry hooks, toolbar/navigation patterns, graph primitives, and bilingual strings remain broadly coherent. The confirmed world-switch and auth-cache issues are state-ownership defects within that structure, not reasons to replace the UI stack.

Port and Backstage intentionally differ in identity, reference semantics, and validation waivers. Preserve those domain boundaries. No evidence justifies merging their services into one general-purpose model or replacing PostgreSQL solely as a result of this audit.

Previously registered API deviations, accepted last-write-wins behavior, documented deployment/native-DNS limitations, and parked HARDENING items were kept separate from new findings. The audit does not reopen the parked backlog automatically.

## Suggested implementation order

1. **Authentication and resource bounds:** Fix private-cache ownership and stale logout together; bound failed-login identifier retention; bound graph materialization and remove duplicate snapshots. Add focused lifecycle and capacity regressions.
2. **State and mutation correctness:** Fix the current CI world-switch race, rename validation in both worlds, ontology replacement property stripping, and pass-two import error isolation.
3. **Query and consistency cleanup:** Unify edge resolution, tighten reused-variable validation, repair loader secret handling, align request-role/sanitization behavior and small registry races, and update current-state documentation.

Each batch should update the relevant Claude guidance and tests with the implementation. No broad refactor or database migration is inherently required by the identified issues; the graph memory work needs a concrete bounded-read design before choosing storage changes.
