package ai.platon.pulsar.skeleton.workflow.component

import ai.platon.pulsar.common.CheckState
import ai.platon.pulsar.common.PulsarParams.VAR_FETCH_STATE
import ai.platon.pulsar.common.PulsarParams.VAR_PREV_FETCH_TIME_BEFORE_UPDATE
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.AppConstants.VAR_CAPTURE
import ai.platon.pulsar.common.config.AppConstants.VAR_REFRESH
import ai.platon.pulsar.common.config.CapabilityTypes.LOAD_DEACTIVATE_FETCH_COMPONENT
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.measure.ByteUnitConverter
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.persist.*
import ai.platon.pulsar.persist.gora.generated.GWebPage
import ai.platon.pulsar.persist.model.ActiveDOMStat
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.common.AppStatusTracker
import ai.platon.pulsar.skeleton.common.message.PageLoadStatusFormatter
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.persist.ext.eventHandlers
import ai.platon.pulsar.skeleton.common.persist.ext.loadEventHandlers
import ai.platon.pulsar.skeleton.common.urls.NormURL
import ai.platon.pulsar.skeleton.event.PageEventHandlers
import ai.platon.pulsar.skeleton.event.PulsarEventBus
import ai.platon.pulsar.skeleton.workflow.common.FetchEntry
import ai.platon.pulsar.skeleton.workflow.common.FetchState
import ai.platon.pulsar.skeleton.workflow.common.GlobalCacheFactory
import ai.platon.pulsar.skeleton.workflow.common.url.CompletableHyperlink
import ai.platon.pulsar.skeleton.workflow.common.url.ListenableUrl
import ai.platon.pulsar.skeleton.workflow.common.url.toCompletableListenableHyperlink
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.workflow.parse.ParseResult
import org.slf4j.LoggerFactory
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Function

/**
 * Created by Vincent on 17-7-15.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 *
 * The load component is the core component of the Pulsar framework, it is responsible for loading pages from the
 * local storage or fetching them from the Internet.
 */
class LoadComponent(
    val webDb: WebDb,
    val globalCacheFactory: GlobalCacheFactory,
    val fetchComponent: BatchFetchComponent,
    val parseComponent: ParseComponent,
    val updateComponent: UpdateComponent,
    val immutableConfig: ImmutableConfig,
    val statusTracker: AppStatusTracker? = null,
) : AutoCloseable {
    companion object {
        val pageCacheHits = AtomicLong()
        val dbGetCount = AtomicLong()
    }

    private val logger = LoggerFactory.getLogger(LoadComponent::class.java)
    private val taskLogger = LoggerFactory.getLogger(LoadComponent::class.java.name + ".Task")
    private val tracer = logger.takeIf { it.isTraceEnabled }

    private val deactivateFetchComponent1 = immutableConfig.getBoolean(LOAD_DEACTIVATE_FETCH_COMPONENT, false)

    /**
     * Deactivate the fetch component, ensuring that all pages are loaded exclusively from storage
     * and never fetched from the Internet.
     *
     * If a page is not found in the local storage, return WebPageImpl.NIL.
     * */
    private val deactivateFetchComponent = deactivateFetchComponent1

    val globalCache get() = globalCacheFactory.globalCache
    val pageCache get() = globalCache.pageCache
    val documentCache get() = globalCache.documentCache

    private val coreMetrics get() = fetchComponent.coreMetrics
    private val closed = AtomicBoolean()

    private val isActive get() = !closed.get()

    @Volatile
    private var numWrite = 0
    private val abnormalPage get() = GoraWebPage.NIL.takeIf { !isActive }

    private var reportCount = AtomicInteger()
    private val batchTaskCount = AtomicInteger()

    /**
     * Retrieve the fetch state of a page, which determines whether the page should be fetched from the Internet.
     *
     * @param page The page to be fetched
     * @param options The load options
     * @return The fetch state
     * */
    fun fetchState(page: WebPage, options: LoadOptions): CheckState {
        val protocolStatus = page.protocolStatus

        return when {
            closed.get() -> CheckState(FetchState.DO_NOT_FETCH, "closed")
            page.isNil -> CheckState(FetchState.NEW_PAGE, "nil")
            page.isInternal -> CheckState(FetchState.DO_NOT_FETCH, "internal")
            protocolStatus.isNotFetched -> CheckState(FetchState.NEW_PAGE, "not fetched")
            protocolStatus.isTempMoved -> CheckState(FetchState.TEMP_MOVED, "temp moved")
            else -> getFetchStateForExistPage(page, options)
        }
    }

    suspend fun open(normURL: NormURL, driver: WebDriver): WebPage {
        val page = createPageShell(normURL)
        require(page is AbstractWebPage)
        require(page.hasVar(VAR_REFRESH))
        val state = page.getVar(VAR_REFRESH)
        require(state is CheckState)

        page.putBean(driver)
        loadNormalURLWithEventHandlersDeferred(normURL, page)
        return page
    }

    suspend fun capture(normURL: NormURL, driver: WebDriver): WebPage {
        val page = createPageShell(normURL)
        require(page is AbstractWebPage)

        page.setVar(VAR_CAPTURE, VAR_CAPTURE)
        page.putBean(driver)
        loadNormalURLWithEventHandlersDeferred(normURL, page)
        return page
    }

    /**
     * Load a page specified by [url] with the given arguments.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * Other fetch conditions can be specified by load arguments:
     *
     * 1. expiration
     * 2. page size requirement
     * 3. fields requirement
     * 4. other
     *
     * @param url The url of the page
     * @param options The load options
     * @return The page
     * */
    @Throws(Exception::class)
    fun load(url: URL, options: LoadOptions): WebPage {
        return abnormalPage ?: loadWithRetry(NormURL(url, options))
    }

    /**
     * Load a page specified by [normURL].
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * Other fetch conditions can be specified by load arguments:
     *
     * 1. expiration
     * 2. page size requirement
     * 3. fields requirement
     * 4. other
     *
     * @param normURL The normalized url of the page
     * @return The page
     * */
    @Throws(Exception::class)
    fun load(normURL: NormURL): WebPage {
        return abnormalPage ?: loadWithRetry(normURL)
    }

    /**
     * Load a page specified by [normURL].
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * This method is a coroutine version of [load].
     *
     * Other fetch conditions can be specified by load arguments:
     *
     * 1. expiration
     * 2. page size requirement
     * 3. fields requirement
     * 4. other
     *
     * @param normURL The normalized url of the page
     * @return The page
     * */
    @Throws(Exception::class)
    suspend fun loadDeferred(normURL: NormURL): WebPage {
        return abnormalPage ?: loadWithRetryDeferred(normURL)
    }

    @Throws(Exception::class)
    fun loadWithRetry(normURL: NormURL): WebPage {
        var page = loadWithEventHandlers(normURL)

        if (URLUtils.isInternal(normURL.urlString)) {
            normURL.options.nJitRetry = 1
        }

        var n = normURL.options.nJitRetry
        while (page.protocolStatus.isRetry && n-- > 0) {
            page = loadWithEventHandlers(normURL)
        }
        return page
    }

    @Throws(Exception::class)
    suspend fun loadWithRetryDeferred(normURL: NormURL): WebPage {
        var page = loadWithEventHandlersDeferred(normURL)

        if (URLUtils.isInternal(normURL.urlString)) {
            normURL.options.nJitRetry = 1
        }

        var n = normURL.options.nJitRetry
        while (page.protocolStatus.isRetry && n-- > 0) {
            page = loadWithEventHandlersDeferred(normURL)
        }
        return page
    }

    /**
     * Load all pages specified by [normUrls], wait until all pages are loaded or timeout
     * */
    fun loadAll(normUrls: Iterable<NormURL>): List<WebPage> {
        if (!normUrls.iterator().hasNext()) {
            return listOf()
        }

        val batchId = batchTaskCount.incrementAndGet()
        val futures = loadAllAsync(normUrls.filter { !it.isNil })

        logger.info("Waiting for {} completable links | #{}", futures.size, batchId)

        val future = CompletableFuture.allOf(*futures.toTypedArray())
        future.join()

        val pages = futures.mapNotNull { it.get().takeIf { it.isNotNil } }

        logger.info("Finished {}/{} pages | #{}", pages.size, futures.size, batchId)

        return pages
    }

    /**
     * Load a page specified by [normURL]
     *
     * @param normURL The normalized url
     * @return A completable future of webpage
     * */
    fun loadAsync(normURL: NormURL): CompletableFuture<WebPage> {
        val link = normURL.toCompletableListenableHyperlink()
        globalCache.urlPool.add(link)
        return link
    }

    /**
     * Load all pages specified by [normUrls], wait until all pages are loaded or timeout
     * */
    fun loadAllAsync(normUrls: Iterable<NormURL>): List<CompletableFuture<WebPage>> {
        if (!normUrls.iterator().hasNext()) {
            return listOf()
        }

        val linkFutures = normUrls.asSequence().filter { !it.isNil }.distinctBy { it.urlString }
            .map { it.toCompletableListenableHyperlink() }
            .toList()
        globalCache.urlPool.addAll(linkFutures)
        return linkFutures
    }

    /**
     * Load a webpage from local storage, or if it doesn't exist in local storage,
     * fetch it from the Internet, unless the fetch component is disabled.
     * */
    @Throws(Exception::class)
    private fun loadWithEventHandlers(normURL: NormURL): WebPage {
        val page = createPageShellOrNilWithEventHandlers(normURL)
        if (page.isInternal) {
            return page
        }

        return loadNormalURLWithEventHandlers(normURL, page)
    }

    @Throws(Exception::class)
    private suspend fun loadWithEventHandlersDeferred(normURL: NormURL): WebPage {
        val page = createPageShellOrNilWithEventHandlers(normURL)
        if (page.isInternal) {
            return page
        }

        return loadNormalURLWithEventHandlersDeferred(normURL, page)
    }

    @Throws(Exception::class)
    private fun loadNormalURLWithEventHandlers(normURL: NormURL, page: WebPage): WebPage {
        require(page.isNotNil) { "Page should not be nil | ${page.configuredUrl}" }
        require(page.isNotInternal) { "Page should not be internal | ${page.configuredUrl}" }

        onWillLoad(normURL, page)

        fetchContentIfNecessary(normURL, page)

        onLoaded(page, normURL)

        return page
    }

    @Throws(Exception::class)
    private suspend fun loadNormalURLWithEventHandlersDeferred(normURL: NormURL, page: WebPage): WebPage {
        require(page.isNotNil) { "Page should not be nil | ${page.configuredUrl}" }
        require(page.isNotInternal) { "Page should not be internal | ${page.configuredUrl}" }

        onWillLoad(normURL, page)

        fetchContentIfNecessaryDeferred(normURL, page)

        onLoaded(page, normURL)

        return page
    }

    private fun createPageShellOrNilWithEventHandlers(normURL: NormURL): WebPage {
        if (normURL.isNil) {
            doHandleLoadEventWithoutFetch(normURL)
            return GoraWebPage.NIL
        }

        tracer?.trace("Loading normURL, creating page shell ... | {}", normURL.configuredUrl)

        val page = createPageShell(normURL)
        if (page.isInternal) {
            doHandleLoadEventWithoutFetch(normURL)
            return page
        }

        if (deactivateFetchComponent && shouldFetch(page)) {
            doHandleLoadEventWithoutFetch(normURL)
            return GoraWebPage.NIL
        }

        return page
    }

    @Throws(Exception::class)
    private fun fetchContentIfNecessary(normURL: NormURL, page: WebPage) {
        require(page is AbstractWebPage)
        require(page.isNotInternal) { "Page should not be internal | ${page.configuredUrl}" }

        // double check is OK
        if (page.isInternal) {
            return
        }

        if (page.removeVar(VAR_REFRESH) != null) {
            fetchContent(page, normURL)
        }
    }

    @Throws(Exception::class)
    private suspend fun fetchContentIfNecessaryDeferred(normURL: NormURL, page: WebPage) {
        require(page is AbstractWebPage)
        when {
            page.hasVar(VAR_CAPTURE) -> fetchContentDeferred(page, normURL)
            page.removeVar(VAR_REFRESH) != null -> fetchContentDeferred(page, normURL)
        }
    }

    /**
     * Create a page shell, the page shell is the process unit for most tasks.
     *
     * If the page is in the cache, return the cached page, otherwise create a new page shell.
     *
     * @param normURL The normalized url
     * @return The page shell
     *
     * @throws IllegalArgumentException If the url is nil
     * */
    @Throws(IllegalArgumentException::class)
    private fun createPageShell(normURL: NormURL): WebPage {
        if (normURL.isNil) {
            throw IllegalArgumentException("Nil url is not allowed")
        }

        val cachedPage = getCachedPageOrNull(normURL)
        var page = FetchEntry.createPageShell(normURL)
        require(page is GoraWebPage)

        if (cachedPage != null) {
            pageCacheHits.incrementAndGet()
            page.isCached = true
            // the cached page can be or not be persisted, but not guaranteed
            // if a page is loaded from cache, the content remains unchanged and should not persist to database
            // TODO: clone the underlying data or not?
            page.unsafeCloneGPage(cachedPage)
            // if the underlying data is not copied, the persist content will be null
            page.clearPersistContent()

            page.tmpContent = cachedPage.content

            // TODO: test the dirty flag
            // do not persist this copy
            page.unbox().clearDirty()
            require(!page.isFetched)
            require(page.isNotInternal)
        } else {
            // get the metadata of the page from the database, this is very fast for a crawler
            // load page content and page model lazily, if we load page content and page model every time,
            // the underlying storage may crash due to the stress.
            val loadedPage = webDb.getOrNull(normURL.urlString)
            dbGetCount.incrementAndGet()
            if (loadedPage != null) {
                // override the old variables: args, href, etc
                FetchEntry.initWebPage(loadedPage, normURL.options, normURL.hrefSpec, normURL.referrer)
                page = loadedPage
            }

            initFetchState(normURL, page, loadedPage)
        }

        return page
    }

    private fun initFetchState(normURL: NormURL, page: WebPage, loadedPage: WebPage?): CheckState {
        val options = normURL.options

        val state = when {
            loadedPage == null -> CheckState(FetchState.NEW_PAGE, "nil 1")
            loadedPage.isNil -> CheckState(FetchState.NEW_PAGE, "nil 2")
            loadedPage.isInternal -> CheckState(FetchState.DO_NOT_FETCH, "internal 1")
            else -> fetchState(page, options)
        }

        require(page is AbstractWebPage)
        page.setVar(VAR_FETCH_STATE, state)
        val refresh = state.code in FetchState.refreshCodes
        if (refresh) {
            page.setVar(VAR_REFRESH, state)
        }

        return state
    }

    private fun onWillLoad(normURL: NormURL, page: WebPage) {
        if (page.isInternal) {
            return
        }

        val options = normURL.options

        shouldBe(options.conf, page.conf) { "Conf should be the same \n${options.conf} \n${page.conf}" }

        doHandleOnWillLoadEvent(normURL, page)
    }

    private fun onLoaded(page: WebPage, normURL: NormURL) {
        if (page.isInternal) {
            // A NIL url might have event handlers, so we must invoke the event handlers
            // return
        }

        val options = normURL.options
        val status = page.protocolStatus

        // handle page content
        if (!page.isCached) {
            // processPageContent(page, normURL)
        }

        // handle cache
        if (!options.readonly) {
            if (page.isFetched && page.protocolStatus.isSuccess) {
                documentCache.remove(page.url)
            }
            pageCache.putDatum(page.url, page)
        }

        // Too many cancels in 1.10.x, so do not report canceled pages, it will be improved in the further version
        if (!page.isCached && !page.isCanceled) {
            report(page)
        }

        // We might use the cached page's content in parse phase
        if (options.parserEngaged()) {
            // TODO: do we need page.protocolStatus.isSuccess?
            if (!page.isCanceled) {
                parse(page, normURL.options)
                if (page.parseStatus.isFailed) {
                    // re-fetch the page if failed to parse
                    page.protocolStatus = ProtocolStatus.retry(RetryScope.CRAWL, "parse failed")
                }
            }
        }

        doHandleOnLoadedEvent(normURL, page)

        if (options.persist && !page.isCanceled && !options.readonly) {
            persist(page, options)
        }
    }

    private fun doHandleLoadEventWithoutFetch(normURL: NormURL) {
        doHandleOnWillLoadEvent(normURL)
        doHandleOnLoadedEvent(normURL)
    }

    private fun doHandleOnWillLoadEvent(normURL: NormURL, page: WebPage? = null) {
        val url = normURL.urlString
        try {
            PulsarEventBus.pageEventHandlers?.loadEventHandlers?.onWillLoad?.invoke(url)
            // The more specific handlers has the opportunity to override the result of more general handlers.
            getPageEventHandlersOrNull(normURL, page)?.loadEventHandlers?.onWillLoad?.invoke(url)
            // Forward to server-side event handlers (non-blocking)
            PulsarEventBus.emitCrawlEvent("onWillLoad", url)
        } catch (e: Throwable) {
            val configuredUrl = page?.configuredUrl ?: normURL.configuredUrl
            logger.warn("Failed to invoke beforeLoad | $configuredUrl", e)
        }
    }

    private fun doHandleOnLoadedEvent(normURL: NormURL, page: WebPage? = null) {
        val url = normURL.urlString
        val detail = normURL.detail
        val page0 = page ?: GoraWebPage.NIL

        try {
            // we might use the cached page's content in after load handler
            if (detail is CompletableHyperlink<*>) {
                require(detail is ListenableUrl) { "The detail url should be a ListenableUrl" }
                require(detail.eventHandlers.loadEventHandlers.onLoaded.isNotEmpty) {
                    "A completable link must have a onLoaded handler"
                }

                if (page0.isNotInternal) {
                    require(page0.loadEventHandlers?.onLoaded?.isNotEmpty == true) {
                        "A page with a completable link must have a onLoaded handler"
                    }
                }
            }

            PulsarEventBus.pageEventHandlers?.loadEventHandlers?.onLoaded?.invoke(page0)
            // The more specific handlers has the opportunity to override the result of more general handlers.
            getPageEventHandlersOrNull(normURL, page)?.loadEventHandlers?.onLoaded?.invoke(page0)
            // Forward to server-side event handlers (non-blocking)
            PulsarEventBus.emitLoadEvent("onLoaded", page0)
        } catch (e: Throwable) {
            val configuredUrl = page?.configuredUrl ?: normURL.configuredUrl
            logger.warn("Failed to invoke doHandleOnLoadedEvent | $configuredUrl", e)
        }
    }

    /**
     * Get the event handlers for the page, if the page has no event handlers, use the event handlers
     *
     * [eventHandlers] is a shortcut of [LoadOptions.eventHandlers], and [LoadOptions.eventHandlers] can come from a [ListenableUrl].
     * */
    private fun getPageEventHandlersOrNull(normURL: NormURL, page: WebPage?): PageEventHandlers? {
        val detail = normURL.detail
        var eventHandlers = page?.eventHandlers

        if (eventHandlers == null && detail is ListenableUrl) {
            eventHandlers = detail.eventHandlers
        }

        return eventHandlers
    }

    private fun parse(page: WebPage, options: LoadOptions): ParseResult? {
        val parser = parseComponent.takeIf { options.parse } ?: return null
        val parseResult = parser.parse(page)
        return parseResult
    }

    private fun report(page: WebPage) {
        if (taskLogger.isInfoEnabled) {
            val verbose = taskLogger.isDebugEnabled
            val report = PageLoadStatusFormatter(page, withSymbolicLink = verbose, withOptions = true).toString()

            taskLogger.info(report)

            if (reportCount.getAndIncrement() == 0) {
                val logExplainUrl = "https://github.com/platonai/browser4/blob/master/docs/log-format.md"
                taskLogger.info("Log explanation: $logExplainUrl")
            }
        }
    }

    /**
     * Get a page from the memory cache, if the page is not in the cache, or it's expired, return null.
     *
     * @param normURL The normalized url
     * @return The page, or null
     * */
    private fun getCachedPageOrNull(normURL: NormURL): WebPage? {
        val url = normURL.urlString
        val options = normURL.options
        if (options.refresh) {
            // refresh the page, do not take cached version
            return null
        }

        val now = Instant.now()
        val cachedPage = pageCache.getDatum(url, options.expires, now)
        if (cachedPage != null && !options.isExpired(cachedPage.prevFetchTime)) {
            // TODO: properly handle page conf, a page might work in different context which have different conf
            // TODO: properly handle ListenableHyperlink
            // here is a complex logic for a ScrapingHyperlink: the page have an event handlers, and the page can
            // also be loaded inside an event handler. We must handle such situation very carefully

            // page.conf = normURL.options.conf
            // page.args = normURL.args

            return cachedPage
        }

        return null
    }

    private fun shouldFetch(page: WebPage): Boolean {
        require(page is AbstractWebPage)
        return page.hasVar(VAR_REFRESH)
    }

    private fun beforeFetch(page: WebPage, options: LoadOptions) {
        // require(page.options == options)
        require(page is AbstractWebPage)
        page.setVar(VAR_PREV_FETCH_TIME_BEFORE_UPDATE, page.prevFetchTime)
        globalCache.fetchingCache.add(page.url)
        logger.takeIf { it.isDebugEnabled }?.debug("Loading url | {} {}", page.url, page.args)
    }

    @Throws(Exception::class)
    private fun fetchContent(page: WebPage, normURL: NormURL) {
        if (page.isInternal) {
            // No need to fetch internal pages
            // No fetch event handlers should be handled for internal pages
            return
        }

        try {
            beforeFetch(page, normURL.options)

            require(page.conf == normURL.options.conf)
//            require(normURL.options.eventHandler != null)
//            require(page.conf.getBeanOrNull(PulsarEventHandler::class) != null)

            tracer?.trace("Fetching with fetch component ... | {}", page.configuredUrl)

            fetchComponent.fetchContent(page)
        } finally {
            afterFetch(page, normURL.options)
        }
    }

    @Throws(Exception::class)
    private suspend fun fetchContentDeferred(page: WebPage, normURL: NormURL) {
        if (page.isInternal) {
            // No need to fetch internal pages, and no event handlers should be handled
            return
        }

        try {
            beforeFetch(page, normURL.options)

            require(page.conf == normURL.options.conf)
//            require(normURL.options.eventHandler != null)
//            require(page.conf.getBeanOrNull(PulsarEventHandler::class) != null)

            fetchComponent.fetchContentDeferred(page)
        } finally {
            afterFetch(page, normURL.options)
        }
    }

    private fun afterFetch(page: WebPage, options: LoadOptions) {
        // the metadata of the page is loaded from database but the content is not cached,
        // so load the content again
        updateFetchSchedule(page, options)
        globalCache.fetchingCache.remove(page.url)
    }

    private fun getFetchStateForExistPage(page: WebPage, options: LoadOptions): CheckState {
        val protocolStatus = page.protocolStatus

        if (options.refresh) {
            page.fetchRetries = 0
            return CheckState(FetchState.REFRESH, "refresh")
        }

        val ignoreFailure = options.ignoreFailure
        if (protocolStatus.isRetry) {
            return CheckState(FetchState.RETRY, "retry")
        } else if (protocolStatus.isFailed && !ignoreFailure) {
            // Failed to fetch the page last time, it might be caused by page is gone
            // in such case, do not fetch it even it's expired, unless the retryFailed flag is set
            return CheckState(FetchState.DO_NOT_FETCH, "failed")
        }

        val now = Instant.now()

        // Fetch a page already fetched before if it's expired
        val prevFetchTime = page.prevFetchTime
        if (prevFetchTime.isBefore(AppConstants.TCP_IP_STANDARDIZED_TIME)) {
            statusTracker?.messageWriter?.debugIllegalLastFetchTime(page)
        }

        // if (expireAt in prevFetchTime..now || now > prevFetchTime + expires), it's expired
        if (options.isExpired(prevFetchTime)) {
            return CheckState(FetchState.EXPIRED, "expired 1")
        }

        val duration = Duration.between(page.fetchTime, now)
        val days = duration.toDays()
        if (duration.toMillis() > 0 && days < 3) {
            return CheckState(FetchState.SCHEDULED, "scheduled")
        }

        // no content
        if (page.persistedContentLength == 0L) {
            // do not enable this feature by default
            // return CheckState(FetchState.NO_CONTENT, "no content")
        }

        // TODO: page.persistedContentLength or page.originalContentLength?
        if (page.persistedContentLength < options.requireSize) {
            return CheckState(FetchState.SMALL_CONTENT, "small content")
        }

        val domStats = page.activeDOMStatTrace
        val (ni, na) = domStats["lastStat"] ?: ActiveDOMStat()
        if (ni < options.requireImages) {
            return CheckState(FetchState.MISS_FIELD, "miss image")
        }
        if (na < options.requireAnchors) {
            return CheckState(FetchState.MISS_FIELD, "miss anchor")
        }

        return CheckState(FetchState.DO_NOT_FETCH, "unknown")
    }

    private fun updateFetchSchedule(page: WebPage, options: LoadOptions) {
        if (page.isInternal) {
            logger.warn("Unexpected internal page [updateFetchSchedule]")
            return
        }

        // canceled or loaded from database, do not update fetch schedule
        if (page.isCanceled || !page.isFetched) {
            return
        }

        updateComponent.updateFetchSchedule(page)

        require(page.isFetched)
    }

    private fun persist(page: WebPage, options: LoadOptions) {
        // Remove page content if dropContent is set or storeContent is false. Page content is set earlier,
        // so the PageParser can parse it, now, we can clear it since it's usually very large.
        if (options.dropContent || !options.storeContent) {
            page.clearPersistContent()
        }

        // The content is loaded from cache, the content remains unchanged, do not persist it
        // TODO: check the logic again
        if (page.isCached) {
            require(page is GoraWebPage)
            page.unbox().clearDirty(GWebPage.Field.CONTENT.index)
            assert(!page.unbox().isContentDirty)
        }

        webDb.put(page)
        ++numWrite

        collectPersistMetrics(page)

        if (numWrite < 200) {
            flush()
        } else if (!options.lazyFlush || numWrite % 20 == 0) {
            flush()
        }
    }

    private fun collectPersistMetrics(page: WebPage) {
        val metrics = coreMetrics
        if (metrics != null) {
            metrics.persists.mark()
            val bytes = page.content?.array()?.size ?: 0
            if (bytes > 0) {
                metrics.contentPersists.mark()
                metrics.persistContentMBytes.inc(ByteUnitConverter.convert(bytes, "M").toLong())
            }
        }
        tracer?.trace("Persisted {} | {}", Strings.compactFormat(page.contentLength), page.url)
    }

    fun flush() = webDb.flush()

    override fun close() {
        closed.compareAndSet(false, true)
    }

    private fun assertSame(a: Any?, b: Any?, lazyMessage: () -> String) {
        require(a === b) { lazyMessage() }
    }

    private fun shouldBe(expected: Any?, actual: Any?, lazyMessage: () -> String) {
        if (actual != expected) {
            logger.warn(lazyMessage())
        }
    }

    class LazyFieldLoader(
        val url: String,
        val db: WebDb
    ) : Function<String, GWebPage?> {
        override fun apply(field: String): GWebPage? {
            return db.get0(url, false, arrayOf(field))
        }
    }
}
