package ai.platon.pulsar.agentic.ai.memory

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CompositeMemoryTest {

    private lateinit var memory: CompositeMemory

    @BeforeEach
    fun setup() {
        memory = CompositeMemory(
            shortTerm = ShortTermMemory(maxSize = 5),
            longTerm = LongTermMemory(importanceThreshold = 0.7, maxSize = 10)
        )
    }

    @Test
    fun `Given high importance memory When add called Then should be in both stores`() {
        memory.add("Important fact", 0.9)
        
        assertEquals(1, memory.shortTerm.size())
        assertEquals(1, memory.longTerm.size())
    }

    @Test
    fun `Given low importance memory When add called Then should only be in short term`() {
        memory.add("Minor detail", 0.5)
        
        assertEquals(1, memory.shortTerm.size())
        assertEquals(0, memory.longTerm.size())
    }

    @Test
    fun `Given memories in both stores When retrieve called Then should merge and deduplicate`() {
        memory.add("Shared memory", 0.8)
        memory.add("Short term only", 0.5)
        
        val results = memory.retrieve(limit = 10)
        
        // Should have 2 unique memories
        assertEquals(2, results.size)
        assertTrue(results.any { it.content == "Shared memory" })
        assertTrue(results.any { it.content == "Short term only" })
    }

    @Test
    fun `Given memories When getMemoryContext called Then should format for prompts`() {
        memory.add("Visited product page", 0.8, mapOf("action" to "navigate"))
        memory.add("Added item to cart", 0.9, mapOf("action" to "click"))
        
        val context = memory.getMemoryContext(limit = 10)
        
        assertTrue(context.contains("## 记忆"))
        assertTrue(context.contains("Visited product page"))
        assertTrue(context.contains("Added item to cart"))
    }

    @Test
    fun `Given empty memory When getMemoryContext called Then should return empty string`() {
        val context = memory.getMemoryContext(limit = 10)
        
        assertEquals("", context)
    }

    @Test
    fun `Given memories When getMemoryContext with query Then should filter relevant memories`() {
        memory.add("Search for products", 0.8)
        memory.add("Navigate to cart", 0.7)
        memory.add("Checkout process started", 0.9)
        
        val context = memory.getMemoryContext("cart", limit = 10)
        
        assertTrue(context.contains("Navigate to cart"))
        assertFalse(context.contains("Search for products"))
    }

    @Test
    fun `Given memories When getAll called Then should return unique memories from both stores`() {
        memory.add("Memory 1", 0.5) // Short term only
        memory.add("Memory 2", 0.8) // Both stores
        memory.add("Memory 3", 0.9) // Both stores
        
        val all = memory.getAll()
        
        assertEquals(3, all.size)
    }

    @Test
    fun `Given memories When clear called Then both stores should be cleared`() {
        memory.add("Memory 1", 0.5)
        memory.add("Memory 2", 0.8)
        
        assertTrue(memory.size() > 0)
        
        memory.clear()
        
        assertEquals(0, memory.size())
        assertEquals(0, memory.shortTerm.size())
        assertEquals(0, memory.longTerm.size())
    }

    @Test
    fun `Given memories When size called Then should return unique count`() {
        memory.add("Shared memory", 0.8)
        memory.add("Another memory", 0.9)
        
        // Both are in both stores but should count as 2 unique
        assertEquals(2, memory.size())
    }

    @Test
    fun `Given memories When createSnapshot called Then should capture all memories`() {
        memory.add("Short term only", 0.5)
        memory.add("Both stores", 0.8)
        memory.add("Another important", 0.9)
        
        val snapshot = memory.createSnapshot()
        
        assertEquals(3, snapshot.shortTermMemories.size)
        assertEquals(2, snapshot.longTermMemories.size)
    }

    @Test
    fun `Given snapshot When restoreFromSnapshot called Then memories should be restored`() {
        // Add initial memories
        memory.add("Memory 1", 0.8)
        memory.add("Memory 2", 0.9)
        
        // Create snapshot
        val snapshot = memory.createSnapshot()
        
        // Clear memory
        memory.clear()
        assertEquals(0, memory.size())
        
        // Restore from snapshot
        memory.restoreFromSnapshot(snapshot)
        
        assertEquals(2, memory.size())
        val restored = memory.getAll()
        assertTrue(restored.any { it.content == "Memory 1" })
        assertTrue(restored.any { it.content == "Memory 2" })
    }
}
