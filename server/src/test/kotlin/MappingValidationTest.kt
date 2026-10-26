package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintDefinition
import ch.nokillswit.blueprints.blueprintJson
import ch.nokillswit.catalog.RegistrySnapshot
import ch.nokillswit.conversion.MappingDriftCode
import ch.nokillswit.conversion.MappingFindingCode
import ch.nokillswit.conversion.MappingVerdict
import ch.nokillswit.conversion.validateMapping
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `conversion/MappingValidation.kt` is pure — (document, blueprints, registry snapshot) in, a
 * verdict out — so this suite needs no database: a tiny hand-built world (two Port blueprints
 * and a registry snapshot) plus one fully valid baseline document, and every test BREAKS that
 * baseline in exactly one way and pins the code and JSON-pointer path of the single finding it
 * must produce. The commerce-payments default mapping against the real registries is
 * [SampleBlueprintsTest]'s job.
 */
class MappingValidationTest {

    private fun blueprint(json: String): BlueprintDefinition = blueprintJson.decodeFromString(json)

    private val world: Map<String, BlueprintDefinition> = mapOf(
        "svc" to blueprint(
            """
            {"schema": {"properties": {
               "type": {"type": "string", "enum": ["service", "website"]},
               "lifecycle": {"type": "string", "enum": ["experimental", "production"]},
               "repo": {"type": "string", "format": "url"},
               "tier": {"type": "string", "enum": ["gold", "silver"]},
               "pii": {"type": "boolean"},
               "stack": {"type": "array", "uniqueItems": true, "items": {"type": "string", "enum": ["java", "kotlin", "sql"]}},
               "links": {"type": "array", "items": {"type": "object"}},
               "note": {"type": "string"},
               "replicas": {"type": "number"},
               "external": {"type": "boolean"}}},
             "relations": {
               "system": {"title": "System", "target": "sys", "required": false, "many": false},
               "deps": {"title": "Deps", "target": "svc", "required": false, "many": true}},
             "mirrorProperties": {"mirrored": {"title": "M", "path": "system.${'$'}title"}},
             "ownership": {"type": "Direct"}}
            """.trimIndent(),
        ),
        "sys" to blueprint(
            """
            {"schema": {"properties": {"type": {"type": "string", "enum": ["product", "capability"]}}},
             "relations": {"parent": {"title": "Parent", "target": "sys", "required": false, "many": false}},
             "ownership": {"type": "Direct"}}
            """.trimIndent(),
        ),
        "plain" to blueprint("""{"schema": {"properties": {"name": {"type": "string"}}}}"""),
        // Inherited ownership: its team is computed from the path, so a team rule on it can only EXPORT.
        "inh" to blueprint(
            """
            {"schema": {"properties": {"name": {"type": "string"}}},
             "relations": {"system": {"title": "System", "target": "sys", "required": false, "many": false}},
             "ownership": {"type": "Inherited", "path": "system"}}
            """.trimIndent(),
        ),
        // A string-array property WITHOUT uniqueItems, for the tag-mapping case.
        "loosebp" to blueprint(
            """{"schema": {"properties": {"stack": {"type": "array", "items": {"type": "string", "enum": ["java", "kotlin", "sql"]}}}}}""",
        ),
        // The Group target every `{meta: team}` rule lands on.
        "_team" to blueprint(
            """{"schema": {"properties": {"type": {"type": "string", "enum": ["team"]}}}}""",
        ),
        // A blueprint whose schema REQUIRES a property and a relation — for the lossy-required cases.
        "reqbp" to blueprint(
            """
            {"schema": {"properties": {"must": {"type": "string"}}, "required": ["must"]},
             "relations": {"need": {"title": "Need", "target": "sys", "required": true, "many": false}}}
            """.trimIndent(),
        ),
    )

    private val registry = RegistrySnapshot(
        labels = mapOf(
            "tier" to (listOf("Component") to listOf("gold", "silver")),
            "pii" to (listOf("Component") to listOf("yes", "no")),
        ),
        annotationKeys = mapOf("example.com/source" to listOf("Component")),
        tags = mapOf(
            "java" to ("Languages" to listOf("Component")),
            "kotlin" to ("Languages" to listOf("Component")),
            "sql" to ("Languages" to listOf("Component")),
        ),
        types = mapOf(
            "Component" to listOf("service", "website"),
            "System" to listOf("product", "capability"),
            "Group" to listOf("team"),
        ),
        lifecycles = setOf("experimental", "production"),
        namespaces = setOf("default", "external"),
    )

    /** The baseline's editable parts; a case mutates ONE of them, and [render] assembles the JSON document. */
    private class Doc {
        var head = "\"schemaVersion\": 1, \"apiVersion\": \"backstage.io/v1alpha1\""
        var svcPairHead = "\"blueprint\": \"svc\", \"kind\": \"Component\""
        val svc = mutableListOf(
            """{"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "copy"}""",
            """{"port": {"property": "lifecycle"}, "backstage": {"spec": "lifecycle"}, "codec": "copy"}""",
            """{"port": {"meta": "title"}, "backstage": {"metadata": "title"}, "codec": "copy"}""",
            """{"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"}""",
            """{"port": {"property": "repo"}, "backstage": {"annotation": "example.com/source"}, "codec": "copy", "prefix": "url:"}""",
            """{"port": {"property": "tier"}, "backstage": {"label": "tier"}, "codec": "copy"}""",
            """{"port": {"property": "pii"}, "backstage": {"label": "pii"}, "codec": "valueMap", "map": {"true": "yes", "false": "no"}}""",
            """{"port": {"property": "stack"}, "backstage": {"tags": "Languages"}, "codec": "tagCategory"}""",
            """{"port": {"property": "links"}, "backstage": {"links": "rest"}, "codec": "copy"}""",
            """{"port": {"property": "external"}, "backstage": {"metadata": "namespace"}, "codec": "valueMap",
               "map": {"true": "external", "false": "default"}}""",
            """{"port": {"relation": "system"}, "backstage": {"spec": "system"}, "codec": "ref"}""",
            """{"port": {"relation": "deps"}, "backstage": {"spec": "dependsOn"}, "codec": "ref"}""",
            """{"port": {"reverse": {"blueprint": "svc", "relation": "deps"}}, "backstage": {"spec": "dependencyOf"}, "codec": "derive"}""",
        )
        var svcLossy = """{"properties": ["note", "replicas"],
            "backstage": ["metadata.description", "spec.subcomponentOf", "spec.providesApis", "spec.consumesApis"]}"""
        var sysPairHead = "\"blueprint\": \"sys\", \"kind\": \"System\""
        val sys = mutableListOf(
            """{"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "copy"}""",
            """{"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"}""",
        )
        var sysLossy = """{"relations": ["parent"], "backstage": ["metadata.title", "metadata.description", "spec.domain"]}"""

        /** The Group pair every team rule lands on; switch it off to leave `_team` unmapped. */
        var includeGroupPair = true
        private val groupPair = """{"blueprint": "_team", "kind": "Group",
            "rules": [{"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "copy"}],
            "lossy": {"backstage": ["metadata.title", "metadata.description", "spec.parent", "spec.children", "spec.members",
                                    "profile.displayName", "profile.email", "profile.picture"]}}"""

        /** Whole extra pairs inserted BEFORE the sys pair (so they get indices 1, 2, ...). */
        val extraPairs = mutableListOf<String>()

        fun render(): String {
            val pairs = listOf("""{$svcPairHead, "rules": [${svc.joinToString(", ")}], "lossy": $svcLossy}""") + extraPairs +
                """{$sysPairHead, "rules": [${sys.joinToString(", ")}], "lossy": $sysLossy}""" +
                if (includeGroupPair) listOf(groupPair) else emptyList()
            return """{$head, "pairs": [${pairs.joinToString(", ")}]}"""
        }
    }

    private fun verdict(edit: Doc.() -> Unit = {}): MappingVerdict =
        validateMapping(Json.parseToJsonElement(Doc().apply(edit).render()), world, registry)

    private fun String.swap(old: String, new: String): String {
        assertTrue(old in this, "test fixture drifted: '$old' not found in '$this'")
        return replace(old, new)
    }

    private fun assertFinding(verdict: MappingVerdict, code: MappingFindingCode, path: String) {
        val matches = verdict.findings.filter { it.code == code && it.path == path }
        assertTrue(matches.isNotEmpty(), "expected $code at $path, got ${verdict.findings}")
    }

    private fun assertDrift(verdict: MappingVerdict, code: MappingDriftCode, path: String) {
        val matches = verdict.drift.filter { it.code == code && it.path == path }
        assertTrue(matches.isNotEmpty(), "expected $code at $path, got ${verdict.drift}")
    }

    /** A selector-ed library-style Component pair over the `plain` blueprint, for the selector/vocabulary cases. */
    private fun libraryPair(select: String = """{"spec": "type", "in": ["library"]}""", blueprint: String = "plain") =
        """{"blueprint": "$blueprint", "kind": "Component", "select": $select,
            "rules": [{"backstage": {"spec": "type"}, "codec": "constant", "value": "library"}],
            "lossy": {"properties": ["name"]}}"""

    @Test
    fun `the baseline is clean - the fixture world itself is consistent`() {
        val v = verdict()
        assertEquals(emptyList(), v.findings)
        assertEquals(emptyList(), v.drift)
    }

    // ── SCHEMA ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a document that is not an object is a SCHEMA finding at the root`() {
        val v = validateMapping(Json.parseToJsonElement("[1]"), world, registry)
        assertEquals(listOf(MappingFindingCode.SCHEMA to ""), v.findings.map { it.code to it.path })
    }

    @Test
    fun `an unsupported schemaVersion and a wrong apiVersion are refused`() {
        assertFinding(
            verdict { head = head.swap("\"schemaVersion\": 1", "\"schemaVersion\": 2") },
            MappingFindingCode.SCHEMA,
            "/schemaVersion",
        )
        assertFinding(
            verdict { head = head.swap("backstage.io/v1alpha1", "backstage.io/v1beta9") },
            MappingFindingCode.SCHEMA,
            "/apiVersion",
        )
        assertFinding(verdict { head = "\"apiVersion\": \"backstage.io/v1alpha1\"" }, MappingFindingCode.SCHEMA, "/schemaVersion")
        assertFinding(
            verdict { head = head.swap("\"schemaVersion\": 1", "\"schemaVersion\": \"1\"") },
            MappingFindingCode.SCHEMA,
            "/schemaVersion",
        )
    }

    @Test
    fun `null anywhere, an unknown member and a wrong type are SCHEMA findings with their pointers`() {
        assertFinding(
            verdict { svc[4] = svc[4].swap("\"prefix\": \"url:\"", "\"prefix\": null") },
            MappingFindingCode.SCHEMA,
            "/pairs/0/rules/4/prefix",
        )
        assertFinding(verdict { sysPairHead += ", \"color\": 1" }, MappingFindingCode.SCHEMA, "/pairs/1/color")
        assertFinding(
            verdict { sysPairHead = sysPairHead.swap("\"kind\": \"System\"", "\"kind\": 7") },
            MappingFindingCode.SCHEMA,
            "/pairs/1/kind",
        )
        assertFinding(verdict { svcPairHead += ", \"select\": null" }, MappingFindingCode.SCHEMA, "/pairs/0/select")
        assertFinding(verdict { svcLossy = """{"properties": null}""" }, MappingFindingCode.SCHEMA, "/pairs/0/lossy/properties")
        assertFinding(
            verdict { svc[10] = svc[10].swap("\"ref\"", "\"ref\", \"direction\": null") },
            MappingFindingCode.SCHEMA,
            "/pairs/0/rules/10/direction",
        )
    }

    @Test
    fun `every defect of a malformed document is reported in one pass and none is also judged for drift`() {
        val v = verdict {
            head = head.swap("\"schemaVersion\": 1", "\"schemaVersion\": 9")
            svc[10] = svc[10].swap("\"ref\"", "\"nope\"")
        }
        assertTrue(v.findings.size >= 2, "expected several findings, got ${v.findings}")
        assertTrue(v.findings.all { it.code == MappingFindingCode.SCHEMA })
        assertEquals(emptyList(), v.drift)
    }

    @Test
    fun `the reserved jq codec and an unknown codec are refused`() {
        val jq = verdict { svc[0] = svc[0].swap("\"copy\"", "\"jq\"") }
        assertFinding(jq, MappingFindingCode.SCHEMA, "/pairs/0/rules/0/codec")
        assertTrue(jq.findings.any { "not supported yet" in it.message }, "the jq refusal must say so: ${jq.findings}")
        assertFinding(verdict { svc[0] = svc[0].swap("\"copy\"", "\"rot13\"") }, MappingFindingCode.SCHEMA, "/pairs/0/rules/0/codec")
    }

    @Test
    fun `an option that does not belong to the codec is refused, and a rule needs its port unless it is a constant`() {
        assertFinding(
            verdict { svc[3] = svc[3].swap("\"ref\"", "\"ref\", \"prefix\": \"x\"") },
            MappingFindingCode.SCHEMA,
            "/pairs/0/rules/3/prefix",
        )
        assertFinding(
            verdict { svc[3] = """{"backstage": {"spec": "owner"}, "codec": "ref"}""" },
            MappingFindingCode.SCHEMA,
            "/pairs/0/rules/3/port",
        )
        assertFinding(
            verdict { svc[0] = """{"port": {"property": "type"}, "backstage": {"spec": "type"}, "codec": "constant", "value": "x"}""" },
            MappingFindingCode.SCHEMA,
            "/pairs/0/rules/0/port",
        )
        assertFinding(
            verdict { svc[0] = svc[0].swap("\"copy\"", "\"copy\", \"direction\": \"sideways\"") },
            MappingFindingCode.SCHEMA,
            "/pairs/0/rules/0/direction",
        )
        assertFinding(
            verdict { svc[2] = svc[2].swap("{\"meta\": \"title\"}", "{\"meta\": \"title\", \"property\": \"x\"}") },
            MappingFindingCode.SCHEMA,
            "/pairs/0/rules/2/port",
        )
    }

    @Test
    fun `a blueprint mapped twice, and a field both mapped and lossy, are SCHEMA findings`() {
        assertFinding(verdict { sysPairHead = sysPairHead.swap("\"sys\"", "\"svc\"") }, MappingFindingCode.SCHEMA, "/pairs/1/blueprint")
        val both = verdict { svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"note\", \"replicas\", \"tier\"]") }
        assertFinding(both, MappingFindingCode.SCHEMA, "/pairs/0/lossy/properties/2")
    }

    // ── names that do not exist ─────────────────────────────────────────────────────────────

    @Test
    fun `an unknown blueprint, property, relation and kind each have their own code`() {
        assertFinding(
            verdict { sysPairHead = sysPairHead.swap("\"sys\"", "\"ghost\"") },
            MappingFindingCode.UNKNOWN_BLUEPRINT,
            "/pairs/1/blueprint",
        )
        assertFinding(
            verdict { svc[4] = svc[4].swap("\"repo\"", "\"ghost\"") },
            MappingFindingCode.UNKNOWN_PROPERTY,
            "/pairs/0/rules/4/port/property",
        )
        assertFinding(
            verdict { svc[10] = svc[10].swap("\"system\"}, \"backstage\"", "\"ghost\"}, \"backstage\"") },
            MappingFindingCode.UNKNOWN_RELATION,
            "/pairs/0/rules/10/port/relation",
        )
        assertFinding(
            verdict { sysPairHead = sysPairHead.swap("\"System\"", "\"Widget\"") },
            MappingFindingCode.UNKNOWN_KIND,
            "/pairs/1/kind",
        )
    }

    @Test
    fun `lossy ids are checked against the blueprint too`() {
        assertFinding(
            verdict { svcLossy = svcLossy.swap("\"replicas\"]", "\"ghost\"]") },
            MappingFindingCode.UNKNOWN_PROPERTY,
            "/pairs/0/lossy/properties/1",
        )
        assertFinding(
            verdict { svcLossy = svcLossy.swap("{\"properties\"", "{\"relations\": [\"ghost\"], \"properties\"") },
            MappingFindingCode.UNKNOWN_RELATION,
            "/pairs/0/lossy/relations/0",
        )
    }

    @Test
    fun `a derive reverse address must name a real blueprint, relation and this pair as the target`() {
        assertFinding(
            verdict { svc[12] = svc[12].swap("\"blueprint\": \"svc\"", "\"blueprint\": \"ghost\"") },
            MappingFindingCode.UNKNOWN_BLUEPRINT,
            "/pairs/0/rules/12/port/reverse/blueprint",
        )
        assertFinding(
            verdict { svc[12] = svc[12].swap("\"relation\": \"deps\"", "\"relation\": \"ghost\"") },
            MappingFindingCode.UNKNOWN_RELATION,
            "/pairs/0/rules/12/port/reverse/relation",
        )
        // sys.parent targets sys, not svc.
        assertFinding(
            verdict {
                svc[12] = svc[12].swap(
                    "\"blueprint\": \"svc\", \"relation\": \"deps\"",
                    "\"blueprint\": \"sys\", \"relation\": \"parent\"",
                )
            },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/0/rules/12/port/reverse",
        )
    }

    @Test
    fun `computed property ids are never mappable and never lossy`() {
        assertFinding(
            verdict { svc[4] = svc[4].swap("\"repo\"", "\"mirrored\"") },
            MappingFindingCode.COMPUTED_PROPERTY,
            "/pairs/0/rules/4/port/property",
        )
        assertFinding(
            verdict { svcLossy = svcLossy.swap("\"replicas\"]", "\"replicas\", \"mirrored\"]") },
            MappingFindingCode.COMPUTED_PROPERTY,
            "/pairs/0/lossy/properties/2",
        )
    }

    // ── Backstage-side structure ────────────────────────────────────────────────────────────

    @Test
    fun `a spec address outside the kind's allowed fields is FIELD_NOT_ALLOWED`() {
        val path = "/pairs/0/rules/10/backstage/spec"
        // subdomainOf belongs to Domain, not Component.
        assertFinding(
            verdict { svc[10] = svc[10].swap("\"system\"}, \"codec\"", "\"subdomainOf\"}, \"codec\"") },
            MappingFindingCode.FIELD_NOT_ALLOWED,
            path,
        )
        assertFinding(
            verdict { svc[10] = svc[10].swap("\"system\"}, \"codec\"", "\"colour\"}, \"codec\"") },
            MappingFindingCode.FIELD_NOT_ALLOWED,
            path,
        )
        assertFinding(
            verdict { svc[10] = svc[10].swap("\"system\"}, \"codec\"", "\"profile\"}, \"codec\"") },
            MappingFindingCode.FIELD_NOT_ALLOWED,
            path,
        )
        // A Component has no profile.
        assertFinding(
            verdict { svc[2] = svc[2].swap("{\"metadata\": \"title\"}", "{\"profile\": \"email\"}") },
            MappingFindingCode.FIELD_NOT_ALLOWED,
            "/pairs/0/rules/2/backstage/profile",
        )
    }

    @Test
    fun `lossy backstage entries must be fields of the kind`() {
        assertFinding(
            verdict { svcLossy = svcLossy.swap("\"spec.subcomponentOf\"", "\"spec.subdomainOf\"") },
            MappingFindingCode.FIELD_NOT_ALLOWED,
            "/pairs/0/lossy/backstage/1",
        )
        assertFinding(
            verdict { svcLossy = svcLossy.swap("\"spec.subcomponentOf\"", "\"spec.type\"") },
            MappingFindingCode.SCHEMA,
            "/pairs/0/lossy/backstage/1",
        )
    }

    @Test
    fun `a selector must read a plain-text spec field of the kind`() {
        for (field in listOf("owner", "lifecycle")) {
            val v = verdict { sysPairHead += ", \"select\": {\"spec\": \"$field\", \"in\": [\"x\"]}" }
            assertFinding(v, MappingFindingCode.FIELD_NOT_ALLOWED, "/pairs/1/select/spec")
        }
    }

    @Test
    fun `two selector-less pairs for one kind are ambiguous, a selector resolves it`() {
        val plainRule = """{"port": {"property": "name"}, "backstage": {"spec": "type"}, "codec": "copy"}"""
        val twoFallbacks = verdict {
            extraPairs += """{"blueprint": "plain", "kind": "Component", "rules": [$plainRule]}"""
        }
        assertFinding(twoFallbacks, MappingFindingCode.AMBIGUOUS_SELECTOR, "/pairs/1")
        val selected = verdict { extraPairs += libraryPair() }
        assertTrue(selected.findings.none { it.code == MappingFindingCode.AMBIGUOUS_SELECTOR }, "${selected.findings}")
    }

    // ── completeness ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a schema property or relation neither mapped nor lossy is UNDECLARED_FIELD`() {
        assertFinding(
            verdict { svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"note\"]") },
            MappingFindingCode.UNDECLARED_FIELD,
            "/pairs/0/lossy/properties",
        )
        assertFinding(verdict { svc.removeAt(11) }, MappingFindingCode.UNDECLARED_FIELD, "/pairs/0/lossy/relations")
    }

    @Test
    fun `a required spec field no rule writes is REQUIRED_UNMAPPED`() {
        val noLifecycle = verdict {
            svc.removeAt(1)
            svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"note\", \"replicas\", \"lifecycle\"]")
        }
        assertFinding(noLifecycle, MappingFindingCode.REQUIRED_UNMAPPED, "/pairs/0/rules")
        // An import-only rule writes nothing on export, so it does not satisfy the requirement.
        val importOnly = verdict { svc[1] = svc[1].swap("\"copy\"", "\"copy\", \"direction\": \"import\"") }
        assertFinding(importOnly, MappingFindingCode.REQUIRED_UNMAPPED, "/pairs/0/rules")
        // A constant satisfies it.
        val constant = verdict {
            svc[0] = """{"backstage": {"spec": "type"}, "codec": "constant", "value": "service"}"""
            svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"note\", \"replicas\", \"type\"]")
        }
        assertTrue(constant.findings.none { it.code == MappingFindingCode.REQUIRED_UNMAPPED }, "${constant.findings}")
    }

    // ── the codec compatibility matrix ──────────────────────────────────────────────────────

    @Test
    fun `valueMap needs an enum or boolean property and must be a bijection with total keys`() {
        // note is a free string.
        assertFinding(
            verdict {
                svc[6] = svc[6].swap("\"pii\"}, \"backstage\"", "\"note\"}, \"backstage\"")
                svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"replicas\", \"pii\"]")
            },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/0/rules/6/codec",
        )
        assertFinding(
            verdict { svc[6] = svc[6].swap("\"false\": \"no\"", "\"false\": \"yes\"") },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/0/rules/6/codec",
        )
        assertFinding(
            verdict { svc[6] = svc[6].swap(", \"false\": \"no\"", "") },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/0/rules/6/codec",
        )
    }

    @Test
    fun `copy rejects a boolean, ref rejects a property and a many relation onto a single field, link needs a url`() {
        assertFinding(
            verdict {
                svc[5] = svc[5].swap("\"tier\"}, \"backstage\"", "\"pii\"}, \"backstage\"")
                svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"note\", \"replicas\", \"tier\"]")
            },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/0/rules/5/codec",
        )
        assertFinding(
            verdict {
                svc[10] = """{"port": {"property": "note"}, "backstage": {"spec": "system"}, "codec": "ref"}"""
                svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"replicas\"]")
            },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/0/rules/10/codec",
        )
        // deps is many-valued: it cannot fill the single-reference spec.system.
        assertFinding(
            verdict { svc[11] = svc[11].swap("\"dependsOn\"", "\"system\"") },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/0/rules/11/codec",
        )
        val link = { property: String ->
            """{"port": {"property": "$property"}, "backstage": {"link": {"title": "Home"}}, "codec": "link"}"""
        }
        assertFinding(
            verdict {
                svc[8] = link("note")
                svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"replicas\"]")
            },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/0/rules/8/codec",
        )
        // A url-format string property is fine (links itself is then simply lossy).
        val ok = verdict {
            svc[8] = link("repo")
            svc[4] = svc[4].swap("\"repo\"", "\"note\"")
            svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"replicas\", \"links\"]")
        }
        assertTrue(ok.findings.none { it.path == "/pairs/0/rules/8/codec" }, "${ok.findings}")
    }

    @Test
    fun `the team meta field is fine without ownership and with Direct ownership, and export-only for Inherited ownership`() {
        val teamRule = """{"port": {"meta": "team"}, "backstage": {"spec": "owner"}, "codec": "ref"}"""
        val systemPair = { blueprint: String, rule: String ->
            """{"blueprint": "$blueprint", "kind": "System", "rules": [$rule],
                "lossy": {"properties": ["name"], "relations": ["system"],
                          "backstage": ["metadata.title", "metadata.description", "spec.type", "spec.domain"]}}"""
        }
        // `plain` declares no ownership: informational only, the team field is still a valid rule target.
        val noOwnership = verdict { extraPairs += systemPair("plain", teamRule) }
        assertTrue(noOwnership.findings.none { it.path.startsWith("/pairs/1/rules/0") }, "${noOwnership.findings}")
        // `inh` is Inherited: a team rule that would import hits TEAM_NOT_ALLOWED.
        val both = verdict { extraPairs += systemPair("inh", teamRule) }
        assertFinding(both, MappingFindingCode.CODEC_TYPE_MISMATCH, "/pairs/1/rules/0/direction")
        val exportOnly = verdict { extraPairs += systemPair("inh", teamRule.replace("\"ref\"}", "\"ref\", \"direction\": \"export\"}")) }
        assertTrue(exportOnly.findings.none { it.path.startsWith("/pairs/1/rules/0") }, "${exportOnly.findings}")
    }

    @Test
    fun `a reference's far end must be mapped, to a kind the spec field may reference`() {
        // Dropping the Group pair leaves every {meta: team} rule pointing at an unmapped `_team`.
        assertFinding(verdict { includeGroupPair = false }, MappingFindingCode.TARGET_UNMAPPED, "/pairs/0/rules/3/port")
        // svc.system targets `sys` (mapped to System); spec.subcomponentOf may only reference a Component.
        assertFinding(
            verdict { svc[10] = svc[10].swap("\"system\"}, \"codec\"", "\"subcomponentOf\"}, \"codec\"") },
            MappingFindingCode.REF_KIND_MISMATCH,
            "/pairs/0/rules/10/backstage",
        )
        // spec.owner may reference a Group or User only: point the owner rule at the system relation instead.
        assertFinding(
            verdict { svc[3] = """{"port": {"relation": "system"}, "backstage": {"spec": "owner"}, "codec": "ref"}""" },
            MappingFindingCode.REF_KIND_MISMATCH,
            "/pairs/0/rules/3/backstage",
        )
    }

    @Test
    fun `derive fills a list field, never a single-reference one`() {
        assertFinding(
            verdict { svc[12] = svc[12].swap("\"dependencyOf\"", "\"system\"") },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/0/rules/12/codec",
        )
    }

    @Test
    fun `a required Port property or relation cannot be declared lossy`() {
        val bad = verdict {
            extraPairs += """{"blueprint": "reqbp", "kind": "Resource", "rules": [],
                "lossy": {"properties": ["must"], "relations": ["need"], "backstage": ["metadata.title", "metadata.description"]}}"""
        }
        assertFinding(bad, MappingFindingCode.REQUIRED_UNMAPPED, "/pairs/1/lossy/properties/0")
        assertFinding(bad, MappingFindingCode.REQUIRED_UNMAPPED, "/pairs/1/lossy/relations/0")
    }

    @Test
    fun `duplicate lossy entries and duplicate link titles in a pair are SCHEMA findings`() {
        assertFinding(
            verdict { svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"note\", \"note\", \"replicas\"]") },
            MappingFindingCode.SCHEMA,
            "/pairs/0/lossy/properties/1",
        )
        val link = { property: String ->
            """{"port": {"property": "$property"}, "backstage": {"link": {"title": "Home"}}, "codec": "link"}"""
        }
        assertFinding(
            verdict {
                svc += link("repo")
                svc += link("repo")
                svc[4] = svc[4].swap("\"repo\"", "\"note\"")
                svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"replicas\"]")
            },
            MappingFindingCode.SCHEMA,
            "/pairs/0/rules/14/backstage/link/title",
        )
    }

    @Test
    fun `a number property may be copied onto free text, and a free value copied onto a closed label is drift`() {
        val withNumber = verdict {
            svc += """{"port": {"property": "replicas"}, "backstage": {"annotation": "example.com/source"}, "codec": "copy"}"""
            svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"note\"]")
        }
        assertTrue(withNumber.findings.none { it.path == "/pairs/0/rules/13/codec" }, "${withNumber.findings}")
        val free = verdict {
            svc[5] = svc[5].swap("\"tier\"}, \"backstage\"", "\"note\"}, \"backstage\"")
            svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"replicas\", \"tier\"]")
        }
        assertDrift(free, MappingDriftCode.VOCABULARY_DRIFT, "/pairs/0/rules/5/backstage/label")
    }

    @Test
    fun `a string-array property mapped to tags must be uniqueItems`() {
        val loose = verdict {
            extraPairs += """{"blueprint": "loosebp", "kind": "Component",
                "rules": [{"port": {"property": "stack"}, "backstage": {"tags": "Languages"}, "codec": "tagCategory"}]}"""
        }
        assertFinding(loose, MappingFindingCode.CODEC_TYPE_MISMATCH, "/pairs/1/rules/0/codec")
    }

    @Test
    fun `selector pairs must not overlap, and a pair must produce only what its own selector claims`() {
        // (2) two selector pairs of one kind claiming the same value on the same field.
        val twoSelectors = verdict {
            extraPairs += libraryPair()
            extraPairs += libraryPair(blueprint = "inh")
        }
        assertFinding(twoSelectors, MappingFindingCode.AMBIGUOUS_SELECTOR, "/pairs/2/select/in")
        // (3) the selector-less svc pair can export `website`, which this selector pair claims for itself.
        val fallbackClaims = verdict { extraPairs += libraryPair("""{"spec": "type", "in": ["library", "website"]}""") }
        assertFinding(fallbackClaims, MappingFindingCode.AMBIGUOUS_SELECTOR, "/pairs/0/rules/0")
        // (4) a selector pair exporting a value outside its own `in` would be re-imported through another pair.
        val outside = verdict { extraPairs += libraryPair("""{"spec": "type", "in": ["other"]}""") }
        assertFinding(outside, MappingFindingCode.AMBIGUOUS_SELECTOR, "/pairs/1/rules/0")
    }

    @Test
    fun `derive and constant are export-only and tagCategory needs an enum-backed property`() {
        assertFinding(
            verdict { svc[12] = svc[12].swap("\"derive\"", "\"derive\", \"direction\": \"both\"") },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/0/rules/12/direction",
        )
        assertFinding(
            verdict { extraPairs += libraryPair().swap("\"value\": \"library\"", "\"value\": \"library\", \"direction\": \"import\"") },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/1/rules/0/direction",
        )
        assertFinding(
            verdict {
                svc[7] = svc[7].swap("\"stack\"", "\"note\"")
                svcLossy = svcLossy.swap("[\"note\", \"replicas\"]", "[\"replicas\", \"stack\"]")
            },
            MappingFindingCode.CODEC_TYPE_MISMATCH,
            "/pairs/0/rules/7/codec",
        )
    }

    // ── drift ───────────────────────────────────────────────────────────────────────────────

    private fun driftAgainst(changed: RegistrySnapshot): MappingVerdict =
        validateMapping(Json.parseToJsonElement(Doc().render()), world, changed)

    @Test
    fun `a label whose closed values differ from the property enum is VOCABULARY_DRIFT`() {
        val widened = registry.labels + ("tier" to (listOf("Component") to listOf("gold", "silver", "bronze")))
        val v = driftAgainst(registry.copy(labels = widened))
        assertEquals(emptyList(), v.findings, "drift never blocks")
        assertDrift(v, MappingDriftCode.VOCABULARY_DRIFT, "/pairs/0/rules/5/backstage/label")
        assertTrue(v.drift.any { "bronze" in it.message }, "the message names the difference: ${v.drift}")
    }

    @Test
    fun `lifecycle, namespace and tag vocabularies are compared with their registries`() {
        assertDrift(
            driftAgainst(registry.copy(lifecycles = setOf("experimental", "production", "retired"))),
            MappingDriftCode.VOCABULARY_DRIFT,
            "/pairs/0/rules/1/backstage",
        )
        assertDrift(
            driftAgainst(registry.copy(namespaces = setOf("default"))),
            MappingDriftCode.VOCABULARY_DRIFT,
            "/pairs/0/rules/9/backstage",
        )
        assertDrift(
            driftAgainst(registry.copy(tags = registry.tags + ("rust" to ("Languages" to listOf("Component"))))),
            MappingDriftCode.VOCABULARY_DRIFT,
            "/pairs/0/rules/7",
        )
    }

    @Test
    fun `the spec type values a kind's pairs can produce must equal its type dictionary`() {
        // The baseline's svc enum (service, website) IS the Component dictionary.
        assertEquals(emptyList(), verdict().drift)
        // A dictionary that gained `library` is unproduced by the mapping...
        val extended = registry.copy(types = registry.types + ("Component" to listOf("service", "website", "library")))
        assertDrift(driftAgainst(extended), MappingDriftCode.VOCABULARY_DRIFT, "/pairs/0/rules/0")
        // ...until a selector-ed constant pair produces it: the UNION across the kind's pairs is what is compared.
        val union = validateMapping(Json.parseToJsonElement(Doc().apply { extraPairs += libraryPair() }.render()), world, extended)
        assertTrue(union.drift.none { it.code == MappingDriftCode.VOCABULARY_DRIFT }, "${union.drift}")
    }

    @Test
    fun `a selector value outside the type dictionary is drift`() {
        val v = verdict { extraPairs += libraryPair("""{"spec": "type", "in": ["widget"]}""") }
        assertDrift(v, MappingDriftCode.VOCABULARY_DRIFT, "/pairs/1/select/in/0")
    }

    @Test
    fun `an unregistered label, annotation key or tag category is REGISTRY_KEY_MISSING`() {
        assertDrift(
            verdict { svc[5] = svc[5].swap("\"tier\"}", "\"ghost\"}") },
            MappingDriftCode.REGISTRY_KEY_MISSING,
            "/pairs/0/rules/5/backstage/label",
        )
        assertDrift(
            verdict { svc[4] = svc[4].swap("example.com/source", "example.com/ghost") },
            MappingDriftCode.REGISTRY_KEY_MISSING,
            "/pairs/0/rules/4/backstage/annotation",
        )
        assertDrift(
            verdict { svc[7] = svc[7].swap("Languages", "Ghosts") },
            MappingDriftCode.REGISTRY_KEY_MISSING,
            "/pairs/0/rules/7/backstage/tags",
        )
    }

    @Test
    fun `a registered key that does not apply to the pair's kind is REGISTRY_KIND_MISMATCH`() {
        val narrowed = registry.copy(
            labels = registry.labels + ("tier" to (listOf("API") to listOf("gold", "silver"))),
            annotationKeys = mapOf("example.com/source" to listOf("API")),
            tags = registry.tags.mapValues { (_, v) -> v.first to listOf("API") },
        )
        val v = driftAgainst(narrowed)
        assertDrift(v, MappingDriftCode.REGISTRY_KIND_MISMATCH, "/pairs/0/rules/5/backstage/label")
        assertDrift(v, MappingDriftCode.REGISTRY_KIND_MISMATCH, "/pairs/0/rules/4/backstage/annotation")
        assertDrift(v, MappingDriftCode.REGISTRY_KIND_MISMATCH, "/pairs/0/rules/7/backstage/tags")
    }

    @Test
    fun `an allowed Backstage field no rule writes and no lossy entry lists is informational drift`() {
        val v = verdict { svcLossy = svcLossy.swap("\"spec.subcomponentOf\", ", "") }
        assertEquals(emptyList(), v.findings)
        assertDrift(v, MappingDriftCode.BACKSTAGE_FIELD_UNMAPPED, "/pairs/0")
        assertTrue(v.drift.any { "spec.subcomponentOf" in it.message })
    }

    @Test
    fun `the verdict is deterministic - the same inputs give the same lists`() {
        val edit: Doc.() -> Unit = { svc[5] = svc[5].swap("\"tier\"}", "\"ghost\"}") }
        assertEquals(verdict(edit), verdict(edit))
    }
}
