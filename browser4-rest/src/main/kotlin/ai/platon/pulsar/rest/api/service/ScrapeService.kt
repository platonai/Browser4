package ai.platon.pulsar.rest.api.service

import ai.platon.browser4.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.agent.tool.UserCommandExecutor.Companion.FLOW_POLLING_INTERVAL
import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.refreshed
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.rest.api.entities.ScrapeStatusRequest
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds

@Service
class ScrapeService(
    private val sessionManager: PulsarSessionManager
) {
    private val logger = LoggerFactory.getLogger(ScrapeService::class.java)

    private val session get() = sessionManager.getOrCreateSession(SWARM_SESSION_ID).agenticSession

    /**
     * The response cache, the key is the id, the value is the response
     * */
    private val responseCache = ConcurrentSkipListMap<String, ScrapeResponse>()

    /**
     * The response status map, the key is the status code, the value is the response's id
     * */
    private val responseStatusIndex = MultiMapUtils.newListValuedHashMap<Int, String>()

    // Create a dedicated dispatcher for long-running command operations
    private val scrapingDispatcher = Dispatchers.IO.limitedParallelism(10)

    private val scrapingScope: CoroutineScope = CoroutineScope(
        scrapingDispatcher + SupervisorJob() + CoroutineName("scraping")
    )

    /**
     * Execute a scrape task and wait until the execution is done,
     * for test purpose only, no customer should access this api
     * */
    fun executeQuery(request: ScrapeRequest): ScrapeResponse {
        try {
            val response = executeQueryOnce(request)

            // Auto-recovery: if the scrape session was recreated (417), the
            // WebDB cache is empty.  Try to pre-load the target page and
            // re-execute the query so the X-SQL UDF can find the data.
            if (response.statusCode == ResourceStatus.SC_EXPECTATION_FAILED) {
                val extractedUrl = extractUrlFromSql(request.sql)
                if (extractedUrl != null) {
                    logger.info("X-SQL scrape session closed (417). Pre-loading '{}' and retrying.", extractedUrl)
                    try {
                        runBlocking { session.load(extractedUrl, "-refresh") }
                        val retryResponse = executeQueryOnce(request)
                        if (retryResponse.statusCode != ResourceStatus.SC_EXPECTATION_FAILED) {
                            logger.info("X-SQL retry succeeded for '{}'", extractedUrl)
                            return retryResponse
                        }
                    } catch (e: Exception) {
                        logger.warn("X-SQL pre-load retry failed: {}", e.message)
                    }
                }
            }

            return response
        } catch (e: TimeoutException) {
            logger.warn("Timeout executing query: >>>${request.sql}<<<", e)
            return ScrapeResponse("", ResourceStatus.SC_REQUEST_TIMEOUT, ProtocolStatusCodes.REQUEST_TIMEOUT)
        } catch (e: Exception) {
            logger.error("Unexpected error executing query: >>>${request.sql}<<<", e)
            return ScrapeResponse("", ResourceStatus.SC_INTERNAL_SERVER_ERROR, ProtocolStatusCodes.EXCEPTION)
        }
    }

    private fun executeQueryOnce(request: ScrapeRequest): ScrapeResponse {
        val hyperlink = createScrapeHyperlink(request)
        session.submit(hyperlink)
        return hyperlink.get(120, TimeUnit.SECONDS)
    }

    /**
     * Extract the URL from an X-SQL query string.
     * Looks for patterns like `load_and_select('http://...', ...)` or
     * `LOAD_AND_SELECT('http://...', ...)`.
     */
    private fun extractUrlFromSql(sql: String): String? {
        val regex = Regex(
            """(?:load_and_select|LOAD_AND_SELECT)\s*\(\s*'(https?://[^']+)'\s*,""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(sql)?.groupValues?.getOrNull(1)
    }

    /**
     * Submit a scraping task
     * */
    fun submitJob(request: ScrapeRequest): String {
        val hyperlink = createScrapeHyperlink(request)
        responseCache[hyperlink.uuid] = hyperlink.response
        hyperlink.response.id = hyperlink.uuid
        val s = session
        require(s is GenericAgenticSession) {
            "Expected GenericAgenticSession but got ${s::class.simpleName} (uuid=${s.uuid})"
        }
        s.submit(hyperlink)
        logger.info("Scrape task submitted: {} sql={}", hyperlink.uuid, request.sql)
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
            delay(FLOW_POLLING_INTERVAL.milliseconds)

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
        return ScrapeHyperlinkFactory.create(request, session) { link ->
            responseCache[link.uuid] = link.response
            responseStatusIndex[link.response.statusCode].add(link.uuid)
        }
    }

    @PreDestroy
    fun destroy() {
        scrapingScope.cancel()
    }
}
