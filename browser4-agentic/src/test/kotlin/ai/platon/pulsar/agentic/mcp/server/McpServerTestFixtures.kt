package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass

/**
 * A tool executor with declared specs and no real behaviour, for registration and
 * routing tests. Its tools return `"<domain>.<method>"`.
 */
internal class FakeToolExecutor(
    override val domain: String,
    methods: List<String>,
    arguments: List<ToolSpec.Arg> = emptyList(),
) : AbstractToolExecutor() {
    override val receiverClass: KClass<*> = FakeToolExecutor::class

    init {
        for (method in methods) {
            toolSpec[method] = ToolSpec(
                domain = domain,
                method = method,
                arguments = arguments,
                returnType = "String",
                description = "Test tool $domain.$method",
            )
        }
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any,
    ): Any? = "$domain.$functionName"
}

/** Build a `tools/call` request from plain string arguments. */
internal fun mcpToolRequest(
    toolName: String,
    arguments: Map<String, String> = emptyMap(),
): CallToolRequest {
    val jsonArgs = JsonObject(arguments.mapValues { JsonPrimitive(it.value) })
    return CallToolRequest(params = CallToolRequestParams(name = toolName, arguments = jsonArgs))
}

/** The text of a tool result, or `null` when it carries non-text content. */
internal fun toolResultText(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String? =
    (result.content.firstOrNull() as? io.modelcontextprotocol.kotlin.sdk.types.TextContent)?.text

/** Build an [AgentToolManager.execute] result carrying [value]. */
internal fun mcpToolCallResult(value: Any? = null, evaluate: TcEvaluate? = null): ToolCallResult {
    val resolvedEvaluate = evaluate ?: TcEvaluate(value = value)
    return ToolCallResult(
        evaluate = resolvedEvaluate,
        message = resolvedEvaluate.exception?.message,
    )
}
