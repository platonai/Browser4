package ai.platon.pulsar.agentic.ai.memory

import java.util.concurrent.ConcurrentHashMap

/**
 * Long-term memory implementation that stores important facts.
 * Retains memories based on importance threshold and supports keyword-based retrieval.
 *
 * @property importanceThreshold Minimum importance score to retain a memory (0.0 to 1.0)
 * @property maxSize Maximum number of memories to retain
 */
class LongTermMemory(
    private val importanceThreshold: Double = 0.7,
    private val maxSize: Int = 200
) : AgentMemory {
    private val memories = ConcurrentHashMap<String, Memory>()

    init {
        require(importanceThreshold in 0.0..1.0) { "importanceThreshold must be between 0.0 and 1.0" }
        require(maxSize > 0) { "maxSize must be positive" }
    }

    override fun add(content: String, importance: Double, metadata: Map<String, String>) {
        // Only store memories that meet the importance threshold
        if (importance < importanceThreshold) {
            return
        }

        val memory = Memory(content, importance = importance, metadata = metadata)
        
        synchronized(memories) {
            // Use content as key to avoid duplicates
            val key = content.trim().lowercase()
            memories[key] = memory
            
            // If exceeding max size, remove least important memories
            if (memories.size > maxSize) {
                val toRemove = memories.size - maxSize
                memories.values
                    .sortedBy { it.importance }
                    .take(toRemove)
                    .forEach { mem ->
                        memories.remove(mem.content.trim().lowercase())
                    }
            }
        }
    }

    override fun retrieve(query: String?, limit: Int): List<Memory> {
        val allMemories = memories.values.toList()
        
        return if (query.isNullOrBlank()) {
            // Return most important memories
            allMemories
                .sortedByDescending { it.importance }
                .take(limit)
        } else {
            // Rank by relevance and importance
            val queryTerms = query.lowercase().split("\\s+".toRegex())
            allMemories
                .map { memory ->
                    val content = memory.content.lowercase()
                    val matchCount = queryTerms.count { term -> content.contains(term) }
                    val relevanceScore = (matchCount.toDouble() / queryTerms.size) * memory.importance
                    memory to relevanceScore
                }
                .filter { (_, score) -> score > 0 }
                .sortedByDescending { (_, score) -> score }
                .take(limit)
                .map { (memory, _) -> memory }
        }
    }

    override fun getAll(): List<Memory> {
        return memories.values
            .sortedByDescending { it.importance }
            .toList()
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
