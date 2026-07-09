package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.QueryRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.rest.api.entities.ScrapeStatusRequest
import ai.platon.pulsar.rest.api.service.SwarmService
import kotlin.reflect.KClass

/**
 * Tool executor that exposes [SwarmService] operations as MCP tools.
 *
 * Domain: `swarm`
 *
 * Supported methods:
 * - `submit(payload)` — Submit a swarm scraping task (URL or X-SQL), returns task ID
 * - `query(url, query, args?)` — Submit a query-based swarm task, returns task ID
 * - `status(id)` — Get the status/result of a swarm task
 * - `result(id)` — Get the result of a completed swarm task
 */
class SwarmToolExecutor(
    private val swarmService: SwarmService,
) : AbstractToolExecutor() {

    override val domain: String = "swarm"
    override val receiverClass: KClass<*> = SwarmService::class

    init {
        toolSpec["submit"] = ToolSpec(
            domain = domain,
            method = "submit",
            arguments = listOf(
                ToolSpec.Arg("payload", "String", null),
            ),
            returnType = "String",
            description = "Submit a swarm scraping task with a URL or X-SQL payload. Returns a task ID."
        )

        toolSpec["query"] = ToolSpec(
            domain = domain,
            method = "query",
            arguments = listOf(
                ToolSpec.Arg("url", "String", null),
                ToolSpec.Arg("query", "String", null),
                ToolSpec.Arg("args", "String", ""),
            ),
            returnType = "String",
            description = "Submit a query-based swarm task. Returns a task ID."
        )

        toolSpec["status"] = ToolSpec(
            domain = domain,
            method = "status",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null),
            ),
            returnType = "ScrapeResponse",
            description = "Get the status/result of a swarm task by its task ID."
        )

        toolSpec["result"] = ToolSpec(
            domain = domain,
            method = "result",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null),
            ),
            returnType = "ScrapeResponse",
            description = "Get the result of a completed swarm task by its task ID."
        )
    }

    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }

        return when (functionName) {
            "submit" -> {
                val payload = paramString(args, "payload", functionName)!!
                if (payload.isBlank()) {
                    throw IllegalArgumentException("'payload' must be a non-blank URL or X-SQL")
                }
                val sql = if (payload.startsWith("http")) {
                    "select dom_base_uri(dom) as url from load_and_select('$payload', ':root')"
                } else {
                    payload
                }
                ScrapeAPIUtils.checkSql(sql)
                swarmService.submit(ScrapeRequest(sql))
            }
            "query" -> {
                val url = paramString(args, "url", functionName)!!
                val query = paramString(args, "query", functionName)!!
                val queryArgs = paramString(args, "args", functionName, required = false, default = "") ?: ""
                swarmService.submit(QueryRequest(url = url, args = queryArgs, query = query))
            }
            "status" -> {
                val id = paramString(args, "id", functionName)!!
                swarmService.getStatus(ScrapeStatusRequest(id))
            }
            "result" -> {
                val id = paramString(args, "id", functionName)!!
                swarmService.getStatus(ScrapeStatusRequest(id))
            }
            else -> throw IllegalArgumentException("Unsupported swarm method: $functionName")
        }
    }
}
