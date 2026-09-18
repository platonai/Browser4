package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.model.TaskPolicy
import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.TaskEnvelopes
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.service.crawl.CrawlRequest
import ai.platon.pulsar.rest.api.service.crawl.CrawlResponse
import ai.platon.pulsar.rest.api.service.crawl.CrawlService
import kotlin.reflect.KClass

/**
 * Tool executor that exposes [CrawlService] operations as MCP tools.
 *
 * Domain: `crawl`
 *
 * Supported methods:
 * - `submit(url, depth?, args?)` — Submit a crawl task, returns task ID
 * - `status(id)` — The shared status envelope of a crawl task, as JSON
 * - `result(id)` — The envelope plus the collected pages
 * - `cancel(id)` — Stop a running task; it ends in `status=cancelled`
 */
class CrawlToolExecutor(
    private val crawlService: CrawlService,
) : AbstractToolExecutor() {

    override val domain: String = "crawl"
    override val receiverClass: KClass<*> = CrawlService::class

    init {
        toolSpec["submit"] = ToolSpec(
            domain = domain,
            method = "submit",
            arguments = listOf(
                ToolSpec.Arg(
                    "url", "String", null,
                    "Seed URL to start from. Required unless the seed list is supplied through `args`.",
                ),
                ToolSpec.Arg(
                    "depth", "Int", "1",
                    "How many link levels to follow from the seed. `0` processes the seed page only.",
                ),
                ToolSpec.Arg(
                    "args", "String", "",
                    "Extra crawl arguments as a CLI-style string, e.g. " +
                        "`-outLinkSelector=a[href]` or an X-SQL query.",
                ),
            ),
            returnType = "String",
            description = "Submit a crawl task. Returns a task ID for status polling.",
            help = """
                Starts a recursive crawl in the background and returns the task id
                immediately. Poll it with `crawl.status`, read the payload with
                `crawl.result`. Use `depth=0` for a single page, and prefer the
                swarm domain for high-throughput parallel extraction.
            """.trimIndent(),
            examples = listOf(
                ToolExample(
                    title = "Crawl one link level from a seed URL",
                    args = mapOf("url" to "https://example.com", "depth" to "1"),
                ),
                ToolExample(
                    title = "Seed page only, then poll",
                    args = mapOf("url" to "https://example.com", "depth" to "0"),
                    notes = "Feed the returned task id to crawl.status",
                ),
            ),
            task = TaskPolicy(
                statusTool = "crawl_status",
                resultTool = "crawl_result",
                cancelTool = "crawl_cancel",
            ),
            outputSchema = TaskEnvelopes.SUBMIT_SCHEMA,
        )

        toolSpec["status"] = ToolSpec(
            domain = domain,
            method = "status",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `crawl.submit`."),
            ),
            returnType = "String",
            description = "Poll a crawl task: the shared status envelope as JSON.",
            help = """
                crawl.status(id: String)

                Returns the shared envelope — `taskId`, `status`
                (`queued|running|done|failed|cancelled`), `processed` (pages collected
                so far), `elapsedMs` once the task finished, and the tool names to
                poll, read and cancel with. An unknown id reports `status=failed`.
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
                ToolSpec.Arg("id", "String", null, "Task id returned by `crawl.submit`."),
            ),
            returnType = "String",
            description = "Read a finished crawl task: the status envelope plus the pages it collected.",
            help = """
                crawl.result(id: String)

                The status envelope plus `pages` — one entry per crawled URL with its
                title, content length and any X-SQL extraction. Poll `crawl.status`
                until it is terminal before reading.
            """.trimIndent(),
            outputSchema = TaskEnvelopes.STATUS_SCHEMA,
            examples = listOf(
                ToolExample(
                    title = "Read the collected pages",
                    args = mapOf("id" to "8f14e45f-ea0b-4c1e-9d3a-2f6d5c4b1a09"),
                ),
            ),
        )

        toolSpec["cancel"] = ToolSpec(
            domain = domain,
            method = "cancel",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `crawl.submit`."),
            ),
            returnType = "String",
            description = "Cancel a running crawl task; the task ends in status=cancelled.",
            help = """
                Cancels the task's coroutine and finalizes its record, so a client can
                stop polling immediately. Cancelling an unknown or already finished
                task is reported (cancelled=false) rather than treated as an error:
                the desired end state already holds.
            """.trimIndent(),
            outputSchema = TaskEnvelopes.STATUS_SCHEMA,
            examples = listOf(
                ToolExample(
                    title = "Stop a running crawl",
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
                val url = paramString(args, "url", functionName, required = false, default = "") ?: ""
                val urls = paramStringList(args, "urls", functionName, required = false).ifEmpty { null }
                val depth = paramInt(args, "depth", functionName, required = false, default = 1) ?: 1
                val crawlArgs = paramString(args, "args", functionName, required = false, default = "") ?: ""
                val sql = paramString(args, "sql", functionName, required = false, default = null)
                crawlService.submit(CrawlRequest(url = url, args = crawlArgs, depth = depth, sql = sql, urls = urls))
            }
            "status" -> envelopeOf(paramString(args, "id", functionName)!!, withPages = false)
            "result" -> envelopeOf(paramString(args, "id", functionName)!!, withPages = true)
            "cancel" -> {
                val id = paramString(args, "id", functionName)!!
                val cancelled = crawlService.cancel(id)
                jsonOf(
                    TaskEnvelopes.of(
                        taskId = id,
                        status = if (cancelled) "cancelled" else crawlService.getResult(id).statusOf(),
                        error = if (cancelled) null else "Task is not running (unknown or already finished)",
                        statusTool = "crawl_status",
                        resultTool = "crawl_result",
                    ) + mapOf("cancelled" to cancelled)
                )
            }
            else -> throw IllegalArgumentException("Unsupported crawl method: $functionName")
        }
    }

    /**
     * The status envelope of one task, serialized as JSON.
     *
     * Serializing here (instead of returning the `CrawlResponse` object) is what
     * makes the contract checkable: the generic renderer turns an arbitrary object
     * into `{type, description}` text, which no client — and no `outputSchema` —
     * can rely on.
     *
     * @param withPages include the collected pages (the `result` tool) or not (`status`)
     */
    private fun envelopeOf(id: String, withPages: Boolean): String {
        val response = crawlService.getResult(id)
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
            statusTool = "crawl_status",
            resultTool = "crawl_result",
            cancelTool = "crawl_cancel",
        )
        val pages = if (withPages && !response.pages.isNullOrEmpty()) response.pages else null
        return jsonOf(envelope + (pages?.let { mapOf("pages" to it) } ?: emptyMap()))
    }

    /** `CrawlResponse.status` mapped onto the shared vocabulary. */
    private fun CrawlResponse.statusOf(): String = TaskEnvelopes.normalise(status)

    private fun CrawlResponse.elapsedMs(): Long? {
        val finish = finishTime ?: return null
        val start = startedTime ?: return null
        return java.time.Duration.between(start, finish).toMillis()
    }

    private fun jsonOf(value: Any): String = pulsarObjectMapper().writeValueAsString(value)
}
