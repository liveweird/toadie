package ch.nokillswit

import ch.nokillswit.dictionaries.Dictionary
import ch.nokillswit.dictionaries.DictionaryEntryInput
import ch.nokillswit.dictionaries.DictionaryEntryList
import ch.nokillswit.dictionaries.DictionaryUpdateRequest
import ch.nokillswit.dictionaries.MAX_DICTIONARY_ENTRIES
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The dictionaries surface (namespaces is the only dictionary today): whole-document replace
 * semantics, the payload rules, and the authz split. The namespaces document is SHARED suite
 * state — document-replacing tests run inside [withNamespacesDocument], which snapshots the
 * active values and restores them afterwards (ids are reminted; nothing keys on them).
 */
class DictionaryTest {

    private fun ns(prefix: String) = "$prefix-${UUID.randomUUID()}"

    private suspend fun HttpClient.readNamespaces(): DictionaryEntryList =
        get("/api/v1/dictionaries/namespaces").body()

    private suspend fun withNamespacesDocument(block: suspend () -> Unit) {
        val snapshot = TestNamespaces.service.read(Dictionary.NAMESPACE).map { it.value }
        try {
            block()
        } finally {
            TestNamespaces.service.replace(
                Dictionary.NAMESPACE,
                DictionaryUpdateRequest(snapshot.map { DictionaryEntryInput(value = it) }),
            )
        }
    }

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/dictionaries/namespaces").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/dictionaries/namespaces").status)
    }

    @Test
    fun `non-admin may read but not write - uniformly 403 even on an unknown slug`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("dictuser")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/dictionaries/namespaces").status)
        val payload = DictionaryUpdateRequest(listOf(DictionaryEntryInput(value = ns("nope"))))
        assertEquals(HttpStatusCode.Forbidden, client.putJson("/api/v1/dictionaries/namespaces", payload).status)
        // Guard-before-slug-resolution: the probe cannot distinguish real from unknown slugs.
        assertEquals(HttpStatusCode.Forbidden, client.putJson("/api/v1/dictionaries/bogus", payload).status)
    }

    @Test
    fun `unknown slug is 404 for authenticated readers and admin writers`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("dictslug")
        assertEquals(HttpStatusCode.NotFound, user.get("/api/v1/dictionaries/bogus").status)
        val admin = seededClient("dictslugadm", UserRole.ADMIN)
        val payload = DictionaryUpdateRequest(listOf(DictionaryEntryInput(value = ns("nope"))))
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/dictionaries/bogus", payload).status)
    }

    @Test
    fun `default namespace is seeded`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("dictseed")
        assertTrue(
            client.readNamespaces().items.any { it.value == "default" },
            "V8 must seed the default namespace",
        )
    }

    @Test
    fun `replace inserts in payload order, reorders keeping ids, and renames in place`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dictcrud", UserRole.ADMIN)
        withNamespacesDocument {
            val (a, b) = ns("aa") to ns("bb")
            val put1 = admin.putJson(
                "/api/v1/dictionaries/namespaces",
                DictionaryUpdateRequest(listOf(DictionaryEntryInput(value = a), DictionaryEntryInput(value = b))),
            )
            assertEquals(HttpStatusCode.NoContent, put1.status)
            val after1 = admin.readNamespaces().items
            assertEquals(listOf(a, b), after1.map { it.value }, "payload order is the stored order")

            val (idA, idB) = after1[0].id to after1[1].id
            val renamed = ns("cc")
            val put2 = admin.putJson(
                "/api/v1/dictionaries/namespaces",
                DictionaryUpdateRequest(
                    listOf(DictionaryEntryInput(idB, b), DictionaryEntryInput(idA, renamed)),
                ),
            )
            assertEquals(HttpStatusCode.NoContent, put2.status)
            val after2 = admin.readNamespaces().items
            assertEquals(listOf(b, renamed), after2.map { it.value }, "reordered + renamed")
            assertEquals(listOf(idB, idA), after2.map { it.id }, "identity survives reorder and rename")
        }
    }

    @Test
    fun `an omitted entry is soft-deleted - flagged, never physically removed`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dictdel", UserRole.ADMIN)
        withNamespacesDocument {
            val (keep, drop) = ns("keep") to ns("drop")
            val (_, dropId) = TestNamespaces.ensure(keep, drop).let { it[0] to it[1] }
            val put = admin.putJson(
                "/api/v1/dictionaries/namespaces",
                DictionaryUpdateRequest(
                    TestNamespaces.service.read(Dictionary.NAMESPACE)
                        .filterNot { it.id == dropId }
                        .map { DictionaryEntryInput(it.id, it.value) },
                ),
            )
            assertEquals(HttpStatusCode.NoContent, put.status)
            assertTrue(admin.readNamespaces().items.none { it.value == drop })
            val raw = TestNamespaces.rawRows().first { it.id == dropId }
            assertTrue(raw.markedAsDeleted, "soft-deleted, not removed")
            assertEquals(drop, raw.value, "the dead row keeps its value")
        }
    }

    @Test
    fun `duplicate, foreign and soft-deleted payload ids are 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dictids", UserRole.ADMIN)
        withNamespacesDocument {
            val value = ns("idv")
            val (id) = TestNamespaces.ensure(value)
            val current = TestNamespaces.service.read(Dictionary.NAMESPACE)
                .map { DictionaryEntryInput(it.id, it.value) }

            val dup = current + DictionaryEntryInput(id, ns("dup"))
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.putJson("/api/v1/dictionaries/namespaces", DictionaryUpdateRequest(dup)).status,
            )

            val foreign = current + DictionaryEntryInput(999_999_999u, ns("foreign"))
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.putJson("/api/v1/dictionaries/namespaces", DictionaryUpdateRequest(foreign)).status,
            )

            TestNamespaces.remove(value)
            val resurrect = TestNamespaces.service.read(Dictionary.NAMESPACE)
                .map { DictionaryEntryInput(it.id, it.value) } + DictionaryEntryInput(id, value)
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.putJson("/api/v1/dictionaries/namespaces", DictionaryUpdateRequest(resurrect)).status,
                "a soft-deleted id is foreign — deleted entries are never resurrected",
            )
        }
    }

    @Test
    fun `values are trimmed and folded to lowercase before storage`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dictfold", UserRole.ADMIN)
        withNamespacesDocument {
            val raw = ns("fold")
            val put = admin.putJson(
                "/api/v1/dictionaries/namespaces",
                DictionaryUpdateRequest(listOf(DictionaryEntryInput(value = "  ${raw.uppercase()}  "))),
            )
            assertEquals(HttpStatusCode.NoContent, put.status)
            assertEquals(listOf(raw), admin.readNamespaces().items.map { it.value })
        }
    }

    @Test
    fun `payload duplicates are rejected after folding`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dictdup", UserRole.ADMIN)
        val value = ns("dupfold")
        val payload = DictionaryUpdateRequest(
            listOf(DictionaryEntryInput(value = value), DictionaryEntryInput(value = " ${value.uppercase()} ")),
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson("/api/v1/dictionaries/namespaces", payload).status,
        )
    }

    @Test
    fun `values violating the namespace grammar are 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dictgram", UserRole.ADMIN)
        for (bad in listOf("under_score", "-leading-dash", "double--dash", "   ", "dot.ted")) {
            val payload = DictionaryUpdateRequest(listOf(DictionaryEntryInput(value = bad)))
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.putJson("/api/v1/dictionaries/namespaces", payload).status,
                "'$bad' must be rejected",
            )
        }
    }

    @Test
    fun `value length and entry count limits are 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dictlim", UserRole.ADMIN)
        val tooLong = DictionaryUpdateRequest(listOf(DictionaryEntryInput(value = "a".repeat(64))))
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson("/api/v1/dictionaries/namespaces", tooLong).status,
        )
        val tooMany = DictionaryUpdateRequest(
            (0..MAX_DICTIONARY_ENTRIES).map { DictionaryEntryInput(value = "n-$it") },
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson("/api/v1/dictionaries/namespaces", tooMany).status,
        )
    }

    @Test
    fun `an empty payload clears the dictionary`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dictclear", UserRole.ADMIN)
        withNamespacesDocument {
            val put = admin.putJson(
                "/api/v1/dictionaries/namespaces",
                DictionaryUpdateRequest(emptyList()),
            )
            assertEquals(HttpStatusCode.NoContent, put.status)
            assertEquals(emptyList(), admin.readNamespaces().items)
        }
    }

    @Test
    fun `a soft-deleted value is reusable and re-adding mints a new id`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dictreuse", UserRole.ADMIN)
        withNamespacesDocument {
            val value = ns("reuse")
            val (oldId) = TestNamespaces.ensure(value)
            TestNamespaces.remove(value)
            val (newId) = TestNamespaces.ensure(value)
            assertTrue(newId != oldId, "re-adding a removed value mints a NEW id")
            assertTrue(admin.readNamespaces().items.any { it.id == newId && it.value == value })
        }
    }

    @Test
    fun `swapping two values in one save is 409 - the documented limitation`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dictswap", UserRole.ADMIN)
        withNamespacesDocument {
            val (a, b) = ns("swapa") to ns("swapb")
            val (idA, idB) = TestNamespaces.ensure(a, b).let { it[0] to it[1] }
            val others = TestNamespaces.service.read(Dictionary.NAMESPACE)
                .filterNot { it.id == idA || it.id == idB }
                .map { DictionaryEntryInput(it.id, it.value) }
            val swap = DictionaryUpdateRequest(
                others + listOf(DictionaryEntryInput(idA, b), DictionaryEntryInput(idB, a)),
            )
            assertEquals(
                HttpStatusCode.Conflict,
                admin.putJson("/api/v1/dictionaries/namespaces", swap).status,
                "trades trip the partial unique index mid-save — rename through a temp value instead",
            )
        }
    }

    @Test
    fun `a successful replace emits the audited counts and a failed one emits nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dictaudit", UserRole.ADMIN)
        withNamespacesDocument {
            withAuditCapture { capture ->
                val (a, b) = ns("auda") to ns("audb")
                val put = admin.putJson(
                    "/api/v1/dictionaries/namespaces",
                    DictionaryUpdateRequest(listOf(DictionaryEntryInput(value = a), DictionaryEntryInput(value = b))),
                )
                assertEquals(HttpStatusCode.NoContent, put.status)
                val event = capture.awaitEvent { it.message == "dictionary.updated" }
                assertNotNull(event, "successful replace must audit")
                assertTrue(event.hasKeyValue("dictionary", "NAMESPACE"))
                assertTrue(event.hasKeyValue("added", 2))
                assertTrue(event.hasKeyValue("renamed", 0))

                val before = capture.events.count { it.message == "dictionary.updated" }
                val bad = DictionaryUpdateRequest(listOf(DictionaryEntryInput(value = "no_good")))
                assertEquals(
                    HttpStatusCode.BadRequest,
                    admin.putJson("/api/v1/dictionaries/namespaces", bad).status,
                )
                assertEquals(
                    before,
                    capture.events.count { it.message == "dictionary.updated" },
                    "failed replace must not audit",
                )
            }
        }
    }
}
