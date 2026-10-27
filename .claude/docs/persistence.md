### User mutation snapshots and administrator locks

- **Applies when:** changing user identity/role, language, disabled features, or deleting a user.
- **Requirement:** read the active target under `FOR UPDATE` and return the mutation's actual
  predecessor from the same transaction. Build audit deltas from that result after success.
  A demotion or deletion first locks active administrators in ID order, then the target;
  promotion and other writes lock only their target. Decide last-admin protection by whether
  a locked **other** administrator survives, not a count that assumes the target was already
  an administrator in the earlier snapshot. Feature writers share the user-row lock.
- **Reference:** `users/UserService.kt`, `users/UserRoutes.kt`.
- **Enforcement:** `UserAuditConcurrencyTest`, `UserRoutesTest`, `FeatureFlagsTest`, `AuditTest`.
- **Exception:** test-only fixture writes may bypass these paths; production callers may not.

### Persistence

PostgreSQL is the only database. Connection settings come from the `postgres:` block in `application.yaml` (env-overridable via `POSTGRES_JDBC_URL`, `POSTGRES_R2DBC_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`); defaults match the `docker compose up postgres` service (host port **5433** — Lettuce may occupy 5432 on the same machine; in-network consumers use `postgres:5432`). There is one persistence stack:

- **Flyway** (`infra/db/Flyway.kt`) — runs schema migrations from `server/src/main/resources/db/migration/` at startup via the Java API, opening a short-lived JDBC connection that retries a refused connect while PostgreSQL starts ("Cold start: Flyway waits for PostgreSQL" below). Migrations are the single source of truth for schema; do not call `SchemaUtils.create` anywhere. **An applied migration's bytes are immutable — comments included**: Flyway validates stored checksums at startup, so any edit to an existing `V*.sql` makes every long-lived database refuse to boot (a failure no fresh-container CI run can see). `MigrationChecksumTest` pins every file's checksum; a new migration adds one manifest line, and a red pin means REVERT the edit (clarifications go into this doc), never update the pinned value.
- **Exposed + R2DBC** (`infra/db/Database.kt` + the feature services) — runtime DB access. `Database.kt` connects the `R2dbcDatabase` and is the composition root: it constructs the services and publishes them into `Application.attributes` (`UserServiceKey`, `GraphLayoutServiceKey`, `EntityGraphLayoutServiceKey`, `TokenBlocklistServiceKey`, `CatalogFileServiceKey`, `CatalogFileEventServiceKey`, `DictionaryServiceKey`, `LabelServiceKey`, `AnnotationKeyServiceKey`, `TagCategoryServiceKey`, `EntityTypesServiceKey`, `LensServiceKey`, `BlueprintServiceKey`, `ConversionMappingServiceKey`, `ConversionExportServiceKey`, `EntityServiceKey`, `SavedEntityQueryServiceKey`, `IntegrationClientServiceKey`, `AuthSessionServiceKey`, `PasswordResetServiceKey`) plus `DatabaseKey` (the `R2dbcDatabase` itself); each service itself lives next to the feature it serves (`users/UserService.kt`, `users/GraphLayoutService.kt` — the second `EntityGraphLayoutServiceKey` instance is the SAME class over a second table, see V30 below —, `auth/TokenBlocklistService.kt`, `catalog/CatalogFileService.kt`, `catalog/CatalogFileEventService.kt`, `dictionaries/DictionaryService.kt`, `labels/LabelService.kt`, `annotations/AnnotationKeyService.kt`, `tags/TagCategoryService.kt`, `types/EntityTypesService.kt`, `lenses/LensService.kt`, `blueprints/BlueprintService.kt`, `conversion/ConversionMappingService.kt`, `conversion/ConversionExportService.kt`, `entities/EntityService.kt`, `entityquery/SavedEntityQueryService.kt`, `integration/IntegrationClientService.kt`, `auth/AuthSessionService.kt`, `auth/PasswordResetService.kt`). The Exposed table `object`s (e.g. `UserService.Users`, nested inside their service) are used for queries only, not DDL. Ids are `UIntIdTable` — unsigned end-to-end (the spec declares `minimum: 0`, and `ErrorHandling.kt` 400s negative path segments before kotlinx's `UInt` decoding can silently wrap them); one wrinkle: V1 created `users.id` as `BIGSERIAL` (64-bit in SQL, 32-bit everywhere above it) while V5 uses `SERIAL` — harmless at this scale, documented so nobody "fixes" one to match the other without a migration.

The `org.postgresql:postgresql` JDBC driver is on the classpath solely for Flyway; runtime queries go through R2DBC.

### Connection pool

- **Applies when:** touching `infra/db/Database.kt`'s connect call, the `postgres.pool.*`
  configuration, or reasoning about how many PostgreSQL backends one Toadie instance can hold.
- **Requirement:** Exposed connects through ONE bounded `io.r2dbc:r2dbc-pool` `ConnectionPool`
  (2.11.1) wrapping the plain PostgreSQL R2DBC factory — never a raw `r2dbc:postgresql://`
  connect, which opens one backend per `suspendTransaction` with nothing capping concurrency
  (measured 2026-09-20 on the compose stack: 120 parallel graph reads → 81 concurrent backends
  against PostgreSQL's default `max_connections = 100`). Bounds come from `application.yaml`'s
  `postgres.pool` block, each boot-validated (startup fails outside the range, the
  `security.passwordReset.tokenTtlSeconds` idiom): `maxSize` (`POSTGRES_POOL_MAX_SIZE`, default
  20, 1..1000), `initialSize` (`POSTGRES_POOL_INITIAL_SIZE`, default 2, 0..maxSize),
  `maxAcquireTimeSeconds` (`POSTGRES_POOL_MAX_ACQUIRE_SECONDS`, default 10, 1..600) and
  `maxIdleTimeSeconds` (`POSTGRES_POOL_MAX_IDLE_SECONDS`, default 600, 1..86400) and
  `maxLifeTimeSeconds` (`POSTGRES_POOL_MAX_LIFE_SECONDS`, default 1800, 1..86400 — a connection is not
  reused past it). Every pooled connection carries `application_name = toadie` (`postgres.pool.applicationName`), so operators
  count this instance's backends with `SELECT count(*) FROM pg_stat_activity WHERE
  application_name = 'toadie'`. Size it as `maxSize × replicas + 1` (Flyway's short-lived JDBC
  connection) well under the server's `max_connections`. A caller that waits past the acquire
  deadline fails with the pool's timeout exception, which `plugins/ErrorHandling.kt`'s catch-all
  renders as a logged `500` — deliberately NOT a new declared status, since the OpenAPI
  conformance gate would need it on every operation. The pool is disposed on
  `ApplicationStopped`, so every `testApplication` the suite boots releases its connections.
  Exposed's `R2dbcDatabase.connect(connectionFactory, databaseConfig)` derives the dialect from
  `databaseConfig.connectionFactoryOptions` alone, so the parsed options (still `driver=postgresql`)
  are threaded into the config unchanged while traffic goes through the pool. The same config pins
  `defaultMaxAttempts = 1`: Exposed would otherwise retry ANY `R2dbcException` three times, and the
  pool's acquire timeout is one — a saturated pool would cost 3 × the acquire budget per request
  and re-enter the acquire queue each time; Toadie's writes serialize on table locks, never on
  serialization failures, so no code path relied on the retry.
- **Reference:** `infra/db/Database.kt` (`connectPooled`, `readPoolBounds`), `application.yaml`
  `postgres.pool`.
- **Enforcement:** `ConnectionPoolTest` — concurrent transactions never exceed `maxSize` in
  `pg_stat_activity`, a saturated pool times out an acquire instead of hanging, the pool releases
  every connection when the application stops, out-of-range bounds fail startup, and a connection
  older than `maxLifeTimeSeconds` is replaced (a different backend pid), and an idle one past it is closed by the background
  sweep alone, with no further acquire. The hung-database bounds below add their own cases to the
  same class.
- **Exception:** none beyond the one in "A hung database is bounded" (Exception (ii)): every acquire
  validates with a bounded round trip (`ValidationDepth.REMOTE`), so a single connection killed
  server-side between uses (restart, failover, an idle killer or proxy) is replaced at acquire instead
  of being handed to a request; after a PostgreSQL restart a request whose two picks are both dead still
  fails fast until the pool has refreshed. No connection is reused past `maxLifeTimeSeconds` either; an expired idle one
  is closed by the background sweep within a minute. r2dbc-pool 1.0.2 (verified from its bytecode)
  evaluates the idle/lifetime eviction predicate on acquire and release, and also in a background
  sweep whose `backgroundEvictionInterval` defaults to `maxIdleTime`; `Database.kt` pins that sweep
  to `min(60 s, maxIdle, maxLife)` so an expired idle connection is released promptly. Lettuce
  ported the pool in v3.16.1 and added the hung-database bounds in v4.7.1; 2.20.2 ports them
  back.

### Cold start: Flyway waits for PostgreSQL

- **Applies when:** touching `infra/db/Flyway.kt`, `postgres.connectRetries`, the boot order of
  `application.yaml`'s modules, `ktor.application.startupTimeoutMillis`, or the app's
  `startupProbe` in `k8s/app-deployment.yaml`. This section is the one home of the numbers below;
  every other file points here.
- **Requirement:** Flyway is the first database contact at boot, so it retries its JDBC connect
  (`postgres.connectRetries` / `POSTGRES_CONNECT_RETRIES`, default 10, boot-validated 0..15; waits
  double 1, 2, 4, 8 s and stay capped at 8 s, `CONNECT_RETRY_MAX_INTERVAL_SECONDS`) instead of
  failing on the first refused connection and crash-looping the pod until PostgreSQL has
  initialised its volume (the 2026-10-25 OrbStack smoke on an empty namespace restarted the app
  twice). The sleeps total 63 s at 10 retries and 103 s at the maximum 15; a REFUSED connect (a
  Kubernetes Service with no ready endpoints, a port nobody listens on) costs nothing on top. After
  the retries are spent the ORIGINAL connection error is raised and the process exits as before; `0`
  restores fail-fast.
  **Ktor's startup timeout is why `ktor.application.startupTimeoutMillis` is 140 000.** Ktor wraps
  module loading in `withTimeout(startupTimeoutMillis)` (default 10 s). `migrate()` is a blocking call
  (`Thread.sleep` back-off), so the timeout cannot interrupt the wait; it fires at the next suspension
  point AFTER Flyway has returned and fails a boot whose database had in fact come up. Measured
  through the real module chain (`FlywayConnectRetriesTest`, database up after 8 s, so Flyway
  returns at about 15 s): with the 10 s default the boot dies with `TimeoutCancellationException`
  ("Timed out waiting for 10000 ms after 16824 ms"); with 140 000 it boots. 140 s outlasts the 103 s
  maximum wait plus boot. It is NOT a hard bound on the wait (see the Exception) and it must stay
  below the `startupProbe` (30 x 5 s = 150 s), pinned by `web/src/test/infrastructure.test.js`.
  **Flyway 13.11.1 retries EVERY `SQLException` from `getConnection`** (read from `JdbcUtils.openConnection`;
  its specific-exception short-circuit covers SQL Server / untrusted-certificate cases only), so a
  wrong password or a missing database also fails, but only after the retries are spent (63 s at the
  default) — a misconfigured Secret takes about a minute to surface instead of seconds.
- **Reference:** `infra/db/Flyway.kt` (`readFlywayConnectRetries`, `buildFlyway`), ported from Flow's
  `readFlywayConnectRetries`; `application.yaml` (`postgres.connectRetries`,
  `startupTimeoutMillis`); `k8s/app-deployment.yaml` (the `startupProbe`).
- **Enforcement:** `FlywayConnectRetriesTest` — range validation (startup refuses 16), the configured
  value reaches the Flyway `configureFlyway` builds (the `(connect retries: N)` log line), a wrong
  password is retried then fails, a REAL boot under the shipped timeout waits for a database that
  comes up late (its Flyway returns after 15 s, so it fails if the timeout falls back to Ktor's 10 s
  default), and the timeout covers the 103 s maximum wait + 20 s;
  `web/src/test/infrastructure.test.js` — the `startupProbe` budget exceeds `startupTimeoutMillis`.
- **Exception:** only Flyway's connect FAILURES are retried (a frozen database at boot still hangs, see
  "A hung database is bounded"). The wait is not bounded by Ktor's timeout, and an address that
  silently drops packets costs pgjdbc's default `connectTimeout` (10 s, verified in `PGProperty` of the
  locked 42.7.14) per attempt on top of the sleeps: 11 x 10 s + 63 s = 173 s at the default retries.
  The startupProbe (about 145-150 s from container start, JVM start included) then kills and restarts
  the pod mid-Flyway, exactly the pre-change behaviour for that network shape. The R2DBC pool opens
  lazily after Flyway succeeded; a database that goes away mid-run is the pool's concern.

### A hung database is bounded

- **Applies when:** touching the pool's validation/creation bounds, `defaultQueryTimeout`, the
  `postgres.connectTimeoutSeconds` / `postgres.statementTimeoutSeconds` /
  `postgres.responseTimeoutSeconds` keys, `infra/db/ResponseDeadline.kt`, any
  `queryTimeout` on a transaction, or anything that could `SET statement_timeout`.
- **Requirement:** the R2DBC driver has no socket read timeout, so a database that accepts or
  holds connections but never answers (a paused container, a network black hole, a frozen VM)
  must be bounded by the client. Against it only a CLIENT-side deadline on an exchange can fire:
  the kernel still ACKs, so TCP keepalive and the server-side timeouts cannot. `r2dbc-pool` offers
  exactly three such deadlines, and all are set, plus a fourth of Toadie's own below the pool for an
  exchange already on the wire (`postgres.responseTimeoutSeconds`, `POSTGRES_RESPONSE_TIMEOUT_SECONDS`, default 45,
  0..3600, 0 = off; "Response deadline: every exchange below the pool" below). All are boot-validated, the same
  `.also { require(..) }` idiom as the other bounds: `postgres.pool.maxValidationTimeSeconds`
  (`POSTGRES_POOL_MAX_VALIDATION_SECONDS`, default 5, 1..60) together with
  `ValidationDepth.REMOTE` (every acquire runs a `SELECT 1`; a validation timeout invalidates the
  connection; the pool returns its slot before the driver's `close()`, which is local, so a frozen
  connection costs no slot (its socket lingers until the thaw)),
  `postgres.pool.maxCreateConnectionTimeSeconds` (`POSTGRES_POOL_MAX_CREATE_SECONDS`, default 10,
  1..600; TCP connect plus startup/auth) and the existing `maxAcquireTimeSeconds`. The TCP connect
  alone is `postgres.connectTimeoutSeconds` (`POSTGRES_CONNECT_TIMEOUT_SECONDS`, default 10,
  1..600), with TCP keepalive on. PostgreSQL's `statement_timeout` is
  `postgres.statementTimeoutSeconds` (`POSTGRES_STATEMENT_TIMEOUT_SECONDS`, default 30, 0..3600,
  0 = off; boot then requires the response deadline off too), set ONLY through Exposed's `defaultQueryTimeout` (beside `defaultMaxAttempts = 1`):
  Exposed asks the driver to apply the transaction's `queryTimeout` before every statement, so a
  driver startup option or a post-allocate `SET` would be overwritten with 0 and nothing else may
  `SET statement_timeout` (the driver turns each ask into a `SET`; "Statement timeouts: the
  per-connection cache" below sends it only when the connection's value differs). It applies to
  every pooled statement, lock waits included (`LOCK TABLE`, `FOR UPDATE`); Toadie's per-document
  transaction holds are ~100 ms. A live
  server cancels a long statement itself (SQLSTATE 57014, Exposed rolls back, the connection stays
  valid). No coroutine `withTimeout` around the pool: it cannot cancel the driver handshake and
  leaves rollback/release in limbo (Lettuce dropped it in v4.5.2). The promise: a request against a
  hung database fails within about 2 x `maxAcquireTimeSeconds` (r2dbc-pool retries a timed-out
  acquire once) with the catch-all 500, and `/readyz` answers 503 within its own deadline; an exchange
  already on the wire fails within `postgres.responseTimeoutSeconds` and its slot comes back while the
  server is still frozen. Cost:
  one `SELECT 1` round trip per acquire (Lettuce measured +0.3 ms mean / +0.7 ms p95 per request).
  Flyway (its own short-lived JDBC connection: a frozen database at boot hangs until the
  startupProbe restarts the pod; only its connect FAILURES are retried, "Cold start" above) and `pg_dump` are out of scope.
  **Releasing the slot survives a cancelled coroutine, by construction.** r2dbc-pool 1.0.2's
  `PooledConnection.close()` is `validate(LOCAL)`, then, only while the connection is flagged in a
  transaction, `rollbackTransaction()`, then `ref.release()`. A subscription cancel that arrives while
  that rollback waits on a frozen server stops the chain before the release and strands the slot for
  good (reproduced against a bare pool on a frozen relay, 2026-10-25: `acquiredSize` stays 1 and the
  next acquire times out). Through Exposed 1.6.0 R2DBC that cannot happen, for two reasons read from
  the bytecode of the locked jars:
  (1) `PooledConnection.commitTransaction()` and `rollbackTransaction()` clear the `inTransaction` flag
  when they are SUBSCRIBED (`doOnSubscribe`), and Exposed always subscribes one of them before it
  closes, so `close()` skips its own rollback; what is left is `validate(LOCAL)` (a synchronous
  `Mono.fromSupplier(client::isConnected)`) and `ref.release()`, which finish during the subscription.
  (2) The close itself is not cancellable: `NonCancellable` sits on `rollbackLoggingException`, on the
  three `withContext(NonCancellable) { closeStatementsAndConnection }` calls in
  `inTopLevelSuspendTransaction` and on `R2dbcConnectionImpl.releaseConnection`. It is NOT on the
  statements or the commit (`withConnection` makes only the pool ACQUIRE non-cancellable), so cancelling
  a coroutine whose commit is on the wire to a frozen server ends the commit await at once; the rollback
  and close that follow run to the end, but Exposed 1.6.0's rollback first asks `isClosed()`, which runs
  `validate(LOCAL)` and then `validate(REMOTE)` (read from the bytecode). On a frozen server that pre-rollback
  `validate(REMOTE)` is the exchange bounded by the response deadline: it times out after `responseTimeoutSeconds`
  (the WARN says "validation") and poisons the connection, `isClosed()` then answers true and the rollback is
  skipped, and the close invalidates the connection and returns the slot, so the coroutine ends within the
  deadline even while the server is still frozen.
  Re-verify both on every Exposed or r2dbc-pool upgrade. If either stops holding, the fix is a
  `ConnectionFactory` decorator above the pool whose `Connection.close()` subscribes the pool's release
  eagerly and independently of the downstream cancel.
  As a server-side backstop for a session whose client stopped talking to it with a transaction
  open, every pooled connection also carries PostgreSQL's `idle_in_transaction_session_timeout`
  (`postgres.pool.idleInTransactionTimeoutSeconds`, `POSTGRES_POOL_IDLE_IN_TRANSACTION_TIMEOUT_SECONDS`,
  default 300, 0..86400, 0 = off), sent as a startup parameter through the driver's `options`
  (`withIdleInTransactionTimeout`; an `options=` already in the r2dbc URL wins) and never `SET`.
  Toadie's longest transaction is ~0.5 s, so 300 s is a wide margin. It ends only an IDLE session; a
  statement stuck `active` is `statement_timeout`'s job. Ported from Flow (without its
  `LeakedSocketGuard`; its `StatementTimeoutCache` is ported too, see below).
- **Reference:** `infra/db/Database.kt` (`readPoolBounds`, `connectPooled`), `application.yaml`
  `postgres`; Lettuce `infra/db/Database.kt` and its `persistence-details.md` "A hung database is
  bounded (v4.7.1)".
- **Enforcement:** `ConnectionPoolTest` — a hung database (`FreezableRelay`, a TCP relay that goes
  silent) fails an authenticated request with the 500 problem body and `/readyz` with 503 within
  the bounds and heals when it answers again, a server-killed connection is replaced at acquire,
  `statementTimeoutSeconds` cancels a long statement and the server reports the configured value,
  and every bound (the idle-in-transaction one included) is range-checked at boot, the timeout
  reaches the server (`SHOW idle_in_transaction_session_timeout`: `5min` by default, `0` when off) and
  the startup-option merge keeps an operator's own value; `DatabaseHangTest` replays the incident against a
  really paused dedicated container (`.claude/docs/testing.md` "Hung-database regressions"):
  bounded 500 and `/readyz` 503, a statement already in flight when the pause lands failing with the response
  deadline, then recovery with a replaced backend, the invalidated backend
  closed and a table-locking transaction completing. The released slot is pinned by `ConnectionPoolTest`
  as an OUTCOME (a transaction cancelled while its commit is held by the frozen relay, joined WHILE FROZEN
  within the response deadline, and one cancelled inside its body, both still give a `maxSize = 1` pool back);
  the tests do not assert the mechanism, so a change in Exposed's or r2dbc-pool's close path is caught only if it
  actually strands the slot. The in-flight cases are in the same class: a statement already on the wire, and a
  commit held with no cancel at all, each fail with the response deadline in under 6 s and the pool's
  `acquiredSize()` and `allocatedSize()` reach 0 (invalidated, not just released) WHILE THE RELAY IS STILL FROZEN
  (`ConnectionPoolKey`; the test's thaw watchdog is cancelled before it can fake that), the old backend ends after
  the thaw; on a live server a `statement_timeout` cancel (57014) still wins and the same backend answers
  afterwards; the boot invariant (`responseTimeoutSeconds` is `0` or greater than a NON-ZERO
  `statementTimeoutSeconds`, naming both keys), the range and `0` = off are boot-checked. `ResponseDeadlineConnectionTest` pins the decorator against a fake connection
  (no database).
- **Exception:** (i) a discarded connection is only discarded locally: its socket lingers until the thaw,
  and its backend, with its locks, until the server reads the `Terminate` the destroy queued (which also
  aborts the transaction the skipped rollback would have ended). A COMMIT that times out is IN DOUBT: the
  thawed server executes it before it reads that `Terminate`, so the transaction may have committed although
  the caller saw `DatabaseResponseTimeoutException`. Routes emit `audit(...)` only after success, so such a
  commit that applies at the thaw leaves a committed security-relevant mutation (an admin role change, say, that
  answered 500) with NO audit line; catalog and entity history is unaffected because it commits in the same
  transaction. And a true black hole with a full kernel send buffer could
  stall the `Terminate` write itself: that holds the requesting coroutine, never the pool slot. (ii) After a
  PostgreSQL restart every idle connection dies at once; each acquire
  gets two validation attempts (`acquireRetry = 1`), so a request whose two picks are both dead still
  fails fast until the pool has refreshed.

### Response deadline: every exchange below the pool

- **Applies when:** touching `infra/db/ResponseDeadline.kt`, `postgres.responseTimeoutSeconds`, the factory
  stack in `Database.kt` (`ResponseDeadlineConnectionFactory` over `StatementTimeoutCachingConnectionFactory`
  over the driver), or upgrading r2dbc-pool, r2dbc-postgresql, r2dbc-spi or Exposed (a new SPI method on
  `Connection`, `Statement`, `Batch` or `Result`, or a new pool release path).
- **Requirement:** every exchange with the database has a CLIENT-side deadline, because the driver has no read
  timeout and a frozen server's kernel keeps ACKing. `ResponseDeadlineConnection` (sits below the pool) bounds
  with reactor `timeout`: each Mono exchange (`beginTransaction`, `commitTransaction`, `rollbackTransaction`,
  `rollbackTransactionToSavepoint`, `createSavepoint`, `releaseSavepoint`, `setAutoCommit`, `setLockWaitTimeout`,
  `setStatementTimeout`, `setTransactionIsolationLevel`, `validate(REMOTE)`) as a whole, and each stream (`Statement.execute()`,
  `Batch.execute()`, `Result.getRowsUpdated`/`map`/`flatMap`, and a `filter`ed `Result`) per GAP, i.e. from the
  subscription to the first signal and between consecutive signals. NOT bounded, on purpose: `close()` (local; the
  destroy path must run after a poison) and `validate(LOCAL)` (a synchronous flag read). Statements, batches and
  results are wrapped explicitly and every fluent method returns the WRAPPER (Kotlin delegation would return the
  inner object and let `execute()` escape). Boot invariant, checked in `readPoolBounds` (startup fails otherwise,
  naming both keys): `responseTimeoutSeconds` is `0` (off: the factory returns the inner connection unwrapped), or
  `statementTimeoutSeconds` is non-zero AND the response deadline is strictly greater than it, so on a live server
  the server's own 57014 always arrives first. A non-zero deadline with `statement_timeout` off is refused: a LIVE
  lock wait longer than the deadline would be poisoned and discarded while its backend keeps waiting (PostgreSQL does
  not notice the client is gone without `client_connection_check_interval`), the freed slot would open another
  backend, and a lock convoy would pile backends past `maxSize` toward `max_connections`. A timeout POISONS the connection once and for good:
  `validate(any depth)` answers `false` without asking the driver (r2dbc-pool's release then invalidates it, and the
  driver's `close()` is local, so the slot is free at once), every other exchange fails with
  `DatabaseResponseTimeoutException` (`R2dbcTimeoutException`, SQLSTATE `08006`, never `57014`) without touching the
  driver, a rollback completes EMPTY (defensive: through Exposed 1.6.0 the rollback is already skipped by its
  `isClosed()` pre-check, so this covers savepoint rollbacks and the pool's release rollback; nothing is sent to a
  server that is not reading, and the destroy's `Terminate` aborts the transaction at the thaw), and `close()`
  delegates unchanged. A poisoned connection is never reused: a
  cancelled conversation is drained silently by the driver, so its protocol state is unknown. A CANCEL is not a
  timeout and never poisons. The first poisoner logs ONE WARN on `ch.nokillswit.infra.db` (kind of exchange and
  bound only, never SQL or parameters). COMMIT is bounded too and a timed-out COMMIT is in doubt (Exception of
  "A hung database is bounded"). Cost: one parallel-scheduler timer task per exchange (and per streamed signal),
  disposed on completion or cancel.
- **Reference:** `infra/db/ResponseDeadline.kt`, `infra/db/Database.kt` (`readPoolBounds`, `connectPooled`,
  `ConnectionPoolKey`), `application.yaml` `postgres.responseTimeoutSeconds`.
- **Enforcement:** `ResponseDeadlineConnectionTest` (fake connection: every SPI method delegates while healthy; each
  Mono exchange and each stream read times out and poisons; a stall mid-stream; refusal after the poison; no escape
  through a fluent `Statement`/`Batch` method or `Result.filter`; cancel never poisons and no timer is left behind;
  one WARN without SQL; `0` = unwrapped). Two set-equality sweeps catch SPI growth: the Publisher-returning
  `Connection` methods (but `close`) must equal the set of bounded exchanges, and the synchronous ones must equal the
  classified set, so a new `Connection` method fails the test (`Connection by delegate` would otherwise pass it through
  unbounded); every method of `Statement`, `Batch` and `Result`, default methods included, must be overridden by its
  wrapper, so a new default method fails the test too (a new abstract one also fails the compile, the wrappers do not
  use `by`). The frozen-relay and paused-container cases are listed in the Enforcement of "A hung database is bounded".
- **Exception:** the streaming gap is measured between signals, not against downstream demand, and the outer
  `execute()` stream's second gap spans the WHOLE row consumption (driver 1.1.3 builds it with `windowUntil`). CPU
  work in a consumer counts, not just suspension, and PostgreSQL does not act on a query cancel (`statement_timeout`)
  while it is blocked writing to a client that is not reading. So the rule: keep per-statement row consumption well
  under `responseTimeoutSeconds - statementTimeoutSeconds`. Today rows are `.toList()`ed inside the transaction, and
  decoding, jq and query evaluation run outside it, so there is no realistic live false positive; re-check that before
  adding a suspending or heavy row consumer inside a transaction. The `Lifecycle` interface stays hidden from the
  pool, as for the statement-timeout cache.

### Statement timeouts: the per-connection cache

- **Applies when:** touching `infra/db/StatementTimeoutCache.kt`, the factory wrapping in
  `Database.kt`, `defaultQueryTimeout`, any transaction's `queryTimeout`, upgrading r2dbc-pool,
  r2dbc-postgresql or Exposed (a driver that implements `io.r2dbc.spi.Lifecycle` would have its
  `postAllocate`/`preRelease` hidden from `PooledConnection` by the decorator), or adding anything
  that could change `statement_timeout` (`SET`, `SET LOCAL`, `set_config`, `RESET`, `DISCARD`) on a
  pooled connection.
- **Requirement:** Exposed's R2DBC executor asks the driver for the transaction's `queryTimeout`
  before EVERY statement and r2dbc-postgresql answers each ask with its own
  `SET STATEMENT_TIMEOUT = <ms>` simple query that Exposed awaits.
  `StatementTimeoutCachingConnectionFactory` (ported from Flow) wraps the unpooled factory, BELOW
  the pool, so each physical connection remembers the value the server last confirmed and the
  driver call is sent only when the requested value differs. Measured 2026-10-25 against a
  dedicated PostgreSQL 18 container (`log_statement=all`, loopback, warm pool, 300 requests per
  path): statements per authenticated request `GET /api/v1/files` 15 to 12, `/api/v1/entities`
  30 to 22, `/entities/graph` 21 to 16, `/blueprints` 12 to 10, i.e. every `SET` gone (3, 8, 5, 2
  per request); mean latency 3.85 to 3.50 ms, 4.84 to 3.99 ms, 3.22 to 2.74 ms and 2.57 to
  2.15 ms (a single run each; the gain grows with the network round-trip time, ~0 on loopback).
  No reset on release is needed: Exposed states its value before every statement, so a borrower
  with another value simply differs from the remembered one and is sent. The one trap is that
  PostgreSQL reverts a session-level `SET` issued INSIDE a transaction when that transaction rolls
  back (also to a savepoint), and a statement cancelled by the timeout (SQLSTATE 57014) aborts its
  transaction. The remembered value is therefore forgotten on every rollback, on a failed or
  cancelled commit and on `setAutoCommit(true)`; forgotten BEFORE a `SET` goes out; and remembered
  only once the `SET` completed under an unchanged generation (a `SET` whose reply was cancelled or
  failed, or that outlived a rollback, is never remembered). It is kept across a successful commit,
  which is where the gain is. The skip is decided at subscription, not when the publisher is
  assembled. A fresh or replaced physical connection (pool eviction, `maxLifeTime`, a failed
  validation, a killed backend) is a new decorator with nothing remembered, so its first statement
  always sends the value; `defaultQueryTimeout = 0` is an ordinary value (sent once per connection,
  then skipped).
  **ONLY the transaction's `queryTimeout` may set the statement timeout.** A hand-rolled `SET`,
  `SET LOCAL`, `set_config(.., false)`, `RESET` or `DISCARD` on a pooled connection desyncs the
  cache from the backend (the next statement sees the requested value as unchanged and skips it).
  Nothing in `server/src/main` does any of these (grep, 2026-10-25), and neither r2dbc-pool 1.0.2
  nor r2dbc-postgresql 1.1.3 issues a `RESET`/`DISCARD` on release or checkout (checked in the
  bytecode). Deployment assumption: one client connection is one server session; a
  transaction-mode PgBouncer in front of PostgreSQL would break the cache (none today).
- **Reference:** `infra/db/StatementTimeoutCache.kt`, `infra/db/Database.kt` (`connectPooled`),
  Flow `infra/db/StatementTimeoutCache.kt` and its `persistence.md`.
- **Enforcement:** `StatementTimeoutCachingConnectionTest` (a recording fake `Connection`, no
  database) pins the skip, the generation guard, forgetting on rollback, savepoint rollback,
  `setAutoCommit(true)` and a failed or cancelled commit, and that every other SPI method
  delegates; `StatementTimeoutCacheTest` (each case on its own one-connection pool built like
  `Database.kt`'s) pins convergence of every borrower to its own value including 0, the rolled-back
  `SET`, the aborted-transaction canary (the driver's ROLLBACK-tag exception plus Exposed's
  rollback after a failed commit), a budget enforced again after a cancelled statement (57014), a
  killed backend replaced and given the value again, and the skip with its limit; and
  `ConnectionPoolTest`'s statement-timeout cases pin the same through the application wiring. The
  rule that nothing else may set the timeout is enforced by manual review (a grep gate is not
  worth its false positives: `SET` and `RESET` are ordinary words in this codebase).
- **Exception:** none.

**Cross-feature table reads (the service-layer rule, inherited from Lettuce).** A feature service MAY query another feature's Exposed table objects directly when the read must run **inside its own transaction** (SQL joins, atomic snapshots) — calling the other feature's *service* would open a second transaction and break atomicity. Route handlers never touch tables (services only). The reads in place: `BlueprintService`'s read of the active `HIERARCHY` rows in `DictionaryService.Entries` (every `hierarchyRelations` key must be one, inside the V27 locked write) and `DictionaryService.replace`'s read of `BlueprintService.Blueprints` (the hierarchy referrer check, under the same lock — V34), `CatalogFileService.joined()`, `LensService.joined()`, and `EntityService.joined()` (catalog list/read, the lens list, and the entity list/read join `UserService.Users` for the creator's display fields), `EntityService.errors`'s read of `SavedEntityQueryService.EntityQueries` via the extracted `savedQueriesVisibleTo(callerId)` predicate (2.5.0 — inside the errors report's own read transaction, the `joined()` idiom above), `EntityService`'s `narrowTargets`/`loadReadSet` (`entities/EntityWorkspaceRead.kt`, 2.4.0) — the per-call read of active rows from `BlueprintService.Blueprints`, the `_team`/`_user` system blueprints (if they exist), and the set of blueprints named by any Inherited `ownership.path` (rows raw inside the transaction, decoded outside it under the read ledger), used to validate relation targets and compute inherited teams (since 2.10.0, `EntityService.loadActiveBlueprints` — the shared `ActiveBlueprint` read behind `list`/`graph`/`errors` alike — also carries each blueprint's `source_url`, so the errors report can decide the blueprint-row `SOURCE_MISSING` finding without a second query). The definition each entity's `entityFindings` is checked against, and every map (`(blueprintId, identifier)` pairs for relations, `(blueprintId, teamId)` for ownership, and the blueprint-graph for path-following) are read inside the calling write/list/read transaction so an entity is never validated against definitions mid-change — see the V28 lock protocol below, `CatalogRegistryReader.resolveNamespace()` (every catalog write resolves its namespace against the active `NAMESPACE` dictionary entries inside the write's own transaction: blank → the ADMIN-flagged default entry, none flagged → 400; a concrete value must be an active entry — STRICT, no grandfathering: a stored file whose namespace was since removed cannot be saved until it is re-added or changed. The stored row AND content JSON always carry the resolved concrete value), and `CatalogRegistryReader.loadSnapshot()` (one snapshot of the five soft-check registries — active labels with kinds+closed value lists, annotation keys with kinds, tag categories, per-kind `entity_types` dictionaries, and the GLOBAL `LIFECYCLE` dictionary entries — plus the active `NAMESPACE` dictionary values (feeding ONLY the Errors report's report-only namespace check, never the soft checks) — read inside the calling write/report transaction; the soft rules themselves are the PURE `registryFindings` in `catalog/Errors.kt`: label key registered + kind allowed + value in the closed list, annotation KEY registered + kind allowed with values staying free, tag registered in a category whose kinds allow the file's kind, non-blank `spec.type` in the kind's active dictionary — no dictionary allows no types — and non-blank `spec.lifecycle` an active entry, byte-exact against the lowercase-folded stored values; empty registries allow nothing). These are the write path's SOFT checks (`CatalogFileService.softFindings` adds reference resolution): strict by default with the same no-grandfathering rule — a stored file whose registry row was since removed cannot be strict-saved until fixed — but waivable per write via `allowInvalid=true` (see `.claude/docs/authorization.md`), and the same snapshot feeds the Errors report (`GET …/errors` — which adds the report-only `STRUCTURE_INVALID`/`NAMESPACE_NOT_ALLOWED` checks over stored content, rules that stay HARD on writes) and `POST …/check`.

**Tag-category ownership under concurrency.** `TagCategoryService` takes a transaction-scoped
PostgreSQL `SHARE ROW EXCLUSIVE` lock on `tag_categories` before the first registry read in
create/replace/delete. The lock serializes these small, infrequent admin writes across service
instances, even when the registry is empty, while ordinary catalog/registry reads continue.
The ownership scan, active-category capacity check, target-existence check, and mutation stay
in the same read-committed transaction: after a lock wait, checks see the preceding commit.
Acquire the lock before row locks or reads that influence a write; locking only current rows
would miss new categories. Commit/rollback releases it. The JSON storage and soft-delete
model are unchanged, and conflicts retain the existing `409` response. This is a cooperating
writer protocol, not a unique constraint over array members: direct SQL writers and old
application versions must not bypass it. Deploy all tag writers with this protocol before
relying on the guarantee. Existing duplicate ownership is not silently repaired. Concurrent
full replacements of one category still use last-write-wins semantics. `LabelService.create`
and `AnnotationKeyService.create` now run their count-then-insert `MAX_LABELS`/
`MAX_ANNOTATION_KEYS` cap check under the same `lockingTransaction` `SHARE ROW EXCLUSIVE`
table-lock pattern (over `labels`/`annotation_keys` respectively), closing the identical
overshoot race for those two registries.

The current R2DBC path may finish cancellation only after a blocking database lock is
released. A cancelled write then rolls back; this protocol does not add a lock-wait deadline
or promise immediate database-query cancellation.

**Entity read memory budget and the consolidated read set (2.4.0).** `entities/
EntityWorkspaceRead.kt` replaces the pre-2.4.0 trio (`loadSnapshot`, `loadWorkspaceSnapshot`, and
`graph`'s own raw row `SELECT`) with ONE function, `loadReadSet(shownPredicate,
targetIdentifiers, blueprintsByIdentifier, reservation)`, called by `list`/`read`/`create`/
`update`/`graph` alike (see `.claude/docs/security.md` "Entity read memory budget" for the
budget/refusal semantics). Inside ONE transaction it: (1) resolves which ids match
`shownPredicate` alone (`list`/`read`/`create`/`update` pass `Op.FALSE` — nothing of THEIRS is
"shown" via this path, only validation/computed-property lookup TARGETS, computed by the pure
`narrowTargets` helper exactly as `loadSnapshot` used to inline: relation targets, `_team`/
`_user`, Inherited `ownership.path` blueprints, and — when `computed`, i.e. `list`/`read`/
`create` — every `computedPathBlueprints` target; `graph` passes the real `blueprint`/`q`/`team`
filter as `shownPredicate`, since a plain graph read genuinely IS this budget's target: it is
unpaged); (2) when `reservation` is non-null, RESERVES (all-or-nothing, 2.19.0) the whole
admission estimate — per row of the COMBINED (shown ∪ targets) set, in PostgreSQL,
`octet_length(document) + octet_length(team)` weighted by `ENTITY_SHOWN_ROW_RATIO` (shown) or
`ENTITY_TARGET_ADMISSION_RATIO` (target-only, = 1) — before a single document crosses the wire; (3) selects
the combined row set into `WorkspaceRow`s — raw, undecoded, tagged `shown = id ∈ shownIds`. `EntitySnapshot` (also in
`EntityWorkspaceRead.kt`) wraps the resulting rows exactly as before (`targetExists`,
`rowLookup`, the lazily-built `inbound` index), but each row's `EntityRowView` (`entities/
EntityComputed.kt`) decodes and charges lazily: `WorkspaceRow.skeleton()` (relations + team) and
`WorkspaceRow.full()` (adds `properties`) each decode and charge the SAME reservation ONCE,
memoized — the charge DRAWS DOWN the prepaid estimate and only an overrun moves the shared
ledger (`.claude/docs/security.md` "Entity read memory budget") — so an aggregation that only counts inbound relations (`EntityIndex.inbound`, built
from `skeleton()`) never touches — or charges for — a target row's `properties` at all.
`reservation = null` exempts `create`/`update` (writers, already serialized by the V28 lock)
from both the admission check and the later decode charges — `narrowTargets`/`loadReadSet` are
otherwise identical for reads and writes.

**Catalog whole-workspace reads (2.19.0).** `CatalogFileService.errors`/`graph`/`export` (the
three reads that decode every active `content` row — `activeSources()` and the export loop) run
through the private `readWorkspace(predicate) { … }`: it opens a `ReadLedger` reservation, and
INSIDE the read transaction, before the content `SELECT`, reserves `SUM(octet_length(content))`
over the same predicate x `CATALOG_READ_FOOTPRINT_RATIO` (x `CATALOG_EXPORT_FOOTPRINT_RATIO` for
the export) (`octetLength`, `infra/db/Sql.kt`),
mapping a `ReadBudgetExceeded` to the `400`/`429` pair via `throwReadBudgetHttp(…, "catalog")`.
The paged list, single read, `check` and the import dry-run (identity columns / one row at a time)
stay unbudgeted. The semantics, the ledger's capacity and the rule live in
`.claude/docs/security.md` "Entity read memory budget"; one `ReadLedger` is built in `infra/db/
Database.kt` and passed to both `CatalogFileService` and `EntityService`.

**Entity graph reads.** `EntityService.graph(EntityGraphFilter)` (Phase 3, v1.25.0) is a plain
read transaction, not a write path: `materializeGraph` calls `loadReadSet` with the `blueprint`/
`q`/`team` filter as `shownPredicate` (the catalog graph's rule, one level down — an edge needs
BOTH ends shown, so a row the filter hid contributes neither a node nor an edge, never a virtual/
MISSING one) and, without a `query`, `narrowTargets` of the shown blueprints as the target set
(computed = false: a plain graph never evaluates computed properties). Each SHOWN
`WorkspaceRow.full()` is decoded once (transiently, for `entityFindings`/`effectiveTeam` only —
`EntityGraphSource` itself carries only `relations`, never `properties`) to compute the returned
row's `findings`, condensed to a count. No lock is taken — a plain committed read, like the
entity list.

**Entity query reads (phase 7, v2.0.0).** With a `query` (`.claude/docs/entity-query-language.md`)
`EntityService.graph` runs TWO short read transactions with the CPU work between and after them:
the first (`loadQuerySchema`) reads the active blueprints plus the active `HIERARCHY` dictionary
values from `DictionaryService.Entries` — a sanctioned cross-feature table read, the
`BlueprintService` precedent — and closes; the query is parsed and validated against that
committed schema OUTSIDE any transaction (a refused query never reads an entity row, and the
Levenshtein suggestion scan never holds a pooled connection); the second is `materializeGraph`'s
own `loadReadSet` call, this time with `targetIdentifiers` widened to EVERY active blueprint
identifier — rather than the shown blueprints' targets only, since a traversal may pass through
entities the `blueprint`/`q`/`team` filters hide — admission-charged exactly like the plain graph
above, over the full workspace. After that transaction closes, `evaluateEntityQuery` (still on
the request-handling coroutine, then moved to the dedicated `entity-query` pool) walks every
`WorkspaceRow` calling `budget.checkpoint()` then `row.skeleton()` — decoding/charging relations +
team for the WHOLE workspace under the cooperative deadline BEFORE `InMemoryQueryGraph` is built,
so its own (unbudgeted) index-building loops never do uninterruptible work; `properties` stays
undecoded and uncharged unless a WHERE/RETURN actually reads one (`QueryRow.candidate`'s
`by lazy`). `InMemoryQueryExecutor` then runs on that pool under the same `QueryBudget` (the
phase-5 rule: a wide join never pins a pooled R2DBC connection or the entity lock); the shown
rows are intersected with the returned `(blueprint, identifier)` set and handed to the unchanged
`buildEntityGraph`. The two reads are independent committed snapshots: a blueprint changed
between them is validated against the first and evaluated against the second, which the
evaluator tolerates (an unknown label or relation simply matches nothing). Both are plain reads,
no lock; in-flight evaluations are bounded by `MAX_CONCURRENT_ENTITY_QUERIES` permits taken
after validation, before the second read (`429` when none is free; a refused query costs no
permit); a `429` also covers OTHER in-flight reads holding the read-memory-budget room (a
[`ReadBudgetExceeded`] whose `ownRequest` is false) — no snapshot cache (the trigger is
documented in the language reference). `checkQuery` is the first transaction alone, validation
after it, no entity rows at all.

**Entity errors reads (2.5.0).** `EntityService.errors` (`.claude/docs/port-data-model.md`
"Computed-property health") runs ONE read transaction: active blueprints plus the active
`HIERARCHY` dictionary values (the sanctioned `DictionaryService.Entries` read, the query-reads
precedent above), `shownScope` (the `blueprint`/`q`/`team` filter prologue extracted out of
`materializeGraph` and shared with it), `narrowTargets(computed = false)`, then `loadReadSet`
with the ledger reservation open — the same up-front `octet_length` admission charge as the
plain graph. Each SHOWN row is decoded once via `full()` for `entityFindings` and its effective
team; `loadVisibleSavedQueries(callerId)` reads the saved-query rows described above. After that
transaction closes, the PURE report runs OUTSIDE it — the `checkQuery` posture: jq expressions
compile on the caller (never the `entity-jq` executor), every visible saved query is parsed and
validated, and nothing is evaluated — so no pool, no permit, and no CPU work pins the connection
or lock the read transaction held. A plain committed read, no lock; readers never wait.

**Blueprint targets under concurrency (V27).** `BlueprintService` takes the same
transaction-scoped `SHARE ROW EXCLUSIVE` lock on `blueprints` before the first read in
create/update/delete: every relation/aggregation `target` inside a definition is a byte-exact
blueprint identifier held in JSON, not a foreign key, so the "target exists", "rename cascades
into the referrers" and "a targeted blueprint cannot be deleted" rules are cooperating-writer
rules over ≤200 rows loaded once under the lock. Since V34 `DictionaryService.replace` takes the
SAME `SHARE ROW EXCLUSIVE` lock on `blueprints` — for the `HIERARCHY` dictionary only — before its
referrer check (a value being removed or renamed must not be named by any active blueprint's
`hierarchy_relations`, else 409 naming the referrers), so a concurrent blueprint write waits and
then validates its keys against the COMMITTED dictionary; blueprint writers read
`DictionaryService.Entries` under their own lock for the same reason (`DictionaryConcurrencyTest`
pins the race). `infra/db/Locking.kt`'s `lockingTransaction`
is the shared helper behind this lock (and `TagCategoryService`'s and `EntityService`'s below) —
one `LOCK TABLE …` per statement, in the order given, inside a READ COMMITTED transaction. The
same caveats apply verbatim: direct SQL writers bypass it, readers never wait, cancellation may
land only after the lock releases.

**Entity targets under concurrency (V28).** Entity create/update/delete run under a TWO-table
lock protocol, in one fixed global order (deadlock-free): `LOCK TABLE blueprints IN SHARE MODE`
first, then `LOCK TABLE entities IN SHARE ROW EXCLUSIVE MODE`, both inside the write's ordinary
READ COMMITTED transaction. `SHARE` is self-compatible, so entity writers only serialize against
EACH OTHER on `entities` (via the `SHARE ROW EXCLUSIVE` half) — but `SHARE` on `blueprints`
conflicts with the blueprint writers' `SHARE ROW EXCLUSIVE` (the V27 lock above), so an entity is
never validated against a blueprint definition mid-change, nor attached to (or left orphaned by)
a blueprint being deleted. `BlueprintService.delete` needs no second lock: holding its own `SHARE
ROW EXCLUSIVE` already excludes every entity writer, so it counts `entities WHERE blueprint_id =
? AND NOT marked_as_deleted` — a sanctioned cross-feature table read of `EntityService.Entities`
— before its existing referrer check, and throws `409` naming the active entity count. Blueprint
schema/relation PUTs need nothing new: entities are not re-validated at edit time, they go stale
(re-checked, and re-blocked on their own next save) — see `.claude/docs/port-data-model.md`
"Entities" → "Lifecycle rules". Entity/`_team` rename cascades (v1.26.0, phase 4) and referrer
checks for all three target sources — relations, `team` field, and `format: team|user` properties
— run under the same lock protocol; the three sources are checked in one referrer sweep on
delete. **The workspace document+team byte budget (2.4.0)** — `SUM(octet_length(document)) +
SUM(octet_length(team))` over active rows (`infra/db/Sql.kt`'s `octetLength`) — is read inside
the SAME two-table-locked write as the entity-count caps, both in `create` and in `update`
(charged only the delta against the row's own already-loaded current size), so the aggregate a
write is checked against is never stale mid-write; the bulk-import planner mirrors this with a
per-row `EntityImportSnapshot.bytesFor` read once, outside any lock (the same plain-read posture
as its entity-count counterpart), so a concurrent write can still make its byte prediction stale
— reported the same way an unexpected pass-2 storage failure is, never silently wrong. The
`_team` delete-vs-entity-create race is pinned in `EntityConcurrencyTest`. The same
caveats as V27 apply: direct SQL writers bypass the protocol, readers never wait, and cancellation
may land only after a held lock releases.

The lock-mode compatibility and transaction lifetime follow the
[PostgreSQL explicit-locking rules](https://www.postgresql.org/docs/18/explicit-locking.html).

**Advisory lock (V46 telemetry).**

- **Applies when:** a piece of work must run on exactly one process per interval and there is no row to lock until it has been done.
- **Requirement:** take `pg_try_advisory_xact_lock(<bigint>)` (the TRY form: a loser skips, never waits; transaction-scoped, so
  commit or rollback releases it and a crashed holder never strands it) at the top of the one transaction that does the work.
  The telemetry cluster section is the only user: `TELEMETRY_LOCK_KEY` = the ASCII bytes of "ToadTele" = `0x546F616454656C65`
  (`telemetry/TelemetryStore.kt`). A new advisory lock needs its own constant, distinct from every other, and a line here. A row lock
  cannot do this job (nothing exists to lock until the first process has written its marker), and the table-lock protocols above
  do not apply: the cluster transaction touches only `telemetry_processes`/`telemetry_samples`, so it never interacts with the
  `blueprints` -> `entities` order.
- **Reference:** `TelemetryStore.clusterSection`, `TelemetrySampler.runClusterSection`.
- **Enforcement:** `TelemetryStoreTest` (the lock is exclusive while held and free after), `TelemetrySamplerTest` (two samplers on
  one database run the section once per interval).
- **Exception:** none. With the single replica of `k8s/app-deployment.yaml` the lock is uncontended; it is what keeps telemetry
  off the BACKLOG "Scaling constraint" list.

**Ownership storage rule (v1.26.0, phase 4).** An entity's `entities.team` column is NULL when
the blueprint has `ownership: { type: Inherited }` (the computed `team` is never stored) and
MUST carry a value when Direct/absent (the stored `team` field). The rule is enforced during
entity write validation. This storage rule is unchanged by the v1.30.0 read-side `team` filter:
`entities/EntityFilter.kt`'s `inheritedTeamMatches` (an extension on `EntityService`) resolves
Inherited teams in memory over the SAME snapshot machinery every other read builds, reached
through `EntityService.rowLookupFor` — the one narrow internal accessor into the otherwise-private
read set (`loadReadSet`/`EntitySnapshot`, 2.4.0) — costing one extra bounded query per team-filtered request over
the in-scope Inherited blueprints' entities, and none at all when no Inherited blueprint is in
scope.

**Computed-property evaluation and the widened target set (phase 5, v1.27.0; read-set model
since 2.4.0).** `narrowTargets(definitions, definitionsByIdentifier, computed)` — the pure set
computation `entities/EntityWorkspaceRead.kt` extracted from the pre-2.4.0 `loadSnapshot` —
widens `loadReadSet`'s target set with every blueprint a mirror path, an aggregation `target`, or
a `pathFilter` chain (in either direction) might touch, plus the Inherited ownership path
blueprints of each of THOSE — `entities/EntityComputed.kt`'s `computedPathBlueprints`, the
static twin of `ownershipPathBlueprints` — so mirror/aggregation evaluation never issues a fresh
query mid-walk. The widening runs only when `computed = true`: `list`, `read`, and `create`
request it (their responses evaluate computed properties); `update` (a `204`, no body) and
`graph` (nodes carry no `properties` at all) pass `computed = false` and never pay for it — the
`WorkspaceRow.full()`/`EntitySnapshot.inbound` machinery below is built or not per call, not per
feature. Each `WorkspaceRow` decodes its relations/team ONCE via `skeleton()` and its properties
ONCE (additionally) via `full()`, regardless of how many computed properties or findings ask for
either (both memoized; before phase 5 `rowLookup` re-decoded on every call), and the
reverse-lookup index aggregation candidates need (`EntitySnapshot.inbound`, `EntityIndex.inbound`,
keyed by `(target blueprint, relation value)` over every row's relations via `skeleton()` alone —
never `properties`) is itself `by lazy` — built only the first time a computed property actually
asks "who points at this entity", so an `update`/`graph` snapshot never constructs it at all, and
even when it IS built (e.g. a `list` request's inbound-count aggregation) the target rows'
`properties` stay undecoded and uncharged unless something ELSE also asks for them.

Evaluation itself runs OUTSIDE the transaction that built the snapshot: `list`/`read`/`create`
materialize their rows (as an in-memory `RawEntity`) plus the `EntitySnapshot` INSIDE
`suspendTransaction`/`writeTransaction` exactly as before, then map them through
`EntityService.toResponse` — a plain function over that in-memory data, no query inside it —
AFTER the transaction closes. A pathological jq expression or a wide aggregation fan-out then
pins a request-handling coroutine, never a pooled R2DBC connection or a database lock (see
`.claude/docs/security.md` "Computed-property evaluation (jq)" for why that boundary is load-
bearing). `update` and `graph` never evaluate computed properties at all, so this ordering
concern does not apply to them. Since v1.29.0 `EntityService.toResponse` is itself a `suspend`
function: each calculation is submitted to the bounded `entity-jq` worker pool
(`.claude/docs/security.md`) and awaited on the request-handling coroutine, still entirely
after the transaction above has closed — the transaction boundary itself is unchanged.

**Ontology import (phase 6, v1.28.0).** `blueprints/BlueprintImport.kt`'s and
`entities/EntityImport.kt`'s effectful `import`/`importCheck` extensions add NO new locking
protocol: each row's pass-1 and pass-2 write is an ordinary call into
`BlueprintService.create`/`update` or `EntityService.create`/`update`, so it runs under that
service's own V27 (`blueprints`) or V28 (`blueprints` then `entities`) table lock exactly as a
single-document API call would — a 200-document batch takes the lock up to 400 times (pass 1 +
pass 2 per deferred row), never once for the whole batch. The registry snapshot each planner
reads before writing (`BlueprintService.list()`, the new `EntityService.importSnapshot()` — one
plain, lock-free transaction reading active blueprint definitions plus every active entity's
`(blueprintId, identifier) → id`) is a single committed read, the same posture as the catalog
import's `batchIdentities`/`withImportSnapshot` and the entity graph's plain read: it can go
stale the instant a concurrent writer commits, which is exactly why a pass-2 failure is possible
and is reported `ERROR` naming the row's `id` rather than silently left `CREATED`/`UPDATED` — a
concurrent-change residual, not a bug in the ordering. `planBlueprintImport`/`planEntityImport`
themselves touch no table at all: pure functions over the snapshot and the batch, so the two
planner test files run without Docker. Pass-2 writes share the SAME per-document
storage-failure helper as pass one, but retain the committed id and report ERROR for any
restoration failure, including zero affected rows and unique conflicts. Only a pass-one
unique race with no committed id is EXISTS. Cancellation propagates. Immediately after each
successful pass-one service return, a required synchronous committed-mutation callback emits
that row's create/update audit before another row or pass two begins. Final ERROR verdicts or
later cancellation cannot erase this event; pass two emits no duplicate. Audit logs remain
post-commit external output, not an atomic database outbox or a process-crash delivery guarantee.

Current migrations are `V1`–`V48` (V39–V42 are described in their own sections below the catalog; V43, a data-only seed re-curation, sits beside V32; V47, a data-only annotation-key seed, follows V43's paragraph; V48, the SSO identity columns, follows V47's; V44, an additive column, follows V48's; V45, a new registry table, follows V44's; V46, the telemetry tables, has its own section at the end of this file, "Telemetry (V46)") — small enough that this section is the catalog (Lettuce splits it into `.claude/docs/features/migrations.md`; introduce that file when the count warrants it):

- `V1__init` — the `users` table: `name` (≤50), `email` (≤254), `password_hash`, `role` with `CHECK ("role" IN ('ADMIN', 'USER'))` (single-column role storage; the wire shape stays a `roles` set, see `.claude/docs/authorization.md`), `password_changed_at` (epoch millis, 0 = never — retained as a timestamp; V25's monotonic `auth_version` supersedes timestamp-based token invalidation), `marked_as_deleted`; plus the partial unique index `uq_users_email_active` over active rows.
- `V2__create_revoked_tokens` — the JWT blocklist for `/logout`: `jti` PK + `expires_at`, with an index on `expires_at` (the revoke path prunes expired rows opportunistically, so the table stays tiny).
- `V3__seed_admin` — the bootstrap administrator `admin@toadie.local` / `changeme`, idempotent via `ON CONFLICT DO NOTHING`; production neutralizes it at startup (see "Default admin" in `.claude/docs/security.md`).
- `V4__enable_unaccent_extension` — Lettuce's unaccent migration, backing every `containsNormalized` substring filter (see `infra/db/Sql.kt`).
- `V5__create_catalog_files` — stored catalog-info.yaml documents (one row = one entity; structured JSON in `content`, identity columns `kind`/`name`/`namespace` denormalized), `created_by` FK to `users`, epoch-millis `created_at`/`updated_at`, and the partial unique index `uq_catalog_files_entity_active` over `(kind, namespace, LOWER(name))` — Backstage's case-insensitive identity, active rows only.
- `V6__widen_catalog_kinds` — the kind CHECK grows from Component-only to the seven landscape kinds (API, System, Domain, Resource, Group, User join; Location/Template deliberately out).
- `V7__create_dictionary_entries` — Lettuce's shared dictionaries table (single-valued): enum-name discriminator `dictionary` (the Kotlin `Dictionary` enum is the whitelist — no CHECK), `position` (rewritten from payload order on every save; soft-deleted rows keep stale positions — reads filter active and order by `position, id`), `value` ≤63 (stored lowercase-folded), soft-delete, plus the partial unique index `uq_dictionary_entries_value_active` over `(dictionary, value)` active rows.
- `V8__seed_namespaces` — seeds the `NAMESPACE` dictionary with Backstage's `default` namespace (idempotent, `ON CONFLICT … DO NOTHING` against V7's partial index). An ordinary entry — admins may reorder or remove it like any other.
- `V9__default_namespace_flag` — `is_default` on `dictionary_entries` (flagging the active seeded `default`), plus the at-most-one backstop `uq_dictionary_entries_default_active` over `(dictionary)` active flagged rows; the EXACTLY-one rule for non-empty documents lives in `validateDictionaryUpdate`, and `DictionaryService.replace` clears every active flag before its upserts so moving the flag in one save never trips the index. Blank/omitted catalog-file namespaces resolve to the flagged entry at write time.
- `V10__create_labels` — the ADMIN-curated label registry: one row = one allowed `metadata.labels` key with its closed value list and applicable kinds (both JSON arrays in TEXT — the `catalog_files.content` precedent, no child tables), soft-delete, plus the partial unique index `uq_labels_key_active` over `LOWER(key)` active rows (no case-twin keys; a soft-deleted label frees its key). No seed — an empty registry means no file may carry labels until an admin defines some (`V22` later seeds the reference set).
- `V11__create_tag_categories` — the ADMIN-curated tag categories (an INTERNAL Toadie concept): one row = one category with its display `name` (≤63, not Backstage grammar), tag list, and applicable kinds (JSON arrays in TEXT), soft-delete, plus the partial unique index `uq_tag_categories_name_active` over `LOWER(name)` active rows. **The one-category-per-tag invariant is enforced service-side in the serialized write transaction** (tags remain JSON arrays; the locking protocol is described above). No seed — an empty registry means no file may carry tags (`V22` later seeds the reference set).

- `V12__user_disabled_features` — Lettuce's per-user feature flags (the DISABLED set — no row = enabled, so the empty table needs no backfill): `(user_id, feature)` PK, `ON DELETE CASCADE`, no CHECK on feature (the Kotlin `Feature` enum is the whitelist), plus the feature index behind the users-list `feature`/`featureEnabled` filter pair.

- `V13__seed_mfa_disabled_flags` — MFA joins the flags with an INVERTED default (opt-in): every pre-existing user gets the `MFA` disabled row (`ON CONFLICT DO NOTHING`); `UserService.create` inserts the same row for every later user.

- `V14__create_entity_types` — the ADMIN-curated per-kind type dictionaries (an INTERNAL Toadie constraint on the open `spec.type` field): one row = one type-bearing kind (canonical casing, no CHECK — the Kotlin `TYPE_BEARING_KINDS` list is the whitelist; User excluded, its spec has no type) with its allowed types (JSON array in TEXT), soft-delete, plus the partial unique index `uq_entity_types_kind_active` over `(kind)` active rows (no LOWER — kinds are stored canonical). The dictionaries are INDEPENDENT: no cross-row uniqueness (unlike tags' one-category-per-tag rule).

- `V15__seed_entity_types` — seeds all six dictionaries with the descriptor reference's well-known values (Component service/website/library, API openapi/asyncapi/graphql/grpc, System product/service/feature-set, Domain product-area/product-group/bundle, Resource database/s3-bucket/kubernetes-cluster, Group team/business-unit/product-area/root), idempotent against V14's partial index (the V8 idiom). Ordinary rows — admins may edit or delete them; a kind left without a dictionary allows NO types (required-type kinds then cannot save).

- `V16__seed_lifecycles` — seeds the `LIFECYCLE` dictionary (V7's table, second `Dictionary` enum value) with the well-known values experimental/production/deprecated (positions 0–2, the V8 conflict idiom). NO default flag — `Dictionary.usesDefault` is false for LIFECYCLE, and `validateDictionaryUpdate` rejects flagged items on it (the per-dictionary branch). The GLOBAL allowlist every catalog write's non-blank `spec.lifecycle` must be in.

- `V17__create_annotation_keys` — the ADMIN-curated annotation-key registry (the V10 labels shape minus `allowed_values` — annotation VALUES stay free): one row = one allowed `metadata.annotations` KEY with its applicable kinds (JSON array in TEXT), soft-delete, plus the partial unique index `uq_annotation_keys_key_active` over `LOWER(key)` active rows. No seed — an empty registry means no file may carry annotations until an admin registers keys (the labels posture; `V22` later seeds the reference set). The server-written `backstage.io/*` keys cannot be registered.

- `V18__add_users_language` — the per-user language (Lettuce's V61): `users.language VARCHAR(10) NOT NULL DEFAULT 'en'` — no index, no CHECK (`SUPPORTED_LANGUAGES` in `dictionaries/Languages.kt` is the whitelist, the V12 idiom). Drives the UI at sign-in and every server-composed email's language; set at create, changed only via `PUT /users/{id}/language` (see `.claude/docs/authorization.md`).

- `V19__create_graph_layouts` — the per-user Graph-page layout (`graph_layouts`, one row per user, `user_id` PK/FK `ON DELETE CASCADE`): `mode VARCHAR(10)` (`auto`/`manual` — no CHECK, `GRAPH_LAYOUT_MODES` in `users/GraphLayout.kt` is the whitelist) + the manually dragged node positions as ONE JSON object in TEXT keyed by node id `kind:namespace/name` (the `catalog_files.content` precedent) + `updated_at` (+ the collapsed node ids since `V24`, below). A **hard-delete table** (the `user_disabled_features` exception class): a pure per-user settings row whose PUT is a wholesale replace — no history worth keeping. Read/replaced only via GET/PUT `/users/{id}/graph-layout` (`users/GraphLayoutService.kt`, upsert — a drag stop and a mode switch may race from one client). `V30` gives the Entity graph page an independent twin table, `entity_graph_layouts`, behind its own `/users/{id}/entity-graph-layout` pair (below).

- `V20__create_lenses` — saved filter sets (`lenses`, the labels CRUD shape + V5's creator column): `name` (≤100), `visibility VARCHAR(10)` (`PRIVATE`/`PUBLIC` — no CHECK, the Kotlin `LensVisibility` enum is the whitelist, the V12/V18 idiom), the nine shared filter slots as ONE JSON object in TEXT (`filters` — the `catalog_files.content` precedent), `created_by` FK (`ON DELETE RESTRICT`, the V5 shape), epoch-millis timestamps, soft-delete, plus the partial unique index `uq_lenses_owner_name_active` over `(created_by, LOWER(name))` active rows — names are unique PER OWNER only (public lenses from different creators may share one; the picker disambiguates by creator name). PRIVATE rows are visible only to their creator; PUBLIC rows to everyone, both creator-only mutable (see `.claude/docs/authorization.md`).

- `V21__catalog_file_source` — source references & repo sync: three envelope columns on `catalog_files` — `source_url VARCHAR(2048) NULL` (the https URL of the file's canonical repo copy; NULL = none, the report-only `SOURCE_MISSING` Errors finding), `last_synced_at BIGINT NOT NULL DEFAULT 0` (epoch millis of the last repo→DB sync; 0 = never — the `password_changed_at` idiom, and the sortable `lastSyncedAt` field: 0 sorts most-stale-first), and `synced_content TEXT NULL` (the document JSON snapshot at sync time — the baseline attributing later changes to a side). The stored `content` stays a PURE Backstage document — the reference never enters it, the export, or the import parser. Invariants live in `CatalogFileService`: a sync (and a fetch-from-URL import row) stamps `updated_at = last_synced_at` and `synced_content` = the stored JSON, so — while `last_synced_at > 0` — `updated_at > last_synced_at` means "modified in the DB since the sync" (a changed/cleared reference resets `last_synced_at` to 0, where the bare predicate carries no drift meaning); an update bumps `updated_at` ONLY on a content change, and a changed/cleared `source_url` resets the sync state.

- `V22__seed_registries` — seeds all six ADMIN-curated registries with the reference workspace's curation, so a freshly built environment starts with the vocabulary an admin would otherwise re-enter by hand: the `external` namespace joins V8's `default` (unflagged — the default stays where V9 put it), `sunsetting` joins V16's lifecycles with the positions rewritten to the real progression experimental → production → sunsetting → deprecated, every type-bearing kind's `entity_types` list is replaced with the curated one (V15's well-known values were never this workspace's), and `labels` (8 rows), `tag_categories` (4) and `annotation_keys` (4) get their FIRST seed — V10/V11/V17 deliberately shipped none, so those registries were empty and no file could carry a label, tag or annotation at all. Every statement is an idempotent upsert whose conflict target names the table's partial unique index with that index's own predicate spelling (plus the `LOWER(...)` expression for the three case-insensitive ones); the three registries V8/V15/V16 already seeded take `DO UPDATE` — a seed declares the intended STATE and those rows exist, so the `VALUES` list IS the curation — while the three that had no seed at all take `DO NOTHING`, keeping an admin's pre-upgrade curation. Surviving an upgrade: an admin's own extra ROWS everywhere, and every edit to labels/tag categories/annotation keys. Not surviving: a re-ordering of the seeded namespaces/lifecycles, and — the one with teeth — extra TYPES added inside a seeded kind's row, since that list is replaced wholesale (files carrying such a type go strict-invalid on their next save, exactly as if an admin had removed the value by hand). One more caveat with teeth: **an admin's soft-DELETION of a seeded row does not survive either** — every conflict target is the partial active-rows index, so a soft-deleted twin does not conflict and the INSERT half creates a fresh ACTIVE row (V9's never-resurrect rule holds for dictionary saves, not for this seed); a deliberately removed seeded value comes back selectable after the upgrade, with no audit event. Accepted for a one-time seed — but the next seed migration should decide this consciously (a `NOT EXISTS` over ALL rows keeps deletions). Ordinary rows throughout — no special-case protection, the V15/V16 posture. Constraints the SQL respects because no index backs them: the seeded tag lists are DISJOINT (one-category-per-tag is service-side only) and no server-written `backstage.io/*` key is registered. V43 later widens `gdpr`/`pci-dss` to Component/API and adds three keys (see below).

- `V23__create_catalog_file_events` — the FIRST `EventLogTable` clone (Lettuce's `V15` shape in Toadie's dialect: `SERIAL`/`INTEGER` like V20, not `BIGSERIAL`): `catalog_file_events`, the immutable per-file change trail behind the editor's History section — `catalog_file_id` (CASCADE), `user_id` (RESTRICT, so an actor is never hard-removed out from under the history), epoch-millis `created_at`, `event_type VARCHAR(40)` (no CHECK — the Kotlin `CatalogFileEventType` enum is the whitelist, the V12/V18/V20 idiom), and `params TEXT` holding the structured JSON map the SPA localizes (an UPDATED/SYNCED event's field-level diff: `changed` plus the per-field `<path>.from`/`.to`/`.added`/`.removed` companions — never free text), plus the owner-FK index. A **hard-delete table** whose CASCADE is vestigial: `catalog_files` soft-deletes, so a file's events outlive it and the DELETED event lands in a history the API can no longer reach (deliberate — kept for the record).

- `V24__graph_layout_collapsed` — the Graph's collapsed nodes join V19's per-user layout document: `graph_layouts.collapsed TEXT NOT NULL DEFAULT '[]'`, the node ids the user folded as ONE JSON array in TEXT (the `positions` column's own idiom, the same `kind:namespace/name` keys). Existing rows fold nothing. Like positions it is replaced wholesale on every save and never pruned server-side — an id of a node outside the user's current filter simply waits — and held to the same ceiling and key grammar (`validateGraphLayout`, one `requireNodeKey` for both).

**V26 — password reset grants.** `password_reset_tokens` stores a SHA-256 digest primary key,
`user_id`, captured `auth_version`, and epoch-millisecond `expires_at`, with user/expiry indexes.
Raw 256-bit tokens exist only in delivery/confirmation memory. These are ephemeral credentials,
not business/history records: consumed grants and siblings hard-delete, expired grants prune
on issuance, and failed delivery revokes its grant. Issuance/confirmation lock the active user
before touching grants; expiry cleanup runs separately to preserve lock ordering. Confirmation
uses `users.updatePasswordInTransaction` inside the SAME transaction as consumption — never
compose two independent service transactions. Concurrent same/sibling confirmations have one
winner; epoch checks also invalidate links after email/role/password changes or deletion.
Migration checksums, including V26, are pinned in `MigrationChecksumTest`.

**V27 — blueprints.** `blueprints` (Port.io-style user-definable entity kinds, v1.23.0):
identity columns denormalized for listing and uniqueness — `identifier` (≤100, the Port charset)
, `title` (≤100), nullable `description` (≤2000) and `icon` (a Port icon NAME) — plus ONE
`definition` TEXT holding the rest of the Port document (`schema`, `relations`,
`mirrorProperties`, `calculationProperties`, `aggregationProperties`, `ownership`) as JSON
encoded with `explicitNulls = false` (the `catalog_files.content`/`lenses.filters` precedent,
replaced whole on every save), `created_by` FK (`ON DELETE RESTRICT`, the V5/V20 shape),
epoch-millis timestamps, soft-delete, the partial unique index `uq_blueprints_identifier_active`
over `LOWER(identifier)` active rows (no case twins; a soft-deleted blueprint frees its
identifier — its `UNIQUE_CONSTRAINT_DETAILS` entry names the clash), and the created_by /
marked_as_deleted indexes. Relation and aggregation targets inside definitions are byte-exact
identifiers cascaded on rename by the service under the table lock (above). No content seed —
the two Port SYSTEM blueprints `_team`/`_user` arrive with V31 (below), as schema. V38 (2.10.0,
below) adds the `source_url`/`last_synced_at`/`synced_content` envelope to this same table,
written under this same lock.
Migration checksums, including V27, are pinned in `MigrationChecksumTest`.

**V28 — entities.** `entities` (instances of a blueprint, Phase 2 of the Port data-model move,
v1.24.0 — see `.claude/docs/port-data-model.md` "Entities"): `blueprint_id INTEGER NOT NULL
REFERENCES blueprints(id) ON DELETE RESTRICT` — a FOREIGN KEY to the blueprint's `id`, not its
identifier, so a blueprint RENAME never touches entities (relation targets stay byte-exact
identifiers inside `document`, the blueprint-target precedent); identity columns denormalized
for listing — `identifier` (≤200, Port's wider entity charset), `title` (≤200), nullable `icon`
(≤100) — plus `team TEXT NULL` holding the `team` value EXACTLY as sent (`"x"` or `["x","y"]`;
`NULL` = absent) and ONE `document TEXT NOT NULL` holding `{properties, relations}` via
`blueprintJson` (the same explicit-nulls-off encoding as `blueprints.definition`, replaced whole
on every save), `created_by` FK (`ON DELETE RESTRICT`, the V5/V20/V27 shape), epoch-millis
timestamps, soft-delete, the partial unique index `uq_entities_blueprint_identifier_active` over
`(blueprint_id, LOWER(identifier))` active rows (no case twins WITHIN one blueprint; the same
identifier is reusable across different blueprints since the index is scoped by `blueprint_id`;
a soft-deleted entity frees its identifier — its `UNIQUE_CONSTRAINT_DETAILS` entry names the
clash), and the `blueprint_id` / `created_by` / `marked_as_deleted` indexes. Relation targets
inside `document` are byte-exact `(blueprintIdentifier, entityIdentifier)` pairs cascaded on
identifier rename by the service under the two-table lock (above). No seed — the registry starts
EMPTY, like blueprints. Migration checksums, including V28, are pinned in
`MigrationChecksumTest`.

**V29 — hierarchy relation.** `ALTER TABLE blueprints ADD COLUMN hierarchy_relation
VARCHAR(100) NULL` (Phase 3 of the Port data-model move, v1.25.0 — see
`.claude/docs/port-data-model.md` "Toadie extensions"): an identity column beside
`definition`, so the stored Port document (`toDefinition()`'s output) stays byte-identical —
a future Port export drops the column rather than stripping anything out of the JSON. No
index, no CHECK: `BlueprintValidation.kt` enforces that a non-null value names a KEY of the
same row's `relations` with `many == false`; no cascade on relation rename, since the column
names a relation KEY of its OWN row, not another blueprint's identity. Read/written alongside
`definition` in `BlueprintService.insertRow`/`update`/`toResponse`, under the same V27 table
lock (a relation the value points at may be renamed/removed in the very definition being
saved). **Replaced by V34 (v1.32.0, phase 3 enhancement)**: this single-string column is
migrated to a JSON map supporting multiple hierarchies; see V34 below. Migration checksums,
including V29, are pinned in `MigrationChecksumTest`.

**V30 — the Entity graph's own layout.** `CREATE TABLE entity_graph_layouts`, a byte-copy of
V19+V24's `graph_layouts` shape (`user_id PK/FK ON DELETE CASCADE`, `mode`, `positions TEXT`,
`collapsed TEXT`, `updated_at`) behind the Entity graph page's own
GET/PUT `/api/v1/users/{id}/entity-graph-layout`. It is a SEPARATE table, not a shared row
with `graph_layouts`: the Backstage Graph page and the Entity graph page persist independent
documents, keyed by a different node-id grammar (`kind:namespace/name` vs.
`<blueprint>|<identifier>`), so one page's manual layout/fold state never leaks into the
other's. `users/GraphLayoutService.kt` is generalized to `GraphLayoutService(database, table:
GraphLayoutTable)` over an `abstract class GraphLayoutTable(name)`, with `GraphLayouts` and
`EntityGraphLayouts` as its two concrete Exposed table objects — `Database.kt` constructs and
publishes two independent service instances, one per table, both under the
`GraphLayoutServiceKey`/`EntityGraphLayoutServiceKey` pair. A **hard-delete table**, the same
exception class as V19 (`graph_layouts`) and `user_disabled_features`: a pure per-user
settings row whose PUT is a wholesale replace, no history worth keeping. Migration checksums,
including V30, are pinned in `MigrationChecksumTest`.

**V31 — system blueprints.** `ALTER TABLE blueprints ADD COLUMN is_system BOOLEAN NOT NULL
DEFAULT FALSE` (Phase 4 of the Port data-model move, v1.26.0 — see `.claude/docs/port-data-model.md`
"System blueprints"): a flag marking Port's seeded SCHEMA blueprints `_team` and `_user`. The two
rows are inserted idempotent via `ON CONFLICT (LOWER(identifier)) WHERE NOT marked_as_deleted DO
UPDATE SET is_system = TRUE`, adopting a pre-existing user-made `_team`/`_user` by flag only (the
definition is untouched; its next PUT must then include the base shape). **This is the ONE
documented exception to "no migration seeds blueprints"**: these two rows are SCHEMA (the V22
registry-seed posture), not workspace content; they count toward `MAX_BLUEPRINTS`. `created_by`
is `(SELECT MIN(id) FROM users)` — the V3 seed admin is the first-ever `users` row and users never
hard-delete, so it survives a renamed seed email (a subselect by email would return NULL and fail
the NOT NULL FK, refusing boot). `definition` is stored in `blueprintJson`'s canonical form
(schema/relations/mirrorProperties/calculationProperties/aggregationProperties in that order;
unset optionals ABSENT, never null) so a later service write is byte-identical — `_team`'s base
is `{schema: {}, relations: {parent → _team, single, optional}, hierarchyRelations: {"composition": "parent"}}`
and `_user`'s is `{schema: {email → string required}, relations: {team → _team, many, optional},
no hierarchyRelations}`. Protections live in `BlueprintService` under the V27 lock: delete is `409`
("a system blueprint"), identifier immutable and base shape not removable/reshapable (the
`validateSystemExtension` function, `400`), identifiers starting with `_` reserved on create
(`400`); ADMIN may extend (add properties/relations, set `hierarchyRelations`, add `ownership`).
Migration checksums, including V31, are pinned in `MigrationChecksumTest`.

**V32 — Languages gains kotlin.** A pure DATA adjustment to V22's seeded "Languages" tag
category (`tag_categories.tags`, one JSON array in TEXT — see V11 above): appends `"kotlin"`
via `tags::jsonb || '["kotlin"]'::jsonb`, never a wholesale replace, so an admin's own
additions/removals to that list otherwise survive. This is the conscious decision V22's own
paragraph asked the next seed migration to make, taken in the OPPOSITE direction of V22's
INSERT half: the predicate requires an ACTIVE `Languages` row (`NOT marked_as_deleted`), so an
admin's soft-deletion of the category is **not** undone and the row is **not** re-created; and
it skips entirely if any OTHER active category already holds `kotlin` (`NOT EXISTS … o.tags::jsonb
? 'kotlin'`), respecting an admin who moved the tag elsewhere under the one-category-per-tag
invariant (service-side only, no database backstop — the V11/V22 posture). Idempotent via the
same `? 'kotlin'` containment check on `Languages` itself. `tags` is read back exclusively
through `Json.decodeFromString` (`TagCategoryService.kt`) and never string-compared, so
PostgreSQL's `jsonb` round-trip re-serializing the array with spaces is immaterial. Migration
checksums, including V32, are pinned in `MigrationChecksumTest`.

**V43 — regulatory-flag labels (2.17.0).** A pure DATA re-curation of V22's seeded `labels`
rows (V22's bytes stay immutable): it WIDENS `gdpr` and `pci-dss` from Resource-only to
Component + API + Resource, and INSERTS `banking-outsourcing` (Component/API/Resource),
`cash-flow-impact` (Component) and `dora-cif` (Component), each with the closed values
`["yes","no"]`. Two conscious decisions, in V32's direction: the widening touches ACTIVE rows
only (`NOT marked_as_deleted`) and REBUILDS `allowed_kinds` as existing ∪ new in canonical
`SUPPORTED_KINDS` order, so an admin's extra kinds and values survive and a soft-deleted
`gdpr`/`pci-dss` is never resurrected; each INSERT is guarded by `NOT EXISTS` over ALL rows
with that `LOWER(key)`, soft-deleted included, so a key an admin created, edited or deleted
stays as it is (the posture V22's paragraph recommended). Idempotent: the widening skips a
row already holding both kinds (`?&`), the INSERT skips an existing key. `allowed_kinds` is
read back exclusively through `Json.decodeFromString` (`LabelService.kt`,
`CatalogRegistryReader.kt`) and never string-compared, so PostgreSQL's `jsonb` round-trip
re-serializing the array with spaces is immaterial. Migration checksums, including V43, are
pinned in `MigrationChecksumTest`; `LabelTest` pins the widened kinds and the eleven keys.

**V47 — `backstage.io/definition-at-location` annotation key (2.31.0).** A pure DATA seed of
V17's `annotation_keys` (V22's bytes stay immutable): it registers the well-known
`backstage.io/definition-at-location` key for the API kind only, so the default conversion
mapping's `definition_url` rule (`copy` codec, `url:` prefix) can write it on an exported API
without every export landing as a registry finding. V43's posture, not V22's: the INSERT is
guarded by `NOT EXISTS` over ALL rows with that `LOWER(key)`, soft-deleted included, so a key an
admin registered by hand (with whatever kinds), edited or deliberately deleted stays exactly as
it is and is never resurrected (V22's `ON CONFLICT DO NOTHING` targets the partial active-rows
index, so a soft-deleted twin did not conflict there). Idempotent: a second run inserts nothing.
`allowed_kinds` is read back exclusively through `Json.decodeFromString`. The key is not one of
the server-written `backstage.io/*` keys the registry refuses. The checksum is pinned in
`MigrationChecksumTest`; `AnnotationKeyTest` checks that the five seeded keys are present.

**V48 — SSO identity and passwordless accounts.** Three changes on `users`, no data rewrite and no
backfill (every existing row keeps its hash and reads as "no SSO link"). `password_hash` DROP NOT
NULL: a NULL hash is a PASSWORDLESS account, reachable only through its SSO link until an admin sets
a password (`security.md` "Passwordless accounts (V48)"). `sso_issuer VARCHAR(512)` and
`sso_subject VARCHAR(255)`, both nullable and bound by `ck_users_sso_identity_pair` (both set or both
NULL, `(sso_issuer IS NULL) = (sso_subject IS NULL)`); `ck_users_service_account_no_sso` forbids a
service account (V41) from carrying a link. The identity index `uq_users_sso_identity` over
`(sso_issuer, sso_subject)` is a FULL unique index, **the deliberate exception to the partial-index
idiom of "Freeing a unique business field on delete"**: a soft-deleted user keeps its link, so
deleting an account really revokes its SSO access and the same external identity can never silently
recreate it. NULL pairs (every password-only account) never collide. Exposed: `Users.passwordHash` is
`.nullable()` and `ssoIssuer`/`ssoSubject` are new nullable columns; `User.passwordHash` is `String?`
with derived `sso`/`hasPassword` flags, surfaced on `UserResponse` and (`hasPassword`) `LoginResponse`.
`rotatePasswordIfHashMatches`/`countActiveWithPasswordHash` compare against a concrete hash, which a
NULL never equals, so the bootstrap seed checks are unchanged. The checksum is pinned in
`MigrationChecksumTest`; `PasswordlessAccountTest` pins the constraints and the index.

**V44 — blueprint tiers (2.18.0).** One additive column, `blueprints.tiers TEXT NOT NULL DEFAULT
'{}'` — the `hierarchy_relations` (V34) idiom: a Toadie-only JSON object stored BESIDE the Port
`definition`, never inside it (`{"blueprint":n,"properties":{..},"relations":{..}}`, see
`port-data-model.md` "Tiers"). No existing row changes (every row reads as "no tiers"), no
backfill, no index, and no CHECK: the 1..4 range and the key-existence rules are
request-validation (`blueprints/BlueprintTiers.kt`), the registry-is-the-whitelist posture.
The column is written by exactly the paths that write `hierarchy_relations` — `insertRow` and
`BlueprintSync.replaceRow`, under the V27 lock — and read by `toResponse`, `activeRows` and
`EntityService.loadActiveBlueprints` (for the query engine); it is never part of the V39
ontology-revision inputs beyond the bump every blueprint write already does. Decoded
exclusively through `blueprintJson.decodeFromString`, never string-compared. The checksum is
pinned in `MigrationChecksumTest`.

**V45 — conversion mappings.** One new registry table, `conversion_mappings`: `id`,
`name VARCHAR(100)`, `document TEXT` (the mapping document RE-SERIALISED as compact JSON, `schemaVersion` 1 —
`conversion/MappingDocument.kt`; not the bytes the admin sent: whitespace is dropped, escapes are
normalised, and a duplicated key collapses to its LAST value because the JSON reader cannot report
duplicates), `created_by` (FK `users`), `created_at`/
`updated_at` (epoch-millis `BIGINT`, the V27/V35 idiom — not `TIMESTAMPTZ`), `marked_as_deleted`;
the partial unique index `uq_conversion_mappings_name_active` over `LOWER(name)` for active rows
(a soft-deleted mapping frees its name; its 409 wording is in `plugins/ErrorHandling.kt`'s
`UNIQUE_CONSTRAINT_DETAILS`). **No row is seeded**: the default mapping is
`sample-data/port/commerce-payments/mapping/backstage.json`, loaded by `mapping/load.sh`, never by a
migration. No CHECK constraints — the caps (20 active rows, 256 KiB per document, 100 characters per
name) are service rules. Stored-JSON rules: the column holds only what the strict parser accepted
(no `null` member anywhere, no unknown member), and from the first save on it round-trips
byte-identically (`GET`, then `PUT` of the same document, stores the same text); `schemaVersion` is part of the document, not a column — a stored document whose version the
running server does not support (a future downgrade) still reads, with a `SCHEMA` finding at
`/schemaVersion`, and is never rewritten. The service writes under a `SHARE ROW EXCLUSIVE` lock on
`conversion_mappings` only for `create`'s cap check (the `labels` precedent); its validation reads
(`BlueprintService.activeRows`, `CatalogRegistryReader.loadSnapshot`) run inside the write's own
transaction WITHOUT any lock — a mapping stores names the validator re-checks on every read, so a
concurrent blueprint or registry edit surfaces as a recomputed finding (like a stale entity), never
as a failed write. The registry is capped at 20 rows, so its reads (and the snapshots the validator
needs) sit outside the whole-workspace read ledger. The Port Errors report reads the table directly
inside its own read transaction (`conversion/MappingErrors.kt` `loadStoredMappings`, the
`loadVisibleSavedQueries` idiom). The checksum is pinned in `MigrationChecksumTest`.

- `V33__seed_hierarchies` — seeds the new `HIERARCHY` dictionary (V7's table, a third `Dictionary`
  enum value) with the single value `composition` (position 0, the V8/V16 conflict idiom). NO
  default flag — `Dictionary.usesDefault` is false for HIERARCHY, and `validateDictionaryUpdate`
  rejects flagged items on it (the LIFECYCLE branch). `composition` is the one entity hierarchy
  the pre-1.32 single blueprint `hierarchyRelation` always described; V34 (below) backfills every
  existing pointer into this seeded map entry. Since V34 every blueprint write's `hierarchyRelations`
  keys must be ACTIVE entries here, and `DictionaryService.replace` refuses (409, naming the
  referrers) to remove or rename a value an active blueprint still names — see "Blueprint targets
  under concurrency (V27)" for the lock it takes to make that check hold.

**V34 — hierarchy relations map.** `ALTER TABLE blueprints ADD COLUMN hierarchy_relations TEXT NOT
NULL DEFAULT '{}'` (v1.32.0 — parallel entity hierarchies, see `.claude/docs/port-data-model.md`
"Toadie extensions"): ONE JSON object in TEXT (the `catalog_files.content`/`lenses.filters`
precedent, replaced whole on every save) mapping a hierarchy identifier — an ACTIVE value of the V33
`HIERARCHY` dictionary — to the relation key that is the entity's parent link in THAT hierarchy;
`{}` = the blueprint takes part in no hierarchy (ABSENT on the wire). The migration then backfills
`json_build_object('composition', hierarchy_relation)` into every row whose V29 pointer is set
(soft-deleted rows included — harmless, the column is replaced whole on the next save) and DROPS
`hierarchy_relation`. V31's INSERT still names the dropped column: it runs earlier on a fresh
database, so it stays valid and `_team` lands as `{"composition":"parent"}` through the backfill —
`SystemBlueprintTest` pins that. Still beside `definition`, never inside it, so the stored Port
document stays byte-identical (a Port export drops the column). No index, no CHECK: the key/value
rules live in `BlueprintValidation.kt` (value = a `many: false` relation KEY of the same row) and
`BlueprintService` (key = an active dictionary value, read inside the V27 locked transaction —
a sanctioned cross-feature table read of `DictionaryService.Entries`). One relation may serve
several hierarchies. Migration checksums, including V34, are pinned in `MigrationChecksumTest`.

**V35 — saved entity queries.** `entity_queries` (phase 7, v2.1.0 — `.claude/docs/entity-query-language.md`
"Saved queries"): V20's `lenses` shape VERBATIM with the nine filter slots replaced by ONE
`query TEXT NOT NULL` — the entity-query text as typed (trimmed, multi-line allowed, ≤ 2000
characters enforced by the service, syntax-valid at save time via `parseEntityQuery`, schema
validity deliberately NOT required so a query survives a blueprint edit and shows its
diagnostics when applied); `name` (≤100), `visibility VARCHAR(10)` (`PRIVATE`/`PUBLIC` — no
CHECK, the Kotlin `SavedEntityQueryVisibility` enum is the whitelist), `created_by` FK
(`ON DELETE RESTRICT`), epoch-millis timestamps, soft-delete, the partial unique index
`uq_entity_queries_owner_name_active` over `(created_by, LOWER(name))` active rows (names unique
PER OWNER; a soft-deleted row frees its name; its `UNIQUE_CONSTRAINT_DETAILS` entry names the
clash) and the `created_by`/`marked_as_deleted` indexes. PRIVATE rows visible only to their
creator, PUBLIC rows to everyone, both creator-only mutable — the lens verdict, decided in the
mutation's transaction before validation (`entityquery/SavedEntityQueryService.kt`, a
`LensService` clone). Migration checksums, including V35, are pinned in `MigrationChecksumTest`.

### Soft delete (convention)

**V25 — authentication sessions.** `users.auth_version BIGINT NOT NULL DEFAULT 0` is the
monotonic credential/identity epoch, incremented atomically by password writes (including
bootstrap rotation) and email/role changes. Cosmetic and no-op profile writes preserve it. `auth_sessions` has a UUID-string
primary key, `user_id`, the captured epoch, and `expires_at`; the user/expiry indexes support
lookup and cleanup. It is ephemeral security state, not a soft-deleted business entity:
logout deletes a family and login prunes expired families. Ordinary acceptance joins the
session to the active user in one fresh database query. Registration/renewal lock the user
before touching the session and reject a stale epoch. Cleanup uses its own transaction to
avoid reversing that lock order. Renewal updates only an existing live family and never
shortens its expiry. No runtime DDL; prior migration checksums remain untouched.

`users`, `catalog_files`, `dictionary_entries`, `labels`, `annotation_keys`, `tag_categories`, `entity_types`, `lenses`, `blueprints`, `entities`, and `entity_queries` are **soft-deleted** — rows are flagged, never physically removed; every future business entity follows the same convention (except V31's two system blueprints, schema not content). Only join/audit/detail tables (today: `password_reset_tokens` (V26, expiring single-use credentials), `auth_sessions` (V25, expiring login families deleted at logout and pruned on login), `revoked_tokens`, a pure token registry, `user_disabled_features`, a pure flag join whose PUT is a wholesale replace, `graph_layouts` (V19) and its V30 twin `entity_graph_layouts`, both pure per-user settings rows whose PUT is a wholesale replace, `catalog_file_events` (V23), the immutable audit trail itself, `telemetry_processes`/`telemetry_samples` (V46 — regenerated, high-volume observations with no reader once old: raw samples after 7 days, hourly rollups after 90, processes unseen for 90 with their samples cascading; pruned inside the telemetry cluster transaction, a row exactly at its cutoff stays — "Telemetry (V46)" below), `ontology_revision` (V39, a single row forever UPDATEd in place — a pure counter, not a business entity, with nothing to soft-delete), and `integration_clients` (V36 — NOT a hard delete at all, but the one table outside the `marked_as_deleted` convention: a terminal `revoked_at` marks the row, which is kept for administrator inspection and can never be re-enabled — justified in "Integration identities (V36)" below) — none carry history worth keeping, or ARE the history) hard-delete — a new hard-delete table needs a documented justification, exactly like Lettuce's exceptions list. To add soft-delete to a new entity, follow the established pattern (reference implementations: `users/UserService.kt`, `catalog/CatalogFileService.kt` — the latter shows the full CRUD shape incl. the delete route):

1. **Migration** — `marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE` in the CREATE (a retrofit adds the column plus `CREATE INDEX idx_<t>_marked_as_deleted ON <t>(marked_as_deleted);`).
2. **Exposed table** — add `val markedAsDeleted = bool("marked_as_deleted").default(false)` and a private helper `fun active(): Op<Boolean> = <T>.markedAsDeleted eq false`.
3. **Filter every read** — `read`, `list`, `count`, and any lookup (e.g. `findWithIdByEmail`) get `… and active()`. Apply it in the shared list predicate so the `count()` (total) and the row select stay consistent.
4. **`delete` flips the flag** — `update({ (id eq id) and (markedAsDeleted eq false) }) { it[markedAsDeleted] = true }`, returning the affected-row `Int`; guard `update` mutations the same way. The route maps `0 → 404` (the `orNotFound` helper), so a missing-or-already-deleted row is `404` (not `204`) and delete stays idempotent in effect — `CatalogFileService.delete` + its route show the full shape; `UserService.deleteGuarded` adds the last-admin check inside the same transaction.
5. **Routes need no special-casing** — they key `404`/`204`/`NoContent` off the row-count and the `active()`-filtered `read`.

**Freeing a unique business field on delete.** To let a value be reused once its holder is soft-deleted, use a **partial unique index** over active rows instead of a global `UNIQUE`: `CREATE UNIQUE INDEX uq_<t>_<col>_active ON <t>(<col>) WHERE NOT marked_as_deleted;`. Skip the Exposed `.uniqueIndex()` on that column (Exposed defs are query-only — the DB enforces it). A clash with an **active** row still raises `23505 → 409` (mapped centrally in `plugins/ErrorHandling.kt`, which names WHAT clashed per constraint — extend `UNIQUE_CONSTRAINT_DETAILS` when adding a partial unique index). In place today: `users.email` (`uq_users_email_active`, `V1`), the catalog-file identity (`uq_catalog_files_entity_active`, `V5` — an expression index over `LOWER(name)`, so identity is case-insensitive like Backstage's), the dictionary value (`uq_dictionary_entries_value_active`, `V7` — the whole-document replace soft-deletes omitted entries FIRST, so remove+re-add works in one save; the documented limitation is that swapping two values in one save trips the index → 409), the label key (`uq_labels_key_active`, `V10` — an expression index over `LOWER(key)`, so no case-twin keys), the tag-category name (`uq_tag_categories_name_active`, `V11` — same `LOWER(name)` shape; a category's TAGS are freed by soft-delete too, but through the service-side check, not an index), and the type-dictionary kind (`uq_entity_types_kind_active`, `V14` — plain `kind`, stored canonical; a soft-deleted dictionary frees its kind for a new one), and the annotation key (`uq_annotation_keys_key_active`, `V17` — the `LOWER(key)` labels shape), and the per-owner lens name (`uq_lenses_owner_name_active`, `V20` — `(created_by, LOWER(name))`, so uniqueness is scoped to the creator), its saved-entity-query twin (`uq_entity_queries_owner_name_active`, `V35` — the same shape), and the per-blueprint entity identifier (`uq_entities_blueprint_identifier_active`, `V28` — `(blueprint_id, LOWER(identifier))`, so uniqueness is scoped to the owning blueprint and a soft-deleted entity frees its identifier within it; its `UNIQUE_CONSTRAINT_DETAILS` entry names the clash). **The one deliberate FULL unique index over a soft-deletable table is `uq_users_sso_identity` (`V48`)**: a deleted user keeps its `(sso_issuer, sso_subject)` link on purpose, so deletion revokes SSO access and the identity cannot recreate the account (see "V48" above). (V9's `uq_dictionary_entries_default_active` is a partial unique index too — the at-most-one default backstop, not a freed business field — and has its own `UNIQUE_CONSTRAINT_DETAILS` entry.)

**`infra/db/Sql.kt`** (ported from Lettuce with the first list endpoint): `containsNormalized` — the case- AND accent-insensitive substring filter over `public.unaccent` (V4); every per-column substring filter MUST use it. Also `jsonArrayContains` (parameter-bound `jsonb_exists` over a JSON TEXT column — the catalog list's tag filter), `jsonStringOrArrayContainsFolded` (v1.26.0 — case-insensitive match of a scalar value against a JSON string OR array column, e.g. entity `team` filter matching the stored value exactly; used only when the column value matches the exact search term, not for substring), and `orVanished` (post-commit read-back guard → 500, used by the catalog create), and `octetLength` (2.4.0 — a `bigint`-decoded `CustomFunction` over PostgreSQL's `octet_length(col)`, which reads a TEXT value's stored byte length from its varlena header without detoasting it; a NULL column extracts to SQL NULL, read back via `ResultRow.getOrNull` and folded to `0` — backs `EntityService`'s workspace document+team byte budget and its import-planner snapshot, summed via Exposed's `Sum`). Lettuce's `requireValidReferences` (client-supplied-FK failures → 400) was dropped as unused — re-port it with the first route that takes a client-supplied foreign key.

**`infra/db/EventLog.kt` + `JsonParams.kt`** (ported from Lettuce with the first history trail, v1.15.0): the shared per-record audit-event machinery. A feature declares `object XEvents : EventLogTable("x_events", "x_id", XTable)` — an FK to the owning record, the acting `user_id`, a server-set `created_at`, `event_type VARCHAR(40)` (no CHECK — the Kotlin enum is the whitelist) and a `params TEXT` JSON `Map<String,String>` — and keeps only its typed `create`/`listFor` wrapper (`catalog/CatalogFileEventService.kt` is the first and so far only one). **Events are stored STRUCTURALLY so the SPA localizes them: no rendered string is ever stored.** Two deliberate departures from Lettuce's copy: its opt-in `commentColumn` hook (an encrypted free-text column on `goal_events`) was left behind — Toadie's events store no free text at all, recording only the FACT that a free-text field changed — and `listFor` is PAGED (`EventLogPage`), because a catalog file's event count is unbounded while Lettuce's per-record counts are intrinsically tiny (see `.claude/docs/list-endpoints.md`). Rows are IMMUTABLE: minted as a side-effect of the mutations, with no create/update/delete API.

**Atomic catalog mutations and product history.** Every catalog create, replacement, repo
sync, and soft delete appends its required structural history event inside the mutation's
own database transaction. The shared event insertion helper must use the caller's transaction;
never open another transaction or defer the event to the route. The actor is required for every
write. Failed event insertion rolls back the entire file write, including source reference,
sync baseline, timestamps, and deletion flag. A no-op replacement has no history event; a sync
always has one, and missing/deleted targets produce none. Existing structural params and
free-text redaction remain unchanged.

Replacement and sync lock the current file row before deriving the before/after diff, so
concurrent writes describe the state they actually replace. Keep these transactions short;
URL fetching stays outside them.

**Catalog optimistic concurrency (V40).**
- **Applies when:** A caller replaces, repo-syncs, or soft-deletes a catalog file.
- **Requirement:** `catalog_files.revision BIGINT NOT NULL DEFAULT 1` is returned by detail,
  list, sync-state, and graph stored-node reads. A supplied `X-Expected-Revision` is compared
  with the current row after the existing `FOR UPDATE` lock is acquired; mismatch throws the
  typed `urn:toadie:catalog-revision-conflict` `409` before changing the row or history. Every
  content/source change, every sync, and every delete increments the revision in the same
  transaction as its history event; a no-op PUT keeps it stable. The header remains optional
  for `/api/v1` compatibility, so an omitted header is an unguarded last-write-wins mutation,
  but it still increments the revision for guarded clients. Creates/imports start at revision 1.
- **Reference:** `CatalogFileService`, `CatalogFileRoutes`, `V40__catalog_file_revision.sql`.
- **Enforcement:** `CatalogFileConcurrencyTest` and `MigrationChecksumTest`.
- **Exception:** The optional header preserves released v1 clients; the SPA always supplies it
  for interactive PUT, sync, and delete flows.

Import remains report-and-skip with one transaction per document. Each successful row commits
its file and CREATED event (`origin=import`) together (a `replaceExisting` row: its file and the
ordinary UPDATED event, below). An unexpected storage/history failure
rolls back that row and returns ERROR without a fileId; other rows proceed, and earlier successful
rows remain committed. Cancellation stops further work without undoing completed rows. The
batch itself is not an all-or-nothing transaction, and sibling identities may still refer to a
row that later fails to store (the existing Errors-report residual).

**Catalog import: `replaceExisting`.**
- **Applies when:** `POST /files/import` or `/files/import/check` carries `replaceExisting: true` (default `false` — the request is then byte-identical to before), or when changing `CatalogFileImport.kt` / `CatalogFileService.update`'s `sourceWrite` handling.
- **Requirement:** an identity (kind + namespace + case-insensitive name, the namespace resolved exactly as for a create) held by an ACTIVE file is replaced IN PLACE through the ordinary `CatalogFileService.update` — its own per-document transaction with the history event inside it; never a delete-and-create — instead of `CONFLICT`. Soft findings are waived as always (hard structural rules stay hard). Rows: `UPDATED` (replaced), `UPDATED_WITH_FINDINGS` (`message` lists the waived findings), `UNCHANGED` (the replace records no field change; `message` still lists the findings the file carries; `fileId` is the file) — decided identically in both runs by the structural `documentChanges` check (plus the reference's pseudo-field). The ontology imports have no such status (their `replaceExisting` reports a no-op PUT as `UPDATED`); the catalog can say so because it suppresses no-op history events. **Source reference (the D3 rule of "Entity source references (V37)")**: a batch WITHOUT `sourceUrl` is `SourceWrite.Keep` — the reference and sync state are untouched, and an `UNCHANGED` row writes NOTHING (no event, no revision bump, no stamp change). A batch WITH one is `SourceWrite.Synced` — an import from a URL IS a repo sync, so the write follows `syncFromRepo` exactly: `updated_at`, `last_synced_at` and the `synced_content` baseline are stamped together, the revision always bumps, and a history event is always recorded (the field-diff `UPDATED` event when the document or the reference changed — the row then reads `UPDATED` — else the empty `SYNCED` event a matching sync records, the row still reading `UNCHANGED`: the document did not change, the sync is still recorded). A soft-deleted file with the same identity is invisible to the lookup (the partial unique index covers active rows only), so that document is `CREATED`. A repeat of an identity the batch already stored or replaced is `CONFLICT`, tracked per batch in the real run (a replace, unlike a create, has no 23505 to catch it) and by the dry-run's seen-set, checked before any replace prediction. Should the matched file vanish between the lookup and the locked update, an unguarded document is created. Import-replace is guarded WHEN REQUESTED (2.29.0): it never sends the `X-Expected-Revision` header (a batch has no single revision to carry), but the request's optional per-file `expected` list makes `update` compare the revision under the row lock — see "Import expectations (entities and catalog files)"; an entry-less file is unguarded, last-write-wins as before, and a concurrent editor still gets its `409` on its own guarded save. The dry-run predicts the same rows through the same `replacedRow` classification, reading only the matched file's `content`/`source_url` (one single-row read per replaced identity, never a workspace content read); its `fileId` stays null. The route audits every replaced row as `catalog_file.updated` (`import: true`, `waivedFindings` count, `withFindings` on `UPDATED_WITH_FINDINGS`) — also `UNCHANGED`, as a no-op PUT is audited.
- **Reference:** `CatalogFileImport.kt`, `CatalogFileService.update`/`activeRowOf`/`predictReplace`, `syncFromRepo` (the semantics mirrored).
- **Enforcement:** `CatalogImportReplaceTest`; the replace case of `CatalogFileAtomicHistoryTest` (a failed history insert rolls back that replace and leaves adjacent rows intact).
- **Exception:** none.

Security `audit(...)` logs stay route-side after successful service returns; they are separate
from the transactional product history and are not an atomic external-delivery guarantee.
The create response's post-commit read-back can also fail after both file and event committed;
atomic storage does not promise that every failed HTTP response means nothing was committed.
This whole-feature convention deliberately supersedes Lettuce's split mutation/event shape.

### Not yet ported from Lettuce

- **Notifications**: port Lettuce's feature when it arrives, while retaining the atomic catalog mutation/history boundary above. External notification delivery needs its own explicit consistency decision.

### Integration identities (V36)

`integration_clients` stores separate machine identities and SHA-256 key digests.
Terminal `revoked_at` is the documented removal exception to `marked_as_deleted`: rows remain
for administrator inspection and cannot be re-enabled. No ontology columns or data change.
See [integration-api.md](integration-api.md) for transaction/authentication invariants.

**Service accounts (V41).** `ALTER TABLE users ADD COLUMN service_account BOOLEAN NOT NULL
DEFAULT FALSE` plus the CHECK `ck_users_service_account_never_admin` (`NOT (service_account AND
"role" = 'ADMIN')`) and `idx_users_service_account` (2.15.0). A service account is a `users` row
that can never authenticate — no login, reset link, session, role change, or admin password set
(`.claude/docs/authorization.md` "Service accounts (V41)") — and exists only so entity writes made
by an integration client through the MCP endpoint carry an ordinary `created_by`. `UserService`
reads the management surface through `human()` (`active() AND service_account = FALSE`);
`rotatePasswordIfHashMatches` and `countActiveWithPasswordHash` keep `active()`, since they look
for the seed admin's hash, which a service account can never carry (`seedNeedsRotation` goes
through `findWithIdByEmail` and therefore `human()` — harmless, the seed admin is human). Rows
are inserted by `insertServiceAccountInTransaction` inside the integration client's own create
transaction (V42, below) and soft-deleted when that client is revoked — never hard-deleted, so
`entities.created_by` keeps joining and the entity's `creatorDeleted` becomes the honest signal.
Migration checksums, including V41, are pinned in `MigrationChecksumTest`.

**V42 — key scope and the paired service account (2.15.0).** `ALTER TABLE integration_clients ADD
COLUMN scope VARCHAR(10) NOT NULL DEFAULT 'read' CHECK (scope IN ('read','write')), ADD COLUMN
service_user_id BIGINT NULL REFERENCES users(id) ON DELETE RESTRICT, ADD CONSTRAINT
ck_integration_clients_write_needs_service_user CHECK (scope = 'read' OR service_user_id IS NOT
NULL)` plus the partial unique index `uq_integration_clients_service_user` over non-null
`service_user_id`. No backfill: pre-existing rows keep `read` and `NULL`, which the CHECK permits.
`IntegrationClientService.create` runs ONE transaction — insert the client, insert its service
account (`insertServiceAccountInTransaction`, V41), link it — and `revoke` soft-deletes the
service account in the same transaction as the terminal `revoked_at` stamp, so an entity the
client wrote keeps its `created_by` join and reports `creatorDeleted`. Migration checksums,
including V42, are pinned in `MigrationChecksumTest`.

### Entity source references (V37)

`ALTER TABLE entities ADD COLUMN source_url VARCHAR(2048) NULL, ADD COLUMN last_synced_at BIGINT
NOT NULL DEFAULT 0, ADD COLUMN synced_content TEXT NULL` — the `V21` (`catalog_files`) twin, one
level up the Port migration: the same three-column envelope (an optional https reference, an
epoch-millis "last re-synced" stamp, 0 = never, and the request-shaped JSON snapshot at sync
time), stored BESIDE `document`, never inside it, so the Port document itself stays untouched
and a future export never needs to strip anything out. `syncFromSource` (`entities/
EntityService.kt`) is an ORDINARY replace under the entity's existing V28 two-table lock — it
takes no new lock — that stamps `updated_at = last_synced_at` from one clock read, so a
successful sync always leaves `updatedAt == lastSyncedAt` byte-exact. `synced_content` is the
baseline an entity's sync diffs against: the SUBMITTED request re-encoded through
`blueprintJson` (request-shaped, null-dropped fields absent, `sourceUrl` itself never inside
it) — never the server's response shape, which may carry computed properties the stored
document does not. A changed or cleared `sourceUrl` on an ordinary PUT resets `last_synced_at`
to `0` and `synced_content` to `null` (the reference and its sync history travel together);
`updatedAt` bumps on every entity PUT REGARDLESS of whether the document changed (D2 — unlike
the catalog, which only bumps on an actual content change), so the SPA's "Local changes" badge
means "saved in Toadie since the sync", not "the document differs from the baseline" — and, since 2.29.0,
the same stamp doubles as the write-version token a conversion row reports (`existingUpdatedAt`) and an
entity import can expect (see "Import expectations (entities and catalog files)"). Import-
from-URL (a batch `sourceUrl` on `POST …/entities/import`) stamps the reference and the sync
baseline on BOTH the pass-one write and, for a row deferred through the two-pass ontology-import
protocol (`.claude/docs/port-data-model.md` "Import and export"), the pass-two restoration —
the row's FINAL baseline is always the complete, fully-resolved document, never the
pass-one-stripped intermediate one. A `replaceExisting` import batch submitted WITHOUT a
`sourceUrl` KEEPS an existing row's reference untouched (D3) — only a batch that itself carries
a URL moves or re-stamps one; a per-document `sourceUrl` inside an individual entity document is
refused `400` (`requireNoDocumentSourceUrl`) on both create and sync, since the reference is row
state for the whole request, never a document member. There is no waiver for entities: a sync
whose fetched copy fails `entityFindings` is refused `400` with the findings, exactly like an
ordinary strict save — entities have no `allowInvalid` escape hatch (unlike the catalog's
sync, which always waives). `EntitySync.kt`'s `replaceRow` resolves these three columns via the
shared, pure `infra/fetch/SourceWrite.kt` `resolveSourceColumns` (2.13.1 — extracted once
verified byte-identical to the blueprint dispatch below). Migration checksums, including V37,
are pinned in `MigrationChecksumTest`.

**Source-bound sync (entities and blueprints).** Applies when a client fetches a remote Port
document, then submits it through either `POST …/{id}/sync`. Requirement: send the stored
`sourceUrl` observed before the fetch as optional `expectedSourceUrl` alongside `document`.
The sync service compares it with the current source reference inside the existing write
transaction and lock, before domain validation or mutation. If the reference changed or was
cleared, sync answers `409` without altering the row, baseline, timestamp, cascade, or audit
trail. A missing guard retains the released `/api/v1` behavior for legacy clients
(API-VER-002); the SPA always sends the guard. Reference: `EntitySync.kt`, `BlueprintSync.kt`,
and the sync request schemas in OpenAPI.
Enforcement: `EntitySyncTest` and `BlueprintSyncTest`; frontend modal regressions. Exception:
batch imports stamp their own source reference and do not use this sync guard.

**Import expectations (entities and catalog files).**

- **Applies when:** a client imports documents it converted earlier (Backstage -> Port through
  `POST …/entity-documents` then `POST /entities/import`; Port -> Backstage through
  `GET …/catalog-documents` then `POST /files/import`) and must not overwrite an edit made
  between the read and the write.
- **Requirement:** the import request may carry `expected`, one entry per guarded document by
  `documents` index: entities `{index, updatedAt}` (the token is `entities.updated_at`, no
  migration; the conversion row's `existingUpdatedAt`), catalog files `{index, revision}`
  (`catalog_files.revision`, V40; the row's `existingRevision`), plus an optional `fileId` (the row's
  `existingId`; a `400` beside revision `0`) because a revision restarts at 1 per row — a file
  deleted and re-created, or renamed into the identity, at an equal revision is another file and
  `STALE` (the entity `updated_at` is a millisecond stamp, so a re-created entity cannot equal the
  old one and needs no id). A positive value is the counterpart's token, `0` means "expect no stored
  counterpart"; an entry-less document is unguarded exactly as
  before. The planner/`checkOne` classify from the snapshot (so the dry-run agrees), and the real
  write compares the expectation against the row read under the existing write lock (the V28
  two-table lock / the catalog `FOR UPDATE`), via `EntityService.update(expectedUpdatedAt)` /
  `CatalogFileService.update(expectedRevision)`: for entities BEFORE validation and mutation; for
  catalog files under the row lock before any mutation, after the document's own validation,
  namespace resolution and `requireOrWaive` (a guarded file whose namespace was removed meanwhile
  reads `INVALID`; nothing is written either way). The located counterpart's id is what the update
  targets, so a delete-and-recreate swap between the lookup and the lock lands on a soft-deleted row
  and reads `STALE` (vanished), never a create. A
  counterpart that changed, vanished or appeared is the per-row `STALE` status (report-and-skip
  inside the per-document transaction; nothing written: no history event, no revision bump, no
  audit event, no new problem type — `EntityStaleException` never escapes a route). With
  `replaceExisting` off an existing counterpart stays `EXISTS`/`CONFLICT`; only a vanished one
  (positive token, no row) is `STALE`, so a create never resurrects what the conversion merged
  over. A unique-violation race on a row that expected no counterpart (`E=0`) reports `STALE` in
  both imports, with `replaceExisting` on or off. A multi-pass entity import expects, in pass two, the stamp pass one left: an edit between
  the passes becomes the existing "Stored without its deferred references" `ERROR` with the committed
  id. A malformed list (index outside `0..documents-1`, a repeated index, a negative value) is a
  `400` for the whole batch on the real run and the check alike (`requireExpectedStates`, inside
  `ontologyImportPreflight` / the catalog preflight, after the batch-size cap and before the
  `sourceUrl` guards). A stale row is refused before its findings run; computed values never move
  `updated_at`, so a blueprint edit never makes an entity `STALE`; a batch `sourceUrl` composes.
- **Reference:** `infra/importing/ImportBatch.kt` (`OntologyImportStatus.STALE`,
  `requireExpectedStates`), `entities/EntityImport.kt` (`staleReason`, `writeEntityRow`,
  `pass2Entity`), `entities/EntityService.kt` (`applyUpdate`), `catalog/Import.kt`
  (`ExpectedFileState` with its optional `fileId`, `ImportResultStatus.STALE`), `catalog/CatalogFileImport.kt` (`staleReason`,
  `importOne`, `checkOne`), `catalog/CatalogFileService.kt` (`update`'s `requireExpectedRevision`,
  `activeRowOf`); `.claude/docs/conversion-mapping.md` (the tokens on the conversion rows).
- **Enforcement:** `EntityImportPlanTest` (one case per cell of the semantics table, EXISTS
  precedence, no cascade), `EntityImportTest` (match, edit in between, appeared/vanished, dry-run
  parity, malformed `expected`, the pass-two pin), `SampleImportConversionTest` (stale re-post),
  and, for catalog files, `CatalogImportReplaceTest` (match, stale with no event / revision bump /
  `catalog_file.updated` audit, the stale no-op, `0` against an active twin, a soft-deleted or
  seam-vanished counterpart never resurrected, a delete-and-recreate at an equal revision, the
  under-the-lock check via the lookup seam, duplicates after a STALE, dry-run parity, malformed
  `expected`); `EntityImportTest` also pins the under-the-lock check (a trigger moving a guarded row's
  `updated_at` after the plan).
- **Exception:** legacy clients, the sample `load.sh` scripts and the MCP import tools send no
  `expected` and behave exactly as before; blueprint imports never carry it. Residuals: a write
  landing between a conversion's snapshot and its merge read leaves the token older than the merged
  document (a spurious, safe `STALE`); the planner's snapshot can go stale before the lock (a
  predicted `UPDATED` becomes `STALE`); the write lock serialises writers but does not force distinct
  stamps, so two entity writes within the same millisecond can share an `updated_at` — a stale token
  can then pass, only for a write landing within the same millisecond as the conversion's read; a case-only identifier rename inside a deferral-bearing batch
  bumps referrers through the rename cascade, which pass two then reports as `ERROR`.

**Import dry-run preflight (files, blueprints, entities).** Applies when a `POST …/import` and its
`POST …/import/check` dry-run accept a batch-level `sourceUrl`. Requirement: both run ONE route-side
preflight, after the route's own guard and `receive` (so 401/403 win over any 400) and before any
service call: the batch-size cap, the optional `expected` list ("Import expectations", entities and
catalog files), then the `sourceUrl`'s static guards (`sanitizedSourceUrl`, the one home of those rules). An invalid value is therefore the same
`400` problem on both; a valid one changes no row prediction. The check validates and never fetches;
only the real run stamps the reference and the synced baseline. Reference:
`infra/importing/ontologyImportPreflight` (blueprints, entities), `catalogImportPreflight` in
`catalog/CatalogFileRoutes.kt`. Enforcement: `ImportSourceUrlPreflightTest`. Exception: none.

### Blueprint source references (V38)

`ALTER TABLE blueprints ADD COLUMN source_url VARCHAR(2048) NULL, ADD COLUMN last_synced_at BIGINT
NOT NULL DEFAULT 0, ADD COLUMN synced_content TEXT NULL` (2.10.0) — the V37 (`entities`) twin, one
level up: the same three-column envelope, stored BESIDE `definition`, never inside it, so the
stored Port document stays byte-identical and a future export never needs to strip anything out.
`syncFromSource` (`blueprints/BlueprintSync.kt`) is an ORDINARY replace under the blueprint's
existing V27 table lock — it takes no new lock — that stamps `updated_at = last_synced_at` from
one clock read, so a successful sync always leaves `updatedAt == lastSyncedAt` byte-exact.
`updatedAt` bumps on every blueprint PUT REGARDLESS of whether the document changed (D2 — as for
entities, unlike the catalog, which only bumps on an actual content change), so the SPA's "Local
changes" badge means "saved in Toadie since the sync", not "the document differs from the
baseline". `synced_content` is the baseline a blueprint's sync diffs against: the submitted
`BlueprintRequest` re-encoded through `blueprintJson` (request-shaped, `sourceUrl` itself never
inside it), INCLUDING the request's merged `hierarchyRelations` and `tiers` — the maps actually stored, never
whatever the remote document happened to carry. **The two deliberate departures from V37's rule**
(both Toadie-only extensions with no Port-native representation to omit-by-absence against):
when the synced request omits `hierarchyRelations`, `syncFromSource` merges in the CURRENTLY
stored map before validation rather than treating absence as "clear it" (entities' own rule) —
the Toadie-only extension has no Port-native representation to omit-by-absence against, so
syncing from a genuine Port export (which never carries `hierarchyRelations` at all) would
otherwise silently wipe it on every sync. A present, non-empty `hierarchyRelations` in the synced
request still REPLACES the stored map; clearing the map remains an ordinary editor PUT action — a
sync can never clear it. The second departure is `tiers` (2.18.0, V44): an omitted or empty
`tiers` keeps the stored ones, but PRUNED to the synced document's own property/relation keys
rather than refused (a tier is only a hint, so a remote that dropped a tiered field never fails
the sync) — see `port-data-model.md` "Tiers". A changed or cleared `sourceUrl` on an ordinary PUT resets
`last_synced_at` to `0` and `synced_content` to `null`, exactly like V37. Import-from-URL (a batch
`sourceUrl` on `POST …/blueprints/import`) stamps the reference and the sync baseline on BOTH the
pass-one write and, for a row deferred through the two-pass ontology-import protocol, the
pass-two restoration — the row's FINAL baseline is always the complete, fully-resolved document.
A `replaceExisting` import batch submitted WITHOUT a `sourceUrl` KEEPS an existing row's reference
untouched (D3, the V37 rule); a per-document `sourceUrl` inside an individual blueprint document
is refused `400` (`requireNoDocumentSourceUrl`) on both create and sync. There is no waiver for
blueprints, same as entities: a sync whose fetched copy fails validation is refused `400` with
the findings and nothing is stored — the ordinary strict-save posture, `validateSystemExtension`
included, so `_team`/`_user` are syncable exactly as they are PUTtable. Entities of a synced
blueprint are NOT re-validated at sync time — they go stale and are re-checked (and re-blocked)
on their own next save, the same as after any other blueprint PUT
(`.claude/docs/port-data-model.md` "Lifecycle rules"). `BlueprintSync.kt`'s `replaceRow` resolves
these three columns via the SAME shared `resolveSourceColumns` (2.13.1) as `EntitySync.kt`
above — `catalog/CatalogFileService.kt`'s own repo sync predates that extraction, always waives,
and stays the older, inline copy rather than a third consumer; the import's `replaceExisting` replace (`CatalogFileService.update`) selects `Keep`/`Synced` directly and, for `Synced`, mirrors that sync's writes ("Catalog import: `replaceExisting`" above).
Migration checksums, including V38, are pinned in `MigrationChecksumTest`.

### Ontology revision (V39)

`CREATE TABLE ontology_revision (id SMALLINT PRIMARY KEY CHECK (id = 1), revision BIGINT NOT NULL)`
(2.12.0) — a single row, seeded `(1, 0)`, backing the GraphQL integration API's monotonic
change-detection counter (`.claude/docs/integration-api.md` "Ontology revision"). A **hard-update
table** (a new hard-delete-adjacent exception, listed below): a pure counter, not a soft-deletable
business entity, so there is nothing to soft-delete and no history worth keeping — the row is
UPDATEd in place forever, never inserted again and never flagged. `infra/db/OntologyRevision.kt`
owns it: `bumpOntologyRevision()` (`UPDATE ontology_revision SET revision = revision + 1 WHERE id
= 1`) is called from inside the CALLER's own already-open V27/V28-locked transaction, immediately
after that transaction's row write succeeds — it takes **no lock of its own**, because every
ontology writer already serializes through the `blueprints` (V27) or `blueprints`-then-`entities`
(V28) table lock before reaching this call, so two concurrent bumps can never race. Bump sites:
`BlueprintService.create`/`applyUpdate`/`delete` (`applyUpdate` is shared by `update` and
`BlueprintSync.kt`'s `syncFromSource`, so a sync bumps through the same call), the same three on
`EntityService`, and `DictionaryService.replace`'s `HIERARCHY`-only branch (the one dictionary a
blueprint's `hierarchyRelations` can reference, already locked under V27 for its referrer check —
`NAMESPACE`/`LIFECYCLE` replaces never bump). **A blueprint/entity PUT always bumps, even a
byte-identical resubmission**: `updatedAt` already bumps on every PUT regardless of whether the
document changed (the V37/V38 "D2" rule above), and this counter follows the SAME posture — it
answers "did an ontology WRITE commit", never "did the ontology CHANGE". A rejected write (400/
409) never reaches the bump call: either the transaction never got that far, or it rolls back and
takes the bump with it. `BlueprintService.listPage` and `EntityService.list` materialize each
page in a read-only REPEATABLE READ transaction. Count, blueprint definitions, inherited-team
matches, page rows, computed-property dependencies, and revision therefore see one PostgreSQL
snapshot, including when another ontology write commits between their SELECT statements. This
is scoped to those readers; the cooperating-writer lock protocol stays READ COMMITTED.
`ontologyRevisionExpression()` remains a scalar subquery
(`(SELECT revision FROM ontology_revision WHERE id = 1)`) alongside nonempty page rows; a
zero-row page reads `currentOntologyRevision()` directly in the same transaction and thus the
same snapshot. `EntityService.errors`
(behind the GraphQL `errors` root) issues several statements to gather its subjects, so it reads
the counter as the FIRST statement in its one transaction instead of riding a row select — the
earliest snapshot the rest of the report could have seen, not a promise it also matches the
report's last statement. Migration checksums, including V39, are pinned in
`MigrationChecksumTest`.

### Telemetry (V46)

`telemetry_processes` and `telemetry_samples` (both in `public`, `SERIAL`/`INTEGER` ids, epoch-millis `BIGINT`) back the in-app
telemetry sampler (`.claude/docs/observability.md` "Telemetry"): the process writes its OWN figures — numbers and labels, never
project data. `telemetry_processes` is one row per process run, unique on `(instance, started_at)` (`instance` = `HOSTNAME`, the
pod or container name), carrying the sizing facts (`processors`, `heap_max_bytes`, `container_limit_bytes`), the optional build
identity (`build_version`/`build_commit`, from the `TOADIE_BUILD_*` image variables, null when unset) and `last_seen`, stamped every
tick. `telemetry_samples` is ONE narrow table for every metric — `(resolution, ts, process_id, metric, label, n, avg, max)` with the
primary key `(resolution, metric, ts, process_id, label)`; the metric names, kinds and units live in
`telemetry/TelemetryMetrics.kt`, so a new metric needs no migration. `resolution` is the sample width in seconds: a RAW row stores
the sampler's real interval (60..1800 and a divisor of 3600; the CHECK is `resolution BETWEEN 60 AND 3600 AND 3600 % resolution =
0`), kept 7 days; 3600 is an HOURLY rollup (`n` = the samples folded, `avg` their n-weighted mean, `max` the largest max, so a
five-minute peak survives), kept 90 days.

- **Hard deletes, justified.** Both tables are on the hard-delete exceptions list above: regenerated, high-volume observations
  with no reader once old. The prune (`pruneTelemetry`) runs inside the cluster transaction and only after a rollup that INSERTED
  hourly rows (`rollupHourly` returns false when there was nothing to fold, and a failing rollup throws before the prune, so raw rows
  are never pruned while the rollup fails): raw rows older than 7 days, hourly rows older than 90, processes whose `last_seen` is
  older than 90 days (their samples cascade, `ON DELETE CASCADE`). A row exactly at its cutoff stays.
- **Raw-tier predicates are equality lists.** Every raw-tier read — the marker lookups, the rollup source, the prune — filters
  `resolution IN (RAW_RESOLUTIONS)`, never `resolution < 3600`, so the primary key gets equality prefixes; `TelemetryStoreTest` pins
  the list against the CHECK.
- **Writes.** Parameterized multi-row `INSERT ... ON CONFLICT DO NOTHING` statements, one per power-of-two chunk, all in the
  tick's one transaction (`infra/db/MultiRowInsert.kt`, never values interpolated into the SQL): at most ~100 rows per tick, so
  98 rows go out as 64 + 32 + 2 (a duplicate keeps the first), after the tick's collectors ran. No statement carries a per-transaction `queryTimeout`; the global `postgres.statementTimeoutSeconds`
  (30 s) and the 45 s response deadline bound every one, and the tick's own `interval / 2` budget bounds the whole tick.
- **Cluster section.** One short transaction under the advisory lock ("Advisory lock (V46 telemetry)" above): it skips when the
  `telemetry.cluster_ms` marker already sits at this bucket, otherwise runs the retention (rollup, then prune) in a savepoint and
  commits the marker with it. Applied V46 is byte-immutable like every migration; its checksum is pinned in `MigrationChecksumTest`.
- **The storage collector reads the catalog and counts, never content.** The heavy phase (`telemetry/StorageCollectors.kt`, claimed
  by the `telemetry.storage_ms` marker row in the cluster transaction, run AFTER it) issues `pg_database_size`, one `pg_class`/
  `pg_namespace` query for `pg_total_relation_size` of the `public` relations and one `SELECT count(*)` per counted table (`WHERE
  NOT marked_as_deleted` for the soft-deleting ones, every row for `catalog_file_events`), each in a short transaction of its own.
  The only interpolated identifiers are the fixed `COUNTED_TABLES` names (a plain-name check guards them; the group names are map keys only); no content column is selected, so these are identity-only
  reads outside the read ledger (`security.md` ledger rule, Exception). A table added by a later migration must be assigned to a
  group (`StorageCollectorsTest`).
