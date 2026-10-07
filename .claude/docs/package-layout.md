### Package layout (the annotated map)

The per-package map of `server/src/main/kotlin/`, moved out of the root `CLAUDE.md` on 2026-10-01 so it loads on demand (`ls server/src/main/kotlin` shows the areas; this file says what each one owns, why, and which version shaped it). Read it before adding, splitting, or relocating a server feature; the flat-package idiom, the feature template, and the reference-examples table stay in the root `CLAUDE.md`.

```
ch.nokillswit
├── main.kt
├── plugins/            cross-cutting Ktor wiring (configureXxx that only `install` plugins):
│                       Http, SecurityHeaders, Monitoring, Serialization, Security (JWT),
│                       ErrorHandling (RFC 7807), OpenTelemetry, AutoHeadResponse, Resources,
│                       Routing (SPA catch-all), Health (/healthz liveness + /readyz readiness —
│                       the k8s probes; unauthenticated, outside /api/, registered after the infra group)
├── infra/mail/         outbound email (Lettuce's, ported): Mailer/SmtpMailer/LogMailer +
│                       LocalizedText (the recipient-language content layer) +
│                       configureMail — MAIL_TRANSPORT log/smtp/disabled, the log-transport
│                       production refusal (fail-closed), null mailer = email features 503.
│                       Consumers: self-service password reset and email MFA
├── infra/db/           Flyway bootstrap + the R2DBC connection/composition root + the seed
│                       bootstrap (admin rotation, prod fail-closed) + Sql.kt (containsNormalized,
│                       jsonArrayContains, orVanished) + EventLog.kt/JsonParams.kt (Lettuce's
│                       shared per-record audit-event machinery — the EventLogTable base behind
│                       catalog_file_events, the first and so far only clone) + Locking.kt
│                       (lockingTransaction — the shared cooperating-writer table-lock helper
│                       behind BlueprintService/TagCategoryService/EntityService's V27/V11/V28
│                       protocols) + OntologyRevision.kt (the V39 monotonic ontology-revision
│                       counter — bumpOntologyRevision/ontologyRevisionExpression/
│                       currentOntologyRevision — behind the GraphQL integration API's
│                       BlueprintPage/EntityPage/OntologyErrors `revision` field) +
│                       OntologyReadTransaction.kt (read-only REPEATABLE READ snapshot for
│                       paged blueprint/entity materialization; writer locks stay READ
│                       COMMITTED — see `.claude/docs/integration-api.md` "Ontology revision")
├── infra/paging/       the shared list-endpoint machinery (PageRequest/parsePaging/applyPaging/
│                       PageResponse + the strict query-param readers) — Lettuce's, ported verbatim;
│                       plus `validatedPage` (the 1..100 bound check) and `inMemoryPage` (the slice
│                       over an already-materialized list), shared by `integration/Fetchers.kt`'s
│                       GraphQL pages and `McpReadTools.kt`'s `ontology_errors`
├── infra/validation/   cross-feature input helpers (sanitizeSingleLine — trim + control-char 400,
│                       requireNoDuplicates, requireNoDocumentSourceUrl — 2.13.1, the one guard
│                       shared by `entities/Entity.kt` and `blueprints/Blueprint.kt`'s bulk-import/
│                       sync bodies, replacing their two byte-identical copies) + InvalidPayloadException,
│                       the one findings-bearing 400 shape (catalog strict save, entity save) —
│                       ErrorHandling.kt renders it feature-free
├── infra/importing/    shared ontology bulk-import vocabulary (phase 6, v1.28.0 — the
│                       `catalog/CatalogFileImport.kt` precedent, one level up): ImportBatch.kt —
│                       `MAX_IMPORT_DOCUMENTS` (200), `OntologyImportStatus`, the per-row
│                       `decodeDocument`/`rawString`/`requireBatchSize` helpers, and
│                       `orderWithDeferral` (the Kahn topological-sort skeleton — the
│                       in-degree/queue loop and its input-order tie-break — shared by
│                       `blueprints/BlueprintImport.kt`'s `orderCandidates` and
│                       `entities/EntityImport.kt`'s `attemptOrdering`; the per-kind edge
│                       collection and the cycle policy stay in each caller) behind
│                       `blueprints/BlueprintImport.kt` and `entities/EntityImport.kt`
├── infra/concurrency/  BoundedExecution.kt — Executor.awaitBounded, the suspend-with-cancellation
│                       bridge to a bounded ThreadPoolExecutor, extracted from
│                       infra/fetch/UrlFetch.kt (originally catalog/UrlFetch.kt); consumers: the
│                       URL fetch and the jq evaluator
├── infra/fetch/        UrlFetch.kt — the SSRF-guarded outbound fetcher (relocated from
│                       catalog/ in 2.9.0 when entities gained their own source references:
│                       `UrlFetcher`/`UrlFetcherKey`, one shared bounded pool, plus
│                       `ApplicationCall.fetchForCaller` — 2.13.1, the byte-identical
│                       receive/fetch/audit body the three fetch routes shared, called with
│                       each route's own `blocked`/`fetched` `AuditEvent` literals) + SourceWrite.kt
│                       (2.10.0 — the sealed FromRequest/Keep/Synced write-source vocabulary,
│                       moved here from `entities/Entity.kt` once blueprints gained the same
│                       source references; 2.13.1 adds `SourceColumns`/`resolveSourceColumns`,
│                       the pure dispatch extracted from `entities/EntitySync.kt`'s and
│                       `blueprints/BlueprintSync.kt`'s byte-identical `replaceRow` blocks —
│                       `catalog/CatalogFileService.kt`'s own sync predates the type, always
│                       waives, and stays its own inline copy); consumers: `POST …/files/fetch`,
│                       `POST …/entities/fetch`, and `POST …/blueprints/fetch`
├── audit/              security audit trail: `audit(event, fields…)` → AUDIT-marked structured logs; `AuditEvent("…")` literals for a shared emitter (2.13.1)
├── authz/              CallerPrincipal + guards (requireAdmin, requireSelfOrAdmin) + typed
│                       HTTP exceptions (401/403/404/409/429/502)
├── auth/               POST /api/v1/login (+ the email-MFA branch and /login/mfa second
│                       step — MfaChallenges/MfaEmail), /refresh, /logout + the self-service
│                       POST /api/v1/password-reset (uniform 202, async single-use link delivery)
│                       + /password-reset/confirm (atomic password/grant consumption, V26)
│                       + PasswordResetThrottle + token minting + password hashing
│                       + LoginThrottle + the revoked-token blocklist
├── users/              the user domain: ADMIN-only management CRUD (/api/v1/users list/create
│                       + {id} get/put/delete with the self-delete 403 and last-admin 409
│                       protections) + PUT /api/v1/users/{id}/password + the per-user feature
│                       flags (Feature enum + PUT {id}/features, the V12 disabled-set model;
│                       MFA is the inverted-default login-scoped flag) + the per-user
│                       language (V18: PUT {id}/language, self-or-admin — the ONE synced
│                       UI+email language) + the per-user Graph layout (V19: GET/PUT
│                       {id}/graph-layout, self-or-admin — the Graph page's Auto/Manual
│                       modes + dragged positions; GraphLayout.kt/GraphLayoutService.kt,
│                       deliberately unaudited) + its Phase 3 twin, the Entity graph's OWN
│                       layout (V30: GET/PUT {id}/entity-graph-layout, same shape/rules over
│                       an independent table — GraphLayoutService generalized to take its
│                       Exposed table object, GraphLayouts/EntityGraphLayouts) + Validation.kt
│                       + service accounts (V41, 2.15.0 — users rows that can never sign in, one per integration client; `human()` hides them from the whole management surface)
├── dictionaries/       admin-curated ordered value lists (Lettuce's dictionaries, single-
│                       valued — no translations): Dictionary.kt (the Dictionary enum whitelist
│                       + DTOs + validateDictionaryUpdate), Languages.kt (SUPPORTED_LANGUAGES —
│                       the V18 per-user-language whitelist), DictionaryService.kt (whole-document
│                       replace: soft-delete-first reconcile, positions rewritten from payload
│                       order — no reorder endpoint), DictionaryRoutes.kt —
│                       GET /api/v1/dictionaries/{slug} (any authenticated, unpaged) +
│                       PUT (ADMIN). Three dictionaries: NAMESPACE ("namespaces") — the
│                       allowlist every catalog-file write's namespace must be in; exactly
│                       one entry flagged isDefault (what blank namespaces resolve to) —
│                       and LIFECYCLE ("lifecycles") — the GLOBAL allowlist every write's
│                       non-blank spec.lifecycle must be in; NO default (flags rejected;
│                       the per-dictionary usesDefault branch in validateDictionaryUpdate) —
│                       and HIERARCHY ("hierarchies", V33) — the identifiers of the parallel
│                       entity hierarchies a blueprint's `hierarchyRelations` may name, seeded
│                       with `composition`; NO default; every blueprint write's map keys must be
│                       ACTIVE entries (400 otherwise), and removing/renaming a value a blueprint
│                       still names is 409 naming the referrers — checked under the `blueprints`
│                       lock (V27), the one dictionary whose replace takes a lock
├── labels/             the ADMIN-curated label registry (per-entity CRUD — the nested
│                       key+values+kinds shape doesn't fit the flat dictionary): Label.kt
│                       (DTOs + sanitizedLabelRequest + validateLabelRequest — key/value
│                       grammar borrowed from catalog's validators), LabelService.kt (one
│                       row = one label; allowed values/kinds as JSON arrays in TEXT),
│                       LabelRoutes.kt — GET /api/v1/labels (any authenticated, unpaged) +
│                       POST/PUT/DELETE (ADMIN). The whitelist every catalog-file write's
│                       metadata.labels is checked against: key registered, kind allowed,
│                       value in the label's closed list (strict, no grandfathering)
├── lenses/             saved filter sets (the labels/ CRUD template + catalog's created_by
│                       join): Lens.kt (LensVisibility PRIVATE/PUBLIC + the LensFilters
│                       payload — the nine shared filter slots, validated STRUCTURALLY
│                       only, never against the registries — + sanitized/validateLensRequest),
│                       LensService.kt (one row = one lens; filters as one JSON object in
│                       TEXT; list = own + everyone's PUBLIC; mutationVerdict decides
│                       creator-only mutations in-transaction), LensRoutes.kt —
│                       GET/POST /api/v1/lenses + PUT/DELETE {id} (ALL any-authenticated:
│                       no admin gate anywhere, ADMIN gets no special access; foreign
│                       PRIVATE/unknown → 404, foreign PUBLIC → 403 — the hybrid
│                       disclosure policy in authorization.md). Backs the LensPicker on
│                       the Hierarchy/Files/Graph/Errors views
├── annotations/        the ADMIN-curated annotation-key registry (the labels/ template
│                       minus the value dimension — annotation VALUES stay free strings):
│                       AnnotationKey.kt (DTOs + sanitized/validateAnnotationKeyRequest —
│                       key grammar from catalog's validateKey; server-written keys
│                       rejected), AnnotationKeyService.kt (one row = one key; kinds as a
│                       JSON array in TEXT), AnnotationKeyRoutes.kt —
│                       GET /api/v1/annotation-keys (any authenticated, unpaged) +
│                       POST/PUT/DELETE (ADMIN). The whitelist every catalog-file write's
│                       metadata.annotations KEYS are checked against: key registered, kind
│                       allowed (strict, no grandfathering; empty registry = no annotations)
├── tags/               the ADMIN-curated tag categories (an INTERNAL Toadie concept — not
│                       in the Backstage schema; the labels/ template): TagCategory.kt
│                       (DTOs + sanitized/validateTagCategoryRequest — tag grammar + kinds
│                       helpers borrowed from catalog's validators), TagCategoryService.kt
│                       (one row = one category; tags/kinds as JSON arrays in TEXT; the
│                       one-category-per-tag 409 enforced after a transaction-scoped PostgreSQL write lock),
│                       TagCategoryRoutes.kt — GET /api/v1/tag-categories (any
│                       authenticated, unpaged) + POST/PUT/DELETE (ADMIN). The whitelist
│                       every catalog-file write's metadata.tags is checked against: tag
│                       registered, its category's kinds allow the file's kind (strict)
├── types/              the ADMIN-curated per-kind type dictionaries (an INTERNAL Toadie
│                       constraint on the open `spec.type` field; the labels/tags template):
│                       EntityTypes.kt (DTOs + sanitized/validateEntityTypesRequest — the
│                       exact spec.type rule via catalog's validateSingleWord; only
│                       TYPE_BEARING_KINDS, i.e. all but User), EntityTypesService.kt (one
│                       row = one KIND's list; types as a JSON array in TEXT; kind unique
│                       among active rows, dictionaries INDEPENDENT — no cross-row check),
│                       EntityTypesRoutes.kt — GET /api/v1/entity-types (any authenticated,
│                       unpaged, ≤6 rows) + POST/PUT/DELETE (ADMIN). V15 seeds the
│                       well-known Backstage values per kind, V22 re-curates them. The
│                       whitelist every catalog-file write's spec.type is checked against
│                       (strict; a kind with no dictionary allows NO types)
├── blueprints/         Port.io-style user-definable entity kinds (v1.23.0, Toadie-first —
│                       Lettuce has none): Blueprint.kt (the Port-native wire DTOs — a typed
│                       skeleton with JsonElement only for Port's open sub-trees: `default`,
│                       `enum`, object `properties`/`patternProperties`/`additionalProperties`,
│                       aggregation `query.rules`/`pathFilter` — plus `blueprintJson`, the
│                       explicitNulls=false serializer used for BOTH the stored TEXT and the
│                       responses so unset optionals are ABSENT, never null),
│                       BlueprintValidation.kt + PropertyValidation.kt (the data-driven
│                       applicability table + one thrower per rule; every rule in
│                       .claude/docs/port-data-model.md), BlueprintReferences.kt (pure
│                       `blueprintTargets`/`withTargetRenamed`), BlueprintService.kt (one row =
│                       one blueprint: identity columns + one `definition` JSON; every mutation
│                       under the `blueprints` table lock (V27 — the tag-category lock's
│                       PATTERN, `infra/db/Locking.kt`, see `.claude/docs/persistence.md`) —
│                       relation/aggregation targets must
│                       exist (self allowed), an identifier RENAME cascades into every row
│                       targeting it in the same transaction, deleting a targeted blueprint is
│                       409 naming the referrers, or that has active ENTITIES 409 naming the
│                       count (V28)); Phase 3's `hierarchyRelations` (V29 → V34 map — a JSON object column
│                       beside `definition`, so the stored Port document stays byte-identical)
│                       names one of the SAME row's `relations` with `many == false`, validated
│                       in BlueprintValidation.kt, read/written alongside `definition`,
│                       BlueprintRoutes.kt — GET /api/v1/blueprints
│                       + GET {id} (any authenticated, unpaged, ≤200) + POST/PUT/DELETE (ADMIN,
│                       guard-before-read). V31 (v1.26.0, phase 4 — `SystemBlueprints.kt`)
│                       seeds `_team` and `_user` system blueprints (flagged `system: true`,
│                       protected against deletion and base-shape removal); ADMIN may extend
│                       both. No other seed: custom blueprints start empty. BlueprintImport.kt
│                       (phase 6, v1.28.0) — `POST …/blueprints/import` + `/import/check`, ADMIN
│                       guard-before-receive: the pure ordering+deferral planner
│                       (`planBlueprintImport`, one Kahn topological sort over sibling
│                       relation/aggregation targets, cycle/forward-reference deferral into a
│                       two-pass write) plus its effectful `BlueprintService.import`/
│                       `importCheck` callers, reusing `create`/`update` per row under the same
│                       V27 lock; BlueprintSync.kt (2.10.0, the `entities/EntitySync.kt` shape
│                       one level up) — `syncFromSource`/`syncState` behind `GET`/
│                       `POST …/{id}/sync` (ADMIN) and `POST …/blueprints/fetch` (ADMIN), an
│                       ordinary replace under the same V27 lock that KEEPS the stored
│                       `hierarchyRelations` map when the remote document omits it
│                       (and, since 2.18.0, the stored `tiers` pruned to the synced keys);
│                       BlueprintTiers.kt (2.18.0, V44) — the Toadie-only fill-in `tiers` rules:
│                       the 1..4 bounds, `sanitizedTiers`, `validateTiers`, `prunedTo` and the
│                       `blueprints.tiers` column codec (the `BlueprintTiers` DTO itself sits
│                       in Blueprint.kt, Kover-excluded with the other wire DTOs)
├── entities/           instances of a blueprint (v1.24.0, Phase 2 of the Port data-model
│                       move): Entity.kt (the wire DTOs — EntityRequest/Entity/EntityFinding,
│                       reusing `blueprintJson` for the stored `document` = {properties,
│                       relations} and for ABSENT-not-null responses), EntityValidation.kt
│                       (the blueprint-free shape rules — identifier/title/icon/team/document
│                       size — plus the pure `entityFindings`, the blueprint-dependent
│                       property/relation rule table in .claude/docs/port-data-model.md, reused
│                       unchanged by both the strict-save 400 and every GET/list `findings`),
│                       EntityOwnership.kt (v1.26.0, phase 4 — ownership rules: Direct/absent
│                       blueprints validate stored `team` against `_team` entities; Inherited
│                       blueprints compute team at read time via path-walking; `format: team|user`
│                       properties validated similarly; + `teamValueMatches`, the in-memory twin
│                       of `jsonStringOrArrayContainsFolded` behind the team filter's Inherited
│                       half), EntityReferences.kt (pure
│                       `entityTargets`/`withEntityTargetRenamed`), EntityFilter.kt (the shared
│                       list/graph filter set — the `catalog/CatalogFileFilter.kt` shape, one
│                       level down: `EntityFilter`/`EntityGraphFilter`, the `q`/blueprint-lookup
│                       helpers, and the team-match predicate + `inheritedTeamMatches`, the
│                       v1.30.0 in-memory Inherited-ownership resolver behind the `team` filter's
│                       SQL `id IN (…)` disjunct), EntityReadBudget.kt (2.4.0 — the process-wide
│                       entity read memory ledger: `ENTITY_READ_BUDGET_BYTES`, `estimatedHeapBytes`,
│                       `EntityReadLedger`/`ReadBudgetExceeded`; `EntityWorkspaceRead.kt`'s
│                       `WorkspaceRow`/`EntitySnapshot`/`loadReadSet` charge it — see
│                       `.claude/docs/security.md` "Entity read memory budget"), EntityService.kt
│                       (one row =
│                       one entity, FK to blueprints.id; every mutation under the two-table
│                       `blueprints`-then-`entities` lock — .claude/docs/persistence.md "Entity
│                       targets under concurrency (V28)" — relation targets must be ACTIVE
│                       entities of the target blueprint, identifier unique per blueprint, a
│                       RENAME cascades into every referring entity's relations/team/format-props,
│                       deleting a targeted entity is 409 naming the referrers from all three
│                       sources), EntityGraph.kt (Phase 3, v1.25.0 — a pure builder + DTOs, no
│                       database: `buildEntityGraph` over the shown rows, one edge per relation
│                       value with the both-ends rule and no virtual/MISSING nodes, `hierarchies`
│                       flagged for the source blueprint's `hierarchyRelations`, `ownership: true`
│                       for `$team` edges to `_team` nodes, the `"<blueprint>|<identifier>"`
│                       node-id grammar), JqCalculation.kt (Phase 5, v1.27.0 — the jq bridge
│                       behind `calculationProperties`: `net.thisptr:jackson-jq` 1.3.0, a
│                       read-only root `Scope` with `env`/`$ENV` shadowed AFTER builtin loading,
│                       first-output-wins abort, a 64 KiB output cap, failures absent and logged
│                       at DEBUG without the input document — see
│                       `.claude/docs/security.md` "Computed-property evaluation (jq)". Since
│                       v1.29.0 a single shared, thread-safe `JqEvaluator` (owned by
│                       `EntityService`) runs every evaluation on the bounded `entity-jq` worker
│                       pool (4 workers, queue 64) under a per-expression deadline on the
│                       compiled expression (builtins loaded at boot, compile on the caller)
│                       (`computed.jq.deadlineMillis`, default 500 ms); an expression that
│                       misses it is quarantined until its text is edited),
│                       EntityComputed.kt (Phase 5 — the computed-property orchestrator: the
│                       mirror-property relation walk, `computedPathBlueprints` snapshot
│                       widening, and the mirror/calculation/aggregation merge into `properties`),
│                       EntityAggregation.kt (Phase 5 — aggregation candidate resolution via
│                       direct relations or `pathFilter` in either direction, plus
│                       `calculationSpec` reduction), AggregationQuery.kt (Phase 5 — Port's
│                       `combinator`+`rules` query syntax evaluated over one aggregation
│                       candidate), EntityRoutes.kt — GET /api/v1/entities (any
│                       authenticated, paged: identifier/title/updatedAt sort, blueprint/team/q
│                       filters) + GET/POST/PUT/DELETE {id} (any authenticated, no admin gate
│                       anywhere — the catalog-file shared-workspace rule) + GET …/entities/graph
│                       (any authenticated, unpaged — the same `blueprint`/`team` any-of/`q`
│                       filters, both-ends rule). No seed: the registry starts empty. Since 2.9.0
│                       `EntityService` also gains `syncFromSource`/`syncState` (the V37 source
│                       reference & re-sync twin of the catalog's own, `.claude/docs/persistence.md`
│                       "V37") and `EntityRoutes.kt` gains `POST …/entities/fetch` and
│                       `GET`/`POST …/{id}/sync`.
│                       EntityImport.kt (phase 6, v1.28.0) — `POST …/entities/import` +
│                       `/import/check`, any authenticated (no admin gate): the pure planner
│                       (`planEntityImport`, ordering over relation/`team`/format-property
│                       sibling references, a MANDATORY reference cycle rejected outright rather
│                       than deferred) plus its effectful `EntityService.import`/`importCheck`
│                       callers and the `importSnapshot()` read seam, reusing `create`/`update`
│                       per row under the same two-table V28 lock; since 2.9.0 a batch `sourceUrl`
│                       stamps every stored row synced, EntityErrors.kt (Phase 8,
│                       v2.5.0 — the Port twin of `catalog/Errors.kt`: pure checkers + DTOs behind
│                       GET …/entities/errors, reporting all five classes — stale entities,
│                       unresolved ownership, broken saved queries, computed-property health, source-less
│                       entities (`SOURCE_MISSING`, 2.9.1) — as
│                       report-only findings/diagnostics, no audit)
├── entityquery/        the entity query language (phase 7, v2.0.0 — Toadie-first, Lettuce has
│                       none; `.claude/docs/entity-query-language.md`): EntityQuery.kt (the caps —
│                       MAX_QUERY_LENGTH 2000, 32 patterns/variables, 10 hops, 100k bindings, the
│                       1..60000 ms deadline range — `QueryDiagnosticCodes`, the wire
│                       `QueryDiagnostic`/`EntityQueryProblem`/`EntityQueryCheckRequest|Response`
│                       DTOs, `QueryException` and `EntityQueryInvalidException`, the findings-
│                       bearing 400 rendered by ErrorHandling.kt's ONE InvalidPayloadException
│                       handler), QueryAst.kt (the spanned AST), QueryLexer.kt + QueryParser.kt/
│                       PatternParser.kt/ExpressionParser.kt (`parseEntityQuery` — hand-written
│                       recursive descent, FIRST syntax error only, every out-of-subset Cypher
│                       feature a fixed UNSUPPORTED message), QueryValidator.kt + Suggestions.kt
│                       (`validateEntityQuery` against `QuerySchema(blueprints, hierarchies)` —
│                       EVERY finding, unknown names with a Levenshtein/prefix suggestion over
│                       identifiers AND titles, the DISCONNECTED_PATTERN cartesian-product refusal),
│                       QueryValues.kt (Kleene three-valued comparisons over JsonElement, numbers
│                       widened to Double, `candidateValue` shared with entities/AggregationQuery.kt
│                       — ONE meta vocabulary), QueryGraph.kt (`InMemoryQueryGraph` over the
│                       workspace's IndexedRows: per-blueprint/incoming/ownership indexes built once
│                       or lazily, `$team` = the EFFECTIVE team, byte-exact), QueryBudget.kt (the
│                       cooperative deadline observed at EVERY checkpoint + binding cap; per-candidate
│                       work capped by the string-operand ceilings), QueryEvaluator.kt (`InMemoryQueryExecutor`
│                       behind the `QueryExecutor` interface — anchored joins, level-set BFS for
│                       `*n..m`, trailing OPTIONAL MATCH, RETURN as a node SET, LIMIT after dedupe).
│                       Pure — no database, no Ktor; consumed by entities/EntityService.graph
│                       (schema read → parse+validate OUTSIDE any transaction → workspace read →
│                       a permit (4, `429` beyond) → decode+evaluate on the dedicated `entity-query` pool)
│                       and `checkQuery`. Since v2.1.0 the package also holds the SAVED queries —
│                       SavedEntityQuery.kt (DTOs, `sanitizeMultiLine` + the parse-at-save rule),
│                       SavedEntityQueryService.kt (the lenses/ clone over V35's `entity_queries`:
│                       own + PUBLIC list, creator-only mutations via the hybrid 404/403 verdict),
│                       SavedEntityQueryRoutes.kt (`/api/v1/entity-queries`, any authenticated)
├── integration/        the machine API: the read-only Port GraphQL adapter (v2.7.0 — committed SDL,
│                       service-backed resolvers, bounded execution, separate machine authentication)
│                       and, since 2.15.0, the MCP endpoint `POST /integration/mcp` (Mcp.kt — a
│                       per-request stateless Streamable HTTP `Server` behind the SAME guard chain;
│                       McpReadTools.kt/McpWriteTools.kt — ten tools for every key, the three
│                       entity write tools refused with FORBIDDEN for read-scope keys; McpTools.kt —
│                       `guarded`, the one exception→tool-result mapper, the request-local scope/
│                       rate-limit gate, + `integration.mcp_call` audit; McpSchemas.kt);
│                       IntegrationClientService/Routes manage revocable keys with an immutable
│                       read/write scope and a paired service account (V36, V42) under ADMIN-only
│                       `/api/v1/integration-clients`. See `.claude/docs/integration-api.md`.
└── catalog/            the catalog-file domain (THE feature reference implementation):
                        CatalogFile.kt (the wire DTOs: kind model + EntitySpec superset),
                        CatalogFileValidation.kt (the sanitizer + per-kind required/forbidden
                        tables + every descriptor-format validator),
                        CatalogFileFilter.kt (the shared list/graph/errors filter set:
                        the SQL predicate AND the in-memory matcher side by side — one
                        MATCHING semantics, incl. owner-reference resolution and the
                        labelValue IN param; what each view DOES with a match differs —
                        shown vs reported — plus allowsVirtualTarget, the kind+namespace
                        rule for a graph node with no document to match),
                        CatalogFileService.kt, CatalogFileRoutes.kt — /api/v1/files CRUD
                        + paginated list + the sync pair (GET/POST …/{id}/sync); shared
                        workspace (no admin gate on content);
                        CatalogRegistryReader.kt — transaction-neutral namespace lookup and
                        registry snapshot SQL called inside the service's write/report transaction;
                        CatalogFileImport.kt — the import pipeline (import/importCheck as
                        service extensions; ONE shared per-document classification, so the
                        real run and the dry-run cannot drift);
                        CatalogFileEvents.kt — the history vocabulary: the event types, the pure
                        descriptor builders, and `documentChanges` (the generic field-level diff
                        of two documents) + CatalogFileEventService.kt (the EventLogTable clone
                        behind GET …/{id}/events — the editor's History section);
                        Errors.kt — the reference resolver + registry/stored-content checks
                        behind GET …/errors (the filterable workspace Errors report: soft
                        findings + the report-only STRUCTURE_INVALID/NAMESPACE_NOT_ALLOWED)
                        and POST …/check (the editor's live document check; its Port twin:
                        `entities/EntityErrors.kt`);
                        + CatalogFileInvalidException/CatalogFileInvalidProblem (v1.31.0 — the strict
                        save's aggregated 400 carrying those findings, an InvalidPayloadException);
                        Graph.kt — the same resolution machinery as a node/edge graph
                        (GET …/graph, the /graph page's backend);
                        Import.kt — the round-trip DTOs (GET …/export ships structured
                        documents, POST …/import stores each independently, report & skip,
                        POST …/import/check is the store-nothing dry-run of the same
                        classification; YAML parsing/rendering stays a client concern);
                        POST …/fetch — the SSRF-guarded server-side fetch of a catalog-info.yaml
                        URL (guards documented in security.md); the fetcher itself moved to
                        `infra/fetch/UrlFetch.kt` in 2.9.0, shared with the entity fetch route
```
