package ch.nokillswit

import ch.nokillswit.ExportFixtures.entity
import ch.nokillswit.ExportFixtures.json
import ch.nokillswit.catalog.CatalogLink
import ch.nokillswit.catalog.EntityProfile
import ch.nokillswit.catalog.EntitySpec
import ch.nokillswit.catalog.SPEC_SHAPES
import ch.nokillswit.catalog.SpecShape
import ch.nokillswit.catalog.specLists
import ch.nokillswit.catalog.specOf
import ch.nokillswit.catalog.specStrings
import ch.nokillswit.conversion.BackstageAddress
import ch.nokillswit.conversion.ConversionFinding
import ch.nokillswit.conversion.ConversionFindingCode
import ch.nokillswit.conversion.DocBuilder
import ch.nokillswit.conversion.ExportContext
import ch.nokillswit.conversion.ExportLookup
import ch.nokillswit.conversion.MappingRule
import ch.nokillswit.conversion.MetadataField
import ch.nokillswit.conversion.PortAddress
import ch.nokillswit.conversion.RefOutcome
import ch.nokillswit.conversion.exportRule
import ch.nokillswit.entities.ExportEntity
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The EXPORT half of every codec (`conversion/Codecs.kt`) and the [DocBuilder] they write into, one
 * rule at a time over the [ExportFixtures] world — no database, no real ontology names. The whole
 * pipeline (identities, derive pass, merge, validation) is [BackstageExportTest]; the real sample
 * is [SampleConversionTest].
 */
class CodecsTest {

    /** A lookup that renders `kind:default/<identifier>` for [known] targets and answers [missing]/unmapped otherwise. */
    private class StubLookup(
        private val known: Set<String> = emptySet(),
        private val unmapped: Set<String> = emptySet(),
        private val referrers: List<ExportEntity> = emptyList(),
    ) : ExportLookup {
        override fun reference(blueprint: String, identifier: String): RefOutcome = when {
            blueprint in unmapped -> RefOutcome.Unmapped
            identifier in known -> RefOutcome.Rendered("thing:default/$identifier")
            else -> RefOutcome.Missing
        }

        override fun referrers(sourceBlueprint: String, relation: String, targetIdentifier: String) = referrers
    }

    private class Run(val builder: DocBuilder, val findings: List<ConversionFinding>)

    private fun rule(blueprint: String, matches: (MappingRule) -> Boolean): MappingRule =
        ExportFixtures.mapping.pairs.first { it.blueprint == blueprint }.rules.first(matches)

    private fun run(rule: MappingRule, entity: ExportEntity, lookup: ExportLookup = StubLookup()): Run {
        val findings = mutableListOf<ConversionFinding>()
        val out = DocBuilder()
        exportRule(rule, ExportContext(entity, ExportFixtures.blueprints[entity.blueprint], ExportFixtures.registry, lookup, findings), out)
        return Run(out, findings)
    }

    private fun property(id: String): (MappingRule) -> Boolean = { (it.port as? PortAddress.Property)?.id == id }
    private fun relation(id: String): (MappingRule) -> Boolean = { (it.port as? PortAddress.Relation)?.id == id }

    // ── copy ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `copy writes the value, with the prefix prepended`() {
        val r = run(rule("svc", property("repo")), entity("svc", "a", properties = json("repo" to "https://git.example.com/a")))
        assertEquals(mapOf("example.com/source" to "url:https://git.example.com/a"), r.builder.annotations)
    }

    @Test
    fun `copy writes a spec text field and the title meta field`() {
        val type = run(rule("svc", property("type")), entity("svc", "a", properties = json("type" to "website")))
        assertEquals("website", type.builder.toFile("Component", "a", "default").spec.type)
        val titleRule = rule("svc") { it.port is PortAddress.Meta && it.backstage is BackstageAddress.Metadata }
        val title = run(titleRule, entity("svc", "a", title = "The A"))
        assertEquals("The A", title.builder.title)
    }

    @Test
    fun `copy with an exportDefault writes it only when the value is absent, null or blank - and never prefixed`() {
        val rule = rule("dat", property("type"))
        fun typeOf(properties: kotlinx.serialization.json.JsonObject) =
            run(rule, entity("dat", "a", properties = properties)).builder.toFile("Resource", "a", "default").spec.type
        assertEquals("database", typeOf(json()))
        assertEquals("database", typeOf(json("type" to null)))
        assertEquals("database", typeOf(json("type" to "")))
        assertEquals("cache", typeOf(json("type" to "cache")))
    }

    @Test
    fun `copy without a default writes nothing for an absent value`() {
        val r = run(rule("svc", property("repo")), entity("svc", "a"))
        assertEquals(emptyMap(), r.builder.annotations)
        assertEquals(emptyList(), r.findings)
    }

    @Test
    fun `copy of an array-of-objects onto links rest keeps every item with a url and drops the rest`() {
        val links = ExportFixtures.json(
            "links" to listOf(
                json("title" to "Wiki", "url" to "https://wiki.example.com", "icon" to "docs"),
                json("url" to "https://bare.example.com"),
                json("title" to "no url"),
            ),
        )
        val r = run(rule("svc", property("links")), entity("svc", "a", properties = links))
        assertEquals(
            listOf(CatalogLink("https://wiki.example.com", "Wiki", "docs"), CatalogLink("https://bare.example.com")),
            r.builder.links,
        )
    }

    // ── valueMap ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `valueMap maps a boolean and an enum through its bijection and skips an absent value`() {
        val pii = rule("svc", property("pii"))
        assertEquals(mapOf("pii" to "yes"), run(pii, entity("svc", "a", properties = json("pii" to true))).builder.labels)
        assertEquals(mapOf("pii" to "no"), run(pii, entity("svc", "a", properties = json("pii" to false))).builder.labels)
        assertEquals(emptyMap(), run(pii, entity("svc", "a")).builder.labels)
        val ext = rule("sys", property("ext"))
        assertEquals("external", run(ext, entity("sys", "a", properties = json("ext" to true))).builder.namespace)
    }

    @Test
    fun `valueMap reports a value its map does not list`() {
        val r = run(rule("svc", property("pii")), entity("svc", "a", properties = json("pii" to "maybe")))
        assertEquals(emptyMap(), r.builder.labels)
        assertEquals(listOf(ConversionFindingCode.VALUE_UNMAPPED to "properties.pii"), r.findings.map { it.code to it.field })
    }

    // ── tagCategory ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `tagCategory turns each array element the registry places in the category into a tag`() {
        val r = run(rule("svc", property("stack")), entity("svc", "a", properties = json("stack" to listOf("java", "sql", "cobol"))))
        assertEquals(listOf("java", "sql"), r.builder.tags.toList())
    }

    @Test
    fun `tagCategory of a scalar is one tag, and a value outside the category is not a tag`() {
        val r = run(rule("svc", property("stack")), entity("svc", "a", properties = json("stack" to "kotlin")))
        assertEquals(listOf("kotlin"), r.builder.tags.toList())
        assertEquals(emptyList(), run(rule("svc", property("stack")), entity("svc", "a")).builder.tags.toList())
    }

    // ── ref ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `ref renders a single relation onto a single field and a many relation onto a list`() {
        val lookup = StubLookup(known = setOf("s1", "d1", "d2"))
        val single = run(rule("svc", relation("system")), entity("svc", "a", relations = json("system" to "s1")), lookup)
        assertEquals("thing:default/s1", single.builder.toFile("Component", "a", "default").spec.system)
        val many = run(rule("svc", relation("deps")), entity("svc", "a", relations = json("deps" to listOf("d1", "d2"))), lookup)
        assertEquals(listOf("thing:default/d1", "thing:default/d2"), many.builder.toFile("Component", "a", "default").spec.dependsOn)
    }

    @Test
    fun `ref drops a dangling or unmapped target and reports each`() {
        val missing = run(rule("svc", relation("system")), entity("svc", "a", relations = json("system" to "gone")), StubLookup())
        assertEquals(listOf(ConversionFindingCode.TARGET_MISSING to "relations.system"), missing.findings.map { it.code to it.field })
        assertNull(missing.builder.toFile("Component", "a", "default").spec.system)
        val unmapped = run(
            rule("svc", relation("system")),
            entity("svc", "a", relations = json("system" to "s1")),
            StubLookup(known = setOf("s1"), unmapped = setOf("sys")),
        )
        assertEquals(listOf(ConversionFindingCode.TARGET_UNMAPPED to "relations.system"), unmapped.findings.map { it.code to it.field })
    }

    @Test
    fun `ref of the team meta field keeps the first team of several and reports OWNER_TRUNCATED`() {
        val owner = rule("svc") { it.backstage == BackstageAddress.Spec("owner") }
        val lookup = StubLookup(known = setOf("t1", "t2"))
        val one = run(owner, entity("svc", "a", team = listOf("t1")), lookup)
        assertEquals("thing:default/t1", one.builder.toFile("Component", "a", "default").spec.owner)
        assertEquals(emptyList(), one.findings)
        val two = run(owner, entity("svc", "a", team = listOf("t1", "t2")), lookup)
        assertEquals("thing:default/t1", two.builder.toFile("Component", "a", "default").spec.owner)
        assertEquals(listOf(ConversionFindingCode.OWNER_TRUNCATED to "team"), two.findings.map { it.code to it.field })
        assertTrue("'t2'" in two.findings.single().message, two.findings.single().message)
        assertNull(run(owner, entity("svc", "a"), lookup).builder.toFile("Component", "a", "default").spec.owner)
    }

    // ── derive ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `derive lists the entities pointing here, and an empty derive still makes the list present`() {
        val children = rule("_team") { it.backstage == BackstageAddress.Spec("children") }
        val referrer = entity("_team", "kid")
        val some = run(children, entity("_team", "boss"), StubLookup(known = setOf("kid"), referrers = listOf(referrer)))
        assertEquals(listOf("thing:default/kid"), some.builder.toFile("Group", "boss", "default").spec.children)
        val none = run(children, entity("_team", "boss"), StubLookup())
        assertEquals(emptyList(), none.builder.toFile("Group", "boss", "default").spec.children)
        // Absent (null) is what an UNTOUCHED nullable list looks like - the difference Backstage requires.
        assertNull(DocBuilder().toFile("Group", "boss", "default").spec.children)
    }

    // ── link ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `link turns a url property into one titled link and ignores an absent one`() {
        val ci = rule("svc", property("ci"))
        val r = run(ci, entity("svc", "a", properties = json("ci" to "https://ci.example.com/a")))
        assertEquals(listOf(CatalogLink("https://ci.example.com/a", "CI", "dashboard")), r.builder.links)
        assertEquals(emptyList(), run(ci, entity("svc", "a")).builder.links)
    }

    // ── DocBuilder ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the first value for a text leaf wins and a reference list concatenates without duplicates`() {
        val b = DocBuilder()
        b.putText(BackstageAddress.Metadata(MetadataField.TITLE), "first")
        b.putText(BackstageAddress.Metadata(MetadataField.TITLE), "second")
        b.putText(BackstageAddress.Label("k"), "v1")
        b.putText(BackstageAddress.Label("k"), "v2")
        b.putRefs("dependsOn", listOf("a:default/x", "a:default/y"))
        b.putRefs("dependsOn", listOf("a:default/y", "a:default/z"))
        b.putRefs("owner", listOf("g:default/one", "g:default/two"))
        b.putRefs("owner", listOf("g:default/three"))
        val file = b.toFile("Component", "n", "default")
        assertEquals("first", file.metadata.title)
        assertEquals(mapOf("k" to "v1"), file.metadata.labels)
        assertEquals(listOf("a:default/x", "a:default/y", "a:default/z"), file.spec.dependsOn)
        assertEquals("g:default/one", file.spec.owner)
    }

    @Test
    fun `a profile is built only when a profile leaf was written`() {
        assertNull(DocBuilder().toFile("User", "n", "default").spec.profile)
        val b = DocBuilder()
        b.email = "a@example.com"
        assertEquals(EntityProfile(null, "a@example.com", null), b.toFile("User", "n", "default").spec.profile)
    }

    // ── the spec writer against the catalog's own field table ───────────────────────────────

    @Test
    fun `the spec writer and its inverses cover every field the catalog's table declares`() {
        val strings = SPEC_SHAPES.filterValues { it == SpecShape.TEXT || it == SpecShape.REF }.keys.associateWith { "v-$it" }
        val lists = SPEC_SHAPES.filterValues { it == SpecShape.REF_LIST }.keys.associateWith { listOf("a-$it", "b-$it") }
        val profile = EntityProfile("n", "e", "p")
        val spec = specOf(strings, lists, profile)
        assertEquals(strings, specStrings(spec), "specOf/specStrings must round-trip every text and single-reference field")
        assertEquals(lists, specLists(spec), "specOf/specLists must round-trip every reference-list field")
        assertEquals(profile, spec.profile)
        // And nothing is left over: a spec built from the maps equals the one with every field set once.
        assertEquals(spec, specOf(specStrings(spec), specLists(spec), spec.profile))
        assertEquals(EntitySpec(), specOf(emptyMap(), emptyMap(), null))
        // A field added to EntitySpec but forgotten in the catalog's field table (and so in these accessors) fails here.
        assertEquals(
            EntitySpec.serializer().descriptor.elementNames.toSet(),
            SPEC_SHAPES.keys,
            "EntitySpec's members and the catalog's SPEC_FIELDS table must name the same fields",
        )
    }
}
