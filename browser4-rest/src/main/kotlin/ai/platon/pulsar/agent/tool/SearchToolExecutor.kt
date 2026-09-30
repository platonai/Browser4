package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.model.TaskPolicy
import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.TaskEnvelopes
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.service.search.SearchRequest
import ai.platon.pulsar.rest.api.service.search.SearchResponse
import ai.platon.pulsar.rest.api.service.search.SearchService
import kotlin.reflect.KClass

/**
 * Tool executor that exposes [SearchService] operations as MCP tools.
 *
 * Domain: `search`
 *
 * Supported methods:
 * - `submit(query, maxResults?, scrape?)` — Submit a search task, returns task ID
 * - `status(id)` — The shared status envelope of a search task, as JSON
 * - `result(id)` — The envelope plus the collected results
 * - `cancel(id)` — Stop a running task
 */
class SearchToolExecutor(
    private val searchService: SearchService,
) : AbstractToolExecutor() {

    override val domain: String = "search"
    override val receiverClass: KClass<*> = SearchService::class

    init {
        toolSpec["submit"] = ToolSpec(
            domain = domain,
            method = "submit",
            arguments = listOf(
                ToolSpec.Arg(
                    "query", "String", null,
                    "The search query. Required.",
                ),
                ToolSpec.Arg(
                    "maxResults", "Int", "10",
                    "Maximum number of results to return.",
                ),
                ToolSpec.Arg(
                    "scrape", "Boolean", "false",
                    "If true, fetch and extract content from each result URL via the " +
                        "browser session.  Slower; one browser tab per result.",
                ),
                ToolSpec.Arg(
                    "scrapeFormat", "String", "markdown",
                    "Output format for scraped content: `markdown` (default) or `html`.",
                ),
                ToolSpec.Arg(
                    "topic", "String", "general",
                    "Search topic: `general` (default) or `news`.",
                ),
                ToolSpec.Arg(
                    "timeRange", "String", "",
                    "Restrict to recent results: `day`, `week`, `month`, or `year`. " +
                        "Empty means no restriction.",
                ),
                ToolSpec.Arg(
                    "includeDomains", "Array<String>", "",
                    "Restrict results to these domains.  Empty means no restriction.",
                ),
                ToolSpec.Arg(
                    "excludeDomains", "Array<String>", "",
                    "Exclude results from these domains.  Empty means no restriction.",
                ),
            ),
            returnType = "String",
            description = "Submit a search task. Returns a task ID for status polling.",
            help = """
                Submits a web search (Tavily by default, Bocha if `search.provider=bocha`)
                and returns the task id immediately.  Poll it with `search.status`,
                read the payload with `search.result`.  Set `scrape=true` to also
                fetch and extract the content of each result URL via the browser
                session — useful when the snippet is not enough and you need the
                full page content.
            """.trimIndent(),
            examples = listOf(
                ToolExample(
                    title = "Quick web search",
                    args = mapOf("query" to "Browser4 agentic browser Rust"),
                ),
                ToolExample(
                    title = "Search and scrape each result",
                    args = mapOf(
                        "query" to "Rust async runtime comparison",
                        "scrape" to true,
                        "maxResults" to 5,
                    ),
                    notes = "Feed the returned task id to search.status / search.result",
                ),
            ),
            task = TaskPolicy(
                statusTool = "search_status",
                resultTool = "search_result",
                cancelTool = "search_cancel",
            ),
            outputSchema = TaskEnvelopes.SUBMIT_SCHEMA,
        )

        toolSpec["status"] = ToolSpec(
            domain = domain,
            method = "status",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `search.submit`."),
            ),
            returnType = "String",
            description = "Poll a search task: the shared status envelope as JSON.",
            help = """
                search.status(id: String)

                Returns the shared envelope — `taskId`, `status`
                (`queued|running|done|failed|cancelled`), `processed` (results collected
                so far), `elapsedMs` once the task finished, and the tool names to
                poll, read and cancel with.  An unknown id reports `status=failed`.
            """.trimIndent(),
            outputSchema = TaskEnvelopes.STATUS_SCHEMA,
            examples = listOf(
                ToolExample(
                    title = "Poll a submitted task",
                    args = mapOf("id" to "8f14e45f-ea0b-4c1e-9d3a-2f6d5c4b1a09"),
                    notes = "An unknown id reports status=failed with an error rather than a 404",
                ),
            ),
        )

        toolSpec["result"] = ToolSpec(
            domain = domain,
            method = "result",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `search.submit`."),
            ),
            returnType = "String",
            description = "Read a finished search task: the status envelope plus the results it collected.",
            help = """
                search.result(id: String)

                The status envelope plus `results` — one entry per search hit with its
                title, url, snippet (and scraped content when `scrape=true` was used).
                Poll `search.status` until it is terminal before reading.
            """.trimIndent(),
            outputSchema = TaskEnvelopes.STATUS_SCHEMA,
            examples = listOf(
                ToolExample(
                    title = "Read the collected results",
                    args = mapOf("id" to "8f14e45f-ea0b-4c1e-9d3a-2f6d5c4b1a09"),
                ),
            ),
        )

        toolSpec["cancel"] = ToolSpec(
            domain = domain,
            method = "cancel",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `search.submit`."),
            ),
            returnType = "String",
            description = "Cancel a running search task; the task ends in status=cancelled.",
            help = """
                Cancels the task's coroutine and finalizes its record, so a client can
                stop polling immediately.  Cancelling an unknown or already finished
                task is reported (cancelled=false) rather than treated as an error:
                the desired end state already holds.
            """.trimIndent(),
            outputSchema = TaskEnvelopes.STATUS_SCHEMA,
            examples = listOf(
                ToolExample(
                    title = "Stop a running search",
                    args = mapOf("id" to "8f14e45f-ea0b-4c1e-9d3a-2f6d5c4b1a09"),
                ),
            ),
        )
    }

    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }

        return when (functionName) {
            "submit" -> {
                val query = paramString(args, "query", functionName, required = true)!!
                val maxResults = paramInt(args, "maxResults", functionName, required = false, default = 10) ?: 10
                val scrape = paramBool(args, "scrape", functionName, required = false, default = false) ?: false
                val scrapeFormat = paramString(args, "scrapeFormat", functionName, required = false, default = "markdown") ?: "markdown"
                val topic = paramString(args, "topic", functionName, required = false, default = "general") ?: "general"
                val timeRange = paramString(args, "timeRange", functionName, required = false, default = null)
                val includeDomains = paramStringList(args, "includeDomains", functionName, required = false).ifEmpty { null }
                val excludeDomains = paramStringList(args, "excludeDomains", functionName, required = false).ifEmpty { null }
                searchService.submit(
                    SearchRequest(
                        query = query,
                        maxResults = maxResults,
                        scrape = scrape,
                        scrapeFormat = scrapeFormat,
                        topic = topic,
                        timeRange = timeRange,
                        includeDomains = includeDomains,
                        excludeDomains = excludeDomains,
                    )
                )
            }
            "status" -> envelopeOf(paramString(args, "id", functionName)!!, withResults = false)
            "result" -> envelopeOf(paramString(args, "id", functionName)!!, withResults = true)
            "cancel" -> {
                val id = paramString(args, "id", functionName)!!
                val cancelled = searchService.cancel(id)
                jsonOf(
                    TaskEnvelopes.of(
                        taskId = id,
                        status = if (cancelled) "cancelled" else searchService.getResult(id).statusOf(),
                        error = if (cancelled) null else "Task is not running (unknown or already finished)",
                        statusTool = "search_status",
                        resultTool = "search_result",
                    ) + mapOf("cancelled" to cancelled)
                )
            }
            else -> throw IllegalArgumentException("Unsupported search method: $functionName")
        }
    }

    /**
     * The status envelope of one task, serialized as JSON.
     *
     * Serializing here (instead of returning the `SearchResponse` object) is what
     * makes the contract checkable: the generic renderer turns an arbitrary object
     * into `{type, description}` text, which no client — and no `outputSchema` —
     * can rely on.
     *
     * @param withResults include the collected results (the `result` tool) or not (`status`)
     */
    private fun envelopeOf(id: String, withResults: Boolean): String {
        val response = searchService.getResult(id)
        val known = response.taskId.isNotBlank()
        // An unknown id must report a terminal status: reporting the placeholder
        // `CREATED` would leave a client polling a task that does not exist.
        val status = if (known) response.statusOf() else "failed"

        val envelope = TaskEnvelopes.of(
            taskId = id,
            status = status,
            processed = if (known) response.pagesFound else null,
            total = null,
            elapsedMs = response.elapsedMs(),
            error = response.error ?: if (known) null else "Unknown task id: $id",
            statusTool = "search_status",
            resultTool = "search_result",
            cancelTool = "search_cancel",
        )
        val results = if (withResults && !response.results.isNullOrEmpty()) response.results else null
        return jsonOf(envelope + (results?.let { mapOf("results" to it) } ?: emptyMap()))
    }

    /** `SearchResponse.status` mapped onto the shared vocabulary. */
    private fun SearchResponse.statusOf(): String = TaskEnvelopes.normalise(status)

    private fun SearchResponse.elapsedMs(): Long? {
        val finish = finishTime ?: return null
        val start = startedTime ?: return null
        return java.time.Duration.between(start, finish).toMillis()
    }

    private fun jsonOf(value: Any): String = pulsarObjectMapper().writeValueAsString(value)
}
