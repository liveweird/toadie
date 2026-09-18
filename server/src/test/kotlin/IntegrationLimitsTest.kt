package ch.nokillswit.integration

import graphql.introspection.IntrospectionQuery
import graphql.ExecutionInput
import graphql.GraphQL
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.math.pow
import io.ktor.server.plugins.BadRequestException
import ch.nokillswit.authz.TooManyRequestsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IntegrationLimitsTest {
    @Test
    fun `admission has no queue and releases exactly once`() {
        val limits = IntegrationLimits(concurrentRequests = 1)
        val lease = assertNotNull(limits.tryAcquire())
        assertNull(limits.tryAcquire())
        lease.close()
        lease.close()
        assertNotNull(limits.tryAcquire())
    }

    @Test
    fun `per-client windows reset and bucket storage remains bounded`() {
        var now = 1_000L
        val limits = IntegrationLimits(nowMillis = { now }, requestsPerMinute = 2, bucketCapacity = 2)
        assertTrue(limits.allow(1u))
        assertTrue(limits.allow(1u))
        assertFalse(limits.allow(1u))
        now += 60_000
        assertTrue(limits.allow(1u))
        assertTrue(limits.allow(2u))
        assertTrue(limits.allow(3u))
        assertEquals(2, limits.bucketCount())
    }

    @Test
    fun `bounded response preserves exact JSON numbers and rejects overflow before growth`() {
        val number = BigDecimal("12345678901234567890.12345678901234567890")
        val encoded = assertNotNull(boundedGraphQLJson(mapOf("data" to mapOf("number" to number)), maxBytes = 100))
        assertEquals(number.toPlainString(), Json.parseToJsonElement(encoded).jsonObject["data"]!!
            .jsonObject["number"]!!.jsonPrimitive.content)
        assertNull(boundedGraphQLJson(mapOf("data" to "ééé"), maxBytes = 12))
        assertEquals(2, "é".utf8Size())
    }

    @Test
    fun `JsonElement conversion does not route large integers through Double`() {
        val raw = "123456789012345678901234567890"
        assertEquals(raw, (JsonPrimitive(raw.toBigInteger()).toAnyValue()).toString())
    }

    @Test
    fun `Long scalar rejects floating point values that round across its boundary`() {
        val schema = parseIntegrationSchema(
            """
            "JSON value." scalar JSON
            "Long value." scalar Long
            "Root." type Query { "Value." value: Long }
            """.trimIndent(),
        )
        val graphQL = GraphQL.newGraphQL(schema).build()
        val overflow = graphQL.execute(
            ExecutionInput.newExecutionInput().query("{ value }").root(mapOf("value" to 2.0.pow(63))).build(),
        )
        assertTrue(overflow.errors.isNotEmpty())
        val exact = graphQL.execute(
            ExecutionInput.newExecutionInput().query("{ value }").root(mapOf("value" to Long.MAX_VALUE)).build(),
        )
        assertTrue(exact.errors.isEmpty(), exact.errors.toString())
        val data = assertNotNull(exact.getData<Map<String, Any?>>())
        assertEquals(Long.MAX_VALUE, (data["value"] as Number).toLong())
    }

    @Test
    fun `raw JSON scanner rejects deep or excessive trees before recursive decoding`() {
        val deeplyNested = "{".repeat(MAX_GRAPHQL_JSON_DEPTH + 1) + "0" + "}".repeat(MAX_GRAPHQL_JSON_DEPTH + 1)
        assertFailsWith<BadRequestException> { validateGraphQLBodyStructure(deeplyNested) }
        val tooManyValues = "[" + List(MAX_GRAPHQL_JSON_NODES + 1) { "0" }.joinToString(",") + "]"
        assertFailsWith<BadRequestException> { validateGraphQLBodyStructure(tooManyValues) }
        validateGraphQLBodyStructure("""{"query":"{ __typename }","variables":{"text":"[{\\\""}}""")
    }

    @Test
    fun `typed memo keys cannot collide through nullable or delimiter-shaped filters`() {
        assertNotEquals(
            RootRequestKey.Entities(1, 20, null, "a:b", "null"),
            RootRequestKey.Entities(1, 20, "null", "a", "b:null"),
        )
        assertNotEquals(
            RootRequestKey.Errors(listOf("a", "b"), null, null, emptySet(), emptySet()),
            RootRequestKey.Errors(listOf("a\u0000b"), null, null, emptySet(), emptySet()),
        )
    }

    @Test
    fun `memo shares identical loads caps distinct roots and rejects retained overflow`() = runBlocking {
        supervisorScope {
            val ledger = IntegrationRetainedLedger()
            ledger.open().use { reservation ->
                val memo = RequestMemo(this, reservation)
                var loads = 0
                val key = RootRequestKey.Entity(1u)
                assertEquals(mapOf("id" to "1"), memo.get(key) { loads += 1; mapOf("id" to "1") })
                assertEquals(mapOf("id" to "1"), memo.get(key) { loads += 1; emptyMap<String, Any?>() })
                assertEquals(1, loads)

                (2u..8u).forEach { id -> memo.get(RootRequestKey.Entity(id)) { mapOf("id" to id.toString()) } }
                assertFailsWith<BadRequestException> {
                    memo.get(RootRequestKey.Entity(9u)) { mapOf("id" to "9") }
                }
            }
            ledger.open().use { reservation ->
                val fresh = RequestMemo(this, reservation)
                assertFailsWith<BadRequestException> {
                    fresh.get(RootRequestKey.Entity(10u)) { mapOf("payload" to "x".repeat(MAX_RESPONSE_BYTES)) }
                }
            }
        }
        Unit
    }

    @Test
    fun `retained heap ledger rejects compact dense trees and distinguishes contention`() {
        val dense = List(100) { 0 }
        assertTrue(assertNotNull(boundedGraphQLJson(dense, 1_000)).utf8Size() < 1_000)
        val tiny = IntegrationRetainedLedger(capacity = 1_000)
        tiny.open().use { reservation ->
            assertFailsWith<BadRequestException> { reservation.charge(dense) }
        }

        val shared = IntegrationRetainedLedger(capacity = 100)
        shared.open().use { first ->
            first.charge("x".repeat(10))
            shared.open().use { second ->
                assertFailsWith<TooManyRequestsException> { second.charge("y".repeat(10)) }
            }
        }
        shared.open().use { afterRelease -> afterRelease.charge("z".repeat(10)) }
    }

    @Test
    fun `bounded parser accepts introspection and rejects syntax and token floods`() {
        assertTrue(hasValidBoundedSyntax("{ __schema { queryType { name } } }"))
        assertFalse(hasValidBoundedSyntax("{ entity(id: }"))
        val tokenFlood = "{" + (1..MAX_QUERY_TOKENS / 3 + 1).joinToString(" ") { "a:__typename" } + "}"
        assertFalse(hasValidBoundedSyntax(tokenFlood))
    }

    @Test
    fun `standard introspection fits guards while finding alias amplification is rejected`() {
        val sdl = checkNotNull(javaClass.getResource("/graphql/schema.graphqls")).readText()
        val graphQL = guardedIntegrationGraphQL(parseIntegrationSchema(sdl)).build()
        val introspection = graphQL.execute(IntrospectionQuery.INTROSPECTION_QUERY)
        assertTrue(introspection.errors.isEmpty(), introspection.errors.toString())

        val ordinaryEntities = graphQL.execute(
            """{ entities(pageSize: 10) {
              items { id identifier title team findings { code field message } }
              page pageSize total
            } }""",
        )
        assertTrue(ordinaryEntities.errors.isEmpty(), ordinaryEntities.errors.toString())
        val ordinaryErrors = graphQL.execute(
            """{ errors {
              checkedEntities checkedBlueprints
              blueprints(pageSize: 10) { items { id identifier title findings { code field message } } total }
            } }""",
        )
        assertTrue(ordinaryErrors.errors.isEmpty(), ordinaryErrors.errors.toString())

        val aliases = (1..100).joinToString(" ") { "m$it: message" }
        val amplified = graphQL.execute("{ entities { items { findings { $aliases } } } }")
        assertTrue(amplified.errors.isNotEmpty())
    }

    @Test
    fun `dedicated executor honors deadline and parent cancellation`() = runBlocking {
        IntegrationExecutor().use { executor ->
            assertNull(withTimeoutOrNull(10) { kotlinx.coroutines.withContext(executor.dispatcher) { delay(100) } })

            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val job = launch(executor.dispatcher) {
                try {
                    started.complete(Unit)
                    delay(Long.MAX_VALUE)
                } catch (cause: CancellationException) {
                    cancelled.complete(Unit)
                    throw cause
                }
            }
            started.await()
            job.cancelAndJoin()
            assertTrue(cancelled.isCompleted)
        }
    }

    @Test
    fun `request cancellation stops memo loads and releases retained heap`() = runBlocking {
        val ledger = IntegrationRetainedLedger(capacity = 100)
        IntegrationExecutor().use { executor ->
            val started = CompletableDeferred<Unit>()
            val queued = CompletableDeferred<Unit>()
            val loadCancelled = CompletableDeferred<Unit>()
            var queuedLoadRan = false
            val request = launch(executor.dispatcher) {
                ledger.open().use { reservation ->
                    reservation.charge("x".repeat(10))
                    supervisorScope {
                        val memo = RequestMemo(this, reservation)
                        launch {
                            memo.get(RootRequestKey.Entity(1u)) {
                                started.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    loadCancelled.complete(Unit)
                                }
                            }
                        }
                        started.await()
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            memo.get(RootRequestKey.Entity(2u)) {
                                queuedLoadRan = true
                                emptyMap<String, Any?>()
                            }
                        }
                        queued.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            started.await()
            queued.await()
            request.cancelAndJoin()
            assertTrue(loadCancelled.isCompleted)
            assertFalse(queuedLoadRan)
            ledger.open().use { afterCancel -> afterCancel.charge("y".repeat(10)) }
        }
    }
}
