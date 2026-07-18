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
