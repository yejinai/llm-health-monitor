package llmhealth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Application configuration, loaded from config.json in the working directory.
 * API keys are NEVER stored here — they are managed by KeyStore
 * (dashboard UI → data/keys.json, or environment variables).
 */
@Serializable
data class AppConfig(
    val port: Int = 8080,
    val checkIntervalMinutes: Int = 60,
    val slowThresholdMs: Int = 15_000,
    val requestTimeoutMs: Long = 30_000,
    val dataDir: String = "data",
    val historyMaxRuns: Int = 1000,
    /** Optional webhook (Slack/Discord/generic) notified when a model goes down or recovers. */
    val webhookUrl: String? = null,
    /** How many recent runs the uptime percentage is computed over. */
    val uptimeWindowRuns: Int = 100,
    /** Latency this many times above a model's own median baseline counts as degraded. */
    val degradedLatencyRatio: Double = 2.0,
    /** Consecutive failed runs required before hidden degradation is flagged (1 = immediate). */
    val confirmationRuns: Int = 1,
    /**
     * Official status pages per provider (Atlassian Statuspage JSON endpoints).
     * Compared against our observed probes to detect unreported degradation.
     * Set a provider to null / remove it to skip.
     */
    val officialStatusUrls: Map<String, String> = mapOf(
        "openai" to "https://status.openai.com/api/v2/status.json",
        "anthropic" to "https://status.anthropic.com/api/v2/status.json"
    ),
    val models: List<ModelInfo> = emptyList()
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun load(path: String = "config.json"): AppConfig {
            val file = File(path)
            require(file.exists()) {
                "config.json not found at ${file.absolutePath}. " +
                    "Copy config.example.json to config.json and adjust it."
            }
            val config = json.decodeFromString<AppConfig>(file.readText())
            require(config.models.isNotEmpty()) { "config.json must declare at least one model." }
            return config
        }
    }
}

// API keys are resolved by KeyStore (dashboard-entered keys.json > environment variables).
// See KeyStore.kt.
