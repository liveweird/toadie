package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.entities.EntityImportRequest
import ch.nokillswit.entities.EntityImportResponse
import ch.nokillswit.entities.EntityInvalidProblem
import ch.nokillswit.entities.EntityPageResponse
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.entities.EntitySyncStateResponse
import ch.nokillswit.entities.SyncEntityRequest
import ch.nokillswit.infra.fetch.FetchUrlRequest
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Entity source references & HTTP re-sync (2.9.0, V37) — the `SyncTest.kt` case list, one level
 * down: `sourceUrl` on create/replace, the sync-state read, the strict remote->DB sync (NO
 * waiver exists for entities, unlike the catalog), the `lastSyncedAt` sort field, and the
 * import-as-sync path. Every test mints unique `bp-`/`ent-<uuid8>` identifiers and cleans up via
 * [TestEntities]/[TestBlueprints].
 */
class EntitySyncTest {

    /** Holds entity writers so a retarget can be queued ahead of a guarded sync deterministically. */
    private class EntityWriteBarrier private constructor(
        private val holder: Connection,
        private val observer: Connection,
    ) {
        private var released = false

        suspend fun awaitWriters(count: Int) {
            withTimeout(15_000) {
                while (waitingWriters() != count) delay(25)
            }
        }

        private suspend fun waitingWriters(): Int = withContext(Dispatchers.IO) {
            observer.prepareStatement(
                """
                SELECT COUNT(*)
                FROM pg_locks
                WHERE relation = 'entities'::regclass
                  AND mode = 'ShareRowExclusiveLock'
                  AND NOT granted
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }

        suspend fun release() {
            if (released) return
            released = true
            withContext(NonCancellable + Dispatchers.IO) {
                var committed = false
                try {
                    holder.commit()
                    committed = true
                } finally {
                    if (!committed) runCatching { holder.rollback() }
                    try {
                        holder.close()
                    } finally {
                        observer.close()
                    }
                }
            }
        }

        companion object {
            suspend fun acquire(): EntityWriteBarrier = withContext(NonCancellable + Dispatchers.IO) {
                var holder: Connection? = null
                var observer: Connection? = null
                try {
                    holder = DriverManager.getConnection(
                        PostgresTestSupport.jdbcUrl,
                        PostgresTestSupport.user,
                        PostgresTestSupport.password,
                    )
                    observer = DriverManager.getConnection(
                        PostgresTestSupport.jdbcUrl,
                        PostgresTestSupport.user,
                        PostgresTestSupport.password,
                    )
                    holder.autoCommit = false
                    holder.createStatement().use { it.execute("LOCK TABLE entities IN SHARE MODE") }
                    EntityWriteBarrier(holder, observer)
                } catch (failure: Exception) {
                    runCatching { holder?.close() }
                    runCatching { observer?.close() }
                    throw failure
                }
            }
        }
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun sourceUrl(marker: String) = "https://example.com/$marker/entity.json"

    private suspend fun HttpClient.createBlueprint(request: BlueprintRequest) =
        postJson("/api/v1/blueprints", request).body<BlueprintResponse>()

    private fun simpleBlueprint(id: String) = BlueprintRequest(identifier = id, title = "T", schema = BlueprintSchema())

    private fun requiredPropBlueprint(id: String) = BlueprintRequest(
        identifier = id,
        title = "T",
        schema = BlueprintSchema(
            properties = mapOf("language" to PropertyDefinition(type = "string", title = "Language")),
            required = listOf("language"),
        ),
    )

    private fun entityRequest(blueprint: String, identifier: String, sourceUrl: String? = null) =
        EntityRequest(blueprint = blueprint, identifier = identifier, title = "Title $identifier", sourceUrl = sourceUrl)

    @Test
    fun `a create with a sourceUrl stores the reference but starts unsynced`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-create", UserRole.ADMIN)
        val bpId = unique("bp-esync-create")
        val entId = unique("ent-esync-create")
        val url = sourceUrl(entId)
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            val created = client.postJson("/api/v1/entities", entityRequest(bpId, entId, url)).body<EntityResponse>()
            assertEquals(url, created.sourceUrl)
            assertEquals(0L, created.lastSyncedAt)

            val state = client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>()
            assertEquals(url, state.sourceUrl)
            assertEquals(0L, state.lastSyncedAt)
            assertNull(state.syncedDocument)

            val row = client.get("/api/v1/entities?q=$entId").body<EntityPageResponse>().items.single()
            assertEquals(url, row.sourceUrl)
            assertEquals(0L, row.lastSyncedAt)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a sync overwrites the document and stamps the sync state, and audits it`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-stamp", UserRole.ADMIN)
        val bpId = unique("bp-esync-stamp")
        val entId = unique("ent-esync-stamp")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            val created = client.postJson("/api/v1/entities", entityRequest(bpId, entId, sourceUrl(entId))).body<EntityResponse>()

            withAuditCapture { capture ->
                val remoteCopy = entityRequest(bpId, entId).copy(title = "From remote")
                val synced = client.postJson("/api/v1/entities/${created.id}/sync", SyncEntityRequest(document = remoteCopy))
                assertEquals(HttpStatusCode.NoContent, synced.status)
                val event = capture.events.firstOrNull { it.message == "entity.synced" }
                assertNotNull(event)
                assertTrue(event.hasKeyValue("entityId", created.id.toLong()))
            }

            val after = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
            assertEquals("From remote", after.title)
            assertTrue(after.lastSyncedAt > 0)
            // The load-bearing stamp: sync sets both equal, so "DB changed" is updatedAt > lastSyncedAt.
            assertEquals(after.lastSyncedAt, after.updatedAt)

            val state = client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>()
            val syncedDocument = assertNotNull(state.syncedDocument)
            assertEquals("From remote", syncedDocument.title)
            // The baseline is request-shaped and drops sourceUrl (the envelope reference never
            // rides inside the compared document).
            assertNull(syncedDocument.sourceUrl)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `source guard rejects changed and cleared references without mutation or audit, while matching and omitted guards sync`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("esync-source-guard", UserRole.ADMIN)
            val bpId = unique("bp-esync-source-guard")
            val entId = unique("ent-esync-source-guard")
            val originalUrl = sourceUrl(entId)
            val movedUrl = sourceUrl(unique("moved"))
            try {
                client.createBlueprint(simpleBlueprint(bpId))
                val created = client.postJson(
                    "/api/v1/entities",
                    entityRequest(bpId, entId, originalUrl),
                ).body<EntityResponse>()

                // API-VER-002: omission remains a successful legacy sync.
                assertEquals(
                    HttpStatusCode.NoContent,
                    client.postJson(
                        "/api/v1/entities/${created.id}/sync",
                        SyncEntityRequest(document = entityRequest(bpId, entId).copy(title = "Legacy")),
                    ).status,
                )

                client.putJson(
                    "/api/v1/entities/${created.id}",
                    entityRequest(bpId, entId, movedUrl).copy(title = "Moved locally"),
                )
                val beforeChangedConflict = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
                val beforeChangedState = client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>()
                withAuditCapture { capture ->
                    val stale = client.postJson(
                        "/api/v1/entities/${created.id}/sync",
                        SyncEntityRequest(
                            document = entityRequest(bpId, entId).copy(title = "Stale remote"),
                            expectedSourceUrl = originalUrl,
                        ),
                    )
                    assertEquals(HttpStatusCode.Conflict, stale.status)
                    assertEquals("urn:toadie:source-reference-conflict", stale.body<ProblemDetail>().type)
                    assertTrue(capture.events.none { it.message == "entity.synced" })
                }
                assertEquals(beforeChangedConflict, client.get("/api/v1/entities/${created.id}").body<EntityResponse>())
                assertEquals(
                    beforeChangedState,
                    client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>(),
                )

                // A guard matching the locked row succeeds and stamps that exact reference.
                assertEquals(
                    HttpStatusCode.NoContent,
                    client.postJson(
                        "/api/v1/entities/${created.id}/sync",
                        SyncEntityRequest(
                            document = entityRequest(bpId, entId).copy(title = "Current remote"),
                            expectedSourceUrl = movedUrl,
                        ),
                    ).status,
                )
                assertEquals("Current remote", client.get("/api/v1/entities/${created.id}").body<EntityResponse>().title)

                client.putJson("/api/v1/entities/${created.id}", entityRequest(bpId, entId).copy(title = "Cleared locally"))
                val beforeClearedConflict = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
                val beforeClearedState = client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>()
                val staleAfterClear = client.postJson(
                    "/api/v1/entities/${created.id}/sync",
                    SyncEntityRequest(
                        document = entityRequest(bpId, entId).copy(title = "Old remote after clear"),
                        expectedSourceUrl = movedUrl,
                    ),
                )
                assertEquals(HttpStatusCode.Conflict, staleAfterClear.status)
                assertEquals(beforeClearedConflict, client.get("/api/v1/entities/${created.id}").body<EntityResponse>())
                assertEquals(
                    beforeClearedState,
                    client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>(),
                )
            } finally {
                TestEntities.remove(entId)
                TestBlueprints.remove(bpId)
            }
        }

    @Test
    fun `source guard validates after authentication and entity lookup`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-source-guard-order", UserRole.ADMIN)
        val user = seededClient("esync-source-guard-order-user", UserRole.USER)
        val bpId = unique("bp-esync-source-guard-order")
        val entId = unique("ent-esync-source-guard-order")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            val created = client.postJson(
                "/api/v1/entities",
                entityRequest(bpId, entId, sourceUrl(entId)),
            ).body<EntityResponse>()
            val invalidExpectedUrls = listOf(
                " ",
                "http://example.com/entity.json",
                "https://example.com/${"x".repeat(2048)}",
            )
            invalidExpectedUrls.forEach { invalid ->
                val body = SyncEntityRequest(document = entityRequest(bpId, entId), expectedSourceUrl = invalid)
                assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/entities/${created.id}/sync", body).status)
            }
            val body = SyncEntityRequest(document = entityRequest(bpId, entId), expectedSourceUrl = " ")
            assertEquals(HttpStatusCode.NotFound, client.postJson("/api/v1/entities/999999999/sync", body).status)
            // Entities are a shared workspace: an authenticated USER reaches the same guarded write.
            assertEquals(HttpStatusCode.BadRequest, user.postJson("/api/v1/entities/${created.id}/sync", body).status)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `source guard compares after a queued retarget commits`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-source-guard-race", UserRole.ADMIN)
        val bpId = unique("bp-esync-source-guard-race")
        val entId = unique("ent-esync-source-guard-race")
        val originalUrl = sourceUrl(entId)
        val movedUrl = sourceUrl(unique("moved"))
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            val created = client.postJson(
                "/api/v1/entities",
                entityRequest(bpId, entId, originalUrl),
            ).body<EntityResponse>()

            coroutineScope {
                val barrier = EntityWriteBarrier.acquire()
                val retarget = async {
                    client.putJson(
                        "/api/v1/entities/${created.id}",
                        entityRequest(bpId, entId, movedUrl).copy(title = "Retargeted"),
                    )
                }
                try {
                    // Queue the retarget first, then the sync carrying the source it fetched.
                    barrier.awaitWriters(1)
                    val sync = async {
                        client.postJson(
                            "/api/v1/entities/${created.id}/sync",
                            SyncEntityRequest(
                                document = entityRequest(bpId, entId).copy(title = "Old remote"),
                                expectedSourceUrl = originalUrl,
                            ),
                        )
                    }
                    barrier.awaitWriters(2)
                    barrier.release()
                    assertEquals(HttpStatusCode.NoContent, withTimeout(15_000) { retarget.await() }.status)
                    val stale = withTimeout(15_000) { sync.await() }
                    assertEquals(HttpStatusCode.Conflict, stale.status)
                    assertEquals("urn:toadie:source-reference-conflict", stale.body<ProblemDetail>().type)
                } finally {
                    barrier.release()
                }
            }

            val after = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
            assertEquals("Retargeted", after.title)
            assertEquals(movedUrl, after.sourceUrl)
            assertEquals(0L, after.lastSyncedAt)
            assertNull(client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>().syncedDocument)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a sync on an entity without a source reference is a 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-nosrc", UserRole.ADMIN)
        val bpId = unique("bp-esync-nosrc")
        val entId = unique("ent-esync-nosrc")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            val created = client.postJson("/api/v1/entities", entityRequest(bpId, entId)).body<EntityResponse>()

            val state = client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>()
            assertEquals(null, state.sourceUrl)
            assertEquals(0, state.lastSyncedAt)
            assertNull(state.syncedDocument)

            val response = client.postJson("/api/v1/entities/${created.id}/sync", SyncEntityRequest(document = entityRequest(bpId, entId)))
            assertEquals(HttpStatusCode.BadRequest, response.status)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a findings-failing sync is refused outright - no waiver exists for entities`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-findings", UserRole.ADMIN)
        val bpId = unique("bp-esync-findings")
        val entId = unique("ent-esync-findings")
        try {
            client.createBlueprint(requiredPropBlueprint(bpId))
            val created = client.postJson(
                "/api/v1/entities",
                entityRequest(bpId, entId, sourceUrl(entId)).copy(properties = buildJsonObject { put("language", "kotlin") }),
            ).body<EntityResponse>()
            val before = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()

            // The remote copy is missing the required "language" property.
            val badRemote = entityRequest(bpId, entId)
            val response = client.postJson("/api/v1/entities/${created.id}/sync", SyncEntityRequest(document = badRemote))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.body<EntityInvalidProblem>().findings.isNotEmpty())

            // The row is untouched — the FULL response, not just properties/lastSyncedAt: title,
            // identifier, sourceUrl, properties, relations, updatedAt, lastSyncedAt all included.
            val after = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
            assertEquals(before, after)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `PUT changing or clearing the source reference resets the sync state, unchanged keeps it`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-reset", UserRole.ADMIN)
        val bpId = unique("bp-esync-reset")
        val entId = unique("ent-esync-reset")
        val url = sourceUrl(entId)
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            val created = client.postJson("/api/v1/entities", entityRequest(bpId, entId, url)).body<EntityResponse>()
            client.postJson("/api/v1/entities/${created.id}/sync", SyncEntityRequest(document = entityRequest(bpId, entId)))
            val synced = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
            assertTrue(synced.lastSyncedAt > 0)

            // An unchanged reference on an ordinary PUT keeps the stamp.
            client.putJson("/api/v1/entities/${created.id}", entityRequest(bpId, entId, url))
            val kept = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
            assertEquals(synced.lastSyncedAt, kept.lastSyncedAt)
            // D2: an entity PUT bumps updatedAt regardless of whether the document changed —
            // unlike the catalog, which only bumps on an actual content change (`SyncTest.kt`).
            assertTrue(kept.updatedAt > kept.lastSyncedAt)

            // A changed reference resets the stamp and drops the baseline.
            val other = sourceUrl(unique("moved"))
            client.putJson("/api/v1/entities/${created.id}", entityRequest(bpId, entId, other))
            val moved = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
            assertEquals(other, moved.sourceUrl)
            assertEquals(0L, moved.lastSyncedAt)
            assertNull(client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>().syncedDocument)

            // Full-replace semantics: an omitted sourceUrl clears the reference.
            client.putJson("/api/v1/entities/${created.id}", entityRequest(bpId, entId))
            val cleared = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
            assertNull(cleared.sourceUrl)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `an import with a batch sourceUrl stores the rows synced, dry-run parity holds, a per-document sourceUrl is INVALID`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("esync-import", UserRole.ADMIN)
            val bpId = unique("bp-esync-import")
            val entId = unique("ent-esync-import")
            val url = sourceUrl(entId)
            try {
                client.createBlueprint(simpleBlueprint(bpId))
                val doc = blueprintJson.encodeToJsonElement(entityRequest(bpId, entId)).jsonObject

                val check = client.postJson(
                    "/api/v1/entities/import/check",
                    EntityImportRequest(documents = listOf(doc), sourceUrl = url),
                ).body<EntityImportResponse>().results.single()
                assertEquals(OntologyImportStatus.CREATED, check.status)

                val real = client.postJson(
                    "/api/v1/entities/import",
                    EntityImportRequest(documents = listOf(doc), sourceUrl = url),
                ).body<EntityImportResponse>().results.single()
                assertEquals(OntologyImportStatus.CREATED, real.status)

                val stored = client.get("/api/v1/entities/${real.id}").body<EntityResponse>()
                assertEquals(url, stored.sourceUrl)
                assertTrue(stored.lastSyncedAt > 0)
                assertEquals(stored.updatedAt, stored.lastSyncedAt)
                val state = client.get("/api/v1/entities/${real.id}/sync").body<EntitySyncStateResponse>()
                assertEquals(entId, state.syncedDocument!!.identifier)

                // A per-document sourceUrl is INVALID — row state belongs to the whole request.
                val badId = unique("ent-esync-baddoc")
                val badDoc = blueprintJson.encodeToJsonElement(entityRequest(bpId, badId, url)).jsonObject
                val badResult = client.postJson("/api/v1/entities/import", EntityImportRequest(documents = listOf(badDoc)))
                    .body<EntityImportResponse>().results.single()
                assertEquals(OntologyImportStatus.INVALID, badResult.status)
            } finally {
                TestEntities.remove(entId)
                TestBlueprints.remove(bpId)
            }
        }

    @Test
    fun `a replaceExisting import without a batch sourceUrl keeps the row's existing reference and stamp - D3 Keep`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("esync-keep", UserRole.ADMIN)
            val bpId = unique("bp-esync-keep")
            val entId = unique("ent-esync-keep")
            val originalUrl = sourceUrl(entId)
            try {
                client.createBlueprint(simpleBlueprint(bpId))
                val created = client.postJson("/api/v1/entities", entityRequest(bpId, entId, originalUrl)).body<EntityResponse>()
                // Sync it once so there is a real baseline to prove D3 leaves untouched, not just nulls.
                client.postJson("/api/v1/entities/${created.id}/sync", SyncEntityRequest(document = entityRequest(bpId, entId)))
                val synced = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
                assertTrue(synced.lastSyncedAt > 0)
                val syncedState = client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>()

                // D3: replaceExisting WITHOUT a batch sourceUrl keeps the existing reference/stamp/baseline.
                val keepDoc = blueprintJson.encodeToJsonElement(entityRequest(bpId, entId).copy(title = "Changed via import")).jsonObject
                val kept = client.postJson(
                    "/api/v1/entities/import",
                    EntityImportRequest(documents = listOf(keepDoc), replaceExisting = true),
                ).body<EntityImportResponse>().results.single()
                assertEquals(OntologyImportStatus.UPDATED, kept.status)
                val afterKeep = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
                assertEquals("Changed via import", afterKeep.title)
                assertEquals(originalUrl, afterKeep.sourceUrl)
                assertEquals(synced.lastSyncedAt, afterKeep.lastSyncedAt)
                val stateAfterKeep = client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>()
                assertEquals(syncedState.syncedDocument, stateAfterKeep.syncedDocument)

                // A batch WITH a sourceUrl moves the reference and re-stamps, even under the same replaceExisting import.
                val movedUrl = sourceUrl(unique("moved"))
                val movedDoc = blueprintJson.encodeToJsonElement(entityRequest(bpId, entId).copy(title = "Moved via import")).jsonObject
                val moved = client.postJson(
                    "/api/v1/entities/import",
                    EntityImportRequest(documents = listOf(movedDoc), replaceExisting = true, sourceUrl = movedUrl),
                ).body<EntityImportResponse>().results.single()
                assertEquals(OntologyImportStatus.UPDATED, moved.status)
                val afterMove = client.get("/api/v1/entities/${created.id}").body<EntityResponse>()
                assertEquals(movedUrl, afterMove.sourceUrl)
                assertTrue(afterMove.lastSyncedAt > 0)
                assertEquals(afterMove.updatedAt, afterMove.lastSyncedAt)
                val stateAfterMove = client.get("/api/v1/entities/${created.id}/sync").body<EntitySyncStateResponse>()
                assertEquals("Moved via import", stateAfterMove.syncedDocument!!.title)
            } finally {
                TestEntities.remove(entId)
                TestBlueprints.remove(bpId)
            }
        }

    @Test
    fun `a pass-2 restored row's sync baseline is the final document, not the pass-1 partial`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-pass2", UserRole.ADMIN)
        val bpId = unique("bp-esync-pass2")
        val a = unique("ent-esync-pass2-a")
        val b = unique("ent-esync-pass2-b")
        val url = sourceUrl(a)
        try {
            client.createBlueprint(
                BlueprintRequest(
                    identifier = bpId, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("peer" to RelationDefinition(title = "Peer", target = bpId, required = false, many = false)),
                ),
            )
            // The EntityImportTest.kt "optional relation cycle lands via pass 2" fixture — the
            // mutual reference forces both rows through the deferred-back-edge, two-pass write.
            val aDoc = blueprintJson.encodeToJsonElement(
                entityRequest(bpId, a).copy(relations = buildJsonObject { put("peer", b) }),
            ).jsonObject
            val bDoc = blueprintJson.encodeToJsonElement(
                entityRequest(bpId, b).copy(relations = buildJsonObject { put("peer", a) }),
            ).jsonObject

            val result = client.postJson(
                "/api/v1/entities/import",
                EntityImportRequest(documents = listOf(aDoc, bDoc), sourceUrl = url),
            ).body<EntityImportResponse>()
            assertEquals(listOf(OntologyImportStatus.CREATED, OntologyImportStatus.CREATED), result.results.map { it.status })
            val aId = result.results[0].id!!

            val state = client.get("/api/v1/entities/$aId/sync").body<EntitySyncStateResponse>()
            // The FINAL restored document, not pass 1's stripped-relation intermediate.
            assertEquals(b, state.syncedDocument!!.relations.getValue("peer").jsonPrimitive.content)
        } finally {
            TestEntities.remove(a, b)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `the list sorts by lastSyncedAt with never-synced entities first ascending`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-sort", UserRole.ADMIN)
        val bpId = unique("bp-esync-sort")
        val never = unique("ent-esync-never")
        val fresh = unique("ent-esync-fresh")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            client.postJson("/api/v1/entities", entityRequest(bpId, never, sourceUrl(never)))
            val freshCreated = client.postJson("/api/v1/entities", entityRequest(bpId, fresh, sourceUrl(fresh))).body<EntityResponse>()
            client.postJson("/api/v1/entities/${freshCreated.id}/sync", SyncEntityRequest(document = entityRequest(bpId, fresh)))

            val ascending = client.get("/api/v1/entities?blueprint=$bpId&sort=lastSyncedAt")
                .body<EntityPageResponse>().items.map { it.identifier }
            assertEquals(listOf(never, fresh), ascending)
            val descending = client.get("/api/v1/entities?blueprint=$bpId&sort=-lastSyncedAt")
                .body<EntityPageResponse>().items.map { it.identifier }
            assertEquals(listOf(fresh, never), descending)
        } finally {
            TestEntities.remove(never, fresh)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `the sync body's own document sourceUrl is refused - it is row state, not a document member`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-docurl", UserRole.ADMIN)
        val bpId = unique("bp-esync-docurl")
        val entId = unique("ent-esync-docurl")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            val created = client.postJson("/api/v1/entities", entityRequest(bpId, entId, sourceUrl(entId))).body<EntityResponse>()
            val response = client.postJson(
                "/api/v1/entities/${created.id}/sync",
                SyncEntityRequest(document = entityRequest(bpId, entId, sourceUrl("other"))),
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.body<ProblemDetail>().detail!!.contains("sourceUrl"))
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a sync changing blueprint is a 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-bpmismatch", UserRole.ADMIN)
        val bpId = unique("bp-esync-bpmismatch")
        val otherBpId = unique("bp-esync-other")
        val entId = unique("ent-esync-bpmismatch")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            client.createBlueprint(simpleBlueprint(otherBpId))
            val created = client.postJson("/api/v1/entities", entityRequest(bpId, entId, sourceUrl(entId))).body<EntityResponse>()
            val response = client.postJson(
                "/api/v1/entities/${created.id}/sync",
                SyncEntityRequest(document = entityRequest(otherBpId, entId)),
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId, otherBpId)
        }
    }

    @Test
    fun `a rename via sync cascades into the referrer, observed on its GET`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-rename", UserRole.ADMIN)
        val targetBp = unique("bp-esync-rename-target")
        val referrerBp = unique("bp-esync-rename-referrer")
        val oldId = unique("ent-esync-old")
        val newId = unique("ent-esync-new")
        val referrerEnt = unique("ent-esync-referrer")
        try {
            client.createBlueprint(simpleBlueprint(targetBp))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = referrerBp,
                    title = "T",
                    schema = BlueprintSchema(),
                    relations = mapOf("target" to RelationDefinition(title = "Target", target = targetBp, required = false, many = false)),
                ),
            )
            val target = client.postJson("/api/v1/entities", entityRequest(targetBp, oldId, sourceUrl(oldId))).body<EntityResponse>()
            val referrer = client.postJson(
                "/api/v1/entities",
                entityRequest(referrerBp, referrerEnt).copy(relations = buildJsonObject { put("target", oldId) }),
            ).body<EntityResponse>()

            withAuditCapture { capture ->
                val synced = client.postJson(
                    "/api/v1/entities/${target.id}/sync",
                    SyncEntityRequest(document = entityRequest(targetBp, newId)),
                )
                assertEquals(HttpStatusCode.NoContent, synced.status)
                val event = capture.events.firstOrNull { it.message == "entity.synced" }
                assertNotNull(event)
                assertTrue(event.hasKeyValue("cascaded", 1))
                assertTrue(event.hasKeyValue("renamedFrom", oldId))
            }

            val updatedReferrer = client.get("/api/v1/entities/${referrer.id}").body<EntityResponse>()
            assertEquals(newId, updatedReferrer.relations.getValue("target").jsonPrimitive.content)
        } finally {
            TestEntities.remove(oldId, newId, referrerEnt)
            TestBlueprints.remove(targetBp, referrerBp)
        }
    }

    @Test
    fun `a repo-side rename landing on a taken identity is a 409`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-clash", UserRole.ADMIN)
        val bpId = unique("bp-esync-clash")
        val taken = unique("ent-esync-taken")
        val entId = unique("ent-esync-renamer")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            client.postJson("/api/v1/entities", entityRequest(bpId, taken))
            val created = client.postJson("/api/v1/entities", entityRequest(bpId, entId, sourceUrl(entId))).body<EntityResponse>()
            val renamed = client.postJson(
                "/api/v1/entities/${created.id}/sync",
                SyncEntityRequest(document = entityRequest(bpId, taken)),
            )
            assertEquals(HttpStatusCode.Conflict, renamed.status)
        } finally {
            TestEntities.remove(taken, entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `sync endpoints require authentication`() = testApplication {
        usePostgresTestcontainer()
        val anonymous = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/v1/entities/1/sync").status)
        val anonymousPost = anonymous.postJson(
            "/api/v1/entities/1/sync",
            SyncEntityRequest(document = EntityRequest(blueprint = "bp", identifier = "x", title = "T")),
        )
        assertEquals(HttpStatusCode.Unauthorized, anonymousPost.status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.postJson("/api/v1/entities/fetch", FetchUrlRequest(url = "https://example.com/x.json")).status,
        )
    }

    @Test
    fun `sync endpoints 404 on unknown ids`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-404", UserRole.ADMIN)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/entities/999999999/sync").status)
        val missing = client.postJson(
            "/api/v1/entities/999999999/sync",
            SyncEntityRequest(document = EntityRequest(blueprint = "bp", identifier = "x", title = "T")),
        )
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `sync on an unknown id 404s even with a structurally invalid document`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-404-invalid", UserRole.ADMIN)
        // A blank identifier fails requireEntityIdentifier — a missing id must still 404 FIRST
        // (the PUT precedent: the service checks existence before it validates).
        val invalid = client.postJson(
            "/api/v1/entities/999999999/sync",
            SyncEntityRequest(document = EntityRequest(blueprint = "bp", identifier = "", title = "T")),
        )
        assertEquals(HttpStatusCode.NotFound, invalid.status)
    }

    @Test
    fun `a raw response body carries no explicit sourceUrl null member when unset`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-rawbody", UserRole.ADMIN)
        val bpId = unique("bp-esync-rawbody")
        val entId = unique("ent-esync-rawbody")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            val response = client.postJson("/api/v1/entities", entityRequest(bpId, entId))
            val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue("lastSyncedAt" in obj.keys)
            assertTrue("sourceUrl" !in obj.keys)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `an invalid batch sourceUrl is a whole-request 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("esync-badbatchurl", UserRole.ADMIN)
        val bpId = unique("bp-esync-badbatchurl")
        val entId = unique("ent-esync-badbatchurl")
        try {
            client.createBlueprint(simpleBlueprint(bpId))
            val doc = blueprintJson.encodeToJsonElement(entityRequest(bpId, entId)).jsonObject
            val response = client.postJson(
                "/api/v1/entities/import",
                EntityImportRequest(documents = listOf(doc), sourceUrl = "not-a-url"),
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
        } finally {
            TestEntities.remove(entId)
            TestBlueprints.remove(bpId)
        }
    }
}
