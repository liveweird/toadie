package ch.nokillswit

import java.io.File

/**
 * Shared file-loading helper for the numbered JSON sample sets under `sample-data/` — the
 * blueprints set (v1.23.1, [SampleBlueprintsTest]) and the entities set (v1.24.1,
 * [SampleEntitiesTest]) both read their fixtures this way. Test cwd is `server/` (the Gradle
 * test task's default working directory), so files are read via `../sample-data/<dir>`.
 */
object SampleData {
    fun numberedFiles(dir: String): List<File> =
        File("../sample-data/$dir").listFiles { f -> f.name.matches(Regex("[0-9]{2}-.*\\.json")) }
            ?.sortedBy { it.name }
            ?: error("sample-data/$dir not found relative to the test working directory")
}
