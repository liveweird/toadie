package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.GraphLayoutDocument
import ch.nokillswit.users.GraphPosition
import ch.nokillswit.users.MAX_GRAPH_POSITIONS
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Entity graph's own per-user layout (V30, Port migration phase 3): the exact
 * [GraphLayoutTest] shape over `/entity-graph-layout`, plus one test proving the two documents
 * (Backstage Graph vs. Entity graph) are independent for the SAME user.
 */
class EntityGraphLayoutTest {

    private fun layoutPath(id: UInt) = "/api/v1/users/$id/entity-graph-layout"
    private fun backstageLayoutPath(id: UInt) = "/api/v1/users/$id/graph-layout"

    private fun doc(
        mode: String,
        vararg positions: Pair<String, GraphPosition>,
        collapsed: List<String> = emptyList(),
    ) = GraphLayoutDocument(mode = mode, positions = positions.toMap(), collapsed = collapsed)

    @Test
    fun `a user round-trips their own entity-graph layout - default doc first, wholesale replace, audit-silent`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("eglayout-self")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)
        val client = authedClient(email, "pw-123456789")

        assertEquals(GraphLayoutDocument(), client.get(layoutPath(id)).body<GraphLayoutDocument>())

        withAuditCapture { capture ->
            val first = doc(
                "manual",
                "bp-a|e1" to GraphPosition(12.5, -3.0),
                "bp-b|team" to GraphPosition(0.0, 640.0),
                collapsed = listOf("bp-a|folded", "bp-b|other"),
            )
            assertEquals(HttpStatusCode.NoContent, client.putJson(layoutPath(id), first).status)
            assertEquals(first, client.get(layoutPath(id)).body<GraphLayoutDocument>())

            val second = doc("manual", "bp-a|e1" to GraphPosition(1.0, 2.0))
            assertEquals(HttpStatusCode.NoContent, client.putJson(layoutPath(id), second).status)
            assertEquals(second, client.get(layoutPath(id)).body<GraphLayoutDocument>())
            assertTrue(client.get(layoutPath(id)).body<GraphLayoutDocument>().collapsed.isEmpty())

            assertTrue(
                capture.events.none { it.message.startsWith("user.") || it.message.startsWith("graph") },
                "the entity-graph layout PUT must not emit audit events",
            )
        }
    }

    @Test
    fun `the two layout documents are independent for the same user`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("eglayout-indep")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)
        val client = authedClient(email, "pw-123456789")

        val entityDoc = doc("manual", "bp-a|e1" to GraphPosition(1.0, 1.0))
        val backstageDoc = doc("manual", "component:default/x" to GraphPosition(9.0, 9.0))

        assertEquals(HttpStatusCode.NoContent, client.putJson(layoutPath(id), entityDoc).status)
        assertEquals(HttpStatusCode.NoContent, client.putJson(backstageLayoutPath(id), backstageDoc).status)

        assertEquals(entityDoc, client.get(layoutPath(id)).body<GraphLayoutDocument>())
        assertEquals(backstageDoc, client.get(backstageLayoutPath(id)).body<GraphLayoutDocument>())
    }

    @Test
    fun `an admin may read and write another user's entity-graph layout - a stranger gets 403 even with a bad body`() = testApplication {
        usePostgresTestcontainer()
        val targetEmail = uniqueEmail("eglayout-target")
        val targetId = TestUsers.seed(email = targetEmail, password = "pw-123456789", role = UserRole.USER)
        val admin = seededClient("eglayout-admin", role = UserRole.ADMIN)
        val stranger = seededClient("eglayout-stranger", role = UserRole.USER)

        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson(layoutPath(targetId), doc("manual", "bp-a|x" to GraphPosition(1.0, 1.0))).status,
        )
        assertEquals("manual", admin.get(layoutPath(targetId)).body<GraphLayoutDocument>().mode)

        assertEquals(HttpStatusCode.Forbidden, stranger.putJson(layoutPath(targetId), doc("bogus")).status)
        assertEquals(HttpStatusCode.Forbidden, stranger.get(layoutPath(targetId)).status)
    }

    @Test
    fun `payload rules - unknown mode, oversized map, and bad keys are 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("eglayout-rules", role = UserRole.ADMIN)
        val email = uniqueEmail("eglayout-rules-target")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)

        val badMode = admin.putJson(layoutPath(id), doc("diagonal"))
        assertEquals(HttpStatusCode.BadRequest, badMode.status)
        assertTrue(badMode.body<ProblemDetail>().detail!!.contains("Unsupported layout mode"))

        val oversized = GraphLayoutDocument(
            mode = "manual",
            positions = (0..MAX_GRAPH_POSITIONS).associate { "bp-a|n$it" to GraphPosition(0.0, 0.0) },
        )
        assertEquals(HttpStatusCode.BadRequest, admin.putJson(layoutPath(id), oversized).status)

        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(layoutPath(id), doc("manual", "  " to GraphPosition(0.0, 0.0))).status,
        )
    }

    @Test
    fun `unknown and soft-deleted targets are 404 on both verbs`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("eglayout-gone", role = UserRole.ADMIN)
        val email = uniqueEmail("eglayout-gone-target")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)

        assertEquals(HttpStatusCode.NotFound, admin.get(layoutPath(999999u)).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson(layoutPath(999999u), doc("auto")).status)
        TestUsers.softDelete(id)
        assertEquals(HttpStatusCode.NotFound, admin.get(layoutPath(id)).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson(layoutPath(id), doc("auto")).status)
    }

    @Test
    fun `the entity-graph layout endpoints require authentication`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get(layoutPath(1u)).status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().putJson(layoutPath(1u), doc("auto")).status)
    }
}
