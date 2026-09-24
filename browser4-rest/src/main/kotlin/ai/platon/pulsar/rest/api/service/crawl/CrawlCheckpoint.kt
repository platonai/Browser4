package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.annotation.JsonIgnore
import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * One seed's slice of a resumable crawl checkpoint.
 *
 * A checkpoint is **work state**, not a result: [pages] is there so a resumed run
 * can report the union of the rows the first run collected, and the rest is what
 * the resumed run has to do about the URLs that never settled.
 *
 * The accounting is the same law the terminal record obeys, one submission at a
 * time, with the outstanding URLs deliberately *not* counted as losses:
 *
 * ```
 * pagesExpected == pages.size + failed.size + outstanding.size
 * ```
 *
 * [frontier] is outside the law on purpose: those URLs were discovered but never
 * handed to the session, so counting them as expected would invent submissions
 * that never happened.
 *
 * @property url the seed URL this slice belongs to (the URL the task was
 *   submitted for, so a resumed run can report the same seed list).
 * @property depth the seed's own depth (0 for every seed today; kept because the
 *   work state of a resumed round is depth-driven).
 * @property completed true once the seed's round settled every URL it submitted.
 *   A completed seed is not re-run at all on resume — its [pages] are restored.
 * @property status the seed's last reported status ("fetched", "error",
 *   "interrupted", …), so a resumed run reports what the first run saw instead of
 *   inventing a fresh one.
 * @property pages the rows the seed's round recorded before the interruption.
 * @property failed terminal failures: the URLs that were fetched and will never
 *   produce a page.  A resume keeps them failed unless `--retry-failed` is given.
 * @property outstanding URLs that were submitted and never settled — the ones a
 *   resume re-submits with a fresh delivery-attempt budget.
 * @property frontier links discovered at `depth < maxDepth` that were never
 *   submitted.  This is what lets a `--depth >= 1` crawl continue from where it
 *   stopped instead of restarting at the seeds.
 * @property pagesExpected how many URLs this seed's round had submitted, so the
 *   merged record can keep `pagesFound + failedPages.size == pagesExpected`.
 * @property linksDiscovered out-links the seed's round discovered before the
 *   interruption (restored so the merged report does not lose them).
 */
data class CrawlSeedCheckpoint(
    val url: String,
    val depth: Int = 0,
    val completed: Boolean = false,
    val status: String = STATUS_PENDING,
    val pages: List<CrawlPageResult> = emptyList(),
    val failed: List<CrawlFailedPage> = emptyList(),
    val outstanding: List<CrawlFailedPage> = emptyList(),
    val frontier: List<CrawlFailedPage> = emptyList(),
    val pagesExpected: Int = 0,
    val linksDiscovered: Int = 0,
    val error: String? = null,
) {
    /**
     * Every URL this seed's crawl already knows about — fetched, failed, in
     * flight or merely discovered.
     *
     * A resumed round seeds its `visited` set with these so a page that was
     * already handled is not queued a second time when another page links to it.
     */
    fun knownUrls(): Set<String> = buildSet {
        pages.forEach { add(normalizeForVisit(it.url)) }
        failed.forEach { add(normalizeForVisit(it.url)) }
        outstanding.forEach { add(normalizeForVisit(it.url)) }
        frontier.forEach { add(normalizeForVisit(it.url)) }
    }

    /**
     * Every URL still to fetch: the ones in flight when the process died plus the
     * discovered-but-unsubmitted frontier.
     *
     * A *completed* seed can still have a frontier: its round ended before the links
     * of a late-parsed page could be queued, and following them is the difference
     * between resuming a crawl and re-running it.
     */
    fun remaining(): Int = outstanding.size + frontier.size

    /** True when this seed's round had handed at least one URL to the session. */
    fun started(): Boolean = pages.isNotEmpty() || failed.isNotEmpty() || outstanding.isNotEmpty()

    companion object {
        /** The seed was never picked up. */
        const val STATUS_PENDING = "pending"

        /** The seed's round was cut off with work left to do. */
        const val STATUS_INTERRUPTED = "interrupted"
    }
}

/**
 * Everything needed to continue an interrupted crawl exactly where it stopped:
 * the input contract that produced it, the per-seed work state, and the run
 * bookkeeping that makes a merged result readable.
 *
 * This is what survives a `SIGKILL`: it is written to its own file (outside the
 * bounded, TTL-purged task store) so neither the 100-entry LRU nor the 1-day task
 * TTL can discard a resumable task's work.  `crawl clear` is the explicit way to
 * throw it away.
 *
 * @property taskId the task this checkpoint belongs to; the resumed run keeps it,
 *   so a caller that knows the id never has to be told a new one.
 * @property request the input contract — seed URLs, `depth`, the LoadOptions
 *   `args` (out-link selector/pattern, top-links, ignore-url-query, no-norm,
 *   readonly, expires, priority), the X-SQL query, the parallelism budget and the
 *   task budget.  Enough to reproduce the run without the user re-typing it.
 * @property seedUrls the resolved seed URLs (a `--seed-file` is resolved by the
 *   CLI before submission, so the file's contents are captured here, not its
 *   path).
 * @property resumedFrom when the interruption this checkpoint continues from was
 *   recorded; null for a checkpoint of a run that was never interrupted.
 * @property run which run produced this checkpoint: 1 for the original
 *   submission, 2 after the first resume, and so on.
 */
data class CrawlCheckpoint(
    val taskId: String,
    /**
     * The seed URL the run started from, and the LoadOptions `args` it ran with —
     * the out-link selector/pattern, top-links, ignore-url-query, no-norm, readonly,
     * expires and priority.
     */
    val url: String = "",
    val args: String = "",
    val depth: Int = 1,
    /** The X-SQL query, so a resumed run extracts the same rows without being told again. */
    val sql: String? = null,
    val seedUrls: List<String> = emptyList(),
    val parallelTabs: Int = 0,
    val taskTimeoutMillis: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val resumeCount: Int = 0,
    val resumedFrom: Instant? = null,
    val run: Int = 1,
    val seeds: List<CrawlSeedCheckpoint> = emptyList(),
) {
    /**
     * The input contract as the executor wants it, rebuilt from the flat fields
     * above.
     *
     * Deliberately **not** a serialized field: `CrawlRequest` carries a
     * primary-constructor `@JsonCreator`, and Kotlin copies that annotation onto the
     * synthetic no-arg constructor it emits for an all-default parameter list, which
     * makes a plain Jackson mapper refuse the class for reading *and* writing
     * ("conflicting property-based creators" — see `CrawlParallelBudgetTest`).  A
     * checkpoint that has to survive a restart cannot depend on that, so it stores
     * the contract field by field and rebuilds the DTO here.
     */
    @get:JsonIgnore
    val request: CrawlRequest
        get() = CrawlRequest(
            url = url,
            args = args,
            depth = depth,
            sql = sql,
            parallelTabs = parallelTabs.takeIf { it > 0 },
            taskTimeoutMillis = taskTimeoutMillis.takeIf { it > 0 }
        )
    /**
     * True when this checkpoint can continue a crawl: it knows the seeds it set
     * out to fetch.  (The work state may still be empty — a task killed before it
     * started a single round resumes from its seeds.)
     */
    val resumable: Boolean get() = seedUrls.isNotEmpty()

    /** URLs this checkpoint will not fetch again: the rows its runs already recorded. */
    fun skippedAlreadyFetched(): Int = seeds.sumOf { it.pages.size }

    /**
     * How much is left to fetch: the in-flight and frontier URLs of every started
     * seed, plus one per seed that was never picked up (its own URL, at least).
     */
    fun remaining(): Int = seedUrls.indices.sumOf { index ->
        val seed = seeds.getOrNull(index)
        if (seed == null || !seed.started()) 1 else seed.remaining()
    }

    /** The slice of seed [index], or null when that seed was never picked up. */
    fun seed(index: Int): CrawlSeedCheckpoint? = seeds.getOrNull(index)

    /**
     * Whether a resume could still do something with this checkpoint: URLs were left
     * unsettled, or a link was discovered and never queued, or URLs failed terminally
     * — which `crawl resume --retry-failed` fetches again.
     *
     * A checkpoint with no work left is deleted rather than kept: `crawl status` and
     * `crawl resume` both answer "nothing to continue", and the rows are already in
     * the task record.
     */
    fun hasWork(): Boolean = remaining() > 0 || seeds.any { it.failed.isNotEmpty() }

    /** Replace the slice of seed [index], keeping every other seed's work state. */
    fun withSeed(index: Int, seed: CrawlSeedCheckpoint): CrawlCheckpoint {
        val updated = seeds.toMutableList()
        while (updated.size <= index) {
            val missing = updated.size
            updated.add(CrawlSeedCheckpoint(url = seedUrls.getOrElse(missing) { "" }))
        }
        updated[index] = seed
        return copy(seeds = updated, updatedAt = System.currentTimeMillis())
    }

    companion object {
        /**
         * The checkpoint of a task that has not fetched anything yet: the input
         * contract, written before the first page so a task killed in its first
         * second is still resumable.
         */
        fun ofRequest(
            taskId: String,
            request: CrawlRequest,
            seedUrls: List<String>,
            parallelTabs: Int,
            taskTimeoutMillis: Long,
            run: Int = 1,
        ): CrawlCheckpoint = CrawlCheckpoint(
            taskId = taskId,
            url = request.url,
            args = request.args,
            depth = request.depth,
            sql = request.sql,
            seedUrls = seedUrls,
            parallelTabs = parallelTabs,
            taskTimeoutMillis = taskTimeoutMillis,
            run = run,
            seeds = seedUrls.map { CrawlSeedCheckpoint(url = it) }
        )
    }
}

/**
 * One row as it was appended to a task's row log, with the seed it was recorded for.
 *
 * @property seed the index of the seed whose round recorded the row.
 * @property row the row itself; null only for a line that was written by a newer
 *   version this one cannot read, which is skipped.
 */
data class CrawlCheckpointRow(
    val seed: Int = 0,
    val row: CrawlPageResult? = null,
)

/**
 * Fold the rows appended since the last state write into a loaded checkpoint.
 *
 * The state file is a periodic *rewrite*, so a row that settled between two writes
 * is not in it — and a resumed crawl that does not know about such a row would
 * request that URL a second time, which is exactly what a resume promises never to
 * do (issue #606).  The row log is an append per settled row, so it is durable the
 * moment the row is recorded; merging it back is what makes "already fetched" mean
 * what it says.
 *
 * The accounting law is preserved exactly, which is the reason for the three-way
 * bookkeeping below: every appended row is either already known (nothing to do),
 * still listed as in flight or failed (it is removed there — it settled), or was
 * submitted after the last state write captured the submission (its expected count
 * is raised, because the crawl did set out to fetch it).
 *
 * @param appended rows read from the task's row log, in no particular order.
 */
internal fun mergeAppendedRows(
    checkpoint: CrawlCheckpoint,
    appended: List<CrawlCheckpointRow>,
): CrawlCheckpoint {
    if (appended.isEmpty()) return checkpoint
    val bySeed = appended.filter { it.row != null }
        .groupBy({ it.seed }, { it.row!! })
        .filterKeys { it in checkpoint.seedUrls.indices }
    if (bySeed.isEmpty()) return checkpoint

    var changed = false
    val merged = checkpoint.seeds.toMutableList()
    while (merged.size < checkpoint.seedUrls.size) {
        merged.add(CrawlSeedCheckpoint(url = checkpoint.seedUrls[merged.size]))
    }
    bySeed.forEach { (index, rows) ->
        val seed = merged[index]
        val known = seed.pages.map { normalizeForVisit(it.url) }.toSet()
        val fresh = rows.filterNot { normalizeForVisit(it.url) in known }
            .distinctBy { normalizeForVisit(it.url) }
        if (fresh.isEmpty()) return@forEach
        val freshKeys = fresh.map { normalizeForVisit(it.url) }.toSet()
        val accounted = seed.knownUrls()
        // A row whose URL the state file never even listed as submitted: the crawl
        // did set out to fetch it, so it is one more expected page.
        val unaccounted = fresh.count { normalizeForVisit(it.url) !in accounted }
        val outstandingAfter = seed.outstanding.filterNot { normalizeForVisit(it.url) in freshKeys }
        // Every URL the round was still waiting for now has a row, and no discovered
        // link is left unqueued: the seed finished after the last state write, and a
        // resume must not run it again.
        val completedAfter = seed.completed || (
            seed.outstanding.isNotEmpty() && outstandingAfter.isEmpty() && seed.frontier.isEmpty()
            )
        merged[index] = seed.copy(
            pages = seed.pages + fresh,
            failed = seed.failed.filterNot { normalizeForVisit(it.url) in freshKeys },
            outstanding = outstandingAfter,
            pagesExpected = seed.pagesExpected + unaccounted,
            // A seed with rows has been picked up, whatever the state file recorded
            // before the rows settled; only a seed whose round is known to have
            // finished is labelled as fetched.
            status = when {
                completedAfter -> "fetched"
                seed.status == CrawlSeedCheckpoint.STATUS_PENDING -> CrawlSeedCheckpoint.STATUS_INTERRUPTED
                else -> seed.status
            },
            completed = completedAfter
        )
        changed = true
    }
    return if (changed) checkpoint.copy(seeds = merged, updatedAt = System.currentTimeMillis()) else checkpoint
}

/**
 * How often a task's checkpoint may hit the disk.
 *
 * The checkpoint is a *rewrite* (atomic replace), so its cost grows with the state
 * it carries — and so does the price of writing it on every settled URL of a large
 * crawl.  This policy is the answer:
 *
 *  * a write happens when enough has changed ([everySettled]) or when the state has
 *    been stale for long enough ([staleAfterMillis]);
 *  * and never more often than the state's own size allows
 *    ([maxBytesPerSecond]), because that is the only thing that actually costs
 *    anything: writing a 1 KB checkpoint every 250 ms is 4 KB/s, writing a 1 MB one
 *    that often is 4 MB/s.
 *
 * The consequence is stated rather than hidden: a hard kill can cost the URLs
 * settled since the last write.  For a crawl whose checkpoint is small — the common
 * case, and the one the resume guarantee is written for — that window is the
 * change threshold, so a `SIGKILL` cannot re-fetch work that was already finished.
 * A very large crawl trades a wider window for bounded checkpoint I/O, and forced
 * writes (a round ending, a status transition, shutdown) are never rate-limited, so
 * the state on disk is complete at every transition.
 */
internal class CheckpointWritePolicy(
    private val everySettled: Int = DEFAULT_EVERY_SETTLED,
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MS,
    private val maxBytesPerSecond: Int = DEFAULT_MAX_BYTES_PER_SECOND,
) {
    private var lastWriteMillis = 0L
    private var lastSettled = 0
    private var lastBytes = 0

    /** Whether a write is due, given how many URLs have settled so far. */
    fun due(settled: Int, now: Long = System.currentTimeMillis(), force: Boolean = false): Boolean {
        if (force) return true
        if (lastWriteMillis == 0L) return true
        val elapsed = now - lastWriteMillis
        if (elapsed < minimalIntervalMillis()) return false
        return settled - lastSettled >= everySettled || elapsed >= staleAfterMillis
    }

    /** Record that a write of [bytes] happened with [settled] URLs settled. */
    fun record(bytes: Int, settled: Int, now: Long = System.currentTimeMillis()) {
        lastWriteMillis = now
        lastSettled = settled
        lastBytes = bytes.coerceAtLeast(0)
    }

    /** The shortest interval this state's size allows. */
    fun minimalIntervalMillis(): Long {
        if (lastBytes <= 0 || maxBytesPerSecond <= 0) return DEFAULT_MIN_INTERVAL_MS
        val bySize = (lastBytes.toLong() * 1000L) / maxBytesPerSecond
        return maxOf(DEFAULT_MIN_INTERVAL_MS, bySize)
    }

    companion object {
        /** How many settled URLs may pass before a write is due. */
        const val DEFAULT_EVERY_SETTLED = 5

        /**
         * The floor between two writes, whatever their size.
         *
         * A crawl settles pages in bursts, and rewriting the same file several times
         * within a second buys a smaller loss window than it costs in I/O.
         */
        const val DEFAULT_MIN_INTERVAL_MS = 250L

        /** How long the state may be stale before a write is due, changed or not. */
        const val DEFAULT_STALE_AFTER_MS = 2_000L

        /**
         * The checkpoint write rate one task may spend.  A crawl's own result data
         * flows at about this rate anyway, so the checkpoint never dominates the
         * crawl's I/O; above it, writes are spaced out in proportion to the state.
         */
        const val DEFAULT_MAX_BYTES_PER_SECOND = 64 * 1024
    }
}

/**
 * Crash-safe, per-task storage for [CrawlCheckpoint]s.
 *
 * Two files per task:
 *
 *  * `<taskId>.json` — the work state (the input contract, and per seed what was
 *    fetched, what failed, what is in flight and what is merely discovered),
 *    written by **atomic replace**: the new state goes to a sibling `.tmp` file,
 *    is flushed to disk, and is then moved over the live file.  A crash therefore
 *    leaves either the previous checkpoint or the new one — never a half-written
 *    one — which is what makes "resume after a truncated checkpoint" a non-event
 *    rather than a corrupted task.  The previous good file is kept as
 *    `<taskId>.json.bak` and is used when the live file cannot be parsed (a
 *    disk-full truncation, a partially synced file on an exotic filesystem).
 *  * `<taskId>.rows.jsonl` — the rows, **appended** as each one settles.  A
 *    periodic rewrite cannot contain the rows that settled since the previous
 *    write, and a resume that does not know about such a row would request the URL
 *    again — the one thing a resume promises never to do.  An append costs a few
 *    hundred bytes per settled URL and is durable immediately, so the two files
 *    together mean: *the work state may be a second or two old, the fact that a URL
 *    was already fetched may not.*
 *
 * A checkpoint that cannot be parsed degrades to "no checkpoint" (via the `.bak`
 * copy first): the task is then reported as not resumable instead of taking the
 * server down or re-fetching work it already has.
 *
 * Files are deliberately **not** size-bounded and **not** TTL-purged: a
 * resumable task's work state must outlive the task store's 100-entry LRU and
 * its 1-day TTL, and `crawl clear` is the explicit way to discard it.
 */
class CrawlCheckpointStore(private val dir: Path) {
    private val logger = LoggerFactory.getLogger(CrawlCheckpointStore::class.java)

    private val mapper = pulsarObjectMapper()

    /** Serializes appends: several seeds of one task record rows concurrently. */
    private val appendLock = Any()

    /** The checkpoint of [taskId], or null when none was persisted (or it is unreadable). */
    fun load(taskId: String): CrawlCheckpoint? {
        if (taskId.isBlank()) return null
        val state = read(fileFor(taskId)) ?: run {
            // The live file is unreadable: fall back to the last known-good copy
            // rather than reporting "no checkpoint" and re-fetching work the crawl
            // already has.
            val backup = backupFileFor(taskId)
            if (!Files.exists(backup)) return null
            read(backup)?.also {
                logger.warn(
                    "Crawl {}: the checkpoint file is unreadable; continuing from the previous good copy {}",
                    taskId, backup
                )
            }
        } ?: return null
        // Rows settle between two state writes; the row log is what makes them
        // durable, so the state is completed with them before it is handed out.
        return mergeAppendedRows(state, loadRows(taskId))
    }

    /**
     * Append one settled row to the task's row log.
     *
     * An append, deliberately: a rewrite of the whole state on every settled URL
     * would cost more the more the crawl has collected, while a row is a few hundred
     * bytes that are written exactly once.  This is what makes "an already-fetched
     * URL is never requested again" true even when the process is killed between two
     * state writes (see [mergeAppendedRows]).
     *
     * @return true when the row reached the disk.
     */
    fun appendRow(taskId: String, seedIndex: Int, row: CrawlPageResult): Boolean {
        if (taskId.isBlank()) return false
        return try {
            val line = mapper.writeValueAsString(CrawlCheckpointRow(seed = seedIndex, row = row))
            synchronized(appendLock) {
                Files.createDirectories(dir)
                Files.writeString(
                    rowsFileFor(taskId),
                    line + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                )
            }
            true
        } catch (e: Exception) {
            logger.warn("Crawl {}: failed to persist a checkpoint row for '{}': {}", taskId, row.url, e.message)
            false
        }
    }

    /**
     * The rows appended for [taskId], in write order.
     *
     * A corrupt or truncated line is skipped, like every other persisted line in this
     * service: a partially written row costs one re-fetch, never the crawl.
     */
    fun loadRows(taskId: String): List<CrawlCheckpointRow> {
        val file = rowsFileFor(taskId)
        if (!Files.isRegularFile(file)) return emptyList()
        return try {
            Files.newBufferedReader(file).use { reader ->
                reader.lineSequence().mapNotNull { line ->
                    if (line.isBlank()) return@mapNotNull null
                    runCatching { mapper.readValue(line, CrawlCheckpointRow::class.java) }
                        .onFailure { logger.debug("Skipping a corrupt checkpoint row in {}: {}", file, it.message) }
                        .getOrNull()
                }.toList()
            }
        } catch (e: Exception) {
            logger.warn("Crawl {}: failed to read the checkpoint row log {}: {}", taskId, file, e.message)
            emptyList()
        }
    }

    /**
     * Persist [checkpoint], replacing the previous state atomically.
     *
     * @return the number of bytes written, or `-1` when the state did not reach
     *   the disk.  A checkpoint failure must never take a crawl down, so the
     *   caller keeps running — but the loss is logged rather than swallowed, and
     *   the byte count is what the caller's write policy needs (see
     *   [CheckpointWritePolicy]).
     */
    fun save(checkpoint: CrawlCheckpoint): Int {
        if (checkpoint.taskId.isBlank()) return -1
        val target = fileFor(checkpoint.taskId)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        return try {
            Files.createDirectories(dir)
            val payload = mapper.writeValueAsBytes(checkpoint)
            FileChannel.open(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(payload))
                // Flush the bytes before the rename: without this a machine-level
                // crash can leave the *new* name pointing at a file whose contents
                // never reached the platter.  (A killed process does not need it —
                // the OS keeps what was written — but the rename is the promise.)
                channel.force(true)
            }
            if (Files.exists(target)) {
                runCatching {
                    Files.copy(target, backupFileFor(checkpoint.taskId), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            moveInto(tmp, target)
            payload.size
        } catch (e: Exception) {
            logger.warn("Crawl {}: failed to persist the resume checkpoint: {}", checkpoint.taskId, e.message)
            runCatching { Files.deleteIfExists(tmp) }
            -1
        }
    }

    /** Discard the checkpoint of [taskId] — the task has no work left, or was cleared. */
    fun delete(taskId: String) {
        if (taskId.isBlank()) return
        runCatching { Files.deleteIfExists(fileFor(taskId)) }
        runCatching { Files.deleteIfExists(backupFileFor(taskId)) }
        runCatching { Files.deleteIfExists(rowsFileFor(taskId)) }
        runCatching { Files.deleteIfExists(fileFor(taskId).resolveSibling("${taskId}.json.tmp")) }
    }

    /** Discard every checkpoint (used by `crawl clear --all`). */
    fun deleteAll(): Int {
        if (!Files.isDirectory(dir)) return 0
        val files = runCatching {
            Files.list(dir).use { stream -> stream.filter { Files.isRegularFile(it) }.toList() }
        }.getOrElse { emptyList() }
        var removed = 0
        files.forEach { file ->
            val name = file.fileName.toString()
            // Only ever remove files this store owns: the directory may be
            // shared with an operator's own notes.
            if (!name.endsWith(".json") && !name.endsWith(".json.bak") &&
                !name.endsWith(".json.tmp") && !name.endsWith(".rows.jsonl")
            ) {
                return@forEach
            }
            if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) removed++
        }
        return removed
    }

    /** The task ids that currently have a checkpoint on disk. */
    fun taskIds(): List<String> {
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.list(dir).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { it.endsWith(".json") }
                    .map { it.removeSuffix(".json") }
                    .toList()
            }
        }.getOrElse { emptyList() }
    }

    fun fileFor(taskId: String): Path = dir.resolve("$taskId.json")

    /** The append-only row log of [taskId] (see [appendRow]). */
    fun rowsFileFor(taskId: String): Path = dir.resolve("$taskId.rows.jsonl")

    private fun backupFileFor(taskId: String): Path = dir.resolve("$taskId.json.bak")

    private fun read(file: Path): CrawlCheckpoint? {
        if (!Files.isRegularFile(file)) return null
        return try {
            val text = Files.readString(file)
            if (text.isBlank()) return null
            mapper.readValue(text, CrawlCheckpoint::class.java)
        } catch (e: Exception) {
            logger.warn("Skipping an unreadable crawl checkpoint {}: {}", file, e.message)
            null
        }
    }

    /**
     * Move [tmp] over [target].  `ATOMIC_MOVE` is what makes a checkpoint
     * all-or-nothing; where the filesystem refuses it, a plain replace is the
     * fallback (the `.tmp` + flush above still keep the window tiny).
     */
    private fun moveInto(tmp: Path, target: Path) {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
