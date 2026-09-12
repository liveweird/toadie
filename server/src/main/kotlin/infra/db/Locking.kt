package ch.nokillswit.infra.db

import io.r2dbc.spi.IsolationLevel
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Runs [block] inside a READ COMMITTED transaction after executing every one of
 * [lockStatements], in the order given — the cooperating-writer table-lock protocol shared by
 * `BlueprintService` (V27), `TagCategoryService` (V11), and `EntityService` (V28); see
 * `.claude/docs/persistence.md`. READ COMMITTED is explicit so a writer that waited on a lock
 * sees the prior writer's committed rows before deciding.
 *
 * **Order matters.** When a caller passes more than one lock statement it MUST list them in the
 * fixed global order its own deadlock-free protocol requires — e.g. `blueprints` before
 * `entities` (V27 before V28, "Entity targets under concurrency (V28)" in
 * `.claude/docs/persistence.md`). This helper only executes the statements in the order it
 * receives them; it does not choose, validate, or reorder that order itself.
 */
suspend fun <T> lockingTransaction(
    database: R2dbcDatabase,
    vararg lockStatements: String,
    block: suspend R2dbcTransaction.() -> T,
): T = suspendTransaction(database, transactionIsolation = IsolationLevel.READ_COMMITTED) {
    lockStatements.forEach { exec(it) }
    block()
}
