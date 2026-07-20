package llmhealth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/** Key status for the dashboard — the raw key NEVER leaves the server. */
@Serializable
data class KeyStatus(
    val provider: String,
    val configured: Boolean,
    /** "ui" (set via dashboard, stored in data/keys.json) | "env" | "none" */
    val source: String,
    /** Masked preview like "sk-pr…Xk4Q". Null when not configured. */
    val masked: String? = null
)

@Serializable
data class SetKeyRequest(val provider: String, val key: String)

/**
 * API key resolution with two sources, in precedence order:
 *
 *   1. Dashboard-entered keys, persisted as plain JSON in {dataDir}/keys.json
 *      (intended for LOCAL machines; the file is gitignored via /data/)
 *   2. Environment variables (OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, XAI_API_KEY)
 *      — recommended for servers/containers.
 *
 * The HTTP endpoints that manage keys are restricted to loopback connections,
 * and read endpoints only ever expose masked previews.
 */
class KeyStore(dataDir: String) {
    private val logger = LoggerFactory.getLogger(KeyStore::class.java)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(dataDir, "keys.json")

    private val uiKeys: MutableMap<String, String> = try {
        if (file.exists()) json.decodeFromString<Map<String, String>>(file.readText()).toMutableMap()
        else mutableMapOf()
    } catch (e: Exception) {
        logger.warn("Could not read ${file.path} (${e.message}) — starting with no UI keys")
        mutableMapOf()
    }

    private fun normalize(provider: String): String =
        provider.lowercase().let { if (it == "grok") "xai" else it }

    private fun envKey(provider: String): String? = when (normalize(provider)) {
        "openai" -> System.getenv("OPENAI_API_KEY")
        "anthropic" -> System.getenv("ANTHROPIC_API_KEY")
        "google" -> System.getenv("GEMINI_API_KEY")
        "xai" -> System.getenv("XAI_API_KEY")
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** Active key for a provider: dashboard key wins, env var is the fallback. */
    @Synchronized
    fun get(provider: String): String? =
        uiKeys[normalize(provider)]?.takeIf { it.isNotBlank() } ?: envKey(provider)

    @Synchronized
    fun set(provider: String, key: String) {
        uiKeys[normalize(provider)] = key.trim()
        persist()
        logger.info("API key for '${normalize(provider)}' set via dashboard")
    }

    @Synchronized
    fun remove(provider: String) {
        uiKeys.remove(normalize(provider))
        persist()
        logger.info("Dashboard API key for '${normalize(provider)}' removed")
    }

    @Synchronized
    fun status(providers: Collection<String>): List<KeyStatus> =
        providers.map { normalize(it) }.distinct().map { p ->
            val ui = uiKeys[p]?.takeIf { it.isNotBlank() }
            val env = envKey(p)
            val active = ui ?: env
            KeyStatus(
                provider = p,
                configured = active != null,
                source = if (ui != null) "ui" else if (env != null) "env" else "none",
                masked = active?.let { mask(it) }
            )
        }

    private fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(uiKeys.toMap()))
    }

    private fun mask(key: String): String =
        if (key.length >= 10) "${key.take(5)}…${key.takeLast(4)}" else "•••"
}
