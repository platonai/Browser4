package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.context.sql.AbstractBrowser4SQLContext
import ai.platon.pulsar.common.PulsarSessionManager
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.ql.common.ResultSets
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.skeleton.workflow.common.url.ParsableHyperlink
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class CrawlRequest(
    val url: String = "",
    val args: String = "",
    val depth: Int = 1,
    val urls: List<String>? = null,
    val sql: String? = null
)

data class CrawlResponse(
    val taskId: String = "",
    val status: String = "CREATED",
    val pagesFound: Int = 0,
    val pages: List<CrawlPageResult>? = null,
    val error: String? = null
)

data class CrawlPageResult(
    val url: String,
    val title: String? = null,
    val contentLength: Long? = null,
    val depth: Int = 0,
    val extracted: List<Map<String, Any?>>? = null
)

@Service
class CrawlService(
    private val sessionManager: PulsarSessionManager
) {
    private val logger = LoggerFactory.getLogger(CrawlService::class.java)

    /** Task store: taskId -> CrawlResponse */
    private val taskStore = ConcurrentHashMap<String, CrawlResponse>()

    /** Dedicated dispatcher for crawl operations */
    private val crawlDispatcher = Dispatchers.IO.limitedParallelism(5)

    private val crawlScope = CoroutineScope(
        crawlDispatcher + SupervisorJob() + CoroutineName("crawl")
    )

    @PreDestroy
    fun shutdown() {
        crawlScope.cancel()
    }

    /**
     * Submit a crawl task. Returns the task ID immediately; the crawl runs
     * asynchronously.  Poll [getResult] to retrieve the final response.
     */
    fun submit(request: CrawlRequest): String {
        val taskId = UUID.randomUUID().toString()
        val response = CrawlResponse(
            taskId = taskId,
            status = ResourceStatus.getStatusText(ResourceStatus.SC_CREATED)
        )
        taskStore[taskId] = response

        // Compute effective seed URL list
        val seedUrls = if (!request.urls.isNullOrEmpty()) {
            request.urls
        } else if (request.url.isNotBlank()) {
            listOf(request.url)
        } else {
            emptyList()
        }

        if (seedUrls.isEmpty()) {
            taskStore[taskId] = CrawlResponse(
                taskId = taskId,
                status = ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
                error = "No URLs provided"
            )
            return taskId
        }

        crawlScope.launch {
            try {
                val allPages = withContext(Dispatchers.IO) {
                    val results = mutableListOf<CrawlPageResult>()
                    for (seedUrl in seedUrls) {
                        val seedRequest = request.copy(url = seedUrl, urls = null)
                        val pages = when {
                            seedRequest.depth == 0 -> crawlDepth0(taskId, seedRequest)
                            seedRequest.depth <= 1 -> crawlDepth1(taskId, seedRequest)
                            else -> crawlDepthN(taskId, seedRequest)
                        }
                        results.addAll(pages)
                    }
                    results
                }
                taskStore[taskId] = CrawlResponse(
                    taskId = taskId,
                    status = ResourceStatus.getStatusText(ResourceStatus.SC_OK),
                    pagesFound = allPages.size,
                    pages = allPages
                )
                logger.info("Crawl task {} completed: {} pages", taskId, allPages.size)
            } catch (e: CancellationException) {
                taskStore[taskId] = CrawlResponse(
                    taskId = taskId,
                    status = ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
                    error = "Crawl cancelled or timed out"
                )
                logger.warn("Crawl task {} cancelled", taskId)
            } catch (e: Exception) {
                taskStore[taskId] = CrawlResponse(
                    taskId = taskId,
                    status = ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
                    error = e.message ?: "Unknown error"
                )
                logger.error("Crawl task {} failed: {}", taskId, e.message, e)
            }
        }

        logger.info("Crawl task submitted: {} seeds={} depth={}", taskId, seedUrls.size, request.depth)
        return taskId
    }

    /**
     * Get the current status/result of a crawl task.
     */
    fun getResult(taskId: String): CrawlResponse {
        return taskStore[taskId] ?: CrawlResponse(
            taskId = taskId,
            status = ResourceStatus.getStatusText(ResourceStatus.SC_NOT_FOUND),
            error = "Task not found: $taskId"
        )
    }

    // ------------------------------------------------------------------
    // Depth=0: fetch each seed URL directly, no link discovery
    // ------------------------------------------------------------------

    private suspend fun crawlDepth0(taskId: String, request: CrawlRequest): List<CrawlPageResult> {
        val session = AgenticContexts.createSession()
        try {
            val options = parseOptions(session, request.args)
            val document = session.loadDocument(request.url, options)

            val extracted = if (request.sql != null) {
                executeSqlQuery(session, request.url, request.sql)
            } else null

            val result = CrawlPageResult(
                url = request.url,
                title = document.title,
                contentLength = document.contentLength()?.toLong(),
                depth = 0,
                extracted = extracted
            )
            logger.info("Crawl {}: fetched seed URL {}", taskId, request.url)
            return listOf(result)
        } catch (e: Exception) {
            logger.error("Crawl {}: failed to fetch seed URL {}", taskId, request.url, e)
            return listOf(CrawlPageResult(
                url = request.url,
                title = null,
                contentLength = null,
                depth = 0
            ))
        } finally {
            runCatching { session.close() }
        }
    }

    // ------------------------------------------------------------------
    // Depth=1: extract out-links from the portal page and load each one
    // ------------------------------------------------------------------

    private suspend fun crawlDepth1(taskId: String, request: CrawlRequest): List<CrawlPageResult> {
        val session = AgenticContexts.createSession()
        try {
            val options = parseOptions(session, request.args)
            if (options.outLinkSelector.isNullOrBlank()) {
                logger.warn("Crawl {}: no outLinkSelector provided, returning empty result", taskId)
                return emptyList()
            }

            val results = Collections.synchronizedList(mutableListOf<CrawlPageResult>())
            val outLinks = extractOutLinks(session, request.url, options)

            if (outLinks.isEmpty()) {
                logger.info("Crawl {}: no out-links found on portal page", taskId)
                return emptyList()
            }

            logger.info("Crawl {}: found {} out-links, submitting...", taskId, outLinks.size)

            // Submit each out-link as a ParsableHyperlink so we can collect results
            outLinks.forEach { linkUrl ->
                val hyperlink = ParsableHyperlink("$linkUrl -parse") { _page: WebPage, _document: FeaturedDocument ->
                    val extracted = if (request.sql != null) {
                        executeSqlQuery(session, linkUrl, request.sql)
                    } else null
                    results.add(CrawlPageResult(
                        url = linkUrl,
                        title = _document.title,
                        contentLength = _page.contentLength,
                        depth = 1,
                        extracted = extracted
                    ))
                }
                session.submit(hyperlink)
            }

            // Wait until all submitted out-pages are processed
            withTimeout(300_000L) { // 5 minute timeout for depth=1
                AgenticContexts.await()
            }

            return results.toList()
        } catch (e: TimeoutCancellationException) {
            logger.warn("Crawl {}: depth=1 timed out", taskId)
            throw e
        } finally {
            runCatching { session.close() }
        }
    }

    // ------------------------------------------------------------------
    // Depth>1: BFS continuous crawl using ParsableHyperlink parse handlers
    // ------------------------------------------------------------------

    private suspend fun crawlDepthN(taskId: String, request: CrawlRequest): List<CrawlPageResult> {
        // Use sequential browsers for continuous crawling (same as _5_ContinuousCrawler.kt)
        PulsarSettings.withSequentialBrowsers().maxOpenTabs(8)

        val session = AgenticContexts.createSession()
        try {
            val options = parseOptions(session, request.args)
            val maxDepth = request.depth

            val results = Collections.synchronizedList(mutableListOf<CrawlPageResult>())
            val visited = ConcurrentHashMap.newKeySet<String>()

            // Use lateinit to allow recursive reference within the parse handler
            lateinit var parseHandler: (WebPage, FeaturedDocument) -> Any?

            parseHandler = { page: WebPage, document: FeaturedDocument ->
                val pageUrl = document.baseURI ?: page.url
                val currentDepth = extractDepth(page) ?: 1

                // Record this page
                val extracted = if (request.sql != null) {
                    executeSqlQuery(session, pageUrl, request.sql)
                } else null
                results.add(CrawlPageResult(
                    url = pageUrl,
                    title = document.title,
                    contentLength = page.contentLength,
                    depth = currentDepth,
                    extracted = extracted
                ))
                visited.add(normalizeForVisit(pageUrl))

                logger.debug("Crawl {}: depth={} page={}", taskId, currentDepth, pageUrl)

                // If we haven't reached max depth, extract and submit more links
                if (currentDepth < maxDepth) {
                    val selector = options.outLinkSelector
                    if (!selector.isNullOrBlank()) {
                        val newLinks = document.selectHyperlinks(selector)
                            .map { it.url }
                            .filter { link ->
                                val norm = normalizeForVisit(link)
                                norm !in visited && matchesPattern(link, options.outLinkPattern)
                            }
                            .take(options.topLinks)
                            .toList()

                        if (newLinks.isNotEmpty()) {
                            val args = buildArgsForDepth(options, currentDepth + 1)
                            newLinks.forEach { link ->
                                visited.add(normalizeForVisit(link))
                                val hyperlink = ParsableHyperlink("$link $args", parseHandler)
                                session.submit(hyperlink)
                            }
                            logger.debug(
                                "Crawl {}: submitted {} links at depth {}",
                                taskId,
                                newLinks.size,
                                currentDepth + 1
                            )
                        }
                    }
                }
            } // parseHandler defined

            // Submit the seed URL
            val seedArgs = buildArgsForDepth(options, 1)
            val seedHyperlink = ParsableHyperlink("${request.url} $seedArgs", parseHandler)
            session.submit(seedHyperlink)

            // Wait until the URL pool is drained
            val timeoutMs = (maxDepth * 300_000L).coerceAtMost(1_800_000L) // max 30 min
            withTimeout(timeoutMs) {
                AgenticContexts.await()
            }

            return results.toList()
        } catch (e: TimeoutCancellationException) {
            logger.warn("Crawl {}: depth>1 timed out", taskId)
            throw e
        } finally {
            runCatching { session.close() }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun parseOptions(session: PulsarSession, args: String): LoadOptions {
        return if (args.isBlank()) {
            session.options()
        } else {
            session.options(args)
        }
    }

    /**
     * Execute an X-SQL query against the page at [pageUrl].
     * Uses [SQLTemplate] to substitute @url placeholder, then runs the query
     * via the session's SQL context.  Returns the result set as a list of row
     * maps, or null on failure (logged, not propagated — a single extraction
     * failure does not fail the whole crawl).
     */
    private fun executeSqlQuery(
        session: PulsarSession,
        pageUrl: String,
        sql: String
    ): List<Map<String, Any?>>? {
        return try {
            val processedSql = SQLTemplate(sql).createSQL(pageUrl)
            val sqlContext = session.context as? AbstractBrowser4SQLContext
                ?: run {
                    logger.warn("Session context is not an SQL context; cannot execute X-SQL")
                    return null
                }
            val rs: ResultSet = sqlContext.executeQuery(processedSql)
            val copied = ResultSets.copyResultSet(rs)
            ResultSetUtils.getTextEntitiesFromResultSet(copied)
        } catch (e: Exception) {
            logger.error("Failed to execute X-SQL on '{}': {}", pageUrl, e.message)
            null
        }
    }

    /**
     * Load the portal page and extract out-links using the same logic as
     * [ai.platon.pulsar.skeleton.session.AbstractPulsarSession.submitForOutPages0].
     */
    private suspend fun extractOutLinks(
        session: PulsarSession,
        portalUrl: String,
        options: LoadOptions
    ): List<String> {
        val normOptions = session.normalize(options)
        val document = session.loadDocument(portalUrl, normOptions)
        val selector = normOptions.outLinkSelector.orEmpty()
        if (selector.isBlank()) return emptyList()

        return document.select(selector) { element ->
            val href = element.attr("href").takeIf { it.isNotBlank() }
                ?: element.attr("src").takeIf { it.isNotBlank() }
                ?: return@select null
            // Normalize: resolve relative URLs, optional query stripping
            val resolved = runCatching {
                java.net.URI(portalUrl).resolve(href).toString()
            }.getOrElse { href }

            if (normOptions.ignoreUrlQuery) {
                resolved.substringBefore('?')
            } else {
                resolved
            }
        }
            .filterNotNull()
            .filter { link -> matchesPattern(link, normOptions.outLinkPattern) }
            .distinct()
            .take(normOptions.topLinks)
            .toList()
    }

    private fun matchesPattern(url: String, pattern: String?): Boolean {
        if (pattern.isNullOrBlank() || pattern == ".+") return true
        return runCatching {
            Regex(pattern).containsMatchIn(url)
        }.getOrDefault(true)
    }

    private fun normalizeForVisit(url: String): String {
        return url.trim().lowercase()
            .removeSuffix("/")
            .substringBefore('?')  // strip query for dedup
    }

    private fun extractDepth(page: WebPage): Int? {
        // Depth is embedded as a synthetic option in the URL's args string.
        // ParsableHyperlink stores the URL with args like "https://... -depth 2 -parse"
        val url = page.url
        val match = Regex("""-depth\s+(\d+)""").find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun buildArgsForDepth(options: LoadOptions, depth: Int): String {
        val parts = mutableListOf("-depth $depth -parse")
        if (options.outLinkSelector.isNotBlank()) {
            parts.add("-outLink \"${options.outLinkSelector}\"")
        }
        if (options.outLinkPattern.isNotBlank() && options.outLinkPattern != ".+") {
            parts.add("-outLinkPattern \"${options.outLinkPattern}\"")
        }
        if (options.refresh) parts.add("-refresh")
        return parts.joinToString(" ")
    }
}
