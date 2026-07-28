package ai.platon.pulsar.agentic.tools.experience

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Aggregated statistics computed from [TraceRecord]s.
 *
 * ExperienceStats are **continuously updated** (mutable) and stored in
 * `knowledge/experience/<domain>/`. They are keyed by `(domain, intent)`.
 *
 * Confidence is computed on-the-fly from these stats — it is NEVER stored.
 * The stats are the source of truth; confidence is derived.
 *
 * @see KnowledgeFacts for immutable verified knowledge
 * @see TraceRecord for raw execution records
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExperienceStats(
    @JsonProperty("intent") val intent: String,
    @JsonProperty("domain") val domain: String,
    @JsonProperty("url_pattern") val urlPattern: String,

    @JsonProperty("total_attempts") val totalAttempts: Int = 0,
    @JsonProperty("successes") val successes: Int = 0,
    @JsonProperty("failures") val failures: Int = 0,

    @JsonProperty("avg_duration_ms") val avgDurationMs: Double? = null,
    @JsonProperty("avg_steps") val avgSteps: Double? = null,

    @JsonProperty("selector_stats") val selectorStats: Map<String, SelectorStats> = emptyMap(),
    @JsonProperty("failure_stats") val failureStats: Map<String, Int> = emptyMap(),

    @JsonProperty("last_updated") val lastUpdated: Instant = Instant.now(),
    @JsonProperty("last_deep_learn") val lastDeepLearn: Instant? = null,
) {
    /**
     * Compute confidence from stats using Laplace smoothing + recency decay.
     *
     * Formula: confidence = α × success_ratio + (1-α) × recency_factor
     *   success_ratio = (successes + 1) / (total + 2)
     *   recency_factor = 0.5 ^ (days_since_last_updated / 60)
     *   α = 0.7
     *
     * Special: first save (total=0) → 0.50
     * Cap: 0.95, Floor: 0.05
     */
    val confidence: Double
        get() {
            val total = successes + failures
            if (total == 0) return INITIAL_CONFIDENCE

            val successRatio = (successes + 1).toDouble() / (total + 2).toDouble()
            val daysSince = (Instant.now().toEpochMilli() - lastUpdated.toEpochMilli())
                .toDouble() / (1000.0 * 60 * 60 * 24)
            val recency = Math.pow(0.5, daysSince / RECENCY_HALF_LIFE)

            val raw = ALPHA * successRatio + (1 - ALPHA) * recency
            return raw.coerceIn(FLOOR, CAP)
        }

    /**
     * Retrieval tier derived from confidence.
     *
     * Degraded by one level if [degradedByFailures] is true.
     */
    val retrievalTier: String
        get() {
            val base = when {
                confidence >= 0.85 -> "P1"
                confidence >= 0.60 -> "P2"
                confidence >= 0.40 -> "P3"
                else -> "P4"
            }
            // Degrade if any failure category forces lower tier
            if (degradedByFailures && base == "P1") return "P2"
            if (degradedByFailures && base == "P2") return "P3"
            return base
        }

    /**
     * True if any recorded failure category degrades the retrieval tier
     * (e.g., ANTI_BOT forces verify-before-replay).
     */
    val degradedByFailures: Boolean
        get() = failureStats.any { (category, count) ->
            count > 0 && try {
                FailureCategory.valueOf(category.uppercase()).degradeRetrieval
            } catch (_: Exception) { false }
        }

    /**
     * Create a new ExperienceStats from a successful [TraceRecord].
     */
    fun withSuccess(trace: TraceRecord): ExperienceStats {
        val newTotal = totalAttempts + 1
        val newSuccesses = successes + 1

        // Update selector stats from trace actions
        val newSelectorStats = selectorStats.toMutableMap()
        for (action in trace.actions) {
            val sel = action.selector ?: continue
            val existing = newSelectorStats[sel]
            newSelectorStats[sel] = if (existing != null) {
                existing.copy(
                    successes = existing.successes + 1,
                    lastSuccess = trace.timestamp,
                )
            } else {
                SelectorStats(
                    selector = sel,
                    successes = 1,
                    lastSuccess = trace.timestamp,
                )
            }
        }

        // Recalculate averages
        val newAvgDuration = if (avgDurationMs != null && trace.durationMs != null) {
            (avgDurationMs * (newTotal - 1) + trace.durationMs) / newTotal
        } else trace.durationMs?.toDouble()

        val newAvgSteps = if (avgSteps != null) {
            (avgSteps * (newTotal - 1) + trace.actions.size) / newTotal
        } else trace.actions.size.toDouble()

        return copy(
            totalAttempts = newTotal,
            successes = newSuccesses,
            avgDurationMs = newAvgDuration,
            avgSteps = newAvgSteps,
            selectorStats = newSelectorStats,
            lastUpdated = Instant.now(),
        )
    }

    /**
     * Create a new ExperienceStats from a failed [TraceRecord].
     */
    fun withFailure(trace: TraceRecord): ExperienceStats {
        val newTotal = totalAttempts + 1
        val newFailures = failures + 1

        // Update selector stats from trace actions (the ones that were attempted)
        val newSelectorStats = selectorStats.toMutableMap()
        for (action in trace.actions) {
            val sel = action.selector ?: continue
            val existing = newSelectorStats[sel]
            // If the action failed (result is error), increment failures
            val isFailed = action.result?.let {
                it.contains("error", ignoreCase = true) ||
                    it.contains("fail", ignoreCase = true) ||
                    it.contains("not found", ignoreCase = true)
            } ?: true
            if (isFailed) {
                newSelectorStats[sel] = if (existing != null) {
                    existing.copy(
                        failures = existing.failures + 1,
                        lastFailure = trace.timestamp,
                    )
                } else {
                    SelectorStats(
                        selector = sel,
                        failures = 1,
                        lastFailure = trace.timestamp,
                    )
                }
            }
        }

        // Update failure stats
        val newFailureStats = failureStats.toMutableMap()
        val category = trace.failureCategory ?: FailureCategory.UNKNOWN.name.lowercase()
        newFailureStats[category] = (newFailureStats[category] ?: 0) + 1

        return copy(
            totalAttempts = newTotal,
            failures = newFailures,
            selectorStats = newSelectorStats,
            failureStats = newFailureStats,
            lastUpdated = Instant.now(),
        )
    }

    companion object {
        const val ALPHA = 0.7
        const val CAP = 0.95
        const val FLOOR = 0.05
        const val INITIAL_CONFIDENCE = 0.50
        const val RECENCY_HALF_LIFE = 60.0

        fun create(intent: String, domain: String, urlPattern: String): ExperienceStats {
            return ExperienceStats(
                intent = intent,
                domain = domain,
                urlPattern = urlPattern,
            )
        }
    }
}

/**
 * Per-selector aggregated statistics.
 *
 * Confidence is NOT stored here — it is computed on-the-fly
 * from [successes] and [failures] using Laplace smoothing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SelectorStats(
    @JsonProperty("selector") val selector: String,
    @JsonProperty("successes") val successes: Int = 0,
    @JsonProperty("failures") val failures: Int = 0,
    @JsonProperty("avg_resolution_ms") val avgResolutionMs: Double? = null,
    @JsonProperty("last_success") val lastSuccess: Instant? = null,
    @JsonProperty("last_failure") val lastFailure: Instant? = null,
) {
    /** Laplace-smoothed stability: (successes + 1) / (total + 2). */
    val stabilityScore: Double
        get() {
            val total = successes + failures
            if (total == 0) return 0.5
            return (successes + 1).toDouble() / (total + 2).toDouble()
        }
}
