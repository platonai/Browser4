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
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
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

    @EventListener(ApplicationReadyEvent::class)
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
                // Mark as "PROCESSING" as soon as the worker picks up the task.
                // Without this, the CLI sees "CREATED" for the entire duration
                // of the crawl (which can be 80-100s for many URLs), making it
                // appear as if nothing is happening.
                val processing = CrawlResponse(
                    taskId = taskId,
                    status = "PROCESSING",
                    pagesFound = 0,
                    startedTime = java.time.Instant.now()
                )
                taskStore.put(taskId, processing)
                onStatusChanged(processing)
                val result = withTimeout(CRAWL_TASK_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                    val results = mutableListOf<CrawlPageResult>()
                    val seedStatuses = mutableListOf<CrawlSeedStatus>()
                    val totalSeeds = seedUrls.size

                    // For depth=0 (bulk fetch mode), reuse a single session across
                    // all seed URLs.  Creating a new session per seed causes the HTTP
                    // protocol handler to be deregistered when the previous session is
                    // closed, and the next session's handler may not re-register in time
                    // for the page load — producing "Protocol not found" (status 1600)
                    // for every seed after the first.  A single session avoids the
                    // deregistration/re-registration cycle entirely.
                    val sharedDepth0Session = if (request.depth == 0) {
                        AgenticContexts.createSession()
                    } else {
                        null
                    }

                    try {
                        for ((index, seedUrl) in seedUrls.withIndex()) {
                            logger.info(
                                "Crawl {}: processing seed URL {}/{}: {}",
                                taskId, index + 1, totalSeeds, seedUrl
                            )
                            val seedRequest = request.copy(url = seedUrl, urls = null)
                            val pages = try {
                                val fetched = when {
                                    seedRequest.depth == 0 -> crawlDepth0(taskId, seedRequest, sharedDepth0Session)
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

                            // Publish incremental progress to the in-memory task
                            // store so the CLI polling loop can show per-seed
                            // extraction progress (e.g. "2/5 seeds done, 4 rows
                            // extracted so far").  We only update the in-memory
                            // store — persistence.append is deferred until the
                            // crawl completes to avoid writing intermediate states.
                            val currentResult = taskStore.getIfPresent(taskId)
                            val incrementalResponse = CrawlResponse(
                                taskId = taskId,
                                status = "PROCESSING",
                                pagesFound = results.size,
                                pages = results.toList(),
                                diagnostic = currentResult?.diagnostic,
                                startedTime = currentResult?.startedTime ?: java.time.Instant.now(),
                                seedStatuses = seedStatuses.toList()
                            )
                            taskStore.put(taskId, incrementalResponse)

                            // Small delay between seed URLs to allow the browser
                            // time to settle between page loads.  When using a shared
                            // session this is less critical (no protocol handler
                            // re-registration), but still prevents resource contention.
                            if (index < totalSeeds - 1) {
                                delay(SEED_INTERVAL_MS)
                            }
                        }
                    } finally {
                        runCatching { sharedDepth0Session?.close() }
                    }
                    Pair(results, seedStatuses)
                }
                } // withTimeout
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

        // Rewrite the JSONL persistence file so cleared tasks don't revive on restart.
        // Without this, terminal tasks removed from the in-memory Caffeine cache are
        // re-read from the append-only JSONL file at startup.
        if (toRemove.isNotEmpty()) {
            persistence.clear()
            taskStore.asMap().values.forEach { persistence.append(it) }
        }

        return toRemove.size
    }

    /**
     * Remove ALL tasks from the store, including actively-running ones.
     * Cancels running jobs before clearing.  Use with caution.
     * @return the number of tasks removed.
     */
    fun clearAll(): Int {
        // Cancel all active jobs first
        jobStore.values.forEach { it.cancel() }
        jobStore.clear()

        val size = taskStore.asMap().size.toInt()
        taskStore.invalidateAll()
        persistence.clear()
        logger.info("Cleared all {} crawl tasks (including active)", size)
        return size
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

    /**
     * Fetch a single seed URL at depth=0 and optionally run X-SQL extraction.
     *
     * @param sharedSession When provided, this session is reused instead of
     *   creating a new one, and it is NOT closed when the method returns.
     *   This eliminates protocol-handler deregistration races when processing
     *   multiple depth=0 seeds in sequence.
     */
    private suspend fun crawlDepth0(
        taskId: String,
        request: CrawlRequest,
        sharedSession: PulsarSession? = null
    ): List<CrawlPageResult> {
        // Depth=0 bulk-fetch: always use -refresh so that internal HTTP
        // caches and protocol-level state from prior sessions don't cause
        // 0-byte responses for URLs after the first.
        val effectiveArgs = if (request.args.isBlank()) "-refresh" else "${request.args} -refresh"

        var lastError: Exception? = null
        repeat(MAX_FETCH_RETRIES) { attempt ->
            // Reuse the shared session if provided; otherwise create a private one.
            // Only private (owned) sessions are closed in the finally block.
            val ownsSession = sharedSession == null
            val session: PulsarSession = sharedSession ?: AgenticContexts.createSession()

            try {
                val options = parseOptions(session, effectiveArgs)
                val page = session.load(request.url, options)

                // If the page loaded but content is empty, retry after a delay.
                // When using a shared session the protocol handler shouldn't be
                // an issue, but transient network problems can still cause this.
                if (page.contentLength == 0L) {
                    val msg = "fetch returned 0 bytes (possible protocol handler not ready)"
                    if (attempt < MAX_FETCH_RETRIES - 1) {
                        val retryDelay = FETCH_RETRY_DELAY_MS * (1L shl attempt)
                        logger.warn("Crawl {}: {} for '{}', retrying in {}ms (attempt {}/{})",
                            taskId, msg, request.url, retryDelay, attempt + 1, MAX_FETCH_RETRIES)
                        if (ownsSession) runCatching { session.close() }
                        delay(retryDelay)
                        return@repeat
                    }
                    logger.error("Crawl {}: {} for '{}' after {} attempts", taskId, msg, request.url, MAX_FETCH_RETRIES)
                    return listOf(CrawlPageResult(
                        url = request.url, title = null, contentLength = 0, depth = 0,
                        extractionError = msg
                    ))
                }

                val document = session.parse(page)

                val extractionResult = if (request.sql != null) {
                    executeSqlQuery(session, request.url, request.sql)
                } else Pair(null, null)

                // Fallback: if document.title is blank, try extracting <title>
                // from the raw HTML. The parse pipeline may skip title extraction
                // on cached/stale content, leaving document.title null/empty even
                // though the raw HTML has a valid <title> tag.
                val title = document.title.takeIf { !it.isNullOrBlank() }
                    ?: extractTitleFromHtml(document.html)

                val result = CrawlPageResult(
                    url = request.url,
                    title = title,
                    contentLength = page.contentLength,
                    depth = 0,
                    extracted = extractionResult.first,
                    extractionError = extractionResult.second
                )
                logger.info("Crawl {}: fetched seed URL {} ({} bytes)", taskId, request.url, page.contentLength)
                return listOf(result)
            } catch (e: Exception) {
                lastError = e
                val isProtocolError = e.message?.contains("Protocol not found", ignoreCase = true) == true
                    || e.javaClass.simpleName.contains("ProtocolNotFound")
                if (attempt < MAX_FETCH_RETRIES - 1 && isProtocolError) {
                    val retryDelay = FETCH_RETRY_DELAY_MS * (1L shl attempt)
                    logger.warn("Crawl {}: protocol error for '{}', retrying in {}ms (attempt {}/{})",
                        taskId, request.url, retryDelay, attempt + 1, MAX_FETCH_RETRIES)
                    if (ownsSession) runCatching { session.close() }
                    delay(retryDelay)
                    return@repeat
                }
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
                if (ownsSession) runCatching { session.close() }
            }
        }

        // All retries exhausted with 0-byte results
        logger.error("Crawl {}: all {} fetch attempts returned 0 bytes for '{}'", taskId, MAX_FETCH_RETRIES, request.url)
        return listOf(CrawlPageResult(
            url = request.url, title = null, contentLength = 0, depth = 0,
            extractionError = lastError?.message ?: "All fetch attempts returned 0 bytes"
        ))
    }

    // ------------------------------------------------------------------
    // Depth=1: extract out-links from the portal page and load each one
    // ------------------------------------------------------------------

    private suspend fun crawlDepth1(taskId: String, request: CrawlRequest): List<CrawlPageResult> {
        val session = AgenticContexts.createSession()
        val results = Collections.synchronizedList(mutableListOf<CrawlPageResult>())
        try {
            // Always add -refresh so the portal page is loaded with fresh content.
            // Without this, cached empty/malformed pages cause link discovery to
            // return 0 elements even when the page has many anchors in the live DOM.
            val effectiveArgs = buildEffectiveArgs(request.args)
            val options = parseOptions(session, effectiveArgs)
            if (options.outLinkSelector.isNullOrBlank()) {
                // If X-SQL extraction was requested but no out-link selector is configured,
                // auto-switch to depth=0 behavior (bulk fetch + extraction).  This prevents
                // the silent "0 pages returned" UX trap where users specify --sql without
                // --depth 0 (which defaults to depth=1 link-discovery mode).
                if (request.sql != null) {
                    logger.info(
                        "Crawl {}: X-SQL extraction requested without --out-link-selector; " +
                        "auto-switching to depth=0 (bulk fetch + extraction mode)",
                        taskId
                    )
                    runCatching { session.close() }
                    return crawlDepth0(taskId, request)
                }
                logger.warn("Crawl {}: no outLinkSelector provided, returning empty result", taskId)
                return emptyList()
            }
            val outLinks = extractOutLinks(session, request.url, options)

            if (outLinks.isEmpty()) {
                // Build a diagnostic that checks document health before blaming the selector.
                // extractOutLinks already logged document.select("a").size and html.length;
                // produce a user-facing diagnostic that distinguishes "page was empty" from
                // "page had content but selector didn't match".
                val diagnostic = try {
                    val normOptions = session.normalize(options)
                    val document = session.loadDocument(request.url, normOptions)
                    val allAnchors = document.select("a").size
                    val htmlLength = document.html.length
                    val totalElements = document.select("*").size
                    when {
                        htmlLength < 200 && allAnchors == 0 ->
                            "Portal page returned near-empty content (${htmlLength} bytes, " +
                            "$totalElements total elements, 0 anchors). " +
                            "The page may not have loaded correctly. Try --refresh, " +
                            "verify the URL is reachable, or check network connectivity."
                        allAnchors > 0 ->
                            "The page has $allAnchors anchors and ${htmlLength}B of HTML, " +
                            "but the CSS selector '${options.outLinkSelector}' matched zero " +
                            "elements. Try a broader selector (e.g., 'a') or use " +
                            "'htmlsnapshot inspect' to discover valid selectors."
                        else ->
                            "No out-links found. The page has 0 anchors and ${htmlLength}B " +
                            "of HTML (${totalElements} elements). The page may have loaded " +
                            "but contains no links — verify the URL."
                    }
                } catch (e: Exception) {
                    "Failed to load portal page: ${e.message}. " +
                    "Verify the URL is accessible and retry."
                }
                logger.info("Crawl {}: {}", taskId, diagnostic)
                taskStore.put(taskId, CrawlResponse(
                    taskId = taskId,
                    status = ResourceStatus.getStatusText(ResourceStatus.SC_OK),
                    pagesFound = 0,
                    diagnostic = diagnostic
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

            // Submit each out-link as a ParsableHyperlink so we can collect results.
            // Include -refresh so each out-link is fetched fresh — without it, internal
            // HTTP caches or stale protocol state can cause 0-byte responses.
            outLinks.forEach { linkUrl ->
                val hyperlink = ParsableHyperlink("$linkUrl -parse -refresh") { _page: WebPage, _document: FeaturedDocument ->
                    val extractionResult = if (request.sql != null) {
                        executeSqlQuery(session, linkUrl, request.sql)
                    } else Pair(null, null)
                    results.add(
                        CrawlPageResult(
                            url = linkUrl,
                            title = _document.title.takeIf { !it.isNullOrBlank() }
                                ?: extractTitleFromHtml(_document.html),
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
            val effectiveArgs = buildEffectiveArgs(request.args)
            val options = parseOptions(session, effectiveArgs)
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
                        title = document.title.takeIf { !it.isNullOrBlank() }
                            ?: extractTitleFromHtml(document.html),
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

    /**
     * Ensure -refresh is present in the args string so portal/link pages are
     * always loaded with fresh content.  Stale internal HTTP caches are the
     * root cause of both "0 elements for any CSS selector" (Issue 1) and
     * "0 byte fetch" (Issue 2).
     */
    private fun buildEffectiveArgs(rawArgs: String): String {
        return when {
            rawArgs.isBlank() -> "-refresh"
            rawArgs.contains("-refresh") -> rawArgs
            else -> "$rawArgs -refresh"
        }
    }

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
            // Pre-load the page into the session's WebDB so load_and_select
            // UDFs find the page in the local cache.  Without this the X-SQL
            // engine may silently return 0 rows even when the page was loaded
            // earlier in a different session lifecycle stage.
            val preloadSucceeded = try {
                runBlocking {
                    withTimeout(10_000L) {
                        session.load(pageUrl, "-refresh")
                    }
                }
                logger.debug("Crawl X-SQL: pre-loaded '{}' into session cache", pageUrl)
                true
            } catch (preloadError: Exception) {
                // Pre-load failure means the WebDB cache may be empty for this
                // page, and DOM_LOAD_AND_SELECT / DOM UDFs will not find the
                // page content.  This is a warning (not debug) because it
                // directly causes empty extraction results for the user.
                logger.warn(
                    "Crawl X-SQL: pre-load of '{}' failed ({}). " +
                    "X-SQL extraction may return 0 rows or empty fields. " +
                    "This can happen when the session's WebDB cache was cleared " +
                    "between page load and query execution.",
                    pageUrl, preloadError.message
                )
                false
            }

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

            // Read column names from ResultSetMetaData BEFORE converting to
            // text entities.  JDBC metadata preserves the SQL SELECT column
            // order, which is the deterministic source of truth for column
            // ordering in CSV/JSON/table output.  Without this, column order
            // depends on Map iteration order which varies across implementations.
            val metaData = copied.metaData
            // Use lowercase column labels so they match the keys produced by
            // ResultSetUtils.getTextEntitiesFromResultSet (which lowercases
            // via ResultSetMetaData.getColumnName + toLowerCase).  Without
            // this the reorder pass produces duplicate columns: the original-
            // case lookup returns null, and the "append extras" loop re-adds
            // the lowercased key — giving each column twice.
            val sqlColumnOrder = (1..metaData.columnCount).map { metaData.getColumnLabel(it).lowercase() }

            val rawRows = ResultSetUtils.getTextEntitiesFromResultSet(copied)
            // Reorder each row to match the SQL SELECT column order so that
            // CSV headers, JSON keys, and table columns are deterministic.
            val rows = if (sqlColumnOrder.isNotEmpty()) {
                rawRows.map { row ->
                    val ordered = linkedMapOf<String, Any?>()
                    for (col in sqlColumnOrder) {
                        ordered[col] = row[col]
                    }
                    // Append any columns not in metadata (computed/dynamic names)
                    for ((key, value) in row) {
                        if (key !in sqlColumnOrder) {
                            ordered[key] = value
                        }
                    }
                    ordered
                }
            } else {
                rawRows
            }
            if (rows.isEmpty()) {
                logger.info("Crawl X-SQL: query returned 0 rows for '{}'", pageUrl)
            } else {
                logger.info("Crawl X-SQL: extracted {} row(s) from '{}'", rows.size, pageUrl)

                // Detect silent failures: rows exist but ALL extracted data
                // fields (excluding URL/URI columns) are empty.  This almost
                // always means the WebDB cache was empty when the UDFs ran,
                // which happens when the pre-load above fails or the cache
                // layer used by session.load() differs from the one the UDFs
                // read.  Surface this as a warning so the user knows something
                // is wrong.
                val nonUrlColumnsHaveContent = rows.any { row ->
                    row.any { (key, value) ->
                        !key.contains("url", ignoreCase = true)
                            && !key.contains("uri", ignoreCase = true)
                            && !key.contains("base", ignoreCase = true)
                            && value != null && value.toString().isNotBlank()
                    }
                }
                if (!nonUrlColumnsHaveContent && !preloadSucceeded) {
                    val msg = "All extracted fields are empty — the WebDB cache may have been " +
                        "empty when the X-SQL UDFs ran. This is likely a cache-coherence issue " +
                        "between session.load() and the DOM UDF layer."
                    logger.warn("Crawl X-SQL: {} for '{}'", msg, pageUrl)
                    return Pair(rows, msg)
                }
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
        /** Maximum fetch retries when content is 0 bytes or protocol error occurs. */
        private const val MAX_FETCH_RETRIES = 3

        /** Base delay in ms between fetch retries; doubles each attempt (exponential backoff). */
        private const val FETCH_RETRY_DELAY_MS = 1000L

        /** Delay in ms between seed URL processing to allow session cleanup. */
        private const val SEED_INTERVAL_MS = 500L

        /** Maximum time (ms) a crawl task may run before being cancelled. */
        private const val CRAWL_TASK_TIMEOUT_MS = 600_000L // 10 minutes

        fun crawlPersistencePath(): Path = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "data", "crawl", "crawl-tasks.jsonl"
        )
    }

    /**
     * Extract the <title> text from raw HTML when [FeaturedDocument.title]
     * returns blank.  Handles the case where the parse pipeline skips title
     * extraction on cached content.  Returns null when no <title> tag is found.
     */
    private fun extractTitleFromHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val match = Regex("""<title[^>]*>\s*(.*?)\s*</title>""", RegexOption.IGNORE_CASE)
            .find(html)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
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
