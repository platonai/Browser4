package ai.platon.pulsar.agentic.memory

/**
 * Disposable derived search index over the [AgentEventLog] (M2).
 *
 * The index is NEVER authoritative: it can be rebuilt from the log at any
 * time, and a corrupt/missing index only degrades search (exact reads and the
 * log itself are unaffected). Alignment is incremental via [sync] driven by a
 * per-backend watermark (the max synced event seq).
 *
 * Design: docs-dev/copilot/robust-browser-agent-memory-system-design.md (§6.3).
 */
interface MemoryQueryIndex : AutoCloseable {

    /**
     * Align the index with newly appended events. Events with `seq <=` the
     * current watermark are ignored (idempotent). Returns the new watermark.
     */
    fun sync(events: List<MemoryEvent>): Long

    /** Watermark of the highest synced event seq, or 0 when empty. */
    fun watermark(): Long

    /** Keyword search over indexed events. */
    fun searchEvents(
        query: String,
        filters: SearchFilters,
        cursor: Cursor?,
        limit: Int,
    ): SearchPage

    /** Keyword search over indexed task-level text. */
    fun searchTasks(
        query: String,
        filters: SearchFilters,
        cursor: Cursor?,
        limit: Int,
    ): SearchPage

    /** Drop one task from the index (must mirror [MemoryQueryService.forget]). */
    fun forget(taskId: String)

    /** Full rebuild from the event log (called when the index file is missing/corrupt). */
    fun rebuild()

    override fun close()
}
