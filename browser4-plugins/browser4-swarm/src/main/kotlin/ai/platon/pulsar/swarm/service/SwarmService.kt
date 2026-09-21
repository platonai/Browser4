package ai.platon.pulsar.swarm.service

import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.agentic.tools.advanced.crawl.QueryRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeStatusRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacade
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmSessionProvider
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeHyperlinkFactory
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.commons.collections4.MultiMapUtils
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import java.nio.file.Path
import java.time.Instant

/**
 * Swarm task backend: submits X-SQL/URL scrape tasks against the shared swarm
 * session and tracks their status/results.
 *
 * Moved from browser4-rest into the `browser4-swarm` plugin. The swarm session
 * is consumed through [SwarmSessionProvider] so this class never depends on
 * REST module classes. Session lifecycle (create/close) stays with the REST
 * session manager; this service only tracks tasks and aborts the pending ones
 * when the session changes.
 */
open class SwarmService(
    private val sessionProvider: SwarmSessionProvider,
) : SwarmFacade {

    private val logger = LoggerFactory.getLogger(SwarmService::class.java)

    /**
     * The id of the swarm session the pending tasks belong to. When the swarm
     * session is closed and a new one is created, this id changes and all
     * pending tasks of the old session are aborted — they can never be
     * consumed again, and leaving them "queued" forever leaks them across
     * sessions.
     * */
    @Volatile
    private var swarmSessionId: Long = -1

    val session: GenericAgenticSession
        get() {
            val s = sessionProvider.session()
            val previousId = swarmSessionId
            if (previousId != -1L && previousId != s.id) {
                logger.info(
                    "Swarm session changed (#{} -> #{}), aborting pending tasks of the old session",
                    previousId, s.id
                )
                abortPendingTasks("Swarm session was closed; task dropped")
            }
            swarmSessionId = s.id
            return s
        }

    /**
     * The response status index, the key is the status code, the value is the response's id.
     * Used for O(1) count-by-status queries.  Kept in sync with [responseCache] via the
     * removal listener.
     * */
    private val responseStatusIndex = MultiMapUtils.newListValuedHashMap<Int, String>()

    /**
     * Size-bounded, time-expiring cache of swarm task responses.
     *
     * - At most 100 000 entries; LRU eviction beyond that via Caffeine's
     *   Window TinyLFU policy.
     * - NOT_FOUND lookups are never cached — only real task responses go in.
     * - Evicted entries are removed from [responseStatusIndex] by the removal listener.
     * - Persisted to a JSONL file so task statuses survive restarts.
     * */
    val responseCache: Cache<String, ScrapeResponse> = Caffeine.newBuilder()
        .maximumSize(100_000)
        .removalListener<String, ScrapeResponse> { key, value, cause ->
            if (value != null && cause.wasEvicted()) {
                responseStatusIndex[value.statusCode]?.remove(key)
                logger.debug(
                    "Evicted swarm task {} (status={}, cause={})",
                    key, value.statusCode, cause
                )
            }
        }
        .recordStats()
        .build()

    /** JSONL persistence for swarm task responses across restarts. */
    internal val persistence = JsonlPersistence(
        file = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "data", "swarm", "swarm-tasks.jsonl"
        ),
        clazz = ScrapeResponse::class,
        objectMapper = pulsarObjectMapper()
    )

    /** Dedicated dispatcher for cleanup operations. */
    private val cleanupDispatcher = Dispatchers.IO.limitedParallelism(2)

    private val cleanupScope = CoroutineScope(
        cleanupDispatcher + SupervisorJob() + CoroutineName("swarm-cleanup")
    )

    /** How long to keep terminal tasks before compacting them out of the JSONL file. */
    @Volatile
    var taskTtlMinutes: Int = 43200 // 30 days

    /**
     * Maximum time (seconds) a swarm task that a worker already picked up
     * (`startedTime` set) may make no progress before being transitioned to a
     * TIMEOUT/failed state.  This catches fetches that hang mid-flight.
     *
     * Only tasks that were actually started are subject to this timeout: a task
     * that is still *queued* is waiting for a free tab, which says nothing
     * about its own health.
     */
    @Volatile
    var staleTaskTimeoutSeconds: Long = 120

    /**
     * Maximum time (seconds) the swarm pipeline may make **no progress at all**
     * before queued (never started) tasks are considered unconsumable and are
     * failed.
     *
     * Bailing out on queued tasks by their own age is wrong whenever the pool
     * is healthy but busy: a 100-URL batch drains at a few pages per minute, so
     * the tail of the queue legitimately waits for many minutes while earlier
     * tasks complete.  Keying the decision on the *pipeline* instead — the most
     * recent status update across all live tasks — keeps a busy queue alive and
     * still reaps tasks when the worker pool really is stalled (contexts that
     * never came up, a hung fetch loop, a closed session).
     */
    @Volatile
    var queueStallTimeoutSeconds: Long = 600

    init {
        // Periodically compact the JSONL file so stale entries don't accumulate forever.
        cleanupScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // every 5 minutes
                compactPersistence()
            }
        }
        // Periodically check for stale QUEUED tasks that never transitioned
        // to a terminal state (e.g. protocol-not-found hung the worker).
        cleanupScope.launch {
            while (isActive) {
                delay(30 * 1000L) // every 30 seconds
                transitionStaleTasks()
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        cleanupScope.cancel()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun restoreFromDisk() {
        val now = Instant.now()
        val ttlCutoff = now.minusSeconds(taskTtlMinutes * 60L)

        persistence.restore { response ->
            response.id?.let { id ->
                // Skip terminal entries whose TTL has expired — they were evicted
                // from the cache before shutdown and should not be revived.
                if (response.isDone && response.createdTime?.isBefore(ttlCutoff) == true) {
                    logger.debug("Skipping expired swarm task {} during restore (created={})", id, response.createdTime)
                    return@restore
                }

                // Non-terminal entries can never resume after a restart: the
                // worker state (hyperlinks, url pool entries) is gone, so a
                // queued/processing task would sit in the cache forever and
                // leak across sessions. Mark them as failed with a clear reason.
                if (!response.isDone) {
                    logger.info(
                        "Swarm task {} was interrupted by a restart and cannot resume; marking as failed",
                        id
                    )
                    response.statusCode = ResourceStatus.SC_GONE
                    response.isDone = true
                    response.finishTime = now
                    response.lastModifiedTime = now
                    response.message = response.message
                        ?: "Task was interrupted by a restart and cannot resume. Re-submit the task to retry."
                }

                responseCache.put(id, response)
                responseStatusIndex[response.statusCode].add(id)
            }
        }
    }

    /** Compact the JSONL persistence file: remove stale terminal entries past TTL. */
    private fun compactPersistence() {
        val now = Instant.now()
        val ttlCutoff = now.minusSeconds(taskTtlMinutes * 60L)

        val stale = responseCache.asMap().entries.filter {
            it.value.isDone && it.value.createdTime?.isBefore(ttlCutoff) == true
        }
        if (stale.isEmpty()) return

        stale.forEach {
            responseCache.invalidate(it.key)
            responseStatusIndex[it.value.statusCode]?.remove(it.key)
        }
        logger.info("Compacted {} expired swarm tasks (TTL: {} min)", stale.size, taskTtlMinutes)

        // Rewrite the persistence file so compacted tasks don't revive on restart.
        persistence.clear()
        responseCache.asMap().values.forEach { persistence.append(it) }
    }

    /**
     * Transition swarm tasks that can no longer make progress to a TIMEOUT/failed
     * state.
     *
     * Two distinct conditions are reaped, with different evidence:
     *
     * 1. **Started but hung** — a task a worker picked up (`startedTime` set) with
     *    no status update for [staleTaskTimeoutSeconds]: the fetch itself is stuck
     *    (unreachable host, protocol not found, hung renderer).
     * 2. **Queued behind a stalled pipeline** — a never-started task older than
     *    [queueStallTimeoutSeconds] in a swarm where no task at all has been
     *    updated for that long: the pool cannot consume anything, so the task
     *    would sit in the queue forever.
     *
     * Deliberately NOT reaped: a queued task whose siblings are still completing.
     * A large batch legitimately waits minutes per page for a free tab, and
     * failing those tasks by their own age used to wipe out most of a one-shot
     * submission (98 of 100 jobs) while the fetch pipeline was busy working
     * through the very same batch.
     */
    private fun transitionStaleTasks() {
        val now = Instant.now()
        val live = responseCache.asMap().entries.filter { !it.value.isDone }
        if (live.isEmpty()) return

        // Pipeline progress = the newest status update anywhere in the swarm.
        // Terminal tasks count: a task that completed 10 seconds ago is the
        // clearest evidence that the pool is draining its queue, even when
        // every other task is still waiting for a tab.  Only when *nothing* has
        // moved for the whole stall window can a queued task be declared
        // unconsumable.
        val lastProgressEpochSecond = responseCache.asMap().values
            .mapNotNull { it.lastModifiedTime ?: it.createdTime }
            .maxOrNull()
            ?.epochSecond
        val stalledForSeconds = lastProgressEpochSecond?.let { now.epochSecond - it } ?: Long.MAX_VALUE
        val pipelineStalled = stalledForSeconds >= queueStallTimeoutSeconds

        val taskCutoff = now.minusSeconds(staleTaskTimeoutSeconds)
        val queueCutoff = now.minusSeconds(queueStallTimeoutSeconds)

        var hungCount = 0
        var queuedCount = 0

        val stale = live.filter {
            val r = it.value
            val lastTouched = r.lastModifiedTime ?: r.createdTime
            if (r.startedTime != null) {
                lastTouched?.isBefore(taskCutoff) == true
            } else {
                pipelineStalled && (r.createdTime?.isBefore(queueCutoff) != false)
            }
        }

        for ((id, response) in stale) {
            val hung = response.startedTime != null
            responseStatusIndex[response.statusCode]?.remove(id)
            response.statusCode = ResourceStatus.SC_REQUEST_TIMEOUT
            response.pageStatusCode = ProtocolStatusCodes.SC_REQUEST_TIMEOUT
            response.finishTime = now
            response.lastModifiedTime = now
            response.isDone = true
            response.message = response.message ?: if (hung) {
                "Task timed out: no progress for ${staleTaskTimeoutSeconds}s after a worker picked it up. " +
                    "The worker may have hung during fetch. Re-submit the task to retry."
            } else {
                "Task was never picked up: the swarm pipeline made no progress for " +
                    "${stalledForSeconds.coerceAtLeast(queueStallTimeoutSeconds)}s, so it cannot be consumed. " +
                    "Check the swarm session's browser contexts, or recreate the session and re-submit."
            }
            responseStatusIndex[response.statusCode]?.add(id)
            persistence.append(response)
            if (hung) {
                hungCount++
            } else {
                queuedCount++
            }
        }

        if (hungCount > 0) {
            logger.info(
                "Transitioned {} hung swarm task(s) to TIMEOUT (threshold: {}s)",
                hungCount, staleTaskTimeoutSeconds
            )
        }
        if (queuedCount > 0) {
            logger.warn(
                "Transitioned {} never-started swarm task(s) to TIMEOUT: the swarm pipeline made no " +
                    "progress for {}s (queueStallTimeoutSeconds={})",
                queuedCount, stalledForSeconds, queueStallTimeoutSeconds
            )
        }
    }

    /**
     * Submit a scraping task
     * */
    override fun submit(request: ScrapeRequest): String = submit(request, request.batchId)

    /**
     * Submit a scraping task as part of [batchId] (nullable).
     *
     * Every task of one batch submission carries the same batch id, so a batch
     * can be tracked, filtered and summarised as a unit — see [batchStatus].
     * */
    override fun submit(request: ScrapeRequest, batchId: String?): String {
        // Resolve the session BEFORE the task is cached: the session getter
        // detects swarm session replacement and aborts pending tasks, and the
        // freshly submitted task must never be aborted by that check.
        val s = session
        require(s is GenericAgenticSession) {
            "Expected GenericAgenticSession but got ${s::class.simpleName} (uuid=${s.uuid})"
        }
        val hyperlink = createScrapeHyperlink(request, s)
        hyperlink.response.batchId = batchId
        responseCache.put(hyperlink.uuid, hyperlink.response)
        hyperlink.response.id = hyperlink.uuid
        persistence.append(hyperlink.response)
        s.submit(hyperlink)
        logger.debug("Swarm task submitted: {} batch={} sql={}", hyperlink.uuid, batchId, request.sql)
        return hyperlink.uuid
    }

    /**
     * Submit a scraping task
     * */
    override fun submit(query: QueryRequest): String {
        return submit(ScrapeRequest(query.toSQL()).also { it.batchId = query.batchId })
    }

    /**
     * Aggregate status of every task submitted under [batchId].
     *
     * Returns the batch's task count, its lifecycle split, the wall-clock window
     * (`startedAt` = earliest task start, `finishedAt` = latest finish, present
     * only when the batch has fully settled) and per-task rows including each
     * task's duration.  Tasks evicted from the response cache are not visible
     * here — the cache is bounded (100k entries) and tasks expire on TTL.
     * */
    override fun batchStatus(batchId: String): Map<String, Any?> {
        val tasks = responseCache.asMap().values.filter { it.batchId == batchId }

        val completed = tasks.count { it.isDone && it.statusCode == ResourceStatus.SC_OK }
        val failed = tasks.count { it.isDone && it.statusCode != ResourceStatus.SC_OK }
        val pending = tasks.count { !it.isDone }

        val startedAt = tasks.mapNotNull { it.startedTime ?: it.createdTime }.minOrNull()
        val finishedAt = if (pending == 0) tasks.mapNotNull { it.finishTime }.maxOrNull() else null
        val durationMillis = if (finishedAt != null && startedAt != null) {
            java.time.Duration.between(startedAt, finishedAt).toMillis().coerceAtLeast(0)
        } else null

        val rows = tasks.map { r ->
            mapOf(
                "id" to r.id,
                "isDone" to r.isDone,
                "statusCode" to r.statusCode,
                "status" to r.status,
                "message" to r.message,
                "createdTime" to r.createdTime?.toString(),
                "startedTime" to r.startedTime?.toString(),
                "finishTime" to r.finishTime?.toString(),
                "durationMillis" to r.durationMillis,
            )
        }

        return mapOf(
            "batchId" to batchId,
            "total" to tasks.size,
            "completed" to completed,
            "failed" to failed,
            "pending" to pending,
            "startedAt" to startedAt?.toString(),
            "finishedAt" to finishedAt?.toString(),
            "durationMillis" to durationMillis,
            "tasks" to rows,
        )
    }

    /**
     * Abort all pending (non-terminal) tasks with a clear failure reason.
     *
     * Pending tasks belong to a live swarm session: once that session is closed
     * they can never be consumed again, so they must not stay "queued" forever
     * (which also leaks them across sessions and restarts). This marks them as
     * failed with [ResourceStatus.SC_GONE] and persists the transition.
     *
     * @param reason the human-readable reason to record in the response message
     * @return the number of aborted tasks
     */
    override fun abortPendingTasks(reason: String): Int {
        val now = Instant.now()
        val pending = responseCache.asMap().entries.filter { !it.value.isDone }

        for ((id, response) in pending) {
            responseStatusIndex[response.statusCode]?.remove(id)
            response.statusCode = ResourceStatus.SC_GONE
            response.pageStatusCode = ProtocolStatusCodes.SC_REQUEST_TIMEOUT
            response.finishTime = now
            response.lastModifiedTime = now
            response.isDone = true
            response.message = response.message ?: reason
            responseStatusIndex[response.statusCode]?.add(id)
            persistence.append(response)
        }

        if (pending.isNotEmpty()) {
            logger.info("Aborted {} pending swarm task(s): {}", pending.size, reason)
        }
        return pending.size
    }

    /**
     * Get the response.  Does NOT cache NOT_FOUND results — only returns a
     * placeholder if the task ID is genuinely unknown.
     * */
    override fun getStatus(request: ScrapeStatusRequest): ScrapeResponse {
        return responseCache.getIfPresent(request.id) ?: run {
            logger.warn("Swarm task not found: {}", request.id)
            // notFound() clears createdTime: the placeholder describes a task
            // that does not exist, so it must not look like a freshly created one.
            ScrapeResponse.notFound(request.id).also { it.message = "Swarm task not found: ${request.id}" }
        }
    }

    /**
     * Get the response count by status code
     * */
    override fun count(statusCode: Int): Int {
        return when (statusCode) {
            0 -> responseCache.estimatedSize().toInt()
            else -> responseStatusIndex[statusCode]?.size ?: 0
        }
    }

    /**
     * Return cache statistics for observability.
     * */
    fun cacheStats(): Map<String, Any> {
        val stats = responseCache.stats()
        return mapOf(
            "estimatedSize" to responseCache.estimatedSize(),
            "hitCount" to stats.hitCount(),
            "missCount" to stats.missCount(),
            "hitRate" to "%.2f".format(stats.hitRate()),
            "evictionCount" to stats.evictionCount(),
            "averageLoadPenaltyNanos" to stats.averageLoadPenalty(),
            "loadSuccessCount" to stats.loadSuccessCount(),
            "loadFailureCount" to stats.loadFailureCount(),
        )
    }

    private fun createScrapeHyperlink(request: ScrapeRequest, agenticSession: GenericAgenticSession): ScrapeHyperlink {
        return ScrapeHyperlinkFactory.create(request, agenticSession) { link ->
            responseCache.put(link.uuid, link.response)
            responseStatusIndex[link.response.statusCode].add(link.uuid)
            persistence.append(link.response)
        }
    }
}
