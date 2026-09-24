package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.ExtractResult
import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper

/**
 * Renders a tool-call evaluation result into the plain text that an MCP client
 * receives as `content[0].text` of a tool result.
 *
 * Two surfaces invoke the very same tools, and they must produce the very same
 * text for the same call:
 *
 * - the standard MCP server — `Browser4MCPServer` (stdio / HTTP+SSE, port 8088)
 * - the private MCP dispatcher — `MCPToolController` (`POST /mcp/call-tool`, port 18182)
 *
 * Keeping the rendering in one place is what makes a tool's output portable
 * between the two channels. Before this existed, the standard server returned
 * `value.toString()` — so a `List<Map<...>>` reached the model as the JVM form
 * `{index=0, guid=...}` — while the REST dispatcher emitted JSON.
 */
object ToolResultTextRenderer {
    private val logger = getLogger(this)

    /**
     * Render [value] as tool-result text.
     *
     * @param value the evaluated value of the tool call
     * @param className the JS class name reported by the evaluation, used to
     *   tell JS `null` (rendered as the literal `"null"`) apart from JS
     *   `undefined` and Kotlin `Unit` (both rendered as an empty string)
     */
    fun render(value: Any?, className: String? = null): String = when (value) {
        null -> if (className == "null") "null" else ""
        is String -> value
        is Number, is Boolean -> value.toString()
        is ExtractResult -> renderExtractResult(value)
        is Map<*, *>, is Collection<*>, is Array<*> -> toJson(value)
        // Non-serializable domain objects (WebDriver, Browser, ...) — wrap them in
        // a description object so internal object graphs are never exposed.
        else -> toJson(
            mapOf(
                "type" to (className ?: value::class.qualifiedName),
                "description" to value.toString(),
            )
        )
    }

    /**
     * Render the result of an evaluation envelope, preserving the null/undefined
     * distinction described in [render].
     */
    fun render(evaluate: TcEvaluate): String = render(evaluate.value, evaluate.className)

    /**
     * Render an [ExtractResult] as clean JSON with `success`, `message` and
     * `data` fields instead of leaking the wrapper's internals.
     */
    private fun renderExtractResult(result: ExtractResult): String = toJson(
        mapOf(
            "success" to result.success,
            "message" to result.message,
            "data" to result.data,
        )
    )

    private fun toJson(value: Any?): String = runCatching {
        pulsarObjectMapper().writeValueAsString(value)
    }.getOrElse { e ->
        // A tool that succeeded must not fail because its return value cannot be
        // serialized; degrade to the description form and keep the cause visible.
        logger.debug("Failed to serialize tool result of type {}: {}", value?.javaClass?.name, e.message)
        value?.toString() ?: ""
    }
}
