package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.mcp.McpToolNames
import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper

/**
 * The `session` domain: the lifecycle tools (`open_session`, `close_session`,
 * `attach_browser`, …) as a regular tool executor.
 *
 * These tools started out inline in [MCPToolController] because they need no
 * browser session to be called. That made them unreachable from the standard MCP
 * server: it enumerates executors, so a tool that is only a `when` branch simply
 * did not exist on that channel. Registering them as an executor (through
 * [ai.platon.pulsar.agentic.tools.ToolMount]) puts them in the shared tool catalog,
 * so both channels advertise — and execute — the same lifecycle tools.
 *
 * Execution still goes through the dispatcher's own handlers
 * ([MCPToolController.executeSessionTool]): they own the verified close/attach
 * payloads and the session-scoped window probe, and duplicating them here is how
 * the two channels would drift apart again. The receiver is the Spring-managed
 * controller, resolved by [CustomToolTargets] on both channels.
 */
class SessionToolExecutor : AbstractToolExecutor() {

    override val domain: String = McpToolNames.SESSION_DOMAIN

    /**
     * The receiver is the REST controller that owns the lifecycle handlers.
     *
     * `receiverClass` is how [CustomToolTargets] knows to resolve that bean —
     * without it the executor would be advertised but not executable on the
     * standard server.
     */
    override val receiverClass = MCPToolController::class

    init {
        toolSpec.putAll(SessionToolSpecs.specs)
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any,
    ): Any? {
        val controller = receiver as? MCPToolController
            ?: throw IllegalStateException(
                "The '${McpToolNames.SESSION_DOMAIN}' domain needs the REST session controller as its " +
                    "receiver, but got ${receiver::class.simpleName}"
            )

        val toolName = McpToolNames.sessionRenames["${McpToolNames.SESSION_DOMAIN}.$functionName"]
            ?: throw IllegalArgumentException("Unsupported session method: $functionName")

        val response = controller.executeSessionTool(toolName, args)
        val text = response.content.joinToString("\n") { it.text }
        if (response.isError) {
            // The handler already phrased the failure (with its stable error code);
            // surface it unchanged instead of inventing a second wording.
            throw IllegalArgumentException(text.ifBlank { "$toolName failed" })
        }

        // Prefer the typed payload: the standard MCP server parses JSON text into
        // `structuredContent`, so an MCP client sees the same fields the dispatcher
        // returns. Fall back to the stable text for tools that only have one.
        val structured = response.structuredContent
        if (structured != null) {
            return pulsarObjectMapper().writeValueAsString(structured)
        }
        return text
    }
}

/**
 * The tool definitions of the `session` domain.
 *
 * Kept apart from [SessionToolExecutor] so the dispatcher's static segment can
 * advertise the same specs without instantiating the executor — the catalog
 * renders one definition per tool on both channels.
 */
object SessionToolSpecs {

    val specs: Map<String, ToolSpec> = linkedMapOf(
        "open" to ToolSpec(
            domain = McpToolNames.SESSION_DOMAIN,
            method = "open",
            arguments = listOf(
                ToolSpec.Arg("url", "String", description = "Initial page URL to load in the new session"),
                ToolSpec.Arg(
                    "capabilities", "Map",
                    description = "Session capabilities, e.g. {displayMode: HEADLESS, headless: true}"
                ),
                ToolSpec.Arg("sessionId", "String", description = "Name for the new session; a default name is used when omitted"),
            ),
            returnType = "String",
            description = "Create a browser session and return its id. A browser is launched lazily, on the first " +
                "tool call that needs a page.",
            examples = listOf(ToolExample(args = mapOf("url" to "https://example.com"))),
        ),
        "close" to ToolSpec(
            domain = McpToolNames.SESSION_DOMAIN,
            method = "close",
            arguments = listOf(ToolSpec.Arg("sessionId", "String", description = "Session to close")),
            returnType = "String",
            description = "Close a session; the payload reports whether its browser was verified gone, " +
                "whether it only detached, and how much profile space the close reclaimed.",
        ),
        "list" to ToolSpec(
            domain = McpToolNames.SESSION_DOMAIN,
            method = "list",
            returnType = "String",
            description = "List sessions with their URL, status, health, kind, display mode and, " +
                "for attached sessions, the verified browser identity and endpoint.",
        ),
        "closeAll" to ToolSpec(
            domain = McpToolNames.SESSION_DOMAIN,
            method = "closeAll",
            returnType = "String",
            description = "Close every session and report, per session, whether its browser was verified " +
                "gone, survived, or could not be checked.",
        ),
        "killAll" to ToolSpec(
            domain = McpToolNames.SESSION_DOMAIN,
            method = "killAll",
            returnType = "String",
            description = "Kill every session's browser and report the same per-session termination facts " +
                "as close_all_sessions.",
        ),
        "deleteData" to ToolSpec(
            domain = McpToolNames.SESSION_DOMAIN,
            method = "deleteData",
            arguments = listOf(ToolSpec.Arg("sessionId", "String", description = "Session whose user data is to be cleared")),
            returnType = "String",
            description = "Clear the session's browser cookies and its page's local/session storage.",
        ),
        "attach" to ToolSpec(
            domain = McpToolNames.SESSION_DOMAIN,
            method = "attach",
            arguments = listOf(
                ToolSpec.Arg("cdpEndpoint", "String", description = "CDP endpoint URL, e.g. http://127.0.0.1:9222"),
                ToolSpec.Arg("cdpPort", "Int", description = "CDP port of a local browser to attach to"),
                ToolSpec.Arg("extension", "Boolean", description = "Attach through the browser extension relay instead of CDP"),
                ToolSpec.Arg("channel", "String", description = "Expected browser channel/product, e.g. chrome (checked against the endpoint)"),
                ToolSpec.Arg("sessionId", "String", description = "Name for the attached session"),
            ),
            returnType = "String",
            description = "Attach a session to an already-running browser (CDP endpoint/port or the extension " +
                "relay) instead of launching one, and report the VERIFIED browser identity at that endpoint.",
        ),
        "checkReady" to ToolSpec(
            domain = McpToolNames.SESSION_DOMAIN,
            method = "checkReady",
            arguments = listOf(ToolSpec.Arg("sessionId", "String", description = "Session whose readiness is to be checked")),
            returnType = "String",
            description = "Report whether an extension-attached session is ready (its extension has connected).",
        ),
        "windowState" to ToolSpec(
            domain = McpToolNames.SESSION_DOMAIN,
            method = "windowState",
            arguments = listOf(ToolSpec.Arg("sessionId", "String", description = "Session whose browser window is to be observed")),
            returnType = "String",
            description = "Report whether THIS session's browser has a visible window: VISIBLE / HIDDEN / " +
                "UNKNOWN, with verified=false when the check could not be performed.",
        ),
    )
}
