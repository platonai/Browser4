package ai.platon.pulsar.agent.tool

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
                ToolSpec.Arg("url", "String", null),
                ToolSpec.Arg("depth", "Int", "1"),
                ToolSpec.Arg("args", "String", ""),
            ),
            returnType = "String",
            description = "Submit a crawl task. Returns a task ID for status polling."
        )

        toolSpec["status"] = ToolSpec(
            domain = domain,
            method = "status",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null),
            ),
            returnType = "CrawlResponse",
            description = "Get the status/result of a crawl task by its task ID."
        )

        toolSpec["result"] = ToolSpec(
            domain = domain,
            method = "result",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null),
            ),
            returnType = "CrawlResponse",
            description = "Get the result of a completed crawl task by its task ID."
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
