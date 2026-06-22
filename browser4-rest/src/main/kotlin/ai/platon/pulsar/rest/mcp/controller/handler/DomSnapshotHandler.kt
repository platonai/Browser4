package ai.platon.pulsar.rest.mcp.controller.handler

import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.rest.mcp.controller.MCPConstants
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallRequest
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.rest.mcp.controller.isElementReference
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler.Companion.errorResponse
import ai.platon.pulsar.rest.mcp.controller.handler.SessionManagementHandler.Companion.textResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity

class DomSnapshotHandler(
    private val sessionManager: PulsarSessionManager,
    private val scrapeService: ScrapeService?,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(DomSnapshotHandler::class.java)

    suspend fun handleDomSnapshotCapture(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId"))

        return try {
            val metadata = managed.withLock {
                val pulsarSession = managed.agenticSession
                val page = pulsarSession.capture(managed.driver)
                val document = pulsarSession.parse(page, noCache = true)
                val title = document.title

                val json = jacksonObjectMapper().createObjectNode().apply {
                    put("url", page.url)
                    put("href", page.href)
                    put("sizeBytes", page.contentLength.toString())
                    put("capturedAt", page.prevFetchTime.toString())
                    put("contentType", page.contentType)
                    put("title", title)
                }
                json.toString()
            }
            ResponseEntity.ok(textResponse(metadata))
        } catch (e: Exception) {
            logger.error("dom_snapshot_capture failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("dom_snapshot_capture failed: ${e.message}"))
        }
    }

    suspend fun handleDomSnapshotScrape(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val args = request.arguments ?: emptyMap()
        val field = args["field"]?.toString() ?: ""
        val selector = args["selector"]?.toString()?.ifEmpty { ":root" } ?: ":root"
        val attrName = args["attrName"]?.toString()

        // Validate field
        if (field !in setOf("text", "html", "attr")) {
            return ResponseEntity.ok(errorResponse("Unknown field '$field'. Use text, html, or attr."))
        }

        // Validate attr field requires an attribute name
        if (field == "attr" && attrName.isNullOrBlank()) {
            return ResponseEntity.ok(errorResponse("The 'attr' field requires an attribute name."))
        }

        // Reject element references
        if (isElementReference(selector)) {
            return ResponseEntity.ok(
                errorResponse(
                    "Element references ('$selector') are not supported in domsnapshot get. Use a CSS selector instead."
                )
            )
        }

        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId"))

        return try {
            val result = managed.withLock {
                val pulsarSession = managed.agenticSession
                val url = pulsarSession.normalize(driver.userTypedUrl())
                val page = pulsarSession.getOrNull(url.urlString) ?: pulsarSession.capture(managed.driver)
                val document = pulsarSession.parse(page)

                when (field) {
                    "text" -> document.selectFirstOrNull(selector)?.text() ?: ""
                    "html" -> document.selectFirstOrNull(selector)?.html() ?: ""
                    "attr" -> document.selectFirstOrNull(selector)?.attr(attrName!!) ?: ""
                    else -> ""
                }
            }

            ResponseEntity.ok(textResponse(result))
        } catch (e: Exception) {
            logger.error("dom_snapshot_scrape failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("dom_snapshot_scrape failed: ${e.message}"))
        }
    }

    suspend fun handleDomSnapshotQuery(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val scrapeService = this.scrapeService
            ?: return ResponseEntity.ok(errorResponse("ScrapeService is not available"))

        val args = request.arguments ?: emptyMap()
        val sql = args["sql"]?.toString() ?: return ResponseEntity.ok(errorResponse("Missing 'sql'"))

        // Resolve URL: use explicit URL if provided, otherwise fall back to the current session's page URL
        val url = args["url"]?.toString()?.takeIf { it.isNotBlank() }
            ?: run {
                val sessionId = requireSessionId(request)
                val managed = sessionManager.getSession(sessionId)
                    ?: return ResponseEntity.ok(errorResponse("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId"))
                val pulsarSession = managed.agenticSession
                pulsarSession.normalize(managed.driver.userTypedUrl()).urlString
            }

        val processedSql = SQLTemplate(sql).createSQL(url)

        return try {
            val response = scrapeService.executeQuery(ScrapeRequest(processedSql))
            val json = objectMapper.writeValueAsString(response)
            ResponseEntity.ok(textResponse(json))
        } catch (e: Exception) {
            logger.error("dom_snapshot_query failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("dom_snapshot_query failed: ${e.message}"))
        }
    }

    suspend fun handleDomSnapshotExport(
        request: MCPToolCallRequest
    ): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("${MCPConstants.ERROR_SESSION_NOT_FOUND}$sessionId"))

        return try {
            val html = managed.withLock {
                val pulsarSession = managed.agenticSession
                val url = pulsarSession.normalize(managed.driver.userTypedUrl())
                val document = pulsarSession.loadDocument(url.urlString)
                document.outerHtml
            }
            ResponseEntity.ok(textResponse(html))
        } catch (e: Exception) {
            logger.error("dom_snapshot_export failed | {}", e.message, e)
            ResponseEntity.ok(errorResponse("dom_snapshot_export failed: ${e.message}"))
        }
    }

    private fun requireSessionId(request: MCPToolCallRequest): String {
        return request.arguments?.get("sessionId")?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: sessionId")
    }
}
