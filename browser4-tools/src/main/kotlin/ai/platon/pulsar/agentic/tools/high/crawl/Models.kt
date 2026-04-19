package ai.platon.pulsar.agentic.tools.high.crawl

import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.crawl.ServerSideEventHandlers
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.*

data class ScrapeRequest(
    var sql: String,
)

data class ScrapeResponse(
    var id: String? = null,
    var statusCode: Int = ResourceStatus.SC_CREATED,
    var pageStatusCode: Int = ProtocolStatusCodes.SC_CREATED,
    var pageContentBytes: Int = 0,
    var isDone: Boolean = false,
    var resultSet: List<Map<String, Any?>>? = null,

    var event: String = "",
) {
    val status: String get() = ResourceStatus.getStatusText(statusCode)
    var lastModifiedTime: Instant? = null
    var finishTime: Instant? = null

    companion object {
        fun notFound(id: String) = ScrapeResponse(id, ResourceStatus.SC_NOT_FOUND, ResourceStatus.SC_NOT_FOUND)
        fun failed(id: String, statusCode: Int, pageStatusCode: Int) =
            ScrapeResponse(id, statusCode = statusCode, pageStatusCode = pageStatusCode)

        fun failed(id: String, e: Exception) =
            ScrapeResponse(
                id,
                statusCode = ResourceStatus.SC_EXPECTATION_FAILED,
                pageStatusCode = ResourceStatus.SC_EXPECTATION_FAILED
            )
    }
}

fun ScrapeResponse.refresh(isDone: Boolean = false) {
    lastModifiedTime = Instant.now()
    this.isDone = isDone
}

fun ScrapeResponse.refresh(statusCode: Int) = refresh(statusCode, this.pageStatusCode, false)

fun ScrapeResponse.refresh(statusCode: Int, pageStatusCode: Int, isDone: Boolean) {
    lastModifiedTime = Instant.now()
    this.statusCode = statusCode
    this.pageStatusCode = pageStatusCode
    this.isDone = isDone
}

fun ScrapeResponse.failed(statusCode: Int): ScrapeResponse {
    // do not change pageStatusCode
    refresh(statusCode, pageStatusCode, isDone = true)
    return this
}

fun ScrapeResponse.emitEvent(event: String) {
    this.event = event
    this.lastModifiedTime = Instant.now()
}

fun ScrapeResponse.failed(statusCode: Int, pageStatusCode: Int): ScrapeResponse {
    refresh(statusCode, pageStatusCode, isDone = true)
    return this
}

fun ScrapeResponse.done() {
    refresh(isDone = true)
    finishTime = Instant.now()
}

fun ScrapeResponse.refreshed(lastModifiedTime: Instant): Boolean {
    val time = this.lastModifiedTime ?: return false
    return time > lastModifiedTime
}

/**
 * Request for web page interactions with structured data extraction capabilities.
 *
 * @property url The target page URL to process.
 * @property args Optional load arguments to customize page loading behavior.
 * @property onBrowserLaunchedActions Actions to perform when the browser is launched (e.g., "clearBrowserCookies", "navigate").
 * @property onPageReadyActions Actions to perform when the document is fully loaded (e.g., "scroll down", "click button").
 * @property pageSummaryPrompt A prompt to analyze or discuss the HTML structure of the page.
 * @property dataExtractionRules Specifications for extracting structured fields from the HTML content.
 * @property uriExtractionRules A regex pattern to extract specific URIs from the page, e.g. "links containing /dp/".
 * @property xsql An X-SQL query for structured data extraction, e.g. "select dom_first_text(dom, '#title') as title, llm_extract(dom, 'price') as price".
 * @property richText Whether to retain rich text formatting in the extracted content.
 * @property async If true, the command is executed asynchronous; otherwise, it's synchronously.
 */
data class PageVisitRequest @JsonCreator constructor(
    @param:JsonProperty("url") var url: String,
    @param:JsonProperty("args") var args: String? = null,
    @param:JsonProperty("onBrowserLaunchedActions") var onBrowserLaunchedActions: List<String>? = null,
    @param:JsonProperty("onPageReadyActions") var onPageReadyActions: List<String>? = null,
    @param:JsonProperty("actions") var actions: List<String>? = null,
    @param:JsonProperty("pageSummaryPrompt") var pageSummaryPrompt: String? = null,
    @param:JsonProperty("dataExtractionRules") var dataExtractionRules: String? = null,
    @param:JsonProperty("uriExtractionRules") var uriExtractionRules: String? = null,
    /**
     * Whether to infer/convert [uriExtractionRules] from natural language into a `Regex:` pattern using LLM.
     *
     * Some LLMs are very slow or unstable when asked to produce regex; set this set to be false to require callers
     * to provide a `Regex:` pattern directly.
     *
     * Defaults to true for backward compatibility.
     */
    @param:JsonProperty("inferUriExtractionRegex") var inferUriExtractionRegex: Boolean? = null,
    @param:JsonProperty("xsql") var xsql: String? = null,
    @param:JsonProperty("richText") var richText: Boolean? = null,
    @param:JsonProperty("async") var async: Boolean? = null,
    @param:JsonProperty("id") var id: String? = null,
) {
    fun hasAction(): Boolean {
        return !onBrowserLaunchedActions.isNullOrEmpty() || !onPageReadyActions.isNullOrEmpty()
    }

    fun isAsync(): Boolean {
        return when {
            async == true -> true
            else -> false
        }
    }

    fun enhanceArgs(): String {
        val minimalSize = 100 // minimal page size required
        val args = if (hasAction()) {
            LoadOptions.mergeArgs(this.args, "-refresh -requireSize $minimalSize")
        } else {
            LoadOptions.mergeArgs(this.args, "-requireSize $minimalSize")
        }

        this.args = args
        return args
    }
}

/**
 * Command result
 *
 * @property pageSummary The summary of the page.
 * @property fields The extracted fields from the page.
 * @property links The extracted links from the page.
 * @property xsqlResultSet The result set from the X-SQL query.
 */
data class PageVisitResult(
    var summary: String? = null,
    var pageSummary: String? = null,
//    var fields: String? = null,
//    var links: String? = null,
    var fields: Map<String, String>? = null,
    var links: List<String>? = null,
    var xsqlResultSet: List<Map<String, Any?>>? = null,
)

/**
 * Instruct result
 *
 * @property name The name of the instruction.
 * @property statusCode The status code of the instruction result.
 * @property result The result of the instruction.
 * @property resultType The json type of the result, e.g. "string", "number", "boolean", "array", "object".
 * @property instruct The instruction text.
 * */
data class PGInstructResult @JsonCreator constructor(
    @param:JsonProperty("name") var name: String,
    @param:JsonProperty("statusCode") var statusCode: Int = ResourceStatus.SC_CREATED,
    @param:JsonProperty("result") var result: Any? = null,
    @param:JsonProperty("resultType") var resultType: String? = null,
    @param:JsonProperty("instruct") var instruct: String? = null,
) {
    companion object {

        fun ok(name: String, result: Any, resultType: String = "string"): PGInstructResult {
            return PGInstructResult(name, ResourceStatus.SC_OK, result = result, resultType = resultType)
        }

        fun failed(name: String, statusCode: Int = ResourceStatus.SC_EXPECTATION_FAILED): PGInstructResult {
            return PGInstructResult(name, statusCode)
        }
    }
}

/**
 * Command status
 *
 * @property id The unique identifier for the command status.
 * @property statusCode The HTTP status code representing the command status.
 * @property event The last event associated with the command status.
 * @property isDone Indicates whether the command has been completed.
 * @property pageStatusCode The HTTP status code representing the page status.
 * @property pageContentBytes The size of the page content in bytes.
 * @property message An optional message providing additional information about the command status.
 * @property request The original command request associated with this status.
 * @property pageVisitResult The result of the command execution.
 * @property instructResults A list of results from the instructions executed during the command.
 * */
data class PageVisitStatus(
    /**
     * The unique identifier for the page visit status.
     * */
    val id: String = UUID.randomUUID().toString(),
    /**
     * The status code representing the task status.
     * */
    var statusCode: Int = ResourceStatus.SC_CREATED,
    /**
     * The last event associated with the task.
     * */
    var event: String = "",
    /**
     * The progress state of the agent task. Can be "created", "in_progress", or "done".
     * */
    var processState: String = "created", // created, in_progress, done

    var pageStatusCode: Int = ProtocolStatusCodes.SC_CREATED,
    var pageContentBytes: Int = 0,

    var message: String? = null, // additional message, e.g. the action flow

    var request: PageVisitRequest? = null,
    var pageVisitResult: PageVisitResult? = null,
    var instructResults: MutableList<PGInstructResult> = mutableListOf()
) {
    val status: String get() = ResourceStatus.getStatusText(statusCode)
    var lastModifiedTime: Instant? = null
    var finishTime: Instant? = null

    val isDone: Boolean get() = processState == "done"

    /**
     * The server-side event handlers reference for tracking server-side events during command execution.
     * This is set when executing commands and provides access to the event flow.
     * It is excluded from JSON serialization as it's only used for internal event streaming.
     */
    @get:JsonIgnore
    var serverSideEventHandlers: ServerSideEventHandlers? = null

    companion object {
        fun notFound(id: String) = PageVisitStatus(id, ResourceStatus.SC_NOT_FOUND, processState = "done")

        fun failed(id: String) = PageVisitStatus(id, ResourceStatus.SC_EXPECTATION_FAILED, processState = "done")

        fun failed(id: String, statusCode: Int, pageStatusCode: Int = statusCode) =
            PageVisitStatus(id, statusCode = statusCode, pageStatusCode = pageStatusCode, processState = "done")

        fun failed(statusCode: Int, pageStatusCode: Int = statusCode) = failed("", statusCode, pageStatusCode)

        fun failed(id: String, e: Exception): PageVisitStatus {
            return PageVisitStatus(id, statusCode = ResourceStatus.SC_EXPECTATION_FAILED, processState = "done")
        }
    }
}

fun PageVisitStatus.ensurePageVisitResult(): PageVisitResult {
    val r = pageVisitResult ?: PageVisitResult()
    pageVisitResult = r
    return r
}

fun PageVisitStatus.refresh(isDone: Boolean = false) {
    lastModifiedTime = Instant.now()
    processState = "done".takeIf { isDone } ?: "in_progress"
}

fun PageVisitStatus.refresh(statusCode: Int) = refresh(statusCode, this.pageStatusCode, false)

fun PageVisitStatus.refresh(statusCode: Int, pageStatusCode: Int, isDone: Boolean) {
    lastModifiedTime = Instant.now()
    this.statusCode = statusCode
    this.pageStatusCode = pageStatusCode
    processState = "done".takeIf { isDone } ?: "in_progress"
}

fun PageVisitStatus.failed(statusCode: Int): PageVisitStatus {
    // do not change pageStatusCode
    refresh(statusCode, pageStatusCode, isDone = true)
    return this
}

fun PageVisitStatus.emitEvent(event: String) {
    this.event = event
    message = if (message != null) "$message,$event" else event
    lastModifiedTime = Instant.now()
}

fun PageVisitStatus.failed(statusCode: Int, pageStatusCode: Int): PageVisitStatus {
    refresh(statusCode, pageStatusCode, isDone = true)
    return this
}

fun PageVisitStatus.addInstructResult(result: PGInstructResult) {
    instructResults.add(result)

    val name = result.name
    val visitResult = ensurePageVisitResult()
    when (name) {
        "pageSummary" -> {
            visitResult.pageSummary = result.result?.toString()
        }

        "fields" -> {
            @Suppress("UNCHECKED_CAST")
            visitResult.fields = result.result as? Map<String, String>?
        }

        "links" -> {
            @Suppress("UNCHECKED_CAST")
            visitResult.links = result.result as? List<String>?
        }
    }
    emitEvent(result.name)
}

fun PageVisitStatus.done() {
    refresh(isDone = true)
    finishTime = Instant.now()
}

fun PageVisitStatus.refreshed(lastModifiedTime: Instant): Boolean {
    val modifiedTime = this.lastModifiedTime ?: return false
    return modifiedTime > lastModifiedTime
}
