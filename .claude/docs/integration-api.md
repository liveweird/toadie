# Port integration API

The read-only machine API follows Lettuce's architecture (reference snapshot `8fbe241`):
SDL-first graphql-java, a separate endpoint and technical client identities, ADMIN-managed
revocable keys, and service-backed resolvers. Its scope is deliberately Toadie's Port ontology.

## Contract and scope

`POST /integration/graphql` accepts JSON `{query, operationName?, variables?}` and answers
`application/json`. `GET /integration/graphql/schema` returns the exact committed SDL from
`server/src/main/resources/graphql/schema.graphqls`. Both require an integration API key.
Authenticated introspection stays enabled. There are no mutations or subscriptions.

Roots are `blueprints`, `blueprint(id)`, `entities`, `entity(id)`, and `errors`. Lists carry
`{items, page, pageSize, total}` with one-based pages, default 20 and maximum 100. Blueprint
pages use SQL pagination ordered by numeric id; entity pages also use numeric id order and
reuse the existing entity-list filters. Missing/deleted single rows return null. `entities` accepts `blueprint`,
`q`, and effective `team`; `errors` accepts `blueprints`, `q`, and effective `team`.

The typed fields retain REST metadata names. IDs use GraphQL `ID` decimal strings so every
UInt database id is representable; timestamps and totals use `Long`. The `JSON` scalar carries
Port's dynamic property schemas, properties, relations, ownership, and hierarchy mappings.
JSON numbers retain their exact numeric text, and optional keys inside JSON keep Port's
absence semantics. An explicitly selected absent GraphQL object field returns null, as
GraphQL requires. Schema changes are additive; the evolution rules are in
`api-guidelines/GRAPHQL-GUIDELINES.md`.

Entity reads reuse the ordinary service: stored and computed properties, inherited ownership,
current findings, soft deletion, and existing read budgets have the same meaning as REST.
`_team` and `_user` are ontology blueprints/entities and are included. Login accounts,
Backstage catalog data, saved queries, and the entity query-language evaluator are excluded —
with one provenance exception: `Blueprint` and `Entity` expose their creator's `createdBy` id,
`creatorName` and `creatorDeleted` flag (the REST list's display fields), never an email or role.
Dynamic JSON can contain sensitive workspace content: a key grants read access to the whole
Port ontology, not ownership-based row permissions. Ownership remains informational. The
REST-only `sourceUrl`/`lastSyncedAt` source-sync envelope members (2.9.0,
`.claude/docs/persistence.md` "V37") are deliberately NOT exposed on the GraphQL `Entity` type —
Toadie provenance, not Port model — and neither is the identical `Blueprint` envelope added in
2.10.0 (`.claude/docs/persistence.md` "Blueprint source references (V38)").

`errors` is ontology health, separate from GraphQL's top-level execution `errors` array.
It reports entity and blueprint findings, with paginated finding-row collections and full
`checkedEntities`/`checkedBlueprints` counts. Entity finding rows use numeric id order; blueprint
finding rows use case-insensitive identifier order. Filters narrow reported subjects while reference
resolution stays workspace-wide. Static computed-property health checks do not evaluate jq
values; quarantine reflects this server instance. `EntityService.ontologyErrors` shares the
REST materialization/checkers but never loads saved queries or their validation schema.
The REST Errors report retains its caller-specific saved-query behavior unchanged. Pagination
limits returned rows, not the cost of the underlying health sweep.

For example, a client can request a page of services and current findings without fetching
every dynamic property. Supply `{"blueprint":"service","page":1}` as `variables` alongside
this query (use an identifier from your own blueprint registry):

```graphql
query ServiceHealth($blueprint: String!, $page: Int!) {
  entities(blueprint: $blueprint, page: $page, pageSize: 10) {
    items { id identifier title team findings { code field message } }
    page pageSize total
  }
}
```

For blueprint health, the separate ontology report exposes pageable rows:

```graphql
query BlueprintHealth {
  errors {
    checkedEntities checkedBlueprints
    blueprints(pageSize: 10) {
      items { id identifier title findings { code field message } }
      total
    }
  }
}
```

GraphQL does not project paths inside the `JSON` scalar: selecting `properties` returns that
whole map. Use typed metadata fields when those are sufficient. The schema is static across
blueprint edits; there is no generated GraphQL type per blueprint.

## Clients and credentials

`/api/v1/integration-clients` is ordinary JWT-authenticated REST, ADMIN-only for every method:
GET paginated list (including revoked rows), POST create, GET `/{id}`, POST `/{id}/revoke`.
Only `id` is sortable. Names are trimmed single-line labels of 1–100 characters. The registry
remains available when the GraphQL endpoint is disabled, allowing keys to be prepared first.

Create returns `201`, Location, and `{client, apiKey}`. A key is `toadie_int_` plus 43 URL-safe
characters encoding 256 random bits; it appears only in the create response and the UI's
one-time reveal panel. Only its SHA-256 digest is stored. Read/list responses never include
keys or hashes. Key-bearing responses use `Cache-Control: no-store`. `lastUsedAt` and
`revokedAt` are nullable timestamps. Revoke returns 204, repeated revocation 409, unknown id
404. There is no edit, re-enable, or delete operation; create another client to rotate a key.

V36 adds only `integration_clients`; existing ontology persistence is unchanged. `revoked_at`
is terminal removal, the documented exception to the business soft-delete column convention.
Revoked rows remain for administrator inspection. The creator FK references a login account,
but clients themselves are separate technical identities, never users or Port entities.
A conditional active-row update authenticates and stamps last use; if revocation wins that
write, authentication fails. Already-authorized requests may finish, matching session policy.

Normal login JWTs cannot authenticate integration routes; integration keys cannot authenticate
REST. Exactly one Authorization header is accepted. Invalid, unknown and revoked keys receive
the same 401 ProblemDetail, with safe reason codes only in audit output.
Machine identities are independent of their creating account: changing or deleting that login
does not revoke its clients. An administrator must explicitly revoke any client that should
lose access; the retained creator reference is provenance, not delegated user authorization.

### Scopes (2.15.0)

- **Applies when:** creating an integration client, authenticating a key, or deciding which
  integration surface a key may use.
- **Requirement:** every key carries an immutable `scope` — `read` (the GraphQL API and the MCP
  read tools) or `write` (additionally the MCP entity write tools: create, replace, import,
  delete entities; never blueprints). Rotate by creating a new client; there is no scope
  change. Every client, regardless of scope, owns a service account (`.claude/docs/
  authorization.md` "Service accounts (V41)") named after the client at
  `integration-client-<id>@toadie.invalid`, created in the same transaction as the client; its
  id rides the principal as `serviceUserId` and is what MCP entity writes stamp as `created_by`
  and audit as `byUserId`. Revoking the client soft-deletes the service account in the same
  transaction (the row stays for attribution; the entity's `creatorDeleted` becomes true). The
  GraphQL API stays read-only for every scope.
- **Reference:** `integration/IntegrationClient.kt` (`IntegrationScope`),
  `integration/IntegrationClientService.kt` (`create`, `revoke`, `authenticate`,
  `IntegrationClientPrincipal.writerId()`), `V42__integration_client_scope_and_service_user.sql`.
- **Enforcement:** `IntegrationClientTest`, `MigrationChecksumTest`.
- **Exception:** rows created before 2.15.0 keep `scope = read` and no service user (the V42
  CHECK permits it); they cannot be upgraded — create a new client for `write`.

## Deployment and limits

`INTEGRATION_ENABLED` defaults to false. Disabled integration paths return 404 even when the
SPA is served. The local Compose demo enables the API; other deployments opt in explicitly.
Kubernetes reads the optional `INTEGRATION_ENABLED` key from the existing `toadie-config`
ConfigMap. Enable it without replacing other configuration, then restart the app:

```bash
kubectl -n toadie patch configmap toadie-config --type merge \
  -p '{"data":{"INTEGRATION_ENABLED":"true"}}'
kubectl -n toadie rollout restart deployment/app
kubectl -n toadie rollout status deployment/app
```

Create the ConfigMap first using the Kubernetes setup guide if it does not exist. Setting the
key to `"false"` and restarting disables the API; applying `k8s/` preserves this opt-in.
REST and GraphQL use the same configured PostgreSQL database. All app replicas in a deployment
share its ontology and client registry. Compose and Kubernetes have separate databases by
default; samples loaded into one do not appear in the other.

Disabling the flag is the operational rollback: keep the additive migration/client records.
No dev data migration or destructive reset is needed. Existing HTTPS/proxy/CORS policy applies;
keys belong in server-side clients, not public browser bundles.

Execution has finite source/parser, depth, complexity, concurrency, deadline, retained-value,
and response budgets. Every page is at most 100 rows. Eager definition/page decoding is capped
at 1 MiB raw text / 8 MiB estimated decoded JSON per service read. This covers blueprint pages
and the active blueprint registry plus page documents used by entity reads; excessive pages
can be reduced, while an oversized registry requires smaller definitions. Entity dependencies
also retain the existing process-wide read ledger.
Computed values and generated findings count toward the cumulative decoded-value budget as
they are produced. Inherited-team filter resolution also holds a shared read-ledger reservation.
Exact execution constants are owned by `integration/IntegrationLimits.kt` and enforced in the
GraphQL regression suite. Identical reads are memoized only within one request; no cross-request
result cache or dynamic schema generation exists. This first schema has no nested database
lookups, so it does not need DataLoaders; add request-scoped batching before introducing them.

The authenticated body reader caps input at 256 KiB before JSON decoding, including variables;
the structural scan additionally limits JSON depth and node count. Authentication/body receipt
has a five-second deadline (HTTP 408), followed by a separate five-second cooperative execution
deadline (a GraphQL error). Four requests may be admitted at once; authenticated clients may
make 120 requests per minute. Shared retained GraphQL values are capped at 32 MiB, in addition
to per-request encoded-value and response caps of 4 MiB.

Transport/authentication/admission failures use RFC 7807 ProblemDetail. Parsed GraphQL documents
answer HTTP 200 with data and/or sanitized errors. Body/query/response-size refusal is an HTTP 413.
The integration follows Lettuce's legacy `application/json` GraphQL transport; it does not
claim full conformance to the evolving GraphQL-over-HTTP draft's newer response media type.

Audits: `integration_client.created/revoked`, `integration.auth_failed`, `integration.request`,
and integration throttling. Request events carry client identity, a bounded operation name,
and root response keys restricted to the fixed SDL root-name vocabulary. Arbitrary aliases are
omitted. Never log credentials, query text, variables, or result data. Audit output remains a
post-operation log, not a transactional outbox.

## Ontology revision

- **Applies when:** changing blueprint/entity write paths, the `HIERARCHY` dictionary replace, or
  any GraphQL field that pages `blueprints`/`entities`/`errors`.
- **Requirement:** `BlueprintPage.revision`, `EntityPage.revision`, and `OntologyErrors.revision`
  (each a decimal string, the `id` idiom) carry the V39 monotonic counter
  (`ch.nokillswit.infra.db.OntologyRevision.kt`) — bumped exactly once inside the SAME
  V27/V28-locked transaction as every committed blueprint write, entity write, and `HIERARCHY`
  dictionary replace (NAMESPACE/LIFECYCLE replaces never bump). This is change DETECTION, never a
  snapshot: the API stays stateless and answers no other query about the ontology's history.
  EQUAL revisions across the pages of one scan mean no ontology write committed between the FIRST
  page snapshot and the LAST; a consumer that sees the revision move restarts its scan rather than
  assembling a graph from two different states. Each blueprint/entity page materializes its
  total, definitions, filtered rows, dependency inputs, and revision in one read-only REPEATABLE
  READ transaction. The row SELECT carries the revision as a scalar subquery; a page with zero
  rows reads the counter directly in that same transaction. Both paths use the page's snapshot,
  even when a write commits between the page's SQL statements. The `errors` report issues several
  statements to gather its subjects, so its revision is read as the FIRST statement in its one
  transaction instead — the earliest possible snapshot the rest of the report could have seen, not
  a guarantee it also matches the report's LAST statement. The counter is a single database row,
  not process state: it holds across restarts and is shared by every replica reading the same
  database, so a multi-replica deployment sees ONE consistent revision regardless of which
  instance answers a given page.
- **Reference:** `infra/db/OntologyRevision.kt`, `blueprints/BlueprintService.kt`'s `create`/
  `applyUpdate`/`delete`, `entities/EntityService.kt`'s `create`/`applyUpdate`/`delete`,
  `dictionaries/DictionaryService.kt`'s `replace` (the `HIERARCHY`-only branch),
  `integration/Fetchers.kt`.
- **Enforcement:** `OntologyRevisionTest`.
- **Exception:** REST responses (`GET /api/v1/blueprints`, `GET /api/v1/entities`,
  `GET /api/v1/entities/errors`) do not carry a revision field — this is a GraphQL-only
  affordance for a multi-page/multi-root scanning consumer; a REST caller reads one page or one
  report at a time and has no cross-page consistency question to answer.

## Required regression boundaries

- **Applies when:** changing integration credentials, transport, schema, resolvers, limits, or UI.
- **Requirement:** preserve key/JWT separation, ADMIN-before-validation, revocation races,
  private-query exclusion, Port/REST parity, exact JSON numerics, bounded work and cancellation,
  and one-time reveal. No table access from GraphQL resolvers.
- **Reference:** `IntegrationClientTest`, `IntegrationClientSecurityTest`,
  `IntegrationGraphQlTest`, `IntegrationSchemaContractTest`, `IntegrationLimitsTest`,
  `IntegrationOntologyParityTest`, `OntologyReadBudgetTest`, `OntologyIntegrationReadTest`,
  `OntologyIntegrationFilterBudgetTest`, existing `EntityErrorsTest`, and the integration-client browser journey.
- **Enforcement:** backend tests/coverage/Detekt, SDL contract tests, REST OpenAPI conformance,
  generated-client drift/Spectral, frontend coverage, E2E spec/scenario/map parity and browser suite.
- **Exception:** ontology error pagination still requires a bounded workspace sweep; limits may
  refuse a large workspace. Already-authorized reads may finish after revocation.

## Repeatable deployment verification

The [seeded deployment smoke runner](../../e2e/README.md#seeded-graphql-deployment-smoke-test)
creates isolated Compose or local OrbStack deployments and tests the existing Port sample,
REST/GraphQL parity, schema discovery, real findings, replica sharing, restart persistence, and
key revocation. It never seeds the development workspace. Compose runs in CI; Kubernetes is an
explicit local verification command and does not replace ingress or authentication scaling work.
