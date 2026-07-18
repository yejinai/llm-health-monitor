package llmhealth

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Fires a webhook when a model's availability changes between runs
 * (up → down, or down → up). Payload carries both `text` (Slack) and
 * `content` (Discord) fields plus a structured `events` array, so the
 * same URL works for Slack incoming webhooks, Discord webhooks, and
 * any generic JSON consumer.
 */
class WebhookNotifier(private val client: HttpClient, private val webhookUrl: String) {
    private val logger = LoggerFactory.getLogger(WebhookNotifier::class.java)

    private fun isUp(status: String) = status == "ok" || status == "slow"

    suspend fun notifyTransitions(previous: HealthCheckResponse?, current: HealthCheckResponse) {
        if (previous == null) return  // first run — nothing to compare against

        val prevByModel = previous.models.associateBy { it.provider + "/" + it.modelName }
        val events = current.models.mapNotNull { now ->
            val before = prevByModel[now.provider + "/" + now.modelName] ?: return@mapNotNull null
            if (before.status == "skipped" || now.status == "skipped") return@mapNotNull null
            when {
                isUp(before.status) && !isUp(now.status) -> Triple("down", before, now)
                !isUp(before.status) && isUp(now.status) -> Triple("recovered", before, now)
                else -> null
            }
        }
        if (events.isEmpty()) return

        val lines = events.map { (kind, before, now) ->
            val icon = if (kind == "down") "🚨" else "✅"
            val detail = now.errorMessage?.let { " — ${it.take(120)}" } ?: ""
            "$icon ${now.modelName} (${now.provider}): ${before.status} → ${now.status}$detail"
        }
        val text = "LLM Health Monitor\n" + lines.joinToString("\n")

        val payload = buildJsonObject {
            put("text", text)      // Slack
            put("content", text)   // Discord
            putJsonArray("events") {
                events.forEach { (kind, before, now) ->
                    addJsonObject {
                        put("event", kind)
                        put("model", now.modelName)
                        put("provider", now.provider)
                        put("from", before.status)
                        put("to", now.status)
                        now.latencyMs?.let { put("latency_ms", it) }
                        now.errorMessage?.let { put("error_message", it) }
                        put("checked_at", now.checkedAt)
                    }
                }
            }
        }

        try {
            client.post(webhookUrl) {
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            logger.info("Webhook sent: ${events.size} transition(s)")
        } catch (e: Exception) {
            logger.warn("Webhook delivery failed (monitoring continues): ${e.message}")
        }
    }
}
