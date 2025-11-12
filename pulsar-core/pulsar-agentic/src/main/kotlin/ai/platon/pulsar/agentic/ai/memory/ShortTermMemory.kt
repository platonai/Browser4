package ai.platon.pulsar.agentic.ai.memory

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Short-term memory implementation using a sliding window approach.
 * Maintains recent memories with automatic cleanup of older entries.
 *
 * @property maxSize Maximum number of memories to retain
 */
class ShortTermMemory(
    private val maxSize: Int = 50
) : AgentMemory {
    private val memories = ConcurrentLinkedDeque<Memory>()

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    override fun add(content: String, importance: Double, metadata: Map<String, String>) {
        val memory = Memory(content, importance = importance, metadata = metadata)
        
        synchronized(memories) {
            memories.addLast(memory)
            
            // Remove oldest if exceeding max size
            while (memories.size > maxSize) {
                memories.removeFirst()
            }
        }
    }

    override fun retrieve(query: String?, limit: Int): List<Memory> {
        val allMemories = memories.toList()
        
        return if (query.isNullOrBlank()) {
            // Return most recent memories
            allMemories.takeLast(limit)
        } else {
            // Simple relevance: check if memory contains query terms
            val queryTerms = query.lowercase().split("\\s+".toRegex())
            allMemories
                .filter { memory ->
                    val content = memory.content.lowercase()
                    queryTerms.any { term -> content.contains(term) }
                }
                .sortedByDescending { it.importance }
                .take(limit)
        }
    }

    override fun getAll(): List<Memory> {
        return memories.toList()
    }

    override fun clear() {
        synchronized(memories) {
            memories.clear()
        }
    }

    override fun size(): Int {
        return memories.size
    }
}
