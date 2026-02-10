package ai.platon.pulsar.agentic.memory.storage

import ai.platon.browser4.driver.chrome.dom.model.BrowserState
import ai.platon.browser4.driver.chrome.dom.model.BrowserUseState
import ai.platon.browser4.driver.chrome.dom.model.DOMState
import ai.platon.pulsar.agentic.memory.*
import ai.platon.pulsar.agentic.model.AgentState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for InMemoryStorage implementation.
 */
class InMemoryStorageTest {

    private lateinit var storage: InMemoryStorage

    @BeforeEach
    fun setup() {
        storage = InMemoryStorage()
    }

    @AfterEach
    fun cleanup() = runBlocking {
        storage.clear()
    }

    @Test
    fun testSaveAndLoadEpisodicMemory() = runBlocking {
        val episode = createTestEpisode()
        val memory = Memory.Episodic(
            id = episode.id,
            timestamp = episode.endTime,
            episode = episode
        )

        val savedId = storage.save(memory)
        assertEquals(episode.id, savedId)

        val loaded = storage.load(savedId)
        assertNotNull(loaded)
        assertTrue(loaded is Memory.Episodic)
        
        val loadedEpisode = (loaded as Memory.Episodic).episode
        assertEquals(episode.id, loadedEpisode.id)
        assertEquals(episode.taskDescription, loadedEpisode.taskDescription)
        assertEquals(episode.outcome, loadedEpisode.outcome)
    }

    @Test
    fun testSaveAndLoadSemanticMemory() = runBlocking {
        val knowledge = SemanticMemory(
            category = MemoryCategory.FACT,
            content = "Test fact content",
            confidence = 0.9,
            sourceEpisodes = listOf("episode-1")
        )
        val memory = Memory.Semantic(
            id = knowledge.id,
            timestamp = knowledge.created,
            knowledge = knowledge
        )

        val savedId = storage.save(memory)
        assertEquals(knowledge.id, savedId)

        val loaded = storage.load(savedId)
        assertNotNull(loaded)
        assertTrue(loaded is Memory.Semantic)
        
        val loadedKnowledge = (loaded as Memory.Semantic).knowledge
        assertEquals(knowledge.id, loadedKnowledge.id)
        assertEquals(knowledge.content, loadedKnowledge.content)
        assertEquals(knowledge.category, loadedKnowledge.category)
    }

    @Test
    fun testQueryAllMemories() = runBlocking {
        val episode = createTestEpisode()
        val knowledge = SemanticMemory(
            category = MemoryCategory.FACT,
            content = "Test content",
            confidence = 0.8
        )

        storage.save(Memory.Episodic(episode.id, episode.endTime, episode))
        storage.save(Memory.Semantic(knowledge.id, knowledge.created, knowledge))

        val all = storage.query(MemoryQuery.All)
        assertEquals(2, all.size)
    }

    @Test
    fun testQueryByRecency() = runBlocking {
        val episode1 = createTestEpisode("Task 1")
        val episode2 = createTestEpisode("Task 2")
        
        storage.save(Memory.Episodic(episode1.id, episode1.endTime, episode1))
        Thread.sleep(10) // Ensure different timestamps
        storage.save(Memory.Episodic(episode2.id, episode2.endTime, episode2))

        val recent = storage.query(MemoryQuery.Recency(limit = 1))
        assertEquals(1, recent.size)
        assertTrue(recent[0] is Memory.Episodic)
        assertEquals(episode2.id, (recent[0] as Memory.Episodic).episode.id)
    }

    @Test
    fun testQueryByTags() = runBlocking {
        val episode1 = createTestEpisode("Task 1", tags = setOf("amazon", "search"))
        val episode2 = createTestEpisode("Task 2", tags = setOf("google", "search"))
        
        storage.save(Memory.Episodic(episode1.id, episode1.endTime, episode1))
        storage.save(Memory.Episodic(episode2.id, episode2.endTime, episode2))

        val amazonResults = storage.query(MemoryQuery.Tag(tags = setOf("amazon")))
        assertEquals(1, amazonResults.size)
        assertEquals(episode1.id, (amazonResults[0] as Memory.Episodic).episode.id)

        val searchResults = storage.query(MemoryQuery.Tag(tags = setOf("search")))
        assertEquals(2, searchResults.size)
    }

    @Test
    fun testQueryBySimilarity() = runBlocking {
        val knowledge1 = SemanticMemory(
            category = MemoryCategory.FACT,
            content = "Amazon search button selector is #nav-search-submit-button",
            confidence = 0.9
        )
        val knowledge2 = SemanticMemory(
            category = MemoryCategory.PATTERN,
            content = "Login requires email then password",
            confidence = 0.8
        )
        
        storage.save(Memory.Semantic(knowledge1.id, knowledge1.created, knowledge1))
        storage.save(Memory.Semantic(knowledge2.id, knowledge2.created, knowledge2))

        val results = storage.query(MemoryQuery.Similarity(
            queryText = "Amazon search",
            minSimilarity = 0.1,
            limit = 5
        ))
        
        assertTrue(results.isNotEmpty())
        assertTrue(results[0] is Memory.Semantic)
        assertTrue((results[0] as Memory.Semantic).knowledge.content.contains("Amazon"))
    }

    @Test
    fun testDeleteMemory() = runBlocking {
        val episode = createTestEpisode()
        val memory = Memory.Episodic(episode.id, episode.endTime, episode)
        
        storage.save(memory)
        assertTrue(storage.delete(episode.id))
        assertNull(storage.load(episode.id))
        assertFalse(storage.delete(episode.id))
    }

    @Test
    fun testUpdateSemanticMemory() = runBlocking {
        val knowledge = SemanticMemory(
            category = MemoryCategory.FACT,
            content = "Test content",
            confidence = 0.8,
            timesUsed = 0
        )
        val memory = Memory.Semantic(knowledge.id, knowledge.created, knowledge)
        
        storage.save(memory)
        
        val updated = storage.update(knowledge.id, mapOf("timesUsed" to 5))
        assertTrue(updated)
        
        val loaded = storage.load(knowledge.id) as Memory.Semantic
        assertEquals(5, loaded.knowledge.timesUsed)
    }

    @Test
    fun testCountMemories() = runBlocking {
        assertEquals(0, storage.count())
        
        val episode = createTestEpisode()
        storage.save(Memory.Episodic(episode.id, episode.endTime, episode))
        assertEquals(1, storage.count())
        
        val knowledge = SemanticMemory(
            category = MemoryCategory.FACT,
            content = "Test",
            confidence = 0.8
        )
        storage.save(Memory.Semantic(knowledge.id, knowledge.created, knowledge))
        assertEquals(2, storage.count())
    }

    @Test
    fun testClearAllMemories() = runBlocking {
        val episode = createTestEpisode()
        storage.save(Memory.Episodic(episode.id, episode.endTime, episode))
        
        assertEquals(1, storage.count())
        storage.clear()
        assertEquals(0, storage.count())
    }

    // Helper methods

    private fun createTestEpisode(
        taskDesc: String = "Test task",
        tags: Set<String> = emptySet()
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
            sessionId = "test-session",
            taskDescription = taskDesc,
            taskGoal = taskDesc,
            startTime = Instant.now().minusSeconds(60),
            endTime = Instant.now(),
            states = listOf(state),
            outcome = TaskOutcome.SUCCESS,
            summary = "Test summary",
            tags = tags
        )
    }
}
