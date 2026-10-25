package ch.nokillswit.infra.db

import io.r2dbc.spi.Batch
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.R2dbcTimeoutException
import io.r2dbc.spi.Readable
import io.r2dbc.spi.Result
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import io.r2dbc.spi.Statement
import io.r2dbc.spi.TransactionDefinition
import io.r2dbc.spi.ValidationDepth
import io.r2dbc.spi.Wrapped
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Predicate

private val log = LoggerFactory.getLogger("ch.nokillswit.infra.db")

/**
 * The database did not answer an exchange within `postgres.responseTimeoutSeconds`; the connection it happened on is
 * discarded (see [ResponseDeadlineConnection]). SQLSTATE `08006` (connection failure), the class the driver itself uses
 * when a conversation dies with its connection — never `57014`, which is reserved for the server's own cancel
 * (`statement_timeout`) so a log reader and `isQueryCanceled()` can tell the two apart.
 *
 * A COMMIT that times out is IN DOUBT: the frozen server may execute it when it thaws, before it reads the `Terminate`
 * the discarded connection queued, so the transaction may have committed although the caller saw this failure.
 */
internal class DatabaseResponseTimeoutException(reason: String, cause: Throwable? = null) :
    R2dbcTimeoutException(reason, SQLSTATE, cause) {
    companion object {
        const val SQLSTATE = "08006"
    }
}

/**
 * Puts a CLIENT-side response deadline on every exchange with the database (`.claude/docs/persistence.md` "Response
 * deadline: every exchange below the pool"). r2dbc-postgresql 1.1.3 has no read deadline and a frozen server's kernel
 * keeps ACKing, so a statement already on the wire when the server froze would otherwise wait until the thaw or TCP
 * gives up, holding one pool slot. Sits ABOVE [StatementTimeoutCachingConnectionFactory] and BELOW the pool; a [deadline]
 * of zero returns the inner connection unwrapped (`postgres.responseTimeoutSeconds = 0` = off).
 */
internal class ResponseDeadlineConnectionFactory(
    private val delegate: ConnectionFactory,
    private val deadline: Duration,
) : ConnectionFactory by delegate {
    override fun create(): Publisher<out Connection> =
        Mono.from(delegate.create()).map<Connection> { if (deadline.isZero) it else ResponseDeadlineConnection(it, deadline) }
}

/**
 * Bounds each exchange with [deadline]. Mono exchanges (begin, commit, rollback, savepoints, session settings,
 * `validate(REMOTE)`) fail when they do not complete in time; streams (`Statement.execute()`, `Batch.execute()` and every
 * `Result` read) fail on a GAP: no signal for [deadline] from the subscription to the first one, or between two consecutive
 * ones. `statement_timeout` is the total time of a statement and the boot invariant keeps this deadline strictly longer,
 * so on a live server the server's own 57014 always arrives first.
 *
 * A timeout POISONS the connection, once and for good: the timer cancels the exchange, and a cancelled conversation is
 * drained silently by the driver, so the protocol state is no longer known. From then on `validate` answers `false`
 * without asking the driver (the pool's release path turns that into an invalidation, whose `close()` is local and
 * returns the pool slot at once), every other exchange fails with [DatabaseResponseTimeoutException] without touching the
 * driver, and a rollback completes empty (nothing is sent to a server that is not reading; the `Terminate` the destroy
 * queues aborts the server-side transaction when it thaws). Only the first poisoner logs, one WARN on
 * `ch.nokillswit.infra.db`: the kind of exchange and the bound, never SQL or parameters. A CANCEL of an exchange (the
 * caller gave up) is not a timeout and never poisons.
 *
 * NOT bounded, by design: `close()` (local; it is the destroy path and must still run after a poison) and
 * `validate(LOCAL)` (a synchronous flag read). Hides `io.r2dbc.spi.Lifecycle` as [StatementTimeoutCachingConnection] does.
 *
 * The empty rollback of a poisoned connection is defensive. Through Exposed 1.6.0 its rollback first asks `isClosed()`
 * (`validate(LOCAL)`, then `validate(REMOTE)`), which answers true once poisoned, so the rollback is skipped; the branch
 * covers a savepoint rollback and the pool's own release rollback.
 */
internal class ResponseDeadlineConnection(
    private val delegate: Connection,
    deadline: Duration,
    timers: Scheduler = Schedulers.parallel(),
) : Connection by delegate, Wrapped<Connection> {
    // `timers` is a test seam (a counting scheduler proves every timer is disposed); production always uses the parallel one.
    private val guard = Deadline(deadline, timers)

    override fun unwrap(): Connection = delegate

    override fun beginTransaction(): Publisher<Void> = guard.exchange("transaction start") { delegate.beginTransaction() }

    override fun beginTransaction(definition: TransactionDefinition): Publisher<Void> =
        guard.exchange("transaction start") { delegate.beginTransaction(definition) }

    override fun commitTransaction(): Publisher<Void> = guard.exchange("commit") { delegate.commitTransaction() }

    override fun rollbackTransaction(): Publisher<Void> =
        guard.exchange("rollback", ifPoisoned = { Mono.empty() }) { delegate.rollbackTransaction() }

    override fun rollbackTransactionToSavepoint(name: String): Publisher<Void> =
        guard.exchange("rollback", ifPoisoned = { Mono.empty() }) { delegate.rollbackTransactionToSavepoint(name) }

    override fun createSavepoint(name: String): Publisher<Void> = guard.exchange("savepoint") { delegate.createSavepoint(name) }

    override fun releaseSavepoint(name: String): Publisher<Void> = guard.exchange("savepoint") { delegate.releaseSavepoint(name) }

    override fun setAutoCommit(autoCommit: Boolean): Publisher<Void> =
        guard.exchange("session setting") { delegate.setAutoCommit(autoCommit) }

    override fun setLockWaitTimeout(timeout: Duration): Publisher<Void> =
        guard.exchange("session setting") { delegate.setLockWaitTimeout(timeout) }

    override fun setStatementTimeout(timeout: Duration): Publisher<Void> =
        guard.exchange("session setting") { delegate.setStatementTimeout(timeout) }

    override fun setTransactionIsolationLevel(isolationLevel: IsolationLevel): Publisher<Void> =
        guard.exchange("session setting") { delegate.setTransactionIsolationLevel(isolationLevel) }

    override fun validate(depth: ValidationDepth): Publisher<Boolean> =
        if (depth == ValidationDepth.LOCAL) {
            Mono.defer { if (guard.isPoisoned) Mono.just(false) else Mono.from(delegate.validate(depth)) }
        } else {
            guard.exchange("validation", ifPoisoned = { Mono.just(false) }) { delegate.validate(depth) }
        }

    override fun createStatement(sql: String): Statement = DeadlineStatement(delegate.createStatement(sql), guard)

    override fun createBatch(): Batch = DeadlineBatch(delegate.createBatch(), guard)
}

/** The poison flag and the timer plumbing one [ResponseDeadlineConnection] shares with the statements, batches and results it hands out. */
private class Deadline(private val bound: Duration, private val timers: Scheduler) {
    private val poisoned = AtomicReference<DatabaseResponseTimeoutException?>(null)

    val isPoisoned: Boolean get() = poisoned.get() != null

    private fun refusal(first: DatabaseResponseTimeoutException) =
        DatabaseResponseTimeoutException("the connection was discarded after a database timeout and is not used again", first)

    // Only the compareAndSet winner logs: one WARN per discarded connection, however many exchanges were in flight.
    private fun poison(kind: String): DatabaseResponseTimeoutException {
        val timeout = DatabaseResponseTimeoutException(
            "the database did not answer a $kind within ${describe(bound)}; the connection is discarded",
        )
        if (poisoned.compareAndSet(null, timeout)) log.warn(timeout.message)
        return timeout
    }

    private fun describe(duration: Duration): String =
        if (duration.toMillis() % 1000 == 0L) "${duration.seconds} s" else "${duration.toMillis()} ms"

    /**
     * One request/response exchange. The fallback runs AFTER reactor cancelled the exchange, so the flag is set before the
     * caller sees the error. [ifPoisoned] answers an exchange started after the poison (default: refuse it).
     */
    fun <T : Any> exchange(
        kind: String,
        ifPoisoned: (DatabaseResponseTimeoutException) -> Mono<T> = { Mono.error(refusal(it)) },
        send: () -> Publisher<T>,
    ): Mono<T> = Mono.defer {
        val first = poisoned.get()
        if (first != null) {
            ifPoisoned(first)
        } else {
            Mono.from(send()).timeout(bound, Mono.defer<T> { Mono.error(poison(kind)) }, timers)
        }
    }

    /** A stream of signals bounded per GAP (subscription to first signal, then between consecutive ones). */
    fun <T : Any> stream(source: () -> Publisher<out T>): Flux<T> = Flux.defer {
        val first = poisoned.get()
        if (first != null) {
            Flux.error(refusal(first))
        } else {
            Flux.from(source()).timeout(bound, Flux.defer<T> { Flux.error(poison("statement")) }, timers)
        }
    }

    fun results(source: () -> Publisher<out Result>): Flux<Result> =
        stream(source).map<Result> { DeadlineResult(it, this) }
}

/**
 * Every fluent method returns THIS wrapper: Kotlin delegation would return the inner statement and let `execute()` escape
 * the deadline. Written without `by` so a new SPI method breaks the build instead of silently bypassing the wrapper.
 */
private class DeadlineStatement(private val delegate: Statement, private val guard: Deadline) : Statement {
    override fun add(): Statement = also { delegate.add() }

    override fun bind(index: Int, value: Any): Statement = also { delegate.bind(index, value) }

    override fun bind(name: String, value: Any): Statement = also { delegate.bind(name, value) }

    override fun bindNull(index: Int, type: Class<*>): Statement = also { delegate.bindNull(index, type) }

    override fun bindNull(name: String, type: Class<*>): Statement = also { delegate.bindNull(name, type) }

    override fun returnGeneratedValues(vararg columns: String): Statement = also { delegate.returnGeneratedValues(*columns) }

    override fun fetchSize(rows: Int): Statement = also { delegate.fetchSize(rows) }

    override fun execute(): Publisher<out Result> = guard.results { delegate.execute() }
}

private class DeadlineBatch(private val delegate: Batch, private val guard: Deadline) : Batch {
    override fun add(sql: String): Batch = also { delegate.add(sql) }

    override fun execute(): Publisher<out Result> = guard.results { delegate.execute() }
}

/** A [Result] whose every read is gap-bounded and whose `filter` stays bounded. */
private class DeadlineResult(private val delegate: Result, private val guard: Deadline) : Result {
    override fun getRowsUpdated(): Publisher<Long> = guard.stream { delegate.rowsUpdated }

    override fun <T : Any> map(mappingFunction: BiFunction<Row, RowMetadata, out T>): Publisher<T> =
        guard.stream { delegate.map(mappingFunction) }

    override fun <T : Any> map(mappingFunction: Function<in Readable, out T>): Publisher<T> =
        guard.stream { delegate.map(mappingFunction) }

    override fun filter(predicate: Predicate<Result.Segment>): Result = DeadlineResult(delegate.filter(predicate), guard)

    override fun <T : Any> flatMap(mappingFunction: Function<Result.Segment, out Publisher<out T>>): Publisher<T> =
        guard.stream { delegate.flatMap(mappingFunction) }
}
