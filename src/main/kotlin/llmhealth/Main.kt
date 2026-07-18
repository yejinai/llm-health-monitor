package llmhealth

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("Main")
    val config = AppConfig.load()

    val service = HealthCheckService(config)
    service.startScheduler()

    logger.info("llm-health-monitor starting on port ${config.port}")

    embeddedServer(Netty, port = config.port) {
        install(ContentNegotiation) {
            json(Json { encodeDefaults = true; prettyPrint = true })
        }
        install(CORS) { anyHost() }  // read-only public status API

        routing {
            // Dashboard
            get("/") {
                val html = object {}.javaClass.getResource("/static/index.html")?.readText()
                    ?: "<h1>llm-health-monitor</h1><p>See <a href=\"/api/health/models\">/api/health/models</a></p>"
                call.respondText(html, ContentType.Text.Html)
            }

            route("/api/health") {
                /** Latest status of every configured model. */
                get("/models") {
                    val latest = service.storage.readLatest()
                    if (latest != null) {
                        call.respond(latest)
                    } else {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("status" to "pending", "message" to "First health check has not completed yet.")
                        )
                    }
                }

                /** Recent check runs, newest first. ?limit=24 (default 24, max 1000). */
                get("/history") {
                    val limit = (call.parameters["limit"]?.toIntOrNull() ?: 24).coerceIn(1, 1000)
                    call.respond(service.storage.readHistory(limit))
                }

                /** Trigger an immediate re-check (useful for testing). */
                post("/check") {
                    call.respond(service.runHealthCheck())
                }

                /** Per-model uptime %% over the recent history window. ?runs=100 */
                get("/uptime") {
                    val runs = (call.parameters["runs"]?.toIntOrNull() ?: config.uptimeWindowRuns)
                        .coerceIn(1, config.historyMaxRuns)
                    val history = service.storage.readHistory(runs)
                    call.respond(Stats.uptime(history, config.models))
                }

                /** Shields.io endpoint badge — embed live status in any README. */
                get("/badge") {
                    call.respondText(
                        Stats.badgeJson(service.storage.readLatest()),
                        ContentType.Application.Json
                    )
                }

                /**
                 * Observed reality vs. official status pages — flags "hidden degradation"
                 * the provider hasn't publicly acknowledged.
                 */
                get("/insights") {
                    val latest = service.storage.readLatest()
                    val history = service.storage.readHistory(config.uptimeWindowRuns)
                    call.respond(Stats.insights(latest, history, config.models, config.degradedLatencyRatio))
                }
            }

            /** Prometheus metrics (text exposition format). */
            get("/metrics") {
                val latest = service.storage.readLatest()
                val history = service.storage.readHistory(config.uptimeWindowRuns)
                val insights = Stats.insights(latest, history, config.models, config.degradedLatencyRatio)
                call.respondText(
                    Stats.prometheus(latest) + Stats.prometheusInsights(insights),
                    ContentType.Text.Plain
                )
            }
        }
    }.start(wait = true)
}
