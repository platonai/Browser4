package ai.platon.pulsar.agentic.tools.experience

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

// =============================================================================
// Task Classification
// =============================================================================

enum class TaskType(val displayName: String) {
    @JsonProperty("navigate") NAVIGATE("navigate"),
    @JsonProperty("search") SEARCH("search"),
    @JsonProperty("extract_product_list") EXTRACT_PRODUCT_LIST("extract_product_list"),
    @JsonProperty("extract_product_detail") EXTRACT_PRODUCT_DETAIL("extract_product_detail"),
    @JsonProperty("extract_article") EXTRACT_ARTICLE("extract_article"),
    @JsonProperty("add_to_cart") ADD_TO_CART("add_to_cart"),
    @JsonProperty("checkout") CHECKOUT("checkout"),
    @JsonProperty("fill_form") FILL_FORM("fill_form"),
    @JsonProperty("login") LOGIN("login"),
    @JsonProperty("extract_table") EXTRACT_TABLE("extract_table"),
    @JsonProperty("download_file") DOWNLOAD_FILE("download_file"),
    @JsonProperty("monitor_change") MONITOR_CHANGE("monitor_change");

    companion object {
        fun fromString(value: String): TaskType {
            return entries.find { it.name.equals(value, ignoreCase = true) || it.displayName == value }
                ?: throw IllegalArgumentException("Unknown task type: $value")
        }
        fun fromStringOrNull(value: String): TaskType? {
            return entries.find { it.name.equals(value, ignoreCase = true) || it.displayName == value }
        }
    }
}

// =============================================================================
// Success Criteria
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SuccessCriteria(
    @JsonProperty("type") val type: String,
    @JsonProperty("value") val value: String? = null,
    @JsonProperty("field") val field: String? = null,
) {
    companion object {
        val DEFAULTS: Map<TaskType, List<SuccessCriteria>> = mapOf(
            TaskType.EXTRACT_PRODUCT_LIST to listOf(
                SuccessCriteria("field_not_null", field = "title"),
                SuccessCriteria("row_count_gt", value = "0")
            ),
            TaskType.EXTRACT_PRODUCT_DETAIL to listOf(
                SuccessCriteria("field_not_null", field = "title")
            ),
            TaskType.EXTRACT_ARTICLE to listOf(
                SuccessCriteria("field_not_null", field = "title"),
                SuccessCriteria("field_not_null", field = "body")
            ),
            TaskType.SEARCH to listOf(SuccessCriteria("row_count_gt", value = "0")),
            TaskType.ADD_TO_CART to listOf(
                SuccessCriteria("selector_visible", value = "#confirm,.success, [data-cart]")
            ),
            TaskType.FILL_FORM to listOf(SuccessCriteria("url_pattern", value = "changed")),
            TaskType.LOGIN to listOf(SuccessCriteria("url_pattern", value = "changed")),
            TaskType.CHECKOUT to listOf(SuccessCriteria("url_pattern", field = "/order/confirmation")),
            TaskType.EXTRACT_TABLE to listOf(
                SuccessCriteria("row_count_gt", value = "0"),
                SuccessCriteria("field_not_null", field = "col_0")
            ),
            TaskType.NAVIGATE to listOf(SuccessCriteria("url_pattern")),
            TaskType.DOWNLOAD_FILE to listOf(SuccessCriteria("field_not_null", field = "file_size")),
            TaskType.MONITOR_CHANGE to listOf(SuccessCriteria("field_not_null", field = "changed_value")),
        )
    }
}

// =============================================================================
// Selector Entry
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SelectorEntry(
    @JsonProperty("selector") val selector: String,
    @JsonProperty("source") val source: String = "css",
    @JsonProperty("coverage") val coverage: Double? = null,
    @JsonProperty("stability_score") val stabilityScore: Double? = null,
    @JsonProperty("successes") val successes: Int = 0,
    @JsonProperty("attempts") val attempts: Int = 0,
    @JsonProperty("last_success") val lastSuccess: Instant? = null,
    @JsonProperty("last_failure") val lastFailure: Instant? = null,
    @JsonProperty("note") val note: String? = null,
)

// =============================================================================
// Action Step
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ActionStep(
    @JsonProperty("sequence") val sequence: Int,
    @JsonProperty("action") val action: String,
    @JsonProperty("selector") val selector: String? = null,
    @JsonProperty("value") val value: String? = null,
    @JsonProperty("result") val result: String? = null,
    @JsonProperty("duration_ms") val durationMs: Long? = null,
    @JsonProperty("timestamp") val timestamp: Instant = Instant.now(),
)

// =============================================================================
// Execution Trace (input to experience_save)
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExecutionTrace(
    @JsonProperty("task_type") @JsonAlias("taskType") val taskType: String? = null,
    @JsonProperty("intent") val intent: String? = null,
    @JsonProperty("url") val url: String,
    @JsonProperty("steps") val steps: List<ActionStep> = emptyList(),
    @JsonProperty("outcome") val outcome: String = "success",
    @JsonProperty("timestamp") val timestamp: Instant = Instant.now(),
    @JsonProperty("extraction_results") @JsonAlias("extractionResults") val extractionResults: Map<String, Any?>? = null,
    @JsonProperty("final_page_url") @JsonAlias("finalPageUrl") val finalPageUrl: String? = null,
    @JsonProperty("final_page_title") @JsonAlias("finalPageTitle") val finalPageTitle: String? = null,
    @JsonProperty("wpsi_summary") @JsonAlias("wpsiSummary") val wpsiSummary: String? = null,
    @JsonProperty("inspect_output") @JsonAlias("inspectOutput") val inspectOutput: String? = null,
    @JsonProperty("error_message") @JsonAlias("errorMessage") val errorMessage: String? = null,
    @JsonProperty("duration_ms") @JsonAlias("durationMs") val durationMs: Long? = null,
)

// =============================================================================
// Query Result
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExperienceQueryResult(
    @JsonProperty("tier") val tier: String = "P5",
    @JsonProperty("confidence") val confidence: Double = 0.0,
    @JsonProperty("domain") val domain: String? = null,
    @JsonProperty("intent") val intent: String? = null,
    @JsonProperty("url_pattern") val urlPattern: String? = null,
    @JsonProperty("page_type") val pageType: String? = null,
    @JsonProperty("task_type") val taskType: String? = null,
    @JsonProperty("summary") val summary: String? = null,
    @JsonProperty("primary_selectors") val primarySelectors: Map<String, String>? = null,
    @JsonProperty("extraction_query") val extractionQuery: String? = null,
    @JsonProperty("known_blockers") val knownBlockers: List<BlockerInfo>? = null,
    @JsonProperty("warnings") val warnings: List<String>? = null,
    @JsonProperty("steps") val steps: List<ActionStep>? = null,
    @JsonProperty("last_verified") val lastVerified: Instant? = null,
    @JsonProperty("status") val status: VerificationStatus? = null,
)

// =============================================================================
// Save Result
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExperienceSaveResult(
    @JsonProperty("saved") val saved: Boolean = true,
    @JsonProperty("domain") val domain: String,
    @JsonProperty("intent") val intent: String? = null,
    @JsonProperty("task_type") val taskType: String? = null,
    @JsonProperty("outcome") val outcome: String = "success",
    @JsonProperty("trace_path") val tracePath: String? = null,
    @JsonProperty("confidence") val confidence: Double = 0.50,
    @JsonProperty("retrieval_tier") val retrievalTier: String = "P4",
    @JsonProperty("failure_category") @JsonInclude(JsonInclude.Include.ALWAYS) val failureCategory: String? = null,
    @JsonProperty("message") val message: String? = null,
)

// =============================================================================
// Deep Learn Result
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DeepLearnResult(
    @JsonProperty("completed") val completed: Boolean = true,
    @JsonProperty("domain") val domain: String,
    @JsonProperty("intent") val intent: String,
    @JsonProperty("status_before") @JsonInclude(JsonInclude.Include.ALWAYS) val statusBefore: VerificationStatus? = null,
    @JsonProperty("status_after") val statusAfter: VerificationStatus = VerificationStatus.HYPOTHESIS,
    @JsonProperty("promoted") val promoted: Boolean = false,
    @JsonProperty("new_confidence") val newConfidence: Double = 0.0,
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("selectors_found") val selectorsFound: Int = 0,
)

// =============================================================================
// List Result
// =============================================================================

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExperienceListResult(
    @JsonProperty("total") val total: Int = 0,
    @JsonProperty("page") val page: Int = 1,
    @JsonProperty("page_size") val pageSize: Int = 20,
    @JsonProperty("total_pages") val totalPages: Int = 0,
    @JsonProperty("entries") val entries: List<KnowledgeListEntry> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class KnowledgeListEntry(
    @JsonProperty("domain") val domain: String,
    @JsonProperty("intent") val intent: String? = null,
    @JsonProperty("site_types") val siteTypes: List<String> = emptyList(),
    @JsonProperty("page_patterns") val pagePatterns: List<String> = emptyList(),
    @JsonProperty("task_types") val taskTypes: List<String> = emptyList(),
    @JsonProperty("confidence") val confidence: Double = 0.0,
    @JsonProperty("retrieval_tier") val retrievalTier: String = "P5",
    @JsonProperty("status") val status: VerificationStatus? = null,
    @JsonProperty("last_verified") val lastVerified: Instant? = null,
    @JsonProperty("success_count") val successCount: Int = 0,
    @JsonProperty("failure_count") val failureCount: Int = 0,
)
