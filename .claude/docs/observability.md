### Observability

`plugins/OpenTelemetry.kt` installs `KtorServerTelemetry` and obtains the SDK via `getOpenTelemetry("toadie")` from the `core` module. `plugins/Monitoring.kt` separately installs Dropwizard metrics (logged via SLF4J every 10s) and the `CallId` plugin using `X-Request-Id` (client-supplied ids are honored only when ≤64 URL-safe chars — they reach logs and OTel attributes, so junk is ignored rather than propagated). The SDK is also wired for **logs**: a Logback `OpenTelemetryAppender` (`server/src/main/resources/logback.xml`, the sole root appender — re-adding STDOUT would double every line, since the interim `console` logs exporter already prints to System.out) bridges every SLF4J log into the OTel logs SDK, and `configureOpenTelemetry` (`plugins/OpenTelemetry.kt`) installs the appender (flushing the pre-install boot-log buffer) while `getOpenTelemetry` (`core/.../OpenTelemetry.kt`) sets the interim exporter to `console`. Defaults are set via `addPropertiesSupplier` (the lowest-precedence config tier) **on purpose**, so the sink can be redirected to a collector by env alone with no code change — set `OTEL_LOGS_EXPORTER=otlp` + `OTEL_EXPORTER_OTLP_ENDPOINT` (the `opentelemetry-exporter-otlp` dep is already on the classpath); `OTEL_TRACES_EXPORTER` likewise. Metrics and traces exporters default to `none` (`otel.metrics.exporter`/`otel.traces.exporter`). The SDK is shut down on `ApplicationStopped` (`sdk.shutdown()`, bounded at 5 s); autoconfigure (1.67) already registers its own JVM shutdown hook, so what ours adds is flushing the batched exporters in order after Ktor's stop path instead of racing that hook, and releasing each application instance's SDK threads (which matters in the test suite) — the hook's later second `shutdown()` is idempotent. Log lines emitted after it on the stop path are dropped, because the OTel appender is the sole root appender; this is accepted. `OpenTelemetryShutdownTest` observes it through `OpenTelemetrySdkKey`. `docker-compose.yaml` and the k8s Deployment pass the three exporter selectors through (`OTEL_TRACES_EXPORTER`, `OTEL_METRICS_EXPORTER`, `OTEL_LOGS_EXPORTER`) with the code's own defaults, so they are behaviour-neutral until overridden; the endpoint is deliberately NOT defaulted (a http/protobuf default would be wrong for gRPC) — the operator adds `OTEL_EXPORTER_OTLP_ENDPOINT` (and `OTEL_EXPORTER_OTLP_PROTOCOL` when not http/protobuf) to ship to a collector. **Convention for non-fatal "this should never happen" events:** emit a WARN with the `SHOULD_NEVER_HAPPEN` marker (`MarkerFactory.getMarker`) plus key/value attributes — they flow through the appender to OTel. Reserve it for branches that are genuinely unreachable by ordinary requests (no current usage — Lettuce's history shows how easily an "unreachable" branch turns out to be a routine denial path).

**Audit trail.** Security-relevant events are structured INFO logs on the dedicated `ch.nokillswit.audit` logger with the `AUDIT` marker, emitted via `audit("area.event", "key" to value, …)` (`audit/Audit.kt`; a shared emitter such as the fetch handler takes its names as `AuditEvent("area.event")` literals from each caller — never an interpolated prefix, so `AuditCatalogTest`'s parity sweep keeps seeing every name) — they ride the same Logback→OTel pipeline, so shipping them to a collector/SIEM is env-only. Fields travel as SLF4J key/values, not in the message text (assert with `hasKeyValue` from `TestEnvironment.kt`). Emitted today:

- `login.success` / `login.failure` (with `reason`: unknown_email/wrong_password) / `login.lockout` (the throttle tripping) / `login.rejected_locked` (an attempt against a locked account),
- `login.mfa_challenge` (email/userId — correct credentials, second factor pending) / `login.mfa_success` (email/userId) / `login.mfa_failure` (with `reason`: unknown_challenge/expired/wrong_code/too_many_attempts/credentials_changed_or_user_gone) / `login.mfa_unavailable` (email/userId — MFA enabled on a mail-less deployment → 503) / `login.mfa_capacity_rejected` (email/userId — the live challenge store is full, conformant 429 without sending email) / `login.mfa_send_failed` (email/errorType — never the exception object or its message, since the MFA email body carries the 6-digit code),
- `logout`,
- `password_reset.requested` (email) / `password_reset.unknown_email` (email) / `password_reset.throttled` (email) / `password_reset.link_sent` (userId/email — delivery succeeded) / `password_reset.send_failed` (email/errorType — the email never left; never the exception object or its message, which may embed the message body) / `password_reset.rejected` (no fields — confirmation against an invalid/expired/replayed/stale grant, uniform across every rejection reason) / `password_reset.completed` (userId) / `password_reset.notification_failed` (userId/errorType — the post-reset notice failed to send after a successful reset) — the self-service reset trail; the async worker's events double as test barriers,
- `refresh.rejected` (with `reason`: invalid_or_expired/wrong_token_type/malformed/revoked/user_gone) / `session.rejected` (userId — the family failed to (re)register during login/renewal, e.g. renewal racing revocation),
- `password.changed` (targetUserId/byUserId/selfChange) / `password.change_denied` (wrong or missing current password),
- `user.features_changed` (byUserId/targetUserId/featuresFrom/featuresTo — only on an actual change),
- `user.language_changed` (the V18 per-user language — byUserId/targetUserId + `from`/`to` codes; emitted only on an actual change; `user.created` additionally carries `language` when non-default),
- `user.created` (byUserId/newUserId/email/roles — roles as STORED, i.e. the folded additional-roles set) / `user.updated` (name/email deltas, only when changed) / `user.roles_changed` (rolesFrom/rolesTo, both as stored) / `user.deleted` (byUserId/targetUserId),
- `catalog_file.created` / `catalog_file.updated` / `catalog_file.deleted` (byUserId/catalogFileId — every catalog-file mutation; an `allowInvalid` save that actually waived soft findings adds `waivedFindings` with the count; a file created through `POST …/import` carries an additional `import: true`, plus `withFindings: true` on `CREATED_WITH_FINDINGS` rows),
- `catalog_file.synced` (byUserId/catalogFileId — every repo→DB sync via `POST …/{id}/sync`; `waivedFindings` with the count when the sync waived soft findings — sync always waives, the import posture; the same field name and type as create/update, so one attribute never carries two types),
- `catalog_file.fetch_blocked` (byUserId/scheme/host — every SSRF-guard rejection on `POST …/fetch`; deliberately NOT the full URL, which may embed query-string tokens) / `catalog_file.fetched` (same fields — every SUCCESSFUL outbound fetch: the server pulled a body from a public host on user command, so the trail records the successes, not only the refusals),
- `dictionary.updated` (byUserId/dictionary/added/renamed/removed/default — every successful whole-document dictionary replace, `default` being the flagged value or null; a rejected save emits nothing),
- `label.created` (byUserId/labelId/key/values count/kinds) / `label.updated` (same fields) / `label.deleted` (byUserId/labelId) — every label-registry mutation; a rejected save emits nothing,
- `annotation_key.created` (byUserId/annotationKeyId/key/kinds) / `annotation_key.updated` (same fields) / `annotation_key.deleted` (byUserId/annotationKeyId) — every annotation-key-registry mutation; a rejected save emits nothing,
- `tag_category.created` (byUserId/categoryId/name/tags count/kinds) / `tag_category.updated` (same fields) / `tag_category.deleted` (byUserId/categoryId) — every tag-category mutation; a rejected save emits nothing,
- `lens.created` (byUserId/lensId/name/visibility) / `lens.updated` (same fields) / `lens.deleted` (byUserId/lensId) — every lens mutation; a rejected save emits nothing,
- `entity_query.created` (byUserId/entityQueryId/name/visibility) / `entity_query.updated` (same fields) / `entity_query.deleted` (byUserId/entityQueryId) — every saved-entity-query mutation (phase 7, v2.1.0, the lens shape); a rejected save emits nothing; applying a saved query is the unaudited graph read,
- `blueprint.created` (byUserId/blueprintId/identifier/properties count/relations count) / `blueprint.updated` (same fields, plus `system` true for the two V31 rows, `renamedFrom` and the `cascaded` count of other blueprints whose relation/aggregation targets were rewritten when the identifier changed) / `blueprint.deleted` (byUserId/blueprintId/identifier) — every blueprint mutation; a rejected save (400/409) emits nothing. Every committed pass-one write of `POST /api/v1/blueprints/import` (phase 6, v1.28.0) emits the SAME two events with `import: true` added (plus `system: true` on an updated system row, the `blueprint.updated` shape) — reduced to `byUserId`/`blueprintId`/`identifier`/`import` only, no `properties`/`relations`/`cascaded` counts; the event is emitted before further writes, even if pass two later returns `ERROR` or the batch is cancelled; pass two does not duplicate it. Rows with no committed write emit nothing, and the `/import/check` dry-run never audits,
- `blueprint.synced` (byUserId/blueprintId/identifier/properties count/relations count/`cascaded`,
  `renamedFrom` when renamed, `system` — the `blueprint.updated` field set, 2.10.0) /
  `blueprint.fetch_blocked` (byUserId/scheme/host — every SSRF-guard rejection on
  `POST …/blueprints/fetch`; deliberately NOT the full URL) / `blueprint.fetched` (same fields —
  every successful outbound fetch) — the blueprint source-reference & re-sync twin of
  `entity.synced`/`entity.fetch_blocked`/`entity.fetched` one level down; a sync that fails
  validation emits nothing (blueprints have no waiver either, so a rejected sync stores nothing
  to audit),
- `entity.created` (byUserId/entityId/blueprint/identifier/properties count/relations count — the STORED `properties` count, i.e. the request's, never the larger response `properties` phase 5's (v1.27.0) computed mirror/calculation/aggregation values inflate) / `entity.updated` (same fields, plus `cascaded` — the count of rewrites of `relations`, `team` field, and `format: team|user` property values across all entities when the identifier changed (v1.26.0, phase 4) — and `renamedFrom`; its own `properties` count is likewise the STORED count) / `entity.deleted` (byUserId/entityId/blueprint/identifier) — every entity mutation (Phase 2 of the Port data-model move); a rejected save (400/409) emits nothing. Every committed pass-one write of `POST /api/v1/entities/import` (phase 6, v1.28.0) emits the SAME two events with `import: true` added, reduced to `byUserId`/`entityId`/`blueprint`/`identifier`/`import` only; the event is emitted before further writes, even if pass two later returns `ERROR` or the batch is cancelled; pass two does not duplicate it. Rows with no committed write emit nothing, and the `/import/check` dry-run never audits,
- `entity.synced` (byUserId/entityId/blueprint/identifier/properties/relations/cascaded,
  `renamedFrom` when renamed — the `entity.updated` field set, 2.9.0) / `entity.fetch_blocked`
  (byUserId/scheme/host — every SSRF-guard rejection on `POST …/entities/fetch`; deliberately
  NOT the full URL) / `entity.fetched` (same fields — every successful outbound fetch) — the
  entity source-reference & re-sync twin of the catalog file's own `catalog_file.synced`/
  `catalog_file.fetch_blocked`/`catalog_file.fetched`; a sync that fails `entityFindings` emits
  nothing (entities have no waiver, so a rejected sync stores nothing to audit),
- `entity_types.created` (byUserId/entityTypesId/kind/types count) / `entity_types.updated` (same fields) / `entity_types.deleted` (byUserId/entityTypesId) — every type-dictionary mutation; a rejected save emits nothing,
- `authz.denied` (every 403, from the `ForbiddenException` handler in `plugins/ErrorHandling.kt`, with method/path/byUserId/detail),
- `integration_client.created` (byUserId/clientId/name/scope/serviceUserId — 2.15.0 adds the immutable key scope and the paired service account's user id) / `integration_client.revoked` (byUserId/clientId/serviceUserId — the paired service account soft-deleted with it, null for a pre-2.15.0 row),
- `integration.auth_failed` (reason: missing_or_malformed/unknown_or_revoked; no credential text),
- `integration.rate_limited` (clientId/clientName — the request-level per-client bucket AND, since
  2.15.0, every MCP tool call within one batch beyond the first that draws the same bucket dry),
- `integration.request` (clientId/clientName/operationName/rootFields; bounded operation metadata, no query, variables or result data).
- `integration.mcp_call` (clientId/clientName/tool/ok — every MCP `tools/call`, 2.15.0; never the arguments or the result; every call that STARTED leaves exactly one event: one cancelled by the request deadline or a disconnect audits `ok=false` before the cancellation propagates, since its writes may already be committed, while a batched call cancelled while still waiting for the request lock never started and leaves none). An MCP entity write additionally emits the ordinary `entity.created`/`entity.updated` (`import: true`, via `entities/EntityAudit.kt`) or `entity.deleted` event with `byUserId` = the client's service account and `clientId` appended.
- `integration.scope_denied` (clientId/clientName/tool — a read-scope key calling an MCP write tool, 2.15.0)

**Not audit events.** The entity query engine's (phase 7, v2.0.0) ONE DEBUG line on
`ch.nokillswit.entityquery` per evaluation-budget miss — the refusal code (`DEADLINE_EXCEEDED`/
`BINDING_LIMIT`) and the query's LENGTH, never its text, which may carry business data — is an
operational signal, not an audit event (the text still rides the GET request line into access/
proxy logs, like `q`; the server itself never writes it); the graph GET's `query` and `POST …/entities/query/check`
are pure reads and audit nothing (`.claude/docs/authorization.md`). `GET …/entities/errors` (Phase 8, v2.5.0 — the Port-world Errors report) is the same rule: a pure read, the `/check`/`/errors`/export idiom, and audits nothing. The bounded jq executor's (v1.29.0) two WARN events on
`ch.nokillswit.entities.computed` — one per calculation quarantine, one per pool-saturation
episode (see `.claude/docs/security.md` "Computed-property evaluation (jq)") — ride the same
Logback→OTel pipeline as everything above but are deliberately NOT `audit(...)` events: neither
is a mutation or a denial, they are operational signals about a runaway or overloaded
expression. The INPUT document is never logged, on either logger. The read ledger's (2.19.0)
ONE INFO line on `ch.nokillswit.infra.concurrency.ReadLedger` per refused whole-workspace read —
`ownRequest`, the requested bytes, the in-flight total and the capacity, never a caller or any
document content — is the same kind of operational signal; the `400`/`429` it accompanies audits
nothing (`.claude/docs/security.md` "Entity read memory budget"). The response deadline's ONE WARN on
`ch.nokillswit.infra.db` per discarded connection — the kind of exchange (statement, commit, rollback, …) and
the bound in seconds, never SQL, parameters or a caller (`infra/db/ResponseDeadline.kt`, `.claude/docs/persistence.md`
"Response deadline: every exchange below the pool") — is an operational signal too, not an audit event; the failure
it causes reaches the caller as the catch-all `500`.

**User mutation deltas.** Identity/role, disabled-feature, and language writes return their
actual predecessor from the same row-locked transaction as the mutation. Routes audit after
success using that predecessor, never a separate pre-write GET. Same-value writes emit no
delta event; overlapping reversals emit their real from/to values. See `persistence.md` for
administrator/target lock ordering and `UserAuditConcurrencyTest` for the held-write regression.

**Two trails, on purpose.** The `audit(...)` events above are the SECURITY log: structured, field-poor, shipped to a collector/SIEM, covering denials and every mutation. The catalog file's **change history** (`catalog_file_events`, V23 — see `.claude/docs/persistence.md`) is the PRODUCT feature: user-facing, per document, field-level, read back through `GET /api/v1/files/{id}/events` and rendered in the viewer's language. Product history is inserted in the catalog mutation transaction; the route emits its security audit only after the service returns successfully. Neither replaces the other — a catalog mutation extends BOTH, and a new history-bearing feature should say so here.

Field-naming convention: the acting caller is `byUserId` everywhere except the auth lifecycle events (`login.*`, `logout`, `refresh.rejected`, `session.rejected`, `password_reset.*`), where `userId` identifies the account being authenticated (or, on the public password-reset routes, acted upon).

Never log secrets (passwords, tokens); emails/ids are fine. Never log a throwable that can carry token or credential text: auth0's `JWTDecodeException` message embeds the decoded token segment (the logout debug lines log `cause.javaClass.simpleName` only; `LogoutTest` pins it), and the Flyway boot line logs `jdbcUrlForLogging(url)` — `host[:port]/db` only: string-based (no `java.net.URI`), a `//` authority accepted only straight after `jdbc:<subprotocol>:`, userinfo stripped at the last `@` before the authority is cut, the host/port/database strictly pattern-checked, and a fixed placeholder for anything unparseable or ambiguous (a `?`/`;` before the last `@` could be a password or a parameter value) (`FlywayLogTest`). When adding a security-relevant mutation or denial path, emit an `audit(...)` event alongside it and extend this list in the same change (the one sanctioned exception: the graph-layout PUT and its V30 `entity-graph-layout` twin — both pure per-user view state written on every drag stop, documented in `.claude/docs/authorization.md`) — in Lettuce this catalog grows to every user/team/content mutation, and the convention transfers wholesale. Tested in `AuditTest` via a Logback `ListAppender` on the audit logger (the shared `LogCapture` helper in `TestEnvironment.kt`).

**Database failures are logged by class and SQLSTATE, never by message.**

- **Applies when:** logging a throwable that may come from a query (the catch-all 500, the readiness
  WARN, any new `log.*(…, throwable)` on a path that touches the database).
- **Requirement:** pass it through `Throwable.safeForLog()` (`plugins/ErrorHandling.kt`). When an R2DBC or
  Exposed exception sits anywhere in the cause chain it logs a `DatabaseFailureLogged` stand-in carrying
  only the chain's exception classes, the SQLSTATE and the original stack trace: `ExposedR2dbcException`
  appends the failed statements to its message (Exposed 1.6.0 R2DBC renders an unexpandable placeholder,
  which nothing pins) and the driver message can quote data, so no database message is ever logged — a
  lock wait cancelled by `statement_timeout` (57014) on a catalog write must not risk the document. Any other throwable is logged unchanged. The catch-all 500 line also carries the
  request PATH only, never the query string (it can hold an entity query's text or filter values).
- **Reference:** `plugins/ErrorHandling.kt` (`respondInternalError`, `safeForLog`), `plugins/Health.kt`.
- **Enforcement:** `DatabaseFailureLogTest` (a real 57014 on a blocked write and a blocked read: SQLSTATE
  present, the document text and the query-string value absent).
- **Exception:** none.

### Integration audit

The machine API additionally audits reads as `integration.request` and MCP tool calls as `integration.mcp_call`, with bounded client and
operation/root metadata only. Client create/revoke, authentication refusal and throttling are
audited; keys, query text, variables and returned data are never logged. See
[integration-api.md](integration-api.md) for the exact scope and delivery limitations.
