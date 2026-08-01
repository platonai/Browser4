package ai.platon.pulsar.agentic.tools.langchain4j

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import dev.langchain4j.agent.tool.ToolSpecification as LangChain4jToolSpec
import dev.langchain4j.agent.tool.ToolExecutionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Typed bridge between LangChain4j's native tool-calling protocol and
 * Browser4's [AgentToolManager].
 *
 * ## Usage
 *
 * ```kotlin
 * val adapter = LangChain4jToolAdapter(toolManager)
 *
 * // Pass these to the LLM via ChatRequest
 * val specs = adapter.toolSpecifications
 *
 * // Execute incoming tool calls
 * val result = adapter.execute(request) // suspend
 * ```
 *
 * This replaces the old reflection-based adapter that used `Any`-typed
 * toolManager and a hand-rolled JSON parser.  The new implementation is
 * fully typed, uses Jackson for argument parsing, and delegates execution
 * through [AgentToolManager.execute] (which normalises domains, aliases,
 * and positional→named arguments).
 */
class LangChain4jToolAdapter(
    private val toolManager: AgentToolManager,
) {
    private val logger = getLogger(LangChain4jToolAdapter::class)

    /** All tool specifications (built-in + custom), ready for LangChain4j. */
    val toolSpecifications: List<LangChain4jToolSpec> by lazy {
        val specs = collectSpecs()
        ToolSpecificationConverter.toToolSpecifications(specs)
    }

    /** Reverse index: tool name → [ToolSpec] for request decoding. */
    val toolRegistry: Map<String, ToolSpec> by lazy {
        val specs = collectSpecs()
        ToolSpecificationConverter.toRegistry(specs)
    }

    /**
     * Execute a [ToolExecutionRequest] received from the LLM.
     *
     * Routes through [AgentToolManager.execute] so that domain aliasing,
     * argument normalisation, and post-call hooks (switchTab, closeTab,
     * navigate) all apply identically to text-parsed and native tool calls.
     *
     * @return A compact string representation of the result, suitable for
     *         inclusion in a [ToolExecutionResultMessage].
     */
    suspend fun execute(request: ToolExecutionRequest): String {
        val tc = ToolCallConverter.toToolCall(request, toolRegistry)

        logger.debug("Executing native tool call: ${tc.domain}.${tc.method}")

        val result = toolManager.execute(tc)
        val evaluate = result.evaluate

        return if (evaluate.success) {
            val value = evaluate.value?.toString() ?: evaluate.description ?: "OK"
            "[Execution Succeeded] Output: ${value.take(5000)}"
        } else {
            val cause = evaluate.exception?.cause?.brief() ?: evaluate.exception?.message ?: "unknown"
            val help = evaluate.exception?.help?.takeIf { it.isNotBlank() }?.let { "\nHelp: $it" } ?: ""
            "[Execution Error] $cause$help"
        }
    }

    /**
     * Synchronous convenience for callers that need a blocking bridge
     * (e.g. LC4j [ToolExecutor]).
     *
     * @see execute
     */
    fun executeBlocking(request: ToolExecutionRequest): String {
        return runBlocking(Dispatchers.IO) { execute(request) }
    }

    // -- internal ------------------------------------------------------------

    private fun collectSpecs(): List<ToolSpec> {
        return toolManager.getAllToolSpecs().values.flatMap { it.values } +
            CustomToolRegistry.instance.getAllToolCallSpecifications()
    }
}
