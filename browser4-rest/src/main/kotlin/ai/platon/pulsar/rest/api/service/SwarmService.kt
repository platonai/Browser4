package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.BasicAgenticSession
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.DegenerateXSQLScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.XSQLScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.refreshed
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.SessionManager
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.rest.api.entities.ScrapeStatusRequest
import ai.platon.pulsar.rest.tool.CommandRunner.Companion.FLOW_POLLING_INTERVAL
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.apache.commons.collections4.MultiMapUtils
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.time.Duration.Companion.milliseconds

@Service
class SwarmService(
    private val sessionManager: SessionManager
) {
    private val logger = LoggerFactory.getLogger(SwarmService::class.java)

    // Discus: CommandRunner works on any sessions with permanent profiles
    val session get() = sessionManager.swarmSession().agenticSession

    /**
     * The response cache, the key is the id, the value is the response
     * */
    private val responseCache = ConcurrentSkipListMap<String, ScrapeResponse>()

    /**
     * The response status map, the key is the status code, the value is the response's id
     * */
    private val responseStatusIndex = MultiMapUtils.newListValuedHashMap<Int, String>()

    /**
     * Submit a scraping task
     * */
    fun submit(request: ScrapeRequest): String {
        val hyperlink = createScrapeHyperlink(request)
        responseCache[hyperlink.uuid] = hyperlink.response
        hyperlink.response.id = hyperlink.uuid
        require(session is BasicAgenticSession)
        session.submit(hyperlink)
        return hyperlink.uuid
    }

    /**
     * Get the response
     * */
    fun getStatus(request: ScrapeStatusRequest): ScrapeResponse {
        return responseCache.computeIfAbsent(request.id) {
            ScrapeResponse(request.id, ResourceStatus.SC_NOT_FOUND, ProtocolStatusCodes.SC_NOT_FOUND)
        }
    }

    /**
     * Get the response count by status code
     * */
    fun count(statusCode: Int): Int {
        return when (statusCode) {
            0 -> responseCache.size
            else -> responseStatusIndex[statusCode]?.size ?: 0
        }
    }

    private fun createScrapeHyperlink(request: ScrapeRequest): ScrapeHyperlink {
        val sql = request.sql
        val link = if (ScrapeAPIUtils.isScrapeUDF(sql)) {
            val xSQL = ScrapeAPIUtils.normalize(sql)
            XSQLScrapeHyperlink(request, xSQL, session)
        } else {
            DegenerateXSQLScrapeHyperlink(request, session)
        }

        link.eventHandlers.crawlEventHandlers.onLoaded.addLast { url, page ->
            responseCache[link.uuid] = link.response
            responseStatusIndex[link.response.statusCode].add(link.uuid)
            null
        }

        return link
    }
}
