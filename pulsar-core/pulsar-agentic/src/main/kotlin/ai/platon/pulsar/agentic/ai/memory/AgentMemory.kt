package ai.platon.pulsar.agentic.ai.memory

import java.time.Instant

/**
 * Represents a single memory entry in the agent's memory system.
 *
 * @property content The actual content/text of the memory
 * @property timestamp When this memory was created
 * @property importance Priority/relevance score (0.0 to 1.0)
 * @property metadata Additional context about this memory
 */
data class Memory(
    val content: String,
    val timestamp: Instant = Instant.now(),
    val importance: Double = 0.5,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(importance in 0.0..1.0) { "Importance must be between 0.0 and 1.0" }
    }
}

/**
 * Interface for agent memory systems.
 * Provides storage and retrieval of memories to help the agent maintain context.
 */
interface AgentMemory {
    /**
     * Add a new memory to the storage.
     *
     * @param content The memory content
     * @param importance Importance score (0.0 to 1.0), defaults to 0.5
     * @param metadata Additional metadata for this memory
     */
    fun add(content: String, importance: Double = 0.5, metadata: Map<String, String> = emptyMap())

    /**
     * Retrieve the most relevant memories based on the query.
     *
     * @param query The query to match against memories
     * @param limit Maximum number of memories to return
     * @return List of matching memories, ordered by relevance
     */
    fun retrieve(query: String? = null, limit: Int = 10): List<Memory>

    /**
     * Get all memories in chronological order.
     *
     * @return All stored memories
     */
    fun getAll(): List<Memory>

    /**
     * Clear all memories from storage.
     */
    fun clear()

    /**
     * Get the number of memories currently stored.
     *
     * @return Count of memories
     */
    fun size(): Int
}
