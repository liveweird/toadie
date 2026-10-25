package ch.nokillswit

import ch.nokillswit.infra.db.StatementTimeoutCachingConnection
import ch.nokillswit.infra.db.StatementTimeoutCachingConnectionFactory
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.TransactionDefinition
import io.r2dbc.spi.ValidationDepth
import io.r2dbc.spi.Wrapped
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun signature(m: Method) = "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"

/**
 * `StatementTimeoutCachingConnection` against a RECORDING fake [Connection] (no database): the paths the database-backed
 * `StatementTimeoutCacheTest` cannot steer deterministically (a SET held in flight, forgetting on each trigger) and the
 * plain delegation of every other SPI method.
 */
class StatementTimeoutCachingConnectionTest {

    /**
     * A [Connection] that records each call as `name(paramTypes)` and answers with an empty publisher unless a test
     * steers `onSetTimeout`/`onCommit`.
     */
    private class Fake {
        val calls = mutableListOf<String>()
        private val sentinels = HashMap<Class<*>, Any>()
        var onSetTimeout: () -> Publisher<Void> = { Mono.empty() }
        var onCommit: () -> Publisher<Void> = { Mono.empty() }

        fun sentinel(type: Class<*>): Any = sentinels.getOrPut(type) {
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null }
        }

        val connection: Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, m, _ ->
            calls += signature(m)
            when {
                m.name == "setStatementTimeout" -> onSetTimeout()
                m.name == "commitTransaction" -> onCommit()
                m.returnType == Boolean::class.javaPrimitiveType -> true
                Publisher::class.java.isAssignableFrom(m.returnType) -> Mono.empty<Any>()
                m.returnType.isInterface -> sentinel(m.returnType)
                else -> null
            }
        } as Connection

        fun setTimeoutCalls() = calls.count { it.startsWith("setStatementTimeout") }
    }

    private fun Publisher<*>.run() = Flux.from(this).collectList().block()

    private val two = Duration.ofSeconds(2)

    private fun decorate(fake: Fake) = StatementTimeoutCachingConnection(fake.connection)

    @Test
    fun `an unchanged timeout is skipped, a changed one is sent`() {
        val fake = Fake()
        val cached = decorate(fake)
        cached.setStatementTimeout(two).run()
        cached.setStatementTimeout(two).run()
        assertEquals(1, fake.setTimeoutCalls())
        cached.setStatementTimeout(Duration.ZERO).run()
        assertEquals(2, fake.setTimeoutCalls(), "0 is an ordinary value: sent once when it differs")
        cached.setStatementTimeout(Duration.ZERO).run()
        assertEquals(2, fake.setTimeoutCalls())
    }

    @Test
    fun `the skip is decided at subscription, not when the publisher is assembled`() {
        val fake = Fake()
        val cached = decorate(fake)
        cached.setStatementTimeout(two).run()
        val assembledWhileKnown = cached.setStatementTimeout(two)
        cached.rollbackTransaction().run()
        assembledWhileKnown.run()
        assertEquals(2, fake.setTimeoutCalls(), "a rollback between assembly and subscription must not be skipped over")
    }

    @Test
    fun `a SET that completes after a rollback started is not remembered (the generation guard)`() {
        val fake = Fake()
        val inFlight = Sinks.empty<Void>()
        fake.onSetTimeout = { inFlight.asMono() }
        val cached = decorate(fake)
        // Subscribe without blocking and hold the SET, roll back (forget), then let the SET's reply arrive.
        val done = Mono.from(cached.setStatementTimeout(two)).toFuture()
        assertEquals(1, fake.setTimeoutCalls(), "one SET is in flight")
        cached.rollbackTransaction().run()
        inFlight.tryEmitEmpty()
        done.get()
        fake.onSetTimeout = { Mono.empty() }
        cached.setStatementTimeout(two).run()
        assertEquals(2, fake.setTimeoutCalls(), "the reply that outlived the rollback must not have been remembered")
    }

    @Test
    fun `a SET that completes with no rollback in between is remembered`() {
        val fake = Fake()
        val inFlight = Sinks.empty<Void>()
        fake.onSetTimeout = { inFlight.asMono() }
        val cached = decorate(fake)
        val done = Mono.from(cached.setStatementTimeout(two)).toFuture()
        inFlight.tryEmitEmpty()
        done.get()
        cached.setStatementTimeout(two).run()
        assertEquals(1, fake.setTimeoutCalls())
    }

    @Test
    fun `a failed SET is never remembered`() {
        val fake = Fake()
        fake.onSetTimeout = { Mono.error(IllegalStateException("boom")) }
        val cached = decorate(fake)
        runCatching { cached.setStatementTimeout(two).run() }
        fake.onSetTimeout = { Mono.empty() }
        cached.setStatementTimeout(two).run()
        assertEquals(2, fake.setTimeoutCalls(), "a failed SET leaves nothing remembered")
    }

    private fun assertForgets(trigger: (Connection) -> Unit) {
        val fake = Fake()
        val cached = decorate(fake)
        cached.setStatementTimeout(two).run()
        trigger(cached)
        cached.setStatementTimeout(two).run()
        assertEquals(2, fake.setTimeoutCalls())
    }

    private fun assertKeeps(trigger: (Connection) -> Unit) {
        val fake = Fake()
        val cached = decorate(fake)
        cached.setStatementTimeout(two).run()
        trigger(cached)
        cached.setStatementTimeout(two).run()
        assertEquals(1, fake.setTimeoutCalls())
    }

    @Test
    fun `switching autocommit back on forgets the remembered value, switching it off does not`() {
        assertForgets { it.setAutoCommit(true).run() }
        assertKeeps { it.setAutoCommit(false).run() }
    }

    @Test
    fun `a rollback, also to a savepoint, forgets the remembered value`() {
        assertForgets { it.rollbackTransaction().run() }
        assertForgets { it.rollbackTransactionToSavepoint("sp").run() }
    }

    @Test
    fun `a failed or cancelled commit forgets the remembered value, a successful one keeps it`() {
        assertKeeps { it.commitTransaction().run() }

        val failing = Fake().apply { onCommit = { Mono.error(IllegalStateException("commit refused")) } }
        val failed = decorate(failing)
        failed.setStatementTimeout(two).run()
        runCatching { failed.commitTransaction().run() }
        failed.setStatementTimeout(two).run()
        assertEquals(2, failing.setTimeoutCalls())

        val hung = Fake().apply { onCommit = { Sinks.empty<Void>().asMono() } }
        val cancelled = decorate(hung)
        cancelled.setStatementTimeout(two).run()
        Mono.from(cancelled.commitTransaction()).subscribe().dispose()
        cancelled.setStatementTimeout(two).run()
        assertEquals(2, hung.setTimeoutCalls())
    }

    @Test
    fun `every other SPI method delegates to the inner connection unchanged`() {
        val fake = Fake()
        val cached = decorate(fake)
        val argumentFor = mapOf<Class<*>, Any>(
            String::class.java to "x",
            Duration::class.java to two,
            Boolean::class.javaPrimitiveType!! to false,
            IsolationLevel::class.java to IsolationLevel.READ_COMMITTED,
            ValidationDepth::class.java to ValidationDepth.LOCAL,
            TransactionDefinition::class.java to fake.sentinel(TransactionDefinition::class.java),
        )
        val methods = Connection::class.java.methods.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertTrue(methods.size >= 17, "the SPI surface was not discovered: ${methods.map { it.name }}")
        for (m in methods) {
            fake.calls.clear()
            val args = m.parameterTypes.map { argumentFor[it] ?: error("no sample argument for ${it.name} of ${m.name}") }.toTypedArray()
            val result = m.invoke(cached, *args)
            if (result is Publisher<*>) result.run()
            assertTrue(signature(m) in fake.calls, "${signature(m)} must reach the inner connection, calls were ${fake.calls}")
            if (m.returnType.isInterface && !Publisher::class.java.isAssignableFrom(m.returnType)) {
                assertSame(fake.sentinel(m.returnType), result, "${m.name} must hand back the inner connection's own object")
            }
        }
    }

    @Test
    fun `the factory decorates every connection it creates and unwrap returns the inner one`() {
        val fake = Fake()
        val metadata = ConnectionFactoryMetadata { "fake" }
        val factory = StatementTimeoutCachingConnectionFactory(object : ConnectionFactory {
            override fun create(): Publisher<out Connection> = Mono.just(fake.connection)
            override fun getMetadata(): ConnectionFactoryMetadata = metadata
        })
        assertSame(metadata, factory.metadata)
        val created = Mono.from(factory.create()).block()!!
        assertTrue(created is StatementTimeoutCachingConnection)
        assertNotSame(fake.connection, created)
        assertSame(fake.connection, (created as Wrapped<*>).unwrap())
    }
}
