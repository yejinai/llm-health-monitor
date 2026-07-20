package llmhealth

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.*
import llmhealth.providers.ProviderRegistry
import llmhealth.storage.JsonFileStorage
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Orchestrates health checks: on server start it runs one full check immediately,
 * then repeats at the configured interval. Each run probes every configured model
 * in parallel and persists the results as JSON files.
 *
 * Status semantics:
 *   ok          — responded within slowThresholdMs
 *   slow        — responded, but slower than slowThresholdMs
 *   error       — provider returned a non-OK response
 *   unavailable — request timed out
 *   skipped     — no API key configured for the provider
 */
class HealthCheckService(private val config: AppConfig, private val keyStore: KeyStore) {
    private val logger = LoggerFactory.getLogger(HealthCheckService::class.java)
    private val dtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val storage = JsonFileStorage(config.dataDir, config.historyMaxRuns)

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMs
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = config.requestTimeoutMs
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notifier = config.webhookUrl?.let { WebhookNotifier(client, it) }
    private val officialStatusFetcher = OfficialStatusFetcher(client, config.officialStatusUrls)

    /** Start the scheduler: immediate first run, then every checkIntervalMinutes. */
    fun startScheduler() {
        scope.launch {
            logger.info("Health check scheduler started (interval: ${config.checkIntervalMinutes}m, models: ${config.models.size})")
            runHealthCheck()
            while (isActive) {
                delay(config.checkIntervalMinutes * 60_000L)
                runHealthCheck()
            }
        }
    }

    /** Run one full check across all configured models. Also callable on demand. */
    suspend fun runHealthCheck(): HealthCheckResponse {
        val startTime = System.currentTimeMillis()
        logger.info("Running health check for ${config.models.size} models...")

        val providers = config.models.map { it.provider.lowercase() }.toSet()
        var officialStatuses: List<ProviderOfficialStatus> = emptyList()
        val results = coroutineScope {
            // Official status pages are fetched IN PARALLEL with the probes so both
            // reflect the same moment — that's what makes the discrepancy meaningful.
            val officialDeferred = async(Dispatchers.IO) { officialStatusFetcher.fetchAll(providers) }
            val probes = config.models.map { model ->
                async(Dispatchers.IO) { checkModel(model) }
            }.awaitAll()
            officialStatuses = officialDeferred.await()
            probes
        }

        val now = LocalDateTime.now().format(dtFormatter)
        val summary = HealthSummary(
            totalModels = results.size,
            okCount = results.count { it.status == "ok" },
            slowCount = results.count { it.status == "slow" },
            errorCount = results.count { it.status == "error" },
            unavailableCount = results.count { it.status == "unavailable" },
            skippedCount = results.count { it.status == "skipped" },
        )
        val response = HealthCheckResponse(
            status = "success",
            lastCheckedAt = now,
            summary = summary,
            models = results,
            officialStatus = officialStatuses
        )
        val previous = storage.readLatest()
        storage.saveRun(response, HealthCheckRun(checkedAt = now, summary = summary, models = results))
        notifier?.notifyTransitions(previous, response)

        val elapsed = System.currentTimeMillis() - startTime
        logger.info(
            "Health check done in ${elapsed}ms — ok=${summary.okCount}, slow=${summary.slowCount}, " +
                "error=${summary.errorCount}, unavailable=${summary.unavailableCount}, skipped=${summary.skippedCount}"
        )
        return response
    }

    private suspend fun checkModel(model: ModelInfo): ModelHealthStatus {
        val checkedAt = LocalDateTime.now().format(dtFormatter)

        val checker = ProviderRegistry.forProvider(model.provider)
            ?: return ModelHealthStatus(
                modelName = model.name, provider = model.provider, modelType = model.type,
                status = "error", errorMessage = "Unknown provider '${model.provider}'", checkedAt = checkedAt
            )

        val apiKey = keyStore.get(model.provider)
            ?: return ModelHealthStatus(
                modelName = model.name, provider = model.provider, modelType = model.type,
                status = "skipped", errorMessage = "No API key configured", checkedAt = checkedAt
            )

        val startTime = System.currentTimeMillis()
        return try {
            checker.check(client, model, apiKey)
            val latency = (System.currentTimeMillis() - startTime).toInt()
            val status = if (latency > config.slowThresholdMs) "slow" else "ok"
            logger.debug("{}: {} ({}ms)", model.name, status, latency)
            ModelHealthStatus(
                modelName = model.name, provider = model.provider, modelType = model.type,
                status = status, latencyMs = latency, checkedAt = checkedAt
            )
        } catch (e: Exception) {
            val latency = (System.currentTimeMillis() - startTime).toInt()
            val isTimeout = e.message?.contains("timeout", ignoreCase = true) == true ||
                e.message?.contains("timed out", ignoreCase = true) == true
            val status = if (isTimeout) "unavailable" else "error"
            logger.warn("{}: {} — {}", model.name, status, e.message?.take(120))
            ModelHealthStatus(
                modelName = model.name, provider = model.provider, modelType = model.type,
                status = status, latencyMs = latency,
                errorMessage = e.message?.take(200), checkedAt = checkedAt
            )
        }
    }
}
