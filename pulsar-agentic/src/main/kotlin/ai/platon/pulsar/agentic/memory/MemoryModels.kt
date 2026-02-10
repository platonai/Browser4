package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.agentic.model.AgentState
import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant
import java.util.*

/**
 * Task outcome enumeration for episodic memory.
 */
enum class TaskOutcome {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILURE,
    ABORTED
}

/**
 * Memory category enumeration for semantic memory classification.
 */
enum class MemoryCategory {
    FACT,              // Domain facts (e.g., "Amazon search is at #twotabsearchtextbox")
    PATTERN,           // Behavioral patterns (e.g., "Login usually requires email then password")
    STRATEGY,          // Problem-solving strategies (e.g., "Use CSS selector before XPath")
    PREFERENCE,        // User preferences (e.g., "User prefers detailed output")
    ERROR_RESOLUTION,  // How to resolve common errors
    SKILL              // Learned skills (e.g., "Extract product info from Amazon")
}

/**
 * Task context for working memory.
 *
 * @property goal The task goal or instruction
 * @property domain Optional domain classification
 * @property subTasks List of sub-tasks if applicable
 * @property currentStep Current step in task execution
 * @property metadata Additional metadata
 */
data class TaskContext(
    val goal: String,
    val domain: String? = null,
    val subTasks: List<String> = emptyList(),
    val currentStep: Int = 0,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Working memory for current session.
 *
 * Maintains short-term context including recent states and temporary variables.
 * Volatile and session-scoped - cleared when session ends.
 *
 * @property sessionId Unique session identifier
 * @property taskContext The task context for this session
 * @property recentStates Recent agent states (limited queue)
 * @property scratchpad Temporary variables and values
 * @property timestamp Creation timestamp
 */
data class WorkingMemory(
    val sessionId: String,
    val taskContext: TaskContext,
    val recentStates: MutableList<AgentState> = mutableListOf(),
    val scratchpad: MutableMap<String, Any> = mutableMapOf(),
    val timestamp: Instant = Instant.now()
) {
    /**
     * Add a state to recent states, maintaining size limit
     */
    fun addState(state: AgentState, maxSize: Int = 50) {
        recentStates.add(state)
        if (recentStates.size > maxSize) {
            recentStates.removeAt(0)
        }
    }
}

/**
 * Episodic memory representing a complete task execution.
 *
 * Persistent record of a task with outcomes, summary, and learnings.
 * Stored across sessions for long-term reference.
 *
 * @property id Unique memory identifier
 * @property sessionId Session in which this episode occurred
 * @property taskDescription Description of the task
 * @property taskGoal The goal of the task
 * @property startTime Task start timestamp
 * @property endTime Task completion timestamp
 * @property states List of agent states during execution
 * @property outcome Task outcome
 * @property summary LLM-generated summary
 * @property keyLearnings List of key learnings from this episode
 * @property tags Tags for categorization
 * @property domain Optional domain classification
 * @property metadata Additional metadata
 */
data class EpisodicMemory(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val taskDescription: String,
    val taskGoal: String,
    val startTime: Instant,
    val endTime: Instant,
    val states: List<AgentState>,
    val outcome: TaskOutcome,
    val summary: String,
    val keyLearnings: List<String> = emptyList(),
    val tags: Set<String> = emptySet(),
    val domain: String? = null,
    val metadata: Map<String, Any> = emptyMap()
) {
    @get:JsonIgnore
    val duration: Long get() = endTime.toEpochMilli() - startTime.toEpochMilli()
    
    @get:JsonIgnore
    val isSuccess: Boolean get() = outcome == TaskOutcome.SUCCESS
}

/**
 * Semantic memory representing extracted knowledge.
 *
 * Generalized knowledge extracted from episodic memories.
 * Indexed and searchable for intelligent retrieval.
 *
 * @property id Unique memory identifier
 * @property category Memory category
 * @property content The knowledge content
 * @property embedding Optional vector embedding for semantic search
 * @property confidence Confidence score (0.0 to 1.0)
 * @property sourceEpisodes References to source episodes
 * @property timesUsed Usage counter
 * @property lastUsed Last usage timestamp
 * @property created Creation timestamp
 * @property tags Tags for categorization
 * @property metadata Additional metadata
 */
data class SemanticMemory(
    val id: String = UUID.randomUUID().toString(),
    val category: MemoryCategory,
    val content: String,
    @JsonIgnore
    var embedding: FloatArray? = null,
    val confidence: Double,
    val sourceEpisodes: List<String> = emptyList(),
    var timesUsed: Int = 0,
    var lastUsed: Instant? = null,
    val created: Instant = Instant.now(),
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, Any> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SemanticMemory

        if (id != other.id) return false
        if (category != other.category) return false
        if (content != other.content) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + content.hashCode()
        return result
    }
}

/**
 * Base sealed class for memory types.
 */
sealed class Memory {
    abstract val id: String
    abstract val timestamp: Instant

    /**
     * Episodic memory wrapper
     */
    data class Episodic(
        override val id: String,
        override val timestamp: Instant,
        val episode: EpisodicMemory
    ) : Memory()

    /**
     * Semantic memory wrapper
     */
    data class Semantic(
        override val id: String,
        override val timestamp: Instant,
        val knowledge: SemanticMemory
    ) : Memory()
}

/**
 * Base interface for memory queries.
 */
sealed interface MemoryQuery {
    /**
     * Query for all memories
     */
    object All : MemoryQuery

    /**
     * Recency-based query
     */
    data class Recency(
        val limit: Int = 10,
        val category: MemoryCategory? = null,
        val tags: Set<String> = emptySet()
    ) : MemoryQuery

    /**
     * Similarity-based query
     */
    data class Similarity(
        val queryText: String,
        val queryEmbedding: FloatArray? = null,
        val limit: Int = 5,
        val minSimilarity: Double = 0.7,
        val category: MemoryCategory? = null
    ) : MemoryQuery {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Similarity

            if (queryText != other.queryText) return false
            if (limit != other.limit) return false
            if (minSimilarity != other.minSimilarity) return false
            if (category != other.category) return false

            return true
        }

        override fun hashCode(): Int {
            var result = queryText.hashCode()
            result = 31 * result + limit
            result = 31 * result + minSimilarity.hashCode()
            result = 31 * result + (category?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Tag-based query
     */
    data class Tag(
        val tags: Set<String>,
        val matchAll: Boolean = false,
        val limit: Int = 10
    ) : MemoryQuery

    /**
     * Hybrid query combining multiple strategies
     */
    data class Hybrid(
        val recencyWeight: Double = 0.3,
        val similarityWeight: Double = 0.5,
        val relevanceWeight: Double = 0.2,
        val context: String,
        val limit: Int = 5
    ) : MemoryQuery
}

/**
 * Agent context built with memories.
 *
 * @property task Current task description
 * @property workingMemory Working memory for current session
 * @property relevantEpisodes Relevant episodic memories
 * @property relevantKnowledge Relevant semantic memories
 * @property formattedContext Formatted context string for LLM
 */
data class AgentContext(
    val task: String,
    val workingMemory: WorkingMemory,
    val relevantEpisodes: List<EpisodicMemory> = emptyList(),
    val relevantKnowledge: List<SemanticMemory> = emptyList(),
    val formattedContext: String = ""
)
