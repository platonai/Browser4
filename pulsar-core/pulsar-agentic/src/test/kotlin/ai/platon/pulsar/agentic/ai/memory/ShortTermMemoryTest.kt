package ai.platon.pulsar.agentic.ai.memory

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContains

class ShortTermMemoryTest {

    private lateinit var memory: ShortTermMemory

    @BeforeEach
    fun setup() {
        memory = ShortTermMemory(maxSize = 5)
    }

    @Test
    fun `Given empty memory When add called Then memory should be stored`() {
        memory.add("Test memory", 0.7)
        
        assertEquals(1, memory.size())
        val memories = memory.getAll()
        assertEquals("Test memory", memories[0].content)
        assertEquals(0.7, memories[0].importance, 0.01)
    }

    @Test
    fun `Given memory at max size When add called Then oldest should be removed`() {
        // Fill to capacity
        repeat(5) { i ->
            memory.add("Memory $i", 0.5)
        }
        
        assertEquals(5, memory.size())
        
        // Add one more
        memory.add("Memory 5", 0.5)
        
        // Still at max size
        assertEquals(5, memory.size())
        
        // Oldest should be removed
        val memories = memory.getAll()
        assertFalse(memories.any { it.content == "Memory 0" })
        assertTrue(memories.any { it.content == "Memory 5" })
    }

    @Test
    fun `Given memories When retrieve with query Then should return matching memories`() {
        memory.add("Search for products on Amazon", 0.8)
        memory.add("Click the add to cart button", 0.6)
        memory.add("Navigate to checkout page", 0.7)
        
        val results = memory.retrieve("cart", limit = 10)
        
        assertEquals(1, results.size)
        assertContains(results[0].content, "cart")
    }

    @Test
    fun `Given memories When retrieve without query Then should return recent memories`() {
        repeat(10) { i ->
            memory.add("Memory $i", 0.5)
        }
        
        val results = memory.retrieve(null, limit = 3)
        
        assertEquals(3, results.size)
        // Should get the most recent (last 3 added, but size is 5, so it's 5-9)
        assertTrue(results.any { it.content.contains("7") || it.content.contains("8") || it.content.contains("9") })
    }

    @Test
    fun `Given memories When clear called Then all memories should be removed`() {
        memory.add("Memory 1", 0.5)
        memory.add("Memory 2", 0.5)
        
        assertEquals(2, memory.size())
        
        memory.clear()
        
        assertEquals(0, memory.size())
    }

    @Test
    fun `Given memories with metadata When add called Then metadata should be stored`() {
        val metadata = mapOf("action" to "click", "locator" to "0,5")
        memory.add("Clicked on button", 0.8, metadata)
        
        val memories = memory.getAll()
        assertEquals(metadata, memories[0].metadata)
    }
}
