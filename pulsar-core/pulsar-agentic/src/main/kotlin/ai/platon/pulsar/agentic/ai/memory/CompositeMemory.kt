package ai.platon.pulsar.agentic.ai.memory

/**
 * Composite memory that combines short-term and long-term memory.
 * Provides a unified interface for managing both types of memories.
 *
 * @property shortTerm Short-term memory for recent context
 * @property longTerm Long-term memory for important facts
 */
class CompositeMemory(
    val shortTerm: ShortTermMemory = ShortTermMemory(),
    val longTerm: LongTermMemory = LongTermMemory()
) : AgentMemory {

    /**
     * Add to both short-term and long-term memory.
     * Long-term will filter based on its importance threshold.
     */
    override fun add(content: String, importance: Double, metadata: Map<String, String>) {
        shortTerm.add(content, importance, metadata)
        longTerm.add(content, importance, metadata)
    }

    /**
     * Retrieve from both memories and merge results.
     * Prioritizes more important and relevant memories.
     */
    override fun retrieve(query: String?, limit: Int): List<Memory> {
        val shortMemories = shortTerm.retrieve(query, limit)
        val longMemories = longTerm.retrieve(query, limit)
        
        // Combine and deduplicate based on content
        val combined = (shortMemories + longMemories)
            .distinctBy { it.content.trim().lowercase() }
            .sortedWith(
                compareByDescending<Memory> { it.importance }
                    .thenByDescending { it.timestamp }
            )
            .take(limit)
        
        return combined
    }

    /**
     * Get all memories from both stores.
     */
    override fun getAll(): List<Memory> {
        val shortMemories = shortTerm.getAll()
        val longMemories = longTerm.getAll()
        
        return (shortMemories + longMemories)
            .distinctBy { it.content.trim().lowercase() }
            .sortedByDescending { it.timestamp }
    }

    /**
     * Clear both memory stores.
     */
    override fun clear() {
        shortTerm.clear()
        longTerm.clear()
    }

    /**
     * Get total unique memory count across both stores.
     */
    override fun size(): Int {
        val allMemories = getAll()
        return allMemories.size
    }

    /**
     * Get formatted memory context string for inclusion in prompts.
     *
     * @param query Optional query to filter relevant memories
     * @param limit Maximum number of memories to include
     * @return Formatted string containing memory context
     */
    fun getMemoryContext(query: String? = null, limit: Int = 10): String {
        val memories = retrieve(query, limit)
        
        if (memories.isEmpty()) {
            return ""
        }
        
        return buildString {
            appendLine("## 记忆")
            appendLine()
            memories.forEachIndexed { index, memory ->
                appendLine("${index + 1}. ${memory.content}")
            }
        }
    }

    /**
     * Create a snapshot of the memory state for checkpointing.
     *
     * @return A snapshot containing all memories
     */
    fun createSnapshot(): MemorySnapshot {
        return MemorySnapshot(
            shortTermMemories = shortTerm.getAll(),
            longTermMemories = longTerm.getAll()
        )
    }

    /**
     * Restore memory state from a snapshot.
     *
     * @param snapshot The snapshot to restore from
     */
    fun restoreFromSnapshot(snapshot: MemorySnapshot) {
        clear()
        snapshot.shortTermMemories.forEach { memory ->
            shortTerm.add(memory.content, memory.importance, memory.metadata)
        }
        snapshot.longTermMemories.forEach { memory ->
            longTerm.add(memory.content, memory.importance, memory.metadata)
        }
    }
}

/**
 * Snapshot of memory state for serialization and restoration.
 *
 * @property shortTermMemories List of memories from short-term storage
 * @property longTermMemories List of memories from long-term storage
 */
data class MemorySnapshot(
    val shortTermMemories: List<Memory> = emptyList(),
    val longTermMemories: List<Memory> = emptyList()
)
