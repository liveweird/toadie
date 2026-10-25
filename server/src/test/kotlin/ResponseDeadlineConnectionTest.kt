package ch.nokillswit

import ch.nokillswit.infra.db.DatabaseResponseTimeoutException
import ch.nokillswit.infra.db.ResponseDeadlineConnection
import ch.nokillswit.infra.db.ResponseDeadlineConnectionFactory
import ch.qos.logback.classic.Level
import io.r2dbc.spi.Batch
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.Readable
import io.r2dbc.spi.Result
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import io.r2dbc.spi.Statement
import io.r2dbc.spi.TransactionDefinition
import io.r2dbc.spi.ValidationDepth
import io.r2dbc.spi.Wrapped
import org.reactivestreams.Publisher
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BiFunction
import java.util.function.Function
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun signature(prefix: String, m: Method) = "$prefix${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"

/**
 * `ResponseDeadlineConnection` against RECORDING fake [Connection]/[Statement]/[Batch]/[Result] objects (no database): the
 * client-side response deadline, poisoning, and the "no escape hatch" surface. A case that expects a timeout uses a 100 ms bound; one
 * that expects none uses [patient] (5 s), so a loaded machine cannot turn a healthy exchange into a timeout. The database-backed
 * cases (a really frozen relay or container) live in `ConnectionPoolTest` and `DatabaseHangTest`.
 */
class ResponseDeadlineConnectionTest {

    private val bound = Duration.ofMillis(100)
    private val patient = Duration.ofSeconds(5)

    /** Counts the timer tasks scheduled and how many are still live (neither run nor disposed). */
    private class CountingScheduler : Scheduler {
        private val inner = Schedulers.parallel()
        val scheduled = AtomicInteger()
        val live = AtomicInteger()

        override fun schedule(task: Runnable): Disposable = inner.schedule(task)

        override fun schedule(task: Runnable, delay: Long, unit: TimeUnit): Disposable {
            scheduled.incrementAndGet()
            live.incrementAndGet()
            val settled = AtomicBoolean()
            val settle = { if (settled.compareAndSet(false, true)) live.decrementAndGet() }
            val handle = inner.schedule({ settle(); task.run() }, delay, unit)
            return Disposable {
                handle.dispose()
                settle()
            }
        }

        override fun createWorker(): Scheduler.Worker = inner.createWorker()
    }

    /**
     * Records each call as `name(paramTypes)` (statement/batch/result calls prefixed `statement.`/`batch.`/`result.`) and
     * answers with an empty publisher unless [hang] or [answers] steer it.
     */
    private class Fake {
        val calls = CopyOnWriteArrayList<String>()
        private val hung = HashSet<String>()
        private val sentinels = HashMap<Class<*>, Any>()
        val answers = HashMap<String, () -> Publisher<*>>()

        /** From now on these inner methods (connection, statement, batch or result, by name) never answer. */
        fun hang(vararg names: String) {
            hung += names
        }

        fun sentinel(type: Class<*>): Any = sentinels.getOrPut(type) { proxy(type) { _, _ -> null } }

        private fun proxy(type: Class<*>, handler: (Method, Array<Any?>?) -> Any?): Any =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, m, args ->
                when {
                    m.declaringClass == Any::class.java -> when (m.name) {
                        "hashCode" -> System.identityHashCode(self)
                        "equals" -> self === args!![0]
                        else -> "fake ${type.simpleName}"
                    }
                    else -> handler(m, args)
                }
            }

        private fun answer(m: Method, self: () -> Any, prefix: String): Any? {
            calls += signature(prefix, m)
            return when {
                m.name in hung && Publisher::class.java.isAssignableFrom(m.returnType) -> Flux.never<Any>()
                m.name in answers -> answers.getValue(m.name)()
                m.name == "validate" -> Mono.just(true)
                m.name == "execute" -> Flux.just(result)
                m.returnType == Boolean::class.javaPrimitiveType -> true
                Publisher::class.java.isAssignableFrom(m.returnType) -> Mono.empty<Any>()
                m.returnType == Statement::class.java || m.returnType == Batch::class.java || m.returnType == Result::class.java -> self()
                m.returnType.isInterface -> sentinel(m.returnType)
                else -> null
            }
        }

        val result: Result = proxy(Result::class.java) { m, _ ->
            if (m.name == "filter") {
                calls += "result.filter"
                filtered
            } else {
                answer(m, { result }, "result.")
            }
        } as Result

        val filtered: Result = proxy(Result::class.java) { m, _ -> answer(m, { filtered }, "filtered.") } as Result

        val statement: Statement = proxy(Statement::class.java) { m, _ -> answer(m, { statement }, "statement.") } as Statement

        val batch: Batch = proxy(Batch::class.java) { m, _ -> answer(m, { batch }, "batch.") } as Batch

        val connection: Connection = proxy(Connection::class.java) { m, _ ->
            when (m.name) {
                "createStatement" -> statement.also { calls += signature("", m) }
                "createBatch" -> batch.also { calls += signature("", m) }
                else -> answer(m, { error("unexpected") }, "")
            }
        } as Connection
    }

    private fun Publisher<*>.run(): List<Any?> = Flux.from(this).collectList().block(Duration.ofSeconds(5))!!

    private fun Publisher<*>.failure(): Throwable =
        assertNotNull(runCatching { run() }.exceptionOrNull(), "the exchange must fail")

    private fun Throwable.assertTimeout(vararg inMessage: String) {
        assertTrue(this is DatabaseResponseTimeoutException, "expected the response-timeout exception, got $this")
        assertEquals("08006", sqlState, "never 57014: that is the server's own cancel")
        for (part in inMessage) assertTrue(part in message.orEmpty(), "the message '$message' must contain '$part'")
    }

    private fun decorate(fake: Fake, timers: Scheduler = Schedulers.parallel(), deadline: Duration = bound) =
        ResponseDeadlineConnection(fake.connection, deadline, timers)

    private val definition = Fake().sentinel(TransactionDefinition::class.java) as TransactionDefinition

    /** Every bounded Mono exchange of the SPI: the inner method hung to simulate a silent server, the kind in the message, the call. */
    private val monoExchanges: List<Triple<String, String, (Connection) -> Publisher<*>>> = listOf(
        Triple("beginTransaction", "transaction start") { c: Connection -> c.beginTransaction() },
        Triple("beginTransaction", "transaction start") { c: Connection -> c.beginTransaction(definition) },
        Triple("commitTransaction", "commit") { c: Connection -> c.commitTransaction() },
        Triple("rollbackTransaction", "rollback") { c: Connection -> c.rollbackTransaction() },
        Triple("rollbackTransactionToSavepoint", "rollback") { c: Connection -> c.rollbackTransactionToSavepoint("sp") },
        Triple("createSavepoint", "savepoint") { c: Connection -> c.createSavepoint("sp") },
        Triple("releaseSavepoint", "savepoint") { c: Connection -> c.releaseSavepoint("sp") },
        Triple("setAutoCommit", "session setting") { c: Connection -> c.setAutoCommit(false) },
        Triple("setLockWaitTimeout", "session setting") { c: Connection -> c.setLockWaitTimeout(bound) },
        Triple("setStatementTimeout", "session setting") { c: Connection -> c.setStatementTimeout(bound) },
        Triple("setTransactionIsolationLevel", "session setting") { c: Connection ->
            c.setTransactionIsolationLevel(IsolationLevel.READ_COMMITTED)
        },
        Triple("validate", "validation") { c: Connection -> c.validate(ValidationDepth.REMOTE) },
    )

    @Test
    fun `every SPI method reaches the inner connection and hands back its own objects while nothing timed out`() {
        val fake = Fake()
        val decorated = decorate(fake, deadline = patient)
        val argumentFor = mapOf<Class<*>, Any>(
            String::class.java to "x",
            Duration::class.java to bound,
            Boolean::class.javaPrimitiveType!! to false,
            IsolationLevel::class.java to IsolationLevel.READ_COMMITTED,
            ValidationDepth::class.java to ValidationDepth.LOCAL,
            TransactionDefinition::class.java to definition,
        )
        val methods = Connection::class.java.methods.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertTrue(methods.size >= 17, "the SPI surface was not discovered: ${methods.map { it.name }}")
        for (m in methods) {
            fake.calls.clear()
            val args = m.parameterTypes.map { argumentFor[it] ?: error("no sample argument for ${it.name} of ${m.name}") }.toTypedArray()
            val result = m.invoke(decorated, *args)
            if (result is Publisher<*>) result.run()
            assertTrue(signature("", m) in fake.calls, "${signature("", m)} must reach the inner connection, calls were ${fake.calls}")
            val wrapsInner = m.name == "createStatement" || m.name == "createBatch"
            if (wrapsInner) {
                assertTrue(result !== fake.statement && result !== fake.batch, "${m.name} must hand back a bounded wrapper")
            } else if (m.returnType.isInterface && !Publisher::class.java.isAssignableFrom(m.returnType)) {
                assertSame(fake.sentinel(m.returnType), result, "${m.name} must hand back the inner connection's own object")
            }
        }
    }

    @Test
    fun `the Connection methods the decorator bounds or wraps are exactly the SPI's, so a new one fails here`() {
        val methods = Connection::class.java.methods.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        // `Connection by delegate` would silently pass a NEW Publisher-returning SPI method straight through, unbounded.
        val publishers = methods.filter { Publisher::class.java.isAssignableFrom(it.returnType) && it.name != "close" }
            .map { signature("", it) }.toSet()
        val bounded = monoExchanges.map { (_, _, call) ->
            val fake = Fake()
            call(decorate(fake, deadline = patient)).run()
            fake.calls.single()
        }.toSet()
        assertEquals(publishers, bounded, "every Publisher-returning Connection method (but close) must be a bounded exchange")
        // The synchronous ones: getters pass through, createStatement/createBatch hand out bounded wrappers. A new one
        // returning a Statement-like object would escape the deadline, so it must be classified here.
        val synchronous = methods.filterNot { Publisher::class.java.isAssignableFrom(it.returnType) }.map { it.name }.toSet()
        assertEquals(
            setOf("isAutoCommit", "getMetadata", "getTransactionIsolationLevel", "createStatement", "createBatch"),
            synchronous,
            "a new synchronous Connection method must be classified (pass-through or wrapped) in ResponseDeadline.kt",
        )
    }

    @Test
    fun `the Statement, Batch and Result wrappers override every method of their interface, defaults included`() {
        val decorated = decorate(Fake(), deadline = patient)
        val statement = decorated.createStatement("SELECT 1")
        val wrapped = listOf(
            Statement::class.java to statement,
            Batch::class.java to decorated.createBatch(),
            Result::class.java to Flux.from(statement.execute()).blockFirst(patient)!!,
        )
        for ((type, wrapper) in wrapped) {
            val own = wrapper.javaClass.declaredMethods.filterNot { it.isBridge || it.isSynthetic }
            for (m in type.methods.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }) {
                assertTrue(
                    own.any { it.name == m.name && it.parameterTypes.contentEquals(m.parameterTypes) },
                    "${type.simpleName}.${signature("", m)} must be overridden by ${wrapper.javaClass.simpleName}: " +
                        "a default or delegated method would hand back (or run on) the unbounded inner object",
                )
            }
        }
    }

    @Test
    fun `statement and batch fluent methods return the wrapper, never the inner object, and execute reaches the inner`() {
        val fake = Fake()
        val statement = decorate(fake, deadline = patient).createStatement("SELECT 1")
        assertNotSame(fake.statement, statement)
        assertSame(statement, statement.add())
        assertSame(statement, statement.bind(0, "x"))
        assertSame(statement, statement.bind("a", "x"))
        assertSame(statement, statement.bindNull(0, String::class.java))
        assertSame(statement, statement.bindNull("a", String::class.java))
        assertSame(statement, statement.returnGeneratedValues("id"))
        assertSame(statement, statement.fetchSize(10))
        for (name in listOf("add", "bind", "bindNull", "returnGeneratedValues", "fetchSize")) {
            assertTrue(fake.calls.any { it.startsWith("statement.$name(") }, "$name must reach the inner statement: ${fake.calls}")
        }
        Flux.from(statement.execute()).blockLast(Duration.ofSeconds(5))
        assertTrue("statement.execute()" in fake.calls)

        val batch = decorate(fake, deadline = patient).createBatch()
        assertNotSame(fake.batch, batch)
        assertSame(batch, batch.add("SELECT 1"))
        Flux.from(batch.execute()).blockLast(Duration.ofSeconds(5))
        assertTrue("batch.add(String)" in fake.calls && "batch.execute()" in fake.calls, "calls were ${fake.calls}")
    }

    @Test
    fun `an exchange that answers in time passes through and poisons nothing`() {
        val fake = Fake()
        fake.answers["commitTransaction"] = { Mono.delay(Duration.ofMillis(20)).then() }
        fake.answers["map"] = { Flux.just("a", "b") }
        val decorated = decorate(fake, deadline = patient)
        decorated.commitTransaction().run()
        val rows = Flux.from(decorated.createStatement("SELECT 1").execute())
            .flatMap { it.map(BiFunction<Row, RowMetadata, String> { _, _ -> "unused" }) }
            .collectList().block(Duration.ofSeconds(5))
        assertEquals(listOf("a", "b"), rows)
        fake.calls.clear()
        assertEquals(listOf(true), decorated.validate(ValidationDepth.LOCAL).run())
        assertEquals(listOf(true), decorated.validate(ValidationDepth.REMOTE).run())
        assertEquals(2, fake.calls.count { it.startsWith("validate") }, "validation must reach the inner connection: ${fake.calls}")
    }

    @Test
    fun `a statement whose first result never arrives fails after the bound and poisons the connection`() {
        val fake = Fake()
        fake.hang("execute")
        val decorated = decorate(fake)
        decorated.createStatement("SELECT secret FROM somewhere").execute().failure().assertTimeout("statement", "100 ms", "discarded")
        fake.calls.clear()
        assertEquals(listOf(false), decorated.validate(ValidationDepth.LOCAL).run())
        assertEquals(listOf(false), decorated.validate(ValidationDepth.REMOTE).run())
        assertTrue(fake.calls.none { it.startsWith("validate") }, "a poisoned connection must not ask the driver: ${fake.calls}")
    }

    @Test
    fun `rows that stall mid-stream deliver what arrived and then fail`() {
        val fake = Fake()
        fake.answers["map"] = { Flux.concat(Flux.just("row"), Flux.never<String>()) }
        val delivered = CopyOnWriteArrayList<Any?>()
        val failure = runCatching {
            Flux.from(decorate(fake).createStatement("SELECT 1").execute())
                .flatMap { it.map(BiFunction<Row, RowMetadata, String> { _, _ -> "unused" }) }
                .doOnNext { delivered += it }
                .collectList().block(Duration.ofSeconds(5))
        }.exceptionOrNull()
        assertEquals(listOf<Any?>("row"), delivered.toList())
        assertNotNull(failure).assertTimeout("statement")
    }

    @Test
    fun `each result read is bounded, also through filter, and so are batches`() {
        val reads: List<Pair<String, (Result) -> Publisher<*>>> = listOf(
            "getRowsUpdated" to { r: Result -> r.rowsUpdated },
            "map" to { r: Result -> r.map(BiFunction<Row, RowMetadata, Int> { _, _ -> 1 }) },
            "map" to { r: Result -> r.map(Function<Readable, Int> { 1 }) },
            "flatMap" to { r: Result -> r.flatMap(Function<Result.Segment, Publisher<Int>> { Mono.just(1) }) },
        )
        for ((name, read) in reads) {
            val fake = Fake()
            fake.hang(name)
            val result = Flux.from(decorate(fake).createStatement("SELECT 1").execute()).blockFirst(Duration.ofSeconds(5))!!
            read(result).failure().assertTimeout("statement")
            // A filtered result is a NEW inner object: it must come back bounded too.
            val fakeFiltered = Fake()
            fakeFiltered.hang(name)
            val filtered = Flux.from(decorate(fakeFiltered).createStatement("SELECT 1").execute()).blockFirst(Duration.ofSeconds(5))!!
                .filter { true }
            assertNotSame(fakeFiltered.filtered, filtered)
            read(filtered).failure().assertTimeout("statement")
        }
        val fake = Fake()
        fake.hang("execute")
        decorate(fake).createBatch().add("SELECT 1").execute().failure().assertTimeout("statement")
    }

    @Test
    fun `every Mono exchange that never answers times out with its kind and poisons the connection`() {
        for ((method, kind, call) in monoExchanges) {
            val fake = Fake()
            fake.hang(method)
            val decorated = decorate(fake)
            call(decorated).failure().assertTimeout(kind, "100 ms")
            fake.calls.clear()
            assertEquals(listOf(false), decorated.validate(ValidationDepth.LOCAL).run(), "$method must have poisoned the connection")
            assertTrue(fake.calls.isEmpty(), "$method: validate must not reach a poisoned connection's driver: ${fake.calls}")
        }
    }

    @Test
    fun `after a poison every exchange is refused without reaching the inner, rollbacks complete empty and close still delegates`() {
        val fake = Fake()
        fake.hang("commitTransaction")
        val decorated = decorate(fake)
        // Assembled BEFORE the poison, subscribed after: the decision is made at subscription.
        val early = decorated.createStatement("SELECT 1").execute()
        decorated.commitTransaction().failure().assertTimeout("commit")
        fake.calls.clear()

        for ((method, _, call) in monoExchanges) {
            when (method) {
                "rollbackTransaction", "rollbackTransactionToSavepoint" ->
                    assertEquals(emptyList<Any?>(), call(decorated).run(), "$method on a poisoned connection completes empty")
                "validate" -> assertEquals(listOf(false), call(decorated).run())
                else -> {
                    val refused = call(decorated).failure()
                    refused.assertTimeout("discarded")
                    assertTrue(refused.cause is DatabaseResponseTimeoutException, "the refusal carries the first timeout as its cause")
                }
            }
        }
        early.failure().assertTimeout("discarded")
        decorated.createStatement("SELECT 1").execute().failure().assertTimeout("discarded")
        decorated.createBatch().add("SELECT 1").execute().failure().assertTimeout("discarded")
        assertTrue(
            fake.calls.all { it in setOf("createStatement(String)", "createBatch()", "batch.add(String)") },
            "nothing but the statement/batch constructors may reach the inner after a poison: ${fake.calls}",
        )

        decorated.close().run()
        assertTrue("close()" in fake.calls, "close() is the destroy path and must still run: ${fake.calls}")
    }

    @Test
    fun `a cancelled exchange does not poison and leaves no timer behind`() {
        val fake = Fake()
        fake.hang("commitTransaction", "execute")
        val timers = CountingScheduler()
        val decorated = decorate(fake, timers, patient)
        val commit = decorated.commitTransaction().let { Mono.from(it).subscribe() }
        val execute = Flux.from(decorated.createStatement("SELECT 1").execute()).subscribe()
        assertEquals(2, timers.live.get(), "each held exchange owns exactly one timer")
        commit.dispose()
        execute.dispose()
        assertEquals(0, timers.live.get(), "cancelling an exchange must dispose its timer")
        fake.calls.clear()
        assertEquals(listOf(true), decorated.validate(ValidationDepth.LOCAL).run(), "a cancel is not a timeout")
        assertEquals(1, fake.calls.count { it.startsWith("validate") })
    }

    @Test
    fun `completed exchanges leave no timer behind`() {
        val fake = Fake()
        val timers = CountingScheduler()
        val decorated = decorate(fake, timers, patient)
        repeat(500) {
            decorated.commitTransaction().run()
            Flux.from(decorated.createStatement("SELECT 1").execute()).blockLast(Duration.ofSeconds(5))
        }
        assertTrue(timers.scheduled.get() >= 1000, "every exchange arms a timer: ${timers.scheduled.get()}")
        assertEquals(0, timers.live.get(), "no timer may outlive its exchange")
    }

    @Test
    fun `the first timeout logs one warning naming the kind and the bound but never the SQL`() {
        val capture = LogCapture("ch.nokillswit.infra.db")
        try {
            val fake = Fake()
            fake.hang("commitTransaction", "execute")
            val decorated = decorate(fake)
            // Both subscribed before either times out: two timeouts race, one warning is logged.
            val statement = Flux.from(decorated.createStatement("SELECT secret_marker FROM nowhere").execute()).collectList().toFuture()
            val commit = Mono.from(decorated.commitTransaction()).toFuture()
            for (future in listOf(statement, commit)) {
                val failure = runCatching { future.get(5, TimeUnit.SECONDS) }.exceptionOrNull()?.cause
                assertNotNull(failure).assertTimeout()
            }
            val warnings = capture.events.filter { it.level == Level.WARN }
            assertEquals(1, warnings.size, "one WARN per discarded connection: ${warnings.map { it.formattedMessage }}")
            val message = warnings.single().formattedMessage
            assertTrue("within 100 ms" in message && "connection is discarded" in message, message)
            assertTrue("secret_marker" !in message, "the log must never carry SQL")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `a zero deadline returns the inner connection itself`() {
        val fake = Fake()
        val factory = ResponseDeadlineConnectionFactory(factoryOf(fake), Duration.ZERO)
        assertSame(fake.connection, Mono.from(factory.create()).block())
    }

    private fun factoryOf(fake: Fake, metadata: ConnectionFactoryMetadata = ConnectionFactoryMetadata { "fake" }) =
        object : ConnectionFactory {
            override fun create(): Publisher<out Connection> = Mono.just(fake.connection)
            override fun getMetadata(): ConnectionFactoryMetadata = metadata
        }

    @Test
    fun `the factory decorates every connection it creates and unwrap returns the inner one`() {
        val fake = Fake()
        val metadata = ConnectionFactoryMetadata { "fake" }
        val factory = ResponseDeadlineConnectionFactory(factoryOf(fake, metadata), bound)
        assertSame(metadata, factory.metadata)
        val created = Mono.from(factory.create()).block()!!
        assertTrue(created is ResponseDeadlineConnection)
        assertNotSame(fake.connection, created)
        assertSame(fake.connection, (created as Wrapped<*>).unwrap())
    }
}
