package ch.nokillswit

import ch.nokillswit.ExportFixtures.convert
import ch.nokillswit.ExportFixtures.entity
import ch.nokillswit.ExportFixtures.json
import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogFileMetadata
import ch.nokillswit.catalog.CatalogLink
import ch.nokillswit.catalog.CatalogSource
import ch.nokillswit.catalog.EntityProfile
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.conversion.ConversionFindingCode
import ch.nokillswit.conversion.ConversionRowStatus
import ch.nokillswit.conversion.validateMapping
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole Port -> Backstage pipeline (`conversion/BackstageExport.kt`) over the [ExportFixtures]
 * world: identities, documents, the derive pass over the full snapshot, the footprint merge and the
 * catalog validation that keeps a refused document out of `files`. Pure — no database. Each codec's
 * own export half is [CodecsTest].
 */
class BackstageExportTest {

    private fun source(id: UInt, file: CatalogFile) = CatalogSource(id = id, file = file, sourceUrl = null)

    /** A valid `svc` entity owned by the team `squad` (list it beside [squad] so the owner reference resolves). */
    private fun service(identifier: String, relations: kotlinx.serialization.json.JsonObject = json()) =
        entity(
            "svc", identifier,
            properties = json("type" to "service", "lifecycle" to "production"), relations = relations, team = listOf("squad"),
        )

    @Test
    fun `the fixture mapping is itself a valid mapping`() {
        val verdict = validateMapping(Json.parseToJsonElement(ExportFixtures.MAPPING), ExportFixtures.blueprints, ExportFixtures.registry)
        assertEquals(emptyList(), verdict.findings)
    }

    @Test
    fun `one entity becomes one document with every pair rule applied`() {
        val result = convert(
            listOf(
                entity("_team", "squad", title = "Squad", properties = json("type" to "team")),
                entity("sys", "shop", title = "Shop", properties = json("type" to "product"), team = listOf("squad")),
                entity(
                    "svc", "web", title = "Web",
                    properties = json(
                        "type" to "website", "lifecycle" to "production", "blurb" to "The web.", "repo" to "https://git.example.com/web",
                        "tier" to "gold", "pii" to false, "stack" to listOf("java", "sql"), "ci" to "https://ci.example.com/web",
                        "links" to listOf(json("title" to "Wiki", "url" to "https://wiki.example.com")),
                    ),
                    relations = json("system" to "shop"),
                    team = listOf("squad"),
                ),
            ),
        )
        assertEquals(listOf("squad", "shop", "web"), result.rows.map { it.identifier })
        assertTrue(result.rows.all { it.status == ConversionRowStatus.CONVERTED }, result.rows.toString())
        val web = result.files.single { it.metadata.name == "web" }
        assertEquals(
            CatalogFile(
                kind = "Component",
                metadata = CatalogFileMetadata(
                    name = "web", namespace = "default", title = "Web", description = "The web.",
                    labels = mapOf("tier" to "gold", "pii" to "no"),
                    annotations = mapOf("example.com/source" to "url:https://git.example.com/web"),
                    tags = listOf("java", "sql"),
                    links = listOf(
                        CatalogLink("https://wiki.example.com", "Wiki"),
                        CatalogLink("https://ci.example.com/web", "CI", "dashboard"),
                    ),
                ),
                spec = EntitySpec(
                    type = "website", lifecycle = "production", owner = "group:default/squad", system = "system:default/shop",
                ),
            ),
            web,
        )
        assertEquals(
            EntitySpec(type = "team", profile = EntityProfile(displayName = "Squad"), children = emptyList()),
            result.files.single { it.metadata.name == "squad" }.spec,
        )
    }

    @Test
    fun `a reference carries the TARGET's namespace, not the referrer's`() {
        val result = convert(
            listOf(
                entity("_team", "squad", properties = json("type" to "team")),
                entity("sys", "acquirer", properties = json("type" to "capability", "ext" to true), team = listOf("squad")),
                service("web", relations = json("system" to "acquirer")),
            ),
        )
        val acquirer = result.files.single { it.metadata.name == "acquirer" }
        assertEquals("external", acquirer.metadata.namespace)
        assertEquals("system:external/acquirer", result.files.single { it.metadata.name == "web" }.spec.system)
        // The owner is in the default namespace while the system is in external: each ref names its own target's.
        assertEquals("group:default/squad", acquirer.spec.owner)
        val row = result.rows.single { it.identifier == "acquirer" }
        assertEquals(Triple("System", "external", "acquirer"), Triple(row.kind, row.namespace, row.name))
    }

    @Test
    fun `an entity with no namespace rule value lands in the workspace default namespace`() {
        val result = convert(
            listOf(squad, entity("sys", "s", properties = json("type" to "product"), team = listOf("squad"))),
            defaultNamespace = "prod",
            registry = ExportFixtures.registry.copy(namespaces = setOf("default", "external", "prod")),
        )
        assertEquals("prod", result.files.single { it.metadata.name == "s" }.metadata.namespace)
        assertEquals("group:prod/squad", result.files.single { it.metadata.name == "s" }.spec.owner)
    }

    @Test
    fun `several reference rules onto one field concatenate in rule order then entity order, without duplicates`() {
        val result = convert(
            listOf(
                entity("_team", "squad", properties = json("type" to "team")),
                entity("dat", "db1", team = listOf("squad")),
                entity("dat", "db2", team = listOf("squad")),
                entity("svc", "a", properties = json("type" to "service", "lifecycle" to "production"), team = listOf("squad")),
                entity("svc", "b", properties = json("type" to "service", "lifecycle" to "production"), team = listOf("squad")),
                entity(
                    "svc", "c",
                    properties = json("type" to "service", "lifecycle" to "production"),
                    relations = json("uses" to listOf("db2", "db1"), "deps" to listOf("b", "a", "b")),
                    team = listOf("squad"),
                ),
            ),
        )
        // `deps` is declared before `uses` in the mapping, so its (de-duplicated) entries come first.
        assertEquals(
            listOf("component:default/b", "component:default/a", "resource:default/db2", "resource:default/db1"),
            result.files.single { it.metadata.name == "c" }.spec.dependsOn,
        )
    }

    @Test
    fun `derive reads the whole snapshot - a filtered-out child still appears under its shown parent`() {
        val result = convert(
            listOf(
                entity("_team", "boss", properties = json("type" to "team")),
                entity("_team", "kid", properties = json("type" to "team"), relations = json("parent" to "boss"), shown = false),
                entity("usr", "ann", relations = json("team" to listOf("boss", "kid")), shown = false),
            ),
        )
        assertEquals(listOf("boss"), result.rows.map { it.identifier })
        val boss = result.files.single()
        assertEquals(listOf("group:default/kid"), boss.spec.children)
        assertEquals(listOf("user:default/ann"), boss.spec.members)
    }

    @Test
    fun `a user's memberOf and a group's empty children stay present even when empty`() {
        val result = convert(listOf(entity("usr", "loner"), entity("_team", "solo", properties = json("type" to "team"))))
        assertEquals(emptyList(), result.files.single { it.kind == "User" }.spec.memberOf)
        assertEquals(emptyList(), result.files.single { it.kind == "Group" }.spec.children)
    }

    @Test
    fun `several teams keep the first as owner and the row says so`() {
        val result = convert(
            listOf(
                entity("_team", "t1", properties = json("type" to "team")),
                entity("_team", "t2", properties = json("type" to "team")),
                entity("sys", "s", properties = json("type" to "product"), team = listOf("t1", "t2")),
            ),
        )
        val row = result.rows.single { it.identifier == "s" }
        assertEquals(ConversionRowStatus.CONVERTED_WITH_FINDINGS, row.status)
        assertEquals(listOf(ConversionFindingCode.OWNER_TRUNCATED), row.findings.map { it.code })
        assertEquals("group:default/t1", result.files.single { it.metadata.name == "s" }.spec.owner)
    }

    @Test
    fun `a dangling relation is dropped with TARGET_MISSING, and an unmapped target blueprint with TARGET_UNMAPPED`() {
        val dangling = convert(
            listOf(
                entity("_team", "squad", properties = json("type" to "team")),
                service("a", relations = json("system" to "gone")),
            ),
        )
        assertEquals(listOf(ConversionFindingCode.TARGET_MISSING), dangling.rows.single { it.identifier == "a" }.findings.map { it.code })
        assertNull(dangling.files.single { it.metadata.name == "a" }.spec.system)

        val withoutSystemPair = ExportFixtures.withoutPair("sys")
        assertTrue(withoutSystemPair.pairs.none { it.blueprint == "sys" })
        val unmapped = convert(
            listOf(
                entity("_team", "squad", properties = json("type" to "team")),
                entity("sys", "shop", properties = json("type" to "product")),
                service("a", relations = json("system" to "shop")),
            ),
            mapping = withoutSystemPair,
        )
        assertEquals(ConversionRowStatus.SKIPPED, unmapped.rows.single { it.identifier == "shop" }.status)
        assertEquals(listOf(ConversionFindingCode.TARGET_UNMAPPED), unmapped.rows.single { it.identifier == "a" }.findings.map { it.code })
    }

    @Test
    fun `an entity of an unmapped blueprint is a SKIPPED row with no document and no identity`() {
        val result = convert(listOf(entity("plain", "p", properties = json("name" to "x"))))
        assertEquals(emptyList(), result.files)
        val row = result.rows.single()
        assertEquals(ConversionRowStatus.SKIPPED, row.status)
        assertEquals(listOf(ConversionFindingCode.NO_PAIR), row.findings.map { it.code })
        assertEquals(Triple(null, null, null), Triple(row.kind, row.namespace, row.name))
    }

    @Test
    fun `unmapped lists what the entity carries that no rule converts`() {
        val result = convert(
            listOf(
                entity("_team", "squad", properties = json("type" to "team")),
                entity(
                    "svc", "a",
                    properties = json("type" to "service", "lifecycle" to "production", "note" to "n", "replicas" to 3, "stack" to null),
                    team = listOf("squad"),
                ),
            ),
        )
        assertEquals(listOf("properties.note", "properties.replicas"), result.rows.single { it.identifier == "a" }.unmapped)
        assertEquals(emptyList(), result.rows.single { it.identifier == "squad" }.unmapped)
    }

    // ── validation ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a document the descriptor format refuses is an INVALID row and is not in files`() {
        val result = convert(
            listOf(
                entity("_team", "squad", properties = json("type" to "team")),
                entity("svc", "bad name", properties = json("type" to "service", "lifecycle" to "production"), team = listOf("squad")),
                entity("svc", "good", properties = json("type" to "service", "lifecycle" to "production"), team = listOf("squad")),
                entity("svc", "no-type", properties = json("lifecycle" to "production"), team = listOf("squad")),
            ),
        )
        val statuses = result.rows.associate { it.identifier to it.status }
        assertEquals(ConversionRowStatus.INVALID, statuses["bad name"])
        assertEquals(ConversionRowStatus.INVALID, statuses["no-type"])
        assertEquals(ConversionRowStatus.CONVERTED, statuses["good"])
        assertEquals(setOf("squad", "good"), result.files.map { it.metadata.name }.toSet())
        val invalid = result.rows.single { it.identifier == "no-type" }
        assertEquals(listOf(ConversionFindingCode.DOCUMENT_INVALID), invalid.findings.map { it.code })
        assertTrue("spec.type" in invalid.findings.single().message, invalid.findings.single().message)
    }

    @Test
    fun `entities converting to one identity are ALL INVALID, whatever the filter, and references to them are dropped`() {
        val entities = listOf(
            squad,
            service("twin"),
            entity("svc", "TWIN", properties = json("type" to "service", "lifecycle" to "production"), team = listOf("squad")),
            service("fine", relations = json("deps" to listOf("twin"))),
        )
        val result = convert(entities)
        val byId = result.rows.associateBy { it.identifier }
        listOf("twin", "TWIN").forEach {
            assertEquals(ConversionRowStatus.INVALID, byId.getValue(it).status, it)
            assertEquals(listOf(ConversionFindingCode.IDENTITY_CONFLICT), byId.getValue(it).findings.map { f -> f.code })
        }
        assertEquals(setOf("squad", "fine"), result.files.map { it.metadata.name }.toSet())
        assertEquals(listOf(ConversionFindingCode.TARGET_INVALID), byId.getValue("fine").findings.map { it.code })
        assertEquals(emptyList(), result.files.single { it.metadata.name == "fine" }.spec.dependsOn)

        // A filter that selects only ONE of the two does not hide the clash: the survivor is still refused.
        val onlyOne = convert(entities.map { if (it.identifier == "TWIN") it.copy(shown = false) else it })
        assertEquals(ConversionRowStatus.INVALID, onlyOne.rows.single { it.identifier == "twin" }.status)
    }

    @Test
    fun `a library and a service of the same name clash across blueprints`() {
        val result = convert(
            listOf(
                squad,
                entity("lib", "commons", team = listOf("squad")),
                service("commons"),
            ),
        )
        assertEquals(
            listOf(ConversionRowStatus.CONVERTED, ConversionRowStatus.INVALID, ConversionRowStatus.INVALID),
            result.rows.map { it.status },
        )
        // Narrowed to the library alone, the service of the same name is still known, so the clash still holds.
        val narrowed = convert(
            listOf(
                squad,
                entity("lib", "commons", team = listOf("squad")),
                service("commons").copy(shown = false),
            ),
        )
        assertEquals(ConversionRowStatus.INVALID, narrowed.rows.single { it.identifier == "commons" }.status)
        assertEquals(emptyList(), narrowed.files.filter { it.metadata.name == "commons" })
    }

    @Test
    fun `an invalid Backstage name makes its own row INVALID and only drops the references to it`() {
        val result = convert(
            listOf(
                entity("_team", "squad", properties = json("type" to "team")),
                entity("usr", "jane@acme.com", properties = json("mail" to "jane@acme.com"), relations = json("team" to listOf("squad"))),
            ),
        )
        val byId = result.rows.associateBy { it.identifier }
        assertEquals(ConversionRowStatus.INVALID, byId.getValue("jane@acme.com").status)
        assertEquals(listOf(ConversionFindingCode.DOCUMENT_INVALID), byId.getValue("jane@acme.com").findings.map { it.code })
        // The Group stays valid: its derived members lose the unwritable user and say so.
        assertEquals(ConversionRowStatus.CONVERTED_WITH_FINDINGS, byId.getValue("squad").status)
        assertEquals(listOf(ConversionFindingCode.TARGET_INVALID), byId.getValue("squad").findings.map { it.code })
        assertEquals(emptyList(), result.files.single().spec.members)
    }

    @Test
    fun `a namespace outside the dictionary makes the row INVALID, and a namespace rule's findings reach the row`() {
        val nowhere = convert(listOf(squad), defaultNamespace = "nowhere")
        val row = nowhere.rows.single()
        assertEquals(ConversionRowStatus.INVALID, row.status)
        assertTrue("namespaces dictionary" in row.findings.single().message, row.findings.toString())
        assertEquals(emptyList(), nowhere.files)

        val maybe = entity("sys", "s", properties = json("type" to "product", "ext" to "maybe"), team = listOf("squad"))
        val unlisted = convert(listOf(squad, maybe))
        val sys = unlisted.rows.single { it.identifier == "s" }
        assertEquals(ConversionRowStatus.CONVERTED_WITH_FINDINGS, sys.status)
        assertEquals(listOf(ConversionFindingCode.VALUE_UNMAPPED), sys.findings.map { it.code })
        assertEquals("default", sys.namespace)
    }

    @Test
    fun `rows are indexed in order and filtered-out entities have none`() {
        val result = convert(
            listOf(
                entity("_team", "squad", properties = json("type" to "team"), shown = false),
                entity("plain", "p"),
                entity("svc", "a", properties = json("type" to "service", "lifecycle" to "production"), team = listOf("squad")),
            ),
        )
        assertEquals(listOf(0, 1), result.rows.map { it.index })
        assertEquals(listOf("p", "a"), result.rows.map { it.identifier })
        // The hidden team still resolved as the owner reference.
        assertEquals("group:default/squad", result.files.single().spec.owner)
    }

    // ── footprint merge ─────────────────────────────────────────────────────────────────────

    private val squad = entity("_team", "squad", properties = json("type" to "team"))
    private val db = entity("dat", "db", team = listOf("squad"))

    @Test
    fun `merge replaces the leaves the pair owns and preserves everything else`() {
        val stored = ExportFixtures.file(
            kind = "Component",
            name = "web",
            metadata = CatalogFileMetadata(
                name = "web",
                title = "Old title",
                description = "Hand-written description.",
                labels = mapOf("tier" to "silver", "pii" to "yes", "team-label" to "mine"),
                annotations = mapOf("example.com/source" to "url:old", "backstage.io/other" to "keep"),
                tags = listOf("java", "handmade"),
                links = listOf(
                    CatalogLink("https://old.example.com", "CI"),
                    CatalogLink("https://mine.example.com", "Mine"),
                    CatalogLink("https://rest.example.com", "Rest"),
                ),
            ),
            spec = EntitySpec(
                type = "service", lifecycle = "experimental", owner = "group:default/someone",
                dependsOn = listOf("resource:default/db", "component:default/legacy", "template:default/starter", "resource:default/ghost"),
                subcomponentOf = "component:default/parent",
            ),
        )
        val result = convert(
            listOf(
                squad, db,
                entity("svc", "legacy-target", properties = json("type" to "service", "lifecycle" to "production"), team = listOf("squad")),
                entity(
                    "svc", "web", title = "New title",
                    properties = json(
                        "type" to "website", "lifecycle" to "production", "tier" to "gold", "stack" to listOf("kotlin"),
                        "ci" to "https://ci.example.com/web",
                    ),
                    relations = json("uses" to listOf("db")),
                    team = listOf("squad"),
                ),
            ),
            existing = listOf(source(77u, stored)),
        )
        val row = result.rows.single { it.identifier == "web" }
        assertEquals(77u, row.existingId)
        val merged = result.files.single { it.metadata.name == "web" }
        // Owned leaves come from Port...
        assertEquals("New title", merged.metadata.title)
        assertEquals("website", merged.spec.type)
        assertEquals("production", merged.spec.lifecycle)
        assertEquals("group:default/squad", merged.spec.owner)
        // ...the label `tier` is owned and re-derived, `pii` is owned and now ABSENT in Port, the foreign key stays...
        assertEquals(mapOf("team-label" to "mine", "tier" to "gold"), merged.metadata.labels)
        assertEquals(mapOf("backstage.io/other" to "keep"), merged.metadata.annotations, "an owned annotation absent in Port is removed")
        // ...tags by category: the Languages tags are owned (java dropped, kotlin added), the hand-made tag survives...
        assertEquals(listOf("handmade", "kotlin"), merged.metadata.tags)
        // ...links: owned titles replaced, the `links: rest` rule owns every other link too.
        assertEquals(listOf(CatalogLink("https://ci.example.com/web", "CI", "dashboard")), merged.metadata.links)
        // ...the svc pair OWNS the description (its `blurb` property), so with no blurb in Port the stored one is removed; the
        // unowned spec field survives; the foreign dependsOn entries survive, the owned ones are re-derived.
        assertNull(merged.metadata.description)
        assertEquals("component:default/parent", merged.spec.subcomponentOf)
        assertEquals(
            listOf("component:default/legacy", "template:default/starter", "resource:default/ghost", "resource:default/db"),
            merged.spec.dependsOn,
        )
    }

    @Test
    fun `merge classifies a stored dependsOn entry by the blueprint it resolves to, not by its text`() {
        // `component:default/legacy-target` resolves to a converted svc entity -> owned by `deps`, so it is
        // dropped when Port no longer lists it; `component:default/unknown` resolves to nothing -> kept.
        val stored = ExportFixtures.file(
            kind = "Component", name = "web",
            spec = EntitySpec(type = "service", lifecycle = "production", owner = "group:default/squad",
                dependsOn = listOf("component:default/legacy-target", "component:default/unknown")),
        )
        val result = convert(
            listOf(
                squad,
                entity("svc", "legacy-target", properties = json("type" to "service", "lifecycle" to "production"), team = listOf("squad")),
                entity("svc", "web", properties = json("type" to "service", "lifecycle" to "production"), team = listOf("squad")),
            ),
            existing = listOf(source(5u, stored)),
        )
        assertEquals(listOf("component:default/unknown"), result.files.single { it.metadata.name == "web" }.spec.dependsOn)
    }

    @Test
    fun `without a stored file nothing is merged, and a stored file of another identity is left alone`() {
        val other = ExportFixtures.file(kind = "Component", name = "elsewhere", spec = EntitySpec(type = "x"))
        val entities = listOf(squad, service("web"))
        val unmerged = convert(entities)
        val merged = convert(entities, existing = listOf(source(9u, other)))
        assertEquals(unmerged.files, merged.files)
        assertNull(merged.rows.single { it.identifier == "web" }.existingId)
    }

    @Test
    fun `merge keeps a Group's unowned profile fields and a stored description`() {
        val stored = ExportFixtures.file(
            kind = "Group", name = "squad",
            metadata = CatalogFileMetadata(name = "squad", description = "Keep me"),
            spec = EntitySpec(
                type = "org-unit",
                profile = EntityProfile(displayName = "Old", email = "keep@example.com", picture = "https://p.example.com/x.png"),
                children = listOf("group:default/gone"),
            ),
        )
        val result = convert(listOf(squad), existing = listOf(source(3u, stored)))
        val merged = result.files.single()
        assertEquals("Keep me", merged.metadata.description)
        assertEquals(
            EntityProfile(displayName = "squad", email = "keep@example.com", picture = "https://p.example.com/x.png"),
            merged.spec.profile,
        )
        assertEquals("team", merged.spec.type)
        // `children` is derive-owned for group references: the stored entry resolves to no converted entity, so it survives.
        assertEquals(listOf("group:default/gone"), merged.spec.children)
    }

    @Test
    fun `a merged document is validated like any other`() {
        // The stored file carries a label value the descriptor format refuses; the owned `tier` label is replaced
        // but the foreign one is kept, so the merged document is INVALID and stays out of files.
        val stored = ExportFixtures.file(
            kind = "Component", name = "web",
            metadata = CatalogFileMetadata(name = "web", labels = mapOf("foreign" to "not valid value!")),
            spec = EntitySpec(type = "service"),
        )
        val result = convert(
            listOf(squad, service("web")),
            existing = listOf(source(4u, stored)),
        )
        assertEquals(ConversionRowStatus.INVALID, result.rows.single { it.identifier == "web" }.status)
        assertEquals(listOf("squad"), result.files.map { it.metadata.name })
    }

    @Test
    fun `the export is a pure function - running it twice gives the same answer`() {
        val entities = listOf(squad, db, service("web", relations = json("uses" to listOf("db"))))
        assertEquals(convert(entities), convert(entities))
    }
}
