package llmhealth.providers

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import llmhealth.ModelInfo

object GoogleChecker : ProviderChecker {
    override val provider = "google"

    override suspend fun check(client: HttpClient, model: ModelInfo, apiKey: String) {
        val requestBody = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", "hi") }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", 1)
            }
        }

        val response = client.post(
            "https://generativelanguage.googleapis.com/v1beta/models/${model.name}:generateContent"
        ) {
            contentType(ContentType.Application.Json)
            header("x-goog-api-key", apiKey)
            setBody(requestBody.toString())
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Gemini API: ${response.status} — ${response.bodyAsText().take(200)}")
        }
    }
}
