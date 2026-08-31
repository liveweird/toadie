package ch.nokillswit

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the Flyway checksum of every migration file. An APPLIED migration's bytes may never
 * change — comments included: Flyway validates stored checksums against the files at startup
 * (`validateOnMigrate`, the default), so any edit to an already-applied migration makes every
 * existing database refuse to boot with a checksum mismatch. This gate turns that silent
 * production-only failure into a red test: editing a listed file fails here, and the fix is
 * to revert the edit (a comment clarification belongs in `.claude/docs/persistence.md`), never
 * to update the pinned value. Adding a NEW migration adds one line — compute it with the same
 * algorithm below, or read it from `flyway_schema_history` after the first local run.
 */
class MigrationChecksumTest {
    private val pinned = mapOf(
        "V1__init.sql" to 571842097,
        "V2__create_revoked_tokens.sql" to 794001362,
        "V3__seed_admin.sql" to 1989532241,
        "V4__enable_unaccent_extension.sql" to 242547752,
        "V5__create_catalog_files.sql" to 487875292,
        "V6__widen_catalog_kinds.sql" to -2026035615,
        "V7__create_dictionary_entries.sql" to 379244060,
        "V8__seed_namespaces.sql" to 999769823,
        "V9__default_namespace_flag.sql" to 449036386,
        "V10__create_labels.sql" to 1709330181,
        "V11__create_tag_categories.sql" to -360373810,
        "V12__user_disabled_features.sql" to 2085610914,
        "V13__seed_mfa_disabled_flags.sql" to 1277248774,
        "V14__create_entity_types.sql" to -964856709,
        "V15__seed_entity_types.sql" to -1513843197,
        "V16__seed_lifecycles.sql" to -2125418287,
        "V17__create_annotation_keys.sql" to 84831346,
        "V18__add_users_language.sql" to -1958598248,
        "V19__create_graph_layouts.sql" to 2053793675,
        "V20__create_lenses.sql" to -67040045,
        "V21__catalog_file_source.sql" to -1306038796,
        "V22__seed_registries.sql" to 803114340,
        "V23__create_catalog_file_events.sql" to -1889608890,
    )

    @Test
    fun `every migration file matches its applied Flyway checksum`() {
        val dir = Path.of("src/main/resources/db/migration")
        val files = dir.listDirectoryEntries("*.sql").associate { it.name to flywayChecksum(it) }
        assertEquals(
            pinned.keys.sorted(),
            files.keys.sorted(),
            "Migration files and the pinned manifest must list the same set — add the new file's checksum here",
        )
        for ((name, expected) in pinned) {
            assertEquals(
                expected,
                files.getValue(name),
                "$name changed after being applied — revert the edit; existing databases would fail Flyway validation",
            )
        }
    }

    // Flyway's algorithm: CRC32 over each line's UTF-8 bytes (terminators excluded), BOM stripped.
    private fun flywayChecksum(file: Path): Int {
        val crc = CRC32()
        Files.readString(file).removePrefix("﻿").lineSequence().forEach { crc.update(it.toByteArray()) }
        return crc.value.toInt()
    }
}
