package ai.platon.pulsar.rest.mcp.controller.handler

import ai.platon.pulsar.rest.api.service.CrawlRequest
import ai.platon.pulsar.rest.api.service.CrawlService
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallRequest
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler.Companion.errorResponse
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler.Companion.textResponse
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity

class CrawlMcpHandler(
    private val crawlService: CrawlService,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(CrawlMcpHandler::class.java)

    suspend fun handleCrawlSubmit(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val args = request.arguments ?: emptyMap()
        val url = args["url"]?.toString()?.trim()
            ?: return ResponseEntity.ok(errorResponse("Missing 'url'"))

        if (url.isBlank()) {
            return ResponseEntity.ok(errorResponse("'url' must not be blank"))
        }

        val depth = (args["depth"] as? Number)?.toInt() ?: 1
        if (depth < 1) {
            return ResponseEntity.ok(errorResponse("'depth' must be >= 1, got $depth"))
        }

        val crawlArgs = args["args"]?.toString() ?: ""

        val crawlRequest = CrawlRequest(
            url = url,
            args = crawlArgs,
            depth = depth,
        )

        return try {
            val taskId = crawlService.submit(crawlRequest)
            logger.info("crawl_submit: taskId={} url={} depth={}", taskId, url, depth)
            ResponseEntity.ok(textResponse(taskId))
        } catch (e: Exception) {
            logger.error("crawl_submit failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("crawl_submit failed: ${e.message}"))
        }
    }

    suspend fun handleCrawlStatus(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val args = request.arguments ?: emptyMap()
        val id = args["id"]?.toString()?.trim()
            ?: return ResponseEntity.ok(errorResponse("Missing 'id'"))

        if (id.isBlank()) {
            return ResponseEntity.ok(errorResponse("'id' must not be blank"))
        }

        return try {
            val result = crawlService.getResult(id)
            ResponseEntity.ok(textResponse(objectMapper.writeValueAsString(result)))
        } catch (e: Exception) {
            logger.error("crawl_status failed | id={} | {}", id, e.message, e)
            ResponseEntity.ok(errorResponse("crawl_status failed: ${e.message}"))
        }
    }

    suspend fun handleCrawlResult(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        // CrawlController.getResult() delegates to getResult() same as getStatus(),
        // so crawl_result and crawl_status have identical semantics
        return handleCrawlStatus(request)
    }
}
