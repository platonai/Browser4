package ai.platon.pulsar.agentic.memory

/**
 * L1 knowledge layer abstraction (semantic/procedural memory). The generic
 * memory skeleton only knows "retrieve knowledge" and "deposit a task";
 * domain-specific implementations (PEM = first implementation) decide what
 * knowledge means (selectors, anti-patterns, confidence...).
 *
 * Design: docs-dev/copilot/robust-browser-agent-memory-system-design.md (§8.1).
 */
interface MemoryKnowledgeProvider {

    /** Retrieve knowledge relevant to [taskText] / [url] within [scope]. */
    suspend fun query(taskText: String, url: String?, scope: MemoryScope): KnowledgeHits

    /**
     * Deposit the task identified by [taskId] (fold its L0 events into a
     * knowledge trace). Idempotent per task; returns true when deposited.
     */
    suspend fun deposit(taskId: String, scope: MemoryScope): Boolean
}

/** One knowledge hit (L1 tier). */
data class KnowledgeHit(
    val tier: String,
    val confidence: Double,
    val domain: String?,
    val intent: String?,
    val snippet: String,
)

/** Query result of an L1 provider. */
data class KnowledgeHits(
    val hits: List<KnowledgeHit> = emptyList(),
    val rendered: String = "",
)
