package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.agentic.memory.storage.FileBasedStorage
import ai.platon.pulsar.agentic.memory.storage.InMemoryStorage
import ai.platon.pulsar.agentic.memory.storage.MemoryStorage
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.common.getLogger
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of MemoryManager.
 *
 * Manages working memories, episodic memories, and semantic memories with
 * pluggable storage backend.
 *
 * @property storage The storage backend
 * @property config Memory configuration
 */
class DefaultMemoryManager(
    private val storage: MemoryStorage,
    private val config: MemoryConfig = MemoryConfig.DEFAULT
) : MemoryManager {

    private val logger = getLogger(this)
    private val workingMemories = ConcurrentHashMap<String, WorkingMemory>()

    companion object {
        /**
         * Create a default memory manager with file-based storage
         */
        fun createDefault(config: MemoryConfig = MemoryConfig.DEFAULT): DefaultMemoryManager {
            val storage = when (config.backend) {
                StorageBackend.IN_MEMORY -> InMemoryStorage()
                StorageBackend.FILE_BASED -> FileBasedStorage(config.baseDir)
                else -> throw UnsupportedOperationException("Backend ${config.backend} not yet implemented")
            }
            return DefaultMemoryManager(storage, config)
        }
    }

    override fun getWorkingMemory(sessionId: String): WorkingMemory {
        return workingMemories.getOrPut(sessionId) {
            WorkingMemory(
                sessionId = sessionId,
                taskContext = TaskContext(goal = "")
            )
        }
    }

    override suspend fun addToWorkingMemory(sessionId: String, state: AgentState) {
        val workingMemory = getWorkingMemory(sessionId)
        workingMemory.addState(state, maxSize = 50)
    }

    override suspend fun consolidateSession(
        sessionId: String,
        outcome: TaskOutcome,
        summary: String?
    ): EpisodicMemory {
        val workingMemory = workingMemories[sessionId]
            ?: throw IllegalStateException("No working memory found for session: $sessionId")

        val episode = EpisodicMemory(
            sessionId = sessionId,
            taskDescription = workingMemory.taskContext.goal,
            taskGoal = workingMemory.taskContext.goal,
            startTime = workingMemory.timestamp,
            endTime = Instant.now(),
            states = workingMemory.recentStates.toList(),
            outcome = outcome,
            summary = summary ?: generateSummary(workingMemory),
            keyLearnings = extractLearnings(workingMemory, outcome),
            tags = generateTags(workingMemory),
            domain = workingMemory.taskContext.domain,
            metadata = workingMemory.taskContext.metadata
        )

        storeEpisode(episode)

        // Clear working memory after consolidation
        workingMemories.remove(sessionId)

        return episode
    }

    override suspend fun storeEpisode(episode: EpisodicMemory): String {
        val memory = Memory.Episodic(
            id = episode.id,
            timestamp = episode.endTime,
            episode = episode
        )
        return storage.save(memory)
    }

    override suspend fun retrieveRelevant(query: MemoryQuery, limit: Int): List<Memory> {
        return storage.query(query).take(limit)
    }

    override suspend fun storeKnowledge(knowledge: SemanticMemory): String {
        val memory = Memory.Semantic(
            id = knowledge.id,
            timestamp = knowledge.created,
            knowledge = knowledge
        )
        return storage.save(memory)
    }

    override suspend fun searchKnowledge(
        query: String,
        category: MemoryCategory?,
        limit: Int
    ): List<SemanticMemory> {
        val memoryQuery = MemoryQuery.Similarity(
            queryText = query,
            category = category,
            limit = limit
        )
        
        return storage.query(memoryQuery)
            .filterIsInstance<Memory.Semantic>()
            .map { it.knowledge }
    }

    override suspend fun extractKnowledge(episodes: List<EpisodicMemory>): List<SemanticMemory> {
        // Simple knowledge extraction (can be enhanced with LLM in future)
        val knowledge = mutableListOf<SemanticMemory>()

        // Extract patterns from successful episodes
        val successful = episodes.filter { it.outcome == TaskOutcome.SUCCESS }
        if (successful.isNotEmpty()) {
            knowledge.add(SemanticMemory(
                category = MemoryCategory.PATTERN,
                content = "Successful pattern observed in ${successful.size} episodes",
                confidence = successful.size.toDouble() / episodes.size,
                sourceEpisodes = successful.map { it.id }
            ))
        }

        // Store extracted knowledge
        knowledge.forEach { storeKnowledge(it) }

        return knowledge
    }

    override suspend fun buildContext(
        currentTask: String,
        sessionId: String,
        maxTokens: Int
    ): AgentContext {
        val workingMemory = getWorkingMemory(sessionId)

        // Retrieve relevant episodes
        val relevantEpisodes = retrieveRelevant(
            MemoryQuery.Hybrid(context = currentTask, limit = 5)
        ).filterIsInstance<Memory.Episodic>()
            .map { it.episode }

        // Retrieve relevant knowledge
        val relevantKnowledge = searchKnowledge(currentTask, limit = 5)

        // Format context for LLM
        val formattedContext = formatContextForLLM(
            currentTask,
            relevantEpisodes,
            relevantKnowledge,
            maxTokens
        )

        return AgentContext(
            task = currentTask,
            workingMemory = workingMemory,
            relevantEpisodes = relevantEpisodes,
            relevantKnowledge = relevantKnowledge,
            formattedContext = formattedContext
        )
    }

    override suspend fun clearSession(sessionId: String) {
        workingMemories.remove(sessionId)
    }

    override suspend fun clearAll() {
        workingMemories.clear()
        storage.clear()
    }

    override suspend fun getStatistics(): MemoryStatistics {
        val totalCount = storage.count()
        val allMemories = storage.query(MemoryQuery.All)
        
        val episodicCount = allMemories.count { it is Memory.Episodic }.toLong()
        val semanticCount = allMemories.count { it is Memory.Semantic }.toLong()

        return MemoryStatistics(
            totalMemories = totalCount,
            episodicCount = episodicCount,
            semanticCount = semanticCount,
            workingMemoryCount = workingMemories.size
        )
    }

    // Helper methods

    private fun generateSummary(workingMemory: WorkingMemory): String {
        // Simple summary generation (can be enhanced with LLM)
        val states = workingMemory.recentStates
        return if (states.isEmpty()) {
            "No actions performed"
        } else {
            "Completed ${states.size} steps for task: ${workingMemory.taskContext.goal}"
        }
    }

    private fun extractLearnings(workingMemory: WorkingMemory, outcome: TaskOutcome): List<String> {
        // Simple learning extraction (can be enhanced with LLM)
        val learnings = mutableListOf<String>()
        
        if (outcome == TaskOutcome.SUCCESS) {
            learnings.add("Task completed successfully")
        }
        
        val states = workingMemory.recentStates
        if (states.isNotEmpty()) {
            learnings.add("Executed ${states.size} steps")
        }

        return learnings
    }

    private fun generateTags(workingMemory: WorkingMemory): Set<String> {
        val tags = mutableSetOf<String>()
        
        // Add domain tag if available
        workingMemory.taskContext.domain?.let { tags.add(it) }
        
        // Add goal-based tags (simple keyword extraction)
        val keywords = workingMemory.taskContext.goal
            .lowercase()
            .split("\\s+".toRegex())
            .filter { it.length > 3 }
            .take(5)
        
        tags.addAll(keywords)
        
        return tags
    }

    private fun formatContextForLLM(
        task: String,
        episodes: List<EpisodicMemory>,
        knowledge: List<SemanticMemory>,
        maxTokens: Int
    ): String {
        val sb = StringBuilder()
        
        sb.appendLine("# Agent Context")
        sb.appendLine()
        sb.appendLine("## Current Task")
        sb.appendLine(task)
        sb.appendLine()

        if (episodes.isNotEmpty()) {
            sb.appendLine("## Relevant Past Experiences")
            episodes.take(3).forEach { episode ->
                sb.appendLine("### ${episode.taskDescription}")
                sb.appendLine("- Outcome: ${episode.outcome}")
                sb.appendLine("- Summary: ${episode.summary}")
                if (episode.keyLearnings.isNotEmpty()) {
                    sb.appendLine("- Key Learnings: ${episode.keyLearnings.joinToString(", ")}")
                }
                sb.appendLine()
            }
        }

        if (knowledge.isNotEmpty()) {
            sb.appendLine("## Relevant Knowledge")
            knowledge.take(5).forEach { k ->
                sb.appendLine("- [${k.category}] ${k.content}")
            }
            sb.appendLine()
        }

        // Simple token approximation (4 chars ≈ 1 token)
        val text = sb.toString()
        val approxTokens = text.length / 4
        
        return if (approxTokens > maxTokens) {
            // Truncate if too long
            text.take(maxTokens * 4) + "..."
        } else {
            text
        }
    }
}
