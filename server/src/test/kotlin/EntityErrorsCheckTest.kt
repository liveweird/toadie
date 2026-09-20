package ch.nokillswit

import ch.nokillswit.blueprints.AggregationCalculationSpec
import ch.nokillswit.blueprints.AggregationPropertyDefinition
import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.BlueprintSchema
import ch.nokillswit.blueprints.CalculationPropertyDefinition
import ch.nokillswit.blueprints.MirrorPropertyDefinition
import ch.nokillswit.blueprints.OwnershipDefinition
import ch.nokillswit.blueprints.PropertyDefinition
import ch.nokillswit.blueprints.RelationDefinition
import ch.nokillswit.entities.AGGREGATION_PATH_STALE
import ch.nokillswit.entities.AGGREGATION_PROPERTY_STALE
import ch.nokillswit.entities.CALCULATION_COMPILE_FAILED
import ch.nokillswit.entities.CALCULATION_QUARANTINED
import ch.nokillswit.entities.CalculationVerdict
import ch.nokillswit.entities.EntityErrorBlueprintCandidate
import ch.nokillswit.entities.EntityErrorEntitySubject
import ch.nokillswit.entities.EntityErrorSubjects
import ch.nokillswit.entities.EntityFinding
import ch.nokillswit.entities.GraphBlueprint
import ch.nokillswit.entities.MIRROR_PATH_STALE
import ch.nokillswit.entities.OWNERSHIP_PATH_STALE
import ch.nokillswit.entities.OWNERSHIP_UNRESOLVED
import ch.nokillswit.entities.SOURCE_MISSING
import ch.nokillswit.entities.SavedQueryCandidate
import ch.nokillswit.entities.aggregationFindings
import ch.nokillswit.entities.aggregationPropertyFindings
import ch.nokillswit.entities.calculationFindings
import ch.nokillswit.entities.entityErrorsReport
import ch.nokillswit.entities.mirrorPathFindings
import ch.nokillswit.entities.ownershipFinding
import ch.nokillswit.entities.ownershipPathFindings
import ch.nokillswit.entities.savedQueryDiagnostics
import ch.nokillswit.entities.sourceMissingFinding
import ch.nokillswit.entityquery.QueryDiagnosticCodes
import ch.nokillswit.entityquery.QuerySchema
import ch.nokillswit.entityquery.SavedEntityQueryVisibility
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Port-world Errors report's PURE checkers (2.5.0, `entities/EntityErrors.kt`) — no
 * database, one case per rule of the finding-code vocabulary table, mirroring the runtime
 * give-up rules in `EntityOwnership.kt`/`EntityComputed.kt`/`EntityAggregation.kt` exactly so
 * these static checks never flag something that resolves fine at read time.
 */
class EntityErrorsCheckTest {

    private fun relation(target: String, many: Boolean = false) =
        RelationDefinition(title = "Rel", target = target, required = false, many = many)

    private fun bp(
        schema: BlueprintSchema = BlueprintSchema(),
        relations: Map<String, RelationDefinition> = emptyMap(),
        mirrorProperties: Map<String, MirrorPropertyDefinition> = emptyMap(),
        calculationProperties: Map<String, CalculationPropertyDefinition> = emptyMap(),
        aggregationProperties: Map<String, AggregationPropertyDefinition> = emptyMap(),
        ownership: OwnershipDefinition? = null,
    ) = BlueprintDefinition(schema, relations, mirrorProperties, calculationProperties, aggregationProperties, ownership)

    private fun numberProp() = PropertyDefinition(type = "number")
    private fun stringProp() = PropertyDefinition(type = "string")

    private fun calcSpec(by: String = "entities", func: String = "count", property: String? = null, measureTimeBy: String? = null) =
        AggregationCalculationSpec(calculationBy = by, func = func, property = property, measureTimeBy = measureTimeBy)

    private fun pathFilterEntry(from: String, path: List<String>): JsonObject = buildJsonObject {
        put("fromBlueprint", from)
        put("path", buildJsonArray { path.forEach { add(JsonPrimitive(it)) } })
    }

    // ---------------------------------------------------------------------------------------
    // mirrorPathFindings (MIRROR_PATH_STALE)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a mirror path landing on a supported meta terminal is sound`() {
        val target = bp()
        val own = bp(relations = mapOf("rel" to relation("target")))
        val defs = mapOf("own" to own, "target" to target)
        assertNull(mirrorPathFindings("own", "m", MirrorPropertyDefinition("T", "rel.\$title"), own, defs))
        assertNull(mirrorPathFindings("own", "m", MirrorPropertyDefinition("T", "rel.\$team"), own, defs))
    }

    @Test
    fun `a mirror path landing on a genuine property of the target is sound`() {
        val target = bp(schema = BlueprintSchema(properties = mapOf("name" to stringProp())))
        val own = bp(relations = mapOf("rel" to relation("target")))
        assertNull(mirrorPathFindings("own", "m", MirrorPropertyDefinition("T", "rel.name"), own, mapOf("own" to own, "target" to target)))
    }

    @Test
    fun `a 1-segment mirror path walks no hop and reads its terminal off the own blueprint`() {
        // `mirrorValue` drops only the LAST segment as the terminal: a relation and a same-named property make a working self-mirror.
        val own = bp(schema = BlueprintSchema(properties = mapOf("rel" to stringProp())), relations = mapOf("rel" to relation("own")))
        assertNull(mirrorPathFindings("own", "m", MirrorPropertyDefinition("T", "rel"), own, mapOf("own" to own)))
    }

    @Test
    fun `a 1-segment mirror path whose segment is only a relation is stale`() {
        val own = bp(relations = mapOf("rel" to relation("own")))
        val finding = mirrorPathFindings("own", "m", MirrorPropertyDefinition("T", "rel"), own, mapOf("own" to own))
        assertEquals(MIRROR_PATH_STALE, finding?.code)
    }

    @Test
    fun `a blank mirror path is stale`() {
        val own = bp()
        assertEquals(MIRROR_PATH_STALE, mirrorPathFindings("own", "m", MirrorPropertyDefinition("T", " "), own, mapOf("own" to own))?.code)
    }

    @Test
    fun `a mirror terminal naming an unsupported meta-property is stale`() {
        val target = bp()
        val own = bp(relations = mapOf("rel" to relation("target")))
        val finding = mirrorPathFindings(
            "own", "m", MirrorPropertyDefinition("T", "rel.\$createdBy"), own, mapOf("own" to own, "target" to target),
        )
        assertEquals(MIRROR_PATH_STALE, finding?.code)
    }

    @Test
    fun `a mirror terminal naming a computed id of the landed blueprint is stale`() {
        val target = bp(mirrorProperties = mapOf("m2" to MirrorPropertyDefinition("T2", "x.\$title")))
        val own = bp(relations = mapOf("rel" to relation("target")))
        val finding = mirrorPathFindings("own", "m", MirrorPropertyDefinition("T", "rel.m2"), own, mapOf("own" to own, "target" to target))
        assertEquals(MIRROR_PATH_STALE, finding?.code)
    }

    @Test
    fun `a mirror terminal naming an unknown property of the landed blueprint is stale`() {
        val target = bp()
        val own = bp(relations = mapOf("rel" to relation("target")))
        val defs = mapOf("own" to own, "target" to target)
        val finding = mirrorPathFindings("own", "m", MirrorPropertyDefinition("T", "rel.ghost"), own, defs)
        assertEquals(MIRROR_PATH_STALE, finding?.code)
    }

    @Test
    fun `a mirror hop naming an unknown relation is stale`() {
        val own = bp()
        val finding = mirrorPathFindings("own", "m", MirrorPropertyDefinition("T", "ghostRel.name"), own, mapOf("own" to own))
        assertEquals(MIRROR_PATH_STALE, finding?.code)
    }

    @Test
    fun `a mirror hop targeting an inactive blueprint is stale`() {
        val own = bp(relations = mapOf("rel" to relation("ghost")))
        val finding = mirrorPathFindings("own", "m", MirrorPropertyDefinition("T", "rel.name"), own, mapOf("own" to own))
        assertEquals(MIRROR_PATH_STALE, finding?.code)
    }

    // ---------------------------------------------------------------------------------------
    // aggregationFindings / aggregationPathFindings (AGGREGATION_PATH_STALE)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a forward pathFilter that lands cleanly at the aggregation target is sound`() {
        val system = bp()
        val service = bp(relations = mapOf("system" to relation("system")))
        val workload = bp(relations = mapOf("service" to relation("service")))
        val agg = AggregationPropertyDefinition(
            title = "T", target = "system", calculationSpec = calcSpec(),
            pathFilter = listOf(pathFilterEntry("workload", listOf("service", "system"))),
        )
        val defs = mapOf("workload" to workload, "service" to service, "system" to system)
        assertTrue(aggregationFindings("workload", workload, "agg", agg, defs).isEmpty())
    }

    @Test
    fun `a reverse pathFilter that lands back at this blueprint is sound - the workload-service-system shape`() {
        val system = bp()
        val service = bp(relations = mapOf("system" to relation("system")))
        val workload = bp(relations = mapOf("service" to relation("service")))
        val agg = AggregationPropertyDefinition(
            title = "T", target = "workload", calculationSpec = calcSpec(),
            pathFilter = listOf(pathFilterEntry("workload", listOf("service", "system"))),
        )
        val defs = mapOf("workload" to workload, "service" to service, "system" to system)
        assertTrue(aggregationFindings("system", system, "agg", agg, defs).isEmpty())
    }

    @Test
    fun `a forward pathFilter hop naming an unknown relation is stale`() {
        val system = bp()
        val service = bp() // no longer has the "system" relation
        val workload = bp(relations = mapOf("service" to relation("service")))
        val agg = AggregationPropertyDefinition(
            title = "T", target = "system", calculationSpec = calcSpec(),
            pathFilter = listOf(pathFilterEntry("workload", listOf("service", "system"))),
        )
        val defs = mapOf("workload" to workload, "service" to service, "system" to system)
        assertEquals(listOf(AGGREGATION_PATH_STALE), aggregationFindings("workload", workload, "agg", agg, defs).map { it.code })
    }

    @Test
    fun `a reverse pathFilter that does not end back at this blueprint is stale`() {
        val system = bp()
        val other = bp()
        val service = bp(relations = mapOf("system" to relation("system")))
        val workload = bp(relations = mapOf("service" to relation("service")))
        val agg = AggregationPropertyDefinition(
            title = "T", target = "workload", calculationSpec = calcSpec(),
            pathFilter = listOf(pathFilterEntry("workload", listOf("service", "system"))),
        )
        val defs = mapOf("workload" to workload, "service" to service, "system" to system, "other" to other)
        // ownIdentifier is "other" — the reverse walk still lands at "system", never "other".
        assertEquals(listOf(AGGREGATION_PATH_STALE), aggregationFindings("other", other, "agg", agg, defs).map { it.code })
    }

    @Test
    fun `a pathFilter entry with a fromBlueprint that is neither this blueprint nor the target is stale`() {
        val system = bp()
        val other = bp()
        val workload = bp()
        val agg = AggregationPropertyDefinition(
            title = "T", target = "system", calculationSpec = calcSpec(),
            pathFilter = listOf(pathFilterEntry("other", listOf("x"))),
        )
        val defs = mapOf("workload" to workload, "system" to system, "other" to other)
        assertEquals(listOf(AGGREGATION_PATH_STALE), aggregationFindings("workload", workload, "agg", agg, defs).map { it.code })
    }

    @Test
    fun `a pathFilter entry missing fromBlueprint or carrying an empty path is stale`() {
        val workload = bp()
        val missingFrom = AggregationPropertyDefinition(
            title = "T", target = "system", calculationSpec = calcSpec(),
            pathFilter = listOf(buildJsonObject { put("path", buildJsonArray { add(JsonPrimitive("x")) }) }),
        )
        assertEquals(1, aggregationFindings("workload", workload, "agg", missingFrom, mapOf("workload" to workload)).size)

        val emptyPath = AggregationPropertyDefinition(
            title = "T", target = "system", calculationSpec = calcSpec(),
            pathFilter = listOf(pathFilterEntry("workload", emptyList())),
        )
        assertEquals(1, aggregationFindings("workload", workload, "agg", emptyPath, mapOf("workload" to workload)).size)
    }

    // ---------------------------------------------------------------------------------------
    // aggregationPropertyFindings (AGGREGATION_PROPERTY_STALE)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a property-calculation aggregation whose target property is not type number is stale`() {
        val target = bp(schema = BlueprintSchema(properties = mapOf("name" to stringProp())))
        val spec = calcSpec(by = "property", func = "sum", property = "name")
        val findings = aggregationPropertyFindings("f", AggregationPropertyDefinition("T", "target", spec), target)
        assertEquals(listOf(AGGREGATION_PROPERTY_STALE), findings.map { it.code })
    }

    @Test
    fun `a property-calculation aggregation over a number property is sound`() {
        val target = bp(schema = BlueprintSchema(properties = mapOf("count" to numberProp())))
        val spec = calcSpec(by = "property", func = "sum", property = "count")
        assertTrue(aggregationPropertyFindings("f", AggregationPropertyDefinition("T", "target", spec), target).isEmpty())
    }

    @Test
    fun `measureTimeBy naming a meta timestamp is always sound`() {
        val target = bp()
        val spec = calcSpec(func = "average", measureTimeBy = "\$createdAt")
        assertTrue(aggregationPropertyFindings("f", AggregationPropertyDefinition("T", "target", spec), target).isEmpty())
    }

    @Test
    fun `measureTimeBy naming an unknown target property is stale`() {
        val target = bp()
        val spec = calcSpec(func = "average", measureTimeBy = "deployedAt")
        val findings = aggregationPropertyFindings("f", AggregationPropertyDefinition("T", "target", spec), target)
        assertEquals(listOf(AGGREGATION_PROPERTY_STALE), findings.map { it.code })
    }

    @Test
    fun `measureTimeBy naming a real target property is sound`() {
        val target = bp(schema = BlueprintSchema(properties = mapOf("deployedAt" to stringProp())))
        val spec = calcSpec(func = "average", measureTimeBy = "deployedAt")
        assertTrue(aggregationPropertyFindings("f", AggregationPropertyDefinition("T", "target", spec), target).isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // calculationFindings (CALCULATION_COMPILE_FAILED / CALCULATION_QUARANTINED)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `calculationFindings reports nothing for an Ok verdict`() {
        val def = CalculationPropertyDefinition("T", "string", calculation = ".")
        assertNull(calculationFindings("id", "bp", def) { _, _ -> CalculationVerdict.Ok })
    }

    @Test
    fun `calculationFindings reports the compile message on a CompileFailed verdict`() {
        val def = CalculationPropertyDefinition("T", "string", calculation = "((")
        val finding = calculationFindings("id", "bp", def) { _, _ -> CalculationVerdict.CompileFailed("boom") }
        assertEquals(CALCULATION_COMPILE_FAILED, finding?.code)
        assertEquals("boom", finding?.message)
    }

    @Test
    fun `calculationFindings reports quarantine on a Quarantined verdict`() {
        val def = CalculationPropertyDefinition("T", "string", calculation = ".")
        val finding = calculationFindings("id", "bp", def) { _, _ -> CalculationVerdict.Quarantined }
        assertEquals(CALCULATION_QUARANTINED, finding?.code)
    }

    // ---------------------------------------------------------------------------------------
    // ownershipPathFindings (OWNERSHIP_PATH_STALE)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an ownership path landing early on a Direct blueprint is fine, even with more segments left`() {
        val direct = bp(ownership = OwnershipDefinition(type = "Direct"))
        // A 2-segment path whose SECOND hop ("ghostRel") does not even exist on "direct" —
        // irrelevant, since the runtime walk (and this static twin) stops at hop 1's landing.
        val own = bp(
            relations = mapOf("owner" to relation("direct")),
            ownership = OwnershipDefinition(type = "Inherited", path = "owner.ghostRel"),
        )
        assertNull(ownershipPathFindings("own", own, mapOf("own" to own, "direct" to direct)))
    }

    @Test
    fun `an ownership path exhausted while still Inherited is stale`() {
        val mid = bp(ownership = OwnershipDefinition(type = "Inherited", path = "irrelevant"))
        val own = bp(relations = mapOf("owner" to relation("mid")), ownership = OwnershipDefinition(type = "Inherited", path = "owner"))
        val finding = ownershipPathFindings("own", own, mapOf("own" to own, "mid" to mid))
        assertEquals(OWNERSHIP_PATH_STALE, finding?.code)
    }

    @Test
    fun `an ownership hop two levels deep naming an unknown relation is stale`() {
        val mid = bp(ownership = OwnershipDefinition(type = "Inherited", path = "x")) // still Inherited, so hop 2 IS reached
        val own = bp(
            relations = mapOf("owner" to relation("mid")),
            ownership = OwnershipDefinition(type = "Inherited", path = "owner.sub"),
        )
        val finding = ownershipPathFindings("own", own, mapOf("own" to own, "mid" to mid))
        assertEquals(OWNERSHIP_PATH_STALE, finding?.code)
    }

    @Test
    fun `an ownership hop targeting a many relation is stale`() {
        val target = bp()
        val own = bp(
            relations = mapOf("owner" to relation("target", many = true)),
            ownership = OwnershipDefinition(type = "Inherited", path = "owner"),
        )
        val finding = ownershipPathFindings("own", own, mapOf("own" to own, "target" to target))
        assertEquals(OWNERSHIP_PATH_STALE, finding?.code)
    }

    @Test
    fun `an ownership hop targeting an inactive blueprint is stale`() {
        val own = bp(relations = mapOf("owner" to relation("ghost")), ownership = OwnershipDefinition(type = "Inherited", path = "owner"))
        assertEquals(OWNERSHIP_PATH_STALE, ownershipPathFindings("own", own, mapOf("own" to own))?.code)
    }

    @Test
    fun `a Direct or absent ownership blueprint is never checked`() {
        val direct = bp(ownership = OwnershipDefinition(type = "Direct"))
        assertNull(ownershipPathFindings("own", direct, mapOf("own" to direct)))
        val absent = bp()
        assertNull(ownershipPathFindings("own", absent, mapOf("own" to absent)))
    }

    // ---------------------------------------------------------------------------------------
    // ownershipFinding (OWNERSHIP_UNRESOLVED + the suppression rule)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `ownershipFinding flags an Inherited entity whose effective team is empty`() {
        val inherited = bp(ownership = OwnershipDefinition(type = "Inherited", path = "owner"))
        assertEquals(OWNERSHIP_UNRESOLVED, ownershipFinding(inherited, emptyList(), pathStale = false)?.code)
    }

    @Test
    fun `ownershipFinding is suppressed when the blueprint's own path is already stale`() {
        val inherited = bp(ownership = OwnershipDefinition(type = "Inherited", path = "owner"))
        assertNull(ownershipFinding(inherited, emptyList(), pathStale = true))
    }

    @Test
    fun `ownershipFinding never fires with a resolved team, nor for Direct-absent ownership`() {
        val inherited = bp(ownership = OwnershipDefinition(type = "Inherited", path = "owner"))
        assertNull(ownershipFinding(inherited, listOf("teamA"), pathStale = false))
        assertNull(ownershipFinding(bp(ownership = OwnershipDefinition(type = "Direct")), emptyList(), pathStale = false))
        assertNull(ownershipFinding(bp(), emptyList(), pathStale = false))
    }

    // ---------------------------------------------------------------------------------------
    // savedQueryDiagnostics
    // ---------------------------------------------------------------------------------------

    @Test
    fun `savedQueryDiagnostics reports a syntax error`() {
        val diagnostics = savedQueryDiagnostics("MATCH (", QuerySchema(emptyMap(), emptySet()))
        assertTrue(diagnostics.isNotEmpty())
    }

    @Test
    fun `savedQueryDiagnostics reports an unknown label with a suggestion`() {
        val schema = QuerySchema(mapOf("service" to GraphBlueprint("service", "Service", BlueprintDefinition(), emptyMap())), emptySet())
        val diagnostics = savedQueryDiagnostics("MATCH (a:`servics`) RETURN a", schema)
        val finding = diagnostics.single { it.code == QueryDiagnosticCodes.UNKNOWN_LABEL }
        assertEquals("service", finding.suggestion)
    }

    @Test
    fun `savedQueryDiagnostics is empty for a valid query`() {
        val schema = QuerySchema(mapOf("service" to GraphBlueprint("service", "Service", BlueprintDefinition(), emptyMap())), emptySet())
        assertTrue(savedQueryDiagnostics("MATCH (a:`service`) RETURN a", schema).isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // entityErrorsReport: narrowing, counters, omission
    // ---------------------------------------------------------------------------------------

    @Test
    fun `entityErrorsReport resolves against the whole workspace, counts everyone, and omits clean rows`() {
        val bpB = bp()
        val bpA = bp(
            relations = mapOf("rel" to relation("bpB")),
            mirrorProperties = mapOf("m" to MirrorPropertyDefinition("T", "rel.\$title")),
        )
        // Only bpA is a CANDIDATE (the "blueprint" filter narrowed to it) — resolving its mirror
        // path against bpB (outside the candidate set) must still succeed.
        val candidates = listOf(EntityErrorBlueprintCandidate(1u, "bpA", "A", bpA, sourceUrl = "https://example.com/bpA"))

        val cleanEntity = EntityErrorEntitySubject(1u, 1u, "bpA", "A", "e1", "E1", emptyList(), emptyList(), "https://example.com/e1")
        val staleEntity = EntityErrorEntitySubject(
            2u, 1u, "bpA", "A", "e2", "E2", emptyList(),
            listOf(EntityFinding("REQUIRED_MISSING", "properties.x", "Required property 'x' is missing")),
            "https://example.com/e2",
        )

        val cleanQuery = SavedQueryCandidate(1u, "z-clean", SavedEntityQueryVisibility.PRIVATE, "MATCH (a) RETURN a", 1u)
        val brokenQuery = SavedQueryCandidate(2u, "a-broken", SavedEntityQueryVisibility.PUBLIC, "MATCH (", 2u)

        val subjects = EntityErrorSubjects(
            entities = listOf(cleanEntity, staleEntity),
            blueprintCandidates = candidates,
            definitionsByIdentifier = mapOf("bpA" to bpA, "bpB" to bpB),
            savedQueries = listOf(cleanQuery, brokenQuery),
            querySchema = QuerySchema(emptyMap(), emptySet()),
        )
        val report = entityErrorsReport(subjects) { _, _ -> CalculationVerdict.Ok }

        assertTrue(report.blueprints.isEmpty(), "bpA's mirror resolves against the WHOLE workspace, never flagging a narrowed-out bpB")
        assertEquals(listOf("e2"), report.entities.map { it.identifier })
        assertEquals(listOf("a-broken"), report.savedQueries.map { it.name })
        assertEquals(2, report.checkedEntities)
        assertEquals(1, report.checkedBlueprints)
        assertEquals(2, report.checkedSavedQueries)
    }

    // ---------------------------------------------------------------------------------------
    // sourceMissingFinding / entityErrorsReport (SOURCE_MISSING, 2.9.1)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `sourceMissingFinding flags a null or blank sourceUrl and nothing else, naming the subject`() {
        assertEquals("source", sourceMissingFinding(null, "entity")?.field)
        assertEquals(SOURCE_MISSING, sourceMissingFinding(null, "entity")?.code)
        assertEquals("This entity has no source reference", sourceMissingFinding(null, "entity")?.message)
        assertEquals("This blueprint has no source reference", sourceMissingFinding(null, "blueprint")?.message)
        assertEquals(SOURCE_MISSING, sourceMissingFinding("", "entity")?.code)
        assertNull(sourceMissingFinding("https://example.com/e", "entity"))
    }

    @Test
    fun `entityErrorsReport reports SOURCE_MISSING for a source-less entity as its last finding`() {
        val bpA = bp()
        val candidates = listOf(EntityErrorBlueprintCandidate(1u, "bpA", "A", bpA, sourceUrl = "https://example.com/bpA"))
        val sourceless = EntityErrorEntitySubject(1u, 1u, "bpA", "A", "e1", "E1", emptyList(), emptyList(), sourceUrl = null)
        val subjects = EntityErrorSubjects(
            entities = listOf(sourceless),
            blueprintCandidates = candidates,
            definitionsByIdentifier = mapOf("bpA" to bpA),
            savedQueries = emptyList(),
            querySchema = QuerySchema(emptyMap(), emptySet()),
        )
        val report = entityErrorsReport(subjects) { _, _ -> CalculationVerdict.Ok }
        val row = report.entities.single { it.identifier == "e1" }
        assertEquals(listOf(SOURCE_MISSING), row.findings.map { it.code })
        assertEquals("source", row.findings.single().field)
    }

    @Test
    fun `entityErrorsReport omits an entity that carries a source and nothing else`() {
        val bpA = bp()
        val candidates = listOf(EntityErrorBlueprintCandidate(1u, "bpA", "A", bpA, sourceUrl = "https://example.com/bpA"))
        val sourced = EntityErrorEntitySubject(1u, 1u, "bpA", "A", "e1", "E1", emptyList(), emptyList(), "https://example.com/e1")
        val subjects = EntityErrorSubjects(
            entities = listOf(sourced),
            blueprintCandidates = candidates,
            definitionsByIdentifier = mapOf("bpA" to bpA),
            savedQueries = emptyList(),
            querySchema = QuerySchema(emptyMap(), emptySet()),
        )
        val report = entityErrorsReport(subjects) { _, _ -> CalculationVerdict.Ok }
        assertTrue(report.entities.isEmpty())
    }

    @Test
    fun `entityErrorsReport reports a stale, source-less entity's stale finding then SOURCE_MISSING`() {
        val bpA = bp()
        val candidates = listOf(EntityErrorBlueprintCandidate(1u, "bpA", "A", bpA, sourceUrl = "https://example.com/bpA"))
        val staleAndSourceless = EntityErrorEntitySubject(
            1u, 1u, "bpA", "A", "e1", "E1", emptyList(),
            listOf(EntityFinding("REQUIRED_MISSING", "properties.x", "Required property 'x' is missing")),
            sourceUrl = null,
        )
        val subjects = EntityErrorSubjects(
            entities = listOf(staleAndSourceless),
            blueprintCandidates = candidates,
            definitionsByIdentifier = mapOf("bpA" to bpA),
            savedQueries = emptyList(),
            querySchema = QuerySchema(emptyMap(), emptySet()),
        )
        val report = entityErrorsReport(subjects) { _, _ -> CalculationVerdict.Ok }
        val row = report.entities.single { it.identifier == "e1" }
        assertEquals(listOf("REQUIRED_MISSING", SOURCE_MISSING), row.findings.map { it.code })
    }

    @Test
    fun `entityErrorsReport reports a source-less blueprint's SOURCE_MISSING last, after a stale computed finding`() {
        val bpA = bp(mirrorProperties = mapOf("m" to MirrorPropertyDefinition("T", "unknownRel.title")))
        val candidates = listOf(EntityErrorBlueprintCandidate(1u, "bpA", "A", bpA, sourceUrl = null))
        val subjects = EntityErrorSubjects(
            entities = emptyList(),
            blueprintCandidates = candidates,
            definitionsByIdentifier = mapOf("bpA" to bpA),
            savedQueries = emptyList(),
            querySchema = QuerySchema(emptyMap(), emptySet()),
        )
        val report = entityErrorsReport(subjects) { _, _ -> CalculationVerdict.Ok }
        val row = report.blueprints.single { it.identifier == "bpA" }
        assertEquals(listOf(MIRROR_PATH_STALE, SOURCE_MISSING), row.findings.map { it.code })
    }

    @Test
    fun `entityErrorsReport omits a sourced blueprint with nothing else wrong`() {
        val bpA = bp()
        val candidates = listOf(EntityErrorBlueprintCandidate(1u, "bpA", "A", bpA, sourceUrl = "https://example.com/bpA"))
        val subjects = EntityErrorSubjects(
            entities = emptyList(),
            blueprintCandidates = candidates,
            definitionsByIdentifier = mapOf("bpA" to bpA),
            savedQueries = emptyList(),
            querySchema = QuerySchema(emptyMap(), emptySet()),
        )
        val report = entityErrorsReport(subjects) { _, _ -> CalculationVerdict.Ok }
        assertTrue(report.blueprints.isEmpty())
    }

    @Test
    fun `entityErrorsReport reports a source-less _team candidate like any other blueprint row`() {
        val team = bp()
        val candidates = listOf(EntityErrorBlueprintCandidate(1u, "_team", "Team", team, sourceUrl = null))
        val subjects = EntityErrorSubjects(
            entities = emptyList(),
            blueprintCandidates = candidates,
            definitionsByIdentifier = mapOf("_team" to team),
            savedQueries = emptyList(),
            querySchema = QuerySchema(emptyMap(), emptySet()),
        )
        val report = entityErrorsReport(subjects) { _, _ -> CalculationVerdict.Ok }
        val row = report.blueprints.single { it.identifier == "_team" }
        assertEquals(listOf(SOURCE_MISSING), row.findings.map { it.code })
    }
}
