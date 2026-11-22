package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Best-effort, append-only persistence of agent task steps — one JSONL shard per task.
 *
 * The step log ([AgentTaskEventLog]) is an in-memory ring on purpose: it is read on every
 * `--follow` poll, so it must be cheap. The cost of that choice was a hard gap: a backend
 * restart restored the task's STATUS row and lost its whole PROCESS, so a supervisor could
 * see that a run had failed but not how it got there (issue #652 §4, the half that cursor
 * honesty does not fix).
 *
 * This store closes that gap with the cheapest thing that can work:
 *
 * - **write-behind, never on the agent's thread.** [append] only enqueues a serialized line;
 *   one daemon thread batches what is queued, groups it per task and appends it. A saturated
 *   queue drops lines (and says so on the first drop) instead of slowing a run down — the
 *   step log itself is still complete in memory, and a supervisor that slept through a gap
 *   is told by `resynced`/`dropped`.
 * - **bounded per task.** A shard that grows past [maxShardBytes] is rewritten to its newest
 *   [retainedEvents] lines (the same window the in-memory ring keeps), so a month of long
 *   runs cannot fill the disk with one task's history.
 * - **tolerant reads.** A crash can leave a half-written last line; [load] skips any line it
 *   cannot parse instead of failing the whole restore. That is the difference between "the
 *   last step is missing" and "the process is gone".
 *
 * The shard lives next to the task registry (`<data>/agent/events/<taskId>.jsonl`), so
 * [prune] can age it out with the same TTL as the status row.
 *
 * @param dir directory holding the shards (created on first write)
 * @param retainedEvents how many events one shard keeps when it is rotated
 * @param maxShardBytes rotation threshold (the newest [retainedEvents] lines are kept)
 * @param queueCapacity how many serialized lines may wait for the writer before drops begin
 * */
class AgentTaskEventStore(
    private val dir: Path,
    private val retainedEvents: Int = AgentTaskEventLog.DEFAULT_CAPACITY,
    private val maxShardBytes: Long = DEFAULT_MAX_SHARD_BYTES,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) : Closeable {
    private val logger = getLogger(AgentTaskEventStore::class)

    /** One line waiting for the writer: the task whose shard it belongs to, and the line. */
    private class PendingLine(val taskId: String, val line: String)

    private val queue = LinkedBlockingQueue<PendingLine>(queueCapacity)
    private val droppedLines = AtomicLong(0)
    private val writtenLines = AtomicLong(0)

    @Volatile
    private var closed = false

    /**
     * The writer thread is a daemon: a task that ends without a close (a crashed backend)
     * must not keep the JVM alive, and the OS closes the file handles anyway.
     */
    private val writer = Thread({ drainLoop() }, "b4-agent-event-store").apply {
        isDaemon = true
        start()
    }

    /** True when this store no longer accepts appends. */
    val isClosed: Boolean get() = closed

    /** Lines dropped because the writer could not keep up (diagnostics only). */
    fun droppedLineCount(): Long = droppedLines.get()

    /** Lines written to disk so far (diagnostics only). */
    fun writtenLineCount(): Long = writtenLines.get()

    /**
     * Persist one step, best effort.
     *
     * Never blocks the caller and never throws: a persistence failure must not be able to
     * fail the run it observes (the same rule the run-event listener follows).
     *
     * @param taskId the task the step belongs to
     * @param event the step as it was appended to the in-memory log
     */
    fun append(taskId: String, event: AgentTaskEvent) {
        if (closed || taskId.isBlank()) return
        val line = runCatching { pulsarObjectMapper().writeValueAsString(event) }.getOrNull() ?: return
        if (!queue.offer(PendingLine(fileNameOf(taskId), line))) {
            // Dropping is the designed back-pressure: the in-memory log is the fast path and
            // the reader is told about any gap through `resynced`/`dropped`.
            if (droppedLines.incrementAndGet() == 1L) {
                logger.warn(
                    "agent event persistence cannot keep up (queue={}); further steps are dropped from the on-disk log only",
                    queueCapacity
                )
            }
        }
    }

    /** True when a shard exists for [taskId] (cheap: a `stat`, not a parse). */
    fun hasShard(taskId: String): Boolean = Files.exists(dir.resolve(fileNameOf(taskId)))

    /**
     * The persisted steps of [taskId], oldest first.
     *
     * Reads the whole shard (bounded by [maxShardBytes]) and keeps the newest [limit] parseable
     * lines: a task whose shard was rotated or whose last line was cut off by a crash still
     * restores the tail instead of failing.
     *
     * @param taskId the task id (the same id `agent run` prints)
     * @param limit maximum events to return
     */
    fun load(taskId: String, limit: Int = retainedEvents): List<AgentTaskEvent> {
        // Flush what is still queued: a reader that arrives right after the last step must
        // see it, or "restart lost the tail" would be a bug of this store, not of the crash.
        flush()
        val path = dir.resolve(fileNameOf(taskId))
        if (!Files.exists(path)) return emptyList()
        val lines = runCatching { Files.readAllLines(path) }.getOrNull() ?: return emptyList()
        if (lines.isEmpty()) return emptyList()
        val mapper = pulsarObjectMapper()
        return lines.asSequence()
            .mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching { mapper.readValue(line, AgentTaskEvent::class.java) }.getOrNull()
            }
            .toList()
            .takeLast(limit.coerceAtLeast(0))
            .sortedBy { it.seq }
    }

    /** Block until everything queued has been written (bounded by a few seconds). */
    fun flush() {
        if (closed) return
        val deadline = System.currentTimeMillis() + FLUSH_TIMEOUT_MS
        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        // The queue can be empty while the writer is still flushing its last batch: give it
        // one scheduling slice so a test (and a restore) reads a complete file.
        Thread.sleep(FLUSH_SETTLE_MS)
    }

    /**
     * Delete shards that have not been touched since [olderThan].
     *
     * The event log and the status row share one TTL: a task nobody can poll any more has no
     * reader left for its steps.
     *
     * @param olderThan cutoff instant (files last modified before it are removed)
     * @return how many shards were removed
     */
    fun prune(olderThan: Instant): Int {
        if (!Files.isDirectory(dir)) return 0
        val cutoff = olderThan.toEpochMilli()
        var removed = 0
        runCatching {
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(SHARD_SUFFIX) }
                    .forEach { path ->
                        val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(Long.MAX_VALUE)
                        if (modified < cutoff) {
                            runCatching { Files.deleteIfExists(path) }
                                .onSuccess { removed++ }
                                .onFailure { logger.debug("could not delete the event shard {}: {}", path, it.message) }
                        }
                    }
            }
        }.onFailure { logger.debug("could not list the event shards under {}: {}", dir, it.message) }
        if (removed > 0) logger.debug("removed {} expired agent event shard(s) from {}", removed, dir)
        return removed
    }

    /** Write what is queued, then stop the writer. Appends after this call are ignored. */
    override fun close() {
        if (closed) return
        flush()
        closed = true
        // The drain loop exits on `closed`; interrupting it would abort a half-written batch.
        runCatching { writer.interrupt() }
        runCatching { writer.join(CLOSE_JOIN_MS) }
    }

    /** The one writer: batch, group by task, append, rotate when a shard is too big. */
    private fun drainLoop() {
        while (!closed) {
            val first = runCatching { queue.poll(WRITER_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrNull() ?: continue
            val batch = ArrayList<PendingLine>(MAX_BATCH)
            batch += first
            while (batch.size < MAX_BATCH) {
                val next = queue.poll() ?: break
                batch += next
            }
            runCatching { write(batch) }
                .onFailure { logger.warn("writing {} agent event line(s) failed: {}", batch.size, it.message) }
        }
    }

    /** Append one batch, grouping by task so each shard is opened once. */
    private fun write(batch: List<PendingLine>) {
        batch.groupBy { it.taskId }.forEach { (taskId, lines) ->
            val path = dir.resolve(taskId)
            runCatching {
                Files.createDirectories(dir)
                Files.write(
                    path,
                    lines.map { it.line },
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE,
                )
                writtenLines.addAndGet(lines.size.toLong())
            }.onFailure { logger.debug("appending {} line(s) to {} failed: {}", lines.size, path, it.message) }
            rotateIfOversized(path)
        }
    }

    /**
     * Cut an oversized shard down to its newest [retainedEvents] lines.
     *
     * Rotating by lines (not bytes) keeps the on-disk window the same shape as the in-memory
     * ring, so a restored log means the same thing as a live one.
     */
    private fun rotateIfOversized(path: Path) {
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        if (size <= maxShardBytes) return
        runCatching {
            val lines = Files.readAllLines(path)
            if (lines.size <= retainedEvents) return
            val kept = lines.takeLast(retainedEvents)
            val tmp = path.resolveSibling("${path.fileName}.tmp")
            Files.write(tmp, kept, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            logger.info("rotated agent event shard {} ({} bytes, {} -> {} lines)", path.fileName, size, lines.size, kept.size)
        }.onFailure { logger.warn("rotating {} failed: {}", path, it.message) }
    }

    companion object {
        /** File-name suffix of one task's shard. */
        const val SHARD_SUFFIX = ".jsonl"

        /** Default rotation threshold: ~4 MB of 240-char previews is far more than 1000 steps. */
        const val DEFAULT_MAX_SHARD_BYTES = 4L * 1024 * 1024

        /** How many serialized lines may wait for the writer. */
        const val DEFAULT_QUEUE_CAPACITY = 4096

        /** Lines written in one batch (one open/close per shard per batch). */
        private const val MAX_BATCH = 128

        /** How long the writer waits for work before re-checking `closed`. */
        private const val WRITER_POLL_MS = 200L

        /** How long [flush] waits for the queue to drain. */
        private const val FLUSH_TIMEOUT_MS = 2_000L

        /** Scheduling settle after the queue empties, so the last batch lands. */
        private const val FLUSH_SETTLE_MS = 10L

        /** How long [close] waits for the writer thread. */
        private const val CLOSE_JOIN_MS = 500L

        /** The shard file name of [taskId]; ids are UUIDs, but never trust a path segment. */
        fun fileNameOf(taskId: String): String {
            val safe = taskId.map { ch ->
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '_'
            }.joinToString("")
            return if (safe.endsWith(SHARD_SUFFIX)) safe else safe + SHARD_SUFFIX
        }
    }
}
