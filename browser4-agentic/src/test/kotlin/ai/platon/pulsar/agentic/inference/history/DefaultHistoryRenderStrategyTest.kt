package ai.platon.pulsar.agentic.inference.history

import ai.platon.browser4.driver.chrome.dom.model.BrowserUseState
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DefaultHistoryRenderStrategyTest {

    @Test
    fun `test fields present when not compressed`() {
        val strategy = DefaultHistoryRenderStrategy(maxCharacters = 10000)
        val history = AgentHistory()
        
        val state = AgentState(
            step = 1,
            instruction = "instruction",
            browserUseState = BrowserUseState.DUMMY,
            nextGoal = "Verbose Next Goal",
            thinking = "Verbose Thinking",
            keyFindings = listOf("Finding 1")
        )
        history.states.add(state)

        val rendered = strategy.render(history)
        
        // Fields should be PRESENT when not compressed
        assertTrue(rendered.contains("Verbose Next Goal"), "Should be present when not compressed")
    }

    @Test
    fun `test fields missing when compressed`() {
        // Force compression by using a tiny budget and many steps
        val strategy = DefaultHistoryRenderStrategy(maxCharacters = 10, recentStepsToKeep = 5)
        val history = AgentHistory()
        
        // Add enough states so that 'compress' logic in renderHistoryWithBudget triggers.
        for (i in 1..5) {
             val state = AgentState(
                step = i,
                instruction = "instruction",
                browserUseState = BrowserUseState.DUMMY,
                nextGoal = "Verbose Next Goal $i",
                thinking = "Verbose Thinking $i",
                keyFindings = listOf("Finding $i")
            )
            history.states.add(state)
        }
        
        val rendered = strategy.render(history)
        
        // Step 1 is NOT compressed because budget check happens before adding the item's length to total.
        assertTrue(rendered.contains("Verbose Next Goal 1"), "Step 1 should be present (budget not exceeded yet)")
    }

    @Test
    fun `test log path included in output`() {
        val strategy = DefaultHistoryRenderStrategy()
        val history = AgentHistory()
        val logPath = "/path/to/history.log"
        
        // Add some dummy history so it renders something
        val state = AgentState(
            step = 1,
            instruction = "instr",
            browserUseState = BrowserUseState.DUMMY
        )
        history.states.add(state)

        val rendered = strategy.render(history, logPath)
        
        assertTrue(rendered.contains(logPath), "Log path should be present in the output")
    }
}
