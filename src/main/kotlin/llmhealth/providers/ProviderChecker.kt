package llmhealth.providers

import io.ktor.client.*
import llmhealth.ModelInfo

/**
 * A minimal, cheap "is this model alive?" probe for one provider.
 *
 * Implementations should send the smallest possible request (1 output token)
 * and throw an exception with a descriptive message on any non-OK response.
 * Returning normally means the model responded successfully.
 */
interface ProviderChecker {
    /** Provider id as used in config.json (e.g. "openai"). */
    val provider: String

    /** Probe the model. Throws on failure. */
    suspend fun check(client: HttpClient, model: ModelInfo, apiKey: String)
}

/** Registry of built-in checkers. Add your own implementation here. */
object ProviderRegistry {
    private val checkers: Map<String, ProviderChecker> = buildMap {
        listOf(OpenAIChecker, AnthropicChecker, GoogleChecker, XaiChecker)
            .forEach { put(it.provider, it) }
        put("grok", XaiChecker)  // alias
    }

    fun forProvider(provider: String): ProviderChecker? = checkers[provider.lowercase()]
}
