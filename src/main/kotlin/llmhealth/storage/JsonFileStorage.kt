package llmhealth.storage

import kotlinx.serialization.json.Json
import llmhealth.HealthCheckResponse
import llmhealth.HealthCheckRun
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Zero-dependency persistence using plain JSON files:
 *
 *   {dataDir}/latest.json     — the most recent full check result (overwritten each run)
 *   {dataDir}/history.jsonl   — one JSON line per check run, append-only,
 *                               trimmed to the newest `historyMaxRuns` lines
 *
 * No database required — clone, run, and the files appear.
 */
class JsonFileStorage(dataDir: String, private val historyMaxRuns: Int) {
    private val logger = LoggerFactory.getLogger(JsonFileStorage::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val dir = File(dataDir).apply { mkdirs() }
    private val latestFile = File(dir, "latest.json")
    private val historyFile = File(dir, "history.jsonl")

    @Synchronized
    fun saveRun(latest: HealthCheckResponse, run: HealthCheckRun) {
        // latest.json — atomic-ish replace via temp file
        val tmp = File(dir, "latest.json.tmp")
        tmp.writeText(prettyJson.encodeToString(HealthCheckResponse.serializer(), latest))
        if (latestFile.exists()) latestFile.delete()
        tmp.renameTo(latestFile)

        // history.jsonl — append one line, then trim if oversized
        historyFile.appendText(json.encodeToString(HealthCheckRun.serializer(), run) + "\n")
        trimHistory()
    }

    @Synchronized
    fun readLatest(): HealthCheckResponse? {
        if (!latestFile.exists()) return null
        return try {
            json.decodeFromString(HealthCheckResponse.serializer(), latestFile.readText())
        } catch (e: Exception) {
            logger.warn("Failed to parse latest.json: ${e.message}")
            null
        }
    }

    @Synchronized
    fun readHistory(limit: Int): List<HealthCheckRun> {
        if (!historyFile.exists()) return emptyList()
        return historyFile.readLines()
            .asReversed()
            .asSequence()
            .filter { it.isNotBlank() }
            .take(limit)
            .mapNotNull { line ->
                try {
                    json.decodeFromString(HealthCheckRun.serializer(), line)
                } catch (e: Exception) {
                    logger.warn("Skipping malformed history line: ${e.message}")
                    null
                }
            }
            .toList()
    }

    private fun trimHistory() {
        val lines = historyFile.readLines().filter { it.isNotBlank() }
        if (lines.size > historyMaxRuns) {
            historyFile.writeText(lines.takeLast(historyMaxRuns).joinToString("\n") + "\n")
        }
    }
}
