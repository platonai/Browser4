package ai.platon.pulsar.agentic.tools.langchain4j

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.*

/**
 * Converts LangChain4j [ToolExecutionRequest]s into Browser4 [ToolCall]s.
 *
 * Arguments arrive as a JSON string (the native function-calling payload).
 * We parse them with Jackson (the same [Pson] mapper used everywhere in the
 * module) rather than a hand-rolled parser.
 */
object ToolCallConverter {

    /**
     * Convert a [ToolExecutionRequest] into a [ToolCall].
     *
     * @param request  The incoming request from the LLM.
     * @param registry Tool-name → [ToolSpec] map built by
     *                 [ToolSpecificationConverter.toRegistry].
     *                 If the name is found the exact `(domain, method)` is used;
     *                 otherwise we fall back to `split("_", limit = 2)`.
     * @return A [ToolCall] ready for [AgentToolManager.execute].
     */
    fun toToolCall(
        request: dev.langchain4j.agent.tool.ToolExecutionRequest,
        registry: Map<String, ToolSpec>,
    ): ToolCall {
        val (domain, method) = resolve(request.name(), registry)
        val arguments = parseArguments(request.arguments())
        return ToolCall(
            domain = domain,
            method = method,
            arguments = arguments,
            description = null,
        )
    }

    // -- internal ------------------------------------------------------------

    private fun resolve(
        name: String,
        registry: Map<String, ToolSpec>,
    ): Pair<String, String> {
        // Exact match (preferred)
        registry[name]?.let { return it.domain to it.method }

        // Fallback: split on last underscore (method may itself contain
        // underscores, so we split on the LAST one).
        val lastUnderscore = name.lastIndexOf('_')
        if (lastUnderscore > 0 && lastUnderscore < name.length - 1) {
            val domain = name.substring(0, lastUnderscore)
            val method = name.substring(lastUnderscore + 1)
            return domain to method
        }

        throw IllegalArgumentException(
            "Unknown tool name '$name' — not found in registry " +
                "and cannot be parsed as domain_method"
        )
    }

    internal fun parseArguments(json: String): MutableMap<String, Any?> {
        if (json.isBlank()) return mutableMapOf()

        return try {
            val node = pulsarObjectMapper().readTree(json)
            if (node is ObjectNode) {
                nodeToMap(node)
            } else {
                mutableMapOf()
            }
        } catch (e: Exception) {
            // Tolerate malformed JSON — the executor will report the error
            mutableMapOf()
        }
    }

    private fun nodeToMap(node: ObjectNode): MutableMap<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        node.fields().forEach { (key, value) ->
            map[key] = nodeToValue(value)
        }
        return map
    }

    private fun nodeToValue(node: JsonNode): Any? {
        return when {
            node.isNull -> null
            node.isBoolean -> node.booleanValue()
            node.isInt -> node.intValue()
            node.isLong -> node.longValue()
            node.isDouble -> node.doubleValue()
            node.isTextual -> node.textValue()
            node.isArray -> {
                node.map { nodeToValue(it) }
            }
            node.isObject -> {
                val map = mutableMapOf<String, Any?>()
                node.fields().forEach { (k, v) -> map[k] = nodeToValue(v) }
                map
            }
            else -> node.asText()
        }
    }
}
