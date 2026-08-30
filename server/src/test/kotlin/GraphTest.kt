package ch.nokillswit

import ch.nokillswit.catalog.CatalogFilePageResponse
import ch.nokillswit.catalog.CatalogGraph
import ch.nokillswit.catalog.GraphNodeStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
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


    private suspend fun HttpClient.graph(namespace: String? = null): CatalogGraph =
        get("$CATALOG_FILES_PATH/graph" + (namespace?.let { "?namespace=$it" } ?: "")).body()

    @Test
    fun `stored files and their resolved references become STORED nodes and edges`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graph")
        val ns = uniqueNamespace("gns")
        val a = uniqueEntityName("a")
        val b = uniqueEntityName("b")
        // Both files share the namespaceless owner, so it collapses into ONE node.
        client.createCatalogFile(groupFile("team-x", namespace = ns))
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
        assertEquals(GraphNodeStatus.STORED, owner.status)
        assertNotNull(owner.fileId)
        assertEquals(3, graph.nodes.size)

        assertTrue(graph.edges.any { it.sourceId == nodeA.id && it.targetId == nodeB.id && it.field == "spec.dependsOn" })
        assertTrue(graph.edges.any { it.sourceId == nodeA.id && it.targetId == owner.id && it.field == "spec.owner" })
    }

    @Test
    fun `a deletion-orphaned reference draws a MISSING node`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graph")
        val ns = uniqueNamespace("gns")
        val a = uniqueEntityName("a")
        val doomed = uniqueEntityName("doomed")
        // Saves enforce resolution — the dangling ref is MADE by deleting its target.
        val doomedId = client.createCatalogFile(componentFile(doomed, namespace = ns)).id
        client.createCatalogFile(
            componentFile(a, namespace = ns).let { it.copy(spec = it.spec.copy(subcomponentOf = doomed)) },
        )
        client.delete("$CATALOG_FILES_PATH/$doomedId")

        val graph = client.graph(namespace = ns)
        val ghostNode = graph.nodes.single { it.name == doomed }
        assertEquals(GraphNodeStatus.MISSING, ghostNode.status)
        // Namespaceless subcomponentOf resolves in the file's OWN namespace.
        assertEquals(ns, ghostNode.namespace)
        assertNull(ghostNode.fileId)
        assertTrue(graph.edges.any { it.targetId == ghostNode.id && it.field == "spec.subcomponentOf" })
    }

    @Test
    fun `kind-less dependsOn entries draw neither node nor edge`() {
        // Such a document can no longer be STORED (saves enforce resolution) — the drawing
        // rule is pinned on the pure builder with an in-memory source instead.
        val file = componentFile("bare-holder").let {
            it.copy(spec = it.spec.copy(dependsOn = listOf("bare-target")))
        }
        val graph = ch.nokillswit.catalog.buildGraph(
            sources = listOf(ch.nokillswit.catalog.CatalogSource(id = 1u, file = file, sourceUrl = null)),
        )
        assertTrue(graph.nodes.none { it.name == "bare-target" })
        assertTrue(graph.edges.none { it.field == "spec.dependsOn" })
    }

    @Test
    fun `case-variant references collapse into one node`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graph")
        val ns = uniqueNamespace("gns")
        val a = uniqueEntityName("a")
        val b = uniqueEntityName("b")
        client.createCatalogFile(componentFile(b, namespace = ns))
        client.createCatalogFile(
            componentFile(a, namespace = ns).let {
                it.copy(
                    spec = it.spec.copy(
                        dependsOn = listOf("component:$ns/${b.uppercase()}"),
                        dependencyOf = listOf("Component:${ns.uppercase()}/$b"),
                    ),
                )
            },
        )

        val graph = client.graph(namespace = ns)
        val targets = graph.nodes.filter { it.name == b }
        assertEquals(1, targets.size)
        assertEquals(GraphNodeStatus.STORED, targets.single().status)
        assertEquals(2, graph.edges.count { it.targetId == targets.single().id })
    }

    @Test
    fun `the namespace filter keeps a stored target from another namespace as STORED`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graph")
        val nsA = uniqueNamespace("gns")
        val nsB = uniqueNamespace("gns")
        val a = uniqueEntityName("a")
        val b = uniqueEntityName("b")
        val bId = client.createCatalogFile(componentFile(b, namespace = nsB)).id
        client.createCatalogFile(
            componentFile(a, namespace = nsA).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$nsB/$b")))
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
        val client = seededClient("graph")
        val ns = uniqueNamespace("gns")
        val a = uniqueEntityName("a")
        val b = uniqueEntityName("b")
        val bId = client.createCatalogFile(componentFile(b, namespace = ns)).id
        client.createCatalogFile(
            componentFile(a, namespace = ns).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$ns/$b")))
            },
        )
        client.delete("$CATALOG_FILES_PATH/$bId")

        val graph = client.graph(namespace = ns)
        val nodeB = graph.nodes.single { it.name == b }
        assertEquals(GraphNodeStatus.MISSING, nodeB.status)
        assertNull(nodeB.fileId)
    }

    @Test
    fun `stored groups and users draw as STORED nodes with membership edges`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graph")
        val ns = uniqueNamespace("gns")
        val team = uniqueEntityName("team")
        val person = uniqueEntityName("person")
        // members ↔ memberOf is circular — built as create-memberless → create → update.
        val personId = client.createCatalogFile(userFile(person, namespace = ns)).id
        client.createCatalogFile(groupFile(team, namespace = ns, members = listOf("user:$ns/$person")))
        client.putJson("$CATALOG_FILES_PATH/$personId", userFile(person, namespace = ns, memberOf = listOf(team)))

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
        val client = seededClient("graph")
        val ns = uniqueNamespace("gns")
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
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("$CATALOG_FILES_PATH/graph").status)
        // Would be a 400 ("id must be a UInt") if {id} captured the literal segment.
        assertEquals(HttpStatusCode.OK, seededClient("graph").get("$CATALOG_FILES_PATH/graph").status)
    }

    @Test
    fun `a repeated namespace parameter is 400 on graph and export`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graphrep")
        // The singleValue rule (API-LIST-004): repetition is reserved for documented IN semantics.
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("$CATALOG_FILES_PATH/graph?namespace=a&namespace=b").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("$CATALOG_FILES_PATH/export?namespace=a&namespace=b").status,
        )
    }

    @Test
    fun `the graph declares the list's filter set and matches its verdicts`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graphfilters")
        val ns = uniqueNamespace("gfil")
        val key = uniqueLabel("gfil", values = listOf("v1", "v2"))
        val a = uniqueEntityName("gfa")
        val b = uniqueEntityName("gfb")
        client.createCatalogFile(groupFile("team-y", namespace = ns))
        client.createCatalogFile(
            componentFile(a, namespace = ns, type = "service", owner = "team-y").let {
                it.copy(metadata = it.metadata.copy(labels = mapOf(key to "v1")))
            },
        )
        client.createCatalogFile(
            componentFile(b, namespace = ns, type = "library", owner = "group:$ns/team-y").let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$ns/$a")))
            },
        )

        suspend fun graphFor(query: String): CatalogGraph =
            client.get("$CATALOG_FILES_PATH/graph?namespace=$ns&$query").body()
        // Every source here carries at least an owner edge, so the edge sourceIds ARE the
        // files the filter EXPANDED — the graph half of the list-vs-graph parity assert.
        suspend fun expandedNames(query: String): Set<String> =
            graphFor(query).edges.map { it.sourceId.substringAfterLast('/') }.toSet()
        suspend fun listedNames(query: String): Set<String> =
            client.get("$CATALOG_FILES_PATH?namespace=$ns&kind=Component&$query")
                .body<CatalogFilePageResponse>().items.map { it.name }.toSet()

        // Parity: the graph expands exactly the files the list returns, filter by filter.
        for (query in listOf("type=library", "owner=group:$ns/team-y", "label=$key&labelValue=v1", "name=$a")) {
            assertEquals(listedNames(query), expandedNames(query), "list/graph parity for $query")
        }

        // Narrowing to B still keeps its STORED targets: A stays a STORED node with a fileId
        // even though the filter excluded it from expansion (the allSources contract).
        val narrowed = graphFor("type=library")
        val nodeA = narrowed.nodes.single { it.name == a }
        assertEquals(GraphNodeStatus.STORED, nodeA.status)
        assertNotNull(nodeA.fileId)
        assertTrue(narrowed.edges.none { it.sourceId == nodeA.id })

        // The graph shares the list's parameter validation: unparsable owner and orphaned
        // labelValue are 400s here too.
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH/graph?owner=a:b:c").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH/graph?labelValue=v1").status)
    }
}
