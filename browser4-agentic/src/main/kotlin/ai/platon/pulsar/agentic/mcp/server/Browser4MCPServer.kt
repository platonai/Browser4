package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.mcp.McpToolAlias
import ai.platon.pulsar.agentic.mcp.McpToolNames
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.ToolResultTextRenderer
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.getLogger
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Browser4 MCP Server — exposes browser automation capabilities as MCP tools.
 *
 * This server allows external MCP clients (Claude Desktop, Cursor, Windsurf, etc.)
 * to drive a real browser through the Model Context Protocol.
 *
 * Tools are discovered dynamically from two disjoint sources, so the standard
 * server exposes the same capability surface as the private dispatcher:
 *
 * - the [AgentToolManager]'s registered executors — the built-in domains
 *   (`tab`, `browser`, `agent`, `coding`, `b4`, `system`, `skill`)
 * - [CustomToolRegistry] — the plugin and business domains registered through
 *   `ToolMount` (`command`, `crawl`, `swarm`, `webdb`, `html_snapshot`,
 *   `experience`, `memory`, markdown/images/media/pptx/seo/...)
 *
 * Every tool handler routes its call through [AgentToolManager.execute], matching
 * the internal agent execution path, and renders its result through
 * [ToolResultTextRenderer] so both MCP channels return identical text.
 *
 * @param toolManager The [AgentToolManager] to use for tool discovery and execution.
 * @param serverInfo MCP server identification (name and version).
 * @param customExecutors Supplies the plugin/business executors to merge in.
 *   Overridable so tests are not affected by the process-wide registry.
 * @param toolManagerResolver Decides which session a tool call runs against; see
 *   [ToolManagerResolver] for the single-session vs multi-session semantics.
 * @param frontendAliases The extra `browser_*` spellings to advertise alongside
 *   the canonical tools. Pass an empty list for a minimal, canonical-only tool
 *   list.
 * @param toolTargetResolver Supplies the receiver for custom-domain tools so an
 *   advertised tool is also an executable one; see [ToolTargetResolver].
 */
class Browser4MCPServer(
    private val toolManager: AgentToolManager,
    serverInfo: Implementation = Implementation(name = "browser4-mcp-server", version = "1.0.0"),
    private val customExecutors: () -> List<ToolExecutor> = { CustomToolRegistry.instance.getAllExecutors() },
    private val toolManagerResolver: ToolManagerResolver = ToolManagerResolver.single(toolManager),
    private val frontendAliases: List<McpToolAlias> = McpToolNames.frontendAliases,
    private val toolTargetResolver: ToolTargetResolver = ToolTargetResolver.NONE,
) {
    private val logger = getLogger(this)

    /** Advertised tools by MCP name; kept so calls can be routed after registration. */
    private val registrations = linkedMapOf<String, ToolRegistration>()

    val server: Server = Server(
        serverInfo = serverInfo,
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        ),
        instructions = serverInstructions(),
    ) {
        registerToolsFromManager(toolManager)
    }

    private fun serverInstructions(): String = buildString {
        appendLine("Browser4 MCP Server gives you full control over a real Chrome browser.")
        appendLine("Use the tools in order: navigate first, then interact, then read content.")
        appendLine("Always call wait_for_selector after actions that trigger page loads or dynamic updates.")
        appendLine("Every tool documents its arguments, defaults and examples in its input schema.")
        append(
            "When a tool's usage is unclear, call `help` with {domain, method} for the exact " +
                "signature and examples, or `skill_doc` with {name} (e.g. crawl.md, htmlsnapshot.md) " +
                "to read a bundled reference — prefer those over guessing."
        )
        if (toolManagerResolver.multiSession) {
            appendLine()
            appendLine()
            append(
                "This server can drive several browser sessions. Every tool accepts an optional " +
                    "'$SESSION_ID_PARAM' argument; pass a session id (e.g. one created by browser4-cli) " +
                    "to act on that session, or omit it to use the server's own session."
            )
        } else {
            appendLine()
            appendLine()
            append(
                "This server drives a single browser session shared by all connected clients, " +
                    "so there is no session handle to pass."
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers — build ToolSchema property descriptors
    // -------------------------------------------------------------------------

    private fun stringProp(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive(description)))

    private fun numberProp(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("number"), "description" to JsonPrimitive(description)))

    private fun intProp(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive(description)))

    private fun boolProp(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive(description)))

    private fun arrayProp(description: String, itemType: String = "string"): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("array"),
                "description" to JsonPrimitive(description),
                "items" to JsonObject(mapOf("type" to JsonPrimitive(itemType)))
            )
        )

    // -------------------------------------------------------------------------
    // Argument parsing
    // -------------------------------------------------------------------------

    private fun arg(arguments: JsonObject?, key: String): String? =
        arguments?.get(key)?.toString()?.trim('"')

    /** Reads the optional session handle a client may pass to any tool. */
    private fun sessionIdArg(arguments: JsonObject?): String? =
        arg(arguments, SESSION_ID_PARAM)?.takeIf { it.isNotBlank() }

    // -------------------------------------------------------------------------
    // Result helpers
    // -------------------------------------------------------------------------

    private fun textResult(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = text)))

    private fun errorResult(message: String): CallToolResult {
        logger.warn("MCP tool error: {}", message)
        return CallToolResult(content = listOf(TextContent(text = "ERROR: $message")), isError = true)
    }

    // -------------------------------------------------------------------------
    // Tool registration
    // -------------------------------------------------------------------------

    /** One advertised tool: the canonical domain/method plus the spec it was built from. */
    private data class ToolRegistration(
        val domain: String,
        val method: String,
        val spec: ToolSpec,
        val source: String,
    )

    /**
     * Register every MCP tool by discovering executors and their [ToolSpec]
     * metadata from [toolManager] and from [customExecutors].
     *
     * Built-in executors are collected first and win name conflicts, which are
     * logged rather than silently overwritten: both `skill` executors expose
     * `list`/`install`/`uninstall`, and the built-in
     * [ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor] is the
     * agent-facing one. Deduplicating here also keeps the registration safe on
     * newer MCP SDK releases, which reject duplicate tool names outright.
     */
    private fun Server.registerToolsFromManager(toolManager: AgentToolManager) {
        val builtInCount = toolManager.registeredExecutors.values.sumOf { mergeExecutor(it, SOURCE_BUILT_IN) }
        val customCount = customExecutors().sumOf { mergeExecutor(it, SOURCE_CUSTOM) }

        val aliasCount = registerFrontendAliases(registrations)

        logger.info(
            "Registered {} MCP tools ({} built-in, {} custom/plugin, {} frontend aliases)",
            registrations.size, builtInCount, customCount, aliasCount
        )
    }

    /**
     * Merge the tools of [executor] into the advertised set, skipping names that
     * are already taken.
     *
     * Built-in executors are merged first and win name conflicts, which are
     * logged rather than silently overwritten: both `skill` executors expose
     * `list`/`install`/`uninstall`, and the built-in
     * [ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor] is the
     * agent-facing one. Deduplicating here also keeps registration safe on newer
     * MCP SDK releases, which reject duplicate tool names outright.
     *
     * @return the number of tools added
     */
    private fun Server.mergeExecutor(executor: ToolExecutor, source: String): Int {
        var added = 0
        for ((method, spec) in executor.getToolSpecs()) {
            val name = McpToolNames.toMcpToolName(executor.domain, method)
            val existing = registrations[name]
            if (existing != null) {
                // Re-scanning the same executor is normal (see [refreshTools]);
                // only a genuine clash between two different tools is worth a log.
                if (existing.domain != executor.domain || existing.method != method) {
                    logger.info(
                        "MCP tool name conflict: '{}' ({}.{}) is shadowed by {}.{} — keeping the earlier registration",
                        name, executor.domain, method, existing.domain, existing.method
                    )
                }
                continue
            }
            registrations[name] = ToolRegistration(executor.domain, method, spec, source)
            addTool(
                name = name,
                description = describe(spec, "${executor.domain}.$method"),
                inputSchema = buildSchemaFromSpec(spec),
            ) { request ->
                invokeTool(name, request.params.arguments)
            }
            added++
        }
        return added
    }

    /**
     * The description a client sees: the tool's one-liner plus its first
     * executable example.
     *
     * MCP has no dedicated examples field and the SDK's `ToolSchema` exposes only
     * `properties`/`required`, so the description is the one place a usage sample
     * can travel in `tools/list` — which is what an agent actually reads.
     */
    private fun describe(spec: ToolSpec, fallback: String): String {
        val base = spec.description?.trim()?.ifBlank { null } ?: fallback
        val example = spec.examples.firstOrNull { it.executable } ?: return base
        val json = example.args.entries.joinToString(", ") { "\"${it.key}\": \"${it.value}\"" }
        return "$base Example: {$json}"
    }

    /**
     * Advertise tools from executors that appeared after this server was built.
     *
     * Plugin and business executors register through `ToolMount` during context
     * refresh, which in the Spring-hosted deployment happens **after** the MCP
     * server bean is constructed — without a refresh those domains would be
     * silently missing from `tools/list` (observed: the server logged
     * "0 custom/plugin" while `CustomToolRegistry` was populated four seconds
     * later). Calling this before serving a request also picks up executors
     * registered at runtime.
     *
     * Idempotent and cheap: known executors are skipped by name.
     *
     * @return the number of tools added by this call
     */
    fun refreshTools(): Int = synchronized(registrations) {
        val before = registrations.size
        customExecutors().forEach { server.mergeExecutor(it, SOURCE_CUSTOM) }
        server.registerFrontendAliases(registrations)
        val added = registrations.size - before
        if (added > 0) {
            logger.info("MCP tool list refreshed: +{} tools, {} total", added, registrations.size)
        }
        added
    }

    /**
     * Advertise the Playwright-MCP style `browser_*` names as additional
     * spellings of the canonical tools they map to.
     *
     * Agents trained on other browser MCP servers reach for `browser_click` /
     * `browser_type` first; without these aliases they get "unknown tool" even
     * though the capability exists. An alias whose canonical tool is not
     * registered is skipped, never registered as a dead entry.
     *
     * @return the number of aliases registered
     */
    private fun Server.registerFrontendAliases(canonical: Map<String, ToolRegistration>): Int {
        var registered = 0
        for (alias in frontendAliases) {
            val target = canonical[alias.canonicalName]
            if (target == null) {
                logger.debug(
                    "MCP alias '{}' skipped: canonical tool '{}' is not registered",
                    alias.frontendName, alias.canonicalName
                )
                continue
            }
            val existing = registrations[alias.frontendName]
            if (existing != null) {
                // Re-running this after a refresh is normal: the alias maps to the
                // very registration it points at (same instance). Only a real
                // clash — a canonical tool already owning the alias name — is worth
                // reporting, otherwise every served request would log 40+ lines.
                if (existing !== target) {
                    logger.info(
                        "MCP alias '{}' skipped: the name is already taken by {}.{}",
                        alias.frontendName, existing.domain, existing.method
                    )
                }
                continue
            }
            // Registered under its own name so a call routed by name (the SDK
            // handler and `invokeTool`) resolves to the same canonical tool.
            registrations[alias.frontendName] = target
            addTool(
                name = alias.frontendName,
                description = describe(target.spec, "${target.domain}.${target.method}") +
                    " (Alias of '${alias.canonicalName}'.)",
                inputSchema = buildSchemaFromSpec(target.spec),
            ) { request ->
                invokeTool(alias.frontendName, request.params.arguments)
            }
            registered++
        }
        return registered
    }

    /**
     * Execute an advertised tool by name, exactly as the MCP layer would.
     *
     * The registered SDK handler delegates here. In-process callers (and tests)
     * use it directly, which keeps them independent of the SDK's handler
     * signature and avoids fabricating a [io.modelcontextprotocol.kotlin.sdk.server.ClientConnection].
     *
     * @param toolName the advertised name (`navigate`, `browser_navigate`, `webdb_export`, …)
     * @param arguments the raw JSON arguments, including the optional `sessionId` handle
     */
    internal suspend fun invokeTool(toolName: String, arguments: JsonObject? = null): CallToolResult {
        val registration = registrations[toolName]
            ?: return errorResult("Unknown tool: $toolName")
        return callTool(toolName, registration, arguments)
    }

    /**
     * Execute one advertised tool.
     *
     * The tool manager is resolved per call so a multi-session server honors the
     * client's optional session handle; a single-session server returns its one
     * bound manager. An unknown handle is reported as a tool error instead of
     * silently acting on the wrong browser.
     *
     * Custom-domain tools additionally need a receiver; the configured
     * [ToolTargetResolver] binds it so an advertised tool is an executable one.
     */
    private suspend fun callTool(
        toolName: String,
        registration: ToolRegistration,
        arguments: JsonObject?,
    ): CallToolResult {
        val sessionId = sessionIdArg(arguments)
        val resolvedManager = toolManagerResolver.resolve(sessionId)
            ?: return errorResult("Session not found: $sessionId")

        bindCustomDomainTarget(registration, sessionId, resolvedManager)

        val args = buildArgsMap(arguments, registration.spec)
        val toolCall = ToolCall(
            domain = registration.domain,
            method = registration.method,
            arguments = args.toMutableMap(),
        )

        return runCatching { resolvedManager.execute(toolCall) }
            .fold(
                onSuccess = { result ->
                    val evaluate = result.evaluate
                    val exception = evaluate.exception
                    if (exception != null) {
                        errorResult("$toolName failed: ${exception.cause?.message ?: exception.expression}")
                    } else {
                        textResult(ToolResultTextRenderer.render(evaluate))
                    }
                },
                onFailure = { errorResult("$toolName failed: ${it.message}") }
            )
    }

    /**
     * Bind the receiver a custom-domain tool needs, when the deployment can
     * supply one.
     *
     * Built-in domains resolve their own receiver inside [AgentToolManager]
     * (`tab` → driver, `fs`, `coding`, …); only registry-sourced domains consult
     * the custom-target map. Nothing happens when no resolver is configured.
     */
    private fun bindCustomDomainTarget(
        registration: ToolRegistration,
        sessionId: String?,
        manager: AgentToolManager,
    ) {
        if (registration.source != SOURCE_CUSTOM) return
        if (toolTargetResolver === ToolTargetResolver.NONE) return

        val executor = customExecutors().firstOrNull { it.domain == registration.domain } ?: return
        val target = runCatching { toolTargetResolver.resolve(executor, sessionId) }
            .onFailure {
                logger.warn(
                    "Tool target resolution failed | domain={} | {}",
                    registration.domain, it.message
                )
            }
            .getOrNull()
            ?: return
        manager.registerCustomTarget(registration.domain, target)
    }

    /**
     * Build a [ToolSchema] from a [ToolSpec], mapping Kotlin type names to JSON Schema types.
     *
     * Each property carries the argument's documented meaning (falling back to its
     * callable form, `depth: Int = 1`) and its declared default — a client that
     * only reads `tools/list` can therefore call the tool without guessing.
     * Naming the property instead of describing it (`"url": {"description": "url"}`)
     * was the old behaviour and told an agent nothing.
     *
     * Every tool also accepts the optional [SESSION_ID_PARAM] handle; it is read
     * separately from the spec arguments and is never required.
     */
    private fun buildSchemaFromSpec(spec: ToolSpec): ToolSchema {
        val props = linkedMapOf<String, JsonObject>()
        for (argSpec in spec.arguments) {
            props[argSpec.name] = typeToJsonProp(argSpec)
        }
        props[SESSION_ID_PARAM] = stringProp(sessionIdDescription())

        val required = spec.arguments
            .filter { it.defaultValue == null }
            .map { it.name }

        return ToolSchema(
            properties = JsonObject(props),
            required = required.ifEmpty { null },
        )
    }

    /** How the injected `sessionId` argument documents itself for this server. */
    private fun sessionIdDescription(): String = if (toolManagerResolver.multiSession) {
        "Session handle to run this tool against, e.g. a session id created by browser4-cli. " +
            "Omit it to use the server's own session."
    } else {
        "Ignored: this server drives a single browser session shared by all clients."
    }

    /**
     * Map one declared argument to a JSON Schema property descriptor.
     */
    private fun typeToJsonProp(argSpec: ToolSpec.Arg): JsonObject {
        val name = argSpec.name
        val description = argSpec.description?.takeIf { it.isNotBlank() } ?: argSpec.expression
        val typed = typeToJsonProp(argSpec.type, description)
        val default = argSpec.defaultValue?.let { defaultValueProp(it, argSpec.type) }
        return if (default == null) typed else JsonObject(typed + ("default" to default))
    }

    /**
     * Render a declared default with the JSON type its argument declares, so
     * `depth: Int = 1` becomes `"default": 1` and not the string `"1"`.
     */
    private fun defaultValueProp(raw: String, type: String): JsonPrimitive {
        val normalised = type.trimEnd('?').trim().lowercase()
        return when (normalised) {
            "int", "integer", "long", "short" -> raw.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)
            "double", "float", "number" -> raw.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)
            "boolean", "bool" -> raw.toBooleanStrictOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(raw)
            else -> JsonPrimitive(raw)
        }
    }

    /**
     * Map a Kotlin type string to a JSON Schema property descriptor.
     */
    private fun typeToJsonProp(type: String, description: String): JsonObject {
        val normalised = type.trimEnd('?').trim()
        return when {
            normalised.startsWith("List<") || normalised.startsWith("Array<") -> arrayProp(description)
            normalised.lowercase() in setOf("string") -> stringProp(description)
            normalised.lowercase() in setOf("int", "integer", "long", "short") -> intProp(description)
            normalised.lowercase() in setOf("double", "float", "number") -> numberProp(description)
            normalised.lowercase() in setOf("boolean", "bool") -> boolProp(description)
            else -> stringProp(description)
        }
    }

    /**
     * Extract the argument values from the MCP request's JSON arguments object into
     * a plain [Map] suitable for [ToolCall.arguments].
     *
     * Declared spec arguments are type-coerced, **and everything else the client
     * sent is forwarded unchanged** (except the session handle, which [callTool]
     * already consumed). Dropping undeclared arguments silently broke the most
     * basic calls: `tab.navigate` declares `userTypedUrl`/`entry`, so a client
     * sending the natural `{"url": …}` (which the driver accepts, and which the
     * private dispatcher forwards) had its URL discarded and got
     * "navigate requires 'url' or ('rawUrl','pageUrl')". Both channels now behave
     * the same; an executor that rejects extra arguments says so explicitly.
     */
    private fun buildArgsMap(arguments: JsonObject?, spec: ToolSpec): Map<String, Any?> {
        if (arguments == null) return emptyMap()
        val declared = spec.arguments.associateBy { it.name }

        val mapped = linkedMapOf<String, Any?>()
        for ((key, element) in arguments) {
            if (key == SESSION_ID_PARAM) continue
            mapped[key] = coerceArgument(element, declared[key])
        }
        return mapped
    }

    /**
     * Convert one JSON argument: a declared argument follows its spec type, an
     * undeclared one keeps its natural JSON value (objects/arrays are passed as
     * their JSON text, which is what the executors parse).
     */
    private fun coerceArgument(element: JsonElement, argSpec: ToolSpec.Arg?): Any? {
        val raw = element.toString().trim('"')
        if (argSpec == null) return naturalValue(element)

        return when (argSpec.type.trimEnd('?').lowercase()) {
            "int", "integer" -> raw.toIntOrNull() ?: raw
            "long" -> raw.toLongOrNull() ?: raw
            "double", "float" -> raw.toDoubleOrNull() ?: raw
            "boolean", "bool" -> raw.toBooleanStrictOrNull() ?: raw
            else -> naturalValue(element)
        }
    }

    private fun naturalValue(element: JsonElement): Any? = when (element) {
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
        // Objects/arrays travel as JSON text: executors that declare a structured
        // parameter either parse the string or accept the raw value.
        else -> element.toString()
    }

    private companion object {
        /** Optional per-call session handle injected into every tool schema. */
        const val SESSION_ID_PARAM = "sessionId"

        const val SOURCE_BUILT_IN = "built-in"
        const val SOURCE_CUSTOM = "custom"
    }
}
