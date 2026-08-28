package ch.nokillswit

import ch.nokillswit.catalog.CrossCheckReport
import ch.nokillswit.catalog.CrossCheckStatus
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
 * The cross-check endpoints. The report spans EVERY file in the shared container, so every
 * assertion is scoped to this test's unique-named files and counters are only ever `>=`.
 */
class CrossCheckTest {


    private suspend fun HttpClient.report(): CrossCheckReport =
        get("/api/v1/catalog-files/cross-check").body()

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
        client.delete("/api/v1/catalog-files/$doomedId")

        val report = client.report()
        assertTrue(report.checkedFiles >= 3)
        assertTrue(report.checkedReferences >= 4, "owner refs count too")
        val mine = report.findings.filter { it.fileName == source }
        // The stored component AND the stored group both resolve; only the deleted one is MISSING.
        assertEquals(
            listOf("component:$ns/$doomed" to CrossCheckStatus.MISSING),
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
        client.delete("/api/v1/catalog-files/$defaultParentId")

        val findings = client.report().findings.filter { it.reference == parent }
        // team-a still holds its parent → resolves; the default-namespace child lost its own.
        assertEquals(listOf(inDefault), findings.map { it.fileName })
        assertEquals(listOf(CrossCheckStatus.MISSING), findings.map { it.status })
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
                it.fileName == source && it.field == "spec.dependsOn" && it.status == CrossCheckStatus.MISSING
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
                "/api/v1/catalog-files/check",
                componentFile(uniqueEntityName("kindless")).let {
                    it.copy(spec = it.spec.copy(dependsOn = listOf(target)))
                },
            )
            assertEquals(HttpStatusCode.OK, response.status)
            val mine = response.body<DocumentCheckReport>().findings.filter { it.field == "spec.dependsOn" }
            assertEquals(listOf(CrossCheckStatus.KIND_REQUIRED), mine.map { it.status })
        }

    @Test
    fun `the check flags references to kinds a field does not allow as WRONG_KIND`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        // Non-stored kinds (Template, custom) and a stored-but-disallowed kind are all
        // WRONG_KIND for dependsOn (Component/Resource only); un-storable → checked ad hoc.
        val response = client.postJson(
            "/api/v1/catalog-files/check",
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
        assertTrue(mine.all { it.status == CrossCheckStatus.WRONG_KIND })
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
            "/api/v1/catalog-files/$personId",
            userFile(person, namespace = ns, memberOf = listOf(team)),
        )
        client.delete("/api/v1/catalog-files/$parentId")

        val findings = client.report().findings.filter { it.fileNamespace == ns }
        // memberOf → the stored group, members → the stored user: both resolve. The group's
        // parent was deleted → MISSING with the group default kind.
        assertEquals(
            listOf(Triple(team, "spec.parent", CrossCheckStatus.MISSING)),
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
                it.fileName == source && it.field == "spec.dependsOn" && it.status == CrossCheckStatus.MISSING
            },
        )
        client.delete("/api/v1/catalog-files/$targetId")
        val after = client.report().findings.filter { it.fileName == source }
        assertEquals(listOf(CrossCheckStatus.MISSING), after.filter { it.field == "spec.dependsOn" }.map { it.status })
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
        val response = client.post("/api/v1/catalog-files/check") {
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
            listOf("component:$ghost" to CrossCheckStatus.MISSING),
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
            "/api/v1/catalog-files/check",
            componentFile(name, namespace = ns).let {
                it.copy(spec = it.spec.copy(subcomponentOf = "component:$ns/$name", dependsOn = listOf("component:$name")))
            },
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val mine = response.body<DocumentCheckReport>().findings
        assertEquals(
            listOf(
                "spec.subcomponentOf" to CrossCheckStatus.SELF_REFERENCE,
                "spec.dependsOn" to CrossCheckStatus.SELF_REFERENCE,
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
            listOf("component:$ns/$name" to CrossCheckStatus.SELF_REFERENCE),
            findings.map { it.reference to it.status },
        )
    }

    @Test
    fun `the literal cross-check segment does not fall into the id route`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("crosscheck")
        // Would be a 400 ("id must be a UInt") if {id} captured the literal segment.
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/catalog-files/cross-check").status)
    }

    @Test
    fun `cross-check endpoints require authentication`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/catalog-files/cross-check").status)
        val check = client.postJson("/api/v1/catalog-files/check", componentFile("x"))
        assertEquals(HttpStatusCode.Unauthorized, check.status)
    }
}
