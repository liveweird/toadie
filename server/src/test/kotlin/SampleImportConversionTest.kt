package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.identityOf
import ch.nokillswit.conversion.CatalogDocumentsResponse
import ch.nokillswit.conversion.ConversionFindingCode
import ch.nokillswit.conversion.ConversionMapping
import ch.nokillswit.conversion.ConversionMappingRequest
import ch.nokillswit.conversion.ConversionRowStatus
import ch.nokillswit.conversion.EntityDocumentsRequest
import ch.nokillswit.conversion.EntityDocumentsResponse
import ch.nokillswit.conversion.MappingDocument
import ch.nokillswit.conversion.PortAddress
import ch.nokillswit.conversion.PortMeta
import ch.nokillswit.conversion.parseMappingDocument
import ch.nokillswit.entities.EntityImportRequest
import ch.nokillswit.entities.EntityImportResponse
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.entities.ExpectedEntityState
import ch.nokillswit.infra.importing.OntologyImportStatus
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flagship test B of the Backstage <-> Port conversion (test A is [SampleConversionTest]): the checked-in
 * Backstage sample (`sample-data/backstage/commerce-payments/catalog-info.yaml`), converted through the
 * checked-in default mapping into an EMPTY Port workspace (blueprints and mapping loaded, no entities), IS the
 * Port sample's mapped entities - modulo what the mapping declares lossy and an EXPLICIT deviation list.
 *
 * Every tolerated difference is a [Deviation] naming the identity, the field, the value the CONVERSION produced
 * and the value the Port sample holds, so a deviation that stops deviating, changes value or appears elsewhere
 * fails the test; there is no wildcard and no field is overwritten wholesale. The converted value of each
 * deviation is asserted BEFORE anything is compared to the Port side. Beyond equality: the documents pass the
 * entity import's dry run with no INVALID row, importing them and converting again is the identity, a second
 * import changes nothing, a merge keeps Port-only data an earlier Port edit added, and the export of the
 * imported workspace is the Backstage sample again modulo its own explicit list.
 */
class SampleImportConversionTest {

    /** One tolerated difference between a converted entity document and the Port sample's. */
    private data class Deviation(
        val identity: String,
        val path: String,
        val converted: JsonElement?,
        val port: JsonElement?,
        val reason: String,
    ) {
        fun key() = Triple(identity, path, converted to port)
    }

    private fun str(value: String) = JsonPrimitive(value)
    private fun strings(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    private val deviations = listOf(
        Deviation(
            "domain/payments", "team", str("payments-squad"), strings("payments-squad", "platform-tribe"),
            "two teams, one owner: spec.owner keeps the first (the export's OWNER_TRUNCATED, in reverse)",
        ),
        Deviation(
            "service/payments-gateway", "team", str("payments-squad"), strings("payments-squad", "platform-tribe"),
            "two teams, one owner: spec.owner keeps the first",
        ),
        Deviation(
            "service/legacy-invoicing", "team", null, str("payments-squad"),
            "the Backstage sample deliberately owns it by group:default/billing-squad, a Group that does not exist: OWNER_DROPPED",
        ),
        Deviation(
            "service/legacy-invoicing", "relations.uses", null, strings("orders-db"),
            "the Backstage sample deliberately depends on resource:default/invoice-archive (missing) and a bare 'orders-db' " +
                "(no kind): both REFERENCE_DROPPED, so nothing is left to become the relation",
        ),
    )

    private fun JsonObject.identity() = getValue("blueprint").jsonPrimitive.content + "/" + getValue("identifier").jsonPrimitive.content

    private fun JsonObject.withoutNulls() = JsonObject(filterValues { it !is JsonNull })

    /** The Port sample document minus what the mapping declares lossy or never reads: the data-driven part of "modulo declared lossy". */
    private fun normalized(port: JsonObject, mapping: MappingDocument): JsonObject {
        val pair = mapping.pairs.first { it.blueprint == port.getValue("blueprint").jsonPrimitive.content }
        val readsIcon = pair.rules.any { it.readsOnImport && (it.port as? PortAddress.Meta)?.field == PortMeta.ICON }
        val properties = port.getValue("properties").jsonObject.withoutNulls().filterKeys { it !in pair.lossy.properties }
        val relations = port.getValue("relations").jsonObject.withoutNulls().filterKeys { it !in pair.lossy.relations }
        return JsonObject(
            port.filterKeys { it != "icon" || readsIcon } +
                mapOf("properties" to JsonObject(properties), "relations" to JsonObject(relations)),
        )
    }

    private fun diffs(converted: JsonObject, port: JsonObject): List<Deviation> {
        val identity = converted.identity()
        fun field(path: String, a: JsonElement?, b: JsonElement?) =
            if (a == b) null else Deviation(identity, path, a, b, "")
        val top = (converted.keys + port.keys).filter { it != "properties" && it != "relations" }
            .mapNotNull { field(it, converted[it], port[it]) }
        val nested = listOf("properties", "relations").flatMap { section ->
            val a = converted.getValue(section).jsonObject
            val b = port.getValue(section).jsonObject
            (a.keys + b.keys).mapNotNull { field("$section.$it", a[it], b[it]) }
        }
        return top + nested
    }

    private suspend fun HttpClient.states(ids: Map<String, UInt>): Map<String, EntityResponse> =
        ids.mapValues { (_, id) -> get("/api/v1/entities/$id").body<EntityResponse>() }

    private fun EntityResponse.content() = listOf(title, icon, team, properties, relations)

    @Test
    fun `the Backstage sample converts to the Port sample modulo the deviation list and survives the round trip`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("convimport", UserRole.ADMIN)
        val blueprintFiles = SampleData.numberedFiles("blueprints")
        val requests = blueprintFiles.associate { file ->
            blueprintJson.decodeFromString<BlueprintRequest>(file.readText()).let { it.identifier to it }
        }
        val portDocs = SampleData.numberedFiles("entities")
            .flatMap { file -> Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject } }
        val hierarchiesBefore = SampleData.snapshotHierarchies()
        val importedIdentifiers = mutableListOf<String>()
        var mappingId: UInt? = null
        try {
            TestHierarchies.ensure(*SampleData.requiredHierarchies)
            SampleData.loadBlueprints(admin, blueprintFiles)
            val mappingFile = File("../sample-data/port/commerce-payments/mapping/backstage.json")
            val mapping = parseMappingDocument(Json.parseToJsonElement(mappingFile.readText())).document!!
            val stored = admin.postJson(
                "/api/v1/conversion-mappings",
                ConversionMappingRequest("imp-${UUID.randomUUID().toString().take(8)}", Json.parseToJsonElement(mappingFile.readText())),
            )
            assertEquals(HttpStatusCode.Created, stored.status, stored.bodyAsText())
            val path = "/api/v1/conversion-mappings/${stored.body<ConversionMapping>().id.also { mappingId = it }}"
            val sample = SampleData.backstageSampleFiles()
            assertEquals(42, sample.size)

            suspend fun convert(files: List<CatalogFile>, merge: Boolean = true): EntityDocumentsResponse {
                val response = admin.postJson("$path/entity-documents", EntityDocumentsRequest(files, merge))
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                return response.body()
            }

            // ── the conversion itself ───────────────────────────────────────────────────────
            val first = convert(sample)
            assertRows(first)
            assertEquals(42, first.documents.size)

            // The converted values of every deviation are asserted first; only then is the Port side consulted.
            val portByIdentity = portDocs.associateBy { it.identity() }
            val convertedByIdentity = first.documents.associateBy { it.identity() }
            val residual = first.documents.flatMap { diffs(it, normalized(portByIdentity.getValue(it.identity()), mapping)) }
            deviations.forEach { deviation ->
                val converted = convertedByIdentity.getValue(deviation.identity)
                val section = deviation.path.substringBefore('.', "")
                val actual = if (section.isEmpty()) {
                    converted[deviation.path]
                } else {
                    converted.getValue(section).jsonObject[deviation.path.substringAfter('.')]
                }
                assertEquals(deviation.converted, actual, "${deviation.identity} ${deviation.path}: ${deviation.reason}")
            }
            assertEquals(
                deviations.map { it.key() }.sortedBy { it.toString() },
                residual.map { it.key() }.sortedBy { it.toString() },
                "the differing fields are exactly the listed deviations",
            )

            // ── the entity import accepts every document; importing then converting again is the identity ──
            val check = admin.postJson("/api/v1/entities/import/check", EntityImportRequest(first.documents))
            assertTrue(check.body<EntityImportResponse>().results.none { it.status == OntologyImportStatus.INVALID })
            val imported = admin.postJson("/api/v1/entities/import", EntityImportRequest(first.documents, replaceExisting = true))
            val importRows = imported.body<EntityImportResponse>().results
            assertTrue(importRows.all { it.status == OntologyImportStatus.CREATED }, importRows.toString())
            importRows.forEach { row -> row.identifier?.let { importedIdentifiers += it } }
            val ids = importRows.associate { it.identifier!! to it.id!! }
            val afterFirstImport = admin.states(ids)

            val second = convert(sample)
            assertEquals(first.documents, second.documents, "converting again after the import yields the same documents")
            assertEquals(first.rows.map { it.status to it.findings }, second.rows.map { it.status to it.findings })
            assertTrue(second.rows.all { it.existingId != null }, "every document now merges over its stored entity")
            val reimport = admin.postJson("/api/v1/entities/import", EntityImportRequest(second.documents, replaceExisting = true))
            assertTrue(reimport.body<EntityImportResponse>().results.all { it.status == OntologyImportStatus.UPDATED })
            assertEquals(
                afterFirstImport.mapValues { it.value.content() },
                admin.states(ids).mapValues { it.value.content() },
                "a second import changes nothing",
            )

            // ── a merge keeps what only Port holds ──────────────────────────────────────────
            val beforeEdit = convert(sample) // the documents and write-version tokens a client would hold while someone edits
            val gateway = afterFirstImport.getValue("payments-gateway")
            val worker = afterFirstImport.getValue("ledger-worker")
            val twoTeams = JsonArray(listOf(JsonPrimitive("payments-squad"), JsonPrimitive("platform-tribe")))
            suspend fun edit(entity: EntityResponse, team: JsonElement?, extra: Map<String, JsonElement>, icon: String?) {
                val response = admin.putJson(
                    "/api/v1/entities/${entity.id}",
                    EntityRequest(
                        blueprint = entity.blueprint, identifier = entity.identifier, title = entity.title, icon = icon,
                        team = team, relations = entity.relations,
                        // The response also carries COMPUTED properties, which a write refuses: keep the stored (schema) ones.
                        properties = JsonObject(
                            entity.properties.filterKeys { it in requests.getValue(entity.blueprint).schema.properties.keys } + extra,
                        ),
                    ),
                )
                assertTrue(response.status.value in 200..299, response.bodyAsText())
            }
            edit(gateway, twoTeams, emptyMap(), "Rocket")
            edit(worker, worker.team, mapOf("schedule" to str("*/5 * * * *")), "Zap")
            // ── the write-version guard: the two edited entities are STALE, every other row still lands ──
            val guarded = admin.postJson(
                "/api/v1/entities/import",
                EntityImportRequest(beforeEdit.documents, replaceExisting = true, expected = beforeEdit.expectedStates()),
            ).body<EntityImportResponse>().results
            assertEquals(
                setOf("payments-gateway", "ledger-worker"),
                guarded.filter { it.status == OntologyImportStatus.STALE }.map { it.identifier }.toSet(),
                "exactly the edited entities are refused",
            )
            val others = guarded.filter { it.status != OntologyImportStatus.STALE }
            assertTrue(others.all { it.status == OntologyImportStatus.UPDATED }, guarded.toString())
            assertEquals(twoTeams, admin.states(ids).getValue("payments-gateway").team, "the edit survived the stale re-post")

            // Converting again yields fresh tokens, and that batch imports cleanly.
            val merged = convert(sample)
            val gatewayDoc = merged.documents.single { it.identity() == "service/payments-gateway" }
            assertEquals(twoTeams, gatewayDoc["team"], "a stored team already containing the converted owner is kept")
            assertEquals(str("Rocket"), gatewayDoc["icon"], "no rule reads the icon, so the stored one stays")
            val workerDoc = merged.documents.single { it.identity() == "service/ledger-worker" }
            assertEquals(str("*/5 * * * *"), workerDoc.getValue("properties").jsonObject["schedule"], "a declared-lossy property survives")
            assertEquals(str("Zap"), workerDoc["icon"])
            val bare = convert(sample, merge = false).documents.single { it.identity() == "service/ledger-worker" }
            assertTrue("schedule" !in bare.getValue("properties").jsonObject && "icon" !in bare, "merge=false is the bare conversion")
            val remerge = admin.postJson(
                "/api/v1/entities/import",
                EntityImportRequest(merged.documents, replaceExisting = true, expected = merged.expectedStates()),
            )
            assertTrue(remerge.body<EntityImportResponse>().results.all { it.status == OntologyImportStatus.UPDATED })
            val finalStates = admin.states(ids)
            assertEquals(twoTeams, finalStates.getValue("payments-gateway").team)
            assertEquals(str("*/5 * * * *"), finalStates.getValue("ledger-worker").properties["schedule"])
            assertEquals(str("Zap"), finalStates.getValue("ledger-worker").icon?.let { JsonPrimitive(it) })

            // ── the round trip: export(import(B)) is B again, modulo the one explicit loss ────────
            val names = sample.map { it.metadata.name }.toSet()
            val blueprintsQuery = mapping.pairs.joinToString("&") { "blueprint=${it.blueprint}" }
            val exported = admin.get("$path/catalog-documents?merge=false&$blueprintsQuery").body<CatalogDocumentsResponse>()
            assertRoundTrip(sample, exported, names)
        } finally {
            try {
                mappingId?.let { admin.delete("/api/v1/conversion-mappings/$it") }
                TestEntities.remove(*importedIdentifiers.toTypedArray())
                TestBlueprints.restoreSystemBlueprints()
                SampleData.stripAggregationsForCleanup(requests)
                TestBlueprints.remove(*requests.keys.toTypedArray())
            } finally {
                SampleData.restoreHierarchies(hierarchiesBefore)
            }
        }
    }

    /** The `expected` list a client builds from a conversion: each row that has a document, in row order, 0 = no counterpart. */
    private fun EntityDocumentsResponse.expectedStates() = rows
        .filter { it.status == ConversionRowStatus.CONVERTED || it.status == ConversionRowStatus.CONVERTED_WITH_FINDINGS }
        .mapIndexed { index, row -> ExpectedEntityState(index, row.existingUpdatedAt ?: 0L) }
        .also { assertEquals(documents.size, it.size, "documents pair with the rows that have one") }

    @Test
    fun `converting the Backstage sample over the loaded Port sample with merge loses nothing Port-only`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("convmerge", UserRole.ADMIN)
        val blueprintFiles = SampleData.numberedFiles("blueprints")
        val requests = blueprintFiles.associate { file ->
            blueprintJson.decodeFromString<BlueprintRequest>(file.readText()).let { it.identifier to it }
        }
        val portDocs = SampleData.numberedFiles("entities")
            .flatMap { file -> Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject } }
        val hierarchiesBefore = SampleData.snapshotHierarchies()
        val importedIdentifiers = mutableListOf<String>()
        var mappingId: UInt? = null
        try {
            TestHierarchies.ensure(*SampleData.requiredHierarchies)
            SampleData.loadBlueprints(admin, blueprintFiles)
            val loaded = admin.postJson("/api/v1/entities/import", EntityImportRequest(documents = portDocs)).body<EntityImportResponse>()
            loaded.results.forEach { row -> row.identifier?.let { importedIdentifiers += it } }
            assertEquals(76, loaded.results.size)
            assertTrue(loaded.results.all { it.status == OntologyImportStatus.CREATED }, loaded.results.toString())
            val ids = loaded.results.associate { it.identifier!! to it.id!! }
            val before = admin.states(ids)

            val mappingText = File("../sample-data/port/commerce-payments/mapping/backstage.json").readText()
            val stored = admin.postJson(
                "/api/v1/conversion-mappings",
                ConversionMappingRequest("mrg-${UUID.randomUUID().toString().take(8)}", Json.parseToJsonElement(mappingText)),
            )
            assertEquals(HttpStatusCode.Created, stored.status, stored.bodyAsText())
            val path = "/api/v1/conversion-mappings/${stored.body<ConversionMapping>().id.also { mappingId = it }}"
            val response = admin.postJson("$path/entity-documents", EntityDocumentsRequest(SampleData.backstageSampleFiles()))
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val merged = response.body<EntityDocumentsResponse>()
            assertEquals(42, merged.documents.size)
            assertTrue(merged.rows.all { it.existingId != null }, "every document merges over its Port entity")

            // The merged documents ARE the Port sample's entities (nothing is normalised away - no lossy list, no icon rule,
            // no team flattening): nothing differs since 2.35.0 (the catalog-trimmed definitions and the
            // materialised `external` booleans are gone).
            val portByIdentity = portDocs.associateBy { it.identity() }
            val residual = merged.documents.flatMap { diffs(it, portByIdentity.getValue(it.identity()).let { port -> dropNulls(port) }) }
            assertEquals(
                emptyList(),
                residual.map { it.key() }.sortedBy { it.toString() },
                "nothing else differs: team arrays, lossy relations, icons, schedules and legacy-invoicing's uses all survive",
            )

            // Importing the merged documents rewrites the entities and loses nothing either.
            val imported = admin.postJson("/api/v1/entities/import", EntityImportRequest(merged.documents, replaceExisting = true))
            assertTrue(imported.body<EntityImportResponse>().results.all { it.status == OntologyImportStatus.UPDATED })
            val after = admin.states(ids)
            val keptWhole = listOf("legacy-invoicing", "payments-gateway", "ledger-worker", "catalog-service", "storefront-web", "prod-k8s")
            keptWhole.forEach { id ->
                val was = before.getValue(id)
                val now = after.getValue(id)
                assertEquals(listOf(was.team, was.icon, was.relations), listOf(now.team, now.icon, now.relations), id)
            }
            assertEquals(
                before.getValue("ledger-worker").properties["schedule"],
                after.getValue("ledger-worker").properties["schedule"],
                "a declared-lossy property survives the merge",
            )
        } finally {
            try {
                mappingId?.let { admin.delete("/api/v1/conversion-mappings/$it") }
                TestEntities.remove(*importedIdentifiers.toTypedArray())
                TestBlueprints.restoreSystemBlueprints()
                SampleData.stripAggregationsForCleanup(requests)
                TestBlueprints.remove(*requests.keys.toTypedArray())
            } finally {
                SampleData.restoreHierarchies(hierarchiesBefore)
            }
        }
    }

    private fun dropNulls(port: JsonObject) = JsonObject(
        port.withoutNulls() + mapOf(
            "properties" to port.getValue("properties").jsonObject.withoutNulls(),
            "relations" to port.getValue("relations").jsonObject.withoutNulls(),
        ),
    )

    /** Statuses, the exact findings of the two deliberate rows, and that nothing the sample carries is unaddressed. */
    private fun assertRows(result: EntityDocumentsResponse) {
        assertEquals(42, result.rows.size)
        val withFindings = result.rows.filter { it.findings.isNotEmpty() }
        assertEquals(setOf("catalog-service", "legacy-invoicing"), withFindings.map { it.name }.toSet())
        val deliberate = setOf("catalog-service", "legacy-invoicing")
        assertTrue(result.rows.all { it.status == ConversionRowStatus.CONVERTED || it.name in deliberate })
        val catalog = withFindings.single { it.name == "catalog-service" }
        assertEquals(ConversionRowStatus.CONVERTED_WITH_FINDINGS, catalog.status)
        assertEquals(
            listOf(ConversionFindingCode.REFERENCE_DROPPED to "spec.dependsOn"),
            catalog.findings.map { it.code to it.field },
        )
        assertTrue("template:default/nodejs-service-template" in catalog.findings.single().message, catalog.findings.single().message)
        val legacy = withFindings.single { it.name == "legacy-invoicing" }
        assertEquals(
            listOf(
                ConversionFindingCode.OWNER_DROPPED to "spec.owner",
                ConversionFindingCode.REFERENCE_DROPPED to "spec.dependsOn",
                ConversionFindingCode.REFERENCE_DROPPED to "spec.dependsOn",
            ),
            legacy.findings.map { it.code to it.field },
        )
        assertTrue("billing-squad" in legacy.findings[0].message)
        assertTrue("invoice-archive" in legacy.findings[1].message)
        assertTrue("orders-db" in legacy.findings[2].message)
        val unaddressed = result.rows.filter { it.unmapped.isNotEmpty() }.map { it.name to it.unmapped }
        assertEquals(emptyList(), unaddressed, "the sample carries nothing the mapping does not address")
    }

    private fun CatalogFile.id() = identityOf(this).let { "${it.kind}:${it.namespace}/${it.name}" }

    /**
     * The Backstage documents of the imported workspace equal the sample except for the two documents whose deliberate
     * findings the import could not carry: `catalog-service` loses its `template:` dependency, and `legacy-invoicing`
     * loses its owner (it has no Port team) and so cannot be exported at all.
     */
    private fun assertRoundTrip(sample: List<CatalogFile>, exported: CatalogDocumentsResponse, names: Set<String>) {
        val mine = exported.rows.filter { it.name in names }
        val legacy = mine.single { it.name == "legacy-invoicing" }
        assertEquals(ConversionRowStatus.INVALID, legacy.status, "an entity without a team has no Backstage owner: not exportable")
        assertTrue(legacy.findings.single().message.contains("owner"), legacy.findings.toString())
        val back = exported.files.filter { it.metadata.name in names }.associateBy { it.id() }
        val original = sample.associateBy { it.id() }
        assertEquals(original.keys - "component:default/legacy-invoicing", back.keys)
        val differing = back.keys.filter { back.getValue(it) != original.getValue(it) }
        assertEquals(listOf("component:default/catalog-service"), differing)
        val catalogService = original.getValue("component:default/catalog-service")
        assertEquals("template:default/nodejs-service-template", catalogService.spec.dependsOn.last())
        assertEquals(
            catalogService.copy(spec = catalogService.spec.copy(dependsOn = catalogService.spec.dependsOn.dropLast(1))),
            back.getValue("component:default/catalog-service"),
        )
    }
}
