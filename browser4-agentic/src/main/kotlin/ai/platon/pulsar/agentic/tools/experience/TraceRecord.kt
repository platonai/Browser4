package ai.platon.pulsar.agentic.tools.experience

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Raw execution record — exactly what happened during a task.
 *
 * TraceRecords are **immutable after write** and stored in `knowledge/.traces/<domain>/`.
 * They have a 30-day TTL and are never used directly for replay.
 *
 * Statistics are derived from TraceRecords and stored in [ExperienceStats].
 * Verified facts are synthesized from successful traces and stored in [KnowledgeFacts].
 *
 * @see ExperienceStats for aggregated statistics
 * @see KnowledgeFacts for verified immutable knowledge
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TraceRecord(
    @JsonProperty("trace_id") val traceId: String = UUID.randomUUID().toString(),
    @JsonProperty("intent") val intent: String,
    @JsonProperty("task_type") val taskType: String? = null,
    @JsonProperty("domain") val domain: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("url_pattern") val urlPattern: String,
    @JsonProperty("outcome") val outcome: String = "success",
    @JsonProperty("failure_category") val failureCategory: String? = null,
    @JsonProperty("actions") val actions: List<ActionStep> = emptyList(),
    @JsonProperty("final_state") val finalState: PageState? = null,
    @JsonProperty("timestamp") val timestamp: Instant = Instant.now(),
    @JsonProperty("duration_ms") val durationMs: Long? = null,
    @JsonProperty("error_message") val errorMessage: String? = null,
    @JsonProperty("redacted") val redacted: Boolean = true,
)

/**
 * Snapshot of the page state at task completion.
 *
 * Only structural information is stored — user data is redacted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PageState(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("wpsi_summary") val wpsiSummary: String? = null,
    @JsonProperty("inspect_output") val inspectOutput: String? = null,
)
