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
    val models: List<ModelHealthStatus>
)

/** One line of history.jsonl — a full check run. */
@Serializable
data class HealthCheckRun(
    @SerialName("checked_at") val checkedAt: String,
    val summary: HealthSummary,
    val models: List<ModelHealthStatus>
)
