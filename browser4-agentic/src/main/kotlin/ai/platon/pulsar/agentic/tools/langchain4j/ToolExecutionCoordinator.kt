package ai.platon.pulsar.agentic.tools.langchain4j

import ai.platon.pulsar.agentic.model.ToolOutcome
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolManager
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
            // Record inner-loop executions for the finish-report guard (they
            // don't create outer AgentStates).
            toolManager.notifyToolExecuted(tc.domain, tc.method)
            // Bounded ToolOutcome envelope instead of raw output — native
            // tool-calling results feed back into the conversation, so
            // unbounded Maven/read outputs blow up the context (observed
            // 90k-token requests). Header + truncated body + errors.
            ToolOutcome.from(result).render().take(5000)
        } catch (e: Exception) {
            "[fail] ${e.message?.take(300) ?: "tool execution failed"}"
        }

        return ToolExecutionResultMessage.from(
            request.id() ?: "",
            request.name(),
            resultText,
        )
    }
}
