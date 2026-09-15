package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.skeleton.workflow.common.url.ParsableHyperlink
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where a round reports the progress it made, without knowing that a task store
 * exists.  [CrawlService] backs it with the in-memory task records, so the
 * execution of a round stays independent of the bookkeeping around it.
 */
internal interface CrawlProgressSink {

    /**
     * Publish the pages collected so far by one seed round, so a poller sees
     * real page counts while the crawl is still running.
     *
     * [diagnostic] is set when the round has something to explain (e.g. the
     * seed page had no followable out-links) and must be preserved otherwise.
     */
    fun publishPages(taskId: String, pages: List<CrawlPageResult>, linksDiscovered: Int, diagnostic: String? = null)

    /** Record a diagnostic-only outcome, for a round that produced no pages at all. */
    fun publishDiagnostic(taskId: String, diagnostic: String)
}

/**
 * Executes one *round* of a crawl: everything that happens for a single seed
 * URL from the moment it is picked up until every page it submitted has been
 * settled.
 *
 * Three strategies live here, and the depth of the request picks one:
 *
 *  * [crawlDepth0] — bulk fetch: load the seed, optionally extract with X-SQL,
 *    no link discovery;
 *  * [crawlDepth1] — load the seed as a portal page, discover its out-links and
 *    fetch each of them;
 *  * [crawlDepthN] — breadth-first crawl, where every parsed page discovers and
 *    submits its own children.
 *
 * The runner owns no crawl state: the task record, the seed budget and the
 * incremental publishing all belong to [CrawlService], which hands over a
 * [CrawlProgressSink] instead.  That is what makes a round's lifetime exactly
 * one call and lets several seeds run their rounds concurrently.
 *
 * Every round reports what it lost, not only what it collected: a submitted URL
 * that never produced a page is settled through [CrawlLedger] and comes back in
 * [CrawlRound.failedPages].
 */
internal class CrawlRoundRunner(
    private val sessionManager: PulsarSessionManager,
    private val progress: CrawlProgressSink,
) {
    private val logger = LoggerFactory.getLogger(CrawlRoundRunner::class.java)

    /**
     * Fetch a single seed URL at depth=0 and optionally run X-SQL extraction.
     *
     * @param sharedSession When provided, this session is reused instead of
     *   creating a new one, and it is NOT closed when the method returns.
     *   This eliminates protocol-handler deregistration races when processing
     *   multiple depth=0 seeds in sequence.
     */
    internal suspend fun crawlDepth0(
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
            val session: PulsarSession = sharedSession ?: sessionManager.agenticContext
                .createSession(PulsarSettings(label = crawlSessionLabel(taskId)))

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
                        if (ownsSession) releaseSession(taskId, session)
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
                    executeCrawlSqlQuery(session, request.url, request.sql)
                } else Pair(null, null)

                // Fallback: if document.title is blank, try extracting <title>
                // from the raw HTML. The parse pipeline may skip title extraction
                // on cached/stale content, leaving document.title null/empty even
                // though the raw HTML has a valid <title> tag.
                val title = document.title.takeIf { !it.isNullOrBlank() }
                    ?: extractTitleFromHtml(document.html)

                val (servedFromStore, storeAgeSeconds) = storeServeMarkers(page)
                val result = CrawlPageResult(
                    url = request.url,
                    title = title,
                    contentLength = page.contentLength,
                    depth = 0,
                    extracted = extractionResult.first,
                    extractionError = extractionResult.second,
                    servedFromStore = servedFromStore,
                    storeAgeSeconds = storeAgeSeconds
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
                    if (ownsSession) releaseSession(taskId, session)
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
                if (ownsSession) releaseSession(taskId, session)
            }
        }

        // All retries exhausted with 0-byte results
        logger.error("Crawl {}: all {} fetch attempts returned 0 bytes for '{}'", taskId, MAX_FETCH_RETRIES, request.url)
        return listOf(CrawlPageResult(
            url = request.url, title = null, contentLength = 0, depth = 0,
            extractionError = lastError?.message ?: "All fetch attempts returned 0 bytes"
        ))
    }

    /**
     * Depth=1 round: extract the out-links of the portal page and load each one.
     *
     * The portal page itself is not part of the result — this strategy answers
     * "what does this listing page link to".
     *
     * @param roundTimeoutMs how long this round may wait for its URLs to settle
     *   before reporting what it has, derived from the task budget by
     *   [resolveRoundTimeoutMs].  It is a parameter rather than a constant on
     *   purpose: a fixed budget that outlives the task-level limit only ever gets
     *   the round killed, and a killed round cannot report the URLs it knows are
     *   missing.
     */
    internal suspend fun crawlDepth1(
        taskId: String,
        request: CrawlRequest,
        linksDiscovered: AtomicInteger,
        roundTimeoutMs: Long
    ): CrawlRound {
        logger.info(
            "Crawl {}: depth=1 round budget {}ms (drawn from what the task has left)",
            taskId, roundTimeoutMs
        )
        // Labelled so the session is identifiable in logs and context dumps; the
        // round owns its lifetime and releases it in the finally block below.
        val session = sessionManager.agenticContext.createSession(PulsarSettings(label = crawlSessionLabel(taskId)))
        val results = Collections.synchronizedList(mutableListOf<CrawlPageResult>())
        // Per-crawl settle bookkeeping: a round is complete only when every
        // submitted URL produced a page or a reported failure, and no parse
        // handler is still running.  A bare `pendingCount == 0` comparison
        // cannot see a fetch that failed (issue #592), and lets a handler
        // that is still discovering links be overtaken by the others.
        val ledger = CrawlLedger(taskId)
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
                    releaseSession(taskId, session)
                    return CrawlRound(pages = crawlDepth0(taskId, request))
                }
                logger.warn("Crawl {}: no outLinkSelector provided, returning empty result", taskId)
                return CrawlRound(pages = emptyList(), pagesExpected = 0)
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
                    emptyOutLinksDiagnostic(document, options.outLinkSelector, options.outLinkPattern)
                } catch (e: Exception) {
                    "Failed to load portal page: ${e.message}. " +
                    "Verify the URL is accessible and retry."
                }
                logger.info("Crawl {}: {}", taskId, diagnostic)
                progress.publishDiagnostic(taskId, diagnostic)
                return CrawlRound(pages = emptyList(), pagesExpected = 0)
            }

            logger.info("Crawl {}: found {} out-links, submitting...", taskId, outLinks.size)
            linksDiscovered.addAndGet(outLinks.size)

            // Per-crawl settle bookkeeping: a round is complete only when every
            // submitted URL produced a page or a reported failure, and no parse
            // handler is still running.  A bare `pendingCount == 0` comparison
            // cannot see a fetch that failed (issue #592), and lets a handler
            // that is still discovering links be overtaken by the others.
            // onHTMLDocumentParsed can fire more than once for a single page
            // (re-parse after refresh, shared-session reloads).  Only the first
            // event may add the result or settle the URL — otherwise one page
            // can appear twice in the listing and the crawl can complete
            // before every page has been collected.
            val recorded = ConcurrentHashMap.newKeySet<String>()

            // Submit each out-link as a ParsableHyperlink so we can collect results.
            // Include -refresh so each out-link is fetched fresh — without it, internal
            // HTTP caches or stale protocol state can cause 0-byte responses.
            outLinks.forEach { linkUrl ->
                // -readonly is forwarded so depth-1 page loads honor it too (no
                // store writes) instead of silently ignoring the flag.
                val readonlySuffix = if (options.readonly) " -readonly" else ""
                val onParse = parse@{ _page: WebPage, _document: FeaturedDocument ->
                    if (!ledger.enter()) {
                        logger.debug(
                            "Crawl {}: ignoring a late parse event for '{}' — the round is already complete",
                            taskId, linkUrl
                        )
                        return@parse null
                    }
                    try {
                        // Only the first parse event for a URL records the result
                        // and settles the URL; duplicates are dropped.
                        if (recorded.add(normalizeForVisit(linkUrl))) {
                            val extractionResult = if (request.sql != null) {
                                executeCrawlSqlQuery(session, linkUrl, request.sql)
                            } else Pair(null, null)
                            val (servedFromStore, storeAgeSeconds) = storeServeMarkers(_page)
                            synchronized(results) {
                                results.add(
                                    CrawlPageResult(
                                        url = linkUrl,
                                        title = _document.title.takeIf { !it.isNullOrBlank() }
                                            ?: extractTitleFromHtml(_document.html),
                                        contentLength = _page.contentLength,
                                        depth = 1,
                                        extracted = extractionResult.first,
                                        extractionError = extractionResult.second,
                                        servedFromStore = servedFromStore,
                                        storeAgeSeconds = storeAgeSeconds
                                    )
                                )
                                // Publish in-memory progress so the CLI poll loop sees
                                // pages as they arrive instead of 'waiting for first
                                // page' for the whole seed round.
                                progress.publishPages(taskId, results.toList(), linksDiscovered.get())
                            }
                            ledger.recordSuccess(linkUrl)
                        } else {
                            logger.debug("Crawl {}: duplicate parse event for {}; already recorded", taskId, linkUrl)
                        }
                    } catch (t: Throwable) {
                        // A page whose parse handler died is a lost page, not a
                        // page that is still coming: report it, then keep the
                        // original propagation semantics.
                        ledger.recordFailure(
                            linkUrl, 1, _page.protocolStatus.minorCode,
                            "the parse handler failed for this page: ${t.message}"
                        )
                        throw t
                    } finally {
                        ledger.leave()
                    }
                    null
                }
                val hyperlink = ParsableHyperlink("$linkUrl -parse -refresh$readonlySuffix", onParse)
                // A fetch that never fires a parse event (retry budget exhausted,
                // dropped task, terminal 4xx/5xx) is settled here instead of
                // vanishing from the result.
                hyperlink.eventHandlers.crawlEventHandlers.onLoaded.addLast { _, loaded ->
                    settleFromLoaded(ledger, linkUrl, 1, loaded)
                    null
                }
                ledger.submit(linkUrl, 1)
                session.submit(hyperlink)
            }

            // Wait until every submitted out-page settled (per-crawl, not global).
            // The budget is the caller's, derived from what the task has left, so
            // this round hits its timeout while it can still report the URLs that
            // never came back instead of being killed mid-flight by the task limit.
            withTimeout(roundTimeoutMs.milliseconds) {
                ledger.awaitAllSettled()
            }

            // Deterministic ordering: identical runs over an unchanged site
            // produce identical listings.
            return CrawlRound(
                pages = snapshotResults(results).sortedBy { it.url },
                failedPages = ledger.failedPages(),
                pagesExpected = ledger.pagesExpected
            )
        } catch (e: TimeoutCancellationException) {
            // The round ran out of time.  Report it as a timed-out round with its
            // outstanding URLs instead of re-throwing: the throw used to be
            // swallowed by the per-seed catch, which then let the terminal write
            // replace the TIMEOUT record with OK — a truncated crawl that looked
            // successful.
            val partial = snapshotResults(results)
            logger.warn("Crawl {}: depth=1 timed out after collecting {} pages; saving partial results", taskId, partial.size)
            return CrawlRound(
                pages = partial.sortedBy { it.url },
                failedPages = ledger.failedPages() + ledger.outstanding(),
                pagesExpected = ledger.pagesExpected,
                timedOut = true,
                timeoutError = "Crawl timed out after collecting ${partial.size} pages (partial results saved)"
            )
        } finally {
            ledger.close()
            releaseSession(taskId, session)
        }
    }

    /**
     * Depth>1 round: breadth-first continuous crawl using ParsableHyperlink
     * parse handlers, where each parsed page discovers and submits its children.
     *
     * @param roundTimeoutMs how long this round may wait for its URLs to settle
     *   before reporting what it has, derived from the task budget by
     *   [resolveRoundTimeoutMs] (see [crawlDepth1] for why it is a parameter).
     */
    internal suspend fun crawlDepthN(
        taskId: String,
        request: CrawlRequest,
        linksDiscovered: AtomicInteger,
        roundTimeoutMs: Long
    ): CrawlRound {
        logger.info(
            "Crawl {}: depth={} round budget {}ms (drawn from what the task has left)",
            taskId, request.depth, roundTimeoutMs
        )
        // Labelled so the session is identifiable in logs and context dumps; the
        // round owns its lifetime and releases it (close + deregister) in the
        // finally block below, since this session never goes through
        // PulsarSessionManager and nothing else tracks it.
        val settings = PulsarSettings(
            profileMode = BrowserProfileMode.SEQUENTIAL,
            label = crawlSessionLabel(taskId)
        )
        val session = sessionManager.agenticContext.createSession(settings = settings)
        val results = Collections.synchronizedList(mutableListOf<CrawlPageResult>())
        // Per-round settle bookkeeping.  The round can only complete when every
        // URL it submitted produced a page or a reported failure *and* no parse
        // handler is still running: the old `completed == submitted` counter let
        // handler A's children be submitted after the counters of the other
        // handlers had already caught up, and could not see a fetch that failed
        // at all (issue #592: "expected 10 pages, got 8", status=OK).
        val ledger = CrawlLedger(taskId)
        try {
            val effectiveArgs = buildEffectiveArgs(request.args)
            val options = parseOptions(session, effectiveArgs)
            val maxDepth = request.depth
            val visited = ConcurrentHashMap.newKeySet<String>()

            // Per-URL crawl bookkeeping.  Both maps are keyed by the URL this
            // crawl *submitted*, which is the only identity it controls:
            //  - recorded: submitted URLs whose parse event already produced a
            //    result entry.  onHTMLDocumentParsed can fire more than once per
            //    page (re-parse after refresh, shared-session reloads); only the
            //    first event may add a result or settle the URL — otherwise a
            //    single page appears twice in the listing and the crawl can
            //    complete early (nondeterministic totals across runs).
            //  - depths: discovery depth captured at submission time, before the
            //    URL is handed to the session.  This is the source of truth for a
            //    page's depth: the '-depth N' marker this used to embed in the URL
            //    args never reaches page.configuredUrl (it is not a LoadOptions
            //    option, so LoadOptions.toString() drops it), and the URL a
            //    document is served under can differ from the URL it was submitted
            //    under (redirect, '<base href>').
            val recorded = ConcurrentHashMap.newKeySet<String>()
            val depths = ConcurrentHashMap<String, Int>()
            // Serializes the visited-check + visited-mark + submit sequence so two
            // pages that discover the same child cannot both pass the check and
            // submit it twice.
            val discoveryLock = Any()

            // Use lateinit to allow recursive reference within the parse handler
            lateinit var parseHandler: (WebPage, FeaturedDocument) -> Any?

            parseHandler = crawlParse@{ page: WebPage, document: FeaturedDocument ->
                // The submission is the identity this crawl owns: `page.url` is the
                // URL the hyperlink was handed to the session under, it is the key
                // `recorded` dedupes on, and it is what the result row reports — so
                // a row is always a URL this crawl queued, and one URL never yields
                // two rows.
                //
                // `baseURI` is where the document was served from and may differ
                // (a redirect, and equally a `<base href>`, which jsoup copies into
                // the document base URI — on such a site every page's base URI can
                // be the same root).  It is therefore only a *fallback* key for the
                // depth lookup and the diagnosis text, never the row identity.
                val servedUrl = document.baseURI?.takeIf { it.isNotBlank() } ?: page.url
                val key = normalizeForVisit(page.url)
                // Refuse everything once the round is terminal: a duplicate parse
                // event arriving minutes later used to keep submitting links for a
                // task the caller had already been told was finished (issue #592).
                if (!ledger.enter()) {
                    logger.debug(
                        "Crawl {}: ignoring a late parse event for '{}' — the round is already complete",
                        taskId, servedUrl
                    )
                    return@crawlParse null
                }
                try {
                // Depth resolution is queue-time bookkeeping, never a guess: every
                // URL this crawl submits is registered in `depths` before it is
                // submitted (seed = 0, each link = discovering page's depth + 1).
                // A miss means URL drift, not a foreign page — this handler only
                // fires for hyperlinks this round submitted — so the document is
                // recorded with an unknown depth rather than dropped: losing a page
                // the round did fetch is the one outcome the ledger exists to
                // prevent.  It is not expanded either, because a depth that cannot
                // be bounded is exactly how a crawl runs away.
                val currentDepth = resolveQueueDepth(page.url, servedUrl, depths)

                // First parse event for this URL owns the result entry and the
                // completion tick.  Later events (re-parses of the same page) only
                // re-run discovery, which finds every link already visited and
                // therefore does nothing.
                val firstEvent = recorded.add(key)

                if (firstEvent) {
                    if (currentDepth == null) {
                        logger.warn(
                            "Crawl {}: page served at '{}' (submitted as '{}', configuredUrl='{}') has no " +
                            "queue-time depth record; recording it with depth {} and following no links from it",
                            taskId, servedUrl, page.url, page.configuredUrl, UNKNOWN_DEPTH
                        )
                    }
                    // Record this page
                    val extractionResult = if (request.sql != null) {
                        executeCrawlSqlQuery(session, page.url, request.sql)
                    } else Pair(null, null)
                    val (servedFromStore, storeAgeSeconds) = storeServeMarkers(page)
                    synchronized(results) {
                        results.add(
                            CrawlPageResult(
                                url = page.url,
                                title = document.title.takeIf { !it.isNullOrBlank() }
                                    ?: extractTitleFromHtml(document.html),
                                contentLength = page.contentLength,
                                depth = currentDepth ?: UNKNOWN_DEPTH,
                                extracted = extractionResult.first,
                                extractionError = extractionResult.second,
                                servedFromStore = servedFromStore,
                                storeAgeSeconds = storeAgeSeconds
                            )
                        )
                        // Publish in-memory progress so the CLI poll loop sees real
                        // page counts while the crawl is still running, instead of
                        // repeating 'waiting for first page' until the whole crawl
                        // finishes.
                        progress.publishPages(taskId, results.toList(), linksDiscovered.get())
                    }
                    ledger.recordSuccess(key)
                    logger.debug("Crawl {}: depth={} page={}", taskId, currentDepth, servedUrl)
                } else {
                    logger.debug(
                        "Crawl {}: duplicate parse event for {} (depth={}); already recorded",
                        taskId, servedUrl, currentDepth
                    )
                }

                // If we haven't reached max depth, extract and submit more links.
                // An unknown depth must not be read as depth 0: a page whose depth
                // could not be resolved is recorded but never expanded.
                if (currentDepth != null && currentDepth < maxDepth) {
                    val selector = options.outLinkSelector
                    if (selector.isNotBlank()) {
                        val allLinks = document.selectHyperlinks(selector)
                            .map { it.url }
                            .toList()
                        // Check-and-mark must be atomic with submission, so two
                        // pages discovering the same link cannot both submit it.
                        val (newLinks, dupes) = synchronized(discoveryLock) {
                            val fresh = allLinks.filter { link ->
                                normalizeForVisit(link) !in visited
                            }
                            val chosen = fresh
                                .filter { link -> matchesPattern(link, options.outLinkPattern) }
                                .take(options.topLinks)
                            chosen.forEach { link -> visited.add(normalizeForVisit(link)) }
                            chosen to (allLinks.size - fresh.size)
                        }
                        if (dupes > 0) {
                            logger.debug(
                                "Crawl {}: {} link(s) skipped — already visited (depth={})",
                                taskId, dupes, currentDepth
                            )
                        }

                        if (newLinks.isNotEmpty()) {
                            linksDiscovered.addAndGet(newLinks.size)
                            val childDepth = currentDepth + 1
                            val args = buildLinkArgs(options)
                            newLinks.forEach { link ->
                                depths[normalizeForVisit(link)] = childDepth
                                val hyperlink = ParsableHyperlink("$link $args", parseHandler)
                                // A fetch that never fires a parse event (retry
                                // budget exhausted, dropped task, terminal
                                // 4xx/5xx) is settled here instead of vanishing.
                                hyperlink.eventHandlers.crawlEventHandlers.onLoaded.addLast { _, loaded ->
                                    settleFromLoaded(ledger, link, childDepth, loaded)
                                    null
                                }
                                // Register before submitting: a page that settles
                                // faster than it is counted would end the round.
                                // `submit` refuses once the round is terminal and
                                // for a URL that is already queued — nothing may be
                                // handed to the session for a task the caller has
                                // already been told is finished (issue #592).
                                if (ledger.submit(link, childDepth)) {
                                    session.submit(hyperlink)
                                } else {
                                    logger.debug(
                                        "Crawl {}: not submitting '{}' — the round is complete or the URL is already queued",
                                        taskId, link
                                    )
                                }
                            }
                            logger.debug(
                                "Crawl {}: submitted {} links at depth {}",
                                taskId,
                                newLinks.size,
                                childDepth
                            )
                        } else if (currentDepth == 0 && ledger.pagesExpected == 1) {
                            // The seed page's round produced no followable
                            // out-links (nothing but the seed has been submitted),
                            // so this crawl will only report the seed page.
                            // Explain why instead of letting it pass as a silent
                            // '1 pages found' success (mirrors crawlDepth1).
                            val diagnostic = runCatching {
                                emptyOutLinksDiagnostic(
                                    document, options.outLinkSelector, options.outLinkPattern
                                )
                            }.getOrNull()
                            if (diagnostic != null) {
                                logger.info("Crawl {}: {}", taskId, diagnostic)
                                progress.publishPages(
                                    taskId,
                                    synchronized(results) { results.toList() },
                                    linksDiscovered.get(),
                                    diagnostic
                                )
                            }
                        }
                    }
                }

                } catch (t: Throwable) {
                    // A page whose parse handler died is a lost page, not a page
                    // that is still coming — but only when it never produced a row.
                    // This event, or an earlier duplicate one, may already have
                    // settled this submission as a success, and settling one
                    // submission twice makes CrawlLedger absorb the over-count by
                    // completing the round while other handlers are still fetching.
                    if (!ledger.isRecordedSuccess(key)) {
                        ledger.recordFailure(
                            key, depths[key] ?: UNKNOWN_DEPTH, page.protocolStatus.minorCode,
                            "the parse handler failed for this page: ${t.message}"
                        )
                    }
                    throw t
                } finally {
                    // The round may only complete once no handler is in flight:
                    // this is what stops handler A's children from being lost
                    // after the other handlers have already settled everything.
                    ledger.leave()
                }
            } // parseHandler defined

            // Submit the seed URL (depth 0 — it is the starting page).
            val seedKey = normalizeForVisit(request.url)
            visited.add(seedKey)
            depths[seedKey] = 0
            val seedArgs = buildLinkArgs(options)
            val seedHyperlink = ParsableHyperlink("${request.url} $seedArgs", parseHandler)
            seedHyperlink.eventHandlers.crawlEventHandlers.onLoaded.addLast { _, loaded ->
                settleFromLoaded(ledger, request.url, 0, loaded)
                null
            }
            // A refused seed submission must not be followed by a wait for a page
            // that was never queued.  (Nothing can make the ledger terminal this
            // early, so this is a guard, not a path.)
            if (!ledger.submit(request.url, 0)) {
                logger.warn("Crawl {}: the seed URL '{}' was not queued; ending the round", taskId, request.url)
                return CrawlRound(pages = emptyList(), pagesExpected = 0)
            }
            session.submit(seedHyperlink)

            // Wait until every submitted URL settled (per-crawl completion, not
            // global).  The budget is the task's remaining budget, never
            // `depth * 5 min`: for depth >= 3 that product exceeds the whole task
            // limit, so the round could only ever be killed by it — and a killed
            // round reports nothing, which is how a timed-out deep crawl used to
            // come back with fewer pages and no losses.
            withTimeout(roundTimeoutMs.milliseconds) {
                ledger.awaitAllSettled()
            }

            // Deterministic ordering: identical runs over an unchanged site
            // produce identical listings.
            return CrawlRound(
                pages = snapshotResults(results).sortedWith(compareBy({ it.depth }, { it.url })),
                failedPages = ledger.failedPages(),
                pagesExpected = ledger.pagesExpected
            )
        } catch (e: TimeoutCancellationException) {
            // The round ran out of time.  Report it as a timed-out round with its
            // outstanding URLs instead of re-throwing: the throw used to be
            // swallowed by the per-seed catch, which then let the terminal write
            // replace the TIMEOUT record with OK — a truncated crawl that looked
            // successful.
            val partial = snapshotResults(results)
            logger.warn("Crawl {}: depth>1 timed out after collecting {} pages; saving partial results", taskId, partial.size)
            return CrawlRound(
                pages = partial.sortedWith(compareBy({ it.depth }, { it.url })),
                failedPages = ledger.failedPages() + ledger.outstanding(),
                pagesExpected = ledger.pagesExpected,
                timedOut = true,
                timeoutError = "Crawl timed out after collecting ${partial.size} pages (partial results saved)"
            )
        } finally {
            ledger.close()
            releaseSession(taskId, session)
        }
    }

    /**
     * Close a round-owned session **and** deregister it from its context.
     *
     * Closing alone would leave the closed session in
     * `AbstractPulsarContext.sessions` for the lifetime of the process (one dead
     * entry per round; only `closeSession` removes it), and
     * `getOrCreateSession()` returns `sessions.values.firstOrNull()` — so a
     * finished round's dead session could be handed to a caller that asked for a
     * live one.
     *
     * A failure here is a real leak — the close is what unbinds the browser and
     * driver — so it is reported instead of swallowed, and it is worth reporting
     * precisely because nothing else tracks this session.
     */
    private fun releaseSession(taskId: String, session: PulsarSession) {
        releaseCrawlSession(session, sessionManager.agenticContext)?.let { failure ->
            logger.warn(
                "Crawl {}: session {} could not be closed and deregistered; " +
                "the browser resources it holds are no longer tracked by anything",
                taskId, session.id, failure
            )
        }
    }

    /**
     * A consistent snapshot of the pages recorded so far.
     *
     * `Collections.synchronizedList` requires holding the list's own monitor to
     * iterate it, and the timeout path reads the list while parse handlers may
     * still be appending to it.
     */
    private fun snapshotResults(results: MutableList<CrawlPageResult>): List<CrawlPageResult> =
        synchronized(results) { results.toList() }

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
            // Fragment-only anchors (href='#', '#section') can never navigate to
            // a new document — resolving them yields the portal page itself with
            // a fragment, which then masquerades as a discovered out-link (it can
            // even match --out-link-pattern after resolution and produce a hollow
            // 'discovered but nothing new' crawl).  Skip them before resolution.
            if (href.trimStart().startsWith("#")) {
                return@mapNotNull null
            }
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

    private fun parseOptions(session: PulsarSession, args: String): LoadOptions {
        return if (args.isBlank()) {
            session.options()
        } else {
            session.options(args)
        }
    }

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

    /**
     * Settle a submitted URL from the crawl `onLoaded` event, which fires for
     * every load attempt — including the ones that no parse event ever follows.
     *
     * Without this, a fetch that fails is invisible to the crawl: the parse
     * event never fires, so the completion wait can never learn that the page
     * is not coming, and the URL simply disappears from the result (issue
     * #592).  Classification mirrors `XSQLHyperlink.CrawlEventHandlers`, which
     * is the established reading of these states in this codebase:
     *
     *  * a retry/canceled status means the page is still in flight — settle
     *    nothing, so the round keeps waiting for the attempt that finally lands;
     *  * `!isFetched` alone is NOT a failure: a page served from the page store
     *    (`-readonly` without `-refresh`) legitimately completes with content
     *    while `isFetched` stays false.
     */
    private fun settleFromLoaded(ledger: CrawlLedger, submittedUrl: String, depth: Int, page: WebPage?) {
        if (ledger.isTerminal) return
        if (page == null) {
            ledger.recordFailure(submittedUrl, depth, 0, CrawlLedger.REASON_NEVER_FETCHED)
            return
        }
        val status = page.protocolStatus
        when {
            page.isCanceled || status.isRetry -> Unit

            page.isNil -> ledger.recordFailure(
                submittedUrl, depth, status.minorCode, CrawlLedger.REASON_NEVER_FETCHED
            )

            status.isFailed -> ledger.recordFailure(
                submittedUrl, depth, status.minorCode,
                status.reason?.toString() ?: CrawlLedger.REASON_FETCH_FAILED
            )

            !page.isFetched && !status.isSuccess -> ledger.recordFailure(
                submittedUrl, depth, status.minorCode, CrawlLedger.REASON_NEVER_FETCHED
            )

            // A successful load is settled by the parse event that records its
            // row, and `onLoaded` fires *after* that event.  A success no row was
            // recorded for will never produce one, so report it instead of
            // letting the round wait out its whole timeout.
            !ledger.isRecordedSuccess(submittedUrl) && !ledger.isRecordedSuccess(page.url) ->
                ledger.recordFailure(submittedUrl, depth, status.minorCode, CrawlLedger.REASON_NOT_PARSED)

            else -> Unit
        }
    }

    /**
     * The load args of a submitted hyperlink.
     *
     * A child's discovery depth is deliberately NOT embedded here.  It used to
     * be (`-depth N`) so that it could be re-read out of `page.configuredUrl`,
     * but [LoadOptions] has no such option: `LoadOptions.toString()` — which is
     * what builds `configuredUrl` — only serializes options it knows, so the
     * marker was dropped on submission and every read of it failed.  Depth is
     * queue-time bookkeeping owned by `crawlDepthN`'s `depths` map and is never
     * re-derived from a URL.
     */
    private fun buildLinkArgs(options: LoadOptions): String {
        val parts = mutableListOf("-parse")
        if (options.outLinkSelector.isNotBlank()) {
            parts.add("-outLink \"${options.outLinkSelector}\"")
        }
        if (options.outLinkPattern.isNotBlank() && options.outLinkPattern != ".+") {
            parts.add("-outLinkPattern \"${options.outLinkPattern}\"")
        }
        // -readonly must reach every page load, not just the seed: without this
        // the flag silently stops applying at depth>=2 and the crawl writes
        // pages to the store while claiming nothing was written.
        if (options.refresh) parts.add("-refresh")
        if (options.readonly) parts.add("-readonly")
        return parts.joinToString(" ")
    }

    private companion object {
        /** Maximum fetch retries when content is 0 bytes or protocol error occurs. */
        const val MAX_FETCH_RETRIES = 3

        /** Base delay in ms between fetch retries; doubles each attempt (exponential backoff). */
        const val FETCH_RETRY_DELAY_MS = 1000L

        /**
         * Depth reported for a page whose queue-time depth could not be resolved:
         * both the URL it was submitted under and the URL it was served under
         * differ from every URL this round queued.
         *
         * `-1` is the project-wide "unknown depth" marker (see
         * [CrawlFailedPage.depth]).  The page is still recorded: an unresolvable
         * label is a reporting gap, and must never cost the page itself.
         */
        const val UNKNOWN_DEPTH = -1
    }
}
