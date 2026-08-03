package ai.platon.pulsar.agentic.tools.langchain4j

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.common.brief
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.ToolExecutionResultMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Executes LangChain4j [ToolExecutionRequest]s by routing them through
 * [AgentToolManager.execute].
 *
 * Tool calls are converted via [ToolCallConverter], which resolves the
 * `(domain, method)` pair from the [ToolExecutionRequest] name using a
 * reverse registry.
 *
 * Because [AgentToolManager.execute] is `suspend` and the LangChain4j
 * tool-execution path is blocking, a [runBlocking] bridge is used
 * (consistent with the rest of the codebase).
 */
class ToolExecutionCoordinator(
    private val toolManager: AgentToolManager,
    private val registry: Map<String, ToolSpec>,
) {

    /**
     * Execute a single [ToolExecutionRequest] and return the result
     * formatted for inclusion in a [ToolExecutionResultMessage].
     *
     * @param request The incoming tool-call request from the LLM.
     * @return A [ToolExecutionResultMessage] containing the execution
     *         output or error text.
     */
    fun execute(request: ToolExecutionRequest): ToolExecutionResultMessage {
        val resultText = try {
            val tc = ToolCallConverter.toToolCall(request, registry)
            val result = runBlocking(Dispatchers.IO) {
                toolManager.execute(tc)
            }
            val evaluate = result.evaluate
            if (evaluate.success) {
                val value = evaluate.value?.toString() ?: evaluate.description ?: "OK"
                "[Succeeded] ${value.take(5000)}"
            } else {
                val cause = evaluate.exception?.cause?.brief()
                    ?: evaluate.exception?.message ?: "unknown error"
                val help = evaluate.exception?.help
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "\nHelp: $it" } ?: ""
                "[Error] $cause$help"
            }
        } catch (e: Exception) {
            "[Error] ${e.message ?: "tool execution failed"}"
        }

        return ToolExecutionResultMessage.from(
            request.id() ?: "",
            request.name(),
            resultText,
        )
    }
}
