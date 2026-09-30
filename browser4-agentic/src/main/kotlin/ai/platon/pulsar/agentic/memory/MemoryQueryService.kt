package ai.platon.pulsar.agentic.memory

/**
 * Scope of a memory read/write. Defaults to the current agent; a null
 * [agentUuid] means "this backend / all agents" (explicitly requested via
 * tools, never implicit).
 */
data class MemoryScope(
    val agentUuid: String? = null,
    val userId: String? = null,
)

/** One task's summary record (built by folding its events). */
data class TaskRecord(
    val taskId: String,
    val agentUuid: String?,
    val instruction: String,
    val engine: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val outcome: String?,
    val urlCandidate: String?,
    val stepCount: Int,
    val toolCount: Int,
)

/** Bounded window of events around a target event (`readEvent`). */
data class EventWindow(
    val taskId: String,
    val events: List<MemoryEvent>,
)

/** Filters for search / task listing. */
data class SearchFilters(
    val agentUuid: String? = null,
    val outcome: String? = null,
    val engine: String? = null,
    val fromTs: Long? = null,
    val toTs: Long? = null,
)

/** Opaque pagination cursor (taskId + seq of the last returned event, or a plain offset). */
data class Cursor(
    val taskId: String? = null,
    val seq: Long? = null,
    val offset: Int? = null,
)

/** One retrieval hit. [tier] is `L0` (facts) / `L1` (PEM knowledge) / `L2` (external). */
data class SearchHit(
    val taskId: String,
    val ts: Long,
    val tool: String?,
    val snippet: String,
    val tier: String = "L0",
)

/** One search result page. */
data class SearchPage(
    val hits: List<SearchHit>,
    val nextCursor: Cursor? = null,
)

/**
 * Unified query facade over the agent memory L0 event log (exact reads) and
 * the derived search index (FTS, when enabled). Live events (in-memory buffer)
 * take precedence over persisted events — an in-flight task is always
 * answerable even when its events have not hit disk yet.
 *
 * Design: docs-dev/copilot/robust-browser-agent-memory-system-design.md (§6).
 */
interface MemoryQueryService {

    /** Tasks of the scope, newest first. */
    suspend fun listTasks(scope: MemoryScope, limit: Int = 50): List<TaskRecord>

    /** Events of one task, oldest first, starting after [cursor]. */
    suspend fun listEvents(taskId: String, cursor: Long? = null, limit: Int = 100): List<MemoryEvent>

    /** Full target event plus bounded [before]/[after] neighbors. */
    suspend fun readEvent(taskId: String, seq: Long, before: Int = 0, after: Int = 0): EventWindow

    /** The complete event chain of one task, oldest first. */
    suspend fun traceTask(taskId: String): List<MemoryEvent>

    /** Keyword search over events (FTS index when available, naive fallback otherwise). */
    suspend fun searchEvents(
        query: String,
        filters: SearchFilters = SearchFilters(),
        cursor: Cursor? = null,
        limit: Int = 10,
    ): SearchPage

    /** Keyword search over task-level summaries. */
    suspend fun searchTasks(
        query: String,
        filters: SearchFilters = SearchFilters(),
        cursor: Cursor? = null,
        limit: Int = 10,
    ): SearchPage

    /** Explicitly forget one task (privacy / correction). */
    suspend fun forget(taskId: String)
}
