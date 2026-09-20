package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintImportRequest
import ch.nokillswit.blueprints.BlueprintImportResponse
import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.BlueprintResponse
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.BlueprintSyncStateResponse
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.blueprints.SYSTEM_TEAM_BLUEPRINT
import ch.nokillswit.blueprints.SyncBlueprintRequest
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.infra.fetch.FetchUrlRequest
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Blueprint source references & HTTP re-sync (2.10.0, V38) — the `EntitySyncTest.kt` case list,
 * one level up: `sourceUrl` on create/replace, the sync-state read, the strict remote->DB sync
 * (NO waiver exists for blueprints, unlike the catalog), the `hierarchyRelations` keep-when-
 * absent merge, system-blueprint extension/rename via sync, and the import-as-sync path. Every
 * test mints unique `bp-<uuid8>` identifiers and cleans up via [TestBlueprints]; any case
 * touching `_team`/`_user` restores them via [TestBlueprints.restoreSystemBlueprints] in `finally`.
 */
class BlueprintSyncTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun sourceUrl(marker: String) = "https://example.com/$marker/blueprint.json"

    private fun simple(id: String) = BlueprintRequest(identifier = id, title = "T", schema = BlueprintSchema())

    private suspend fun HttpClient.createBlueprint(request: BlueprintRequest) =
        postJson("/api/v1/blueprints", request).body<BlueprintResponse>()

    private suspend fun HttpClient.syncState(id: UInt) = get("/api/v1/blueprints/$id/sync").body<BlueprintSyncStateResponse>()

    private suspend fun HttpClient.sync(id: UInt, document: BlueprintRequest) =
        postJson("/api/v1/blueprints/$id/sync", SyncBlueprintRequest(document = document))

    @Test
    fun `a create with a sourceUrl stores the reference but starts unsynced`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-create", UserRole.ADMIN)
        val bpId = unique("bp-bpsync-create")
        val url = sourceUrl(bpId)
        try {
            val created = client.createBlueprint(simple(bpId).copy(sourceUrl = url))
            assertEquals(url, created.sourceUrl)
            assertEquals(0L, created.lastSyncedAt)

            val state = client.syncState(created.id)
            assertEquals(url, state.sourceUrl)
            assertEquals(0L, state.lastSyncedAt)
            assertNull(state.syncedDocument)
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `an invalid URL is 400 on both POST and PUT`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-badurl", UserRole.ADMIN)
        val bpId = unique("bp-bpsync-badurl")
        try {
            assertEquals(
                HttpStatusCode.BadRequest,
                client.postJson("/api/v1/blueprints", simple(bpId).copy(sourceUrl = "not-a-url")).status,
            )
            val created = client.createBlueprint(simple(bpId))
            assertEquals(
                HttpStatusCode.BadRequest,
                client.putJson("/api/v1/blueprints/${created.id}", simple(bpId).copy(sourceUrl = "ftp://x")).status,
            )
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a sync overwrites the definition and stamps the sync state, audited system false`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-stamp", UserRole.ADMIN)
        val bpId = unique("bp-bpsync-stamp")
        try {
            val created = client.createBlueprint(simple(bpId).copy(sourceUrl = sourceUrl(bpId)))

            withAuditCapture { capture ->
                val remote = simple(bpId).copy(title = "From remote")
                val synced = client.sync(created.id, remote)
                assertEquals(HttpStatusCode.NoContent, synced.status)
                val event = capture.events.firstOrNull { it.message == "blueprint.synced" }
                assertNotNull(event)
                assertTrue(event.hasKeyValue("blueprintId", created.id.toLong()))
                assertTrue(event.hasKeyValue("system", false))
            }

            val after = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
            assertEquals("From remote", after.title)
            assertTrue(after.lastSyncedAt > 0)
            assertEquals(after.lastSyncedAt, after.updatedAt)

            val state = client.syncState(created.id)
            assertEquals("From remote", state.syncedDocument!!.title)
            // The baseline is request-shaped and drops sourceUrl (the envelope reference never
            // rides inside the compared document).
            assertNull(state.syncedDocument!!.sourceUrl)
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a sync on a blueprint without a source reference is a 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-nosrc", UserRole.ADMIN)
        val bpId = unique("bp-bpsync-nosrc")
        try {
            val created = client.createBlueprint(simple(bpId))
            val state = client.syncState(created.id)
            assertEquals(null, state.sourceUrl)
            assertNull(state.syncedDocument)

            assertEquals(HttpStatusCode.BadRequest, client.sync(created.id, simple(bpId)).status)
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `a validation-failing sync is refused outright, the row left byte-identical`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-findings", UserRole.ADMIN)
        val bpId = unique("bp-bpsync-findings")
        try {
            val created = client.createBlueprint(simple(bpId).copy(sourceUrl = sourceUrl(bpId)))
            val before = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()

            // The remote copy names an unresolvable relation target.
            val badRemote = simple(bpId).copy(
                relations = mapOf("bad" to RelationDefinition(title = "Bad", target = "no-such-blueprint", required = false, many = false)),
            )
            val response = client.sync(created.id, badRemote)
            assertEquals(HttpStatusCode.BadRequest, response.status)

            val after = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
            assertEquals(before, after)
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `PUT changing or clearing the source reference resets the sync state, unchanged keeps it`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-reset", UserRole.ADMIN)
        val bpId = unique("bp-bpsync-reset")
        val url = sourceUrl(bpId)
        try {
            val created = client.createBlueprint(simple(bpId).copy(sourceUrl = url))
            client.sync(created.id, simple(bpId))
            val synced = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
            assertTrue(synced.lastSyncedAt > 0)

            // An unchanged reference on an ordinary PUT keeps the stamp.
            client.putJson("/api/v1/blueprints/${created.id}", simple(bpId).copy(sourceUrl = url))
            val kept = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
            assertEquals(synced.lastSyncedAt, kept.lastSyncedAt)

            // A changed reference resets the stamp and drops the baseline.
            val other = sourceUrl(unique("moved"))
            client.putJson("/api/v1/blueprints/${created.id}", simple(bpId).copy(sourceUrl = other))
            val moved = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
            assertEquals(other, moved.sourceUrl)
            assertEquals(0L, moved.lastSyncedAt)
            assertNull(client.syncState(created.id).syncedDocument)

            // Full-replace semantics: an omitted sourceUrl clears the reference.
            client.putJson("/api/v1/blueprints/${created.id}", simple(bpId))
            val cleared = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
            assertNull(cleared.sourceUrl)
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `hierarchyRelations trio - keep when absent, apply when present, refused when the remote dropped the named relation`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("bpsync-hier", UserRole.ADMIN)
            val bpId = unique("bp-bpsync-hier")
            val hierarchiesBefore = SampleData.snapshotHierarchies()
            try {
                TestHierarchies.ensure("composition")
                val withRelation = BlueprintRequest(
                    identifier = bpId, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("parent" to RelationDefinition(title = "Parent", target = bpId, required = false, many = false)),
                    hierarchyRelations = mapOf("composition" to "parent"),
                    sourceUrl = sourceUrl(bpId),
                )
                val created = client.createBlueprint(withRelation)

                // (a) A remote body OMITTING hierarchyRelations keeps the stored map — on the GET
                // and on the sync baseline. Remote copies never carry `sourceUrl` (row state,
                // never a document member) — cleared explicitly since `.copy()` off `withRelation`
                // would otherwise inherit it.
                val remoteWithoutMap = withRelation.copy(title = "Kept", hierarchyRelations = null, sourceUrl = null)
                assertEquals(HttpStatusCode.NoContent, client.sync(created.id, remoteWithoutMap).status)
                val afterKeep = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
                assertEquals(mapOf("composition" to "parent"), afterKeep.hierarchyRelations)
                val stateAfterKeep = client.syncState(created.id)
                assertEquals(mapOf("composition" to "parent"), stateAfterKeep.syncedDocument!!.hierarchyRelations)

                // (b) A remote body naming a DIFFERENT map applies it.
                val secondRelation = mapOf(
                    "parent" to RelationDefinition(title = "Parent", target = bpId, required = false, many = false),
                    "alt" to RelationDefinition(title = "Alt", target = bpId, required = false, many = false),
                )
                val remoteWithDifferentMap = withRelation.copy(
                    relations = secondRelation, hierarchyRelations = mapOf("composition" to "alt"), sourceUrl = null,
                )
                assertEquals(HttpStatusCode.NoContent, client.sync(created.id, remoteWithDifferentMap).status)
                val afterApply = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
                assertEquals(mapOf("composition" to "alt"), afterApply.hierarchyRelations)

                // (c) A remote body OMITTING the map, which ALSO dropped the "alt" relation the
                // stored map still names, is refused - the merge runs before validation.
                val remoteDroppingNamedRelation = BlueprintRequest(
                    identifier = bpId, title = "Dropped", schema = BlueprintSchema(),
                    relations = mapOf("parent" to RelationDefinition(title = "Parent", target = bpId, required = false, many = false)),
                )
                val refused = client.sync(created.id, remoteDroppingNamedRelation)
                assertEquals(HttpStatusCode.BadRequest, refused.status)
                assertTrue(refused.body<ProblemDetail>().detail!!.contains("hierarchyRelations.composition"))
            } finally {
                TestBlueprints.remove(bpId)
                SampleData.restoreHierarchies(hierarchiesBefore)
            }
        }

    @Test
    fun `_team extended via sync succeeds and audits system true`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-team-ext", UserRole.ADMIN)
        try {
            val team = client.get("/api/v1/blueprints").body<ch.nokillswit.blueprints.BlueprintList>()
                .items.single { it.identifier == SYSTEM_TEAM_BLUEPRINT }
            client.putJson("/api/v1/blueprints/${team.id}", BlueprintRequest(
                identifier = SYSTEM_TEAM_BLUEPRINT, title = team.title, schema = team.schema, relations = team.relations,
                sourceUrl = sourceUrl("team"),
            ))

            withAuditCapture { capture ->
                val remote = BlueprintRequest(
                    identifier = SYSTEM_TEAM_BLUEPRINT, title = team.title, schema = team.schema,
                    relations = team.relations + (
                        "extra" to RelationDefinition(title = "Extra", target = SYSTEM_TEAM_BLUEPRINT, required = false, many = false)
                    ),
                )
                val synced = client.sync(team.id, remote)
                assertEquals(HttpStatusCode.NoContent, synced.status)
                val event = capture.events.firstOrNull { it.message == "blueprint.synced" }
                assertNotNull(event)
                assertTrue(event.hasKeyValue("system", true))
            }
            val reread = client.get("/api/v1/blueprints/${team.id}").body<BlueprintResponse>()
            assertTrue(reread.relations.containsKey("extra"))
        } finally {
            TestBlueprints.restoreSystemBlueprints()
        }
    }

    @Test
    fun `_team renamed via sync is 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-team-rename", UserRole.ADMIN)
        try {
            val team = client.get("/api/v1/blueprints").body<ch.nokillswit.blueprints.BlueprintList>()
                .items.single { it.identifier == SYSTEM_TEAM_BLUEPRINT }
            client.putJson("/api/v1/blueprints/${team.id}", BlueprintRequest(
                identifier = SYSTEM_TEAM_BLUEPRINT, title = team.title, schema = team.schema, relations = team.relations,
                sourceUrl = sourceUrl("team"),
            ))
            val renamed = client.sync(
                team.id,
                BlueprintRequest(identifier = "not-team", title = team.title, schema = team.schema, relations = team.relations),
            )
            assertEquals(HttpStatusCode.BadRequest, renamed.status)
        } finally {
            TestBlueprints.restoreSystemBlueprints()
        }
    }

    @Test
    fun `a rename via sync cascades into a referrer, audited with renamedFrom`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-rename", UserRole.ADMIN)
        val targetBp = unique("bp-bpsync-rename-target")
        val referrerBp = unique("bp-bpsync-rename-referrer")
        val newId = unique("bp-bpsync-rename-new")
        try {
            val target = client.createBlueprint(simple(targetBp).copy(sourceUrl = sourceUrl(targetBp)))
            client.createBlueprint(
                BlueprintRequest(
                    identifier = referrerBp, title = "T", schema = BlueprintSchema(),
                    relations = mapOf("target" to RelationDefinition(title = "Target", target = targetBp, required = false, many = false)),
                ),
            )

            withAuditCapture { capture ->
                val synced = client.sync(target.id, simple(newId))
                assertEquals(HttpStatusCode.NoContent, synced.status)
                val event = capture.events.firstOrNull { it.message == "blueprint.synced" }
                assertNotNull(event)
                assertTrue(event.hasKeyValue("cascaded", 1))
                assertTrue(event.hasKeyValue("renamedFrom", targetBp))
            }

            val referrer = client.get("/api/v1/blueprints").body<ch.nokillswit.blueprints.BlueprintList>()
                .items.single { it.identifier == referrerBp }
            assertEquals(newId, referrer.relations.getValue("target").target)
        } finally {
            TestBlueprints.remove(newId, referrerBp)
        }
    }

    @Test
    fun `a repo-side rename landing on a taken identifier is a 409`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-clash", UserRole.ADMIN)
        val taken = unique("bp-bpsync-taken")
        val bpId = unique("bp-bpsync-renamer")
        try {
            client.createBlueprint(simple(taken))
            val created = client.createBlueprint(simple(bpId).copy(sourceUrl = sourceUrl(bpId)))
            val renamed = client.sync(created.id, simple(taken))
            assertEquals(HttpStatusCode.Conflict, renamed.status)
        } finally {
            TestBlueprints.remove(taken, bpId)
        }
    }

    @Test
    fun `the sync body's own document sourceUrl is refused - it is row state, not a document member`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-docurl", UserRole.ADMIN)
        val bpId = unique("bp-bpsync-docurl")
        try {
            val created = client.createBlueprint(simple(bpId).copy(sourceUrl = sourceUrl(bpId)))
            val response = client.sync(created.id, simple(bpId).copy(sourceUrl = sourceUrl("other")))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.body<ProblemDetail>().detail!!.contains("sourceUrl"))
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `sync endpoints require authentication`() = testApplication {
        usePostgresTestcontainer()
        val anonymous = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/v1/blueprints/1/sync").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.postJson("/api/v1/blueprints/1/sync", SyncBlueprintRequest(document = simple("x"))).status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            anonymous.postJson("/api/v1/blueprints/fetch", FetchUrlRequest(url = "https://example.com/x.json")).status,
        )
    }

    @Test
    fun `a USER may read sync state but not sync or fetch, on existing and unknown ids alike`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("bpsync-user-admin", UserRole.ADMIN)
        val user = seededClient("bpsync-user", UserRole.USER)
        val bpId = unique("bp-bpsync-user")
        try {
            val created = admin.createBlueprint(simple(bpId).copy(sourceUrl = sourceUrl(bpId)))
            assertEquals(HttpStatusCode.OK, user.get("/api/v1/blueprints/${created.id}/sync").status)
            assertEquals(HttpStatusCode.Forbidden, user.sync(created.id, simple(bpId)).status)
            assertEquals(HttpStatusCode.Forbidden, user.sync(999_999_999u, simple(bpId)).status)
            assertEquals(
                HttpStatusCode.Forbidden,
                user.postJson("/api/v1/blueprints/fetch", FetchUrlRequest(url = "https://example.com/x.json")).status,
            )
        } finally {
            TestBlueprints.remove(bpId)
        }
    }

    @Test
    fun `sync endpoints 404 on unknown ids`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-404", UserRole.ADMIN)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/blueprints/999999999/sync").status)
        assertEquals(HttpStatusCode.NotFound, client.sync(999_999_999u, simple("x")).status)
    }

    @Test
    fun `sync on an unknown id 404s even with a structurally invalid document`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-404-invalid", UserRole.ADMIN)
        // A blank identifier fails validation - a missing id must still 404 FIRST (the PUT
        // precedent: the service checks existence before it validates).
        val invalid = client.sync(999_999_999u, BlueprintRequest(identifier = "", title = "T"))
        assertEquals(HttpStatusCode.NotFound, invalid.status)
    }

    @Test
    fun `an import with a batch sourceUrl stores rows synced, dry-run parity holds, a per-document sourceUrl is INVALID`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("bpsync-import", UserRole.ADMIN)
            val bpId = unique("bp-bpsync-import")
            val url = sourceUrl(bpId)
            try {
                val doc = blueprintJson.encodeToJsonElement(simple(bpId)).jsonObject

                val check = client.postJson(
                    "/api/v1/blueprints/import/check",
                    BlueprintImportRequest(documents = listOf(doc), sourceUrl = url),
                ).body<BlueprintImportResponse>().results.single()
                assertEquals(OntologyImportStatus.CREATED, check.status)

                val real = client.postJson(
                    "/api/v1/blueprints/import",
                    BlueprintImportRequest(documents = listOf(doc), sourceUrl = url),
                ).body<BlueprintImportResponse>().results.single()
                assertEquals(OntologyImportStatus.CREATED, real.status)

                val stored = client.get("/api/v1/blueprints/${real.id}").body<BlueprintResponse>()
                assertEquals(url, stored.sourceUrl)
                assertTrue(stored.lastSyncedAt > 0)
                assertEquals(stored.updatedAt, stored.lastSyncedAt)
                val state = client.syncState(real.id!!)
                assertEquals(bpId, state.syncedDocument!!.identifier)

                // A per-document sourceUrl is INVALID - row state belongs to the whole request.
                val badId = unique("bp-bpsync-baddoc")
                val badDoc = blueprintJson.encodeToJsonElement(simple(badId).copy(sourceUrl = url)).jsonObject
                val badResult = client.postJson("/api/v1/blueprints/import", BlueprintImportRequest(documents = listOf(badDoc)))
                    .body<BlueprintImportResponse>().results.single()
                assertEquals(OntologyImportStatus.INVALID, badResult.status)
            } finally {
                TestBlueprints.remove(bpId)
            }
        }

    @Test
    fun `a replaceExisting import without a batch sourceUrl keeps the row's existing reference and stamp - D3 Keep`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("bpsync-keep", UserRole.ADMIN)
            val bpId = unique("bp-bpsync-keep")
            val originalUrl = sourceUrl(bpId)
            try {
                val created = client.createBlueprint(simple(bpId).copy(sourceUrl = originalUrl))
                client.sync(created.id, simple(bpId))
                val synced = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
                assertTrue(synced.lastSyncedAt > 0)
                val syncedState = client.syncState(created.id)

                val keepDoc = blueprintJson.encodeToJsonElement(simple(bpId).copy(title = "Changed via import")).jsonObject
                val kept = client.postJson(
                    "/api/v1/blueprints/import",
                    BlueprintImportRequest(documents = listOf(keepDoc), replaceExisting = true),
                ).body<BlueprintImportResponse>().results.single()
                assertEquals(OntologyImportStatus.UPDATED, kept.status)
                val afterKeep = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
                assertEquals("Changed via import", afterKeep.title)
                assertEquals(originalUrl, afterKeep.sourceUrl)
                assertEquals(synced.lastSyncedAt, afterKeep.lastSyncedAt)
                val stateAfterKeep = client.syncState(created.id)
                assertEquals(syncedState.syncedDocument, stateAfterKeep.syncedDocument)

                val movedUrl = sourceUrl(unique("moved"))
                val movedDoc = blueprintJson.encodeToJsonElement(simple(bpId).copy(title = "Moved via import")).jsonObject
                val moved = client.postJson(
                    "/api/v1/blueprints/import",
                    BlueprintImportRequest(documents = listOf(movedDoc), replaceExisting = true, sourceUrl = movedUrl),
                ).body<BlueprintImportResponse>().results.single()
                assertEquals(OntologyImportStatus.UPDATED, moved.status)
                val afterMove = client.get("/api/v1/blueprints/${created.id}").body<BlueprintResponse>()
                assertEquals(movedUrl, afterMove.sourceUrl)
                assertTrue(afterMove.lastSyncedAt > 0)
                assertEquals(afterMove.updatedAt, afterMove.lastSyncedAt)
                val stateAfterMove = client.syncState(created.id)
                assertEquals("Moved via import", stateAfterMove.syncedDocument!!.title)
            } finally {
                TestBlueprints.remove(bpId)
            }
        }

    @Test
    fun `a pass-2 restored row's sync baseline is the final document, not the pass-1 partial`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-pass2", UserRole.ADMIN)
        val a = unique("bp-bpsync-pass2-a")
        val b = unique("bp-bpsync-pass2-b")
        val url = sourceUrl(a)
        try {
            // A 2-cycle: a's relation targets b, b's relation targets a - forces a's first write
            // to defer "peer" and restore it via pass 2.
            val aDoc = blueprintJson.encodeToJsonElement(
                BlueprintRequest(
                    identifier = a, title = "A", schema = BlueprintSchema(),
                    relations = mapOf("peer" to RelationDefinition(title = "Peer", target = b, required = false, many = false)),
                ),
            ).jsonObject
            val bDoc = blueprintJson.encodeToJsonElement(
                BlueprintRequest(
                    identifier = b, title = "B", schema = BlueprintSchema(),
                    relations = mapOf("peer" to RelationDefinition(title = "Peer", target = a, required = false, many = false)),
                ),
            ).jsonObject

            val result = client.postJson(
                "/api/v1/blueprints/import",
                BlueprintImportRequest(documents = listOf(aDoc, bDoc), sourceUrl = url),
            ).body<BlueprintImportResponse>()
            assertEquals(listOf(OntologyImportStatus.CREATED, OntologyImportStatus.CREATED), result.results.map { it.status })
            val aId = result.results[0].id!!

            val state = client.syncState(aId)
            // The FINAL restored document, not pass 1's stripped-relation intermediate.
            assertEquals(b, state.syncedDocument!!.relations.getValue("peer").target)
        } finally {
            TestBlueprints.remove(a, b)
        }
    }

    @Test
    fun `an invalid batch sourceUrl is a whole-request 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("bpsync-badbatchurl", UserRole.ADMIN)
        val bpId = unique("bp-bpsync-badbatchurl")
        try {
            val doc = blueprintJson.encodeToJsonElement(simple(bpId)).jsonObject
            val response = client.postJson(
                "/api/v1/blueprints/import",
                BlueprintImportRequest(documents = listOf(doc), sourceUrl = "not-a-url"),
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
        } finally {
            TestBlueprints.remove(bpId)
        }
    }
}
