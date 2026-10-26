package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `telemetry/` is the TOP layer, an observer of every lower one: it reaches `plugins/` and `infra/db` through attribute keys, so
 * nothing outside it may name it (the one exception is `application.yaml`'s module line, which is not a source import). The scan
 * must find the package, or an empty result would prove nothing. Package map: `.claude/docs/package-layout.md`.
 */
class TelemetryLayeringTest {
    private val mainSources: List<File> =
        File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun packageOf(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()

    @Test
    fun `no main source outside telemetry references the telemetry package`() {
        assertTrue(
            mainSources.any { packageOf(it.readText()) == "ch.nokillswit.telemetry" },
            "no source declares ch.nokillswit.telemetry",
        )
        val violations = mainSources.flatMap { file ->
            val text = file.readText()
            if (packageOf(text) == "ch.nokillswit.telemetry") {
                emptyList()
            } else {
                text.lineSequence().filter { it.contains("ch.nokillswit.telemetry") }.map { "${file.name}: ${it.trim()}" }.toList()
            }
        }
        assertEquals(emptyList(), violations)
    }
}
