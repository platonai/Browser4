package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.agentic.tools.advanced.crawl.QueryRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.rest.api.entities.ScrapeStatusRequest
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import kotlinx.coroutines.*
import org.apache.commons.collections4.MultiMapUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant

@Service
class SwarmService(
    private val sessionManager: PulsarSessionManager
) {

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

    val session: AgenticSession
        get() {
            val s = sessionManager.ensureSwarmSession().agenticSession
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
     * Maximum time (seconds) a swarm task may remain in CREATED state before
     * being automatically transitioned to a TIMEOUT/failed state.  This catches
     * tasks that are picked up by a worker but hang during fetch (e.g. due to
     * "Protocol not found" for localhost URLs) and never update their status.
     */
    @Volatile
    var staleTaskTimeoutSeconds: Long = 120

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
     * Transition swarm tasks that have been stuck in a non-terminal state
     * (CREATED/queued or picked up but never finished) for longer than
     * [staleTaskTimeoutSeconds] to a TIMEOUT/failed state.
     *
     * This catches tasks whose workers hung during fetch (e.g. "Protocol not found"
     * for localhost URLs or other unreachable hosts) and never updated their status.
     * Without this, these tasks appear as "queued" indefinitely with statusCode 201.
     */
    private fun transitionStaleTasks() {
        val now = Instant.now()
        val staleCutoff = now.minusSeconds(staleTaskTimeoutSeconds)

        val stale = responseCache.asMap().entries.filter {
            val r = it.value
            // Use lastModifiedTime when available: a task picked up by a worker
            // has a startedTime, so checking createdTime alone would never catch
            // tasks whose workers hung mid-fetch. Every status/event update
            // refreshes lastModifiedTime, so tasks that are actively progressing
            // (e.g. waiting between bounded retry attempts) are never affected.
            val lastTouched = r.lastModifiedTime ?: r.createdTime
            !r.isDone
                && lastTouched?.isBefore(staleCutoff) == true
        }

        for ((id, response) in stale) {
            responseStatusIndex[response.statusCode]?.remove(id)
            response.statusCode = ResourceStatus.SC_REQUEST_TIMEOUT
            response.pageStatusCode = ProtocolStatusCodes.SC_REQUEST_TIMEOUT
            response.finishTime = now
            response.lastModifiedTime = now
            response.isDone = true
            response.message = response.message
                ?: "Task timed out: no progress for ${staleTaskTimeoutSeconds}s. " +
                "The worker may have hung during fetch. Re-submit the task to retry."
            responseStatusIndex[response.statusCode]?.add(id)
            persistence.append(response)
            logger.warn(
                "Swarm task {} auto-timed-out after no progress for {}s " +
                "(staleTaskTimeoutSeconds={}). Likely cause: worker hung during fetch.",
                id, staleTaskTimeoutSeconds, staleTaskTimeoutSeconds
            )
        }

        if (stale.isNotEmpty()) {
            logger.info(
                "Transitioned {} stale swarm task(s) to TIMEOUT " +
                "(threshold: {}s)", stale.size, staleTaskTimeoutSeconds
            )
        }
    }

    /**
     * Submit a scraping task
     * */
    fun submit(request: ScrapeRequest): String {
        // Resolve the session BEFORE the task is cached: the session getter
        // detects swarm session replacement and aborts pending tasks, and the
        // freshly submitted task must never be aborted by that check.
        val s = session
        require(s is GenericAgenticSession) {
            "Expected GenericAgenticSession but got ${s::class.simpleName} (uuid=${s.uuid})"
        }
        val hyperlink = createScrapeHyperlink(request, s)
        responseCache.put(hyperlink.uuid, hyperlink.response)
        hyperlink.response.id = hyperlink.uuid
        persistence.append(hyperlink.response)
        s.submit(hyperlink)
        logger.debug("Swarm task submitted: {} sql={}", hyperlink.uuid, request.sql)
        return hyperlink.uuid
    }

    /**
     * Submit a scraping task
     * */
    fun submit(request: QueryRequest): String {
        return submit(ScrapeRequest(request.toSQL()))
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
    fun abortPendingTasks(reason: String): Int {
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
     * Close the swarm session: abort its pending tasks and release the session.
     * Called by the REST close endpoint (and indirectly by `swarm close`).
     *
     * @return the number of aborted pending tasks
     */
    fun closeSession(): Int {
        val aborted = abortPendingTasks("Swarm session was closed; task dropped")
        runCatching { sessionManager.deleteSession(SWARM_SESSION_ID) }
            .onFailure { logger.warn("Failed to close the swarm session: {}", it.message) }
        return aborted
    }

    /**
     * Get the response.  Does NOT cache NOT_FOUND results — only returns a
     * placeholder if the task ID is genuinely unknown.
     * */
    fun getStatus(request: ScrapeStatusRequest): ScrapeResponse {
        return responseCache.getIfPresent(request.id) ?: run {
            logger.warn("Swarm task not found: {}", request.id)
            ScrapeResponse(request.id, ResourceStatus.SC_NOT_FOUND, ProtocolStatusCodes.SC_NOT_FOUND)
        }
    }

    /**
     * Get the response count by status code
     * */
    fun count(statusCode: Int): Int {
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

    private fun createScrapeHyperlink(request: ScrapeRequest, agenticSession: AgenticSession): ScrapeHyperlink {
        return ScrapeHyperlinkFactory.create(request, agenticSession) { link ->
            responseCache.put(link.uuid, link.response)
            responseStatusIndex[link.response.statusCode].add(link.uuid)
            persistence.append(link.response)
        }
    }
}
