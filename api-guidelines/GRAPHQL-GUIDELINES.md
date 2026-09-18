# GraphQL integration API guidelines

The GraphQL sibling of `API-GUIDELINES.md`, adapted from Lettuce's SDL-first architecture.
These rules govern `/integration/graphql`; JWT-authenticated integration-client management
remains governed by the REST guidelines. Domain and operational details live in
[the integration reference](../.claude/docs/integration-api.md).

| Rule | Requirement | Enforcement |
|---|---|---|
| GQL-CON-001 | Load the committed `server/src/main/resources/graphql/schema.graphqls` as the executable contract; never generate a second schema from code. | SDL contract tests and boot wiring review |
| GQL-CON-002 | No Mutation or Subscription. Root scope is blueprints, blueprint, entities, entity, errors. | SDL root/type assertions |
| GQL-CON-003 | Evolve additively. Deprecate a member in a minor release, naming its removal major version; remove/rename/tighten nullability only in that major release. | `:server:checkGraphqlCompatibility` in `check`; explicit major-version policy review and release notes |
| GQL-CON-004 | Describe every type, field, argument and enum value; authenticated SDL discovery and standard introspection must work. | Schema documentation walk and introspection tests |
| GQL-NAME-001 | Preserve REST field names. IDs use ID decimal strings, timestamps/totals Long, dynamic Port maps JSON with exact numeric values. | Contract and numeric round-trip tests |
| GQL-LIST-001 | Page collections with items/page/pageSize/total, one-based/default20/max100; document filtering, ordering, and full checked counts versus finding-row totals. | Paging and Errors parity regressions |
| GQL-LIST-002 | Any future nested database lookup must batch through request-scoped loaders; no per-row SQL and no cross-request result cache. | Resolver/service review (v1 has no nested SQL) |
| GQL-SEC-001 | Require a dedicated integration key; fail uniformly with 401. ADMIN-only key lifecycle; SHA-256 at rest, one-time reveal, terminal race-safe revocation. | Client/auth/race tests and browser journey |
| GQL-SEC-002 | Read through owning services with existing active-row, computed-property, ownership and read-budget rules; keys read the shared Port workspace. | Resolver review and service-parity tests |
| GQL-SEC-003 | Exclude login accounts, Backstage, saved-query content, credentials and capability flags. User-defined JSON is workspace data, not an automatically sanitized secret store. | Scope/unknown-field/private-query regressions |
| GQL-SEC-004 | Disabled by default; reserve the integration namespace from SPA fallback. Apply admission and authenticated-client rate limits to queries and SDL discovery. | Configuration/auth/admission tests |
| GQL-ERR-001 | Transport failures are ProblemDetail; parsed GraphQL documents use HTTP200 application/json with data/errors. Response-size refusal is HTTP413. | HTTP response tests |
| GQL-ERR-002 | Return safe argument/budget errors and sanitize unexpected failures. Propagate caller cancellation; translate only this execution's deadline. | Error/cancellation/deadline tests |
| GQL-OPS-001 | Bound parser input, depth, weighted cost, retained values, concurrent work, execution time, and encoded responses. Aliases/fragments/variables cannot evade limits. | Limits/shape/large-value tests and executor review |
| GQL-OPS-002 | Audit identity and bounded operation/root metadata, never credentials/query text/variables/results. | Audit regressions |

`JSON` is a deliberate addition to Lettuce's scalar set: Port blueprints and values carry
user-defined property keys and arbitrary JSON subtrees. `ID` instead of GraphQL `Int` avoids
narrowing Toadie's UInt database identifiers to signed 31-bit values. Selected optional GraphQL
fields are null; absent keys inside JSON retain Port's wire semantics.

This API deliberately supports the established `application/json` transport used by Lettuce.
It does not advertise `application/graphql-response+json` or claim complete compliance with
that evolving transport draft. See the primary references:
[GraphQL over HTTP](https://http-spec.graphql.org/draft/) and
[graphql-java limits](https://www.graphql-java.com/documentation/limits/).

## Schema compatibility gate

`./gradlew :server:checkGraphqlCompatibility` compares the current SDL with its Git baseline,
without starting PostgreSQL. It runs as part of `:server:check` and therefore the backend CI
job and required **Quality gate**. It executes on every invocation rather than reusing an
up-to-date result for a moving Git reference.

Baseline precedence is `-PgraphqlBaselineRef=<ref>`, then `GRAPHQL_BASELINE_REVISION`, then
`origin/master`. Empty/all-zero CI before revisions fall back to `origin/master`. The CI job
fetches full history and supplies the PR target SHA, merge-group base SHA, or pre-push SHA.
A missing baseline revision or schema fails with an actionable error; it never silently skips
compatibility. Fetch the relevant history before running against a shallow local checkout.

The gate rejects removal/renaming of existing members, type/nullability changes, newly required
inputs, and changes to existing defaults. This is deliberately stricter than client-side
GraphQL compatibility alone: adding an output non-null guarantee is also a contract change under
GQL-CON-003. Additive optional fields/types/arguments and documentation/order-only edits pass.
An intentional major-version break needs explicit review of the baseline/gate policy alongside
consumer migration and release notes; changing the displayed version does not bypass this gate.
