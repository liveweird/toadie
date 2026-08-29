package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.infra.db.SEED_ADMIN_EMAIL
import ch.nokillswit.infra.db.SEED_PASSWORD_HASH
import ch.nokillswit.plugins.isUniqueViolation
import ch.nokillswit.users.User
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * Points the app at the shared Testcontainers Postgres (with CSRF off) WITHOUT starting it —
 * callers that assert startup behavior (fail-closed checks) add their own overrides and call
 * `startApplication()` themselves. Later duplicate keys win in [MapApplicationConfig], so
 * [overrides] may replace the defaults listed first.
 */
fun ApplicationTestBuilder.configureApp(vararg overrides: Pair<String, String>) {
    environment {
        config = ApplicationConfig("application.yaml").mergeWith(
            MapApplicationConfig(
                "postgres.jdbcUrl" to PostgresTestSupport.jdbcUrl,
                "postgres.r2dbcUrl" to PostgresTestSupport.r2dbcUrl,
                "postgres.user" to PostgresTestSupport.user,
                "postgres.password" to PostgresTestSupport.password,
                "security.csrf.enabled" to "false",
                *overrides,
            )
        )
    }
}

suspend fun ApplicationTestBuilder.usePostgresTestcontainer() {
    configureApp()
    startApplication()
    TestRefTargets.ensure()
}

/**
 * Shared config for every test HTTP client: JSON (+ problem+json) negotiation and the
 * [OpenApiConformance] plugin, which validates each /api/ interaction against the OpenAPI spec.
 */
fun HttpClientConfig<*>.toadieTestClientDefaults() {
    install(ContentNegotiation) { json(); json(contentType = ContentType.parse("application/problem+json")) }
    install(OpenApiConformance)
}

fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient { toadieTestClientDefaults() }

/** A unique throwaway email so tests never collide on the partial-unique active-email index. */
fun uniqueEmail(prefix: String) = "$prefix-${java.util.UUID.randomUUID()}@test"

/** POSTs [body] as JSON — the contentType+setBody ceremony, owned once. */
suspend inline fun <reified T> HttpClient.postJson(path: String, body: T): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

/** PUTs [body] as JSON. */
suspend inline fun <reified T> HttpClient.putJson(path: String, body: T): HttpResponse =
    put(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

/** The raw login POST — for tests asserting login behavior itself ([authedClient] wraps it). */
suspend fun HttpClient.login(email: String, password: String): HttpResponse =
    postJson("/api/v1/login", LoginRequest(email, password))

/** Logs in as [email] and returns a client that sends the bearer token on every request. */
suspend fun ApplicationTestBuilder.authedClient(email: String, password: String): HttpClient {
    val token = jsonClient().login(email, password).body<LoginResponse>().token
    return createClient {
        toadieTestClientDefaults()
        install(DefaultRequest) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }
}

/** Seeds a unique throwaway user and logs them in — the standard per-test caller fixture. */
suspend fun ApplicationTestBuilder.seededClient(prefix: String, role: UserRole = UserRole.USER): HttpClient {
    val email = uniqueEmail(prefix)
    TestUsers.seed(email = email, password = "pw", role = role)
    return authedClient(email, "pw")
}

/**
 * Captures a logger's events with a Logback ListAppender (the audit trail on
 * `ch.nokillswit.audit`). Use in a try/finally with [detach]; [awaitEvent] polls for
 * asynchronously produced events.
 */
class LogCapture(loggerName: String) {
    private val logger = org.slf4j.LoggerFactory.getLogger(loggerName) as ch.qos.logback.classic.Logger
    private val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()

    init {
        appender.start()
        logger.addAppender(appender)
    }

    val events: List<ch.qos.logback.classic.spi.ILoggingEvent> get() = appender.list

    fun detach() = logger.detachAppender(appender)

    suspend fun awaitEvent(
        predicate: (ch.qos.logback.classic.spi.ILoggingEvent) -> Boolean,
    ): ch.qos.logback.classic.spi.ILoggingEvent? {
        repeat(100) {
            events.firstOrNull(predicate)?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        return null
    }
}

/**
 * audit() fields travel as SLF4J key/values, not in the message text — and TYPED: ids arrive
 * as Longs, flags as Booleans, so compare with the same type the emitter used.
 */
fun ch.qos.logback.classic.spi.ILoggingEvent.hasKeyValue(key: String, value: Any?) =
    keyValuePairs?.any { it.key == key && it.value == value } == true

/** The audit-trail capture scaffold: attaches to the audit logger and always detaches. */
suspend fun <T> withAuditCapture(block: suspend (LogCapture) -> T): T {
    val capture = LogCapture("ch.nokillswit.audit")
    return try {
        block(capture)
    } finally {
        capture.detach()
    }
}

/** Bootstrap/prod-mode scaffold: whatever [block] does to the seed admin is restored after. */
suspend fun withSeedRestored(block: suspend () -> Unit) {
    try {
        block()
    } finally {
        TestSeedState.restoreSeedAccounts()
    }
}

/** Asserts the app refuses to start and that the failure cause chain mentions [messagePart]. */
suspend fun assertStartupFails(messagePart: String, start: suspend () -> Unit) {
    val failure = runCatching { start() }.exceptionOrNull()
    kotlin.test.assertNotNull(failure, "startup must fail closed")
    val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    kotlin.test.assertTrue(messagePart in messages, "unexpected startup failure: $messages")
}

/** A fresh blocklist service (own cache) over the shared container DB, with an injected clock. */
internal fun newTokenBlocklistService(clock: () -> Long): ch.nokillswit.auth.TokenBlocklistService =
    ch.nokillswit.auth.TokenBlocklistService(sharedTestDatabase, clock)

private val sharedTestDatabase: R2dbcDatabase by lazy {
    R2dbcDatabase.connect(
        url = PostgresTestSupport.r2dbcUrl,
        user = PostgresTestSupport.user,
        password = PostgresTestSupport.password,
    )
}

object TestUsers {
    val service: UserService by lazy { UserService(sharedTestDatabase) }

    suspend fun seed(
        email: String,
        password: String,
        name: String = "Test",
        role: UserRole = UserRole.ADMIN,
    ): UInt = service.create(
        User(
            name = name,
            email = email,
            passwordHash = hashPassword(password, cost = 4),
            role = role,
        )
    )

    /** Direct soft-delete for fixtures needing to bypass the endpoint's guards. */
    suspend fun softDelete(id: UInt) {
        suspendTransaction(sharedTestDatabase) {
            UserService.Users.update({ UserService.Users.id eq id }) {
                it[UserService.Users.markedAsDeleted] = true
            }
        }
    }

    /**
     * Runs [block] while the users in [soloAdminIds] are the ONLY active admins — every other
     * active ADMIN row (the seed admin and other tests' fixtures included) is temporarily
     * soft-deleted and restored in a finally. Backs the last-admin-protection tests, which
     * need `countActiveAdmins()` to be exact in the shared container.
     */
    suspend fun withSoloAdmins(soloAdminIds: Set<UInt>, block: suspend () -> Unit) {
        val parked: List<UInt> = suspendTransaction(sharedTestDatabase) {
            val others = UserService.Users.selectAll()
                .where {
                    (UserService.Users.role eq UserRole.ADMIN.name) and
                        (UserService.Users.markedAsDeleted eq false)
                }
                .map { it[UserService.Users.id].value }
                .toList()
                .filter { it !in soloAdminIds }
            UserService.Users.update({ UserService.Users.id inList others }) {
                it[UserService.Users.markedAsDeleted] = true
            }
            others
        }
        try {
            block()
        } finally {
            suspendTransaction(sharedTestDatabase) {
                UserService.Users.update({ UserService.Users.id inList parked }) {
                    it[UserService.Users.markedAsDeleted] = false
                }
            }
        }
    }
}

/** Direct service handle for contracts the routes can't exercise (e.g. blank-filter rules). */
object TestCatalogFiles {
    val service: ch.nokillswit.catalog.CatalogFileService by lazy {
        ch.nokillswit.catalog.CatalogFileService(sharedTestDatabase)
    }

    /**
     * Direct content overwrite bypassing the write path's validation (the TestUsers.softDelete
     * bypass precedent) — for planting legacy rows the API can no longer produce, e.g. a
     * stored self-reference for the cross-check report tests.
     */
    suspend fun overwriteContent(id: UInt, file: ch.nokillswit.catalog.CatalogFile) {
        val table = ch.nokillswit.catalog.CatalogFileService.CatalogFiles
        suspendTransaction(sharedTestDatabase) {
            table.update({ table.id eq id }) {
                it[table.content] = Json.encodeToString(file)
            }
        }
    }
}

/**
 * The well-known reference targets the catalog fixtures point at — catalog writes reject
 * unresolved references, so the fixtures' default `owner = "group:default/platform"` needs
 * that Group STORED (a test seed analogous to the V3 admin). [ensure] is idempotent across
 * tests (JVM once-guard) and runs (create races lose to the identity index and are fine);
 * invoked from [usePostgresTestcontainer], so every ordinary test can rely on it.
 */
object TestRefTargets {
    @Volatile
    private var seeded = false

    suspend fun ensure() {
        if (seeded) return
        val creator = TestUsers.service.findWithIdByEmail("ref-targets@toadie.test")?.first
            ?: TestUsers.seed(email = "ref-targets@toadie.test", password = "pw", name = "Ref Targets")
        try {
            TestCatalogFiles.service.create(groupFile("platform"), createdByUserId = creator)
        } catch (e: Exception) {
            if (!e.isUniqueViolation()) throw e // already stored by an earlier run
        }
        seeded = true
    }
}

/**
 * The namespaces dictionary is SHARED suite state (like the seed admin) — tests append
 * unique throwaway values via [ensure] rather than replacing the whole document, so the
 * V8 `default` seed and other tests' values survive. [rawRows] reads soft-deleted rows
 * too (the API read filters active) to assert flagging over physical removal.
 */
object TestNamespaces {
    val service: ch.nokillswit.dictionaries.DictionaryService by lazy {
        ch.nokillswit.dictionaries.DictionaryService(sharedTestDatabase)
    }

    private val DICT = ch.nokillswit.dictionaries.Dictionary.NAMESPACE

    data class RawEntry(
        val id: UInt,
        val value: String,
        val position: Int,
        val isDefault: Boolean,
        val markedAsDeleted: Boolean,
    )

    suspend fun rawRows(): List<RawEntry> = suspendTransaction(sharedTestDatabase) {
        val t = ch.nokillswit.dictionaries.DictionaryService.Entries
        t.selectAll()
            .where { t.dictionary eq DICT.name }
            .map { RawEntry(it[t.id].value, it[t.value], it[t.position], it[t.isDefault], it[t.markedAsDeleted]) }
            .toList()
    }

    /** The active document as replayable inputs — ids AND default flags preserved. */
    private suspend fun currentInputs(): List<ch.nokillswit.dictionaries.DictionaryEntryInput> =
        service.read(DICT).map {
            ch.nokillswit.dictionaries.DictionaryEntryInput(it.id, it.value, isDefault = it.isDefault)
        }

    /**
     * Ensures every value in [values] is an active namespace entry (append-preserving
     * whole-document replace keeping every existing flag) and returns their ids in
     * [values] order.
     */
    suspend fun ensure(vararg values: String): List<UInt> {
        val current = service.read(DICT)
        val missing = values.filterNot { v -> current.any { it.value == v } }
        if (missing.isNotEmpty()) {
            service.replace(
                DICT,
                ch.nokillswit.dictionaries.DictionaryUpdateRequest(
                    currentInputs() + missing.map { ch.nokillswit.dictionaries.DictionaryEntryInput(value = it) },
                ),
            )
        }
        val byValue = service.read(DICT).associate { it.value to it.id }
        return values.map { byValue.getValue(it) }
    }

    /** Removes [values] from the active document (a no-op for values not present; flags kept). */
    suspend fun remove(vararg values: String) {
        service.replace(
            DICT,
            ch.nokillswit.dictionaries.DictionaryUpdateRequest(currentInputs().filterNot { it.value in values }),
        )
    }

    /**
     * Snapshot of the active document as id-LESS inputs (flags kept) — replay with
     * [replaceDocument] to restore after a document-mutating test (ids are reminted;
     * nothing keys on them).
     */
    suspend fun snapshotValues(): List<ch.nokillswit.dictionaries.DictionaryEntryInput> =
        service.read(DICT).map {
            ch.nokillswit.dictionaries.DictionaryEntryInput(value = it.value, isDefault = it.isDefault)
        }

    suspend fun replaceDocument(items: List<ch.nokillswit.dictionaries.DictionaryEntryInput>) {
        service.replace(DICT, ch.nokillswit.dictionaries.DictionaryUpdateRequest(items))
    }

    /**
     * Runs [block] with [value] as the flagged DEFAULT namespace (registered if missing),
     * restoring the prior flag — and removing [value] again if this call added it — in a
     * finally. The flag is SHARED suite state exactly like the seed admin.
     */
    suspend fun withDefaultNamespace(value: String, block: suspend () -> Unit) {
        val before = service.read(DICT)
        val prior = before.firstOrNull { it.isDefault }?.value
        val added = before.none { it.value == value }
        ensure(value)
        setDefault(value)
        try {
            block()
        } finally {
            if (prior != null && prior != value) {
                setDefault(prior)
                if (added) remove(value)
            }
        }
    }

    private suspend fun setDefault(value: String) {
        service.replace(
            DICT,
            ch.nokillswit.dictionaries.DictionaryUpdateRequest(
                service.read(DICT).map {
                    ch.nokillswit.dictionaries.DictionaryEntryInput(it.id, it.value, isDefault = it.value == value)
                },
            ),
        )
    }
}

/**
 * The lifecycles dictionary is SHARED suite state too (V16 seeds experimental/production/
 * deprecated — every catalog fixture's lifecycle rides those): tests append unique
 * throwaway values via [ensure] and remove only what they added, so the seeds and other
 * tests' values survive. No default-flag plumbing — the LIFECYCLE dictionary has none.
 */
object TestLifecycles {
    val service: ch.nokillswit.dictionaries.DictionaryService by lazy {
        ch.nokillswit.dictionaries.DictionaryService(sharedTestDatabase)
    }

    private val DICT = ch.nokillswit.dictionaries.Dictionary.LIFECYCLE

    private suspend fun currentInputs(): List<ch.nokillswit.dictionaries.DictionaryEntryInput> =
        service.read(DICT).map { ch.nokillswit.dictionaries.DictionaryEntryInput(it.id, it.value) }

    /** Ensures every value in [values] is an active lifecycle entry (append-preserving replace). */
    suspend fun ensure(vararg values: String) {
        val current = service.read(DICT)
        val missing = values.filterNot { v -> current.any { it.value == v } }
        if (missing.isNotEmpty()) {
            service.replace(
                DICT,
                ch.nokillswit.dictionaries.DictionaryUpdateRequest(
                    currentInputs() + missing.map { ch.nokillswit.dictionaries.DictionaryEntryInput(value = it) },
                ),
            )
        }
    }

    /** Removes [values] from the active document (a no-op for values not present). */
    suspend fun remove(vararg values: String) {
        service.replace(
            DICT,
            ch.nokillswit.dictionaries.DictionaryUpdateRequest(currentInputs().filterNot { it.value in values }),
        )
    }
}

/**
 * Direct access to the SHARED label registry (V10) — like the namespaces dictionary, suite
 * state every catalog write is checked against. Tests only ever mint UNIQUE keys (the
 * `uniqueLabel` fixture) and remove them when a test's assertions depend on absence.
 */
object TestLabels {
    val service: ch.nokillswit.labels.LabelService by lazy {
        ch.nokillswit.labels.LabelService(sharedTestDatabase)
    }

    data class RawRow(val id: UInt, val key: String, val markedAsDeleted: Boolean)

    suspend fun rawRows(): List<RawRow> = suspendTransaction(sharedTestDatabase) {
        val t = ch.nokillswit.labels.LabelService.Labels
        t.selectAll().map { RawRow(it[t.id].value, it[t.key], it[t.markedAsDeleted]) }.toList()
    }

    /** Registers [key] (create-if-missing; an existing active label is updated) and returns its id. */
    suspend fun ensure(key: String, values: List<String>, kinds: List<String>): UInt {
        val request = ch.nokillswit.labels.LabelRequest(key = key, values = values, kinds = kinds)
        val existing = service.list().firstOrNull { it.key.equals(key, ignoreCase = true) }
        if (existing != null) {
            service.update(existing.id, request)
            return existing.id
        }
        return service.create(request)
    }

    /** Soft-deletes the active labels holding [keys] (a no-op for keys not present). */
    suspend fun remove(vararg keys: String) {
        service.list().filter { label -> keys.any { it.equals(label.key, ignoreCase = true) } }
            .forEach { service.delete(it.id) }
    }
}

/**
 * Direct access to the SHARED annotation-key registry (V17) — the label registry's sibling
 * (keys + kinds, no values), suite state every catalog write's annotation keys are checked
 * against. Tests only ever mint UNIQUE keys (the `uniqueAnnotationKey` fixture) and remove
 * them when a test's assertions depend on absence.
 */
object TestAnnotationKeys {
    val service: ch.nokillswit.annotations.AnnotationKeyService by lazy {
        ch.nokillswit.annotations.AnnotationKeyService(sharedTestDatabase)
    }

    data class RawRow(val id: UInt, val key: String, val markedAsDeleted: Boolean)

    suspend fun rawRows(): List<RawRow> = suspendTransaction(sharedTestDatabase) {
        val t = ch.nokillswit.annotations.AnnotationKeyService.AnnotationKeys
        t.selectAll().map { RawRow(it[t.id].value, it[t.key], it[t.markedAsDeleted]) }.toList()
    }

    /** Registers [key] (create-if-missing; an existing active row is updated) and returns its id. */
    suspend fun ensure(key: String, kinds: List<String>): UInt {
        val request = ch.nokillswit.annotations.AnnotationKeyRequest(key = key, kinds = kinds)
        val existing = service.list().firstOrNull { it.key.equals(key, ignoreCase = true) }
        if (existing != null) {
            service.update(existing.id, request)
            return existing.id
        }
        return service.create(request)
    }

    /** Soft-deletes the active rows holding [keys] (a no-op for keys not present). */
    suspend fun remove(vararg keys: String) {
        service.list().filter { row -> keys.any { it.equals(row.key, ignoreCase = true) } }
            .forEach { service.delete(it.id) }
    }
}

/**
 * Direct access to the SHARED tag-category registry (V11) — like the label registry, suite
 * state every catalog write is checked against. Tests only ever mint UNIQUE names and tags
 * (the `uniqueTagCategory` fixture) and remove them when assertions depend on absence.
 */
object TestTagCategories {
    val service: ch.nokillswit.tags.TagCategoryService by lazy {
        ch.nokillswit.tags.TagCategoryService(sharedTestDatabase)
    }

    data class RawRow(val id: UInt, val name: String, val markedAsDeleted: Boolean)

    suspend fun rawRows(): List<RawRow> = suspendTransaction(sharedTestDatabase) {
        val t = ch.nokillswit.tags.TagCategoryService.TagCategories
        t.selectAll().map { RawRow(it[t.id].value, it[t.name], it[t.markedAsDeleted]) }.toList()
    }

    /** Registers [name] (create-if-missing; an existing active category is updated) and returns its id. */
    suspend fun ensure(name: String, tags: List<String>, kinds: List<String>): UInt {
        val request = ch.nokillswit.tags.TagCategoryRequest(name = name, tags = tags, kinds = kinds)
        val existing = service.list().firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            service.update(existing.id, request)
            return existing.id
        }
        return service.create(request)
    }

    /** Soft-deletes the active categories holding [names] (a no-op for names not present). */
    suspend fun remove(vararg names: String) {
        service.list().filter { category -> names.any { it.equals(category.name, ignoreCase = true) } }
            .forEach { service.delete(it.id) }
    }
}

/**
 * Direct access to the SHARED per-kind type dictionaries (V14, seeded by V15) — suite state
 * every catalog write's spec.type is checked against. Unlike labels/tags, the rows are
 * per-kind SINGLETONS (at most six, all seeded), so tests cannot mint unique registries:
 * anything that must change a kind's dictionary goes through [withKindTypes], which
 * restores the prior state in a finally (the TestNamespaces.withDefaultNamespace idiom).
 */
object TestEntityTypes {
    val service: ch.nokillswit.types.EntityTypesService by lazy {
        ch.nokillswit.types.EntityTypesService(sharedTestDatabase)
    }

    data class RawRow(val id: UInt, val kind: String, val markedAsDeleted: Boolean)

    suspend fun rawRows(): List<RawRow> = suspendTransaction(sharedTestDatabase) {
        val t = ch.nokillswit.types.EntityTypesService.EntityTypes
        t.selectAll().map { RawRow(it[t.id].value, it[t.kind], it[t.markedAsDeleted]) }.toList()
    }

    /** The kind's active dictionary, or null when it has none. */
    suspend fun current(kind: String): ch.nokillswit.types.EntityTypesResponse? =
        service.list().singleOrNull { it.kind == kind }

    private suspend fun apply(kind: String, types: List<String>?) {
        val existing = current(kind)
        when {
            existing == null && types != null ->
                service.create(ch.nokillswit.types.EntityTypesRequest(kind = kind, types = types))
            existing != null && types == null -> service.delete(existing.id)
            existing != null && types != null ->
                service.update(existing.id, ch.nokillswit.types.EntityTypesRequest(kind = kind, types = types))
            else -> {} // absent stays absent
        }
    }

    /**
     * Runs [block] with [kind]'s dictionary temporarily set to [types] (null = no dictionary
     * at all), restoring the previous list afterwards — a delete/recreate may mint a new row
     * id, so tests must not pin the seed ids.
     */
    suspend fun withKindTypes(kind: String, types: List<String>?, block: suspend () -> Unit) {
        val snapshot = current(kind)?.types
        apply(kind, types)
        try {
            block()
        } finally {
            apply(kind, snapshot)
        }
    }
}

// Bootstrap tests (and prod-mode boot tests) rotate the seed admin password in the SHARED
// container. Call this afterwards to put the V3 seed state back so later tests (and re-runs)
// see the pristine seed.
object TestSeedState {
    suspend fun restoreSeedAccounts() {
        suspendTransaction(sharedTestDatabase) {
            UserService.Users.update({ UserService.Users.email eq SEED_ADMIN_EMAIL }) {
                it[UserService.Users.passwordHash] = SEED_PASSWORD_HASH
                it[UserService.Users.markedAsDeleted] = false
                it[UserService.Users.passwordChangedAt] = 0
            }
        }
    }
}
