package llmhealth.providers

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import llmhealth.ModelInfo

object AnthropicChecker : ProviderChecker {
    override val provider = "anthropic"

    override suspend fun check(client: HttpClient, model: ModelInfo, apiKey: String) {
        val requestBody = buildJsonObject {
            put("model", model.name)
            put("max_tokens", 1)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "hi")
                }
            }
        }

        val response = client.post("https://api.anthropic.com/v1/messages") {
            contentType(ContentType.Application.Json)
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(requestBody.toString())
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Anthropic API: ${response.status} — ${response.bodyAsText().take(200)}")
        }
    }
}
