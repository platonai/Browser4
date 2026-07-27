package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.context.sql.AbstractBrowser4SQLContext
import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.skeleton.workflow.common.url.ParsableHyperlink
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.sql.ResultSet
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class CrawlRequest @JsonCreator constructor(
    @param:JsonProperty("url") val url: String = "",
    @param:JsonProperty("args") val args: String = "",
    @param:JsonProperty("depth") val depth: Int = 1,
    @param:JsonProperty("urls") val urls: List<String>? = null,
    @param:JsonProperty("sql") val sql: String? = null
)

data class CrawlSeedStatus(
    val url: String,
    val status: String,  // "fetched", "skipped", "error"
    val pagesReturned: Int = 0,
    val error: String? = null,
)

data class CrawlResponse(
    val taskId: String = "",
    val status: String = "CREATED",
    val pagesFound: Int = 0,
    val pages: List<CrawlPageResult>? = null,
    val error: String? = null,
    val diagnostic: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val taskTTLMinutes: Int = 60,
    /** Set when a worker first picks up the task (first non-CREATED status). */
    var startedTime: java.time.Instant? = null,
    /** Set when the task reaches a terminal state (OK, TIMEOUT, ERROR). */
    var finishTime: java.time.Instant? = null,
    /** Per-seed-URL processing status (populated when verbose). */
    val seedStatuses: List<CrawlSeedStatus>? = null,
)

data class CrawlPageResult(
    val url: String,
    val title: String? = null,
    val contentLength: Long? = null,
    val depth: Int = 0,
    val extracted: List<Map<String, Any?>>? = null,
    /** Non-null when X-SQL extraction was attempted but failed on this page. */
    val extractionError: String? = null,
)

@Service
class CrawlService(
    private val sessionManager: PulsarSessionManager
) {
    private val logger = LoggerFactory.getLogger(CrawlService::class.java)

    /**
     * Task store: taskId -> CrawlResponse.
     *
     * Size-bounded at 100 entries; Window TinyLFU eviction beyond that.
     * Terminal tasks are purged by TTL in [purgeExpiredTasks] long before the
     * cache fills up in normal operation.
     */
    private val taskStore: Cache<String, CrawlResponse> = Caffeine.newBuilder()
        .maximumSize(100)
        .recordStats()
        .build()

    /** Active coroutine jobs: taskId -> Job (for cancellation) */
    private val jobStore = ConcurrentHashMap<String, Job>()

    internal val persistence = JsonlPersistence(
        file = crawlPersistencePath(),
        clazz = CrawlResponse::class,
        objectMapper = pulsarObjectMapper()
    )

    @PostConstruct
    fun restoreFromDisk() {
        val now = System.currentTimeMillis()
        val ttlMillis = taskTtlMinutes * 60_000L
        val terminalStatuses = setOf(
            ResourceStatus.getStatusText(ResourceStatus.SC_OK),
            ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
            ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
        )
        persistence.restore { entry ->
            if (entry.taskId.isBlank()) return@restore
            // Skip terminal entries that have already expired — they were
            // purged from memory before shutdown and should not be revived.
            if (entry.status in terminalStatuses && (now - entry.createdAt) > ttlMillis) {
                logger.debug("Skipping expired crawl task {} during restore", entry.taskId)
                return@restore
            }
            taskStore.put(entry.taskId, entry)
        }
    }

    private fun onStatusChanged(response: CrawlResponse) {
        persistence.append(response)
    }

    /** Dedicated dispatcher for crawl operations */
    private val crawlDispatcher = Dispatchers.IO.limitedParallelism(5)

    private val crawlScope = CoroutineScope(
        crawlDispatcher + SupervisorJob() + CoroutineName("crawl")
    )

    /** How long to keep completed/failed tasks in the store (minutes). */
    @Volatile
    var taskTtlMinutes: Int = 1440 // 1 day

    init {
        // Periodically purge expired tasks so stale entries don't accumulate
        crawlScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // every 5 minutes
                purgeExpiredTasks()
            }
        }
    }

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
        taskStore.put(taskId, response)
        onStatusChanged(response)

        // Compute effective seed URL list
        val seedUrls = if (!request.urls.isNullOrEmpty()) {
            request.urls
        } else if (request.url.isNotBlank()) {
            listOf(request.url)
        } else {
            emptyList()
        }

        if (seedUrls.isEmpty()) {
            val errorResponse = CrawlResponse(
                taskId = taskId,
                status = ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
                error = "No URLs provided"
            )
            taskStore.put(taskId, errorResponse)
            onStatusChanged(errorResponse)
            return taskId
        }

        val job = crawlScope.launch {
            try {
                // Mark started when work begins
                val existing = taskStore.getIfPresent(taskId)
                if (existing != null && existing.startedTime == null) {
                    existing.startedTime = java.time.Instant.now()
                }
                val result = withContext(Dispatchers.IO) {
                    val results = mutableListOf<CrawlPageResult>()
                    val seedStatuses = mutableListOf<CrawlSeedStatus>()
                    val totalSeeds = seedUrls.size
                    for ((index, seedUrl) in seedUrls.withIndex()) {
                        logger.info(
                            "Crawl {}: processing seed URL {}/{}: {}",
                            taskId, index + 1, totalSeeds, seedUrl
                        )
                        val seedRequest = request.copy(url = seedUrl, urls = null)
                        val pages = try {
                            val fetched = when {
                                seedRequest.depth == 0 -> crawlDepth0(taskId, seedRequest)
                                seedRequest.depth <= 1 -> crawlDepth1(taskId, seedRequest)
                                else -> crawlDepthN(taskId, seedRequest)
                            }
                            logger.info(
                                "Crawl {}: seed URL {}/{} completed: {} → {} page(s)",
                                taskId, index + 1, totalSeeds, seedUrl, fetched.size
                            )
                            seedStatuses.add(CrawlSeedStatus(
                                url = seedUrl,
                                status = "fetched",
                                pagesReturned = fetched.size
                            ))
                            fetched
                        } catch (e: Exception) {
                            logger.error(
                                "Crawl {}: seed URL {}/{} failed: {} — {}",
                                taskId, index + 1, totalSeeds, seedUrl, e.message, e
                            )
                            seedStatuses.add(CrawlSeedStatus(
                                url = seedUrl,
                                status = "error",
                                pagesReturned = 0,
                                error = e.message
                            ))
                            listOf(
                                CrawlPageResult(
                                    url = seedUrl,
                                    title = null,
                                    contentLength = null,
                                    depth = 0
                                )
                            )
                        }
                        results.addAll(pages)
                    }
                    Pair(results, seedStatuses)
                }
                val (allPages, seedStatuses) = result
                val existingDiagnostic = taskStore.getIfPresent(taskId)?.diagnostic
                val now = java.time.Instant.now()
                val previous = taskStore.getIfPresent(taskId)
                val completed = CrawlResponse(
                    taskId = taskId,
                    status = ResourceStatus.getStatusText(ResourceStatus.SC_OK),
                    pagesFound = allPages.size,
                    pages = allPages,
                    diagnostic = existingDiagnostic,
                    startedTime = previous?.startedTime ?: now,
                    finishTime = now,
                    seedStatuses = seedStatuses
                )
                taskStore.put(taskId, completed)
                onStatusChanged(completed)
                logger.info("Crawl task {} completed: {} pages", taskId, allPages.size)
            } catch (e: CancellationException) {
                val existing = taskStore.getIfPresent(taskId)
                val now = java.time.Instant.now()
                if (existing == null || existing.status == ResourceStatus.getStatusText(ResourceStatus.SC_CREATED)) {
                    val timeout = CrawlResponse(
                        taskId = taskId,
                        status = ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
                        error = "Crawl cancelled or timed out",
                        startedTime = existing?.startedTime ?: now,
                        finishTime = now
                    )
                    taskStore.put(taskId, timeout)
                    onStatusChanged(timeout)
                }
                logger.warn("Crawl task {} cancelled", taskId)
            } catch (e: Exception) {
                val existing = taskStore.getIfPresent(taskId)
                val now = java.time.Instant.now()
                val failed = CrawlResponse(
                    taskId = taskId,
                    status = ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
                    error = e.message ?: "Unknown error",
                    startedTime = existing?.startedTime ?: now,
                    finishTime = now
                )
                taskStore.put(taskId, failed)
                onStatusChanged(failed)
                logger.error("Crawl task {} failed: {}", taskId, e.message, e)
            } finally {
                jobStore.remove(taskId)
            }
        }

        jobStore[taskId] = job

        logger.info("Crawl task submitted: {} seeds={} depth={}", taskId, seedUrls.size, request.depth)
        return taskId
    }

    /**
     * Cancel a running crawl task by its ID.
     * @return true if the task was found and cancelled, false otherwise.
     */
    fun cancel(taskId: String): Boolean {
        val job = jobStore.remove(taskId) ?: return false
        job.cancel()
        val now = java.time.Instant.now()
        val previous = taskStore.getIfPresent(taskId)
        val cancelled = CrawlResponse(
            taskId = taskId,
            status = ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
            error = "Cancelled by user",
            startedTime = previous?.startedTime ?: now,
            finishTime = now
        )
        taskStore.put(taskId, cancelled)
        onStatusChanged(cancelled)
        logger.info("Crawl task {} cancelled by user", taskId)
        return true
    }

    /**
     * Remove all terminal-state tasks from the store.
     * @return the number of tasks removed.
     */
    fun clearTerminal(): Int {
        val terminalStatuses = setOf(
            ResourceStatus.getStatusText(ResourceStatus.SC_OK),
            ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
            ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
        )
        val toRemove = taskStore.asMap().entries.filter { it.value.status in terminalStatuses }
        toRemove.forEach { taskStore.invalidate(it.key) }
        logger.info("Cleared {} terminal crawl tasks", toRemove.size)
        return toRemove.size
    }

    /**
     * Purge tasks whose TTL has expired.  Only removes terminal-state tasks;
     * actively-running tasks are never purged.
     */
    private fun purgeExpiredTasks() {
        val now = System.currentTimeMillis()
        val ttlMillis = taskTtlMinutes * 60_000L
        val terminalStatuses = setOf(
            ResourceStatus.getStatusText(ResourceStatus.SC_OK),
            ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
            ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
        )

        val expired = taskStore.asMap().entries.filter {
            it.value.status in terminalStatuses &&
                (now - it.value.createdAt) > ttlMillis
        }
        if (expired.isEmpty()) return

        expired.forEach { taskStore.invalidate(it.key) }
        logger.info("Purged {} expired crawl tasks (TTL: {} min)", expired.size, taskTtlMinutes)

        // Rewrite the persistence file so purged tasks don't revive on restart.
        // The JSONL is append-only and would otherwise accumulate stale entries forever.
        persistence.clear()
        taskStore.asMap().values.forEach { persistence.append(it) }
    }

    /**
     * Get the current status/result of a crawl task.
     */
    fun getResult(taskId: String): CrawlResponse {
        return taskStore.getIfPresent(taskId) ?: CrawlResponse(
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
            val page = session.load(request.url, options)
            val document = session.parse(page)

            val extractionResult = if (request.sql != null) {
                executeSqlQuery(session, request.url, request.sql)
            } else Pair(null, null)

            val result = CrawlPageResult(
                url = request.url,
                title = document.title,
                contentLength = page.contentLength,
                depth = 0,
                extracted = extractionResult.first,
                extractionError = extractionResult.second
            )
            logger.info("Crawl {}: fetched seed URL {}", taskId, request.url)
            return listOf(result)
        } catch (e: Exception) {
            logger.error("Crawl {}: failed to fetch seed URL {}", taskId, request.url, e)
            return listOf(
                CrawlPageResult(
                    url = request.url,
                    title = null,
                    contentLength = null,
                    depth = 0
                )
            )
        } finally {
            runCatching { session.close() }
        }
    }

    // ------------------------------------------------------------------
    // Depth=1: extract out-links from the portal page and load each one
    // ------------------------------------------------------------------

    private suspend fun crawlDepth1(taskId: String, request: CrawlRequest): List<CrawlPageResult> {
        val session = AgenticContexts.createSession()
        val results = Collections.synchronizedList(mutableListOf<CrawlPageResult>())
        try {
            val options = parseOptions(session, request.args)
            if (options.outLinkSelector.isNullOrBlank()) {
                logger.warn("Crawl {}: no outLinkSelector provided, returning empty result", taskId)
                return emptyList()
            }
            val outLinks = extractOutLinks(session, request.url, options)

            if (outLinks.isEmpty()) {
                val message = "No out-links found on portal page. " +
                    "The page loaded but the CSS selector '${options.outLinkSelector}' " +
                    "matched zero elements. Verify the selector or check that the " +
                    "page content loaded correctly."
                logger.info("Crawl {}: {}", taskId, message)
                // Write diagnostic to taskStore so the CLI can display it
                taskStore.put(taskId, CrawlResponse(
                    taskId = taskId,
                    status = ResourceStatus.getStatusText(ResourceStatus.SC_OK),
                    pagesFound = 0,
                    diagnostic = message
                ))
                return emptyList()
            }

            logger.info("Crawl {}: found {} out-links, submitting...", taskId, outLinks.size)

            // Per-crawl completion tracking: use a CompletableDeferred signaled by
            // an AtomicInteger counter instead of AgenticContexts.await(), which
            // relies on the global activeContext and can deadlock when multiple
            // crawls run concurrently or stale contexts persist in PulsarContexts.
            val pendingCount = AtomicInteger(outLinks.size)
            val allCompleted = CompletableDeferred<Unit>()

            // Submit each out-link as a ParsableHyperlink so we can collect results
            outLinks.forEach { linkUrl ->
                val hyperlink = ParsableHyperlink("$linkUrl -parse") { _page: WebPage, _document: FeaturedDocument ->
                    val extractionResult = if (request.sql != null) {
                        executeSqlQuery(session, linkUrl, request.sql)
                    } else Pair(null, null)
                    results.add(
                        CrawlPageResult(
                            url = linkUrl,
                            title = _document.title,
                            contentLength = _page.contentLength,
                            depth = 1,
                            extracted = extractionResult.first,
                            extractionError = extractionResult.second
                        )
                    )
                    // Signal completion when the last out-link finishes processing
                    if (pendingCount.decrementAndGet() == 0) {
                        allCompleted.complete(Unit)
                    }
                }
                session.submit(hyperlink)
            }

            // Wait until all submitted out-pages are processed (per-crawl, not global)
            withTimeout(300_000L) { // 5 minute timeout for depth=1
                allCompleted.await()
            }

            return results.toList()
        } catch (e: TimeoutCancellationException) {
            logger.warn("Crawl {}: depth=1 timed out after collecting {} pages; saving partial results", taskId, results.size)
            // Save partial results BEFORE re-throwing — the outer launch catch
            // guards against overwriting already-saved partial results
            val previous = taskStore.getIfPresent(taskId)
            val timeout = CrawlResponse(
                taskId = taskId,
                status = ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
                pagesFound = results.size,
                pages = results.toList(),
                error = "Crawl timed out after collecting ${results.size} pages (partial results saved)",
                startedTime = previous?.startedTime ?: java.time.Instant.now(),
                finishTime = java.time.Instant.now()
            )
            taskStore.put(taskId, timeout)
            onStatusChanged(timeout)
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
        try { PulsarSettings.withSequentialBrowsers().maxOpenTabs(8) } catch (e: Exception) { /* optional config */ }

        val session = AgenticContexts.createSession()
        val results = Collections.synchronizedList(mutableListOf<CrawlPageResult>())
        try {
            val options = parseOptions(session, request.args)
            val maxDepth = request.depth
            val visited = ConcurrentHashMap.newKeySet<String>()

            // Per-crawl completion tracking: submitted count increments when new
            // links are submitted, completed count increments when parseHandler
            // finishes processing a page.  When they match the pool is drained.
            val submittedCount = AtomicInteger(1) // seed URL counts as submitted
            val completedCount = AtomicInteger(0)
            val allCompleted = CompletableDeferred<Unit>()

            // Use lateinit to allow recursive reference within the parse handler
            lateinit var parseHandler: (WebPage, FeaturedDocument) -> Any?

            parseHandler = { page: WebPage, document: FeaturedDocument ->
                val pageUrl = document.baseURI ?: page.url
                val currentDepth = extractDepth(page) ?: 1

                // Record this page
                val extractionResult = if (request.sql != null) {
                    executeSqlQuery(session, pageUrl, request.sql)
                } else Pair(null, null)
                results.add(
                    CrawlPageResult(
                        url = pageUrl,
                        title = document.title,
                        contentLength = page.contentLength,
                        depth = currentDepth,
                        extracted = extractionResult.first,
                        extractionError = extractionResult.second
                    )
                )
                visited.add(normalizeForVisit(pageUrl))

                logger.debug("Crawl {}: depth={} page={}", taskId, currentDepth, pageUrl)

                // If we haven't reached max depth, extract and submit more links
                if (currentDepth < maxDepth) {
                    val selector = options.outLinkSelector
                    if (!selector.isNullOrBlank()) {
                        val allLinks = document.selectHyperlinks(selector)
                            .map { it.url }
                            .toList()
                        val (dupes, fresh) = allLinks.partition { link ->
                            normalizeForVisit(link) in visited
                        }
                        if (dupes.isNotEmpty()) {
                            logger.debug(
                                "Crawl {}: {} link(s) skipped — already visited (depth={})",
                                taskId, dupes.size, currentDepth
                            )
                        }
                        val newLinks = fresh
                            .filter { link -> matchesPattern(link, options.outLinkPattern) }
                            .take(options.topLinks)
                            .toList()

                        if (newLinks.isNotEmpty()) {
                            submittedCount.addAndGet(newLinks.size)
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

                // Signal completion when all submitted pages have been processed
                if (completedCount.incrementAndGet() == submittedCount.get()) {
                    allCompleted.complete(Unit)
                }
            } // parseHandler defined

            // Submit the seed URL
            val seedArgs = buildArgsForDepth(options, 1)
            val seedHyperlink = ParsableHyperlink("${request.url} $seedArgs", parseHandler)
            session.submit(seedHyperlink)

            // Wait until the URL pool is drained (per-crawl completion, not global)
            val timeoutMs = (maxDepth * 300_000L).coerceAtMost(1_800_000L) // max 30 min
            withTimeout(timeoutMs) {
                allCompleted.await()
            }

            return results.toList()
        } catch (e: TimeoutCancellationException) {
            logger.warn("Crawl {}: depth>1 timed out after collecting {} pages; saving partial results", taskId, results.size)
            val previous = taskStore.getIfPresent(taskId)
            val timeout = CrawlResponse(
                taskId = taskId,
                status = ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
                pagesFound = results.size,
                pages = results.toList(),
                error = "Crawl timed out after collecting ${results.size} pages (partial results saved)",
                startedTime = previous?.startedTime ?: java.time.Instant.now(),
                finishTime = java.time.Instant.now()
            )
            taskStore.put(taskId, timeout)
            onStatusChanged(timeout)
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
     * via the session's SQL context.  Returns a pair of (extracted rows, error message).
     * On success the error is null; on failure the rows are null and the error
     * describes what went wrong.
     */
    private fun executeSqlQuery(
        session: PulsarSession,
        pageUrl: String,
        sql: String
    ): Pair<List<Map<String, Any?>>?, String?> {
        return try {
            val processedSql = SQLTemplate(sql).createSQL(pageUrl)
            logger.info("Crawl X-SQL: executing query on '{}': {}", pageUrl, processedSql.take(300))

            val sqlContext = session.context as? AbstractBrowser4SQLContext
                ?: run {
                    val msg = "Session context is not an SQL context; cannot execute X-SQL"
                    logger.warn(msg)
                    return Pair(null, msg)
                }
            val rs: ResultSet = sqlContext.executeQuery(processedSql)
            val copied = ResultSetUtils.copyResultSet(rs)
            val rows = ResultSetUtils.getTextEntitiesFromResultSet(copied)
            if (rows.isEmpty()) {
                logger.info("Crawl X-SQL: query returned 0 rows for '{}'", pageUrl)
            } else {
                logger.info("Crawl X-SQL: extracted {} row(s) from '{}'", rows.size, pageUrl)
            }
            Pair(rows, null)
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            logger.error("Failed to execute X-SQL on '{}': {} (SQL: {})", pageUrl, msg, sql.take(300))
            Pair(null, msg)
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
        val rawSelector = normOptions.outLinkSelector.orEmpty()
        if (rawSelector.isBlank()) return emptyList()

        // Diagnostic: log the selector as-provided; normalize() already
        // calls correctOutLinkSelector() internally, so outLinkSelector is corrected.
        val correctedSelector = normOptions.outLinkSelector
        logger.debug(
            "extractOutLinks: rawSelector='{}' correctedSelector='{}' ignoreUrlQuery={}",
            rawSelector, correctedSelector, normOptions.ignoreUrlQuery
        )

        val document = session.loadDocument(portalUrl, normOptions)

        // Diagnostic: verify the document has meaningful content
        val docHtmlLength = document.html.length
        val allAnchors = document.select("a").size
        logger.debug(
            "extractOutLinks: document.html.length={} document.select('a').size={}",
            docHtmlLength, allAnchors
        )

        val selector = correctedSelector ?: rawSelector
        val matchedElements = document.select(selector)
        if (matchedElements.isEmpty() && allAnchors > 0) {
            logger.warn(
                "extractOutLinks: document has {} anchors but selector '{}' matched 0 elements. " +
                "Verify the CSS selector targets the correct elements.",
                allAnchors, selector
            )
        }
        logger.debug(
            "extractOutLinks: selector='{}' matched {} element(s)",
            selector, matchedElements.size
        )

        return matchedElements.mapNotNull { element ->
            val href = element.attr("href").takeIf { it.isNotBlank() }
                ?: element.attr("src").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
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
            .filter { link -> matchesPattern(link, normOptions.outLinkPattern) }
            .distinct()
            .take(normOptions.topLinks)
            .toList()
    }

    companion object {
        fun crawlPersistencePath(): Path = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "data", "crawl", "crawl-tasks.jsonl"
        )
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
