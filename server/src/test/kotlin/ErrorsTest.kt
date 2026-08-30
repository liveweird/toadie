package ch.nokillswit

import ch.nokillswit.catalog.ErrorsReport
import ch.nokillswit.catalog.ErrorStatus
import ch.nokillswit.catalog.DocumentCheckReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Errors-report and ad-hoc check endpoints. The report spans EVERY file in the shared
 * container, so every assertion is scoped to this test's unique-named files and counters are
 * only ever `>=` (or the test filters the report down to its own namespace).
 */
class ErrorsTest {


    private suspend fun HttpClient.report(): ErrorsReport =
        get("$CATALOG_FILES_PATH/errors").body()

    @Test
    fun `resolved component references produce no findings, deletion-orphaned ones are MISSING`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        val ns = uniqueNamespace("xns")
        val target = uniqueEntityName("target")
        val doomed = uniqueEntityName("doomed")
        val source = uniqueEntityName("source")
        val team = uniqueEntityName("team")
        client.createCatalogFile(groupFile(team, namespace = ns))
        client.createCatalogFile(componentFile(target, namespace = ns, owner = team))
        val doomedId = client.createCatalogFile(componentFile(doomed, namespace = ns, owner = team)).id
        client.createCatalogFile(
            componentFile(source, namespace = ns, owner = team).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$ns/$target", "component:$ns/$doomed")))
            },
        )
        // Saves enforce resolution, so the dangling ref is MADE by deleting its target.
        client.delete("$CATALOG_FILES_PATH/$doomedId")

        val report = client.report()
        assertTrue(report.checkedFiles >= 3)
        assertTrue(report.checkedReferences >= 4, "owner refs count too")
        val mine = report.findings.filter { it.fileName == source }
        // The stored component AND the stored group both resolve; only the deleted one is MISSING.
        assertEquals(
            listOf("component:$ns/$doomed" to ErrorStatus.MISSING),
            mine.map { it.reference to it.status },
        )
    }

    @Test
    fun `a namespaceless reference resolves within the referencing file's own namespace`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        val parent = uniqueEntityName("parent")
        val inTeam = uniqueEntityName("child-team")
        val inDefault = uniqueEntityName("child-default")
        // The parent exists in BOTH namespaces at save time; deleting the default-namespace
        // copy leaves each child's bare ref pointing into its OWN namespace.
        TestNamespaces.ensure("team-a")
        client.createCatalogFile(componentFile(parent, namespace = "team-a"))
        val defaultParentId = client.createCatalogFile(componentFile(parent)).id
        client.createCatalogFile(
            componentFile(inTeam, namespace = "team-a").let {
                it.copy(spec = it.spec.copy(subcomponentOf = parent))
            },
        )
        client.createCatalogFile(
            componentFile(inDefault).let { it.copy(spec = it.spec.copy(subcomponentOf = parent)) },
        )
        client.delete("$CATALOG_FILES_PATH/$defaultParentId")

        val findings = client.report().findings.filter { it.reference == parent }
        // team-a still holds its parent → resolves; the default-namespace child lost its own.
        assertEquals(listOf(inDefault), findings.map { it.fileName })
        assertEquals(listOf(ErrorStatus.MISSING), findings.map { it.status })
    }

    @Test
    fun `resolution is case-insensitive across kind, namespace and name`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        val target = uniqueEntityName("Cased")
        val source = uniqueEntityName("caser")
        TestNamespaces.ensure("team-b")
        client.createCatalogFile(componentFile(target, namespace = "team-b"))
        client.createCatalogFile(
            componentFile(source).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("Component:TEAM-B/${target.uppercase()}")))
            },
        )

        assertTrue(
            client.report().findings.none {
                it.fileName == source && it.field == "spec.dependsOn" && it.status == ErrorStatus.MISSING
            },
        )
    }

    @Test
    fun `the check flags a kind-less dependsOn entry as KIND_REQUIRED even when the name exists`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("crosscheck")
            val target = uniqueEntityName("present")
            client.createCatalogFile(componentFile(target))
            // Such a document cannot be STORED (saves enforce resolution) — the ad-hoc check
            // is where the finding surfaces, as the editor types.
            val response = client.postJson(
                "$CATALOG_FILES_PATH/check",
                componentFile(uniqueEntityName("kindless")).let {
                    it.copy(spec = it.spec.copy(dependsOn = listOf(target)))
                },
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val mine = response.body<DocumentCheckReport>().findings.filter { it.field == "spec.dependsOn" }
            assertEquals(listOf(ErrorStatus.KIND_REQUIRED), mine.map { it.status })
        }

    @Test
    fun `the check flags references to kinds a field does not allow as WRONG_KIND`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        // Non-stored kinds (Template, custom) and a stored-but-disallowed kind are all
        // WRONG_KIND for dependsOn (Component/Resource only); un-storable → checked ad hoc.
        val response = client.postJson(
            "$CATALOG_FILES_PATH/check",
            componentFile(uniqueEntityName("external")).let {
                it.copy(
                    spec = it.spec.copy(
                        dependsOn = listOf("template:default/scaffolder", "mycustomkind:thing", "group:default/platform"),
                    ),
                )
            },
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val findings = response.body<DocumentCheckReport>().findings
        val mine = findings.filter { it.field == "spec.dependsOn" }
        assertEquals(3, mine.size)
        assertTrue(mine.all { it.status == ErrorStatus.WRONG_KIND })
        // The default owner (group:default/platform, the test seed) resolves — no owner finding.
        assertTrue(findings.none { it.field == "spec.owner" })
    }

    @Test
    fun `stored groups and users resolve organizational references`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        val ns = uniqueNamespace("orgns")
        val person = uniqueEntityName("person")
        val team = uniqueEntityName("team")
        val parent = uniqueEntityName("doomedparent")
        // The members ↔ memberOf pair is CIRCULAR — under strict resolution it is built by
        // creating the user memberless, then the group, then UPDATING the user (its own
        // identity and the group's are both active by then).
        val parentId = client.createCatalogFile(groupFile(parent, namespace = ns)).id
        val personId = client.createCatalogFile(userFile(person, namespace = ns)).id
        client.createCatalogFile(
            groupFile(team, namespace = ns, members = listOf("user:$ns/$person"), parent = parent),
        )
        client.putJson(
            "$CATALOG_FILES_PATH/$personId",
            userFile(person, namespace = ns, memberOf = listOf(team)),
        )
        client.delete("$CATALOG_FILES_PATH/$parentId")

        val findings = client.report().findings.filter { it.fileNamespace == ns }
        // memberOf → the stored group, members → the stored user: both resolve. The group's
        // parent was deleted → MISSING with the group default kind.
        assertEquals(
            listOf(Triple(team, "spec.parent", ErrorStatus.MISSING)),
            findings.map { Triple(it.fileName, it.field, it.status) },
        )
    }

    @Test
    fun `a soft-deleted target stops resolving`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        val target = uniqueEntityName("doomed")
        val source = uniqueEntityName("bereft")
        val targetId = client.createCatalogFile(componentFile(target)).id
        client.createCatalogFile(
            componentFile(source).let { it.copy(spec = it.spec.copy(dependsOn = listOf("component:$target"))) },
        )

        assertTrue(
            client.report().findings.none {
                it.fileName == source && it.field == "spec.dependsOn" && it.status == ErrorStatus.MISSING
            },
        )
        client.delete("$CATALOG_FILES_PATH/$targetId")
        val after = client.report().findings.filter { it.fileName == source }
        assertEquals(listOf(ErrorStatus.MISSING), after.filter { it.field == "spec.dependsOn" }.map { it.status })
    }

    @Test
    fun `the ad-hoc check reports an unsaved document without validating it`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        val target = uniqueEntityName("adhoc-target")
        client.createCatalogFile(componentFile(target))
        val ghost = uniqueEntityName("adhoc-ghost")

        // Blank name + a half-typed (unparsable) ref: save validation would 400 this document;
        // the check answers 200 with findings for what IS parsable.
        val response = client.post("$CATALOG_FILES_PATH/check") {
            contentType(ContentType.Application.Json)
            setBody(
                componentFile("x").let {
                    it.copy(
                        metadata = it.metadata.copy(name = ""),
                        spec = it.spec.copy(
                            dependsOn = listOf("component:$target", "component:$ghost", "a:b:c"),
                        ),
                    )
                },
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val findings = response.body<DocumentCheckReport>().findings
        assertEquals(
            listOf("component:$ghost" to ErrorStatus.MISSING),
            findings.filter { it.field == "spec.dependsOn" }.map { it.reference to it.status },
        )
    }

    @Test
    fun `the ad-hoc check flags a self-reference as SELF_REFERENCE, saved or not`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        val ns = uniqueNamespace("selfck")
        val name = uniqueEntityName("narcissus")
        // Unsaved document (nothing stored): the self identity comes from the payload, so the
        // verdict is SELF_REFERENCE — not MISSING — for full and short forms alike.
        val response = client.postJson(
            "$CATALOG_FILES_PATH/check",
            componentFile(name, namespace = ns).let {
                it.copy(spec = it.spec.copy(subcomponentOf = "component:$ns/$name", dependsOn = listOf("component:$name")))
            },
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val mine = response.body<DocumentCheckReport>().findings
        assertEquals(
            listOf(
                "spec.subcomponentOf" to ErrorStatus.SELF_REFERENCE,
                "spec.dependsOn" to ErrorStatus.SELF_REFERENCE,
            ),
            mine.map { it.field to it.status },
        )
    }

    @Test
    fun `a legacy stored self-reference surfaces in the workspace report`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        val ns = uniqueNamespace("selfrep")
        val name = uniqueEntityName("legacy-self")
        val id = client.createCatalogFile(componentFile(name, namespace = ns)).id
        // The write path can no longer produce a self-reference — plant one by rewriting the
        // stored content directly (the TestUsers.softDelete bypass precedent), modeling a row
        // saved before the rule existed.
        val selfRef = componentFile(name, namespace = ns).let {
            it.copy(spec = it.spec.copy(subcomponentOf = "component:$ns/$name"))
        }
        TestCatalogFiles.overwriteContent(id, selfRef)

        val findings = client.report().findings.filter { it.fileName == name && it.fileNamespace == ns }
        assertEquals(
            listOf("component:$ns/$name" to ErrorStatus.SELF_REFERENCE),
            findings.map { it.reference to it.status },
        )
    }

    @Test
    fun `the literal errors segment does not fall into the id route`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        // Would be a 400 ("id must be a UInt") if {id} captured the literal segment.
        assertEquals(HttpStatusCode.OK, client.get("$CATALOG_FILES_PATH/errors").status)
    }

    @Test
    fun `the errors and check endpoints require authentication`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("$CATALOG_FILES_PATH/errors").status)
        val check = client.postJson("$CATALOG_FILES_PATH/check", componentFile("x"))
        assertEquals(HttpStatusCode.Unauthorized, check.status)
    }

    @Test
    fun `a malformed check body is a 400 problem`() = testApplication {
        usePostgresTestcontainer()
        val response = seededClient("crosscheck400").postJson("$CATALOG_FILES_PATH/check", "{ not json")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `the check endpoint reports registry findings alongside reference findings`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("checkregistry")
        val ghostLabel = uniqueEntityName("chklbl")
        val file = componentFile(uniqueEntityName("chkdoc"), type = "no-such-type")
            .let { it.copy(metadata = it.metadata.copy(labels = mapOf(ghostLabel to "x"))) }

        val response = client.postJson("$CATALOG_FILES_PATH/check", file)
        assertEquals(HttpStatusCode.OK, response.status)
        val findings = response.body<DocumentCheckReport>().findings
        assertTrue(
            findings.any {
                it.status == ErrorStatus.LABEL_NOT_ALLOWED &&
                    it.field == "metadata.labels" && it.reference == ghostLabel
            },
        )
        assertTrue(
            findings.any {
                it.status == ErrorStatus.TYPE_NOT_ALLOWED &&
                    it.field == "spec.type" && it.reference == "no-such-type"
            },
        )
    }

    @Test
    fun `the workspace report lists a waived file's registry findings`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheckwaived")
        val ghostTag = uniqueTag("xwtag")
        val name = uniqueEntityName("xwaived")
        val file = componentFile(name)
            .let { it.copy(metadata = it.metadata.copy(tags = listOf(ghostTag))) }
        val created = client.postJson("$CATALOG_FILES_PATH?allowInvalid=true", file)
        assertEquals(HttpStatusCode.Created, created.status)
        val id = created.body<ch.nokillswit.catalog.CatalogFileResponse>().id

        val report = client.report()
        assertTrue(
            report.findings.any {
                it.fileId == id && it.status == ErrorStatus.TAG_NOT_ALLOWED &&
                    it.field == "metadata.tags" && it.reference == ghostTag
            },
        )

        client.delete("$CATALOG_FILES_PATH/$id")
    }

    @Test
    fun `the report declares the list's filter set and narrows only what is reported`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("errfilters")
        val ns = uniqueNamespace("erfns")
        val other = uniqueNamespace("erfother")
        val doomed = uniqueEntityName("erf-doomed")
        val insider = uniqueEntityName("erf-in")
        val outsider = uniqueEntityName("erf-out")
        val doomedId = client.createCatalogFile(componentFile(doomed, namespace = ns)).id
        client.createCatalogFile(
            componentFile(insider, namespace = ns).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$ns/$doomed")))
            },
        )
        client.createCatalogFile(
            componentFile(outsider, namespace = other).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$ns/$insider", "component:$ns/$doomed")))
            },
        )
        client.delete("$CATALOG_FILES_PATH/$doomedId")

        // namespace=ns reports only the insider's dangling ref; the outsider's is filtered out
        // — and the counters count the REPORTED set, not the workspace.
        val filtered = client.get("$CATALOG_FILES_PATH/errors?namespace=$ns").body<ErrorsReport>()
        assertEquals(1, filtered.checkedFiles)
        assertEquals(listOf(insider), filtered.findings.map { it.fileName })
        assertEquals("Component", filtered.findings.single().fileKind)

        // The asymmetry: narrowed to the OTHER namespace, the outsider's ref to the (filtered
        // out but stored) insider still resolves — only the genuinely dangling ref is MISSING.
        val outer = client.get("$CATALOG_FILES_PATH/errors?namespace=$other").body<ErrorsReport>()
        assertEquals(
            listOf("component:$ns/$doomed" to ErrorStatus.MISSING),
            outer.findings.filter { it.fileName == outsider }.map { it.reference to it.status },
        )

        // The list's parameter validation rides along (the graph precedent): unparsable owner,
        // orphaned labelValue, repeated single-value param, unknown kind.
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH/errors?owner=a:b:c").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH/errors?labelValue=v1").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("$CATALOG_FILES_PATH/errors?namespace=a&namespace=b").status,
        )
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH/errors?kind=Bogus").status)
    }

    @Test
    fun `a legacy structurally invalid document reports STRUCTURE_INVALID with the validator's message`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("errstruct")
            val ns = uniqueNamespace("erstns")
            val name = uniqueEntityName("erst-legacy")
            val id = client.createCatalogFile(componentFile(name, namespace = ns)).id
            // The write path can no longer store this shape — plant a Component without its
            // required spec.type directly (the overwriteContent bypass), modeling a row saved
            // before a structural rule existed.
            val invalid = componentFile(name, namespace = ns).let {
                it.copy(spec = it.spec.copy(type = null))
            }
            TestCatalogFiles.overwriteContent(id, invalid)

            val findings = client.get("$CATALOG_FILES_PATH/errors?namespace=$ns").body<ErrorsReport>()
                .findings.filter { it.fileId == id }
            assertEquals(listOf(ErrorStatus.STRUCTURE_INVALID), findings.map { it.status })
            val structural = findings.single()
            assertEquals("Component", structural.fileKind)
            assertEquals("document", structural.field)
            assertEquals("", structural.reference)
            assertTrue(structural.message!!.contains("spec.type"))
        }

    @Test
    fun `the pure report skips the namespace check for a blank namespace`() {
        // Stored rows always carry a resolved concrete namespace; the pure function still
        // guards the blank case (the GraphTest pure-call precedent) so it never reports one.
        val file = componentFile("blank-ns", namespace = "")
        val source = ch.nokillswit.catalog.CatalogSource(id = 1u, file = file)
        val report = ch.nokillswit.catalog.errorsReport(
            reported = listOf(source),
            all = listOf(source),
            registries = ch.nokillswit.catalog.RegistrySnapshot(
                labels = emptyMap(),
                annotationKeys = emptyMap(),
                tags = emptyMap(),
                types = mapOf("Component" to listOf("service")),
                lifecycles = setOf("production"),
                namespaces = emptySet(),
            ),
        )
        assertTrue(report.findings.none { it.status == ErrorStatus.NAMESPACE_NOT_ALLOWED })
        assertTrue(report.findings.none { it.status == ErrorStatus.STRUCTURE_INVALID })
    }

    @Test
    fun `a namespace removed after the save reports NAMESPACE_NOT_ALLOWED until re-added`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("errnsgone")
        val ns = uniqueNamespace("ergone")
        val name = uniqueEntityName("ergone-file")
        val id = client.createCatalogFile(componentFile(name, namespace = ns)).id
        TestNamespaces.remove(ns)

        val findings = client.get("$CATALOG_FILES_PATH/errors?name=$name").body<ErrorsReport>().findings
        assertEquals(
            listOf(Triple("metadata.namespace", ns, ErrorStatus.NAMESPACE_NOT_ALLOWED)),
            findings.filter { it.fileId == id }.map { Triple(it.field, it.reference, it.status) },
        )

        // Re-adding the entry clears the finding — membership is live, in both directions.
        TestNamespaces.ensure(ns)
        assertTrue(
            client.get("$CATALOG_FILES_PATH/errors?name=$name").body<ErrorsReport>()
                .findings.none { it.fileId == id },
        )
        client.delete("$CATALOG_FILES_PATH/$id")
    }
}
