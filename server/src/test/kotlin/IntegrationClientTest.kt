package ch.nokillswit

import ch.nokillswit.blueprints.BlueprintRequest
import ch.nokillswit.entities.EntityRequest
import ch.nokillswit.entities.EntityResponse
import ch.nokillswit.integration.INTEGRATION_API_KEY_PREFIX
import ch.nokillswit.integration.IntegrationClientCreateResponse
import ch.nokillswit.integration.IntegrationClientListResponse
import ch.nokillswit.integration.IntegrationClientRequest
import ch.nokillswit.integration.IntegrationClientResponse
import ch.nokillswit.integration.IntegrationScope
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The integration-client registry (v2.7.0): ADMIN-only CRUD including reads (the management
 * policy), the show-the-key-once create, and the terminal revoke. The GraphQL endpoint the
 * keys authenticate is covered by IntegrationGraphQlTest.
 */
class IntegrationClientTest {

    private fun uniqueName(prefix: String) = "$prefix-${UUID.randomUUID()}"

    // Audit ids are logged as Longs; the shared hasKeyValue helper compares Strings only.
    private fun ch.qos.logback.classic.spi.ILoggingEvent.hasLongValue(key: String, value: Long) =
        keyValuePairs?.any { it.key == key && it.value == value } == true

    private suspend fun HttpClient.createClient(name: String, scope: IntegrationScope? = null): HttpResponse =
        post("/api/v1/integration-clients") {
            contentType(ContentType.Application.Json)
            setBody(IntegrationClientRequest(name = name, scope = scope ?: IntegrationScope.READ))
        }

    @Test
    fun `admin can create, read, list, and revoke an integration client`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("intc-admin")
        TestUsers.seed(adminEmail, "pw", name = "Int Admin")
        val admin = authedClient(adminEmail, "pw")
        val name = uniqueName("intc")

        val created = admin.createClient(name)
        assertEquals(HttpStatusCode.Created, created.status)
        val body = created.body<IntegrationClientCreateResponse>()
        assertTrue(body.apiKey.startsWith(INTEGRATION_API_KEY_PREFIX))
        assertEquals(name, body.client.name)
        assertEquals("Int Admin", body.client.createdByName)
        assertFalse(body.client.revoked)
        assertNull(body.client.lastUsedAt)
        assertEquals("/api/v1/integration-clients/${body.client.id}", created.headers[HttpHeaders.Location])

        // The single GET and the list never carry the key — it existed once, in the create body.
        val fetched = admin.get("/api/v1/integration-clients/${body.client.id}")
        assertEquals(HttpStatusCode.OK, fetched.status)
        assertEquals(name, fetched.body<IntegrationClientResponse>().name)
        assertFalse(fetched.bodyAsText().contains(body.apiKey))
        assertFalse(fetched.bodyAsText().contains("keyHash"))
        assertEquals("no-store", created.headers[HttpHeaders.CacheControl])
        val listed = admin.get("/api/v1/integration-clients?pageSize=100&sort=-id").body<IntegrationClientListResponse>()
        assertNotNull(listed.items.find { it.id == body.client.id })

        // Revoke: terminal, idempotence-guarded (repeat → 409), row stays listed as revoked.
        assertEquals(
            HttpStatusCode.NoContent,
            admin.post("/api/v1/integration-clients/${body.client.id}/revoke").status,
        )
        assertEquals(
            HttpStatusCode.Conflict,
            admin.post("/api/v1/integration-clients/${body.client.id}/revoke").status,
        )
        val revoked = admin.get("/api/v1/integration-clients/${body.client.id}")
            .body<IntegrationClientResponse>()
        assertTrue(revoked.revoked)
        assertNotNull(revoked.revokedAt)
    }

    @Test
    fun `unknown client ids answer 404`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("intc-404")
        TestUsers.seed(adminEmail, "pw")
        val admin = authedClient(adminEmail, "pw")
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/integration-clients/999999999").status)
        assertEquals(HttpStatusCode.NotFound, admin.post("/api/v1/integration-clients/999999999/revoke").status)
    }

    @Test
    fun `unauthenticated requests return 401`() = testApplication {
        usePostgresTestcontainer()
        val plain = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, plain.get("/api/v1/integration-clients").status)
        assertEquals(HttpStatusCode.Unauthorized, plain.createClient("x").status)
        assertEquals(HttpStatusCode.Unauthorized, plain.post("/api/v1/integration-clients/1/revoke").status)
    }

    @Test
    fun `non-admins get 403 before resource lookup or request validation`() = testApplication {
        usePostgresTestcontainer()
        val userEmail = uniqueEmail("intc-user")
        TestUsers.seed(userEmail, "pw", role = UserRole.USER)
        val client = authedClient(userEmail, "pw")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/integration-clients?page=0").status)
        assertEquals(HttpStatusCode.Forbidden, client.createClient(" ").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/integration-clients/999999999").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/integration-clients/999999999/revoke").status)
    }

    @Test
    fun `client names are validated up-front`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("intc-val")
        TestUsers.seed(adminEmail, "pw")
        val admin = authedClient(adminEmail, "pw")
        assertEquals(HttpStatusCode.BadRequest, admin.createClient("   ").status)
        assertEquals(HttpStatusCode.BadRequest, admin.createClient("x".repeat(101)).status)
        assertEquals(HttpStatusCode.BadRequest, admin.createClient("badname").status)
    }

    @Test
    fun `create and revoke are audited, including scope and the paired service account`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("intc-audit")
        val adminId = TestUsers.seed(adminEmail, "pw")
        val admin = authedClient(adminEmail, "pw")
        val appender = LogCapture("ch.nokillswit.audit")
        try {
            val name = uniqueName("intc-audit")
            val clientId = admin.createClient(name, IntegrationScope.WRITE)
                .body<IntegrationClientCreateResponse>().client.id
            admin.post("/api/v1/integration-clients/$clientId/revoke")

            val created = appender.events.find {
                it.message == "integration_client.created" && it.hasLongValue("clientId", clientId.toLong())
            }
            assertNotNull(created, "expected an integration_client.created audit event")
            assertTrue(created.hasLongValue("byUserId", adminId.toLong()))
            assertTrue(created.hasKeyValue("name", name))
            assertTrue(created.hasKeyValue("scope", "write"))
            val serviceUserId = created.keyValuePairs?.find { it.key == "serviceUserId" }?.value as? Long
            assertNotNull(serviceUserId, "expected a serviceUserId on the created audit event")
            val revoked = appender.events.find {
                it.message == "integration_client.revoked" && it.hasLongValue("clientId", clientId.toLong())
            }
            assertNotNull(revoked, "expected an integration_client.revoked audit event")
        } finally {
            appender.detach()
        }
    }

    @Test
    fun `key scope defaults to read, is immutable, and rejects an unknown value`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("intc-scope")
        TestUsers.seed(adminEmail, "pw")
        val admin = authedClient(adminEmail, "pw")

        // Omitted scope defaults to read, and it round-trips on GET/list.
        val readCreated = admin.createClient(uniqueName("intc-read")).body<IntegrationClientCreateResponse>()
        assertEquals(IntegrationScope.READ, readCreated.client.scope)
        val readFetched = admin.get("/api/v1/integration-clients/${readCreated.client.id}")
            .body<IntegrationClientResponse>()
        assertEquals(IntegrationScope.READ, readFetched.scope)
        val readListed = admin.get("/api/v1/integration-clients?pageSize=100&sort=-id")
            .body<IntegrationClientListResponse>().items.find { it.id == readCreated.client.id }
        assertEquals(IntegrationScope.READ, assertNotNull(readListed).scope)

        // Explicit write scope.
        val writeCreated = admin.createClient(uniqueName("intc-write"), IntegrationScope.WRITE)
            .body<IntegrationClientCreateResponse>()
        assertEquals(IntegrationScope.WRITE, writeCreated.client.scope)
        val writeFetched = admin.get("/api/v1/integration-clients/${writeCreated.client.id}")
            .body<IntegrationClientResponse>()
        assertEquals(IntegrationScope.WRITE, writeFetched.scope)

        // An unknown scope value is a plain 400 (kotlinx serialization's unknown-enum-value
        // decode failure, wrapped by the ContentConvertException handler).
        val invalidScope = admin.post("/api/v1/integration-clients") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"${uniqueName("intc-bad-scope")}","scope":"admin"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidScope.status)
    }

    @Test
    fun `every client is paired with a service account hidden from user management`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("intc-svc")
        TestUsers.seed(adminEmail, "pw")
        val admin = authedClient(adminEmail, "pw")
        val name = uniqueName("intc-svc")

        val appender = LogCapture("ch.nokillswit.audit")
        val clientId: UInt
        val serviceUserId: UInt
        try {
            val created = admin.createClient(name).body<IntegrationClientCreateResponse>()
            clientId = created.client.id
            val event = assertNotNull(
                appender.events.find {
                    it.message == "integration_client.created" && it.hasLongValue("clientId", clientId.toLong())
                },
            )
            serviceUserId = (event.keyValuePairs?.find { it.key == "serviceUserId" }?.value as Long).toUInt()
        } finally {
            appender.detach()
        }

        // The paired row: a real service account, never a person's row.
        val raw = assertNotNull(TestUsers.rawRow(serviceUserId), "expected the paired service account to exist")
        assertEquals("integration-client-$clientId@toadie.invalid", raw.email)
        assertTrue(raw.serviceAccount)
        assertEquals(UserRole.USER, raw.role)
        assertFalse(raw.markedAsDeleted)

        // Invisible to the whole /api/v1/users management surface.
        assertEquals(
            0L,
            admin.get("/api/v1/users?email=${raw.email}").body<ch.nokillswit.users.UserPageResponse>().total,
        )
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/users/$serviceUserId").status)

        // An entity the service account creates carries an ordinary, honest creatorDeleted flag —
        // false while the client is live, true once the client (and its service account) is revoked.
        val blueprintIdentifier = uniqueName("intc-bp")
        TestBlueprints.service.create(BlueprintRequest(identifier = blueprintIdentifier, title = "Svc test"), serviceUserId)
        val entityIdentifier = uniqueName("intc-ent")
        val entity = TestEntities.service.create(
            EntityRequest(blueprint = blueprintIdentifier, identifier = entityIdentifier, title = "Svc entity"),
            serviceUserId,
        )
        assertFalse(entity.creatorDeleted)

        assertEquals(HttpStatusCode.NoContent, admin.post("/api/v1/integration-clients/$clientId/revoke").status)
        val raw2 = assertNotNull(TestUsers.rawRow(serviceUserId))
        assertTrue(raw2.markedAsDeleted, "revoking a client must soft-delete its service account")

        val entityAfterRevoke = admin.get("/api/v1/entities/${entity.id}").body<EntityResponse>()
        assertTrue(entityAfterRevoke.creatorDeleted)

        TestBlueprints.remove(blueprintIdentifier)
    }
    @Test
    fun `management listing is paged and rejects invalid paging or sort`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("intc-pages", role = UserRole.ADMIN)
        val created = admin.createClient(uniqueName("page")).body<IntegrationClientCreateResponse>()
        val first = admin.get("/api/v1/integration-clients?pageSize=1&sort=-id").body<IntegrationClientListResponse>()
        assertEquals(1, first.page)
        assertEquals(1, first.pageSize)
        assertEquals(created.client.id, first.items.single().id)
        assertTrue(first.total >= 1)
        val far = admin.get("/api/v1/integration-clients?page=2147483647&pageSize=100")
            .body<IntegrationClientListResponse>()
        assertTrue(far.items.isEmpty())
        assertEquals(first.total, far.total)
        for (query in listOf("page=0", "page=no", "pageSize=101", "pageSize=0", "sort=name", "page=1&page=2")) {
            assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/integration-clients?$query").status)
        }
    }

}
