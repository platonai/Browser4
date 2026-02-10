package ai.platon.pulsar.agentic.memory.storage

import ai.platon.pulsar.agentic.memory.Memory
import ai.platon.pulsar.agentic.memory.MemoryQuery

/**
 * Storage interface for persisting memories.
 *
 * Provides abstraction over different storage backends (in-memory, file-based, database).
 */
interface MemoryStorage {
    /**
     * Save a memory to storage.
     *
     * @param memory The memory to save
     * @return The ID of the saved memory
     */
    suspend fun save(memory: Memory): String

    /**
     * Load a memory by ID.
     *
     * @param id The memory ID
     * @return The memory, or null if not found
     */
    suspend fun load(id: String): Memory?

    /**
     * Query memories based on query criteria.
     *
     * @param query The query criteria
     * @return List of matching memories
     */
    suspend fun query(query: MemoryQuery): List<Memory>

    /**
     * Delete a memory by ID.
     *
     * @param id The memory ID
     * @return True if deleted, false if not found
     */
    suspend fun delete(id: String): Boolean

    /**
     * Update a memory.
     *
     * @param id The memory ID
     * @param updates Map of field updates
     * @return True if updated, false if not found
     */
    suspend fun update(id: String, updates: Map<String, Any>): Boolean

    /**
     * Count total memories in storage.
     *
     * @return Total count
     */
    suspend fun count(): Long

    /**
     * Clear all memories from storage.
     */
    suspend fun clear()
}
