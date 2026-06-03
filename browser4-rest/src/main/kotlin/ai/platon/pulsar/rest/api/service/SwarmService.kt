package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.DegenerateXSQLScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.XSQLScrapeHyperlink
import ai.platon.pulsar.common.PulsarSessionManager
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.rest.api.entities.ScrapeStatusRequest
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.apache.commons.collections4.MultiMapUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

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

    /**
     * Submit a scraping task
     * */
    fun submit(request: ScrapeRequest): String {
        val hyperlink = createScrapeHyperlink(request)
        responseCache.put(hyperlink.uuid, hyperlink.response)
        hyperlink.response.id = hyperlink.uuid
        val s = session
        require(s is GenericAgenticSession) {
            "Expected GenericAgenticSession but got ${s::class.simpleName} (uuid=${s.uuid})"
        }
        s.submit(hyperlink)
        logger.info("Swarm task submitted: {} sql={}", hyperlink.uuid, request.sql)
        return hyperlink.uuid
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
        val sql = request.sql
        val link = if (ScrapeAPIUtils.isScrapeUDF(sql)) {
            val xSQL = ScrapeAPIUtils.normalize(sql)
            XSQLScrapeHyperlink(request, xSQL, session)
        } else {
            DegenerateXSQLScrapeHyperlink(request, session)
        }

        link.eventHandlers.crawlEventHandlers.onLoaded.addLast { _, _ ->
            responseCache.put(link.uuid, link.response)
            responseStatusIndex[link.response.statusCode].add(link.uuid)
            null
        }

        return link
    }
}
