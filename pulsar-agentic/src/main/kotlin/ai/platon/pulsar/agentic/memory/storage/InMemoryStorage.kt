package ai.platon.pulsar.agentic.memory.storage

import ai.platon.pulsar.agentic.memory.*
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory storage implementation.
 *
 * Fast access with no persistence. Suitable for development and testing.
 * All data is lost when the application stops.
 */
class InMemoryStorage : MemoryStorage {
    private val memories = ConcurrentHashMap<String, Memory>()

    override suspend fun save(memory: Memory): String {
        memories[memory.id] = memory
        return memory.id
    }

    override suspend fun load(id: String): Memory? {
        return memories[id]
    }

    override suspend fun query(query: MemoryQuery): List<Memory> {
        val allMemories = memories.values.toList()

        return when (query) {
            is MemoryQuery.All -> allMemories

            is MemoryQuery.Recency -> {
                val filtered = if (query.category != null) {
                    allMemories.filter { memory ->
                        memory is Memory.Semantic && memory.knowledge.category == query.category
                    }
                } else {
                    allMemories
                }

                val tagFiltered = if (query.tags.isNotEmpty()) {
                    filtered.filter { memory ->
                        val tags = when (memory) {
                            is Memory.Episodic -> memory.episode.tags
                            is Memory.Semantic -> memory.knowledge.tags
                        }
                        query.tags.any { it in tags }
                    }
                } else {
                    filtered
                }

                tagFiltered
                    .sortedByDescending { it.timestamp }
                    .take(query.limit)
            }

            is MemoryQuery.Tag -> {
                val filtered = allMemories.filter { memory ->
                    val tags = when (memory) {
                        is Memory.Episodic -> memory.episode.tags
                        is Memory.Semantic -> memory.knowledge.tags
                    }
                    if (query.matchAll) {
                        query.tags.all { it in tags }
                    } else {
                        query.tags.any { it in tags }
                    }
                }
                filtered
                    .sortedByDescending { it.timestamp }
                    .take(query.limit)
            }

            is MemoryQuery.Similarity -> {
                // Basic text similarity for in-memory storage
                val semanticMemories = allMemories.filterIsInstance<Memory.Semantic>()
                val filtered = if (query.category != null) {
                    semanticMemories.filter { it.knowledge.category == query.category }
                } else {
                    semanticMemories
                }

                // Simple keyword matching for basic similarity
                val queryWords = query.queryText.lowercase().split("\\s+".toRegex()).toSet()
                val scored = filtered.map { memory ->
                    val contentWords = memory.knowledge.content.lowercase().split("\\s+".toRegex()).toSet()
                    val similarity = queryWords.intersect(contentWords).size.toDouble() / 
                                   (queryWords.size + contentWords.size - queryWords.intersect(contentWords).size)
                    memory to similarity
                }

                scored
                    .filter { it.second >= query.minSimilarity }
                    .sortedByDescending { it.second }
                    .take(query.limit)
                    .map { it.first }
            }

            is MemoryQuery.Hybrid -> {
                // Simple hybrid: combine recency and similarity
                val recent = query(MemoryQuery.Recency(limit = query.limit * 2))
                val similar = query(MemoryQuery.Similarity(
                    queryText = query.context,
                    limit = query.limit * 2
                ))

                // Combine with weights (simplified)
                (recent + similar)
                    .distinct()
                    .take(query.limit)
            }
        }
    }

    override suspend fun delete(id: String): Boolean {
        return memories.remove(id) != null
    }

    override suspend fun update(id: String, updates: Map<String, Any>): Boolean {
        val memory = memories[id] ?: return false

        // For semantic memory, update usage tracking
        if (memory is Memory.Semantic && updates.containsKey("timesUsed")) {
            val updated = memory.knowledge.copy(
                timesUsed = updates["timesUsed"] as? Int ?: memory.knowledge.timesUsed
            )
            memories[id] = Memory.Semantic(
                id = memory.id,
                timestamp = memory.timestamp,
                knowledge = updated
            )
            return true
        }

        return false
    }

    override suspend fun count(): Long {
        return memories.size.toLong()
    }

    override suspend fun clear() {
        memories.clear()
    }
}
