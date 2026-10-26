package ch.nokillswit

import ch.nokillswit.ExportFixtures.json
import ch.nokillswit.ImportFixtures.component
import ch.nokillswit.ImportFixtures.doc
import ch.nokillswit.ImportFixtures.document
import ch.nokillswit.ImportFixtures.group
import ch.nokillswit.ImportFixtures.importDocs
import ch.nokillswit.ImportFixtures.properties
import ch.nokillswit.ImportFixtures.relations
import ch.nokillswit.ImportFixtures.row
import ch.nokillswit.ImportFixtures.stored
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.catalog.CatalogLink
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.conversion.ConversionFindingCode
import ch.nokillswit.conversion.ConversionRowStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The never-lose-Port-data cases of the import merge, one per way an earlier version of the engine could
 * have removed something it had no evidence about: a partly resolved many-valued relation, two rules
 * reading one field, a tag the registry does not know, `links: rest` items carrying more than the
 * Backstage side can, a placeholder namespace — plus the entity value rules that keep a document the
 * strict import would refuse out of the batch (and its referrers' relations to it).
 */
class PortImportSafetyTest {

    private fun names(vararg values: String): JsonArray = JsonArray(values.map { JsonPrimitive(it) })
    private val squad = group("squad")

    private fun deps(vararg refs: String) = component("web", spec = EntitySpec(dependsOn = refs.toList()))

    // ── B1: a partly resolved many-valued relation ──────────────────────────────────────────

    @Test
    fun `a many-valued relation keeps the stored targets when only some entries resolve`() {
        val existing = stored("svc", "web", relations = json("deps" to listOf("gone-from-backstage", "peer")))
        val result = importDocs(
            listOf(squad, component("peer"), deps("component:default/peer", "component:default/ghost")),
            listOf(existing, stored("svc", "gone-from-backstage"), stored("svc", "peer")),
        )
        // `ghost` is dropped; `peer` resolves; the stored target the Backstage side no longer lists survives: our
        // failure to place `ghost` is no evidence the relation is wrong. Stored order first, nothing duplicated.
        assertEquals(names("gone-from-backstage", "peer"), result.document("svc", "web").relations()["deps"])
        assertEquals(ConversionFindingCode.REFERENCE_DROPPED, result.row("web").findings.single().code)
    }

    @Test
    fun `a stored target is kept when the entry naming it is refused in the same batch`() {
        // `long` is refused (its icon, copied from the description, is over-long), `ok` resolves.
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
            listOf(long, component("ok"), deps("component:default/long", "component:default/ok")),
            listOf(stored("svc", "web", relations = json("deps" to listOf("long", "ok"))), stored("svc", "long"), stored("svc", "ok")),
            mapping = mapping,
        )
        assertEquals(ConversionRowStatus.INVALID, result.row("long").status)
        assertEquals(names("long", "ok"), result.document("svc", "web").relations()["deps"], "both stored targets survive")
    }

    @Test
    fun `without a dropped entry the relation is exactly what the Backstage side lists`() {
        val existing = stored("svc", "web", relations = json("deps" to listOf("old", "peer")))
        val result = importDocs(
            listOf(squad, component("peer"), deps("component:default/peer")),
            listOf(existing, stored("svc", "old"), stored("svc", "peer")),
        )
        assertEquals(names("peer"), result.document("svc", "web").relations()["deps"])
    }

    // ── B2: two rules, one field, one target ────────────────────────────────────────────────

    private val dualBlueprints = ExportFixtures.blueprints + mapOf(
        "dual" to blueprintJson.decodeFromString<BlueprintDefinition>(
            """{"schema": {"properties": {}},
                "relations": {"a": {"title": "A", "target": "svc", "required": false, "many": true},
                              "b": {"title": "B", "target": "svc", "required": false, "many": true},
                              "boss": {"title": "Boss", "target": "_team", "required": false, "many": false}}}""",
        ),
    )

    private fun dualMapping(vararg rules: String) = ExportFixtures.parse(
        """{"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
             {"blueprint": "dual", "kind": "Component", "select": {"spec": "type", "in": ["service"]}, "rules": [${rules.joinToString(",")}]},
             {"blueprint": "svc", "kind": "Component", "rules": []},
             {"blueprint": "_team", "kind": "Group", "rules": []}]}""",
    )

    /** A Component the `dual` mapping files under `svc` (its type is not the `dual` selector's). */
    private fun peer(name: String) = component(name, spec = EntitySpec(type = "website"))

    private fun ref(port: String, field: String) = """{"port": $port, "backstage": {"spec": "$field"}, "codec": "ref"}"""

    @Test
    fun `a rule shadowed by an earlier rule with the same target leaves its stored relation alone`() {
        // The validator refuses this mapping (AMBIGUOUS_REF_PARTITION); the engine must still not empty the second rule's relation.
        val mapping = dualMapping(ref("""{"relation": "a"}""", "dependsOn"), ref("""{"relation": "b"}""", "dependsOn"))
        val result = importDocs(
            listOf(peer("peer"), deps("component:default/peer")),
            listOf(stored("dual", "web", relations = json("b" to listOf("keepme"))), stored("svc", "keepme")),
            mapping = mapping,
            blueprints = dualBlueprints,
        )
        val web = result.document("dual", "web")
        assertEquals(names("peer"), web.relations()["a"])
        assertEquals(names("keepme"), web.relations()["b"], "the shadowed rule's relation is neither emptied nor filled")
    }

    @Test
    fun `a team rule shadowed by a relation to _team on the same field keeps the stored team`() {
        val mapping = dualMapping(ref("""{"relation": "boss"}""", "owner"), ref("""{"meta": "team"}""", "owner"))
        val result = importDocs(
            listOf(squad, component("web")),
            listOf(stored("dual", "web", team = JsonPrimitive("sre")), stored("_team", "sre")),
            mapping = mapping,
            blueprints = dualBlueprints,
        )
        val web = result.document("dual", "web")
        assertEquals(JsonPrimitive("squad"), web.relations()["boss"])
        assertEquals(JsonPrimitive("sre"), web["team"])
    }

    // ── one leaf, two fields ────────────────────────────────────────────────────────────────

    @Test
    fun `two fields feeding one relation union their entries and never drop a stored target`() {
        // The validator refuses it (DUPLICATE_IMPORT_TARGET); the engine still must not let one field's value erase
        // what the other field's failure to resolve says nothing about.
        val mapping = dualMapping(ref("""{"relation": "a"}""", "dependsOn"), ref("""{"relation": "a"}""", "subcomponentOf"))
        val file = component(
            "web",
            spec = EntitySpec(dependsOn = listOf("component:default/peer"), subcomponentOf = "component:default/ghost"),
        )
        val result = importDocs(
            listOf(peer("peer"), file),
            listOf(stored("dual", "web", relations = json("a" to listOf("old"))), stored("svc", "old"), stored("svc", "peer")),
            mapping = mapping,
            blueprints = dualBlueprints,
        )
        assertEquals(names("old", "peer"), result.document("dual", "web").relations()["a"])
    }

    @Test
    fun `a stored target whose entity no longer exists is not carried over by a union`() {
        val result = importDocs(
            listOf(squad, component("peer"), deps("component:default/peer", "component:default/ghost")),
            listOf(stored("svc", "web", relations = json("deps" to listOf("deleted-long-ago", "peer"))), stored("svc", "peer")),
        )
        assertEquals(names("peer"), result.document("svc", "web").relations()["deps"])
        assertEquals(ConversionRowStatus.CONVERTED_WITH_FINDINGS, result.row("web").status)
    }

    // ── tags the registry does not know, and values no tag can express ───────────────────────

    private fun withTags(vararg tags: String) = component("web").let { it.copy(metadata = it.metadata.copy(tags = tags.toList())) }

    // `cobol` is a legal value of the Port property but no registered tag names it: Backstage can never carry it.
    private val tagBlueprints = ExportFixtures.blueprints + mapOf(
        "tagbp" to blueprintJson.decodeFromString<BlueprintDefinition>(
            """{"schema": {"properties": {"stack": {"type": "array", "uniqueItems": true,
                 "items": {"type": "string", "enum": ["java", "kotlin", "sql", "cobol"]}}}}}""",
        ),
    )

    private val tagMapping = ExportFixtures.parse(
        """
        {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
          {"blueprint": "tagbp", "kind": "Component", "rules": [
            {"port": {"property": "stack"}, "backstage": {"tags": "Languages"}, "codec": "tagCategory"}]}]}
        """.trimIndent(),
    )

    private fun tagged(vararg tags: String, values: List<String>): JsonElement? =
        importDocs(
            listOf(withTags(*tags)),
            listOf(stored("tagbp", "web", properties = json("stack" to values))),
            mapping = tagMapping,
            blueprints = tagBlueprints,
        ).documents.single().properties()["stack"]

    @Test
    fun `a tag-backed array keeps the stored values no registered tag of its categories can express`() {
        // `java` is expressible and its tag is missing: it goes. `rust` (unknown to the registry) contributes nothing, and a
        // known tag next to it still counts.
        assertEquals(names("cobol", "kotlin"), tagged("kotlin", "rust", values = listOf("java", "cobol")))
        assertEquals(names("cobol"), tagged("rust", values = listOf("java", "cobol")))
        assertEquals(names("java", "cobol"), tagged("java", "rust", values = listOf("java", "cobol")), "stored order, nothing duplicated")
    }

    @Test
    fun `a tag-backed array with nothing left and nothing inexpressible is removed`() {
        assertEquals(null, tagged(values = listOf("java")))
        assertEquals(null, tagged("rust", values = listOf("java")))
        assertEquals(names("sql"), tagged("sql", values = listOf("java")))
    }

    // ── S7: links: rest ─────────────────────────────────────────────────────────────────────

    @Test
    fun `links rest keeps the extra keys of a stored item with the same url and the items the export could not emit`() {
        val existing = stored(
            "svc", "web",
            properties = json(
                "links" to listOf(
                    json("url" to "https://wiki", "title" to "Old title", "tag" to "docs"),
                    json("title" to "no url yet"),
                    json("url" to "https://gone", "title" to "removed in Backstage"),
                ),
            ),
        )
        val web = component("web").let {
            val links = listOf(CatalogLink("https://wiki", "New title"), CatalogLink("https://new", "New"))
            it.copy(metadata = it.metadata.copy(links = links))
        }
        val links = importDocs(listOf(squad, web), listOf(existing)).document("svc", "web").properties().getValue("links") as JsonArray
        assertEquals(
            listOf(
                json("url" to "https://wiki", "title" to "New title", "tag" to "docs"),
                json("url" to "https://new", "title" to "New"),
                json("title" to "no url yet"),
            ),
            links.toList(),
        )
    }

    @Test
    fun `links rest keeps a stored item whose title a link rule owns, and pairs items sharing one url in order`() {
        val existing = stored(
            "svc", "web",
            properties = json(
                "links" to listOf(
                    json("url" to "https://same", "title" to "First", "k" to 1),
                    json("url" to "https://same", "title" to "Second", "k" to 2),
                    json("url" to "https://owned-by-the-ci-rule", "title" to "CI"),
                ),
            ),
        )
        val web = component("web").let {
            val links = listOf(CatalogLink("https://same", "First*"), CatalogLink("https://same", "Second*"))
            it.copy(metadata = it.metadata.copy(links = links))
        }
        val links = importDocs(listOf(squad, web), listOf(existing)).document("svc", "web").properties().getValue("links") as JsonArray
        assertEquals(
            listOf(
                json("url" to "https://same", "title" to "First*", "k" to 1),
                json("url" to "https://same", "title" to "Second*", "k" to 2),
                json("url" to "https://owned-by-the-ci-rule", "title" to "CI"),
            ),
            links.toList(),
        )
    }

    @Test
    fun `the export skips a links rest item whose title a link rule owns, so the import never reads it into that property`() {
        val entity = ExportFixtures.entity(
            "svc", "web",
            properties = json(
                "type" to "service", "lifecycle" to "production", "ci" to "https://ci.example.com",
                "links" to listOf(json("url" to "https://impostor", "title" to "CI"), json("url" to "https://wiki", "title" to "Wiki")),
            ),
            team = listOf("squad"),
        )
        val exported = ExportFixtures.convert(listOf(ExportFixtures.entity("_team", "squad"), entity))
        val links = exported.files.single { it.metadata.name == "web" }.metadata.links
        assertEquals(listOf(CatalogLink("https://wiki", "Wiki"), CatalogLink("https://ci.example.com", "CI", "dashboard")), links)
        val back = importDocs(exported.files, merge = false).document("svc", "web").properties()
        assertEquals(JsonPrimitive("https://ci.example.com"), back["ci"])
    }

    @Test
    fun `links rest leaves nothing behind when the Backstage side has none and no unemittable item is stored`() {
        val existing = stored("svc", "web", properties = json("links" to listOf(json("url" to "https://gone"))))
        assertFalse("links" in importDocs(listOf(squad, component("web")), listOf(existing)).document("svc", "web").properties())
    }

    // ── S4: a placeholder namespace ─────────────────────────────────────────────────────────

    @Test
    fun `a copy namespace rule lets its placeholders through and keeps the Port leaf for them`() {
        val mapping = ExportFixtures.parse(
            """
            {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
              {"blueprint": "svc", "kind": "Component", "rules": [
                {"port": {"property": "note"}, "backstage": {"metadata": "namespace"}, "codec": "copy",
                 "importIgnore": ["scratch"], "exportDefault": "default"}]}]}
            """.trimIndent(),
        )
        val existing = stored("svc", "web", properties = json("note" to "kept"))
        val scratch = importDocs(listOf(component("web", namespace = "scratch")), listOf(existing), mapping = mapping)
        assertEquals(ConversionRowStatus.CONVERTED, scratch.row("web").status)
        assertEquals(JsonPrimitive("kept"), scratch.document("svc", "web").properties()["note"])
        val default = importDocs(listOf(component("web")), listOf(existing), mapping = mapping)
        val defaultNote = default.document("svc", "web").properties()["note"]
        assertEquals(JsonPrimitive("kept"), defaultNote, "the export's default is a placeholder too")
        val real = importDocs(listOf(component("web", namespace = "team-a")), listOf(existing), mapping = mapping)
        assertEquals(JsonPrimitive("team-a"), real.document("svc", "web").properties()["note"])
    }

    // ── numbers ─────────────────────────────────────────────────────────────────────────────

    private fun numberMapping(codec: String) = ExportFixtures.parse(
        """
        {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
          {"blueprint": "num", "kind": "Component", "rules": [$codec]}]}
        """.trimIndent(),
    )

    private val numBlueprints = ExportFixtures.blueprints + mapOf(
        "num" to blueprintJson.decodeFromString<BlueprintDefinition>(
            """{"schema": {"properties": {"ratio": {"type": "number"}, "level": {"type": "number", "enum": [1, 2]}}}}""",
        ),
    )

    private fun numbersOf(label: String, value: String, port: String, codec: String = "copy", map: String = ""): JsonElement? {
        val rule = """{"port": {"property": "$port"}, "backstage": {"label": "n"}, "codec": "$codec" $map}"""
        val file = component("n1", spec = EntitySpec()).let { it.copy(metadata = it.metadata.copy(labels = mapOf(label to value))) }
        val result = importDocs(listOf(file), mapping = numberMapping(rule), blueprints = numBlueprints)
        return result.documents.firstOrNull()?.properties()?.get(port)
    }

    @Test
    fun `a number property takes an integer exactly or a decimal, refuses text, and a valueMap into a number yields a number`() {
        assertEquals(JsonPrimitive(3L), numbersOf("n", "3", "ratio"))
        assertEquals(JsonPrimitive(java.math.BigDecimal("0.5")), numbersOf("n", "0.5", "ratio"))
        assertEquals(JsonPrimitive(9007199254740993L), numbersOf("n", "9007199254740993", "ratio"))
        assertEquals(null, numbersOf("n", "many", "ratio"), "text is no number: nothing is converted")
        assertEquals(JsonPrimitive(2L), numbersOf("n", "two", "level", "valueMap", """, "map": {"1": "one", "2": "two"}"""))
    }

    // ── S1: the entity value rules ──────────────────────────────────────────────────────────

    private val strictBlueprints = ExportFixtures.blueprints + mapOf(
        "req" to blueprintJson.decodeFromString<BlueprintDefinition>(
            """{"schema": {"properties": {"repo": {"type": "string"}}, "required": ["repo"]},
                "relations": {"deps": {"title": "Deps", "target": "req", "required": false, "many": true}}}""",
        ),
    )

    private val strictMapping = ExportFixtures.parse(
        """
        {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
          {"blueprint": "req", "kind": "Component", "rules": [
            {"port": {"property": "repo"}, "backstage": {"annotation": "example.com/source"}, "codec": "copy"},
            {"port": {"relation": "deps"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}]}]}
        """.trimIndent(),
    )

    @Test
    fun `a document missing a required property is INVALID here and its referrers drop the relation`() {
        val annotations = mapOf("example.com/source" to "https://r")
        val withRepo = component("fine").let { it.copy(metadata = it.metadata.copy(annotations = annotations)) }
        val web = withRepo.copy(
            metadata = withRepo.metadata.copy(name = "web"),
            spec = withRepo.spec.copy(dependsOn = listOf("component:default/bare", "component:default/fine")),
        )
        val result = importDocs(listOf(component("bare"), withRepo, web), mapping = strictMapping, blueprints = strictBlueprints)
        val bare = result.row("bare")
        assertEquals(ConversionRowStatus.INVALID, bare.status)
        assertEquals(ConversionFindingCode.DOCUMENT_INVALID, bare.findings.single().code)
        assertTrue("repo" in bare.findings.single().message, bare.findings.single().message)
        assertEquals(setOf("fine", "web"), result.documents.map { (it["identifier"] as JsonPrimitive).content }.toSet())
        assertEquals(names("fine"), result.document("req", "web").relations()["deps"])
        assertEquals(ConversionFindingCode.REFERENCE_DROPPED, result.row("web").findings.single().code)
    }

    // ── nits ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a scalar tagCategory property with several tags of its category reports the extras`() {
        val mapping = ExportFixtures.parse(
            """
            {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
              {"blueprint": "svc", "kind": "Component", "rules": [
                {"port": {"property": "note"}, "backstage": {"tags": "Languages"}, "codec": "tagCategory"}]}]}
            """.trimIndent(),
        )
        val file = component("web").let { it.copy(metadata = it.metadata.copy(tags = listOf("java", "sql"))) }
        val result = importDocs(listOf(file), mapping = mapping)
        assertEquals(JsonPrimitive("java"), result.document("svc", "web").properties()["note"])
        assertEquals(ConversionFindingCode.VALUE_UNMAPPED, result.row("web").findings.single().code)
    }

    @Test
    fun `two documents with one Backstage identity selecting different pairs are both refused`() {
        val mapping = ExportFixtures.parse(
            """
            {"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
              {"blueprint": "plain", "kind": "Component", "select": {"spec": "type", "in": ["library"]}, "rules": []},
              {"blueprint": "svc", "kind": "Component", "rules": []}]}
            """.trimIndent(),
        )
        val result = importDocs(
            listOf(component("same", spec = EntitySpec(type = "library")), component("same", spec = EntitySpec(type = "service"))),
            mapping = mapping,
        )
        assertEquals(List(2) { ConversionRowStatus.INVALID }, result.rows.map { it.status })
        assertEquals(List(2) { ConversionFindingCode.IDENTITY_CONFLICT }, result.rows.map { it.findings.single().code })
        assertEquals(emptyList(), result.documents)
        assertTrue(doc("Group", "unused").metadata.name.isNotEmpty())
    }
}
