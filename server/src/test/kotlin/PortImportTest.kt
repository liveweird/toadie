package ch.nokillswit

import ch.nokillswit.ExportFixtures.json
import ch.nokillswit.ImportFixtures.assertAllConverted
import ch.nokillswit.ImportFixtures.component
import ch.nokillswit.ImportFixtures.doc
import ch.nokillswit.ImportFixtures.document
import ch.nokillswit.ImportFixtures.group
import ch.nokillswit.ImportFixtures.importDocs
import ch.nokillswit.ImportFixtures.properties
import ch.nokillswit.ImportFixtures.relations
import ch.nokillswit.ImportFixtures.row
import ch.nokillswit.ImportFixtures.stored
import ch.nokillswit.ImportFixtures.storedIdentity
import ch.nokillswit.ImportFixtures.system
import ch.nokillswit.ImportFixtures.user
import ch.nokillswit.catalog.CatalogLink
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.conversion.ConversionFindingCode
import ch.nokillswit.conversion.ConversionRowStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole Backstage -> Port pipeline (`conversion/PortImport.kt`) over the [ExportFixtures] world:
 * staging (pair selection, namespace rule, identity claims), reference resolution and partition, the
 * footprint merge that must never wipe Port-only data, and the entity import's shape rules. Pure —
 * no database. Each codec's own import half is [ImportCodecsTest]; the real sample is
 * [SampleConversionTest].
 */
class PortImportTest {

    private fun withLabels(file: ch.nokillswit.catalog.CatalogFile, vararg labels: Pair<String, String>) =
        file.copy(metadata = file.metadata.copy(labels = labels.toMap()))

    private val squad = group("squad")
    private val shop = system("shop")

    // ── a whole document ────────────────────────────────────────────────────────────────────

    @Test
    fun `one document becomes one entity document with every pair rule read back`() {
        val web = doc(
            "Component", "web",
            title = "Web", description = "The web.",
            labels = mapOf("tier" to "gold", "pii" to "no"),
            annotations = mapOf("example.com/source" to "url:https://git.example.com/web"),
            tags = listOf("java", "sql"),
            links = listOf(CatalogLink("https://wiki.example.com", "Wiki"), CatalogLink("https://ci.example.com/web", "CI", "dashboard")),
            spec = EntitySpec(type = "website", lifecycle = "production", owner = "group:default/squad", system = "system:default/shop"),
        )
        val result = importDocs(listOf(squad, shop, web))
        assertAllConverted(result)
        assertTrue(result.rows.all { it.status == ConversionRowStatus.CONVERTED }, result.rows.toString())
        val entity = result.document("svc", "web")
        assertEquals("Web", entity.getValue("title").let { (it as JsonPrimitive).content })
        assertEquals("squad", (entity.getValue("team") as JsonPrimitive).content)
        assertEquals(
            json(
                "type" to "website", "lifecycle" to "production", "blurb" to "The web.", "repo" to "https://git.example.com/web",
                "tier" to "gold", "pii" to false, "stack" to listOf("java", "sql"),
                "links" to listOf(json("url" to "https://wiki.example.com", "title" to "Wiki")),
                "ci" to "https://ci.example.com/web", "ext" to false,
            ),
            entity.properties(),
        )
        assertEquals(json("system" to "shop"), entity.relations())
        assertEquals(3, result.documents.size)
        assertEquals(listOf(0, 1, 2), result.rows.map { it.index })
    }

    @Test
    fun `the row names the document it read and the entity it became`() {
        val result = importDocs(listOf(squad, shop, component("web")))
        val row = result.row("web")
        assertEquals("Component", row.kind)
        assertEquals("default", row.namespace)
        assertEquals("svc", row.blueprint)
        assertEquals("web", row.identifier)
        assertNull(row.existingId)
        assertEquals(emptyList(), row.unmapped)
    }

    // ── selection ───────────────────────────────────────────────────────────────────────────

    private val selectorMapping = ExportFixtures.parse(
        """
        {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
          {"blueprint": "plain", "kind": "Component", "select": {"spec": "type", "in": ["library"]}, "rules": [
            {"backstage": {"spec": "type"}, "codec": "constant", "value": "library"},
            {"port": {"meta": "title"}, "backstage": {"metadata": "title"}, "codec": "copy"},
            {"port": {"property": "name"}, "backstage": {"metadata": "description"}, "codec": "copy"}]},
          {"blueprint": "svc", "kind": "Component", "rules": [
            {"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "copy"},
            {"port": {"meta": "title"}, "backstage": {"metadata": "title"}, "codec": "copy"},
            {"port": {"relation": "deps"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}]}
        ]}
        """.trimIndent(),
    )

    @Test
    fun `selector pairs are tried first and the selector-less pair takes the rest`() {
        val files = listOf(
            component("lib", spec = EntitySpec(type = "library")),
            component("app", spec = EntitySpec(type = "service")),
            component("site", spec = EntitySpec(type = "website")),
        )
        val result = importDocs(files, mapping = selectorMapping)
        assertEquals(listOf("plain" to "lib", "svc" to "app", "svc" to "site"), result.rows.map { it.blueprint to it.identifier })
        assertEquals(json("type" to "service"), result.document("svc", "app").properties())
        assertEquals(json(), result.document("plain", "lib").properties(), "the constant takes part in selection only")
    }

    @Test
    fun `a reference reaches the pair of its target document, so dependsOn follows the target's selector`() {
        val files = listOf(
            component("lib", spec = EntitySpec(type = "library")),
            component("app", spec = EntitySpec(type = "service", dependsOn = listOf("component:default/lib"))),
        )
        val result = importDocs(files, mapping = selectorMapping)
        // The only relation rule on dependsOn targets svc; lib is a plain entity, so no rule takes it.
        assertEquals(json(), result.document("svc", "app").relations())
        val finding = result.row("app").findings.single()
        assertEquals(ConversionFindingCode.REFERENCE_DROPPED to "spec.dependsOn", finding.code to finding.field)
        assertTrue("blueprint 'plain'" in finding.message, finding.message)
    }

    @Test
    fun `a document of a kind no pair takes is skipped with NO_PAIR and has no document`() {
        // The fixture mapping has no pair for kind API.
        val api = doc(
            "API", "x",
            spec = EntitySpec(type = "openapi", lifecycle = "production", owner = "group:default/squad", definition = "openapi: 3"),
        )
        val result = importDocs(listOf(api, component("web")))
        assertEquals(ConversionRowStatus.SKIPPED, result.rows[0].status)
        assertEquals(listOf(ConversionFindingCode.NO_PAIR), result.rows[0].findings.map { it.code })
        assertNull(result.rows[0].blueprint)
        assertEquals(1, result.documents.size)
    }

    // ── namespace ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a namespace rule maps the namespace back to its property`() {
        val result = importDocs(listOf(squad, system("ext-shop", namespace = "external"), system("def-shop")))
        assertEquals(JsonPrimitive(true), result.document("sys", "ext-shop").properties()["ext"])
        assertEquals(JsonPrimitive(false), result.document("sys", "def-shop").properties()["ext"])
    }

    @Test
    fun `a namespace the rule does not carry is skipped, never collapsed onto the default one`() {
        val result = importDocs(listOf(squad, system("shop", namespace = "weird")))
        val row = result.row("shop")
        assertEquals(ConversionRowStatus.SKIPPED, row.status)
        assertEquals(listOf(ConversionFindingCode.NAMESPACE_UNMAPPED to "metadata.namespace"), row.findings.map { it.code to it.field })
        assertEquals(1, result.documents.size, "only the group converted")
    }

    @Test
    fun `a pair without a namespace rule accepts only the workspace default namespace`() {
        val result = importDocs(listOf(group("outsider", namespace = "external"), group("insider")))
        assertEquals(ConversionRowStatus.SKIPPED, result.row("outsider").status)
        assertEquals(ConversionFindingCode.NAMESPACE_UNMAPPED, result.row("outsider").findings.single().code)
        assertEquals(ConversionRowStatus.CONVERTED, result.row("insider").status)
        // A different flagged default namespace moves the rule with it, and a blank namespace resolves to it.
        val moved = importDocs(listOf(group("a", namespace = "")), defaultNamespace = "main")
        assertEquals("main", moved.rows.single().namespace)
        assertEquals(ConversionRowStatus.CONVERTED, moved.rows.single().status)
        assertEquals(ConversionRowStatus.SKIPPED, importDocs(listOf(group("a")), defaultNamespace = "main").rows.single().status)
    }

    @Test
    fun `two documents claiming one Port identity are BOTH refused, whatever their order`() {
        val one = system("shop")
        val other = system("shop", namespace = "external")
        listOf(listOf(squad, one, other), listOf(squad, other, one)).forEach { files ->
            val result = importDocs(files)
            val converted = ConversionRowStatus.CONVERTED
            val invalid = ConversionRowStatus.INVALID
            assertEquals(listOf(converted, invalid, invalid), result.rows.map { it.status })
            assertEquals(ConversionFindingCode.IDENTITY_CONFLICT, result.rows[1].findings.single().code)
            assertEquals(ConversionFindingCode.IDENTITY_CONFLICT, result.rows[2].findings.single().code)
            assertEquals(1, result.documents.size, "neither colliding document is emitted")
        }
        // Identifiers fold case, exactly like the unique index.
        val folded = importDocs(listOf(squad, component("Web"), component("web")))
        assertEquals(2, folded.rows.count { it.status == ConversionRowStatus.INVALID })
    }

    @Test
    fun `a reference to a colliding document is dropped with a finding`() {
        val result = importDocs(
            listOf(
                squad, system("shop"), system("shop", namespace = "external"),
                component("web", spec = EntitySpec(system = "system:default/shop")),
            ),
        )
        assertFalse("system" in result.document("svc", "web").relations())
        val findings = result.row("web").findings
        assertTrue(findings.any { it.field == "spec.system" && it.code == ConversionFindingCode.REFERENCE_DROPPED }, findings.toString())
    }

    @Test
    fun `a document that differs from a Port entity only in case is a conflict, not a silent update`() {
        val result = importDocs(listOf(squad, component("Web")), existing = listOf(stored("svc", "web")))
        val row = result.row("Web")
        assertEquals(ConversionRowStatus.INVALID, row.status)
        assertEquals(ConversionFindingCode.IDENTITY_CONFLICT, row.findings.single().code)
        // The same spelling is the ordinary update.
        val sameSpelling = importDocs(listOf(squad, component("web")), existing = listOf(stored("svc", "web")))
        assertEquals(ConversionRowStatus.CONVERTED, sameSpelling.row("web").status)
    }

    @Test
    fun `a document the entity shape rules refuse is INVALID alone and its referrers drop the reference instead`() {
        // The mapping copies the description onto the entity icon (100 characters at most).
        val mapping = ExportFixtures.parse(
            """
            {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
              {"blueprint": "svc", "kind": "Component", "rules": [
                {"port": {"meta": "icon"}, "backstage": {"metadata": "description"}, "codec": "copy"},
                {"port": {"relation": "deps"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}]}]}
            """.trimIndent(),
        )
        val long = component("long").let { it.copy(metadata = it.metadata.copy(description = "x".repeat(150))) }
        val result = importDocs(
            listOf(
                long,
                component("web", spec = EntitySpec(dependsOn = listOf("component:default/long", "component:default/ok"))),
                component("ok"),
            ),
            mapping = mapping,
        )
        assertEquals(ConversionRowStatus.INVALID, result.row("long").status)
        assertEquals(ConversionFindingCode.DOCUMENT_INVALID, result.row("long").findings.single().code)
        assertEquals(setOf("web", "ok"), result.documents.map { (it.getValue("identifier") as JsonPrimitive).content }.toSet())
        assertEquals(JsonArray(listOf(JsonPrimitive("ok"))), result.document("svc", "web").relations()["deps"])
        val finding = result.row("web").findings.single()
        assertEquals(ConversionFindingCode.REFERENCE_DROPPED, finding.code)
        assertTrue("invalid" in finding.message, finding.message)
        assertEquals(ConversionRowStatus.CONVERTED_WITH_FINDINGS, result.row("web").status)
    }

    // ── references ──────────────────────────────────────────────────────────────────────────

    private fun withDeps(vararg refs: String) = component("web", spec = EntitySpec(dependsOn = refs.toList()))

    @Test
    fun `dependsOn is partitioned by the blueprint of what each entry names`() {
        val result = importDocs(
            listOf(
                squad,
                component("peer"),
                doc("Resource", "db", spec = EntitySpec(type = "database", owner = "group:default/squad")),
                withDeps("component:default/peer", "resource:default/db"),
            ),
        )
        val web = result.document("svc", "web")
        assertEquals(JsonArray(listOf(JsonPrimitive("peer"))), web.relations()["deps"])
        assertEquals(JsonArray(listOf(JsonPrimitive("db"))), web.relations()["uses"])
        assertAllConverted(result)
    }

    @Test
    fun `an unresolvable, unmapped or untaken reference is dropped with a finding naming it`() {
        val result = importDocs(
            listOf(squad, shop, withDeps("resource:default/ghost", "db-without-a-kind", "system:default/shop", "template:default/t")),
        )
        val web = result.row("web")
        assertEquals(ConversionRowStatus.CONVERTED_WITH_FINDINGS, web.status)
        assertEquals(
            List(4) { ConversionFindingCode.REFERENCE_DROPPED to "spec.dependsOn" },
            web.findings.map { it.code to it.field },
        )
        assertEquals(json(), result.document("svc", "web").relations())
        assertTrue(web.findings.any { "no document of this batch" in it.message }, web.findings.toString())
        assertTrue(web.findings.any { "no kind" in it.message }, web.findings.toString())
        assertTrue(web.findings.any { "blueprint 'sys'" in it.message }, web.findings.toString())
        assertTrue(web.findings.any { "no pair" in it.message }, web.findings.toString())
    }

    @Test
    fun `an owner that does not resolve is OWNER_DROPPED and leaves the entity without a team`() {
        val result = importDocs(listOf(component("web", owner = "group:default/billing-squad")))
        val row = result.row("web")
        assertEquals(listOf(ConversionFindingCode.OWNER_DROPPED to "spec.owner"), row.findings.map { it.code to it.field })
        assertFalse("team" in result.document("svc", "web"))
    }

    @Test
    fun `an owner naming a User has no team rule to take it and is dropped as an owner`() {
        val result = importDocs(listOf(user("bob"), component("web", owner = "user:default/bob")))
        assertEquals(ConversionFindingCode.OWNER_DROPPED, result.row("web").findings.single().code)
    }

    @Test
    fun `a stored file with no Port entity yet is dropped, and one that has an entity resolves with its stored spelling`() {
        val files = listOf(squad, withDeps("component:default/legacy"))
        val identity = storedIdentity("Component", "Legacy", spec = mapOf("type" to "service"))
        val missing = importDocs(files, stored = listOf(identity))
        assertEquals(ConversionFindingCode.REFERENCE_DROPPED, missing.row("web").findings.single().code)
        assertTrue("no Port entity yet" in missing.row("web").findings.single().message)
        val present = importDocs(files, stored = listOf(identity), existing = listOf(stored("svc", "Legacy")))
        assertAllConverted(present)
        assertEquals(JsonArray(listOf(JsonPrimitive("Legacy"))), present.document("svc", "web").relations()["deps"])
    }

    @Test
    fun `a reference to nothing known falls back to the Port entity of that name under the kind's pair`() {
        val files = listOf(squad, withDeps("component:default/legacy"))
        val result = importDocs(files, existing = listOf(stored("svc", "Legacy")))
        assertAllConverted(result)
        assertEquals(JsonArray(listOf(JsonPrimitive("Legacy"))), result.document("svc", "web").relations()["deps"])
    }

    @Test
    fun `a reference matching Port entities of several pairs of the kind is ambiguous and dropped`() {
        val result = importDocs(
            listOf(squad, withDeps("component:default/twin")),
            existing = listOf(stored("svc", "twin"), stored("plain", "twin")),
            mapping = selectorMapping,
        )
        val finding = result.row("web").findings.single()
        assertEquals(ConversionFindingCode.REFERENCE_DROPPED, finding.code)
        assertTrue("several blueprints" in finding.message, finding.message)
    }

    @Test
    fun `a reference to a document the batch refuses is dropped even when a Port entity exists`() {
        val broken = doc("Component", "broken", spec = EntitySpec(type = "service")) // no owner/lifecycle: refused
        val result = importDocs(listOf(broken, withDeps("component:default/broken")))
        assertEquals(ConversionRowStatus.INVALID, result.row("broken").status)
        assertTrue("skipped or is invalid" in result.row("web").findings.first { it.field == "spec.dependsOn" }.message)
    }

    @Test
    fun `a one-valued relation fed by a list keeps the first entry and reports the rest`() {
        val result = importDocs(
            listOf(squad, system("one"), system("two"), withDeps("system:default/one", "system:default/two")),
            mapping = ExportFixtures.parse(
                """
                {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
                  {"blueprint": "svc", "kind": "Component", "rules": [
                    {"port": {"relation": "system"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}]},
                  {"blueprint": "sys", "kind": "System", "rules": []}]}
                """.trimIndent(),
            ),
        )
        assertEquals(json("system" to "one"), result.document("svc", "web").relations())
        val finding = result.row("web").findings.single()
        assertEquals(ConversionFindingCode.REFERENCE_DROPPED, finding.code)
        assertTrue("'two'" in finding.message, finding.message)
    }

    // ── the footprint merge ─────────────────────────────────────────────────────────────────

    /** The Port entities `existingWeb` relates to, so the stored side is itself valid: the entity rules check targets. */
    private val portDb = stored("dat", "db")
    private val portSre = stored("_team", "sre")

    private val existingWeb = stored(
        "svc", "web",
        title = "Stored title",
        icon = "Rocket",
        team = JsonPrimitive("squad"),
        properties = json(
            "type" to "service", "lifecycle" to "production", "tier" to "gold", "pii" to true,
            "note" to "port-only note", "replicas" to 3,
        ),
        relations = json("system" to "shop", "uses" to listOf("db")),
    )

    @Test
    fun `Port-only properties, relations and the icon survive a merge`() {
        // The mapping below reads neither note/replicas (lossy properties) nor the uses relation (lossy), nor the icon.
        val mapping = ExportFixtures.withoutRule("svc") {
            ((it["port"] as? JsonObject)?.get("relation") as? JsonPrimitive)?.content == "uses"
        }
        val webFile = component("web", spec = EntitySpec(system = "system:default/shop"))
        val result = importDocs(
            listOf(squad, shop, webFile), listOf(existingWeb, portDb), mapping = mapping,
            updatedAt = mapOf(existingWeb.id to 1_700_000_000_123L),
        )
        val web = result.document("svc", "web")
        assertEquals(JsonPrimitive("port-only note"), web.properties()["note"])
        assertEquals(JsonPrimitive(3), web.properties()["replicas"])
        assertEquals(JsonArray(listOf(JsonPrimitive("db"))), web.relations()["uses"], "an unowned relation is untouched")
        assertEquals(JsonPrimitive("Rocket"), web["icon"], "no rule reads the icon, so the stored one stays")
        assertEquals(existingWeb.id, result.row("web").existingId)
        assertEquals(1_700_000_000_123L, result.row("web").existingUpdatedAt, "the token rides beside existingId")
        assertNull(result.row("squad").existingUpdatedAt, "no counterpart, no token")
    }

    @Test
    fun `without merge nothing of the stored entity leaks into the document`() {
        val result = importDocs(
            listOf(squad, shop, component("web")), listOf(existingWeb), merge = false,
            updatedAt = mapOf(existingWeb.id to 42L),
        )
        val web = result.document("svc", "web")
        assertFalse("note" in web.properties())
        assertFalse("icon" in web)
        assertEquals(existingWeb.id, result.row("web").existingId, "named either way: the entity a replaceExisting import overwrites")
        assertEquals(42L, result.row("web").existingUpdatedAt, "and so is its write-version token")
    }

    @Test
    fun `documents are exactly the rows that have one, in row order - the pairing the SPA's expected list relies on`() {
        val result = importDocs(
            listOf(squad, group("outsider", namespace = "external"), shop, component("web")),
            listOf(existingWeb),
            updatedAt = mapOf(existingWeb.id to 7L),
        )
        val withDocument = result.rows.filter {
            it.status == ConversionRowStatus.CONVERTED || it.status == ConversionRowStatus.CONVERTED_WITH_FINDINGS
        }
        assertTrue(result.rows.size > withDocument.size, "the fixture includes a row without a document")
        assertEquals(
            withDocument.map { it.blueprint to it.identifier },
            result.documents.map {
                (it.getValue("blueprint") as JsonPrimitive).content to (it.getValue("identifier") as JsonPrimitive).content
            },
        )
    }

    @Test
    fun `an owned leaf the Backstage side cleanly lacks is removed, and one it has replaces the stored value`() {
        val result = importDocs(
            listOf(squad, shop, withLabels(component("web"), "tier" to "silver")),
            listOf(existingWeb),
        )
        val web = result.document("svc", "web")
        assertEquals(JsonPrimitive("silver"), web.properties()["tier"])
        assertFalse("pii" in web.properties(), "no pii label in Backstage: the owned property goes")
        assertFalse("system" in web.relations(), "no spec.system: the owned relation goes")
        assertFalse("uses" in web.relations())
    }

    @Test
    fun `a value that cannot be converted keeps the stored leaf instead of removing it`() {
        val file = component("web").let {
            it.copy(
                metadata = it.metadata.copy(
                    labels = mapOf("pii" to "maybe", "tier" to "gold"),
                    annotations = mapOf("example.com/source" to "git:somewhere"),
                ),
            )
        }
        val existing = stored(
            "svc", "web", team = JsonPrimitive("squad"),
            properties = json("pii" to true, "repo" to "https://kept.example.com"),
        )
        val result = importDocs(listOf(squad, file), listOf(existing))
        val web = result.document("svc", "web")
        assertEquals(JsonPrimitive(true), web.properties()["pii"])
        assertEquals(JsonPrimitive("https://kept.example.com"), web.properties()["repo"])
        assertEquals(
            setOf(ConversionFindingCode.VALUE_UNMAPPED),
            result.row("web").findings.map { it.code }.toSet(),
        )
    }

    @Test
    fun `an owner that could not be placed keeps the stored team, and dropped references keep a relation nothing else filled`() {
        val web = component("web", owner = "group:default/billing-squad", spec = EntitySpec(dependsOn = listOf("resource:default/ghost")))
        val team = JsonArray(listOf(JsonPrimitive("squad"), JsonPrimitive("sre")))
        val existing = stored("svc", "web", team = team, relations = json("uses" to listOf("db")))
        val result = importDocs(listOf(web), listOf(existing, portDb, portSre, stored("_team", "squad")))
        val entity = result.document("svc", "web")
        assertEquals(JsonArray(listOf(JsonPrimitive("squad"), JsonPrimitive("sre"))), entity["team"])
        assertEquals(
            JsonArray(listOf(JsonPrimitive("db"))),
            entity.relations()["uses"],
            "the dropped dependsOn entry is no evidence the relation is wrong",
        )
        assertEquals(
            setOf(ConversionFindingCode.OWNER_DROPPED, ConversionFindingCode.REFERENCE_DROPPED),
            result.row("web").findings.map { it.code }.toSet(),
        )
    }

    @Test
    fun `the converted owner stands for the FIRST stored team, and replaces only that entry`() {
        fun team(vararg names: String) = JsonArray(names.map { JsonPrimitive(it) })
        fun merged(storedTeam: kotlinx.serialization.json.JsonElement, owner: String = "squad") = importDocs(
            listOf(squad, group("other"), component("web", owner = "group:default/$owner")),
            listOf(stored("svc", "web", team = storedTeam), portSre),
        ).document("svc", "web")["team"]
        // Equal to the first entry: the stored team is kept as it is (the export keeps the first team as the owner).
        assertEquals(team("squad", "sre"), merged(team("squad", "sre")))
        // Different: only the first entry is replaced, the extra teams stay.
        assertEquals(team("other", "sre"), merged(team("squad", "sre"), owner = "other"))
        // The owner appearing LATER in the array is a change of owner too: it moves to the front, no duplicate.
        assertEquals(team("squad"), merged(team("sre", "squad")))
        // A stored scalar is simply replaced, or kept when equal.
        assertEquals(JsonPrimitive("other"), merged(JsonPrimitive("squad"), owner = "other"))
        assertEquals(JsonPrimitive("squad"), merged(JsonPrimitive("squad")))
    }

    @Test
    fun `the stored title stays when the document carries none, the identifier stands in for a new entity`() {
        val merged = importDocs(listOf(squad, component("web")), listOf(existingWeb))
        assertEquals(JsonPrimitive("Stored title"), merged.document("svc", "web")["title"])
        val fresh = importDocs(listOf(squad, component("web")))
        assertEquals(JsonPrimitive("web"), fresh.document("svc", "web")["title"])
        val newTitle = component("web").let { it.copy(metadata = it.metadata.copy(title = "New")) }
        val titled = importDocs(listOf(squad, newTitle), listOf(existingWeb))
        assertEquals(JsonPrimitive("New"), titled.document("svc", "web")["title"])
    }

    // ── validity ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an invalid document is an INVALID row, left out of the documents, and does not stop its siblings`() {
        val noOwner = doc("Component", "web", spec = EntitySpec(type = "service", lifecycle = "production"))
        val result = importDocs(listOf(noOwner, squad))
        assertEquals(ConversionRowStatus.INVALID, result.rows[0].status)
        assertEquals(ConversionFindingCode.DOCUMENT_INVALID, result.rows[0].findings.single().code)
        assertNull(result.rows[0].blueprint)
        assertEquals(1, result.documents.size)
        assertEquals("squad", (result.documents.single().getValue("identifier") as JsonPrimitive).content)
    }

    @Test
    fun `the unmapped list names the Backstage fields no rule of the pair addresses`() {
        val web = component("web").let {
            it.copy(
                metadata = it.metadata.copy(
                    labels = mapOf("stray" to "x", "tier" to "gold"),
                    tags = listOf("rust", "java"),
                    links = listOf(CatalogLink("https://l.example.com")),
                ),
                spec = it.spec.copy(dependencyOf = listOf("component:default/other")),
            )
        }
        val sysDoc = system("shop").let { it.copy(metadata = it.metadata.copy(description = "desc")) }
        val result = importDocs(listOf(squad, sysDoc, web))
        assertEquals(setOf("metadata.labels.stray", "metadata.tags.rust", "spec.dependencyOf"), result.row("web").unmapped.toSet())
        assertEquals(listOf("metadata.description"), result.row("shop").unmapped)
    }

    @Test
    fun `a group's derived and export-only fields are covered by their rules, not unmapped`() {
        val result = importDocs(
            listOf(
                doc(
                    "Group", "squad",
                    spec = EntitySpec(
                        type = "team", children = listOf("group:default/kid"), members = listOf("user:default/bob"),
                        profile = ch.nokillswit.catalog.EntityProfile(displayName = "Squad"),
                    ),
                ),
            ),
        )
        assertEquals(emptyList(), result.row("squad").unmapped)
    }
}
