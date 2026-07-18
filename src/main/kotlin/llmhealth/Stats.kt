package llmhealth

/**
 * Derived statistics computed from stored history — no extra state needed.
 * (Feature inspired by uptime percentages in Uptime Kuma / Gatus.)
 */
object Stats {

    /** Per-model uptime over the given runs (newest first). Skipped runs are not counted. */
    fun uptime(history: List<HealthCheckRun>, models: List<ModelInfo>): List<ModelUptime> {
        return models.map { model ->
            val samples = history.mapNotNull { run ->
                run.models.find { it.modelName == model.name && it.provider == model.provider }
            }.filter { it.status != "skipped" }

            val upSamples = samples.filter { it.status == "ok" || it.status == "slow" }
            val latencies = upSamples.mapNotNull { it.latencyMs }

            ModelUptime(
                modelName = model.name,
                provider = model.provider,
                uptimePercent = if (samples.isNotEmpty())
                    Math.round(upSamples.size * 1000.0 / samples.size) / 10.0 else null,
                avgLatencyMs = if (latencies.isNotEmpty()) latencies.average().toInt() else null,
                samples = samples.size
            )
        }
    }

    /**
     * Shields.io endpoint-badge JSON (https://shields.io/badges/endpoint-badge).
     * Embed in a README:  ![](https://img.shields.io/endpoint?url=https://your-host/api/health/badge)
     */
    fun badgeJson(latest: HealthCheckResponse?): String {
        if (latest == null) {
            return """{"schemaVersion":1,"label":"LLM APIs","message":"no data","color":"lightgrey"}"""
        }
        val counted = latest.models.count { it.status != "skipped" }
        val up = latest.models.count { it.status == "ok" || it.status == "slow" }
        val color = when {
            counted == 0 -> "lightgrey"
            up == counted -> "brightgreen"
            up * 100 >= counted * 75 -> "yellow"
            else -> "red"
        }
        return """{"schemaVersion":1,"label":"LLM APIs","message":"$up/$counted up","color":"$color"}"""
    }

    /**
     * The differentiator: reality vs. the provider's official claim.
     *
     * A model is flagged `hidden_degradation` when our probe observed a problem
     * (error/unavailable, or latency ≥ degradedLatencyRatio × its own median baseline)
     * while the provider's official status page reports "none" (all operational).
     * Official status pages are slow to admit incidents — this measures the gap.
     */
    fun insights(
        latest: HealthCheckResponse?,
        history: List<HealthCheckRun>,
        models: List<ModelInfo>,
        degradedLatencyRatio: Double,
        confirmationRuns: Int = 1
    ): List<ModelInsight> {
        if (latest == null) return emptyList()
        val officialByProvider = latest.officialStatus?.associateBy { it.provider.lowercase() } ?: emptyMap()

        return models.mapNotNull { model ->
            val current = latest.models.find { it.modelName == model.name && it.provider == model.provider }
                ?: return@mapNotNull null
            if (current.status == "skipped") return@mapNotNull null

            val baselineLatencies = history
                .mapNotNull { run -> run.models.find { it.modelName == model.name && it.provider == model.provider } }
                .filter { it.status == "ok" || it.status == "slow" }
                .mapNotNull { it.latencyMs }
                .sorted()
            val baseline = if (baselineLatencies.isNotEmpty()) baselineLatencies[baselineLatencies.size / 2] else null

            val ratio = if (current.latencyMs != null && baseline != null && baseline > 0)
                Math.round(current.latencyMs * 100.0 / baseline) / 100.0 else null

            val official = officialByProvider[model.provider.lowercase()]
            val officialIndicator = official?.indicator ?: "unknown"
            val officialClaimsOk = officialIndicator == "none"

            // Consecutive failed runs, newest first (history[0] is the current run)
            var consecutiveFailures = 0
            for (run in history) {
                val m = run.models.find { it.modelName == model.name && it.provider == model.provider }
                    ?: break
                if (m.status == "error" || m.status == "unavailable") consecutiveFailures++ else break
            }

            val observedHardFailure = (current.status == "error" || current.status == "unavailable") &&
                consecutiveFailures >= confirmationRuns
            val observedSlowdown = ratio != null && ratio >= degradedLatencyRatio
            val hidden = officialClaimsOk && (observedHardFailure || observedSlowdown)

            val note = when {
                hidden && observedHardFailure ->
                    "Probe failed $consecutiveFailures time(s) in a row (${current.status}) while the official status page reports all operational."
                hidden && observedSlowdown ->
                    "Latency is ${ratio}x this model's baseline while the official status page reports all operational."
                observedHardFailure && !officialClaimsOk ->
                    "Probe failed — consistent with the officially reported incident ($officialIndicator)."
                else -> null
            }

            ModelInsight(
                modelName = model.name,
                provider = model.provider,
                observedStatus = current.status,
                officialIndicator = officialIndicator,
                latencyMs = current.latencyMs,
                baselineLatencyMs = baseline,
                latencyRatio = ratio,
                consecutiveFailures = consecutiveFailures,
                hiddenDegradation = hidden,
                note = note
            )
        }
    }

    /** Prometheus metrics for hidden-degradation detection, appended to /metrics. */
    fun prometheusInsights(insights: List<ModelInsight>): String = buildString {
        appendLine("# HELP llm_health_hidden_degradation 1 when we observe a problem the provider's official status page does not report.")
        appendLine("# TYPE llm_health_hidden_degradation gauge")
        insights.forEach { i ->
            val v = if (i.hiddenDegradation) 1 else 0
            appendLine("""llm_health_hidden_degradation{model="${i.modelName}",provider="${i.provider}"} $v""")
        }
    }

    /** Prometheus text exposition format (0.0.4) rendered from the latest check. */
    fun prometheus(latest: HealthCheckResponse?): String = buildString {
        appendLine("# HELP llm_health_up 1 if the model responded (ok or slow), 0 otherwise. Skipped models are omitted.")
        appendLine("# TYPE llm_health_up gauge")
        latest?.models?.filter { it.status != "skipped" }?.forEach { m ->
            val up = if (m.status == "ok" || m.status == "slow") 1 else 0
            appendLine("""llm_health_up{model="${m.modelName}",provider="${m.provider}"} $up""")
        }
        appendLine("# HELP llm_health_latency_milliseconds Probe latency of the last check.")
        appendLine("# TYPE llm_health_latency_milliseconds gauge")
        latest?.models?.forEach { m ->
            m.latencyMs?.let {
                appendLine("""llm_health_latency_milliseconds{model="${m.modelName}",provider="${m.provider}"} $it""")
            }
        }
        latest?.summary?.let { s ->
            appendLine("# HELP llm_health_models_total Models by status in the last check.")
            appendLine("# TYPE llm_health_models_total gauge")
            appendLine("""llm_health_models_total{status="ok"} ${s.okCount}""")
            appendLine("""llm_health_models_total{status="slow"} ${s.slowCount}""")
            appendLine("""llm_health_models_total{status="error"} ${s.errorCount}""")
            appendLine("""llm_health_models_total{status="unavailable"} ${s.unavailableCount}""")
            appendLine("""llm_health_models_total{status="skipped"} ${s.skippedCount}""")
        }
    }
}
