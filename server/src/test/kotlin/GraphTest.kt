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
    fun `a stored node carries the fields its rendering needs - virtual nodes carry none`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graph")
        val ns = uniqueNamespace("gns")
        val tag = uniqueTag("gtag")
        uniqueTagCategory("gcat", listOf(tag), listOf("Component"))
        val a = uniqueEntityName("a")
        val doomed = uniqueEntityName("doomed")
        // The dangling ref is MADE by deleting its target — saves enforce resolution.
        val doomedId = client.createCatalogFile(componentFile(doomed, namespace = ns)).id
        client.createCatalogFile(
            componentFile(a, namespace = ns, title = "Alpha", type = "library").let {
                it.copy(
                    metadata = it.metadata.copy(tags = listOf(tag)),
                    spec = it.spec.copy(dependsOn = listOf("component:$ns/$doomed")),
                )
            },
        )
        client.delete("$CATALOG_FILES_PATH/$doomedId")

        val graph = client.graph(namespace = ns)
        // The graph node's whole display surface: the second line (type) and the tooltip
        // (title + tags) come off the stored document, not a second request.
        val nodeA = graph.nodes.single { it.name == a }
        assertEquals("library", nodeA.type)
        assertEquals(listOf(tag), nodeA.tags)
        assertEquals("Alpha", nodeA.title)
        // A virtual node has no document behind it, so it carries none of the three.
        val ghost = graph.nodes.single { it.name == doomed }
        assertEquals(GraphNodeStatus.MISSING, ghost.status)
        assertNull(ghost.type)
        assertNull(ghost.title)
        assertTrue(ghost.tags.isEmpty())
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
    fun `a stored target the filter hides leaves the graph, and never reads as MISSING`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graph")
        val nsA = uniqueNamespace("gns")
        val nsB = uniqueNamespace("gns")
        val a = uniqueEntityName("a")
        val b = uniqueEntityName("b")
        client.createCatalogFile(componentFile(b, namespace = nsB))
        client.createCatalogFile(
            componentFile(a, namespace = nsA).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("component:$nsB/$b")))
            },
        )

        val graph = client.graph(namespace = nsA)
        // b is stored, but the namespace filter does not show it: no node, and therefore no
        // edge either (both ends must be shown). Hidden is not absent — it must NOT go MISSING,
        // which is why the builder still resolves against the unfiltered workspace.
        assertTrue(graph.nodes.none { it.name == b })
        assertTrue(graph.edges.isEmpty())
        assertEquals(listOf(a), graph.nodes.map { it.name })
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
    fun `the kind filter selects what is shown - a hidden owner takes its edge with it`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graphkind")
        val ns = uniqueNamespace("gkns")
        val team = uniqueEntityName("gkteam")
        val svc = uniqueEntityName("gksvc")
        client.createCatalogFile(groupFile(team, namespace = ns))
        client.createCatalogFile(componentFile(svc, namespace = ns, owner = team))

        // The reported bug: filtering to one kind used to draw the referenced Group too.
        val components: CatalogGraph = client.get("$CATALOG_FILES_PATH/graph?namespace=$ns&kind=Component").body()
        assertEquals(listOf(svc), components.nodes.map { it.name })
        assertTrue(components.edges.isEmpty(), "the owner edge needs both ends shown")

        // Enabling both pills brings the Group — and with it the owner edge — back.
        val both: CatalogGraph =
            client.get("$CATALOG_FILES_PATH/graph?namespace=$ns&kind=Component&kind=Group").body()
        assertEquals(setOf(svc, team), both.nodes.map { it.name }.toSet())
        assertEquals(listOf("spec.owner"), both.edges.map { it.field })
    }

    @Test
    fun `a MISSING entity is governed by its own kind pill, not the referrer's`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graphmiss")
        val ns = uniqueNamespace("gmns")
        val svc = uniqueEntityName("gmsvc")
        val ghost = uniqueEntityName("gmghost")
        // Waived: the reference is deliberately unresolvable.
        client.postJson(
            "$CATALOG_FILES_PATH?allowInvalid=true",
            componentFile(svc, namespace = ns).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("api:$ns/$ghost")))
            },
        )

        // The ghost is an API: the Component pill alone does not show it, so its edge goes too.
        val componentsOnly: CatalogGraph =
            client.get("$CATALOG_FILES_PATH/graph?namespace=$ns&kind=Component").body()
        assertEquals(listOf(svc), componentsOnly.nodes.map { it.name })
        assertTrue(componentsOnly.edges.isEmpty())

        // With the API pill on it is drawn — a missing API is still an API.
        val withApis: CatalogGraph =
            client.get("$CATALOG_FILES_PATH/graph?namespace=$ns&kind=Component&kind=API").body()
        val ghostNode = withApis.nodes.single { it.name == ghost }
        assertEquals(GraphNodeStatus.MISSING, ghostNode.status)
        assertEquals(1, withApis.edges.size)
    }

    @Test
    fun `a reference to a kind Toadie doesn't store draws nothing`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("graphext")
        val ns = uniqueNamespace("gxns")
        val svc = uniqueEntityName("gxsvc")
        client.postJson(
            "$CATALOG_FILES_PATH?allowInvalid=true",
            componentFile(svc, namespace = ns).let {
                it.copy(spec = it.spec.copy(dependsOn = listOf("template:$ns/scaffold")))
            },
        )

        // No kind pill exists for Template, so nothing could ever select it — and every pill
        // being on (no kind param at all) is not a way in either.
        val graph = client.graph(namespace = ns)
        assertEquals(listOf(svc), graph.nodes.map { it.name })
        assertTrue(graph.edges.isEmpty())
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
        // None of these queries can strand a MISSING target, so the graph's whole node set is
        // its STORED one — the strongest form of the parity: same query, same entities.
        suspend fun shownNames(query: String): Set<String> =
            graphFor(query).nodes.map { it.name }.toSet()
        suspend fun listedNames(query: String): Set<String> =
            client.get("$CATALOG_FILES_PATH?namespace=$ns&kind=Component&$query")
                .body<CatalogFilePageResponse>().items.map { it.name }.toSet()

        // Parity: the graph SHOWS exactly the files the list returns, filter by filter.
        for (query in listOf("type=library", "owner=group:$ns/team-y", "label=$key&labelValue=v1", "name=$a")) {
            assertEquals(listedNames(query), shownNames(query), "list/graph parity for $query")
        }

        // Narrowing to B drops A entirely — and B's dependsOn edge with it, since an edge
        // needs both ends shown. A is stored, so it must not turn up as MISSING either.
        val narrowed = graphFor("type=library")
        assertEquals(listOf(b), narrowed.nodes.map { it.name })
        assertTrue(narrowed.edges.isEmpty())

        // The graph shares the list's parameter validation: unparsable owner and orphaned
        // labelValue are 400s here too.
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH/graph?owner=a:b:c").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$CATALOG_FILES_PATH/graph?labelValue=v1").status)
    }
}
