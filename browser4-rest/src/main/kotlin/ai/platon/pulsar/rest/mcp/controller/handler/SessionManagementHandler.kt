package ai.platon.pulsar.rest.mcp.controller.handler

import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.mcp.controller.CLEAR_SESSION_STORAGE_SCRIPT
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallRequest
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.rest.mcp.controller.dto.MCPContent
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity

class SessionManagementHandler(
    private val sessionManager: PulsarSessionManager,
) {
    private val logger = LoggerFactory.getLogger(SessionManagementHandler::class.java)

    fun handleOpenSession(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val capabilities = request.arguments?.get("capabilities") as? Map<String, String?>
        val session = sessionManager.getOrCreateSession(capabilities)

        // Navigate to initial URL if provided
        val url = request.arguments?.get("url")?.toString()
        // Navigate operation is handled in the client side

        logger.info("MCP open_session: created session {}", session.sessionId)
        return ResponseEntity.ok(
            textResponse("""{"sessionId":"${session.sessionId}"}""")
        )
    }

    fun handleCloseSession(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val deleted = sessionManager.deleteSession(sessionId)
        return if (deleted) {
            ResponseEntity.ok(textResponse("Session closed"))
        } else {
            ResponseEntity.ok(errorResponse("Session not found: $sessionId"))
        }
    }

    fun handleListSessions(): ResponseEntity<MCPToolCallResponse> {
        val sessions = sessionManager.getAllSessions().map { s ->
            """{"sessionId":"${s.sessionId}","url":"${s.url ?: ""}","status":"${s.status}"}"""
        }
        return ResponseEntity.ok(textResponse("[${sessions.joinToString(",")}]"))
    }

    fun handleCloseAllSessions(): ResponseEntity<MCPToolCallResponse> {
        val count = sessionManager.deleteAllSessions()
        return ResponseEntity.ok(textResponse("Closed $count session(s)"))
    }

    fun handleKillAllSessions(): ResponseEntity<MCPToolCallResponse> {
        val count = sessionManager.deleteAllSessions()
        return ResponseEntity.ok(textResponse("Killed $count session(s)"))
    }

    suspend fun handleDeleteSessionData(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val managed = sessionManager.getSession(sessionId)
            ?: return ResponseEntity.ok(errorResponse("Session not found: $sessionId"))

        managed.withLock {
            driver.clearBrowserCookies()
            val storageResult = driver.evaluate(CLEAR_SESSION_STORAGE_SCRIPT)?.toString().orEmpty()
            if (storageResult.isNotBlank() && !storageResult.contains("\"errors\":[]")) {
                logger.warn(
                    "delete_session_data completed with partial storage cleanup | sessionId={} | result={}",
                    sessionId,
                    storageResult
                )
            }
        }

        return ResponseEntity.ok(textResponse("User data deleted for session"))
    }

    fun handleAttachBrowser(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val args = request.arguments ?: emptyMap()

        // Extension-based attach (Browser4 Chrome Extension)
        val extensionMode = (args["extension"] as? Boolean) == true
            || (args["extension"] as? String)?.toBoolean() == true

        if (extensionMode) {
            val channel = (args["channel"] as? String)?.takeIf { it.isNotBlank() }
                ?: (args["extension"] as? String)?.takeIf { it.isNotBlank() && it != "true" }

            val session = sessionManager.createExtensionAttachedSession(
                channel = channel,
                capabilities = args.filterKeys {
                    it != "extension" && it != "channel" && it != "sessionId" && it != "cdp" && it != "endpoint"
                }.mapValues { it.value?.toString() }
            )

            logger.info(
                "MCP attach_browser (extension): created session {} at {} (channel={})",
                session.sessionId, session.wsEndpoint, channel ?: "default"
            )
            return ResponseEntity.ok(
                textResponse("""{"sessionId":"${session.sessionId}","wsEndpoint":"${session.wsEndpoint}"}""")
            )
        }

        // Existing CDP-based attach
        val cdpEndpoint = (args["cdpEndpoint"] as? String)?.takeIf { it.isNotBlank() }
        val cdpPort = (args["cdpPort"] as? Number)?.toInt()

        require(cdpEndpoint != null || cdpPort != null) {
            "attach_browser requires either 'cdpEndpoint' (URL), 'cdpPort' (number), or 'extension' (boolean)"
        }

        val session = sessionManager.createAttachedSession(
            cdpEndpoint = cdpEndpoint,
            cdpPort = cdpPort,
            capabilities = args.filterKeys {
                it != "cdpEndpoint" && it != "cdpPort" && it != "sessionId"
            }.mapValues { it.value?.toString() }
        )

        logger.info(
            "MCP attach_browser: created session {} attached to {}",
            session.sessionId,
            cdpEndpoint ?: "port $cdpPort"
        )
        return ResponseEntity.ok(
            textResponse("""{"sessionId":"${session.sessionId}"}""")
        )
    }

    /**
     * Checks whether an extension-attached session is ready (the extension has
     * connected via WebSocket).  Used by the CLI to poll after launching the
     * browser.
     */
    fun handleCheckSessionReadiness(request: MCPToolCallRequest): ResponseEntity<MCPToolCallResponse> {
        val sessionId = requireSessionId(request)
        val ready = sessionManager.isExtensionSessionReady(sessionId)
        val session = sessionManager.getSession(sessionId)
        val healthy = if (session != null) {
            try {
                sessionManager.checkHealthyBlocking(session).isOK
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        return ResponseEntity.ok(
            textResponse("""{"ready":$ready,"healthy":$healthy}""")
        )
    }

    private fun requireSessionId(request: MCPToolCallRequest): String {
        return request.arguments?.get("sessionId")?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: sessionId")
    }

    companion object {
        internal fun textResponse(text: String): MCPToolCallResponse =
            MCPToolCallResponse(content = listOf(MCPContent(text = text)))

        internal fun errorResponse(message: String): MCPToolCallResponse =
            MCPToolCallResponse(content = listOf(MCPContent(text = "ERROR: $message")), isError = true)
    }
}
