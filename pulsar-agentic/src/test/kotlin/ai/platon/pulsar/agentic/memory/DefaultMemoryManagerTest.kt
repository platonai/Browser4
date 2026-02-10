package ai.platon.pulsar.agentic.memory

import ai.platon.browser4.driver.chrome.dom.model.BrowserState
import ai.platon.browser4.driver.chrome.dom.model.BrowserUseState
import ai.platon.browser4.driver.chrome.dom.model.DOMState
import ai.platon.pulsar.agentic.memory.storage.InMemoryStorage
import ai.platon.pulsar.agentic.model.AgentState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for DefaultMemoryManager.
 */
class DefaultMemoryManagerTest {

    private lateinit var manager: DefaultMemoryManager
    private val sessionId = "test-session-123"

    @BeforeEach
    fun setup() {
        val storage = InMemoryStorage()
        manager = DefaultMemoryManager(storage)
    }

    @AfterEach
    fun cleanup() = runBlocking {
        manager.clearAll()
    }

    @Test
    fun testGetWorkingMemory() {
        val workingMemory = manager.getWorkingMemory(sessionId)
        
        assertNotNull(workingMemory)
        assertEquals(sessionId, workingMemory.sessionId)
        assertTrue(workingMemory.recentStates.isEmpty())
        
        // Getting again should return the same instance
        val workingMemory2 = manager.getWorkingMemory(sessionId)
        assertEquals(workingMemory, workingMemory2)
    }

    @Test
    fun testAddToWorkingMemory() = runBlocking {
        val state = createTestState()
        
        manager.addToWorkingMemory(sessionId, state)
        
        val workingMemory = manager.getWorkingMemory(sessionId)
        assertEquals(1, workingMemory.recentStates.size)
        assertEquals(state, workingMemory.recentStates[0])
    }

    @Test
    fun testConsolidateSession() = runBlocking {
        // Setup working memory with states
        val state1 = createTestState(1, "Step 1")
        val state2 = createTestState(2, "Step 2")
        
        val workingMemory = manager.getWorkingMemory(sessionId)
        workingMemory.taskContext = TaskContext(goal = "Test consolidation task")
        manager.addToWorkingMemory(sessionId, state1)
        manager.addToWorkingMemory(sessionId, state2)

        // Consolidate
        val episode = manager.consolidateSession(sessionId, TaskOutcome.SUCCESS)
        
        assertNotNull(episode)
        assertEquals(sessionId, episode.sessionId)
        assertEquals(TaskOutcome.SUCCESS, episode.outcome)
        assertEquals(2, episode.states.size)
        assertTrue(episode.summary.isNotEmpty())
        
        // Working memory should be cleared after consolidation
        assertNull(manager.getWorkingMemory(sessionId).recentStates.firstOrNull()?.let { null })
    }

    @Test
    fun testStoreAndRetrieveEpisode() = runBlocking {
        val episode = createTestEpisode()
        
        val episodeId = manager.storeEpisode(episode)
        assertEquals(episode.id, episodeId)
        
        val retrieved = manager.retrieveRelevant(MemoryQuery.All, limit = 10)
        assertEquals(1, retrieved.size)
        assertTrue(retrieved[0] is Memory.Episodic)
        assertEquals(episode.id, (retrieved[0] as Memory.Episodic).episode.id)
    }

    @Test
    fun testStoreAndSearchKnowledge() = runBlocking {
        val knowledge = SemanticMemory(
            category = MemoryCategory.FACT,
            content = "Amazon search button is at #nav-search-submit-button",
            confidence = 0.9
        )
        
        manager.storeKnowledge(knowledge)
        
        val results = manager.searchKnowledge("Amazon search", limit = 5)
        assertTrue(results.isNotEmpty())
        assertEquals(knowledge.id, results[0].id)
    }

    @Test
    fun testExtractKnowledge() = runBlocking {
        val episode1 = createTestEpisode("Task 1", TaskOutcome.SUCCESS)
        val episode2 = createTestEpisode("Task 2", TaskOutcome.SUCCESS)
        
        manager.storeEpisode(episode1)
        manager.storeEpisode(episode2)
        
        val knowledge = manager.extractKnowledge(listOf(episode1, episode2))
        assertTrue(knowledge.isNotEmpty())
        assertTrue(knowledge[0].category == MemoryCategory.PATTERN)
    }

    @Test
    fun testBuildContext() = runBlocking {
        // Setup working memory
        val workingMemory = manager.getWorkingMemory(sessionId)
        workingMemory.taskContext = TaskContext(goal = "Test task")
        manager.addToWorkingMemory(sessionId, createTestState())
        
        // Store some episodes
        manager.storeEpisode(createTestEpisode("Previous task 1"))
        manager.storeEpisode(createTestEpisode("Previous task 2"))
        
        // Store some knowledge
        manager.storeKnowledge(SemanticMemory(
            category = MemoryCategory.FACT,
            content = "Test knowledge content",
            confidence = 0.8
        ))
        
        val context = manager.buildContext("New task", sessionId, maxTokens = 4000)
        
        assertNotNull(context)
        assertEquals("New task", context.task)
        assertEquals(workingMemory, context.workingMemory)
        assertTrue(context.formattedContext.isNotEmpty())
        assertTrue(context.formattedContext.contains("Agent Context"))
    }

    @Test
    fun testGetStatistics() = runBlocking {
        // Initially empty
        var stats = manager.getStatistics()
        assertEquals(0, stats.totalMemories)
        assertEquals(0, stats.episodicCount)
        assertEquals(0, stats.semanticCount)
        
        // Add some memories
        manager.storeEpisode(createTestEpisode())
        manager.storeKnowledge(SemanticMemory(
            category = MemoryCategory.FACT,
            content = "Test",
            confidence = 0.8
        ))
        
        // Create working memory
        manager.getWorkingMemory("session-1")
        manager.getWorkingMemory("session-2")
        
        stats = manager.getStatistics()
        assertEquals(2, stats.totalMemories)
        assertEquals(1, stats.episodicCount)
        assertEquals(1, stats.semanticCount)
        assertEquals(2, stats.workingMemoryCount)
    }

    @Test
    fun testClearSession() = runBlocking {
        val state = createTestState()
        manager.addToWorkingMemory(sessionId, state)
        
        assertEquals(1, manager.getWorkingMemory(sessionId).recentStates.size)
        
        manager.clearSession(sessionId)
        
        // Working memory should be cleared
        val newWorkingMemory = manager.getWorkingMemory(sessionId)
        assertTrue(newWorkingMemory.recentStates.isEmpty())
    }

    @Test
    fun testClearAll() = runBlocking {
        // Add various memories
        manager.addToWorkingMemory(sessionId, createTestState())
        manager.storeEpisode(createTestEpisode())
        manager.storeKnowledge(SemanticMemory(
            category = MemoryCategory.FACT,
            content = "Test",
            confidence = 0.8
        ))
        
        assertTrue(manager.getStatistics().totalMemories > 0)
        
        manager.clearAll()
        
        val stats = manager.getStatistics()
        assertEquals(0, stats.totalMemories)
        assertEquals(0, stats.workingMemoryCount)
    }

    // Helper methods

    private fun createTestState(
        step: Int = 1,
        instruction: String = "Test instruction"
    ): AgentState {
        val browserState = BrowserState(url = "https://example.com")
        val domState = DOMState()
        val browserUseState = BrowserUseState(browserState, domState)
        
        return AgentState(
            step = step,
            instruction = instruction,
            browserUseState = browserUseState
        )
    }

    private fun createTestEpisode(
        taskDesc: String = "Test task",
        outcome: TaskOutcome = TaskOutcome.SUCCESS
    ): EpisodicMemory {
        val browserState = BrowserState(url = "https://example.com")
        val domState = DOMState()
        val browserUseState = BrowserUseState(browserState, domState)
        
        val state = AgentState(
            step = 1,
            instruction = taskDesc,
            browserUseState = browserUseState
        )

        return EpisodicMemory(
            sessionId = sessionId,
            taskDescription = taskDesc,
            taskGoal = taskDesc,
            startTime = Instant.now().minusSeconds(60),
            endTime = Instant.now(),
            states = listOf(state),
            outcome = outcome,
            summary = "Test summary for $taskDesc",
            keyLearnings = listOf("Learning 1", "Learning 2"),
            tags = setOf(taskDesc.lowercase().split(" ").first())
        )
    }
}
