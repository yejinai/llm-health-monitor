package llmhealth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A model to be health-checked, as declared in config.json. */
@Serializable
data class ModelInfo(
    val name: String,
    val provider: String,      // openai | anthropic | google | xai
    val type: String = "text"  // text | image
)

/** Health status of a single model at a single check. */
@Serializable
data class ModelHealthStatus(
    @SerialName("model_name") val modelName: String,
    val provider: String,
    @SerialName("model_type") val modelType: String,
    val status: String,                                  // ok | slow | error | unavailable | skipped
    @SerialName("latency_ms") val latencyMs: Int? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("checked_at") val checkedAt: String
)

/** Aggregated counts for one check run. */
@Serializable
data class HealthSummary(
    @SerialName("total_models") val totalModels: Int,
    @SerialName("ok_count") val okCount: Int,
    @SerialName("slow_count") val slowCount: Int,
    @SerialName("error_count") val errorCount: Int,
    @SerialName("unavailable_count") val unavailableCount: Int,
    @SerialName("skipped_count") val skippedCount: Int
)

/** API response / latest.json shape. */
@Serializable
data class HealthCheckResponse(
    val status: String = "success",
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    val summary: HealthSummary,
    val models: List<ModelHealthStatus>,
    /** Official status-page state per provider, fetched alongside the probes. */
    @SerialName("official_status") val officialStatus: List<ProviderOfficialStatus>? = null
)

/** One line of history.jsonl — a full check run. */
@Serializable
data class HealthCheckRun(
    @SerialName("checked_at") val checkedAt: String,
    val summary: HealthSummary,
    val models: List<ModelHealthStatus>
)

/** A provider's OFFICIAL status-page state at check time (what the company claims). */
@Serializable
data class ProviderOfficialStatus(
    val provider: String,
    /** Statuspage indicator: none | minor | major | critical — or "unknown" if unreachable/unconfigured. */
    val indicator: String,
    val description: String? = null,
    @SerialName("fetched_at") val fetchedAt: String
)

/**
 * The headline feature: observed reality vs. the provider's official claim.
 * Catches "hidden degradation" — errors or unusual slowness on our probes while
 * the company's status page still says everything is operational.
 */
@Serializable
data class ModelInsight(
    @SerialName("model_name") val modelName: String,
    val provider: String,
    @SerialName("observed_status") val observedStatus: String,
    @SerialName("official_indicator") val officialIndicator: String,
    @SerialName("latency_ms") val latencyMs: Int? = null,
    /** Median latency of successful probes over the history window. */
    @SerialName("baseline_latency_ms") val baselineLatencyMs: Int? = null,
    /** Current latency / baseline. > 2.0 means unusually slow for THIS model. */
    @SerialName("latency_ratio") val latencyRatio: Double? = null,
    /** true = we observe a problem the official status page does not report. */
    @SerialName("hidden_degradation") val hiddenDegradation: Boolean,
    val note: String? = null
)

/** Uptime statistics for one model over the recent history window. */
@Serializable
data class ModelUptime(
    @SerialName("model_name") val modelName: String,
    val provider: String,
    /** ok+slow runs / counted runs × 100. Null when no counted samples. */
    @SerialName("uptime_percent") val uptimePercent: Double? = null,
    @SerialName("avg_latency_ms") val avgLatencyMs: Int? = null,
    /** Number of runs counted (skipped runs are excluded). */
    val samples: Int
)
