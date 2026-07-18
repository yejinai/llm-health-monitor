package llmhealth.providers

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import llmhealth.ModelInfo

object XaiChecker : ProviderChecker {
    override val provider = "xai"

    override suspend fun check(client: HttpClient, model: ModelInfo, apiKey: String) {
        val requestBody = buildJsonObject {
            put("model", model.name)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "hi")
                }
            }
            put("max_tokens", 1)
        }

        val response = client.post("https://api.x.ai/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(requestBody.toString())
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("xAI API: ${response.status} — ${response.bodyAsText().take(200)}")
        }
    }
}
