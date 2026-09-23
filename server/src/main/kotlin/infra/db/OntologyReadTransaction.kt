package ch.nokillswit.infra.db

import io.r2dbc.spi.IsolationLevel
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Runs a multi-statement ontology read against one PostgreSQL snapshot.
 *
 * List materialization reads related blueprint/entity rows, totals, target rows, and the
 * ontology revision in separate statements. REPEATABLE READ keeps those statements on the
 * snapshot established by the transaction's first query, while read-only mode prevents this
 * helper from accidentally becoming another writer path.
 */
suspend fun <T> ontologyReadTransaction(
    database: R2dbcDatabase,
    block: suspend R2dbcTransaction.() -> T,
): T = suspendTransaction(
    db = database,
    transactionIsolation = IsolationLevel.REPEATABLE_READ,
    readOnly = true,
    statement = block,
)
