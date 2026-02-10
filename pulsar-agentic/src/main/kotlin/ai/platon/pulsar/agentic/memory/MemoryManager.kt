package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.agentic.model.AgentState

/**
 * Memory Manager interface for managing agent memories.
 *
 * Central coordinator for all memory operations including working memory,
 * episodic memory, and semantic memory management.
 */
interface MemoryManager {
    /**
     * Get working memory for a session.
     *
     * @param sessionId The session identifier
     * @return The working memory for the session
     */
    fun getWorkingMemory(sessionId: String): WorkingMemory

    /**
     * Add a state to working memory.
     *
     * @param sessionId The session identifier
     * @param state The agent state to add
     */
    suspend fun addToWorkingMemory(sessionId: String, state: AgentState)

    /**
     * Consolidate working memory into episodic memory.
     *
     * @param sessionId The session identifier
     * @param outcome The task outcome
     * @param summary Optional custom summary (generated if null)
     * @return The created episodic memory
     */
    suspend fun consolidateSession(
        sessionId: String,
        outcome: TaskOutcome,
        summary: String? = null
    ): EpisodicMemory

    /**
     * Store an episodic memory.
     *
     * @param episode The episodic memory to store
     * @return The memory ID
     */
    suspend fun storeEpisode(episode: EpisodicMemory): String

    /**
     * Retrieve relevant memories based on query.
     *
     * @param query The memory query
     * @param limit Maximum number of results
     * @return List of matching memories
     */
    suspend fun retrieveRelevant(
        query: MemoryQuery,
        limit: Int = 5
    ): List<Memory>

    /**
     * Store semantic knowledge.
     *
     * @param knowledge The semantic memory to store
     * @return The memory ID
     */
    suspend fun storeKnowledge(knowledge: SemanticMemory): String

    /**
     * Search semantic memory.
     *
     * @param query The search query
     * @param category Optional category filter
     * @param limit Maximum number of results
     * @return List of matching semantic memories
     */
    suspend fun searchKnowledge(
        query: String,
        category: MemoryCategory? = null,
        limit: Int = 5
    ): List<SemanticMemory>

    /**
     * Extract knowledge from episodes.
     *
     * @param episodes The episodes to extract knowledge from
     * @return List of extracted semantic memories
     */
    suspend fun extractKnowledge(episodes: List<EpisodicMemory>): List<SemanticMemory>

    /**
     * Build agent context with memories.
     *
     * @param currentTask The current task description
     * @param sessionId The session identifier
     * @param maxTokens Maximum tokens for context
     * @return The built agent context
     */
    suspend fun buildContext(
        currentTask: String,
        sessionId: String,
        maxTokens: Int = 4000
    ): AgentContext

    /**
     * Clear all memories for a session.
     *
     * @param sessionId The session identifier
     */
    suspend fun clearSession(sessionId: String)

    /**
     * Clear all memories (use with caution).
     */
    suspend fun clearAll()

    /**
     * Get memory statistics.
     *
     * @return Memory statistics
     */
    suspend fun getStatistics(): MemoryStatistics
}

/**
 * Memory statistics.
 *
 * @property totalMemories Total number of memories
 * @property episodicCount Number of episodic memories
 * @property semanticCount Number of semantic memories
 * @property workingMemoryCount Number of active working memories
 */
data class MemoryStatistics(
    val totalMemories: Long,
    val episodicCount: Long,
    val semanticCount: Long,
    val workingMemoryCount: Int
)
