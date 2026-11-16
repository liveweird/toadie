package ch.nokillswit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The construction-site pin behind `.claude/docs/persistence.md` "Entity mutations and product
 * history": every write to the `entities` table happens at one of exactly FOUR sites, and each of
 * them mints its history event in the same transaction. A fifth writer fails this test until it is
 * either routed through one of the four or added here AFTER it mints its own event (and its
 * rollback regression lands in `EntityAtomicHistoryTest`). The `SsoConfigTest` source-grep idiom,
 * hardened: the scan reads each file as ONE whitespace-joined string (a write split across lines
 * is still seen), recognises every receiver that names the table — `Entities`, `EntityService.Entities`,
 * any `typealias`/`val`/import alias of it and any other Exposed object declared over `"entities"` —
 * and every write-shaped verb. [writeSites] is itself tested on synthetic sources, so the pin is
 * proven to discriminate rather than merely to pass.
 */
class EntityWriteSitesTest {

    private val root = Path.of("src/main/kotlin")

    private val tableAlias = Regex(
        """(?:typealias|val|var)\s+(\w+)\s*(?::\s*[\w.]+\s*)?=\s*(?:[\w.]*\.)?EntityService\.Entities\b""",
    )
    private val importAlias = Regex("""import\s+[\w.]*EntityService\.Entities\s+as\s+(\w+)""")
    private val tableObject = Regex("""object\s+(\w+)\s*:\s*\w*Table\(\s*"entities"""")
    private val rawSql = Regex("""(?i)\b(?:insert\s+into|update|delete\s+from)\s+entities\b""")
    // `update` is exact (+ `Returning`): a bare `update\w*` would also swallow the `updatedAt` column.
    private val writeVerb = """(?:insert\w*|batchInsert\w*|upsert\w*|batchUpsert\w*|replace\w*|update(?:Returning)?|delete\w*)"""

    /** Block comments opening a line (KDoc and `/* */` prose) and `//` comments: prose that names a call is not a call. */
    private fun withoutComments(source: String): String = source
        .replace(Regex("""(?m)^\s*/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("""(?m)(?:^|\s)//.*$"""), " ")

    /** Every write against the `entities` table in [source], as a normalized `receiver.verb` / raw-SQL snippet. */
    private fun writeSites(source: String): List<String> {
        val code = withoutComments(source).replace(Regex("""\s+"""), " ")
        val receivers = buildSet {
            add("Entities")
            add("EntityService.Entities")
            tableAlias.findAll(code).forEach { add(it.groupValues[1]) }
            importAlias.findAll(code).forEach { add(it.groupValues[1]) }
            tableObject.findAll(code).forEach { add(it.groupValues[1]) }
        }
        val receiver = receivers.joinToString("|") { Regex.escape(it) }
        // `Receiver.verb(...)` — also a function taking the table first (`insertRows(Entities, …)`).
        val member = Regex("""(?<![\w.])($receiver)\s*\.\s*($writeVerb)\b""")
        val argument = Regex("""\b($writeVerb)\s*\(\s*($receiver)\b""")
        return buildList {
            member.findAll(code).forEach { add("${it.groupValues[1]}.${it.groupValues[2]}") }
            argument.findAll(code).forEach { add("${it.groupValues[1]}(${it.groupValues[2]}") }
            rawSql.findAll(code).forEach { add(it.value.lowercase()) }
        }
    }

    private fun kotlinSources(): List<Path> = Files.walk(root).use { stream -> stream.filter { it.name.endsWith(".kt") }.toList() }

    private fun relative(path: Path) = root.relativize(path).invariantSeparatorsPathString

    @Test
    fun `the entities table is written at exactly four sites`() {
        val sites = kotlinSources().flatMap { path -> writeSites(path.readText()).map { relative(path) to it } }
        assertEquals(
            listOf(
                // insertRow: the create (and every import-created row)
                "entities/EntityService.kt" to "Entities.insert",
                // cascadeRename: a referrer rewritten by a rename
                "entities/EntityService.kt" to "Entities.update",
                // delete: the soft delete
                "entities/EntityService.kt" to "Entities.update",
                // replaceRow: update / sync / import replace / pass two
                "entities/EntitySync.kt" to "EntityRows.update",
            ).sortedBy { it.first + it.second },
            sites.sortedBy { it.first + it.second },
            "a new writer of `entities` must mint a history event in its own transaction (persistence.md)",
        )
    }

    @Test
    fun `the replace write is reached from applyUpdate only`() {
        val callers = Files.walk(root.resolve("entities")).use { stream ->
            stream.filter { it.name.endsWith(".kt") }.toList()
        }.flatMap { path ->
            Regex("""\breplaceRow\s*\(""").findAll(withoutComments(path.readText())).map { relative(path) }.toList()
        }
        // The declaration (EntitySync.kt) and the ONE call, inside EntityService.applyUpdate — which mints the event right after it.
        assertEquals(listOf("entities/EntityService.kt", "entities/EntitySync.kt"), callers.sorted())
        val service = root.resolve("entities/EntityService.kt").readText()
        val applyUpdate = service.substringAfter("internal suspend fun applyUpdate(").substringBefore("private suspend fun cascadeRename(")
        assertTrue("replaceRow(" in applyUpdate && "replaceEvent(" in applyUpdate, "the one replaceRow call mints through replaceEvent")
    }

    @Test
    fun `each write site mints its history event in the write transaction`() {
        val service = root.resolve("entities/EntityService.kt").readText()
        // create, the replace (through replaceEvent), the cascade rewrite and the soft delete: one record call each.
        assertEquals(4, Regex("""eventService\.recordInTransaction\(""").findAll(service).count())
        assertTrue(service.contains("entityCreationEvent("), "insert mints CREATED")
        assertTrue(service.contains("replaceEvent("), "the replace mints UPDATED/SYNCED")
        assertTrue(service.contains("cascadeEvent("), "the cascade rewrite mints one UPDATED per referrer")
        assertTrue(service.contains("entityDeletionEvent()"), "delete mints DELETED")
    }

    // ---- the scanner itself: it must SEE the evasions, and ignore prose ----------------------------------------------------

    @Test
    fun `the scanner sees a write split across lines, behind an alias and through other receivers`() {
        assertEquals(listOf("Entities.update"), writeSites("Entities\n    .update({ x }) { }"))
        assertEquals(listOf("EntityService.Entities.insertAndGetId"), writeSites("EntityService.Entities . insertAndGetId { }"))
        assertEquals(listOf("Rows.deleteWhere"), writeSites("private typealias Rows = EntityService.Entities\nRows.deleteWhere { x }"))
        assertEquals(listOf("Alias.upsert"), writeSites("import ch.nokillswit.entities.EntityService.Entities as Alias\nAlias.upsert { }"))
        assertEquals(listOf("Mirror.update"), writeSites("val Mirror = EntityService.Entities\nMirror.update { }"))
        assertEquals(
            listOf("ReadEntities.insertReturning"),
            writeSites("object ReadEntities : UIntIdTable(\"entities\") { }\nReadEntities.insertReturning { }"),
        )
        assertEquals(listOf("Entities.insertRowsIgnoringConflicts"), writeSites("Entities.insertRowsIgnoringConflicts(rows) { }"))
        assertEquals(listOf("batchInsert(Entities"), writeSites("batchInsert(Entities, rows)"))
        assertEquals(listOf("update entities"), writeSites("""val sql = "UPDATE   entities SET x = 1""""))
    }

    @Test
    fun `the scanner ignores reads, comments, other tables and look-alike names`() {
        assertEquals(
            emptyList(),
            writeSites(
                "Entities.selectAll().where { active() }\nEntities.select(Entities.id, Entities.updatedAt).count()\nit[Entities.updatedAt]",
            ),
        )
        val prose = "/**\n * Entities.update is the write\n */\n// Entities.insert { } elsewhere\nval x = 1 // Entities.delete"
        assertEquals(emptyList(), writeSites(prose))
        assertEquals(emptyList(), writeSites("ReadEntities.update { }\nBlueprints.update { }\nMyEntities.insert { }"))
        assertEquals(emptyList(), writeSites("val Other = Something.Else\nOther.update { }"))
    }
}
