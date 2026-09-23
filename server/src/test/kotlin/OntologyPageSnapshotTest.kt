package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.BlueprintService
import ch.nokillswit.entities.EntityFilter
import ch.nokillswit.infra.db.currentOntologyRevision
import ch.nokillswit.infra.db.ontologyReadTransaction
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll

/**
 * Regression coverage for the multi-statement ontology page snapshot. A blueprint commit lands
 * after the reader's first SELECT and before its later row/revision SELECTs. READ COMMITTED would
 * expose that commit to the later statements; [ontologyReadTransaction]'s REPEATABLE READ must
 * retain the snapshot established by the first query.
 */
class OntologyPageSnapshotTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun simpleBlueprint(identifier: String) =
        BlueprintRequest(identifier = identifier, title = "Snapshot", schema = BlueprintSchema())

    private fun page() = PageRequest(1, 100, listOf(SortField("id", descending = true)))

    @Test
    fun `a commit between ontology page statements stays outside the reader snapshot`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ontology-page-snapshot", UserRole.ADMIN)
        val identifier = unique("snapshot-bp")
        val database = R2dbcDatabase.connect(
            url = PostgresTestSupport.r2dbcUrl,
            user = PostgresTestSupport.user,
            password = PostgresTestSupport.password,
        )
        val firstSelectCompleted = CompletableDeferred<Unit>()
        val writerCommitted = CompletableDeferred<Unit>()

        try {
            coroutineScope {
                val snapshot = async {
                    ontologyReadTransaction(database) {
                        val initialCount = matchingBlueprintCount(identifier)
                        val initialRevision = currentOntologyRevision()
                        firstSelectCompleted.complete(Unit)
                        writerCommitted.await()
                        Snapshot(
                            initialCount = initialCount,
                            laterCount = matchingBlueprintCount(identifier),
                            initialRevision = initialRevision,
                            laterRevision = currentOntologyRevision(),
                        )
                    }
                }

                withTimeout(10_000) { firstSelectCompleted.await() }
                val create = admin.postJson(
                    "/api/v1/blueprints",
                    simpleBlueprint(identifier),
                )
                assertEquals(HttpStatusCode.Created, create.status)
                writerCommitted.complete(Unit)

                val observed = withTimeout(10_000) { snapshot.await() }
                assertEquals(0L, observed.initialCount)
                assertEquals(0L, observed.laterCount)
                assertEquals(observed.initialRevision, observed.laterRevision)
            }

            assertEquals(1L, matchingBlueprintCountAfterCommit(database, identifier))

            val currentRevision = TestOntologyRevision.current()
            val blueprintPage = TestBlueprints.service.listPage(page())
            assertEquals(currentRevision, blueprintPage.revision)
            assertEquals(identifier, blueprintPage.items.single { it.identifier == identifier }.identifier)

            val entityPage = TestEntities.service.list(EntityFilter(blueprint = identifier, q = null), page())
            assertEquals(currentRevision, entityPage.revision)
            assertEquals(0L, entityPage.total)
        } finally {
            writerCommitted.complete(Unit)
            TestBlueprints.remove(identifier)
        }
    }

    private data class Snapshot(
        val initialCount: Long,
        val laterCount: Long,
        val initialRevision: Long,
        val laterRevision: Long,
    )

    private suspend fun matchingBlueprintCount(identifier: String): Long =
        BlueprintService.Blueprints.selectAll()
            .where {
                (BlueprintService.Blueprints.identifier eq identifier) and
                    (BlueprintService.Blueprints.markedAsDeleted eq false)
            }
            .count()

    private suspend fun matchingBlueprintCountAfterCommit(database: R2dbcDatabase, identifier: String): Long =
        ontologyReadTransaction(database) { matchingBlueprintCount(identifier) }
}
