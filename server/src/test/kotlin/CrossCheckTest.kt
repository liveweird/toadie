package ch.nokillswit

import ch.nokillswit.catalog.CrossCheckReport
import ch.nokillswit.catalog.CrossCheckStatus
import ch.nokillswit.catalog.DocumentCheckReport
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cross-check endpoints. The report spans EVERY file in the shared container, so every
 * assertion is scoped to this test's unique-named files and counters are only ever `>=`.
 */
class CrossCheckTest {

    private suspend fun ApplicationTestBuilder.userClient(): HttpClient {
        val email = uniqueEmail("crosscheck")
        TestUsers.seed(email = email, password = "pw", role = UserRole.USER)
        return authedClient(email, "pw")
    }

    private suspend fun HttpClient.report(): CrossCheckReport =
        get("/api/v1/catalog-files/cross-check").body()

    @Test
    fun `resolved component references produce no findings, dangling ones are MISSING`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val ns = uniqueEntityName("xns")
        val target = uniqueEntityName("target")
        val source = uniqueEntityName("source")
        val ghost = uniqueEntityName("ghost")
        val team = uniqueEntityName("team")
        client.createCatalogFile(groupFile(team, namespace = ns))
        client.createCatalogFile(componentFile(target, namespace = ns, owner = team))
        client.createCatalogFile(
            componentFile(source, namespace = ns, owner = team).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$ns/$target", "component:$ns/$ghost")))
            },
        )

        val report = client.report()
        assertTrue(report.checkedFiles >= 3)
        assertTrue(report.checkedReferences >= 4, "owner refs count too")
        val mine = report.findings.filter { it.fileName == source }
        // The stored component AND the stored group both resolve; only the ghost is MISSING.
        assertEquals(
            listOf("component:$ns/$ghost" to CrossCheckStatus.MISSING),
            mine.map { it.reference to it.status },
        )
    }

    @Test
    fun `a namespaceless reference resolves within the referencing file's own namespace`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val parent = uniqueEntityName("parent")
        val inTeam = uniqueEntityName("child-team")
        val inDefault = uniqueEntityName("child-default")
        // The parent exists ONLY in team-a.
        client.createCatalogFile(componentFile(parent, namespace = "team-a"))
        client.createCatalogFile(
            componentFile(inTeam, namespace = "team-a").let {
                it.copy(spec = it.spec.copy(subcomponentOf = parent))
            },
        )
        client.createCatalogFile(
            componentFile(inDefault).let { it.copy(spec = it.spec.copy(subcomponentOf = parent)) },
        )

        val findings = client.report().findings.filter { it.reference == parent }
        // Same namespace → resolves; default namespace → the parent is not there → MISSING.
        assertEquals(listOf(inDefault), findings.map { it.fileName })
        assertEquals(listOf(CrossCheckStatus.MISSING), findings.map { it.status })
    }

    @Test
    fun `resolution is case-insensitive across kind, namespace and name`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val target = uniqueEntityName("Cased")
        val source = uniqueEntityName("caser")
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
    fun `a kind-less dependsOn entry is KIND_REQUIRED even when a component of that name exists`() =
        testApplication {
            usePostgresTestcontainer()
            val client = userClient()
            val target = uniqueEntityName("present")
            val source = uniqueEntityName("kindless")
            client.createCatalogFile(componentFile(target))
            client.createCatalogFile(
                componentFile(source).let { it.copy(spec = it.spec.copy(dependsOn = listOf(target))) },
            )

            val mine = client.report().findings.filter { it.fileName == source && it.field == "spec.dependsOn" }
            assertEquals(listOf(CrossCheckStatus.KIND_REQUIRED), mine.map { it.status })
        }

    @Test
    fun `references to kinds Toadie does not store are UNVERIFIABLE`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val source = uniqueEntityName("external")
        client.createCatalogFile(
            componentFile(source).let {
                // Location, Template, and custom kinds stay out of the store — only explicit
                // references can carry them (every default kind is a stored kind now).
                it.copy(
                    spec = it.spec.copy(
                        dependsOn = listOf("template:default/scaffolder", "mycustomkind:thing"),
                    ),
                )
            },
        )

        val mine = client.report().findings.filter { it.fileName == source && it.field == "spec.dependsOn" }
        assertEquals(2, mine.size)
        assertTrue(mine.all { it.status == CrossCheckStatus.UNVERIFIABLE })
        // The owner ref (group kind, stored) is a real MISSING error, not informational.
        assertEquals(
            listOf(CrossCheckStatus.MISSING),
            client.report().findings.filter { it.fileName == source && it.field == "spec.owner" }.map { it.status },
        )
    }

    @Test
    fun `stored groups and users resolve organizational references`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val ns = uniqueEntityName("orgns")
        val person = uniqueEntityName("person")
        val team = uniqueEntityName("team")
        val ghostParent = uniqueEntityName("ghostparent")
        client.createCatalogFile(userFile(person, namespace = ns, memberOf = listOf(team)))
        client.createCatalogFile(
            groupFile(team, namespace = ns, members = listOf("user:$ns/$person"), parent = ghostParent),
        )

        val findings = client.report().findings.filter { it.fileNamespace == ns }
        // memberOf → the stored group, members → the stored user: both resolve. The group's
        // parent points nowhere → MISSING with the group default kind.
        assertEquals(
            listOf(Triple(team, "spec.parent", CrossCheckStatus.MISSING)),
            findings.map { Triple(it.fileName, it.field, it.status) },
        )
    }

    @Test
    fun `a soft-deleted target stops resolving`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
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
        val client = userClient()
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
    fun `the literal cross-check segment does not fall into the id route`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        // Would be a 400 ("id must be a UInt") if {id} captured the literal segment.
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/catalog-files/cross-check").status)
    }

    @Test
    fun `cross-check endpoints require authentication`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/catalog-files/cross-check").status)
        val check = client.post("/api/v1/catalog-files/check") {
            contentType(ContentType.Application.Json)
            setBody(componentFile("x"))
        }
        assertEquals(HttpStatusCode.Unauthorized, check.status)
    }
}
