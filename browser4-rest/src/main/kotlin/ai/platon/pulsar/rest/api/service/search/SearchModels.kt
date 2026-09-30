package ai.platon.pulsar.rest.api.service.search

import ai.platon.pulsar.common.ResourceStatus
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Wire model + reporting model of the search API.
 *
 * These types are deliberately free of search machinery: they are what a caller
 * sends, what the REST layer serializes, and what [SearchService] persists to its
 * task store.  The execution side lives in [SearchService] (task lifecycle) and
 * the configured [SearchProvider] (external API call).
 */
data class SearchRequest @JsonCreator constructor(
    @param:JsonProperty("query") val query: String = "",
    @param:JsonProperty("maxResults") val maxResults: Int = 10,
    /**
     * If true, fetch and extract content from each result URL via the browser
     * session.  Slower; one browser tab per result.  Populates
     * [SearchResult.scrapedContent].
     */
    @param:JsonProperty("scrape") val scrape: Boolean = false,
    /** Output format for scraped content: `markdown` (default) or `html`. */
    @param:JsonProperty("scrapeFormat") val scrapeFormat: String = "markdown",
    /** Search topic: `general` (default) or `news`. */
    @param:JsonProperty("topic") val topic: String = "general",
    /** Restrict to recent results: `day`, `week`, `month`, or `year`.  `null` = no restriction. */
    @param:JsonProperty("timeRange") val timeRange: String? = null,
    /** Restrict results to these domains.  `null` = no restriction. */
    @param:JsonProperty("includeDomains") val includeDomains: List<String>? = null,
    /** Exclude results from these domains.  `null` = no restriction. */
    @param:JsonProperty("excludeDomains") val excludeDomains: List<String>? = null,
    /**
     * How long this search task may run before the server cancels it (ms).
     *
     * `null` (the default) uses the server's [SearchService.DEFAULT_TASK_TIMEOUT_MS]
     * (60 seconds).  A value above zero is the per-request budget.
     */
    @param:JsonProperty("taskTimeoutMillis") val taskTimeoutMillis: Long? = null
)

/**
 * The one spelling per search task state, defined once.
 *
 * Mirrors [ai.platon.pulsar.rest.api.service.crawl.CrawlStatus] so the CLI and
 * MCP clients can use the same vocabulary for both crawl and search tasks.
 */
object SearchStatus {
    val CREATED: String = ResourceStatus.getStatusText(ResourceStatus.SC_CREATED)
    val PROCESSING: String = ResourceStatus.getStatusText(ResourceStatus.SC_PROCESSING)
    val OK: String = ResourceStatus.getStatusText(ResourceStatus.SC_OK)
    val REQUEST_TIMEOUT: String = ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT)
    val INTERNAL_SERVER_ERROR: String = ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR)
    val NOT_FOUND: String = ResourceStatus.getStatusText(ResourceStatus.SC_NOT_FOUND)

    /** States a task never leaves. */
    val TERMINAL: Set<String> = setOf(OK, REQUEST_TIMEOUT, INTERNAL_SERVER_ERROR, NOT_FOUND)

    /** States a task is still making progress in. */
    val RUNNING: Set<String> = setOf(CREATED, PROCESSING)

    fun isTerminal(status: String): Boolean = status in TERMINAL

    fun isRunning(status: String): Boolean = status in RUNNING
}

/**
 * One search result from the provider.
 *
 * @property url the result URL.
 * @property title the result title.
 * @property content the snippet/summary from the search provider.
 * @property score relevance score from the provider, if available.
 * @property scrapedContent populated only when [SearchRequest.scrape] is true:
 *   the full-page content extracted via the browser session.
 * @property scrapeError non-null when scraping this URL failed; one bad URL
 *   never aborts the whole task.
 */
data class SearchResult(
    val url: String,
    val title: String,
    val content: String? = null,
    val score: Double? = null,
    val scrapedContent: String? = null,
    val scrapeError: String? = null,
)

/**
 * The outcome of a search task: status, the results collected, and any error.
 */
data class SearchResponse(
    val taskId: String = "",
    val status: String = SearchStatus.CREATED,
    val query: String = "",
    val results: List<SearchResult>? = null,
    val pagesFound: Int = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var startedTime: Instant? = null,
    var finishTime: Instant? = null,
)
