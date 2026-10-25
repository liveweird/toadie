package ch.nokillswit.infra.db

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Wrapped
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Skips the driver's `SET STATEMENT_TIMEOUT` round trip when the PHYSICAL connection already has the requested value
 * (`.claude/docs/persistence.md` "Statement timeouts: the per-connection cache"; ported from Flow's
 * `infra/db/StatementTimeoutCache.kt`).
 *
 * Exposed's R2DBC executor calls `setTimeout(transaction.queryTimeout)` before EVERY statement, and r2dbc-postgresql
 * answers each call with a separate simple query that Exposed awaits: measured on a typical authenticated read, about a
 * fifth to a quarter of all the statements a request sends. This factory wraps the RAW factory, i.e. sits BELOW the pool,
 * so the remembered value belongs to one backend and survives pool borrows. No reset on release is needed: Exposed states
 * its value before every statement, so a borrower with another value simply differs from the remembered one and is sent.
 *
 * The one trap: PostgreSQL reverts a session-level `SET` issued inside a transaction when that transaction rolls back
 * (or rolls back to an earlier savepoint) — and a statement cancelled by the timeout aborts its transaction. The
 * remembered value is therefore forgotten on every rollback, on a failed commit and when autocommit is switched back on
 * (r2dbc-postgresql commits, or for an aborted transaction rolls back, there); it is forgotten BEFORE a SET goes out and
 * remembered only when it completed, so a SET the server applied but whose reply was cancelled or failed is never
 * remembered as the old value. It is kept across a successful commit, which is where the cross-transaction gain is.
 *
 * Correct only while nothing else changes the setting behind the cache: only the transaction's `queryTimeout` (Exposed's
 * `defaultQueryTimeout`, `Database.kt`) may. Neither r2dbc-pool 1.0.2 nor r2dbc-postgresql 1.1.3 ever issues a `RESET` or
 * `DISCARD` on a connection (checked from the bytecode), and nothing in `server/src/main` sets the timeout itself.
 * The decorator hides `io.r2dbc.spi.Lifecycle` (postAllocate/preRelease) from `PooledConnection` should a future driver
 * implement it: re-check this class on every driver or pool upgrade.
 */
internal class StatementTimeoutCachingConnectionFactory(
    private val delegate: ConnectionFactory,
) : ConnectionFactory by delegate {
    override fun create(): Publisher<out Connection> =
        Mono.from(delegate.create()).map { StatementTimeoutCachingConnection(it) }
}

internal class StatementTimeoutCachingConnection(
    private val delegate: Connection,
) : Connection by delegate, Wrapped<Connection> {
    // One transaction at a time uses a connection; volatile because the pool hands it between threads.
    @Volatile
    private var known: Duration? = null

    // Bumped by every forget(): a SET records its value only if no rollback/failed commit/autocommit happened since it started,
    // so a SET whose reply was already past a cancel check can never re-record a value a rollback just reverted.
    private val generation = AtomicLong()

    private fun forget() {
        generation.incrementAndGet()
        known = null
    }

    override fun unwrap(): Connection = delegate

    override fun setStatementTimeout(timeout: Duration): Publisher<Void> {
        // Decided at subscription, not at assembly: Exposed may build the publisher ahead of a rollback that forgets the value.
        return Mono.defer {
            if (known == timeout) {
                Mono.empty()
            } else {
                val startedUnder = generation.get()
                known = null
                Mono.from(delegate.setStatementTimeout(timeout)).doOnSuccess {
                    if (generation.get() == startedUnder) known = timeout
                }
            }
        }
    }

    override fun setAutoCommit(autoCommit: Boolean): Publisher<Void> {
        if (autoCommit) forget()
        return delegate.setAutoCommit(autoCommit)
    }

    // A commit that failed or was cancelled leaves the server's state unknown (a failed one rolls the transaction back).
    override fun commitTransaction(): Publisher<Void> =
        Mono.from(delegate.commitTransaction()).doOnError { forget() }.doOnCancel { forget() }

    override fun rollbackTransaction(): Publisher<Void> {
        forget()
        return delegate.rollbackTransaction()
    }

    override fun rollbackTransactionToSavepoint(name: String): Publisher<Void> {
        forget()
        return delegate.rollbackTransactionToSavepoint(name)
    }
}
