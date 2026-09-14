package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.model.TaskPolicy
import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.rest.api.service.CrawlRequest
import ai.platon.pulsar.rest.api.service.CrawlService
import kotlin.reflect.KClass

/**
 * Tool executor that exposes [CrawlService] operations as MCP tools.
 *
 * Domain: `crawl`
 *
 * Supported methods:
 * - `submit(url, depth?, args?)` — Submit a crawl task, returns task ID
 * - `status(id)` — Get the status/result of a crawl task
 * - `result(id)` — Get the result of a completed crawl task
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
            task = TaskPolicy(statusTool = "crawl_status", resultTool = "crawl_result"),
            outputSchema = TASK_ENVELOPE_SCHEMA,
        )

        toolSpec["status"] = ToolSpec(
            domain = domain,
            method = "status",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `crawl.submit`."),
            ),
            returnType = "CrawlResponse",
            description = "Get the status/result of a crawl task by its task ID.",
            // No outputSchema yet: the result renderer wraps a CrawlResponse as
            // {type, description} text, so there is no stable JSON shape to
            // promise. Declaring one before the renderer emits JSON would fail
            // result validation on every call.
            examples = listOf(
                ToolExample(
                    title = "Poll a submitted task",
                    args = mapOf("id" to "8f14e45f-ea0b-4c1e-9d3a-2f6d5c4b1a09"),
                    notes = "An unknown id returns a 404-style CrawlResponse rather than an error",
                ),
            ),
        )

        toolSpec["result"] = ToolSpec(
            domain = domain,
            method = "result",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `crawl.submit`."),
            ),
            returnType = "CrawlResponse",
            description = "Get the result of a completed crawl task by its task ID.",
            examples = listOf(
                ToolExample(
                    title = "Read the collected pages",
                    args = mapOf("id" to "8f14e45f-ea0b-4c1e-9d3a-2f6d5c4b1a09"),
                ),
            ),
        )
    }

    private companion object {
        /** The shared task envelope every submit tool returns. */
        const val TASK_ENVELOPE_SCHEMA = """
            {
              "type": "object",
              "required": ["taskId", "status", "statusTool"],
              "properties": {
                "taskId": {"type": "string"},
                "status": {"type": "string", "enum": ["running", "done", "failed"]},
                "pollAfterMs": {"type": "integer"},
                "statusTool": {"type": "string"},
                "resultTool": {"type": "string"}
              }
            }
        """
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
            "status" -> {
                val id = paramString(args, "id", functionName)!!
                crawlService.getResult(id)
            }
            "result" -> {
                val id = paramString(args, "id", functionName)!!
                crawlService.getResult(id)
            }
            else -> throw IllegalArgumentException("Unsupported crawl method: $functionName")
        }
    }
}
