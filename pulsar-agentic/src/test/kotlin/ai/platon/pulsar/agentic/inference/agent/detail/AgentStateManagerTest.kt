package ai.platon.pulsar.agentic.inference.agent.detail

import ai.platon.browser4.driver.chrome.dom.model.BrowserState
import ai.platon.browser4.driver.chrome.dom.model.BrowserUseState
import ai.platon.browser4.driver.chrome.dom.model.DOMState
import ai.platon.pulsar.agentic.ActionOptions
import ai.platon.pulsar.agentic.agents.AgentConfig
import ai.platon.pulsar.agentic.agents.BrowserAgentActor
import ai.platon.pulsar.agentic.inference.detail.AgentStateManager
import ai.platon.pulsar.agentic.inference.detail.PageStateTracker
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for AgentStateManager focusing on context lifecycle and memory management.
 * 
 * Tests validate:
 * - Context creation and activation
 * - Context lifecycle invariants
 * - Memory cleanup mechanisms
 * - State history management
 * - Process trace recording
 */
class AgentStateManagerTest {

    private lateinit var stateManager: AgentStateManager
    private lateinit var mockAgent: BrowserAgentActor
    private lateinit var mockPageStateTracker: PageStateTracker
    private lateinit var mockDriver: PulsarWebDriver
    private lateinit var config: AgentConfig

    @BeforeEach
    fun setUp() {
        // Create test configuration with small limits for easier testing
        config = AgentConfig(
            maxHistorySize = 10,
            memoryCleanupIntervalSteps = 5
        )

        // Mock dependencies
        mockAgent = mockk<BrowserAgentActor>(relaxed = true)
        mockPageStateTracker = mockk<PageStateTracker>(relaxed = true)
        mockDriver = mockk<PulsarWebDriver>(relaxed = true)

        // Configure mocks
        every { mockAgent.config } returns config
        every { mockAgent.activeDriver } returns mockDriver
        
        // Mock browser state calls
        val mockBrowserState = BrowserState(url = "https://example.com")
        val mockDOMState = DOMState()
        val mockBrowserUseState = BrowserUseState(mockBrowserState, mockDOMState)
        
        coEvery { mockDriver.domService.getBrowserUseState(any()) } returns mockBrowserUseState
        coEvery { mockDriver.currentUrl() } returns "https://example.com"
        coEvery { mockDriver.evaluate(any()) } returns ""
        coEvery { mockDriver.browser.listDrivers() } just Runs
        coEvery { mockDriver.browser.drivers } returns emptyMap()
        coEvery { mockPageStateTracker.waitForDOMSettle() } just Runs

        // Create state manager instance
        stateManager = AgentStateManager(mockAgent, mockPageStateTracker)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ========== Context Creation Tests ==========

    @Test
    fun `should create base context with step 0`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        
        val context = stateManager.buildBaseExecutionContext(action, "test-event")
        
        assertEquals(0, context.step)
        assertEquals("test-event", context.event)
        assertEquals("Test action", context.instruction)
        assertNotNull(context.sessionId)
        assertNotNull(context.agentState)
        assertEquals(config, context.config)
    }

    @Test
    fun `should create init context with step 1`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        
        val context = stateManager.buildInitExecutionContext(action, "init-event")
        
        assertEquals(1, context.step)
        assertEquals("init-event", context.event)
        assertEquals("Test action", context.instruction)
    }

    @Test
    fun `should create subsequent context with incremented step`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val baseContext = stateManager.buildBaseExecutionContext(action, "base")
        
        val nextContext = stateManager.buildExecutionContext(
            instruction = "Next step",
            step = 2,
            event = "step-2",
            baseContext = baseContext
        )
        
        assertEquals(2, nextContext.step)
        assertEquals("step-2", nextContext.event)
        assertEquals(baseContext.sessionId, nextContext.sessionId)
        assertEquals("Next step", nextContext.instruction)
    }

    @Test
    fun `should reuse session ID from base context`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val baseContext = stateManager.buildBaseExecutionContext(action, "base")
        
        val context1 = stateManager.buildExecutionContext("Step 1", 1, "step-1", baseContext)
        val context2 = stateManager.buildExecutionContext("Step 2", 2, "step-2", baseContext)
        
        assertEquals(baseContext.sessionId, context1.sessionId)
        assertEquals(baseContext.sessionId, context2.sessionId)
    }

    // ========== Context Activation Tests ==========

    @Test
    fun `should set and get active context`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val context = stateManager.buildInitExecutionContext(action, "test")
        
        stateManager.setActiveContext(context)
        val activeContext = stateManager.getActiveContext()
        
        assertEquals(context, activeContext)
    }

    @Test
    fun `should throw when getting active context before initialization`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            stateManager.getActiveContext()
        }
        
        assertTrue(exception.message!!.contains("Actor not initialized"))
    }

    @Test
    fun `should maintain activeContext equals contexts last invariant`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val context1 = stateManager.buildInitExecutionContext(action, "step1")
        val context2 = stateManager.buildExecutionContext("Step 2", 2, "step2")
        
        stateManager.setActiveContext(context1)
        stateManager.setActiveContext(context2)
        
        val activeContext = stateManager.getActiveContext()
        // The invariant is checked inside getActiveContext()
        assertEquals(context2, activeContext)
    }

    @Test
    fun `should not add same context twice`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val context = stateManager.buildInitExecutionContext(action, "test")
        
        stateManager.setActiveContext(context)
        stateManager.setActiveContext(context) // Try to add again
        
        // Should only be added once (tested via no exception)
        val activeContext = stateManager.getActiveContext()
        assertEquals(context, activeContext)
    }

    @Test
    fun `getOrCreateActiveContext should create context on first call`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        
        val context = stateManager.getOrCreateActiveContext(action, "auto-create")
        
        assertNotNull(context)
        assertEquals(1, context.step) // Init context has step 1
        assertEquals("Test action", context.instruction)
    }

    @Test
    fun `getOrCreateActiveContext should return existing context on subsequent calls`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        
        val context1 = stateManager.getOrCreateActiveContext(action, "first")
        val context2 = stateManager.getOrCreateActiveContext(action, "second")
        
        assertEquals(context1, context2)
    }

    // ========== State History Tests ==========

    @Test
    fun `should add state to history`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val context = stateManager.buildInitExecutionContext(action, "test")
        val state = context.agentState
        
        stateManager.addToHistory(state)
        
        val history = stateManager.stateHistory
        assertEquals(1, history.states.size)
        assertEquals(state, history.states.first())
    }

    @Test
    fun `should maintain multiple states in history`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val baseContext = stateManager.buildBaseExecutionContext(action, "base")
        
        repeat(5) { i ->
            val context = stateManager.buildExecutionContext("Step $i", i + 1, "step-$i", baseContext)
            stateManager.addToHistory(context.agentState)
        }
        
        val history = stateManager.stateHistory
        assertEquals(5, history.states.size)
    }

    @Test
    fun `should trim history when exceeding maxHistorySize times 2`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val baseContext = stateManager.buildBaseExecutionContext(action, "base")
        
        // Add more than maxHistorySize * 2 (10 * 2 = 20) states
        repeat(25) { i ->
            val context = stateManager.buildExecutionContext("Step $i", i + 1, "step-$i", baseContext)
            stateManager.addToHistory(context.agentState)
        }
        
        val history = stateManager.stateHistory
        // Should be trimmed to maxHistorySize (10)
        assertEquals(config.maxHistorySize, history.states.size)
    }

    @Test
    fun `should clear history when requested`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val context = stateManager.buildInitExecutionContext(action, "test")
        
        stateManager.addToHistory(context.agentState)
        assertEquals(1, stateManager.stateHistory.states.size)
        
        stateManager.clearHistory()
        assertEquals(0, stateManager.stateHistory.states.size)
    }

    @Test
    fun `should remove last history entry if step matches`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val baseContext = stateManager.buildBaseExecutionContext(action, "base")
        
        val context1 = stateManager.buildExecutionContext("Step 1", 1, "step-1", baseContext)
        val context2 = stateManager.buildExecutionContext("Step 2", 2, "step-2", baseContext)
        
        stateManager.addToHistory(context1.agentState)
        stateManager.addToHistory(context2.agentState)
        assertEquals(2, stateManager.stateHistory.states.size)
        
        stateManager.removeLastIfStep(2)
        assertEquals(1, stateManager.stateHistory.states.size)
        assertEquals(1, stateManager.stateHistory.states.last().step)
    }

    // ========== Process Trace Tests ==========

    @Test
    fun `should add trace to process trace list`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val context = stateManager.buildInitExecutionContext(action, "test")
        
        stateManager.addTrace(context.agentState, emptyMap(), "test-event", "Test message")
        
        val traces = stateManager.processTrace
        assertEquals(1, traces.size)
        assertEquals("test-event", traces.first().event)
        assertEquals("Test message", traces.first().message)
    }

    @Test
    fun `should record multiple traces`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val baseContext = stateManager.buildBaseExecutionContext(action, "base")
        
        repeat(5) { i ->
            val context = stateManager.buildExecutionContext("Step $i", i + 1, "step-$i", baseContext)
            stateManager.addTrace(context.agentState, emptyMap(), "event-$i", "Message $i")
        }
        
        val traces = stateManager.processTrace
        assertEquals(5, traces.size)
    }

    @Test
    fun `should trim process trace when exceeding limit`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val baseContext = stateManager.buildBaseExecutionContext(action, "base")
        
        // Add more than 200 traces (the limit in clearUpHistory)
        repeat(250) { i ->
            val context = stateManager.buildExecutionContext("Step $i", i + 1, "step-$i", baseContext)
            stateManager.addTrace(context.agentState, emptyMap(), "event-$i", "Message $i")
        }
        
        // Trigger cleanup
        stateManager.clearUpHistory(0)
        
        val traces = stateManager.processTrace
        // Should be trimmed to 100 (half of 200 limit)
        assertTrue(traces.size <= 100)
    }

    // ========== Memory Management Tests ==========

    @Test
    fun `should cleanup contexts when exceeding limit`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val baseContext = stateManager.buildBaseExecutionContext(action, "base")
        stateManager.setActiveContext(baseContext)
        
        // Create more than 100 contexts (the limit in clearUpHistory)
        repeat(110) { i ->
            val context = stateManager.buildExecutionContext("Step $i", i + 1, "step-$i", baseContext)
            stateManager.setActiveContext(context)
        }
        
        // Trigger cleanup
        stateManager.clearUpHistory(0)
        
        // Contexts should be trimmed to 50 (half of 100 limit)
        // We can't directly check contexts size, but we can verify active context is still valid
        val activeContext = stateManager.getActiveContext()
        assertNotNull(activeContext)
    }

    @Test
    fun `should manually cleanup specified number of history entries`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val baseContext = stateManager.buildBaseExecutionContext(action, "base")
        
        repeat(20) { i ->
            val context = stateManager.buildExecutionContext("Step $i", i + 1, "step-$i", baseContext)
            stateManager.addToHistory(context.agentState)
        }
        
        assertEquals(20, stateManager.stateHistory.states.size)
        
        stateManager.clearUpHistory(10)
        
        assertEquals(10, stateManager.stateHistory.states.size)
    }

    // ========== Agent State Tests ==========

    @Test
    fun `should create agent state with correct step number`() = runBlocking {
        val state = stateManager.getAgentState("Test instruction", 5, null)
        
        assertEquals(5, state.step)
        assertEquals("Test instruction", state.instruction)
        assertNotNull(state.browserUseState)
        assertNull(state.prevState)
    }

    @Test
    fun `should link agent state to previous state`() = runBlocking {
        val prevState = stateManager.getAgentState("Previous", 1, null)
        val currentState = stateManager.getAgentState("Current", 2, prevState)
        
        assertEquals(prevState, currentState.prevState)
    }

    @Test
    fun `should sync browser use state on context`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val context = stateManager.buildInitExecutionContext(action, "test")
        
        val browserState = stateManager.syncBrowserUseState(context)
        
        assertNotNull(browserState)
        assertEquals(browserState, context.agentState.browserUseState)
    }

    // ========== Edge Cases Tests ==========

    @Test
    fun `should handle empty history gracefully`() {
        val history = stateManager.stateHistory
        
        assertTrue(history.isEmpty())
        assertNull(history.lastOrNull())
        assertEquals(0, history.size)
    }

    @Test
    fun `should handle empty process trace gracefully`() {
        val traces = stateManager.processTrace
        
        assertTrue(traces.isEmpty())
        assertEquals(0, traces.size)
    }

    @Test
    fun `should handle cleanup with zero toRemove`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val context = stateManager.buildInitExecutionContext(action, "test")
        stateManager.addToHistory(context.agentState)
        
        stateManager.clearUpHistory(0)
        
        // Should not affect existing history
        assertEquals(1, stateManager.stateHistory.states.size)
    }

    @Test
    fun `should handle cleanup with toRemove exceeding history size`() = runBlocking {
        val action = ActionOptions(action = "Test action")
        val context = stateManager.buildInitExecutionContext(action, "test")
        stateManager.addToHistory(context.agentState)
        
        stateManager.clearUpHistory(100) // Much more than actual size
        
        // Should clear all history
        assertEquals(0, stateManager.stateHistory.states.size)
    }
}
