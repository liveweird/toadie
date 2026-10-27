### Observability

`plugins/OpenTelemetry.kt` installs `KtorServerTelemetry` and obtains the SDK via `getOpenTelemetry("toadie")` from the `core` module. `plugins/Monitoring.kt` separately installs Dropwizard metrics (dumped to the `metrics` logger by an `Slf4jReporter` every `monitoring.reporterIntervalSeconds` — env `METRICS_REPORT_INTERVAL_SECONDS`, default 300, `0` = off, otherwise 30..3600, anything else refuses boot; it was a fixed 10 s, which with Dropwizard's JVM gauge sets enumerating every thread was a steady idle-allocation cost — see "Telemetry" below for the registry) and the `CallId` plugin using `X-Request-Id` (client-supplied ids are honored only when ≤64 URL-safe chars — they reach logs and OTel attributes, so junk is ignored rather than propagated). The SDK is also wired for **logs**: a Logback `OpenTelemetryAppender` (`server/src/main/resources/logback.xml`, the sole root appender — re-adding STDOUT would double every line, since the interim `console` logs exporter already prints to System.out) bridges every SLF4J log into the OTel logs SDK, and `configureOpenTelemetry` (`plugins/OpenTelemetry.kt`) installs the appender (flushing the pre-install boot-log buffer) while `getOpenTelemetry` (`core/.../OpenTelemetry.kt`) sets the interim exporter to `console`. Defaults are set via `addPropertiesSupplier` (the lowest-precedence config tier) **on purpose**, so the sink can be redirected to a collector by env alone with no code change — set `OTEL_LOGS_EXPORTER=otlp` + `OTEL_EXPORTER_OTLP_ENDPOINT` (the `opentelemetry-exporter-otlp` dep is already on the classpath); `OTEL_TRACES_EXPORTER` likewise. Metrics and traces exporters default to `none` (`otel.metrics.exporter`/`otel.traces.exporter`). The SDK is shut down on `ApplicationStopped` (`sdk.shutdown()`, bounded at 5 s); autoconfigure (1.67) already registers its own JVM shutdown hook, so what ours adds is flushing the batched exporters in order after Ktor's stop path instead of racing that hook, and releasing each application instance's SDK threads (which matters in the test suite) — the hook's later second `shutdown()` is idempotent. Log lines emitted after it on the stop path are dropped, because the OTel appender is the sole root appender; this is accepted. `OpenTelemetryShutdownTest` observes it through `OpenTelemetrySdkKey`. `docker-compose.yaml` and the k8s Deployment pass the three exporter selectors through (`OTEL_TRACES_EXPORTER`, `OTEL_METRICS_EXPORTER`, `OTEL_LOGS_EXPORTER`) with the code's own defaults, so they are behaviour-neutral until overridden; the endpoint is deliberately NOT defaulted (a http/protobuf default would be wrong for gRPC) — the operator adds `OTEL_EXPORTER_OTLP_ENDPOINT` (and `OTEL_EXPORTER_OTLP_PROTOCOL` when not http/protobuf) to ship to a collector. **Convention for non-fatal "this should never happen" events:** emit a WARN with the `SHOULD_NEVER_HAPPEN` marker (`MarkerFactory.getMarker`) plus key/value attributes — they flow through the appender to OTel. Reserve it for branches that are genuinely unreachable by ordinary requests (no current usage — Lettuce's history shows how easily an "unreachable" branch turns out to be a routine denial path).

**Audit trail.** Security-relevant events are structured INFO logs on the dedicated `ch.nokillswit.audit` logger with the `AUDIT` marker, emitted via `audit("area.event", "key" to value, …)` (`audit/Audit.kt`; a shared emitter such as the fetch handler takes its names as `AuditEvent("area.event")` literals from each caller — never an interpolated prefix, so `AuditCatalogTest`'s parity sweep keeps seeing every name) — they ride the same Logback→OTel pipeline, so shipping them to a collector/SIEM is env-only. Fields travel as SLF4J key/values, not in the message text (assert with `hasKeyValue` from `TestEnvironment.kt`). Emitted today:

- `login.success` / `login.failure` (with `reason`: unknown_email/wrong_password/no_password — `no_password` is a passwordless account, V48, which answers the same 401 as a wrong password) / `login.lockout` (the throttle tripping) / `login.rejected_locked` (an attempt against a locked account),
- `login.mfa_challenge` (email/userId — correct credentials, second factor pending) / `login.mfa_success` (email/userId) / `login.mfa_failure` (with `reason`: unknown_challenge/expired/wrong_code/too_many_attempts/credentials_changed_or_user_gone) / `login.mfa_unavailable` (email/userId — MFA enabled on a mail-less deployment → 503) / `login.mfa_capacity_rejected` (email/userId — the live challenge store is full, conformant 429 without sending email) / `login.mfa_send_failed` (email/errorType — never the exception object or its message, since the MFA email body carries the 6-digit code),
- `logout`,
- `password_reset.requested` (email) / `password_reset.unknown_email` (email) / `password_reset.throttled` (email) / `password_reset.link_sent` (userId/email — delivery succeeded) / `password_reset.skipped` (userId/reason=`no_password` — a passwordless SSO-only account, V48: no token minted, no email sent, the request still answered 202) / `password_reset.send_failed` (email/errorType — the email never left; never the exception object or its message, which may embed the message body) / `password_reset.rejected` (no fields — confirmation against an invalid/expired/replayed/stale grant, uniform across every rejection reason) / `password_reset.completed` (userId) / `password_reset.notification_failed` (userId/errorType — the post-reset notice failed to send after a successful reset) — the self-service reset trail; the async worker's events double as test barriers,
- `refresh.rejected` (with `reason`: invalid_or_expired/wrong_token_type/malformed/revoked/user_gone) / `session.rejected` (userId — the family failed to (re)register during login/renewal, e.g. renewal racing revocation),
- `password.changed` (targetUserId/byUserId/selfChange) / `password.change_denied` (wrong or missing current password),
- `user.features_changed` (byUserId/targetUserId/featuresFrom/featuresTo — only on an actual change),
- `user.language_changed` (the V18 per-user language — byUserId/targetUserId + `from`/`to` codes; emitted only on an actual change; `user.created` additionally carries `language` when non-default),
- `user.created` (byUserId/newUserId/email/roles — roles as STORED, i.e. the folded additional-roles set) / `user.updated` (name/email deltas, only when changed) / `user.roles_changed` (rolesFrom/rolesTo, both as stored) / `user.deleted` (byUserId/targetUserId),
- `catalog_file.created` / `catalog_file.updated` / `catalog_file.deleted` (byUserId/catalogFileId — every catalog-file mutation; an `allowInvalid` save that actually waived soft findings adds `waivedFindings` with the count; a file created through `POST …/import` carries an additional `import: true`, plus `withFindings: true` on `CREATED_WITH_FINDINGS` rows; a `replaceExisting` import audits each replaced row as `catalog_file.updated` with `import: true`, the `waivedFindings` count and `withFindings: true` on `UPDATED_WITH_FINDINGS`, also for `UNCHANGED` rows, as a no-op PUT is),
- `catalog_file.synced` (byUserId/catalogFileId — every repo→DB sync via `POST …/{id}/sync`; `waivedFindings` with the count when the sync waived soft findings — sync always waives, the import posture; the same field name and type as create/update, so one attribute never carries two types),
- `catalog_file.fetch_blocked` (byUserId/scheme/host — every SSRF-guard rejection on `POST …/fetch`; deliberately NOT the full URL, which may embed query-string tokens) / `catalog_file.fetched` (same fields — every SUCCESSFUL outbound fetch: the server pulled a body from a public host on user command, so the trail records the successes, not only the refusals),
- `dictionary.updated` (byUserId/dictionary/added/renamed/removed/default — every successful whole-document dictionary replace, `default` being the flagged value or null; a rejected save emits nothing),
- `label.created` (byUserId/labelId/key/values count/kinds) / `label.updated` (same fields) / `label.deleted` (byUserId/labelId) — every label-registry mutation; a rejected save emits nothing,
- `annotation_key.created` (byUserId/annotationKeyId/key/kinds) / `annotation_key.updated` (same fields) / `annotation_key.deleted` (byUserId/annotationKeyId) — every annotation-key-registry mutation; a rejected save emits nothing,
- `tag_category.created` (byUserId/categoryId/name/tags count/kinds) / `tag_category.updated` (same fields) / `tag_category.deleted` (byUserId/categoryId) — every tag-category mutation; a rejected save emits nothing,
- `lens.created` (byUserId/lensId/name/visibility) / `lens.updated` (same fields) / `lens.deleted` (byUserId/lensId) — every lens mutation; a rejected save emits nothing,
- `entity_query.created` (byUserId/entityQueryId/name/visibility) / `entity_query.updated` (same fields) / `entity_query.deleted` (byUserId/entityQueryId) — every saved-entity-query mutation (phase 7, v2.1.0, the lens shape); a rejected save emits nothing; applying a saved query is the unaudited graph read,
- `conversion_mapping.created` (byUserId/mappingId/name/pairs count) / `conversion_mapping.updated` (same fields — the STORED, sanitized name and the stored document's pair count) / `conversion_mapping.deleted` (byUserId/mappingId) — every conversion-mapping-registry mutation (ADMIN-only); a rejected save (blocking findings, `409`, a full registry) emits nothing, and the document itself is never logged,
- `blueprint.created` (byUserId/blueprintId/identifier/properties count/relations count) / `blueprint.updated` (same fields, plus `system` true for the two V31 rows, `renamedFrom` and the `cascaded` count of other blueprints whose relation/aggregation targets were rewritten when the identifier changed) / `blueprint.deleted` (byUserId/blueprintId/identifier) — every blueprint mutation; a rejected save (400/409) emits nothing. Every committed pass-one write of `POST /api/v1/blueprints/import` (phase 6, v1.28.0) emits the SAME two events with `import: true` added (plus `system: true` on an updated system row, the `blueprint.updated` shape) — reduced to `byUserId`/`blueprintId`/`identifier`/`import` only, no `properties`/`relations`/`cascaded` counts; the event is emitted before further writes, even if pass two later returns `ERROR` or the batch is cancelled; pass two does not duplicate it. Rows with no committed write emit nothing, and the `/import/check` dry-run never audits,
- `blueprint.synced` (byUserId/blueprintId/identifier/properties count/relations count/`cascaded`,
  `renamedFrom` when renamed, `system` — the `blueprint.updated` field set, 2.10.0) /
  `blueprint.fetch_blocked` (byUserId/scheme/host — every SSRF-guard rejection on
  `POST …/blueprints/fetch`; deliberately NOT the full URL) / `blueprint.fetched` (same fields —
  every successful outbound fetch) — the blueprint source-reference & re-sync twin of
  `entity.synced`/`entity.fetch_blocked`/`entity.fetched` one level down; a sync that fails
  validation emits nothing (blueprints have no waiver either, so a rejected sync stores nothing
  to audit),
- `entity.created` (byUserId/entityId/blueprint/identifier/properties count/relations count — the STORED `properties` count, i.e. the request's, never the larger response `properties` phase 5's (v1.27.0) computed mirror/calculation/aggregation values inflate) / `entity.updated` (same fields, plus `cascaded` — the count of rewrites of `relations`, `team` field, and `format: team|user` property values across all entities when the identifier changed (v1.26.0, phase 4) — and `renamedFrom`; its own `properties` count is likewise the STORED count) / `entity.deleted` (byUserId/entityId/blueprint/identifier) — every entity mutation (Phase 2 of the Port data-model move); a rejected save (400/409) emits nothing. Every committed pass-one write of `POST /api/v1/entities/import` (phase 6, v1.28.0) emits the SAME two events with `import: true` added, reduced to `byUserId`/`entityId`/`blueprint`/`identifier`/`import` only; the event is emitted before further writes, even if pass two later returns `ERROR` or the batch is cancelled; pass two does not duplicate it. Rows with no committed write emit nothing — a `STALE` row (2.29.0, an `expected` write-version that no longer holds) is a refused write and emits nothing, with no new event — and the `/import/check` dry-run never audits,
- `entity.synced` (byUserId/entityId/blueprint/identifier/properties/relations/cascaded,
  `renamedFrom` when renamed — the `entity.updated` field set, 2.9.0) / `entity.fetch_blocked`
  (byUserId/scheme/host — every SSRF-guard rejection on `POST …/entities/fetch`; deliberately
  NOT the full URL) / `entity.fetched` (same fields — every successful outbound fetch) — the
  entity source-reference & re-sync twin of the catalog file's own `catalog_file.synced`/
  `catalog_file.fetch_blocked`/`catalog_file.fetched`; a sync that fails `entityFindings` emits
  nothing (entities have no waiver, so a rejected sync stores nothing to audit),
- `entity_types.created` (byUserId/entityTypesId/kind/types count) / `entity_types.updated` (same fields) / `entity_types.deleted` (byUserId/entityTypesId) — every type-dictionary mutation; a rejected save emits nothing,
- `authz.denied` (every 403, from the `ForbiddenException` handler in `plugins/ErrorHandling.kt`, with method/path/byUserId/detail — the path without its query string, see "A logged request location is its path" below),
- `integration_client.created` (byUserId/clientId/name/scope/serviceUserId — 2.15.0 adds the immutable key scope and the paired service account's user id) / `integration_client.revoked` (byUserId/clientId/serviceUserId — the paired service account soft-deleted with it, null for a pre-2.15.0 row),
- `integration.auth_failed` (reason: missing_or_malformed/unknown_or_revoked; no credential text),
- `integration.rate_limited` (clientId/clientName — the request-level per-client bucket AND, since
  2.15.0, every MCP tool call within one batch beyond the first that draws the same bucket dry),
- `integration.request` (clientId/clientName/operationName/rootFields; bounded operation metadata, no query, variables or result data).
- `integration.mcp_call` (clientId/clientName/tool/ok — every MCP `tools/call`, 2.15.0; never the arguments or the result; every call that STARTED leaves exactly one event: one cancelled by the request deadline or a disconnect audits `ok=false` before the cancellation propagates, since its writes may already be committed, while a batched call cancelled while still waiting for the request lock never started and leaves none). An MCP entity write additionally emits the ordinary `entity.created`/`entity.updated` (`import: true`, via `entities/EntityAudit.kt`) or `entity.deleted` event with `byUserId` = the client's service account and `clientId` appended.
- `integration.scope_denied` (clientId/clientName/tool — a read-scope key calling an MCP write tool, 2.15.0)

**Not audit events.** The telemetry sampler's (`telemetry/`, "Telemetry" below) WARN/INFO lines on
`ch.nokillswit.telemetry.TelemetrySampler` — one WARN per failing collector per episode ("Telemetry collector <name> failed: ..."),
one INFO when it recovers (the heavy phase's budget miss is the same kind, collector "heavy"), never a stack, at most 300 characters, a database failure rendered by class and SQLSTATE only — are
operational signals, not audit events: the sampler reads the process's own counters and mutates no project data, so it
emits no `audit(...)` event at all. The entity query engine's (phase 7, v2.0.0) ONE DEBUG line on
`ch.nokillswit.entityquery` per evaluation-budget miss — the refusal code (`DEADLINE_EXCEEDED`/
`BINDING_LIMIT`) and the query's LENGTH, never its text, which may carry business data — is an
operational signal, not an audit event (the text still rides the GET request line into access/
proxy logs, like `q`; the server itself never writes it); the graph GET's `query` and `POST …/entities/query/check`
are pure reads and audit nothing (`.claude/docs/authorization.md`). `GET …/entities/errors` (Phase 8, v2.5.0 — the Port-world Errors report) is the same rule: a pure read, the `/check`/`/errors`/export idiom, and audits nothing; so is
`GET …/conversion-mappings/{id}/catalog-documents` (conversion phase 2 — the Port -> Backstage export computes
documents and stores nothing; the writes happen later, through the ordinary imports, which audit as usual), and so is
`POST …/conversion-mappings/{id}/entity-documents` (phase 4 — the Backstage -> Port conversion is a pure computation behind
`POST` only because its input is a body). The bounded jq executor's (v1.29.0) two WARN events on
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
the bound (seconds, or milliseconds for a sub-second bound), never SQL, parameters or a caller (`infra/db/ResponseDeadline.kt`, `.claude/docs/persistence.md`
"Response deadline: every exchange below the pool") — is an operational signal too, not an audit event; the failure
it causes reaches the caller as the catch-all `500`. Routes audit only after success, so a COMMIT that timed out
and then applies at the thaw leaves a committed mutation (an admin role change, say) that answered `500` with NO
audit line (`persistence.md` "A hung database is bounded", Exception (i)); catalog and entity history is unaffected,
it commits in the same transaction.

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
  lock wait cancelled by `statement_timeout` (57014) on a catalog write must not risk the document. Any other throwable is logged unchanged. The catch-all 500 line names the request
  by its path ("A logged request location is its path", below).
- **Reference:** `plugins/ErrorHandling.kt` (`respondInternalError`, `safeForLog`), `plugins/Health.kt`.
- **Enforcement:** `DatabaseFailureLogTest` (a real 57014 on a blocked write and a blocked read: SQLSTATE
  present, the document text and the query-string value absent).
- **Exception:** none.

**A logged request location is its path, never its query string.**

- **Applies when:** an audit event or a log line records which request it concerns.
- **Requirement:** record `request.path()` (and the method), never `request.uri`, `request.local.uri` or the
  query string: query parameters carry search text and filter values (the Users list filters by email) and the
  entity query's text (`GET …/entities/graph?query=`), none of which the server may log. Name the specific
  parameter when one matters (`group`, `range`), never the whole query.
- **Reference:** `plugins/ErrorHandling.kt` (the `authz.denied` audit, the catch-all 500 line).
- **Enforcement:** `AuditTest` "a denial audits the request path without its query string" (a denied
  `GET /api/v1/users?email=…` audits `/api/v1/users` and no field carries the value), `DatabaseFailureLogTest`
  (the 500 line); manual review for a new log line.
- **Exception:** HTTP server trace spans. When an operator enables a trace exporter (`OTEL_TRACES_EXPORTER`,
  `none` by default), the OpenTelemetry Ktor instrumentation records the HTTP semantic conventions'
  `url.query`. Its built-in redaction blanks only a fixed set of signature/credential parameters
  (`AWSAccessKeyId`, `Signature`, `X-Amz-*`, `sig`, `X-Goog-Signature`), so `email`, `q` and `query` values reach
  the span unchanged — the same exposure class as the GET request line in access and proxy logs, opted into
  outside the application.

### Telemetry

The in-app telemetry sampler (`telemetry/`, ported from Flow 1.17.0; the ADMIN read API below, the panel arrives in a later change)
records the process's OWN vital signs (memory, JVM, requests, workspace storage, pressure) into `telemetry_processes`/`telemetry_samples` (V46, `.claude/docs/persistence.md` "Telemetry
(V46)"). Numbers and labels only; it is a best-effort observer that never touches readiness or request handling.

- **Sampler.** `configureTelemetry` validates `telemetry.*` on every boot (enabled or not), then on `ApplicationStarted` builds one
  `TelemetrySampler` (published as `TelemetrySamplerKey`) whose loop ticks 5 s after start and then aligned to
  `telemetry.sampleIntervalSeconds` (env `TELEMETRY_SAMPLE_INTERVAL_SECONDS`, default 300, 60..1800 and a divisor of 3600, so an
  hour's samples tile it; the stored raw `resolution` and, capped at 300 s, the request timers' window). `TELEMETRY_ENABLED=false`
  starts none; `TELEMETRY_STORAGE_INTERVAL_SECONDS` (900..86400, default 3600) is how often the workspace is measured (the heavy
  phase below). `ApplicationStopping` cancels the scope and joins it for at most 2 s, BEFORE the pool is
  disposed on `ApplicationStopped`.
- **A tick** runs under `interval / 2`: the collectors (JVM, cgroup, pool, requests, pressure; each in `catchingFailures`, isolated, one WARN per
  episode and one INFO "recovered"), the process row's `last_seen` stamp (re-registering if the row vanished), the
  readings written as multi-row `INSERT ... ON CONFLICT DO NOTHING` statements, one per power-of-two chunk, all in one
  transaction (98 rows go out as 64 + 32 + 2; `infra/db/MultiRowInsert.kt`, fully parameterized; non-finite values dropped), then the collectors' COMMIT steps (below), then the CLUSTER section — `pg_try_advisory_xact_lock` in one transaction, skipped
  when the `telemetry.cluster_ms` marker already sits at this bucket, else the retention (hourly rollup of every complete hour that
  ended at least `max(10 min, interval / 2)` ago; prune only after a rollup that inserted) in a savepoint, then the marker. Raw rows
  live 7 days, hourly rollups and silent processes 90. The process identity (`HOSTNAME`, else a resolver lookup that can block for
  seconds) is resolved inside the sampler's coroutine before the first tick, never in the synchronous startup handler.
- **Delta baselines advance only for what was stored.** The collectors that difference a cumulative figure (requests: `previous`;
  pressure: the refusal counters, and the read ledger's interval peak) keep their baseline until the sampler has durably inserted
  the tick's readings and then calls the collector's `commit` (`ProcessCollector.commit`): a failed insert (a database outage)
  therefore loses no interval - the next tick reports both intervals' 5xx, 429s, refusals and the ledger's peak. The ledger's
  `snapshot()` resets nothing; `endInterval()` does, at commit, handing the next interval the highest total reached since that
  snapshot (a burst between the snapshot and the commit is kept). Each collector commits on its own, and one that threw is not
  committed, so no collector consumes another's baseline. A tick whose bucket is not newer than the newest one stored (the wall
  clock stepped back, or a second tick in the same bucket) is skipped before it collects and before the cluster section (a failed
cluster section is retried in the next bucket, not the same one), because its insert would write nothing
  (`ON CONFLICT` keeps the first row) yet commit the baselines: its deltas roll into the next bucket. (`JvmProbe`'s CPU and GC
  deltas, and `mem.old_gen_peak`, which is reset on read, still advance when read: a failed insert loses them.) **Delivery is
  at-least-once:** an insert whose outcome is unknown - a lost reply to COMMIT, or the tick budget cancelling while it awaits the
  COMMIT - is not committed by the sampler, so if the rows were in fact stored the next tick counts that interval again (the
  deltas of both intervals land in the next bucket). `TelemetryCommitTest`.
- **The heavy phase** (the workspace measurement; budget `2/5 interval`, so the light phase plus the heavy one always fit in an
  interval). The cluster section only DECIDES that a heavy collector is due (its marker is absent or at least
  `storageIntervalSeconds` old) and CLAIMS the run by writing its marker row `telemetry.storage_ms` with value 0, committed with the
  cheap collectors' readings; the run itself starts AFTER that transaction, so a scan never sits inside the lock transaction. It
  reads through a `HeavyReader`: every statement a SHORT transaction of its own (no lock held across scans, so a deploy's `ALTER`
  is never queued behind them), then one more transaction inserts the readings and sets the marker to the run's duration (at least
  1 ms). A run is attempted at most once per interval whatever its outcome: a failing collector is one WARN per episode, a run that
  outruns the budget is cancelled with one WARN ("heavy"), and a marker still at 0 is a claim that did not complete - not retried
  before the next interval. No statement carries a `queryTimeout` of its own (the global statement timeout and the budget bound
  it).
- **Metrics** (`TelemetryMetrics.kt`): `mem.*` (heap, committed, interval old-gen peak, non-heap, direct buffers; cgroup current,
  anon, working set, lifetime peak and `VmRSS` — a figure the host cannot tell is no row), `jvm.cpu_ratio`, `jvm.threads`,
  `jvm.gc_count`/`jvm.gc_time_ms` (per collector, deltas), `pool.*` (the R2DBC pool, read through `ConnectionPoolKey`), `http.*`
  (requests), `ledger.*`/`query.*`/`jq.*`/`fetch.*`/`integration.*` (pressure), `db.size`/`db.group.size`/`ws.rows` (workspace) and
  the `telemetry.cluster_ms`/`telemetry.storage_ms` markers. Process identity is the pod/container name (`HOSTNAME`), the JVM start and the optional
  `TOADIE_BUILD_VERSION`/`TOADIE_BUILD_COMMIT` image variables (Dockerfile build args, empty = null).
- **Requests** (`LatencyCollector.kt`): reads Ktor's `ktor.calls.<route>.timer` and `.<status>` series out of the registry
  (`MetricRegistryKey`; never a gauge) into `http.count`/`http.5xx`/`http.429` (DELTAS of the cumulative counts against the last COMMITTED
  read, kept in memory, the first tick counting since start) and `http.p50`/`http.p95` (the timers' windowed snapshots, merged per label so the total's
  percentile is a real one). Label `*` is every `/api/` and `/integration/` route except `/api/v1/telemetry*` (`/healthz`,
  `/readyz` and the static/SPA routes are not API traffic). A route additionally gets a label of its own when its NORMALISED path
  ends with `/graph`, `/errors`, `/export`, `/import`, `/import/check`, `/fetch` or `-documents`, or starts with `/integration/`
  (`isLabelled`: a shape rule, so a future heavy route is labelled with no code change; 19 today, pinned against the real routing
  tree by `LatencyCollectorTest`); every other route counts in `*` only. `normaliseRoute` strips every parenthesised selector from
  the name Ktor 3.6 writes (`/(authenticate "default")/api/v1/entities/graph/(method:GET)` becomes `/api/v1/entities/graph`) and
  keeps `{id}` literal. A label has rows only in an interval that saw something. **No 429 correction.** Flow subtracted the surplus
  of a route's status meters over its timer from its `429` count, because Ktor marks StatusPages' second answer to the RateLimit
  plugin's bodiless 429 under the routed name a second time. Here the second answer is named from the call's routing attributes,
  falls back to the URI name, and `TelemetryRegistry` refuses that name whenever the route has a selector, a path parameter or a
  query string; the double mark survives only on a selector-free route whose template equals its URI. Every `rateLimit {}` route of
  Toadie carries the `(RateLimit x)` selector (login, MFA, refresh, both password-reset routes, the three `/integration/` routes,
  and the two telemetry reads - which `isCounted` excludes, so they are never counted and a throttled telemetry read appears in
  no request row), and `LatencyCollectorTest` drives EACH of the counted ones past its bucket - login, refresh and password-reset with a limiter
  override, MFA and the reset confirmation at their fixed 10/min, `/integration/graphql`, `/schema` and `/mcp` through the shared
  integration bucket - and asserts the `429` count equals the rejections and `http.count` the requests sent; every route except
  the three integration ones is also hit with varying query strings (on the integration routes, only `/integration/graphql`
  is). So the correction was dropped. A hand-written bare `/api/` route answering a bodiless 429 itself would double-count - none exists;
  throw `TooManyRequestsException` instead, which is answered once.
- **Workspace** (`StorageCollectors.kt`, the heavy phase): `db.size` (`pg_database_size`), `db.group.size` per hand-listed table group
  (`catalog`, `ontology`, `registries`, `views`, `accounts`, `telemetry`: the sum of `pg_total_relation_size`, heap + TOAST +
  indexes, of its tables) and `ws.rows` per table (`catalog_files`, `blueprints`, `entities`, `conversion_mappings`, `lenses`,
  `entity_queries`: `NOT marked_as_deleted`; `catalog_file_events`: every row). The only identifiers interpolated into SQL are the fixed
  `COUNTED_TABLES` names (guarded by a plain-name check; the group names are only map lookups and labels, and `StorageCollectorsTest`
  asserts every counted table is a grouped one); `StorageCollectorsTest` fails when a `public` table (Flyway's history aside) is in no
  group or in two, so a migration that adds a table must assign it. No content column is ever selected.
- **Pressure** (`PressureCollector.kt`): the five bounded things that answer "is Toadie near a limit?", each read through a seam
  that only counts - `ReadLedger.snapshot()` (`ledger.in_flight`, `ledger.peak` since the previous committed interval, `ledger.refusals`;
  the ledger is published as `ReadLedgerKey` by `configureDatabase`), `EntityService.pressure()` (`query.in_use` permits taken
  of `MAX_CONCURRENT_ENTITY_QUERIES`, `query.refusals`, and `JqEvaluator.poolStats()`: `jq.quarantined`, `jq.active`,
  `jq.queued`), `UrlFetcher.poolStats()` (`fetch.active`, `fetch.queued` of the shared `url-fetch` pool), and
  `IntegrationPressureKey` (`integration.in_use`, `integration.retained`, `integration.refusals` - admission AND per-client
  rate refusals; the key is absent, and the rows with it, while the integration API is disabled). Refusal figures are DELTAS
  of cumulative counters. No limit and no behaviour changed: counters and snapshots only. Seam tests: `ReadLedgerTest`,
  `IntegrationLimitsTest`, `JqCalculationTest`, `UrlFetchTest`, `EntityQueryRouteTest`; `PressureCollectorTest`.
- **Measured cost.** Method: 20 warm-up ticks then 200 timed ticks on the shared Testcontainers PostgreSQL 18 (macOS/OrbStack, 18
  CPUs); allocation is summed over all threads. Before the multi-row insert (per-row `batchInsert`), p50 (p95): a tick holding the
  cluster section 27.4 ms (34.1), 4.79 MiB; a skipping tick (another process served the interval) 24.7 ms (30.4), 4.68 MiB; the
  rollup + prune tick 31.3 ms (36.5), 4.86 MiB; a tick after 15k requests in the interval 28.7 ms (33.6), 5.95 MiB; the heavy
  storage pass 37.2 ms (43.9), 8.08 MiB, with one 66 ms spike; the probes alone 0.1 ms; about 99 rows per bucket. The 98-row
  `insertSampleRows` alone was 15.4 ms and 2.67 MiB with `batchInsert` (60 % of the tick) - which is why the ported
  `MultiRowInsert` replaced it. Timed again in a temporary test on the final code (98 rows in one transaction, warm-up 20 + 200
  timed): 8.2 ms (p95 10.5-11.7) and 1.62 MiB against 17.4 ms (p95 22-26) and 2.68 MiB for the per-row insert on the same
  machine - about half. Steady state for one process: 250,560 rows = 49.2 MiB, 206 B/row (heap 90, indexes 116), SEEDED at 60
  rows per bucket (a real tick writes about 99) across both tiers: 7 days of raw buckets (2,016 x 60 = 120,960) plus 90 days of
  hourly buckets (2,160 x 60 = 129,600); a delete-churned table can sit far above that until autovacuum catches up (728 B/row seen
  before a TRUNCATE). **Read API** (PR 3 code on the disposable compose stack, OrbStack, 18 CPUs; curl `time_total` over localhost,
  JSON encoding included, paced under the 30/min bucket; about 415k rows = 90 MB: 7 days of raw rows at 300 s plus 90 days of hourly
  rows, 99 rows per bucket, plus a live process at 60 s): `current` p50 21 ms (max 23 ms, 4 KB); history 24h p50 30-37 ms (`requests`
  68 ms), 186-603 KB; history 7d p50 17 ms (`requests` 52 ms), 169 points; history 90d p50 34-48 ms (`requests` 100 ms), max 115 ms,
  753 KB for 33 series x 334 points. Flow's comparable figures were 31 ms for `current` and 81 ms for its heaviest read. Not
  measured: a cold disk, a CPU-limited pod, a live cgroup.
- **Never recorded** (the one home of this list): JVM arguments, environment, system properties, request paths with identifiers
  (route templates only, `{id}` literal), query strings, document contents or any content column, query text, a user, client or
  file identity, labels longer than 120 characters. The probes read counters and sizes only — never a
  thread dump.
- **The registry.** `plugins/Monitoring.kt`'s `TelemetryRegistry` (the Ktor `DropwizardMetrics` registry, published as
  `MetricRegistryKey`) gives every ROUTE timer (`ktor.calls.<route>.timer`) a true sliding window
  (`SlidingTimeWindowArrayReservoir`, the sample interval capped at 300 s); Ktor's global `ktor.calls.duration` keeps the default
  reservoir, as nothing reads it windowed. A sliding reservoir keeps ~16 bytes per update until it ages out, so a route timer
  holds about requests/s x window x 16 B: a sustained 2,000 requests/s for the full 300 s window is 600,000 updates, about 10 MB
  against the 256 MiB heap (a few KB at Toadie's usual few requests/s). It registers no Dropwizard JVM gauge sets, and bounds the
  names it will register, because a registered instrument is never evicted: Ktor 3.6 names an UNROUTED call's instruments after
  `request.uri + "/(method:X)"` (client-controlled, query string included), so a `ktor.calls.` name containing `?` is never
  registered; past the fixed global names, a name registers only when it is a route's `.meter` (created only by a ROUTED call) or
  its route's `.meter` is already registered, so unrouted URLs register nothing and cannot crowd out the routes hit later; and the
  family is capped at 2,000 names (one WARN). A refused name gets a detached instrument that counts nothing readable. The reporter
  also filters `?` names. `TelemetryRegistryTest` floods it. A ROUTED name is the route's own `toString()`, so a route containing `?` (an optional
  query field of a `@Resource` prints `[name?]`) would lose its metrics: `RoutingNamesTest` walks the real routing tree and fails on any such
  name, and is the enforcement of the `?` rule.
- **Layering.** `telemetry/` is the top layer: it reaches the registry, the database and the pool through attribute keys and nothing
  outside it imports it (`TelemetryLayeringTest`).
- **Read API.** `GET /api/v1/telemetry/current` and `GET /api/v1/telemetry/history?group=&range=`
  (`telemetry/TelemetryRoutes.kt`, `TelemetryReads.kt`, `TelemetryCurrent.kt`, `TelemetryHistory.kt`, `TelemetryWire.kt`; the
  `Telemetry*` schemas of `openapi/documentation.yaml`) are ADMIN only, read-only and emit NO audit event (a denial is the generic
  `authz.denied`); the guard order, the 400 rule and the per-user bucket are in `authorization.md` and `security.md` "Telemetry". They
  read whether or not this process samples (`telemetry.enabled`), and every statement runs under the global statement timeout (no
  per-transaction override). Every raw-tier predicate is `resolution IN (RAW_RESOLUTIONS)`. The clock is `TelemetryOverridesKey`'s
  when a test sets it.
  - `current` returns `{generatedAt, sampleIntervalSeconds, limits, processes[], workspace, requests}`. `limits` is NOT sampled: it
    is read per request from the live seams and the configuration - `ReadLedgerKey` (`snapshot().capacity`),
    `EntityService.pressure()` (the query permits and the jq pool's workers/queue), `UrlFetcher().poolStats()`,
    `IntegrationPressureKey` (absent = `integrationEnabled` false and the two integration bounds null) and `postgres.pool.maxSize`.
    A process carries `memory`, `jvm` and `pressure` blocks from ITS newest raw bucket (rows within two intervals of `last_seen`); a
    figure that bucket did not write is null, never carried over or zero. `percentOfLimit` = working set / container limit, one
    decimal. `workspace` is the newest `db.size`/`db.group.size`/`ws.rows` measurement (null before the first), `requests` the
    latest interval of the LIVE processes: `*` plus the 15 busiest labelled routes, `windowSeconds` the sample interval and
    `percentileWindowSeconds` the timers' reservoir window (the interval capped at 300 s, so the two can differ). With several
    live processes the counts and error counts add, the p50 is weighted by each writing process's request count and the p95 is the
    largest (a mean of quantiles understates a tail) - over the newest bucket every CONTRIBUTING live process has written (the oldest
    of their newest buckets, so a process lagging one tick is not undercounted; one more than an interval behind the newest does
    not contribute: it is wedged and the stale rule will flag it, and its bucket is one the healthy processes no longer hold). Trivial with the single replica, stated and tested so a
    second one never has to re-derive it.
  - **Stale rule.** With i the sample interval, a process is `stale` when |now - `last_seen`| > 3i, or its newest sample is more
    than 3i from now (the age is absolute, so a timestamp in the future beyond the limit is stale too), or it has no sample and
    started more than 3i ago. The newest sample is read only from the rows within 2i of `last_seen` (older ones are not read, so
    there is none): a process that ticks but stores nothing therefore becomes stale after about 2i through the no-sample branch,
    and the sample-age branch matters in the band where `last_seen` is 2i-3i old and the sample 3i-5i. Listed: every live process,
    every stale one seen within the last hour (a wedged process must stay visible, and so does the container a rolling update just
    replaced), and - only when none is live - the newest stale one seen within the last day; a process unseen for 24 hours is not
    listed, and at most 1000 are returned (the spec's `maxItems`, the most recently seen kept). The samples are read for the processes last seen within the greater of an hour and 3i together (so a restart's previous run, kept
    in the list for a day, never drags the read's `ts` floor back over the live process's history), each process's own 2i window is
    applied in SQL (about three buckets per process cross the wire), and only when nothing fresher exists is the single newest older
    process sampled by a query of its own.
  - `history` returns `{group, range, bucketSeconds, from, to, historyFrom, truncated, series[]}` for `group`
    `memory|workspace|pressure|requests|jvm` and `range` `24h|7d|90d` (both required). Buckets: 24h = the sample interval (widened
    to a whole multiple only if a day would exceed 400 points), 7d = 3600 s (the hourly rows plus the raw rows after the newest
    hourly row, nothing counted twice), 90d = 21,600 s. Every process merges into ONE series per `(metric, label)` (`key` =
    `<metric>[:<label>]`), so a trend survives a restart; a GAUGE or PERCENTILE point is the n-weighted mean and the largest max,
    a DELTA point the bucket's TOTAL (`avg`) and the largest single interval (`max`) of any one process. The p50 is
    REQUEST-weighted in `current` (each process's p50 by its request count) but SAMPLE-weighted in `history` (a PERCENTILE point is
    the n-weighted mean of the stored p50s, quiet and busy intervals alike); the p95 is the largest in both. At most 400 points per series and 40 series
    (`truncated`); `requests` keeps `*` (five metrics) and the ten busiest routes of the range (count, p50, p95: 35 series).
    `historyFrom` is the first retained process start clipped to the range.
  - Tests: `TelemetryRoutesTest` (fixtures `TelemetryRoutesFixtures.kt`).
- **Tests:** `.claude/docs/testing.md` "Telemetry".

### Integration audit

The machine API additionally audits reads as `integration.request` and MCP tool calls as `integration.mcp_call`, with bounded client and
operation/root metadata only. Client create/revoke, authentication refusal and throttling are
audited; keys, query text, variables and returned data are never logged. See
[integration-api.md](integration-api.md) for the exact scope and delivery limitations.
