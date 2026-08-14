package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.agentic.tools.advanced.crawl.QueryRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
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

    val session get() = sessionManager.ensureSwarmSession().agenticSession

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
     * Transition swarm tasks that have been stuck in CREATED (queued) state
     * for longer than [staleTaskTimeoutSeconds] to a TIMEOUT/failed state.
     *
     * This catches tasks whose workers hung during fetch (e.g. "Protocol not found"
     * for localhost URLs or other unreachable hosts) and never updated their status.
     * Without this, these tasks appear as "queued" indefinitely with statusCode 201.
     */
    private fun transitionStaleTasks() {
        val now = Instant.now()
        val staleCutoff = now.minusSeconds(staleTaskTimeoutSeconds)
        val createdStatusCode = ResourceStatus.SC_CREATED

        val stale = responseCache.asMap().entries.filter {
            val r = it.value
            r.statusCode == createdStatusCode
                && !r.isDone
                && r.createdTime?.isBefore(staleCutoff) == true
                && r.startedTime == null // hasn't been picked up by a worker at all
        }

        for ((id, response) in stale) {
            response.statusCode = ResourceStatus.SC_REQUEST_TIMEOUT
            response.pageStatusCode = ProtocolStatusCodes.SC_REQUEST_TIMEOUT
            response.finishTime = now
            response.lastModifiedTime = now
            response.isDone = true
            responseStatusIndex[createdStatusCode]?.remove(id)
            responseStatusIndex[response.statusCode]?.add(id)
            persistence.append(response)
            logger.warn(
                "Swarm task {} auto-timed-out after {}s in CREATED state " +
                "(staleTaskTimeoutSeconds={}). Likely cause: worker hung during fetch.",
                id, staleTaskTimeoutSeconds, staleTaskTimeoutSeconds
            )
        }

        if (stale.isNotEmpty()) {
            logger.info(
                "Transitioned {} stale swarm task(s) from CREATED to TIMEOUT " +
                "(threshold: {}s)", stale.size, staleTaskTimeoutSeconds
            )
        }
    }

    /**
     * Submit a scraping task
     * */
    fun submit(request: ScrapeRequest): String {
        val hyperlink = createScrapeHyperlink(request)
        responseCache.put(hyperlink.uuid, hyperlink.response)
        hyperlink.response.id = hyperlink.uuid
        persistence.append(hyperlink.response)
        val s = session
        require(s is GenericAgenticSession) {
            "Expected GenericAgenticSession but got ${s::class.simpleName} (uuid=${s.uuid})"
        }
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

    private fun createScrapeHyperlink(request: ScrapeRequest): ScrapeHyperlink {
        return ScrapeHyperlinkFactory.create(request, session) { link ->
            responseCache.put(link.uuid, link.response)
            responseStatusIndex[link.response.statusCode].add(link.uuid)
            persistence.append(link.response)
        }
    }
}
