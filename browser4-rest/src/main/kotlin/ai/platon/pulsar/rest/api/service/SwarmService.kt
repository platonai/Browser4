package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.GenericAgenticSession
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
import jakarta.annotation.PostConstruct
import org.apache.commons.collections4.MultiMapUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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

    /** JSONL file for persisting swarm task responses across restarts. */
    internal var persistenceFile: Path = Path.of(
        System.getProperty("browser4.data.dir", System.getProperty("user.home")),
        ".browser4", "data", "swarm"
    ).resolve("swarm-tasks.jsonl")

    private val objectMapper = pulsarObjectMapper()

    @PostConstruct
    fun restoreFromDisk() {
        if (!Files.exists(persistenceFile)) {
            logger.info("No swarm persistence file found at {}", persistenceFile)
            return
        }

        var restored = 0
        try {
            Files.newBufferedReader(persistenceFile).use { reader ->
                reader.lines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    try {
                        val response = objectMapper.readValue(line, ScrapeResponse::class.java)
                        response.id?.let { id ->
                            responseCache.put(id, response)
                            responseStatusIndex[response.statusCode].add(id)
                            restored++
                        }
                    } catch (e: Exception) {
                        logger.warn("Skipping corrupt swarm task line: {}", e.message)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to restore swarm tasks from {}", persistenceFile, e)
        }

        logger.info("Restored {} swarm task(s) from {}", restored, persistenceFile)
    }

    /** Append a response to the persistence file. */
    private fun persist(response: ScrapeResponse) {
        try {
            Files.createDirectories(persistenceFile.parent)
            val line = objectMapper.writeValueAsString(response)
            Files.writeString(
                persistenceFile,
                line + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
            )
        } catch (e: Exception) {
            logger.warn("Failed to persist swarm task {}: {}", response.id, e.message)
        }
    }

    /**
     * Submit a scraping task
     * */
    fun submit(request: ScrapeRequest): String {
        val hyperlink = createScrapeHyperlink(request)
        responseCache.put(hyperlink.uuid, hyperlink.response)
        hyperlink.response.id = hyperlink.uuid
        persist(hyperlink.response)
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
            persist(link.response)
        }
    }
}
