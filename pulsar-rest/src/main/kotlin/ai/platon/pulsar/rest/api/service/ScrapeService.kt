package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.BasicAgenticSession
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.rest.api.common.DegenerateXSQLScrapeHyperlink
import ai.platon.pulsar.rest.api.common.ScrapeAPIUtils
import ai.platon.pulsar.rest.api.common.ScrapeHyperlink
import ai.platon.pulsar.rest.api.common.XSQLScrapeHyperlink
import ai.platon.pulsar.rest.api.entities.ScrapeRequest
import ai.platon.pulsar.rest.api.entities.ScrapeResponse
import ai.platon.pulsar.rest.api.entities.ScrapeStatusRequest
import ai.platon.pulsar.rest.api.entities.refreshed
import ai.platon.pulsar.rest.api.service.CommandService.Companion.FLOW_POLLING_INTERVAL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.apache.commons.collections4.MultiMapUtils
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Service
class ScrapeService(
    val session: AgenticSession
) {
    private val logger = LoggerFactory.getLogger(ScrapeService::class.java)

    /**
     * The response cache, the key is the id, the value is the response
     * */
    private val responseCache = ConcurrentSkipListMap<String, ScrapeResponse>()

    /**
     * The response status map, the key is the status code, the value is the response's id
     * */
    private val responseStatusIndex = MultiMapUtils.newListValuedHashMap<Int, String>()

    // Create a dedicated dispatcher for long-running command operations
    private val scrapingExecutor = Executors.newFixedThreadPool(10)
    private val scrapingDispatcher = scrapingExecutor.asCoroutineDispatcher()

    private val scrapingScope: CoroutineScope = CoroutineScope(
        scrapingDispatcher + SupervisorJob() + CoroutineName("scraping")
    )

    /**
     * Execute a scrape task and wait until the execution is done,
     * for test purpose only, no customer should access this api
     * */
    fun executeQuery(request: ScrapeRequest): ScrapeResponse {
        try {
            val hyperlink = createScrapeHyperlink(request)
            session.submit(hyperlink)
            val response = hyperlink.get(120, TimeUnit.SECONDS)
            return response
        } catch (e: TimeoutException) {
            logger.warn("Timeout executing query: >>>${request.sql}<<<", e)
            return ScrapeResponse("", ResourceStatus.SC_REQUEST_TIMEOUT, ProtocolStatusCodes.REQUEST_TIMEOUT)
        } catch (e: Exception) {
            logger.error("Unexpected error executing query: >>>${request.sql}<<<", e)
            return ScrapeResponse("", ResourceStatus.SC_INTERNAL_SERVER_ERROR, ProtocolStatusCodes.EXCEPTION)
        }
    }

    /**
     * Submit a scraping task
     * */
    fun submitJob(request: ScrapeRequest): String {
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


    fun streamEvents(id: String): Flux<ServerSentEvent<ScrapeResponse>> {
        return Flux.create<ScrapeResponse> { sink ->
            val job = commandStatusFlow(id).onEach {
                sink.next(it)
                if (it.isDone) {
                    sink.complete()
                }
            }.catch {
                logger.error("Error in command status flow", it)
                sink.error(it)
            }.launchIn(scrapingScope)

            sink.onDispose {
                job.cancel()
            }
        }.map {
            ServerSentEvent.builder(it).id(it.id!!).event(it.event).build()
        }
    }

    fun commandStatusFlow(uuid: String): Flow<ScrapeResponse> = flow {
        var lastModifiedTime = Instant.EPOCH
        do {
            delay(FLOW_POLLING_INTERVAL)

            val status = responseCache[uuid] ?: ScrapeResponse.notFound(uuid)
            if (status.isDone) {
                emit(status)
                return@flow
            }

            if (status.refreshed(lastModifiedTime)) {
                emit(status)
                lastModifiedTime = status.lastModifiedTime
            }
        } while (!status.isDone)
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
