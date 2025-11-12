package ai.platon.pulsar.agentic.ai.memory

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LongTermMemoryTest {

    private lateinit var memory: LongTermMemory

    @BeforeEach
    fun setup() {
        memory = LongTermMemory(importanceThreshold = 0.7, maxSize = 10)
    }

    @Test
    fun `Given memory above threshold When add called Then memory should be stored`() {
        memory.add("Important memory", 0.8)
        
        assertEquals(1, memory.size())
        val memories = memory.getAll()
        assertEquals("Important memory", memories[0].content)
    }

    @Test
    fun `Given memory below threshold When add called Then memory should not be stored`() {
        memory.add("Unimportant memory", 0.5)
        
        assertEquals(0, memory.size())
    }

    @Test
    fun `Given duplicate content When add called Then should update existing memory`() {
        memory.add("Important fact", 0.8)
        memory.add("Important fact", 0.9)
        
        assertEquals(1, memory.size())
        val memories = memory.getAll()
        assertEquals(0.9, memories[0].importance, 0.01)
    }

    @Test
    fun `Given memory at max size When add called Then least important should be removed`() {
        // Fill with high importance memories
        repeat(10) { i ->
            memory.add("Memory $i", 0.7 + (i * 0.01))
        }
        
        assertEquals(10, memory.size())
        
        // Add one more with very high importance
        memory.add("Very important memory", 1.0)
        
        // Still at max size
        assertEquals(10, memory.size())
        
        // Least important should be removed (Memory 0 with 0.7)
        val memories = memory.getAll()
        assertFalse(memories.any { it.content == "Memory 0" })
        assertTrue(memories.any { it.content == "Very important memory" })
    }

    @Test
    fun `Given memories When retrieve with query Then should rank by relevance and importance`() {
        memory.add("Search for products on Amazon", 0.9)
        memory.add("Search results page loaded", 0.7)
        memory.add("Click the add to cart button", 0.8)
        
        val results = memory.retrieve("search", limit = 10)
        
        assertEquals(2, results.size)
        // Higher importance should come first when relevance is equal
        assertEquals("Search for products on Amazon", results[0].content)
    }

    @Test
    fun `Given memories When retrieve without query Then should return by importance`() {
        memory.add("Low priority task", 0.7)
        memory.add("High priority task", 0.95)
        memory.add("Medium priority task", 0.8)
        
        val results = memory.retrieve(null, limit = 3)
        
        assertEquals(3, results.size)
        assertEquals("High priority task", results[0].content)
        assertEquals("Medium priority task", results[1].content)
        assertEquals("Low priority task", results[2].content)
    }

    @Test
    fun `Given memories When clear called Then all memories should be removed`() {
        memory.add("Memory 1", 0.8)
        memory.add("Memory 2", 0.9)
        
        assertEquals(2, memory.size())
        
        memory.clear()
        
        assertEquals(0, memory.size())
    }
}
