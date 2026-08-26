package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.common.getLogger
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite FTS5 derived search index over the [AgentEventLog] (M2).
 *
 * - **Disposable**: the index is never authoritative; delete the file and
 *   [rebuild] regenerates it from the log. A corrupt/unreadable index is
 *   dropped and rebuilt on first use.
 * - **Incremental alignment**: per-agent watermarks are tracked in a `meta`
 *   table, so events from multiple agents (shared backend) never collide on
 *   the per-sink `seq` counter; [sync] ingests only what is new per agent.
 * - **Bounded**: `LIMIT/OFFSET` pagination + snippet truncation.
 *
 * Storage: `events_fts` (event rows, content held in the FTS table) and
 * `tasks_fts` (task-level text) — both FTS5 virtual tables, so keyword search
 * uses the native `MATCH` operator; non-indexed filter columns (agent_uuid,
 * outcome, engine, ts) participate in WHERE clauses.
 *
 * Design: docs-dev/copilot/robust-browser-agent-memory-system-design.md (§6.3).
 */
class SqliteMemoryQueryIndex(
    dbPath: Path,
    private val eventLog: AgentEventLog,
    private val agentUuid: String? = null,
    private val snippetChars: Int = MemoryConfig.snippetChars,
) : MemoryQueryIndex {

    private val logger = getLogger(SqliteMemoryQueryIndex::class)

    private val lock = Any()

    private val conn: Connection = runCatching {
        Files.createDirectories(dbPath.parent)
        val c = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        c.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
        }
        c
    }.getOrElse {
        logger.warn("memory.index.open failed, rebuilding: {}", it.message)
        Files.deleteIfExists(dbPath)
        Files.deleteIfExists(dbPath.resolveSibling(dbPath.fileName.toString() + "-wal"))
        Files.deleteIfExists(dbPath.resolveSibling(dbPath.fileName.toString() + "-shm"))
        val c = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        c.createStatement().use { st -> st.execute("PRAGMA journal_mode=WAL") }
        c
    }

    init {
        synchronized(lock) {
            runCatching {
                conn.createStatement().use { st ->
                    st.execute(
                        """CREATE TABLE IF NOT EXISTS meta (
                            agent_uuid TEXT PRIMARY KEY, watermark INTEGER NOT NULL
                        )"""
                    )
                    st.execute(eventsSchemaSql())
                    st.execute(tasksSchemaSql())
                }
            }.onFailure { e ->
                logger.warn("memory.index.schema failed, dropping and rebuilding: {}", e.message)
                rebuildLocked()
            }
        }
    }

    override fun sync(events: List<MemoryEvent>): Long {
        if (events.isEmpty()) return watermark()
        synchronized(lock) {
            runCatching {
                events.filter { agentUuid == null || it.agentUuid == agentUuid }
                    .groupBy { it.agentUuid }
                    .forEach { (agent, group) ->
                        val w = watermarkOf(agent)
                        val fresh = group.filter { it.seq > w }.sortedBy { it.seq }
                        if (fresh.isEmpty()) return@forEach
                        fresh.forEach { e -> upsertEvent(e) }
                        // Task-level text is recomputed from the authoritative log.
                        val taskIds = fresh.map { it.taskId }.distinct()
                        taskIds.forEach { taskId -> upsertTask(agent, taskId) }
                        conn.prepareStatement(
                            "INSERT INTO meta(agent_uuid, watermark) VALUES(?, ?) " +
                                "ON CONFLICT(agent_uuid) DO UPDATE SET watermark = excluded.watermark"
                        ).use { ps ->
                            ps.setString(1, agent)
                            ps.setLong(2, fresh.maxOf { it.seq })
                            ps.executeUpdate()
                        }
                    }
            }.onFailure { logger.warn("memory.index.sync failed: {}", it.message) }
        }
        return watermark()
    }

    override fun watermark(): Long {
        synchronized(lock) {
            if (agentUuid != null) return watermarkOf(agentUuid)
            return conn.createStatement().use { st ->
                st.executeQuery("SELECT COALESCE(MAX(watermark), 0) FROM meta").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0
                }
            }
        }
    }

    override fun searchEvents(
        query: String, filters: SearchFilters, cursor: Cursor?, limit: Int,
    ): SearchPage {
        val terms = ftsTerms(query)
        if (terms.isEmpty()) return SearchPage(emptyList())
        synchronized(lock) {
            val offset = cursor?.offset ?: 0
            val requested = limit.coerceIn(1, 50)
            // Event-level MATCH; task-level outcome filter via a subquery; the
            // outer dedupe keeps ONE (rank-best) hit per task so callers see
            // tasks, not their event streams. Fetch extra rows for dedupe headroom.
            val sql = buildString {
                append("SELECT task_id, ts, tool, snippet(events_fts, 7, '[', ']', '…', ?) AS snip ")
                append("FROM events_fts WHERE events_fts MATCH ?")
                // `IS` (not `=`) so NULL columns compare equal to a NULL filter
                // (rows without outcome/engine must survive unfiltered searches).
                append(" AND agent_uuid IS COALESCE(?, agent_uuid)")
                append(" AND engine IS COALESCE(?, engine)")
                append(" AND ts >= COALESCE(?, ts)")
                append(" AND ts <= COALESCE(?, ts)")
                append(" AND (? IS NULL OR task_id IN (SELECT DISTINCT task_id FROM events_fts WHERE outcome = ?))")
                append(" ORDER BY rank LIMIT ? OFFSET ?")
            }
            val raw = mutableListOf<SearchHit>()
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, snippetChars)
                ps.setString(2, terms)
                bindNullableString(ps, 3, effectiveAgent(filters))
                bindNullableString(ps, 4, filters.engine)
                bindNullableLong(ps, 5, filters.fromTs)
                bindNullableLong(ps, 6, filters.toTs)
                bindNullableString(ps, 7, filters.outcome)
                bindNullableString(ps, 8, filters.outcome)
                ps.setInt(9, requested * 4)
                ps.setInt(10, offset)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        raw += SearchHit(
                            taskId = rs.getString(1),
                            ts = rs.getLong(2),
                            tool = rs.getString(3),
                            snippet = rs.getString(4) ?: "",
                            tier = "L0",
                        )
                    }
                }
            }
            // One (rank-best) hit per task; rows come rank-ordered.
            val hits = raw.distinctBy { it.taskId }.take(requested)
            return SearchPage(hits, nextCursor = Cursor(offset = offset + hits.size))
        }
    }

    override fun searchTasks(
        query: String, filters: SearchFilters, cursor: Cursor?, limit: Int,
    ): SearchPage {
        val terms = ftsTerms(query)
        if (terms.isEmpty()) return SearchPage(emptyList())
        synchronized(lock) {
            val offset = cursor?.offset ?: 0
            val sql = buildString {
                append("SELECT task_id, ts, snippet(tasks_fts, 3, '[', ']', '…', ?) AS snip ")
                append("FROM tasks_fts WHERE tasks_fts MATCH ?")
                append(" AND agent_uuid IS COALESCE(?, agent_uuid)")
                append(" AND ts >= COALESCE(?, ts)")
                append(" AND ts <= COALESCE(?, ts)")
                append(" ORDER BY rank LIMIT ? OFFSET ?")
            }
            val hits = mutableListOf<SearchHit>()
            conn.prepareStatement(sql).use { ps ->
                ps.setInt(1, snippetChars)
                ps.setString(2, terms)
                bindNullableString(ps, 3, effectiveAgent(filters))
                bindNullableLong(ps, 4, filters.fromTs)
                bindNullableLong(ps, 5, filters.toTs)
                ps.setInt(6, limit)
                ps.setInt(7, offset)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        hits += SearchHit(
                            taskId = rs.getString(1),
                            ts = rs.getLong(2),
                            tool = null,
                            snippet = rs.getString(3) ?: "",
                            tier = "L0",
                        )
                    }
                }
            }
            return SearchPage(hits, nextCursor = Cursor(offset = offset + hits.size))
        }
    }

    override fun forget(taskId: String) {
        synchronized(lock) {
            runCatching {
                conn.prepareStatement("DELETE FROM events_fts WHERE task_id = ?").use { ps ->
                    ps.setString(1, taskId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM tasks_fts WHERE task_id = ?").use { ps ->
                    ps.setString(1, taskId)
                    ps.executeUpdate()
                }
            }.onFailure { logger.warn("memory.index.forget failed: {}", it.message) }
        }
    }

    override fun rebuild() {
        synchronized(lock) { rebuildLocked() }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { conn.close() }
        }
    }

    // ─── internals ───────────────────────────────────────────────────────────

    private fun effectiveAgent(filters: SearchFilters): String? =
        filters.agentUuid ?: agentUuid

    private fun watermarkOf(agent: String): Long {
        return conn.prepareStatement("SELECT watermark FROM meta WHERE agent_uuid = ?").use { ps ->
            ps.setString(1, agent)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    private fun upsertEvent(e: MemoryEvent) {
        // FTS5 virtual tables do not support UPSERT — delete by the globally
        // unique seq (the only precise key; task_id alone would remove the
        // task's other events) then insert.
        conn.prepareStatement("DELETE FROM events_fts WHERE seq = ?").use { ps ->
            ps.setLong(1, e.seq)
            ps.executeUpdate()
        }
        conn.prepareStatement(
            """INSERT INTO events_fts(seq, task_id, agent_uuid, ts, tool, outcome, engine, text)
               VALUES(?, ?, ?, ?, ?, ?, ?, ?)"""
        ).use { ps ->
            ps.setLong(1, e.seq)
            ps.setString(2, e.taskId)
            ps.setString(3, e.agentUuid)
            ps.setLong(4, e.ts)
            ps.setString(5, toolOf(e))
            ps.setString(6, outcomeOf(e))
            ps.setString(7, engineOf(e))
            ps.setString(8, eventText(e))
            ps.executeUpdate()
        }
    }

    private fun upsertTask(agent: String, taskId: String) {
        val events = eventLog.readTask(agent, taskId)
        val text = taskTextOf(events)
        val ts = events.maxOfOrNull { it.ts } ?: 0
        conn.prepareStatement("DELETE FROM tasks_fts WHERE task_id = ? AND agent_uuid = ?").use { ps ->
            ps.setString(1, taskId)
            ps.setString(2, agent)
            ps.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO tasks_fts(task_id, agent_uuid, ts, text) VALUES(?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, taskId)
            ps.setString(2, agent)
            ps.setLong(3, ts)
            ps.setString(4, text)
            ps.executeUpdate()
        }
    }

    private fun bindNullableString(ps: java.sql.PreparedStatement, index: Int, value: String?) {
        if (value == null) ps.setNull(index, java.sql.Types.VARCHAR) else ps.setString(index, value)
    }

    private fun bindNullableLong(ps: java.sql.PreparedStatement, index: Int, value: Long?) {
        if (value == null) ps.setNull(index, java.sql.Types.BIGINT) else ps.setLong(index, value)
    }

    private fun rebuildLocked() {
        runCatching {
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS events_fts")
                st.execute("DROP TABLE IF EXISTS tasks_fts")
                st.execute("DELETE FROM meta")
            }
            conn.createStatement().use { st ->
                st.execute(eventsSchemaSql())
                st.execute(tasksSchemaSql())
            }
        }.onFailure { logger.warn("memory.index.rebuild failed: {}", it.message) }
        sync(eventLog.readAll(agentUuid))
    }

    private fun eventsSchemaSql(): String =
        """CREATE VIRTUAL TABLE IF NOT EXISTS events_fts USING fts5(
            seq UNINDEXED, task_id UNINDEXED, agent_uuid UNINDEXED, ts UNINDEXED,
            tool UNINDEXED, outcome UNINDEXED, engine UNINDEXED, text
        )"""

    private fun tasksSchemaSql(): String =
        """CREATE VIRTUAL TABLE IF NOT EXISTS tasks_fts USING fts5(
            task_id UNINDEXED, agent_uuid UNINDEXED, ts UNINDEXED, text
        )"""

    /** Build an FTS5 MATCH expression from a plain keyword query (stop-word-free, OR). */
    private fun ftsTerms(query: String): String = MemoryKeywords.ftsMatchExpression(query)

    private fun toolOf(e: MemoryEvent): String? = when (e) {
        is ToolExecuted -> e.tool
        is PageViewed -> "page_view"
        else -> null
    }

    private fun outcomeOf(e: MemoryEvent): String? = when (e) {
        is Completed -> e.outcome
        is Failed -> "failure"
        else -> null
    }

    private fun engineOf(e: MemoryEvent): String? = (e as? TaskStarted)?.engine

    private fun eventText(e: MemoryEvent): String = when (e) {
        is TaskStarted -> listOf(e.instruction, e.engine, e.urlCandidate).filterNotNull().joinToString(" ")
        is ToolExecuted -> listOf(e.tool, e.argsBrief, e.resultBrief).joinToString(" ")
        is PageViewed -> listOf(e.url, e.title, e.viewType).joinToString(" ")
        is TextEmitted -> e.textBrief
        is NoteWritten -> listOf(e.key, e.valueBrief).joinToString(" ")
        is Completed -> listOf(e.summary, e.keyFindings?.joinToString(" ")).filterNotNull().joinToString(" ")
        is Failed -> listOf(e.errorBrief, e.failureCategory).filterNotNull().joinToString(" ")
    }

    private fun taskTextOf(events: List<MemoryEvent>): String {
        if (events.isEmpty()) return ""
        val started = events.filterIsInstance<TaskStarted>().firstOrNull()?.instruction ?: ""
        val tools = events.filterIsInstance<ToolExecuted>().joinToString(" ") { it.tool }
        val pages = events.filterIsInstance<PageViewed>().joinToString(" ") { it.url }
        val completed = events.filterIsInstance<Completed>().lastOrNull()
        val summary = completed?.let {
            listOf(it.summary, it.keyFindings?.joinToString(" ")).filterNotNull().joinToString(" ")
        } ?: ""
        return listOf(started, tools, pages, summary).filter { it.isNotBlank() }.joinToString(" ")
    }
}
