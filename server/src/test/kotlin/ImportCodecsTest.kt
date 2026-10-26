package ch.nokillswit

import ch.nokillswit.ExportFixtures.entity
import ch.nokillswit.ExportFixtures.json
import ch.nokillswit.ImportFixtures.document
import ch.nokillswit.ImportFixtures.importDocs
import ch.nokillswit.ImportFixtures.properties
import ch.nokillswit.ImportFixtures.relations
import ch.nokillswit.catalog.CatalogFile
import ch.nokillswit.catalog.CatalogLink
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.conversion.ConversionFinding
import ch.nokillswit.conversion.ConversionFindingCode
import ch.nokillswit.conversion.EntityDraft
import ch.nokillswit.conversion.ImportContext
import ch.nokillswit.conversion.ImportRefs
import ch.nokillswit.conversion.Leaf
import ch.nokillswit.conversion.MappingPair
import ch.nokillswit.conversion.RefTarget
import ch.nokillswit.conversion.importRefFields
import ch.nokillswit.conversion.importRule
import ch.nokillswit.conversion.then
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The IMPORT half of every codec (`conversion/CodecsImport.kt`), one rule at a time over the
 * [ExportFixtures] world, and — the property that makes the two halves one mapping — the round trip
 * `import(export(x)) == x` over a whole fixture workspace. No database, no real ontology names.
 */
class ImportCodecsTest {

    /** A one-pair mapping (blueprint `svc`, kind Component) holding the given rule JSON objects. */
    private fun pairOf(vararg rules: String): MappingPair = ExportFixtures.parse(
        """{"schemaVersion": 1, "apiVersion": "backstage.io/v1alpha1", "pairs": [
             {"blueprint": "svc", "kind": "Component", "rules": [${rules.joinToString(",")}]}]}""",
    ).pairs.single()

    private class Run(val draft: EntityDraft, val findings: List<ConversionFinding>)

    private val noRefs = ImportRefs { _, _, raw -> RefTarget.Dropped("no refs in this test: $raw") }

    private fun run(pair: MappingPair, file: CatalogFile, refs: ImportRefs = noRefs): Run {
        val findings = mutableListOf<ConversionFinding>()
        val ctx = ImportContext(file, pair, ExportFixtures.blueprints["svc"], ExportFixtures.registry, refs, findings)
        val draft = EntityDraft()
        pair.rules.filter { it.readsOnImport }.forEach { importRule(it, ctx, draft) }
        importRefFields(ctx, draft)
        return Run(draft, findings)
    }

    private fun file(
        labels: Map<String, String> = emptyMap(),
        annotations: Map<String, String> = emptyMap(),
        tags: List<String> = emptyList(),
        links: List<CatalogLink> = emptyList(),
        title: String? = null,
        spec: EntitySpec = EntitySpec(),
    ) = ImportFixtures.doc(
        "Component", "a",
        labels = labels, annotations = annotations, tags = tags, links = links, title = title, spec = spec,
    )

    private fun value(leaf: Leaf?) = (leaf as? Leaf.Value)?.json

    // ── leaves ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `leaf verdicts combine as value over keep over absence, first value winning`() {
        val a = Leaf.Value(JsonPrimitive("a"))
        val b = Leaf.Value(JsonPrimitive("b"))
        assertEquals(a, null.then(a))
        assertEquals(a, a.then(b))
        assertEquals(b, Leaf.Absent.then(b))
        assertEquals(a, Leaf.Kept.then(a))
        assertEquals(Leaf.Kept, Leaf.Absent.then(Leaf.Kept))
        assertEquals(Leaf.Kept, Leaf.Kept.then(Leaf.Absent))
        assertEquals(Leaf.Absent, Leaf.Absent.then(Leaf.Absent))
    }

    // ── copy ────────────────────────────────────────────────────────────────────────────────

    private val repoRule = """{"port": {"property": "repo"}, "backstage": {"annotation": "k"}, "codec": "copy",
        "prefix": "url:", "importIgnore": ["dir:."]}"""

    @Test
    fun `copy strips the prefix and an absent value is Absent`() {
        val pair = pairOf(repoRule)
        val withUrl = run(pair, file(annotations = mapOf("k" to "url:https://x")))
        assertEquals(JsonPrimitive("https://x"), value(withUrl.draft.properties["repo"]))
        assertEquals(Leaf.Absent, run(pair, file()).draft.properties["repo"])
        val bare = run(pair, file(annotations = mapOf("k" to "url:")))
        assertEquals(Leaf.Absent, bare.draft.properties["repo"], "nothing after the prefix is no value")
    }

    @Test
    fun `copy keeps the Port side for an ignored placeholder and for a value without the prefix`() {
        val pair = pairOf(repoRule)
        val ignored = run(pair, file(annotations = mapOf("k" to "dir:.")))
        assertEquals(Leaf.Kept, ignored.draft.properties["repo"])
        assertEquals(emptyList(), ignored.findings, "an ignored placeholder is not a finding")
        val foreign = run(pair, file(annotations = mapOf("k" to "github:o/r")))
        assertEquals(Leaf.Kept, foreign.draft.properties["repo"])
        assertEquals(
            listOf(ConversionFindingCode.VALUE_UNMAPPED to "metadata.annotations.k"),
            foreign.findings.map { it.code to it.field },
        )
    }

    @Test
    fun `copy onto a number property parses the text, and refuses text that is no number`() {
        val pair = pairOf("""{"port": {"property": "replicas"}, "backstage": {"label": "n"}, "codec": "copy"}""")
        val blueprint = ExportFixtures.blueprints.getValue("svc")
        // `replicas` is a number in the fixture blueprint.
        assertEquals("number", blueprint.schema.properties.getValue("replicas").type)
        assertEquals(JsonPrimitive(3L), value(run(pair, file(labels = mapOf("n" to "3"))).draft.properties["replicas"]))
        assertEquals(JsonPrimitive(2.5), value(run(pair, file(labels = mapOf("n" to "2.5"))).draft.properties["replicas"]))
        val bad = run(pair, file(labels = mapOf("n" to "many")))
        assertEquals(Leaf.Kept, bad.draft.properties["replicas"])
        assertEquals(ConversionFindingCode.VALUE_UNMAPPED, bad.findings.single().code)
    }

    @Test
    fun `copy reads title, description and a profile field`() {
        val pair = pairOf(
            """{"port": {"meta": "title"}, "backstage": {"metadata": "title"}, "codec": "copy"}""",
            """{"port": {"meta": "icon"}, "backstage": {"label": "icon"}, "codec": "copy"}""",
        )
        val r = run(pair, file(title = "The A", labels = mapOf("icon" to "Rocket")))
        assertEquals(JsonPrimitive("The A"), value(r.draft.title))
        assertEquals(JsonPrimitive("Rocket"), value(r.draft.icon))
        assertEquals(Leaf.Absent, run(pair, file()).draft.title)
    }

    @Test
    fun `copy onto links rest takes every link no link rule claims, with its title and icon`() {
        val pair = pairOf(
            """{"port": {"property": "links"}, "backstage": {"links": "rest"}, "codec": "copy"}""",
            """{"port": {"property": "ci"}, "backstage": {"link": {"title": "CI"}}, "codec": "link"}""",
        )
        val links = listOf(CatalogLink("https://ci", "CI"), CatalogLink("https://wiki", "Wiki", "docs"), CatalogLink("https://bare"))
        val r = run(pair, file(links = links))
        assertEquals(
            Leaf.LinkItems(
                listOf(json("url" to "https://wiki", "title" to "Wiki", "icon" to "docs"), json("url" to "https://bare")),
                claimed = setOf("CI"),
            ),
            r.draft.properties["links"],
        )
        assertEquals(JsonPrimitive("https://ci"), value(r.draft.properties["ci"]))
        assertEquals(Leaf.LinkItems(emptyList(), setOf("CI")), run(pair, file(links = listOf(links[0]))).draft.properties["links"])
    }

    // ── valueMap ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `valueMap inverts a boolean map into a boolean and an enum map into the enum value`() {
        val pair = pairOf(
            """{"port": {"property": "pii"}, "backstage": {"label": "pii"}, "codec": "valueMap", "map": {"true": "yes", "false": "no"}}""",
            """{"port": {"property": "tier"}, "backstage": {"label": "t"}, "codec": "valueMap", "map": {"gold": "G", "silver": "S"}}""",
        )
        val r = run(pair, file(labels = mapOf("pii" to "no", "t" to "S")))
        assertEquals(JsonPrimitive(false), value(r.draft.properties["pii"]))
        assertEquals(JsonPrimitive("silver"), value(r.draft.properties["tier"]))
        assertEquals(JsonPrimitive(true), value(run(pair, file(labels = mapOf("pii" to "yes"))).draft.properties["pii"]))
        assertEquals(Leaf.Absent, run(pair, file()).draft.properties["pii"])
    }

    @Test
    fun `valueMap keeps the Port side for a value its map does not list`() {
        val pair = pairOf(
            """{"port": {"property": "pii"}, "backstage": {"label": "pii"}, "codec": "valueMap", "map": {"true": "yes", "false": "no"}}""",
        )
        val r = run(pair, file(labels = mapOf("pii" to "maybe")))
        assertEquals(Leaf.Kept, r.draft.properties["pii"])
        assertEquals(ConversionFindingCode.VALUE_UNMAPPED, r.findings.single().code)
    }

    // ── tagCategory ─────────────────────────────────────────────────────────────────────────

    private val languages = setOf("java", "kotlin", "sql")

    @Test
    fun `tagCategory collects the tags of the category in document order and knows which values its categories cover`() {
        val pair = pairOf("""{"port": {"property": "stack"}, "backstage": {"tags": "Languages"}, "codec": "tagCategory"}""")
        val r = run(pair, file(tags = listOf("sql", "rust", "java", "sql")))
        assertEquals(Leaf.TagValues(listOf("sql", "java"), languages, scalar = false), r.draft.properties["stack"])
        assertEquals(Leaf.TagValues(emptyList(), languages, scalar = false), run(pair, file()).draft.properties["stack"])
    }

    @Test
    fun `several tagCategory rules from one property add up, and a scalar property takes the first tag`() {
        val two = pairOf(
            """{"port": {"property": "stack"}, "backstage": {"tags": "Languages"}, "codec": "tagCategory"}""",
            """{"port": {"property": "stack"}, "backstage": {"tags": "Other"}, "codec": "tagCategory"}""",
        )
        // "Other" holds no registered tags in the fixture registry, so only Languages contributes values or coverage.
        assertEquals(
            Leaf.TagValues(listOf("java"), languages, scalar = false),
            run(two, file(tags = listOf("java", "x"))).draft.properties["stack"],
        )
        val scalar = pairOf("""{"port": {"property": "tier"}, "backstage": {"tags": "Languages"}, "codec": "tagCategory"}""")
        assertEquals(
            Leaf.TagValues(listOf("kotlin"), languages, scalar = true),
            run(scalar, file(tags = listOf("kotlin", "java"))).draft.properties["tier"],
        )
    }

    @Test
    fun `contributions to one leaf combine - arrays union, a keep makes a union, a value beats an absence`() {
        fun array(vararg v: String) = JsonArray(v.map { JsonPrimitive(it) })
        assertEquals(Leaf.Value(array("a", "b")), Leaf.Value(array("a")).then(Leaf.Value(array("b", "a"))))
        assertEquals(Leaf.Union(array("a")), Leaf.Value(array("a")).then(Leaf.Kept))
        assertEquals(Leaf.Union(array("a")), Leaf.Kept.then(Leaf.Value(array("a"))))
        assertEquals(Leaf.Union(array("a", "b")), Leaf.Union(array("a")).then(Leaf.Value(array("b"))))
        assertEquals(Leaf.Value(array("a")), Leaf.Value(array("a")).then(Leaf.Absent))
        val scalar = Leaf.Value(JsonPrimitive("x"))
        assertEquals(scalar, scalar.then(Leaf.Kept), "a scalar cannot be unioned: the value stands")
        val tags = Leaf.TagValues(listOf("a"), setOf("a", "b"), scalar = false)
        val more = Leaf.TagValues(listOf("c"), setOf("c"), false)
        assertEquals(Leaf.TagValues(listOf("a", "c"), setOf("a", "b", "c"), false), tags.then(more))
        val kept = tags.then(Leaf.Kept)
        assertEquals(Leaf.TagValues(listOf("a"), emptySet(), false), kept, "a keep covers nothing: every stored value stays")
    }

    // ── link ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `link reads the url of the link with its title`() {
        val pair = pairOf("""{"port": {"property": "ci"}, "backstage": {"link": {"title": "CI"}}, "codec": "link"}""")
        val both = run(pair, file(links = listOf(CatalogLink("https://x", "Wiki"), CatalogLink("https://ci", "CI"))))
        assertEquals(JsonPrimitive("https://ci"), value(both.draft.properties["ci"]))
        assertEquals(Leaf.Absent, run(pair, file(links = listOf(CatalogLink("https://x", "Wiki")))).draft.properties["ci"])
    }

    // ── ref ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `ref rules on one field split its entries by the blueprint each names, in rule order`() {
        val pair = pairOf(
            """{"port": {"relation": "deps"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}""",
            """{"port": {"relation": "uses"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}""",
            """{"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"}""",
        )
        val refs = ImportRefs { _, _, raw ->
            when {
                raw.startsWith("component:") -> RefTarget.Entity("svc", raw.substringAfter('/'))
                raw.startsWith("resource:") -> RefTarget.Entity("dat", raw.substringAfter('/'))
                raw.startsWith("group:") -> RefTarget.Entity("_team", raw.substringAfter('/'))
                else -> RefTarget.Dropped("unknown")
            }
        }
        val spec = EntitySpec(
            dependsOn = listOf("component:default/a", "resource:default/d", "component:default/b", "component:default/a"),
            owner = "group:default/squad",
        )
        val r = run(pair, file(spec = spec), refs)
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), value(r.draft.relations["deps"]))
        assertEquals(JsonArray(listOf(JsonPrimitive("d"))), value(r.draft.relations["uses"]))
        assertEquals(JsonPrimitive("squad"), value(r.draft.team))
        assertEquals(emptyList(), r.findings)
    }

    @Test
    fun `a ref field with no entries is Absent, one with only dropped entries is Kept`() {
        val pair = pairOf("""{"port": {"relation": "deps"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}""")
        assertEquals(Leaf.Absent, run(pair, file()).draft.relations["deps"])
        val dropped = run(pair, file(spec = EntitySpec(dependsOn = listOf("component:default/gone"))))
        assertEquals(Leaf.Kept, dropped.draft.relations["deps"])
        assertEquals(ConversionFindingCode.REFERENCE_DROPPED, dropped.findings.single().code)
    }

    // ── the round trip ──────────────────────────────────────────────────────────────────────

    /** Property ids the fixture mapping declares lossy, per blueprint: the only Port data a round trip may lose. */
    private val lossyProperties = mapOf("svc" to setOf("note", "replicas"))

    @Test
    fun `importing what the export produced gives back the entities, modulo what the pairs declare lossy`() {
        val world = listOf(
            entity("_team", "squad", title = "squad", properties = json("type" to "team")),
            entity("_team", "ops", title = "ops", properties = json("type" to "team"), relations = json("parent" to "squad")),
            entity(
                "usr", "bob", title = "bob",
                properties = json("mail" to "bob@example.com"), relations = json("team" to listOf("squad", "ops")),
            ),
            entity("sys", "shop", title = "Shop", properties = json("type" to "product", "ext" to false), team = listOf("squad")),
            entity("sys", "partner", title = "Partner", properties = json("type" to "capability", "ext" to true), team = listOf("ops")),
            entity(
                "dat", "db", title = "db", properties = json("type" to "database"),
                relations = json("system" to "shop", "deps" to listOf("db2")), team = listOf("squad"),
            ),
            entity("dat", "db2", title = "db2", properties = json("type" to "cache"), team = listOf("squad")),
            entity(
                "svc", "web", title = "Web",
                properties = json(
                    "type" to "website", "lifecycle" to "production", "blurb" to "The web.", "repo" to "https://git.example.com/web",
                    "tier" to "gold", "pii" to true, "stack" to listOf("java", "sql"), "ext" to true, "ci" to "https://ci.example.com/web",
                    "links" to listOf(
                        json("url" to "https://wiki.example.com", "title" to "Wiki", "icon" to "docs"),
                        json("url" to "https://bare.example.com"),
                    ),
                ),
                relations = json("system" to "shop", "deps" to listOf("api"), "uses" to listOf("db", "db2")),
                team = listOf("squad"),
            ),
            entity(
                "svc", "api", title = "Api",
                properties = json(
                    "type" to "service", "lifecycle" to "experimental", "tier" to "silver", "pii" to false, "ext" to false,
                    "stack" to listOf("kotlin"),
                ),
                relations = json("system" to "shop"),
                team = listOf("ops"),
            ),
        )
        val exported = ExportFixtures.convert(world)
        val exportFindings = exported.rows.filter { it.findings.isNotEmpty() }.map { it.identifier to it.findings }
        assertEquals(emptyList(), exportFindings, "the export is clean")
        val imported = importDocs(exported.files, merge = false)
        assertEquals(emptyList(), imported.rows.filter { it.findings.isNotEmpty() }.map { it.name to it.findings }, "the import is clean")
        assertEquals(world.size, imported.documents.size)

        world.forEach { original ->
            val result = imported.document(original.blueprint, original.identifier)
            val props = original.properties.filterKeys { it !in lossyProperties[original.blueprint].orEmpty() }
            assertEquals(JsonObject(props), result.properties(), "${original.blueprint}/${original.identifier} properties")
            assertEquals(original.relations, result.relations(), "${original.blueprint}/${original.identifier} relations")
            val label = "${original.blueprint}/${original.identifier}"
            assertEquals(original.title, (result.getValue("title") as JsonPrimitive).content, "$label title")
            val expectedTeam = original.team.firstOrNull()?.let { JsonPrimitive(it) }
            assertEquals(expectedTeam, result["team"], "${original.blueprint}/${original.identifier} team")
        }
    }
}
