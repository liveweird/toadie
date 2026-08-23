package ch.nokillswit

import ch.nokillswit.catalog.CatalogGraph
import ch.nokillswit.catalog.GraphNodeStatus
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The graph endpoint. Isolation trick for the shared container: each test creates its files in
 * a UNIQUE namespace and queries `?namespace=<it>`, so node/edge assertions can be exact.
 */
class GraphTest {

    private suspend fun ApplicationTestBuilder.userClient(): HttpClient {
        val email = uniqueEmail("graph")
        TestUsers.seed(email = email, password = "pw", role = UserRole.USER)
        return authedClient(email, "pw")
    }

    private suspend fun HttpClient.graph(namespace: String? = null): CatalogGraph =
        get("/api/v1/catalog-files/graph" + (namespace?.let { "?namespace=$it" } ?: "")).body()

    @Test
    fun `stored files and their resolved references become STORED nodes and edges`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val ns = uniqueEntityName("gns")
        val a = uniqueEntityName("a")
        val b = uniqueEntityName("b")
        // Both files share the namespaceless owner, so it collapses into ONE virtual node.
        client.createCatalogFile(componentFile(b, namespace = ns, title = "Target B", owner = "team-x"))
        client.createCatalogFile(
            componentFile(a, namespace = ns).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$ns/$b"), owner = "team-x"))
            },
        )

        val graph = client.graph(namespace = ns)
        val nodeA = graph.nodes.single { it.name == a }
        val nodeB = graph.nodes.single { it.name == b }
        assertEquals(GraphNodeStatus.STORED, nodeA.status)
        assertEquals(GraphNodeStatus.STORED, nodeB.status)
        assertEquals("Target B", nodeB.title)
        assertNotNull(nodeB.fileId)
        val owner = graph.nodes.single { it.id == "group:$ns/team-x" }
        // Groups are a stored kind now — an absent one is MISSING, not external.
        assertEquals(GraphNodeStatus.MISSING, owner.status)
        assertNull(owner.fileId)
        assertEquals(3, graph.nodes.size)

        assertTrue(graph.edges.any { it.sourceId == nodeA.id && it.targetId == nodeB.id && it.field == "spec.dependsOn" })
        assertTrue(graph.edges.any { it.sourceId == nodeA.id && it.targetId == owner.id && it.field == "spec.owner" })
    }

    @Test
    fun `a dangling component reference draws a MISSING node`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val ns = uniqueEntityName("gns")
        val a = uniqueEntityName("a")
        val ghost = uniqueEntityName("ghost")
        client.createCatalogFile(
            componentFile(a, namespace = ns).let {
                it.copy(spec = it.spec.copy(subcomponentOf = ghost, owner = "group:default/x"))
            },
        )

        val graph = client.graph(namespace = ns)
        val ghostNode = graph.nodes.single { it.name == ghost }
        assertEquals(GraphNodeStatus.MISSING, ghostNode.status)
        // Namespaceless subcomponentOf resolves in the file's OWN namespace.
        assertEquals(ns, ghostNode.namespace)
        assertNull(ghostNode.fileId)
        assertTrue(graph.edges.any { it.targetId == ghostNode.id && it.field == "spec.subcomponentOf" })
    }

    @Test
    fun `kind-less dependsOn entries draw neither node nor edge`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val ns = uniqueEntityName("gns")
        val a = uniqueEntityName("a")
        val bare = uniqueEntityName("bare")
        client.createCatalogFile(
            componentFile(a, namespace = ns).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf(bare), owner = "team-x"))
            },
        )

        val graph = client.graph(namespace = ns)
        assertTrue(graph.nodes.none { it.name == bare })
        assertTrue(graph.edges.none { it.field == "spec.dependsOn" })
    }

    @Test
    fun `case-variant references collapse into one node`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val ns = uniqueEntityName("gns")
        val a = uniqueEntityName("a")
        val b = uniqueEntityName("b")
        client.createCatalogFile(
            componentFile(a, namespace = ns).let {
                it.copy(
                    spec = it.spec.copy(
                        dependsOn = listOf("component:$ns/${b.uppercase()}"),
                        dependencyOf = listOf("Component:${ns.uppercase()}/$b"),
                        owner = "team-x",
                    ),
                )
            },
        )

        val graph = client.graph(namespace = ns)
        val targets = graph.nodes.filter { it.name == b.lowercase() }
        assertEquals(1, targets.size)
        assertEquals(GraphNodeStatus.MISSING, targets.single().status)
        assertEquals(2, graph.edges.count { it.targetId == targets.single().id })
    }

    @Test
    fun `the namespace filter keeps a stored target from another namespace as STORED`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val nsA = uniqueEntityName("gns")
        val nsB = uniqueEntityName("gns")
        val a = uniqueEntityName("a")
        val b = uniqueEntityName("b")
        val bId = client.createCatalogFile(componentFile(b, namespace = nsB)).id
        client.createCatalogFile(
            componentFile(a, namespace = nsA).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$nsB/$b"), owner = "team-x"))
            },
        )

        val graph = client.graph(namespace = nsA)
        // b's own references are NOT expanded (it is outside the filter), but the node is
        // there — STORED, with its real fileId, never MISSING.
        val nodeB = graph.nodes.single { it.name == b }
        assertEquals(GraphNodeStatus.STORED, nodeB.status)
        assertEquals(bId, nodeB.fileId)
        assertTrue(graph.edges.none { it.sourceId == nodeB.id })
    }

    @Test
    fun `a soft-deleted file leaves the graph and its targets go MISSING`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val ns = uniqueEntityName("gns")
        val a = uniqueEntityName("a")
        val b = uniqueEntityName("b")
        val bId = client.createCatalogFile(componentFile(b, namespace = ns)).id
        client.createCatalogFile(
            componentFile(a, namespace = ns).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$ns/$b"), owner = "team-x"))
            },
        )
        client.delete("/api/v1/catalog-files/$bId")

        val graph = client.graph(namespace = ns)
        val nodeB = graph.nodes.single { it.name == b }
        assertEquals(GraphNodeStatus.MISSING, nodeB.status)
        assertNull(nodeB.fileId)
    }

    @Test
    fun `stored groups and users draw as STORED nodes with membership edges`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val ns = uniqueEntityName("gns")
        val team = uniqueEntityName("team")
        val person = uniqueEntityName("person")
        client.createCatalogFile(groupFile(team, namespace = ns, members = listOf("user:$ns/$person")))
        client.createCatalogFile(userFile(person, namespace = ns, memberOf = listOf(team)))

        val graph = client.graph(namespace = ns)
        val teamNode = graph.nodes.single { it.name == team }
        val personNode = graph.nodes.single { it.name == person }
        assertEquals(GraphNodeStatus.STORED, teamNode.status)
        assertEquals(GraphNodeStatus.STORED, personNode.status)
        assertEquals("group", teamNode.kind)
        assertEquals("user", personNode.kind)
        assertTrue(graph.edges.any { it.sourceId == teamNode.id && it.targetId == personNode.id && it.field == "spec.members" })
        assertTrue(graph.edges.any { it.sourceId == personNode.id && it.targetId == teamNode.id && it.field == "spec.memberOf" })
    }

    @Test
    fun `the unfiltered graph spans the workspace`() = testApplication {
        usePostgresTestcontainer()
        val client = userClient()
        val ns = uniqueEntityName("gns")
        val a = uniqueEntityName("a")
        client.createCatalogFile(componentFile(a, namespace = ns))

        // Shared container: other tests' files are in here too — only >= and containment.
        val graph = client.graph()
        assertTrue(graph.nodes.any { it.name == a })
        assertTrue(graph.nodes.size >= 1)
    }

    @Test
    fun `the graph endpoint requires authentication and dodges the id route`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/catalog-files/graph").status)
        // Would be a 400 ("id must be a UInt") if {id} captured the literal segment.
        assertEquals(HttpStatusCode.OK, userClient().get("/api/v1/catalog-files/graph").status)
    }
}
