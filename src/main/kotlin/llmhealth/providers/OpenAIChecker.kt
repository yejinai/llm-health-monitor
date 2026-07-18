package llmhealth.providers

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import llmhealth.ModelInfo

object OpenAIChecker : ProviderChecker {
    override val provider = "openai"

    override suspend fun check(client: HttpClient, model: ModelInfo, apiKey: String) {
        // Image models: existence check via the models endpoint (no generation cost)
        if (model.type == "image") {
            val response = client.get("https://api.openai.com/v1/models/${model.name}") {
                header("Authorization", "Bearer $apiKey")
            }
            if (response.status != HttpStatusCode.OK) {
                throw Exception("OpenAI models API: ${response.status}")
            }
            return
        }

        // Reasoning models (o-series) require max_completion_tokens instead of max_tokens
        val isReasoningModel = model.name.startsWith("o")
        val requestBody = buildJsonObject {
            put("model", model.name)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "hi")
                }
            }
            if (isReasoningModel) {
                put("max_completion_tokens", 16)
            } else {
                put("max_tokens", 1)
            }
        }

        val response = client.post("https://api.openai.com/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(requestBody.toString())
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("OpenAI Chat API: ${response.status} — ${response.bodyAsText().take(200)}")
        }
    }
}
