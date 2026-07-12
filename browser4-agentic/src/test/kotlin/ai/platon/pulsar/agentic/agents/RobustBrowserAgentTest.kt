package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.chrome.dom.model.BrowserUseState
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RobustBrowserAgentTest {
    private val session = mockk<AgenticSession>(relaxed = true)

    @Test
    @DisplayName("test last executed tool call comes from previous agent state")
    fun testLastExecutedToolCallComesFromPreviousAgentState() = runBlocking {
        val previousToolCall = ToolCall("tab", "click", mutableMapOf("selector" to "#submit"))
        val previousActionDescription = ActionDescription(
            instruction = "click submit",
            observeElements = listOf(ObserveElement(toolCall = previousToolCall))
        )
        val previousAgentState = AgentState(
            step = 1,
            instruction = "click submit",
            browserUseState = BrowserUseState.DUMMY,
        ).apply {
            toolCallResult = ToolCallResult(
                evaluate = TcEvaluate(expression = previousToolCall.pseudoExpression),
                actionDescription = previousActionDescription
            )
        }
        val currentAgentState = AgentState(
            step = 2,
            instruction = "click submit",
            browserUseState = BrowserUseState.DUMMY,
            prevState = previousAgentState
        )
        val context = ExecutionContext(
            step = 2,
            instruction = "click submit",
            event = "step",
            agentState = currentAgentState,
            stateHistory = AgentHistory(),
            config = AgentConfig(),
            sessionId = "session-id"
        )

        val actual = TestRobustBrowserAgent(session).resolveLastExecutedToolCall(context)

        assertEquals(previousToolCall.pseudoExpression, actual?.pseudoExpression)
    }

    private class TestRobustBrowserAgent(session: AgenticSession) : RobustBrowserAgent(session) {
        fun resolveLastExecutedToolCall(context: ExecutionContext): ToolCall? = lastExecutedToolCall(context)
    }
}
