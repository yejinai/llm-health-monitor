package llmhealth

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Fetches each provider's OFFICIAL status page (Atlassian Statuspage JSON format:
 * `{"status": {"indicator": "none", "description": "All Systems Operational"}}`).
 *
 * Why: official status pages are famously slow to admit incidents. By recording
 * what the company CLAIMS at the same moment we PROBE reality, the gap between
 * the two becomes measurable — that gap is the status they don't publish.
 *
 * Providers without a configured URL (or with unreachable pages) report "unknown".
 */
class OfficialStatusFetcher(
    private val client: HttpClient,
    private val urls: Map<String, String>
) {
    private val logger = LoggerFactory.getLogger(OfficialStatusFetcher::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val dtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    suspend fun fetchAll(providers: Set<String>): List<ProviderOfficialStatus> = coroutineScope {
        providers.map { provider ->
            async(Dispatchers.IO) { fetchOne(provider) }
        }.awaitAll()
    }

    private suspend fun fetchOne(provider: String): ProviderOfficialStatus {
        val now = LocalDateTime.now().format(dtFormatter)
        val url = urls[provider.lowercase()]
            ?: return ProviderOfficialStatus(provider, "unknown", "No status page configured", now)

        return try {
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) {
                throw Exception("HTTP ${response.status}")
            }
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val status = body["status"]?.jsonObject
            ProviderOfficialStatus(
                provider = provider,
                indicator = status?.get("indicator")?.jsonPrimitive?.contentOrNull ?: "unknown",
                description = status?.get("description")?.jsonPrimitive?.contentOrNull,
                fetchedAt = now
            )
        } catch (e: Exception) {
            logger.warn("Official status fetch failed for $provider: ${e.message}")
            ProviderOfficialStatus(provider, "unknown", "Status page unreachable: ${e.message?.take(80)}", now)
        }
    }
}
