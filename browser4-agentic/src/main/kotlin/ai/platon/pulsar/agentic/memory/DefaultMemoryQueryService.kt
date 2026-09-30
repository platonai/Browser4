package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Default [MemoryQueryService]: live-preferred resolution over the in-memory
 * [EventBuffer] plus the persisted [AgentEventLog], with an optional derived
 * search index (M2) for keyword search and a bounded naive fallback otherwise.
 */
class DefaultMemoryQueryService(
    private val eventLog: AgentEventLog,
    private val buffer: EventBuffer,
    private val enabled: Boolean = true,
    private val readWindowMax: Int = MemoryConfig.readWindowMax,
    private val snippetChars: Int = MemoryConfig.snippetChars,
    private val index: MemoryQueryIndex? = null,
) : MemoryQueryService {

    private val logger = getLogger(DefaultMemoryQueryService::class)

    override suspend fun listTasks(scope: MemoryScope, limit: Int): List<TaskRecord> =
        withContext(Dispatchers.IO) {
            if (!enabled) return@withContext emptyList()
            allEvents(scope.agentUuid)
                .groupBy { it.taskId }
                .values
                .map { buildTaskRecord(it) }
                .sortedByDescending { it.startedAt }
                .take(limit.coerceIn(1, 500))
        }

    override suspend fun listEvents(taskId: String, cursor: Long?, limit: Int): List<MemoryEvent> =
        withContext(Dispatchers.IO) {
            if (!enabled) return@withContext emptyList()
            allEvents(null)
                .filter { it.taskId == taskId && (cursor == null || it.seq > cursor) }
                .sortedBy { it.seq }
                .take(limit.coerceIn(1, 1000))
        }

    override suspend fun readEvent(taskId: String, seq: Long, before: Int, after: Int): EventWindow =
        withContext(Dispatchers.IO) {
            if (!enabled) return@withContext EventWindow(taskId, emptyList())
            val events = allEvents(null).filter { it.taskId == taskId }.sortedBy { it.seq }
            val idx = events.indexOfFirst { it.seq == seq }
            if (idx < 0) return@withContext EventWindow(taskId, emptyList())
            val from = (idx - before.coerceIn(0, readWindowMax)).coerceAtLeast(0)
            val to = (idx + 1 + after.coerceIn(0, readWindowMax)).coerceAtMost(events.size)
            EventWindow(taskId, events.subList(from, to))
        }

    override suspend fun traceTask(taskId: String): List<MemoryEvent> =
        withContext(Dispatchers.IO) {
            if (!enabled) return@withContext emptyList()
            allEvents(null).filter { it.taskId == taskId }.sortedBy { it.seq }
        }

    override suspend fun searchEvents(
        query: String,
        filters: SearchFilters,
        cursor: Cursor?,
        limit: Int,
    ): SearchPage = withContext(Dispatchers.IO) {
        if (!enabled || query.isBlank()) return@withContext SearchPage(emptyList())
        // Derived index first (M2): align from the live buffer when possible
        // (zero file I/O), falling back to a log scan on cold start.
        val indexed = index?.let { idx ->
            runCatching {
                val fresh = buffer.snapshot().filter { it.seq > idx.watermark() }
                if (fresh.isNotEmpty()) idx.sync(fresh)
                else idx.sync(eventLog.readSince(idx.watermark(), limit = 10_000))
            }.onFailure { logger.warn("memory.index.sync failed: {}", it.message) }
            runCatching { idx.searchEvents(query, filters, cursor, limit.coerceIn(1, 50)) }
                .onFailure { logger.warn("memory.index.search failed, falling back: {}", it.message) }
                .getOrNull()
        }
        if (indexed != null) return@withContext indexed

        val keywords = MemoryKeywords.extract(query)
        if (keywords.isEmpty()) return@withContext SearchPage(emptyList())
        // Task-level outcome map: the outcome filter applies to the TASK, not
        // to each event (a failed task's TaskStarted/ToolExecuted events carry
        // no outcome themselves).
        val all = allEvents(filters.agentUuid)
        val taskOutcomes = all.groupBy { it.taskId }
            .mapValues { (_, evs) -> taskOutcomeOf(evs) }
        val scored = all
            .filter { matchesFilters(it, filters) }
            .filter { filters.outcome == null || taskOutcomes[it.taskId] == filters.outcome }
            .mapNotNull { e ->
                val text = eventText(e)
                val score = keywords.count { text.contains(it) }
                if (score > 0) Triple(e, text, score) else null
            }
            .sortedWith(compareByDescending<Triple<MemoryEvent, String, Int>> { it.third }
                .thenByDescending { it.first.ts })
        // One (best) hit per task — same semantics as the FTS path.
        SearchPage(
            scored.distinctBy { it.first.taskId }
                .take(limit.coerceIn(1, 50))
                .map { (e, text, _) ->
                    SearchHit(e.taskId, e.ts, toolOf(e), snippetOf(text), tier = "L0")
                },
            nextCursor = null,
        )
    }

    override suspend fun searchTasks(
        query: String,
        filters: SearchFilters,
        cursor: Cursor?,
        limit: Int,
    ): SearchPage = withContext(Dispatchers.IO) {
        if (!enabled || query.isBlank()) return@withContext SearchPage(emptyList())
        val indexed = index?.let { idx ->
            runCatching {
                val fresh = buffer.snapshot().filter { it.seq > idx.watermark() }
                if (fresh.isNotEmpty()) idx.sync(fresh)
                else idx.sync(eventLog.readSince(idx.watermark(), limit = 10_000))
            }.onFailure { logger.warn("memory.index.sync failed: {}", it.message) }
            runCatching { idx.searchTasks(query, filters, cursor, limit.coerceIn(1, 50)) }
                .onFailure { logger.warn("memory.index.searchTasks failed, falling back: {}", it.message) }
                .getOrNull()
        }
        if (indexed != null) return@withContext indexed

        val keywords = MemoryKeywords.extract(query)
        if (keywords.isEmpty()) return@withContext SearchPage(emptyList())
        val tasks = allEvents(filters.agentUuid).groupBy { it.taskId }
        val scored = tasks.mapNotNull { (taskId, events) ->
            val text = taskText(taskId, events)
            val score = keywords.count { text.contains(it) }
            val outcomeOk = filters.outcome == null || taskOutcomeOf(events) == filters.outcome
            if (score > 0 && outcomeOk && events.any { matchesFilters(it, filters) }) {
                Triple(taskId, events, score)
            } else null
        }
            .sortedWith(compareByDescending<Triple<String, List<MemoryEvent>, Int>> { it.third }
                .thenByDescending { it.second.maxOf { e -> e.ts } })
        SearchPage(
            scored.take(limit.coerceIn(1, 50)).map { (taskId, events, _) ->
                SearchHit(taskId, events.maxOf { it.ts }, tool = null, snippetOf(taskText(taskId, events)), tier = "L0")
            },
            nextCursor = null,
        )
    }

    override suspend fun forget(taskId: String): Unit = withContext(Dispatchers.IO) {
        index?.forget(taskId)
        eventLog.deleteTask(taskId)
        buffer.removeTask(taskId)
    }

    // ─── internals ───────────────────────────────────────────────────────────

    /**
     * Live-preferred union: in-memory buffer first, persisted events fill the
     * gaps. Live events win on (taskId, seq) collisions (a persisted snapshot
     * can lag behind the live log).
     */
    private fun allEvents(agentUuid: String?): List<MemoryEvent> {
        val live = buffer.snapshot()
        val persisted = eventLog.readAll(agentUuid)
        val seen = HashSet<String>(live.size)
        val merged = ArrayList<MemoryEvent>(live.size + persisted.size)
        (live + persisted).forEach { e ->
            if (seen.add(e.taskId + ":" + e.seq)) merged.add(e)
        }
        return merged
    }

    private fun buildTaskRecord(events: List<MemoryEvent>): TaskRecord {
        val started = events.minByOrNull { it.ts }
        val finished = events.maxByOrNull { it.ts }
        val startedEvent = events.filterIsInstance<TaskStarted>().firstOrNull()
        val completed = events.filterIsInstance<Completed>().lastOrNull()
        val failed = events.filterIsInstance<Failed>().lastOrNull()
        val tools = events.filterIsInstance<ToolExecuted>()
        return TaskRecord(
            taskId = started?.taskId ?: events.first().taskId,
            agentUuid = started?.agentUuid,
            instruction = startedEvent?.instruction ?: "",
            engine = startedEvent?.engine,
            startedAt = started?.ts ?: 0,
            finishedAt = finished?.ts,
            outcome = completed?.outcome ?: if (failed != null) "failure" else null,
            urlCandidate = startedEvent?.urlCandidate,
            stepCount = tools.size,
            toolCount = tools.map { it.tool }.distinct().size,
        )
    }

    private fun matchesFilters(event: MemoryEvent, filters: SearchFilters): Boolean {
        if (filters.agentUuid != null && event.agentUuid != filters.agentUuid) return false
        if (filters.fromTs != null && event.ts < filters.fromTs) return false
        if (filters.toTs != null && event.ts > filters.toTs) return false
        if (filters.engine != null && event !is TaskStarted) return false
        if (filters.engine != null && event is TaskStarted && event.engine != filters.engine) return false
        // NOTE: outcome filtering is TASK-level (see searchEvents) — a task's
        // outcome lives on its Completed/Failed events, not on every event.
        return true
    }

    private fun taskOutcomeOf(events: List<MemoryEvent>): String? {
        val completed = events.filterIsInstance<Completed>().lastOrNull()
        if (completed != null) return completed.outcome
        if (events.any { it is Failed }) return "failure"
        return null
    }

    private fun eventText(event: MemoryEvent): String = when (event) {
        is TaskStarted -> listOf(event.instruction, event.engine, event.urlCandidate).filterNotNull().joinToString(" ")
        is ToolExecuted -> listOf(event.tool, event.argsBrief, event.resultBrief).joinToString(" ")
        is PageViewed -> listOf(event.url, event.title, event.viewType).joinToString(" ")
        is TextEmitted -> event.textBrief
        is NoteWritten -> listOf(event.key, event.valueBrief).joinToString(" ")
        is Completed -> listOf(event.summary, event.keyFindings?.joinToString(" ")).filterNotNull().joinToString(" ")
        is Failed -> listOf(event.errorBrief, event.failureCategory).filterNotNull().joinToString(" ")
    }

    private fun taskText(taskId: String, events: List<MemoryEvent>): String {
        val started = events.filterIsInstance<TaskStarted>().firstOrNull()?.instruction ?: ""
        val tools = events.filterIsInstance<ToolExecuted>().joinToString(" ") { it.tool }
        val pages = events.filterIsInstance<PageViewed>().joinToString(" ") { it.url }
        val completed = events.filterIsInstance<Completed>().lastOrNull()
        val summary = completed?.let { listOf(it.summary, it.keyFindings?.joinToString(" ")).filterNotNull().joinToString(" ") } ?: ""
        return listOf(started, tools, pages, summary).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun toolOf(event: MemoryEvent): String? = when (event) {
        is ToolExecuted -> event.tool
        is PageViewed -> "page_view"
        else -> null
    }

    private fun snippetOf(text: String): String = Sanitizer.brief(text, snippetChars)
}
