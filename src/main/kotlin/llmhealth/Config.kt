package llmhealth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Application configuration, loaded from config.json in the working directory.
 * API keys are NEVER stored here — they come from environment variables only:
 *   OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, XAI_API_KEY
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

/** Environment-variable API keys. Missing keys mean the provider's models are 'skipped'. */
object ApiKeys {
    val openai: String? = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
    val anthropic: String? = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
    val gemini: String? = System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
    val xai: String? = System.getenv("XAI_API_KEY")?.takeIf { it.isNotBlank() }

    fun forProvider(provider: String): String? = when (provider.lowercase()) {
        "openai" -> openai
        "anthropic" -> anthropic
        "google" -> gemini
        "xai", "grok" -> xai
        else -> null
    }
}
