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
 * The per-user Graph-page layout (V19): the self-or-admin GET/PUT pair, the wholesale
 * replace, the default document for a never-saved user, and the payload rules. The PUT is
 * DELIBERATELY unaudited (pure high-frequency view state) — one test pins the silence.
 */
class GraphLayoutTest {

    private fun layoutPath(id: UInt) = "/api/v1/users/$id/graph-layout"

    private fun doc(
        mode: String,
        vararg positions: Pair<String, GraphPosition>,
        collapsed: List<String> = emptyList(),
    ) = GraphLayoutDocument(mode = mode, positions = positions.toMap(), collapsed = collapsed)

    @Test
    fun `a user round-trips their own layout - default doc first, wholesale replace, audit-silent`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("layout-self")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)
        val client = authedClient(email, "pw-123456789")

        // Never saved: the default document, never a 404 for a live target.
        assertEquals(GraphLayoutDocument(), client.get(layoutPath(id)).body<GraphLayoutDocument>())

        withAuditCapture { capture ->
            // The collapsed list rides the same document (V24) — order preserved, ids of nodes
            // the server never heard of kept verbatim (never pruned, the positions posture).
            val first = doc(
                "manual",
                "component:default/a" to GraphPosition(12.5, -3.0),
                "group:default/team" to GraphPosition(0.0, 640.0),
                collapsed = listOf("system:default/shop", "domain:default/commerce"),
            )
            assertEquals(HttpStatusCode.NoContent, client.putJson(layoutPath(id), first).status)
            assertEquals(first, client.get(layoutPath(id)).body<GraphLayoutDocument>())

            // Wholesale replace: the re-PUT's map wins outright — the dropped key is gone, and
            // a document that omits `collapsed` reads back as nothing collapsed.
            val second = doc("manual", "component:default/a" to GraphPosition(1.0, 2.0))
            assertEquals(HttpStatusCode.NoContent, client.putJson(layoutPath(id), second).status)
            assertEquals(second, client.get(layoutPath(id)).body<GraphLayoutDocument>())
            assertTrue(client.get(layoutPath(id)).body<GraphLayoutDocument>().collapsed.isEmpty())

            // Deliberately unaudited: high-frequency pure view state.
            assertTrue(
                capture.events.none { it.message.startsWith("user.") || it.message.startsWith("graph") },
                "the layout PUT must not emit audit events",
            )
        }
    }

    @Test
    fun `an admin may read and write another user's layout - a stranger gets 403 even with a bad body`() = testApplication {
        usePostgresTestcontainer()
        val targetEmail = uniqueEmail("layout-target")
        val targetId = TestUsers.seed(email = targetEmail, password = "pw-123456789", role = UserRole.USER)
        val admin = seededClient("layout-admin", role = UserRole.ADMIN)
        val stranger = seededClient("layout-stranger", role = UserRole.USER)

        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson(layoutPath(targetId), doc("manual", "component:default/x" to GraphPosition(1.0, 1.0))).status,
        )
        assertEquals("manual", admin.get(layoutPath(targetId)).body<GraphLayoutDocument>().mode)

        // 403 wins over 400: the guard runs before the payload is even validated.
        assertEquals(HttpStatusCode.Forbidden, stranger.putJson(layoutPath(targetId), doc("bogus")).status)
        assertEquals(HttpStatusCode.Forbidden, stranger.get(layoutPath(targetId)).status)
    }

    @Test
    fun `payload rules - unknown mode, oversized map, and bad keys are 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("layout-rules", role = UserRole.ADMIN)
        val email = uniqueEmail("layout-rules-target")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)

        val badMode = admin.putJson(layoutPath(id), doc("diagonal"))
        assertEquals(HttpStatusCode.BadRequest, badMode.status)
        assertTrue(badMode.body<ProblemDetail>().detail!!.contains("Unsupported layout mode"))

        val oversized = GraphLayoutDocument(
            mode = "manual",
            positions = (0..MAX_GRAPH_POSITIONS).associate { "component:default/n$it" to GraphPosition(0.0, 0.0) },
        )
        assertEquals(HttpStatusCode.BadRequest, admin.putJson(layoutPath(id), oversized).status)

        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(layoutPath(id), doc("manual", "  " to GraphPosition(0.0, 0.0))).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(layoutPath(id), doc("manual", "a".repeat(401) to GraphPosition(0.0, 0.0))).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(layoutPath(id), doc("manual", "bad\u0007key" to GraphPosition(0.0, 0.0))).status,
        )

        // The collapsed list (V24) is held to the same ceiling and the same id grammar.
        val tooManyCollapsed = doc("auto", collapsed = (0..MAX_GRAPH_POSITIONS).map { "component:default/n$it" })
        val overCap = admin.putJson(layoutPath(id), tooManyCollapsed)
        assertEquals(HttpStatusCode.BadRequest, overCap.status)
        assertTrue(overCap.body<ProblemDetail>().detail!!.contains("collapsed"))
        for (bad in listOf("  ", "a".repeat(401), "bad\u0007key")) {
            val response = admin.putJson(layoutPath(id), doc("auto", collapsed = listOf("system:default/ok", bad)))
            assertEquals(HttpStatusCode.BadRequest, response.status, "collapsed id ${bad.take(12)} must be refused")
            assertTrue(response.body<ProblemDetail>().detail!!.startsWith("Collapsed node ids"))
        }
    }

    @Test
    fun `unknown and soft-deleted targets are 404 on both verbs`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("layout-gone", role = UserRole.ADMIN)
        val email = uniqueEmail("layout-gone-target")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)

        assertEquals(HttpStatusCode.NotFound, admin.get(layoutPath(999999u)).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson(layoutPath(999999u), doc("auto")).status)
        TestUsers.softDelete(id)
        assertEquals(HttpStatusCode.NotFound, admin.get(layoutPath(id)).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson(layoutPath(id), doc("auto")).status)
    }

    @Test
    fun `the layout document decodes with its defaults absent`() {
        // The GET default and a mode-only PUT both lean on these defaults.
        val decoded = kotlinx.serialization.json.Json.decodeFromString<GraphLayoutDocument>("""{}""")
        assertEquals("auto", decoded.mode)
        assertTrue(decoded.positions.isEmpty())
        // Pre-V24 clients send no `collapsed` at all — that must read as nothing collapsed.
        assertTrue(decoded.collapsed.isEmpty())
    }

    @Test
    fun `the layout endpoints require authentication`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get(layoutPath(1u)).status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().putJson(layoutPath(1u), doc("auto")).status)
    }
}
