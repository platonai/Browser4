package ai.platon.browser4.cli

import kotlin.system.exitProcess

/**
 * Browser4 CLI — drive a Browser4 server from the command line.
 *
 * Most operations are routed through the Browser4 MCP Server tool interface
 * via `POST /mcp/call-tool`.
 *
 * Mirrors the Rust [main.rs] entry point and command dispatch.
 */
object Cli {

    /** Resolved from `cli/VERSION-CLI` at build time; falls back to this constant. */
    const val VERSION = "0.1.0"

    @JvmStatic
    fun main(vararg rawArgs: String) {
        val global = parseGlobalFlags(rawArgs.toList())

        try {
            run(global)
        } catch (e: CliError) {
            if (global.json) {
                println("""{"message": "${e.message}", "code": "${e.code.name}"}""")
            } else {
                System.err.println("Error: ${e.message}")
            }
            exitProcess(e.code.code)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            exitProcess(ExitCode.General.code)
        }
    }
}

private fun run(global: GlobalFlags) {
    val command = global.args.firstOrNull() ?: ""
    val cmdMap = commandsMap()

    // -- help / no command --
    if (command.isEmpty() || command == "help") {
        val helpTarget = global.args.getOrNull(1)
        if (helpTarget != null) {
            val cmd = cmdMap[helpTarget]
            if (cmd != null) {
                println(generateCommandHelp(cmd))
            } else {
                println("Unknown command: $helpTarget")
                println(generateHelp())
            }
        } else {
            println(generateHelp())
        }
        return
    }

    // -- version --
    if (command == "--version" || command == "-v" || command == "version") {
        println("browser4-cli-kotlin ${Cli.VERSION}")
        return
    }

    // Resolve base URL and state
    val state = CliStateManager.readState(sessionName = global.sessionName)
    val baseUrl = global.serverUrl ?: state.baseUrl

    // Find command definition
    val cmdDef = cmdMap[command] ?: run {
        println("Unknown command: $command")
        println(generateHelp())
        return
    }

    // Build short-to-long option mapping
    val shortToLong = cmdDef.options
        .filter { it.short != null }
        .associate { it.short!! to it.name }

    // Parse arguments
    val rawParsed = parseRawArgs(global.args, shortToLong)
    val argNames = cmdDef.args.map { it.name }
    val parsed = buildCommandArgs(rawParsed, argNames)

    // Resolve MCP tool name and params
    val toolName = cmdDef.toolNameFn(parsed)
    val toolParams = cmdDef.toolParamsFn(parsed)

    // -------- Command dispatch --------

    when (command) {
        "open"      -> handleOpen(baseUrl, parsed, global.sessionName)
        "goto"      -> handleGoto(baseUrl, parsed, global.sessionName)
        "close"     -> handleClose(baseUrl, global.sessionName)
        "list"      -> handleList(baseUrl, global.sessionName)
        "snapshot"  -> handleSnapshot(baseUrl, parsed, toolName, toolParams, global.sessionName)
        "screenshot" -> handleScreenshot(baseUrl, parsed, toolName, toolParams, global.sessionName)
        "status"    -> handleStatus(baseUrl)
        else -> {
            if (toolName.isNotEmpty()) {
                handleSimpleToolCall(baseUrl, toolName, toolParams, global.sessionName)
            } else {
                println("Command '$command' is not yet implemented in the Kotlin CLI.")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Session helpers
// ---------------------------------------------------------------------------

/**
 * Returns an existing session ID or creates a new one via `open_session`.
 */
private fun getOrCreateSessionId(baseUrl: String, sessionName: String?): String {
    val state = CliStateManager.readState(sessionName = sessionName)
    state.sessionId?.let { return it }

    // Create a fresh session
    val sessionLabel = sessionName ?: "default"
    val capabilities = mapOf("sessionId" to sessionLabel)
    val result = McpClient.callTool(baseUrl, null, "open_session", mapOf("capabilities" to capabilities))

    val sessionId = result.getOrElse { e ->
        throw CliError(ExitCode.Session, "Failed to open session: ${e.message}")
    }

    val newState = state.copy(sessionId = sessionId, baseUrl = baseUrl, activeSelector = null)
    CliStateManager.writeState(newState, sessionName = sessionName)
    return sessionId
}

// ---------------------------------------------------------------------------
// Command handlers
// ---------------------------------------------------------------------------

private fun handleOpen(baseUrl: String, args: Map<String, String>, sessionName: String?) {
    val sessionId = getOrCreateSessionId(baseUrl, sessionName)
    val url = args["url"] ?: "about:blank"
    println("Session opened: $sessionId")

    if (url.isNotBlank() && url != "about:blank") {
        val result = McpClient.callTool(baseUrl, sessionId, "browser_navigate", mapOf("url" to url))
        result.onSuccess { text -> if (text.isNotBlank()) println(text) }
        result.onFailure { e -> System.err.println("Navigation failed: ${e.message}") }
    }
}

private fun handleGoto(baseUrl: String, args: Map<String, String>, sessionName: String?) {
    val sessionId = getOrCreateSessionId(baseUrl, sessionName)
    val url = args["url"] ?: throw CliError(ExitCode.Usage, "url is required")

    val result = McpClient.callTool(baseUrl, sessionId, "browser_navigate", mapOf("url" to url))
    result.onSuccess { text -> if (text.isNotBlank()) println(text) }
    result.onFailure { e -> System.err.println("Navigation failed: ${e.message}") }
}

private fun handleClose(baseUrl: String, sessionName: String?) {
    val state = CliStateManager.readState(sessionName = sessionName)
    val sessionId = state.sessionId
    if (sessionId == null) {
        System.err.println("No active session.")
        return
    }

    McpClient.callTool(baseUrl, sessionId, "close_session", emptyMap())
    CliStateManager.clearState(sessionName = sessionName)
    println("Session closed.")
}

private fun handleList(baseUrl: String, sessionName: String?) {
    val state = CliStateManager.readState(sessionName = sessionName)
    println("Base URL: ${state.baseUrl}")
    println("Session ID: ${state.sessionId ?: "none"}")

    val result = McpClient.get(baseUrl, "api/system/sessions")
    result.onSuccess { data -> println("Active sessions: $data") }
    result.onFailure { println("Sessions: unknown (server unreachable)") }
}

private fun handleSnapshot(
    baseUrl: String,
    args: Map<String, String>,
    toolName: String,
    toolParams: Map<String, Any>,
    sessionName: String?,
) {
    handleSimpleToolCall(baseUrl, toolName, toolParams, sessionName)
}

private fun handleScreenshot(
    baseUrl: String,
    args: Map<String, String>,
    toolName: String,
    toolParams: Map<String, Any>,
    sessionName: String?,
) {
    val sessionId = getOrCreateSessionId(baseUrl, sessionName)
    val result = McpClient.callTool(baseUrl, sessionId, toolName, toolParams)
    result.onSuccess { data ->
        val path = SnapshotManager.resolveOutputPath(
            args["filename"], "screenshot", "png"
        )
        SnapshotManager.saveBinary(path, data.toByteArray())
        println("Screenshot saved: ${path.toAbsolutePath()}")
    }
    result.onFailure { e -> System.err.println("Screenshot failed: ${e.message}") }
}

private fun handleStatus(baseUrl: String) {
    val result = McpClient.get(baseUrl, "actuator/health")
    result.onSuccess { data -> println("Server status: $data") }
    result.onFailure { println("Server is not reachable at $baseUrl") }
}

/**
 * Generic dispatcher for commands that map directly to a single MCP tool call.
 */
private fun handleSimpleToolCall(
    baseUrl: String,
    tool: String,
    args: Map<String, Any>,
    sessionName: String?,
) {
    if (tool.isBlank()) return
    val sessionId = getOrCreateSessionId(baseUrl, sessionName)
    val result = McpClient.callTool(baseUrl, sessionId, tool, args)
    result.onSuccess { text -> println(text) }
    result.onFailure { e -> System.err.println("$tool failed: ${e.message}") }
}
