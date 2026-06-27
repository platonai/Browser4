package ai.platon.pulsar.rest.mcp.controller.handler

import ai.platon.pulsar.agentic.tools.advanced.crawl.QueryRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.rest.api.entities.ScrapeStatusRequest
import ai.platon.pulsar.rest.api.service.SwarmService
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallRequest
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler.Companion.errorResponse
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler.Companion.textResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity

class SwarmMcpHandler(
    private val swarmService: SwarmService,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(SwarmMcpHandler::class.java)

    suspend fun handleSwarmSubmit(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val args = request.arguments ?: emptyMap()
        val payload = args["payload"]?.toString()?.trim()
            ?: return ResponseEntity.ok(errorResponse("Missing 'payload': url or X-SQL to submit"))

        if (payload.isBlank()) {
            return ResponseEntity.ok(errorResponse("'payload' must be a non-blank URL or X-SQL"))
        }

        val sql = if (payload.startsWith("http")) {
            "select dom_base_uri(dom) as url from load_and_select('$payload', ':root')"
        } else {
            payload
        }

        return try {
            ScrapeAPIUtils.checkSql(sql)
            val taskId = swarmService.submit(ScrapeRequest(sql))
            logger.info("swarm_submit: taskId={}", taskId)
            ResponseEntity.ok(textResponse(taskId))
        } catch (e: IllegalArgumentException) {
            logger.warn("swarm_submit invalid payload: {}", e.message)
            ResponseEntity.ok(errorResponse("Invalid URL or X-SQL: $payload"))
        } catch (e: Exception) {
            logger.error("swarm_submit failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("swarm_submit failed: ${e.message}"))
        }
    }

    suspend fun handleSwarmQuery(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val args = request.arguments ?: emptyMap()
        val url = args["url"]?.toString()?.trim()
            ?: return ResponseEntity.ok(errorResponse("Missing 'url'"))
        val query = args["query"]?.toString()?.trim()
            ?: return ResponseEntity.ok(errorResponse("Missing 'query' (X-SQL)"))
        val queryArgs = args["args"]?.toString() ?: ""

        val queryRequest = QueryRequest(
            url = url,
            args = queryArgs,
            query = query,
        )

        return try {
            val taskId = swarmService.submit(queryRequest)
            logger.info("swarm_query: taskId={} url={}", taskId, url)
            ResponseEntity.ok(textResponse(taskId))
        } catch (e: Exception) {
            logger.error("swarm_query failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("swarm_query failed: ${e.message}"))
        }
    }

    suspend fun handleSwarmStatus(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val args = request.arguments ?: emptyMap()
        val id = args["id"]?.toString()?.trim()
            ?: return ResponseEntity.ok(errorResponse("Missing 'id'"))

        if (id.isBlank()) {
            return ResponseEntity.ok(errorResponse("'id' must not be blank"))
        }

        return try {
            val status = swarmService.getStatus(ScrapeStatusRequest(id))
            ResponseEntity.ok(textResponse(objectMapper.writeValueAsString(status)))
        } catch (e: Exception) {
            logger.error("swarm_status failed | id={} | {}", id, e.message, e)
            ResponseEntity.ok(errorResponse("swarm_status failed: ${e.message}"))
        }
    }

    suspend fun handleSwarmResult(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        // SwarmController.getResult() delegates to getStatus(), same semantics
        return handleSwarmStatus(request)
    }
}
