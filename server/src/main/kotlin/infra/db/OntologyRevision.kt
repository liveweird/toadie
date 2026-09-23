package ch.nokillswit.infra.db

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update

/** The V39 single fixed row this table ever holds — never a soft-deleted/inserted second value. */
private const val ONTOLOGY_REVISION_ROW_ID: Short = 1

/**
 * The V39 monotonic ontology-revision counter (`.claude/docs/persistence.md` "V39"): ONE row,
 * bumped by every committed blueprint write, entity write, and `HIERARCHY`-dictionary replace —
 * never queried/DDL'd outside this file's helpers. Query-only (Exposed table defs are never DDL).
 */
object OntologyRevisions : Table("ontology_revision") {
    val id = short("id")
    val revision = long("revision")
    override val primaryKey = PrimaryKey(id)
}

/**
 * Bumps the counter on the CALLER's CURRENT transaction — call this from inside the same
 * `lockingTransaction`/`writeTransaction` block as the row write it accompanies, AFTER that write
 * succeeds. **Needs no lock of its own**: every ontology writer already serializes through the
 * `blueprints` (V27) or `blueprints`-then-`entities` (V28) table lock before it reaches this call
 * (`blueprints/BlueprintService.kt`'s `writeTransaction`, `entities/EntityService.kt`'s
 * `writeTransaction`, and `dictionaries/DictionaryService.kt`'s `HIERARCHY`-only lock), so two
 * concurrent bumps can never race each other — the `UPDATE ... SET revision = revision + 1`
 * below only ever runs one at a time, in the order the callers' own locks already impose.
 *
 * **A blueprint/entity PUT always bumps, even a byte-identical resubmission**: `updatedAt` bumps
 * on every PUT regardless of whether the document changed (`.claude/docs/persistence.md`'s "D2"
 * rule for both `entities`/V37 and `blueprints`/V38), so this counter follows the SAME posture —
 * it answers "did an ontology WRITE commit", not "did the ontology CHANGE". A rejected write
 * (400/409) never reaches this call: either the transaction never got this far, or it rolls back
 * and takes this bump with it.
 */
suspend fun bumpOntologyRevision() {
    OntologyRevisions.update({ OntologyRevisions.id eq ONTOLOGY_REVISION_ROW_ID }) {
        it[revision] = revision + 1
    }
}

/**
 * A scalar subquery expression — `(SELECT revision FROM ontology_revision WHERE id = 1)` — usable
 * as an EXTRA column alongside a row SELECT's own columns, so a single statement returns the page
 * rows and the revision they were read against together. [ontologyReadTransaction] extends that
 * consistency across the list operation's other statements (count, definitions, and target
 * reads), including [currentOntologyRevision]'s zero-row fallback. `null` only if the one seeded
 * row were ever missing — never expected outside a corrupted database.
 */
fun ontologyRevisionExpression(): Expression<Long?> = wrapAsExpression(
    OntologyRevisions.select(OntologyRevisions.revision).where { OntologyRevisions.id eq ONTOLOGY_REVISION_ROW_ID },
)

/**
 * A plain, direct read of the counter — the fallback for a page with ZERO rows (there are no rows
 * for [ontologyRevisionExpression] to ride along with) and the seam `OntologyRevisionTest` reads
 * through directly. Must run inside the caller's own transaction, exactly like every other query
 * helper in this package; multi-statement ontology lists use [ontologyReadTransaction] so this
 * fallback shares their snapshot.
 */
suspend fun currentOntologyRevision(): Long =
    OntologyRevisions.selectAll()
        .where { OntologyRevisions.id eq ONTOLOGY_REVISION_ROW_ID }
        .map { it[OntologyRevisions.revision] }
        .toList()
        .single()
