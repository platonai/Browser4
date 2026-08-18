package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.agent.tool.UserCommandExecutor.Companion.FLOW_POLLING_INTERVAL
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeStatusRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeHyperlink
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeHyperlinkFactory
import ai.platon.pulsar.agentic.tools.advanced.crawl.refreshed
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
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

    // Use the swarm session for distributed scraping.
    private val session get() = sessionManager.ensureSwarmSession().agenticSession

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
            var lastResponse: ScrapeResponse? = null
            val maxRetries = 3
            val baseDelayMs = 500L

            // Pre-load the target page BEFORE the first query attempt to warm
            // the session's WebDB cache.  Without this, the first
            // DOM_LOAD_AND_SELECT call may find an empty cache and return 417.
            // Uses a simple load_and_select query to warm the cache via the
            // hyperlink mechanism (which launches a browser if needed), rather
            // than session.load() which may fail if no browser is running yet.
            val extractedUrl = extractUrlFromSql(request.sql)
            if (extractedUrl != null) {
                try {
                    val preloadSql = "select dom_base_uri(dom) as _url from load_and_select('$extractedUrl', ':root')"
                    executeQueryOnce(ScrapeRequest(preloadSql), session)
                    logger.debug("X-SQL: pre-loaded '{}' before first query attempt", extractedUrl)
                } catch (e: Exception) {
                    logger.warn("X-SQL: pre-load of '{}' before first attempt failed: {}",
                        extractedUrl, e.message)
                }
            }

            for (attempt in 0..maxRetries) {
                // Capture a stable session reference for this attempt so that
                // the hyperlink and the submit call use the SAME session
                // instance.  The `session` property getter may return a
                // different instance each time (e.g. when resolveHealthySession
                // recreates an unhealthy session), which would cause the
                // hyperlink to reference a stale session while submit() targets
                // the fresh one — or vice versa.
                val currentSession = session
                val response = executeQueryOnce(request, currentSession)

                if (response.statusCode != ResourceStatus.SC_EXPECTATION_FAILED) {
                    return response
                }

                lastResponse = response

                // Auto-recovery: if the scrape session was recreated (417), the
                // WebDB cache is empty.  Pre-load the target page and retry with
                // exponential backoff so the X-SQL UDF can find the data.
                if (extractedUrl == null) {
                    logger.warn("X-SQL scrape session closed (417) but URL could not be extracted from SQL; cannot retry.")
                    return response
                }

                if (attempt < maxRetries) {
                    val delayMs = baseDelayMs * (1L shl attempt) // 500, 1000, 2000
                    logger.info(
                        "X-SQL scrape session closed (417), attempt {}/{}. Pre-loading '{}' and retrying in {}ms...",
                        attempt + 1, maxRetries, extractedUrl, delayMs
                    )
                    try {
                        Thread.sleep(delayMs)
                        // Re-fetch the session on retry — if the old one was
                        // recreated, we want the fresh instance for the pre-load.
                        val retrySession = session
                        val preloadSql = "select dom_base_uri(dom) as _url from load_and_select('$extractedUrl', ':root')"
                        executeQueryOnce(ScrapeRequest(preloadSql), retrySession)
                    } catch (e: Exception) {
                        logger.warn("X-SQL pre-load retry failed (attempt {}): {}", attempt + 1, e.message)
                    }
                }
            }

            logger.warn("X-SQL query failed after {} retries", maxRetries)
            val response = lastResponse ?: ScrapeResponse(
                "", ResourceStatus.SC_EXPECTATION_FAILED, ProtocolStatusCodes.EXCEPTION
            )
            // Ensure a diagnostic message is present — if the hyperlink
            // didn't capture one (e.g., DegenerateXSQLScrapeHyperlink), add
            // a generic message so the CLI doesn't show an empty error.
            if (response.message.isNullOrBlank()) {
                response.message = "X-SQL query failed with status ${response.statusCode}. " +
                    "This may indicate a function type mismatch (e.g., passing a string " +
                    "to a DOM-element function like DOM_ABS_SRC)."
            }
            return response
        } catch (e: TimeoutException) {
            logger.warn("Timeout executing query: >>>${request.sql}<<<", e)
            return ScrapeResponse(
                "", ResourceStatus.SC_REQUEST_TIMEOUT, ProtocolStatusCodes.REQUEST_TIMEOUT,
                message = "X-SQL query timed out after 120s. The page may be too large or the session may be unresponsive."
            )
        } catch (e: Exception) {
            logger.error("Unexpected error executing query: >>>${request.sql}<<<", e)
            return ScrapeResponse(
                "", ResourceStatus.SC_INTERNAL_SERVER_ERROR, ProtocolStatusCodes.EXCEPTION,
                message = e.message ?: e.toString()
            )
        }
    }

    private fun executeQueryOnce(
        request: ScrapeRequest,
        agenticSession: AgenticSession
    ): ScrapeResponse {
        val hyperlink = createScrapeHyperlink(request, agenticSession)
        agenticSession.submit(hyperlink)
        return hyperlink.get(120, TimeUnit.SECONDS)
    }

    /**
     * Extract the URL from an X-SQL query string.
     * Looks for patterns like `load_and_select('http://...', ...)` or
     * `LOAD_AND_SELECT('http://...', ...)`.
     */
    private fun extractUrlFromSql(sql: String): String? {
        val regex = Regex(
            """(?:load_and_select|LOAD_AND_SELECT)\s*\(\s*'(https?://[^'\s]+)(?:\s[^']*)?'\s*,""",
            RegexOption.IGNORE_CASE
        )
        // Group 1 captures the URL (stops at first space or closing quote).
        // The optional second group (?:\s[^']*)? matches X-SQL flags like "-i 10d".
        return regex.find(sql)?.groupValues?.getOrNull(1)
    }

    /**
     * Submit a scraping task
     * */
    fun submitJob(request: ScrapeRequest): String {
        val s = session
        require(s is GenericAgenticSession) {
            "Expected GenericAgenticSession but got ${s::class.simpleName} (uuid=${s.uuid})"
        }
        val hyperlink = createScrapeHyperlink(request, s)
        responseCache[hyperlink.uuid] = hyperlink.response
        hyperlink.response.id = hyperlink.uuid
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

    private fun createScrapeHyperlink(
        request: ScrapeRequest,
        agenticSession: AgenticSession
    ): ScrapeHyperlink {
        return ScrapeHyperlinkFactory.create(request, agenticSession) { link ->
            responseCache[link.uuid] = link.response
            responseStatusIndex[link.response.statusCode].add(link.uuid)
        }
    }

    @PreDestroy
    fun destroy() {
        scrapingScope.cancel()
    }
}
