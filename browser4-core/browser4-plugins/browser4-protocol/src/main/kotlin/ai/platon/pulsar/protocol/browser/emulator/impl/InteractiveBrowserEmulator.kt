/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.protocol.browser.emulator.impl

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.browser4.driver.common.DomSettlePolicy
import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.AppConstants.VAR_CAPTURE
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.event.AbstractEventEmitter
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.RetryScope
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.persist.model.ActiveDOMMessage
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import ai.platon.pulsar.protocol.browser.emulator.*
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.common.persist.ext.browseEventHandlers
import ai.platon.pulsar.skeleton.common.persist.ext.options
import ai.platon.pulsar.skeleton.event.PulsarEventBus
import ai.platon.pulsar.skeleton.workflow.fetch.FetchResult
import ai.platon.pulsar.skeleton.workflow.fetch.FetchTask
import ai.platon.pulsar.skeleton.workflow.fetch.driver.*
import ai.platon.pulsar.skeleton.workflow.protocol.ForwardingResponse
import ai.platon.pulsar.skeleton.workflow.protocol.Response
import ai.platon.pulsar.skeleton.workflow.protocol.http.ProtocolStatusTranslator
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * Created by Vincent on 18-1-1.
 * Copyright @ 2013-2023 Platon AI. All rights reserved.
 */
open class InteractiveBrowserEmulator(
    /**
     * The driver pool manager
     * */
    val driverPoolManager: WebDriverPoolManager,
    /**
     * The response handler
     * */
    responseHandler: BrowserResponseHandler,
    /**
     * The immutable config
     * */
    immutableConfig: ImmutableConfig,
) : BrowserEmulator,
    BrowserEmulatorImplBase(responseHandler, immutableConfig) {
    private val logger = getLogger(InteractiveBrowserEmulator::class)
    private val tracer = getTracerOrNull(InteractiveBrowserEmulator::class)
    private val taskLogger = getLogger(InteractiveBrowserEmulator::class, ".Task")

    private val numDeferredNavigates by lazy { MetricsSystem.reg.meter(this, "deferredNavigates") }

    override var eventExceptionHandler: (Throwable) -> Unit = {
        warnInterruptible(AbstractEventEmitter::class, it)
    }

    init {
        // Attach event handlers
        attach()
    }

    /**
     * Fetch a page using a browser which can render the DOM and execute scripts.
     *
     * @param task The task to fetch
     * @param driver The web driver
     * @return The result of this fetch
     * */
    @Throws(WebDriverException::class)
    override suspend fun visit(task: FetchTask, driver: WebDriver): FetchResult {
        return takeIf { isActive }?.browseWithDriver(task, driver)
            ?: FetchResult.canceled(task, "Inactive browser emulator")
    }

    /**
     * Cancel a task immediately
     *
     * @param task The task to cancel
     * */
    override fun cancelNow(task: FetchTask) {
        counterCancels.inc()
        task.cancel()
        driverPoolManager.cancel(task.url)
    }

    /**
     * Cancel a task
     *
     * @param task The task to cancel
     * */
    override suspend fun cancel(task: FetchTask) {
        counterCancels.inc()
        task.cancel()
        driverPoolManager.cancel(task.url)
    }

    /**
     * Attach event handlers
     * */
    private fun attach() {
        on1(EmulateEvents.willNavigate) { page: WebPage, driver: WebDriver ->
            this.onWillNavigate(page, driver)
        }
        on1(EmulateEvents.willInteract) { page: WebPage, driver: WebDriver ->
            this.onWillInteract(page, driver)
        }
        on1(EmulateEvents.willCheckDocumentState) { page: WebPage, driver: WebDriver ->
            this.onWillCheckDocumentState(page, driver)
        }
        on1(EmulateEvents.documentFullyLoaded) { page: WebPage, driver: WebDriver ->
            this.onDocumentFullyLoaded(page, driver)
        }
        on1(EmulateEvents.willScroll) { page: WebPage, driver: WebDriver ->
            this.onWillScroll(page, driver)
        }
        on1(EmulateEvents.didScroll) { page: WebPage, driver: WebDriver ->
            this.onDidScroll(page, driver)
        }
        on1(EmulateEvents.documentSteady) { page: WebPage, driver: WebDriver ->
            this.onDocumentSteady(page, driver)
        }
        on1(EmulateEvents.willComputeFeature) { page: WebPage, driver: WebDriver ->
            this.onWillComputeFeature(page, driver)
        }
        on1(EmulateEvents.featureComputed) { page: WebPage, driver: WebDriver ->
            this.onFeatureComputed(page, driver)
        }
        on1(EmulateEvents.didInteract) { page: WebPage, driver: WebDriver ->
            this.onDidInteract(page, driver)
        }
        on1(EmulateEvents.navigated) { page: WebPage, driver: WebDriver ->
            this.onNavigated(page, driver)
        }
        on1(EmulateEvents.willStopTab) { page: WebPage, driver: WebDriver ->
            this.onWillStopTab(page, driver)
        }
        on1(EmulateEvents.tabStopped) { page: WebPage, driver: WebDriver ->
            this.onTabStopped(page, driver)
        }
    }

    private fun detach() {
        EmulateEvents.entries.forEach { off(it) }
    }

    override suspend fun onWillNavigate(page: WebPage, driver: WebDriver) {
        PulsarEventBus.emitBrowseEvent("onWillNavigate", page)
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onWillNavigate?.invoke(page, driver)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        page.browseEventHandlers?.onWillNavigate?.invoke(page, driver)
    }

    override suspend fun onNavigated(page: WebPage, driver: WebDriver) {
        PulsarEventBus.emitBrowseEvent("onNavigated", page)
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onNavigated?.invoke(page, driver)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        page.browseEventHandlers?.onNavigated?.invoke(page, driver)
    }

    override suspend fun onWillInteract(page: WebPage, driver: WebDriver) {
        PulsarEventBus.emitBrowseEvent("onWillInteract", page)
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onWillInteract?.invoke(page, driver)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        page.browseEventHandlers?.onWillInteract?.invoke(page, driver)
    }

    override suspend fun onWillCheckDocumentState(page: WebPage, driver: WebDriver) {
        PulsarEventBus.emitBrowseEvent("onWillCheckDocumentState", page)
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onWillCheckDocumentState?.invoke(page, driver)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        page.browseEventHandlers?.onWillCheckDocumentState?.invoke(page, driver)
    }

    override suspend fun onDocumentFullyLoaded(page: WebPage, driver: WebDriver) {
        PulsarEventBus.emitBrowseEvent("onDocumentFullyLoaded", page)
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onDocumentFullyLoaded?.invoke(page, driver)
        page.browseEventHandlers?.onDocumentFullyLoaded?.invoke(page, driver)
    }

    override suspend fun onWillScroll(page: WebPage, driver: WebDriver) {
        PulsarEventBus.emitBrowseEvent("onWillScroll", page)
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onWillScroll?.invoke(page, driver)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        page.browseEventHandlers?.onWillScroll?.invoke(page, driver)
    }

    override suspend fun onDidScroll(page: WebPage, driver: WebDriver) {
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onDidScroll?.invoke(page, driver)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        page.browseEventHandlers?.onDidScroll?.invoke(page, driver)
        PulsarEventBus.emitBrowseEvent("onDidScroll", page)
    }

    override suspend fun onDocumentSteady(page: WebPage, driver: WebDriver) {
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onDocumentSteady?.invoke(page, driver)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        page.browseEventHandlers?.onDocumentSteady?.invoke(page, driver)
        PulsarEventBus.emitBrowseEvent("onDocumentSteady", page)
    }

    override suspend fun onWillComputeFeature(page: WebPage, driver: WebDriver) {
        PulsarEventBus.emitBrowseEvent("onWillComputeFeature", page)
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onWillComputeFeature?.invoke(page, driver)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        page.browseEventHandlers?.onWillComputeFeature?.invoke(page, driver)
    }

    override suspend fun onFeatureComputed(page: WebPage, driver: WebDriver) {
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onFeatureComputed?.invoke(page, driver)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        page.browseEventHandlers?.onFeatureComputed?.invoke(page, driver)
        PulsarEventBus.emitBrowseEvent("onFeatureComputed", page)
    }

    override suspend fun onDidInteract(page: WebPage, driver: WebDriver) {
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onDidInteract?.invoke(page, driver)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        page.browseEventHandlers?.onDidInteract?.invoke(page, driver)
        PulsarEventBus.emitBrowseEvent("onDidInteract", page)
    }

    override suspend fun onWillStopTab(page: WebPage, driver: WebDriver) {
        PulsarEventBus.emitBrowseEvent("onWillStopTab", page)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onWillStopTab?.invoke(page, driver)
        page.browseEventHandlers?.onWillStopTab?.invoke(page, driver)
    }

    override suspend fun onTabStopped(page: WebPage, driver: WebDriver) {
        PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onTabStopped?.invoke(page, driver)
        // The more specific handlers has the opportunity to override the result of more general handlers.
        page.browseEventHandlers?.onTabStopped?.invoke(page, driver)
        PulsarEventBus.emitBrowseEvent("onTabStopped", page)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            detach()
        }
    }

    @Throws(Exception::class)
    protected open suspend fun browseWithDriver(task: FetchTask, driver: WebDriver): FetchResult {
        require(driver is AbstractWebDriver)

        // page.lastBrowser is used by AppFiles.export, so it has to be set before export
        // TODO: page should not be modified in browser phase, it should only be updated using PageDatum
        task.page.lastBrowser = driver.browserType

        if (task.page.options.isDead()) {
            taskLogger.info("Page is dead, cancel the task | {}", task.page.configuredUrl)
            return FetchResult.canceled(task, "Page deadline exceed")
        }

        var exception: Exception? = null
        var response: Response?
        val settings = driver.browser.settings
        val navigateTask = NavigateTask(task, driver)

        try {
            checkState(task, driver)

            response = if (task.page.isResource) {
                loadResourceWithoutRendering(navigateTask, driver)
            } else browseWithCancellationHandled(navigateTask, driver, settings)
        } catch (e: NavigateTaskCancellationException) {
            // The task is canceled
            response = ForwardingResponse.canceled(task.page)
        } catch (e: WebDriverCancellationException) {
            // The web driver is canceled
            response = ForwardingResponse.canceled(task.page)
        } catch (e: IllegalWebDriverStateException) {
            if (isActive) {
                val browser = driver.browser
                logger.info(
                    "Dismiss illegal driver #{}: {} | browser #{}:{} | {}",
                    driver.id, driver.status, browser.instanceId, browser.readableState, e.brief()
                )
                // e.printStackTrace()
            }

            driver.retire()
            exception = e
            response = ForwardingResponse.privacyRetry(task.page, "Illegal web driver")
        } catch (e: WebDriverException) {
            if (e.cause is org.apache.http.conn.HttpHostConnectException) {
                logger.warn("Web driver is disconnected - {}", e.brief())
            } else {
                logger.warn("[Unexpected] WebDriverException", e)
            }

            driver.retire()
            exception = e
            response = ForwardingResponse.crawlRetry(task.page, e)
        } catch (e: TimeoutCancellationException) {
            logger.warn("[Timeout] Coroutine was cancelled, thrown by [withTimeout] | {}", e.stringify())
            response = ForwardingResponse.crawlRetry(task.page, e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            response = ForwardingResponse.crawlRetry(task.page, e)
        } catch (e: Exception) {
            // handleException(e, task, driver)
            // Let the higher level to handle it
            throw e
        } finally {
        }

        return FetchResult(task, response ?: ForwardingResponse(exception, task.page), exception)
    }

    private fun handleException(e: Exception, task: FetchTask, driver: WebDriver) {
        when {
            e.javaClass.name == "kotlinx.coroutines.JobCancellationException" -> {
                if (isActive) {
                    // The system is not closing.
                    // The coroutine is canceled, it's not a normal case
                    val message = e.message ?: "Coroutine was cancelled"
                    logger.warn("{}. {} | {}", task.page.id, message, task.url)
                } else {
                    // The system is closing.
                    // Let the higher level to handle it, usually it's handled by the main loop
                    throw e
                }
            }

            else -> {
                logger.warn("[Unexpected]", e)
            }
        }
    }

    @Throws(NavigateTaskCancellationException::class, WebDriverCancellationException::class)
    private suspend fun loadResourceWithoutRendering(navigateTask: NavigateTask, driver: WebDriver): Response {
        checkState(navigateTask.fetchTask, driver)

        val page = navigateTask.page
        val referrer = page.referrer
        if (referrer != null && !driver.browser.navigateHistory.contains(referrer)) {
            driver.navigate(referrer)
            driver.waitForSelector("body", Duration.ofSeconds(15))
        }

        val resourceLoader = page.conf["resource.loader", "jsoup"]
        val response = when (resourceLoader) {
            "web.driver" -> driver.loadResource(navigateTask.url)
            "jsoup" -> NetworkResourceHelper.fromJsoup(driver.loadJsoupResource(navigateTask.url))
            else -> NetworkResourceHelper.fromJsoup(driver.loadJsoupResource(navigateTask.url))
        }

        // TODO: transform protocol status in AbstractHttpProtocol
        val protocolStatus = ProtocolStatusTranslator.translateHttpCode(response.httpStatusCode)
        val headers = response.headers ?: mapOf()
        val content = response.stream ?: ""
        // Note: originalContentLength is already set before willComputeFeature event, (if not removed by someone)
        navigateTask.originalContentLength = content.length
        navigateTask.pageSource = preprocessPageContent(content)

        navigateTask.pageDatum.also {
            it.protocolStatus = protocolStatus
            headers.forEach { (t, u) -> it.headers[t] = u.toString() }
//            it.contentType = response.contentType()
            it.content = navigateTask.pageSource.toByteArray(StandardCharsets.UTF_8)
            it.originalContentLength = navigateTask.originalContentLength
        }

        responseHandler.emit(BrowserResponseEvents.willCreateResponse)
        return createResponseWithDatum(navigateTask, navigateTask.pageDatum).also {
            responseHandler.emit(BrowserResponseEvents.responseCreated)
        }
    }

    private suspend fun browseWithCancellationHandled(
        navigateTask: NavigateTask, driver: WebDriver, settings: BrowserSettings
    ): Response? {
        checkState(navigateTask.fetchTask, driver)

        var response: Response?
        val page = navigateTask.page

        try {
            response = browseWithWebDriver(navigateTask, driver)

            emit1(EmulateEvents.willStopTab, page, driver)

            if (!settings.isSPA) {
                /**
                 * Force the page stop all navigations and releases all resources.
                 * If a web driver is terminated, it should not be used any more and should be quit
                 * as soon as possible.
                 * */
                driver.stop()
            }

            emit1(EmulateEvents.tabStopped, page, driver)
        } catch (e: NavigateTaskCancellationException) {
            if (isActive) {
                logger.info(
                    "{}. Task is canceled {}/{} again later (privacy scope suggested)",
                    page.id, navigateTask.fetchTask.id, navigateTask.fetchTask.batchId
                )
            }
            response = ForwardingResponse.canceled(page)
        }

        return response
    }

    @Throws(NavigateTaskCancellationException::class, WebDriverCancellationException::class)
    private suspend fun browseWithWebDriver(navigateTask: NavigateTask, driver: WebDriver): Response {
        val fetchTask = navigateTask.fetchTask
        checkState(navigateTask.fetchTask, driver)
        require(driver is AbstractWebDriver)

        val browserSettings = driver.browser.settings
        // TODO: a better flag to specify whether to attach or navigate
        val page = fetchTask.page
        require(page is AbstractWebPage)
        val capture = page.hasVar(VAR_CAPTURE)
        val interactResult = if (capture) {
            driver.ignoreDOMFeatures = true
            captureLivePage(navigateTask, driver, browserSettings)
        } else {
            navigateAndInteract(navigateTask, driver, browserSettings)
        }

        // TODO: separate status code of pulsar system and the status code from browser
        val httpCode = driver.mainResponseStatus
        val finalProtocolStatus =
            if (httpCode < 0 || interactResult.protocolStatus.minorCode >= ProtocolStatusCodes.INCOMPATIBLE_CODE_START) {
                // Browser4 status
                interactResult.protocolStatus
            } else {
                // HTTP status
                ProtocolStatusTranslator.translateHttpCode(httpCode)
            }

        navigateTask.pageDatum.apply {
            protocolStatus = finalProtocolStatus
            activeDOMStatTrace = interactResult.activeDOMMessage?.trace
            activeDOMUrls = interactResult.activeDOMMessage?.urls
            activeDomMetadata = interactResult.activeDOMMessage?.metadata
        }
        val content = driver.pageSource()
        // Note: originalContentLength is already set before willComputeFeature event, (if not removed by someone)
        navigateTask.originalContentLength = content?.length ?: 0
        navigateTask.pageSource = preprocessPageContent(content)

        responseHandler.onWillCreateResponse(fetchTask, driver)
        return createResponse(navigateTask).also {
            responseHandler.onResponseCreated(fetchTask, driver, it)
        }
    }

    @Throws(NavigateTaskCancellationException::class, WebDriverException::class)
    private suspend fun navigateAndInteract(
        task: NavigateTask,
        driver: WebDriver,
        settings: BrowserSettings,
    ): InteractResult {
        val fetchTask = task.fetchTask
        checkState(fetchTask, driver)

        val page = task.page

        logBeforeNavigate(fetchTask, settings)

        meterNavigates.mark()
        numDeferredNavigates.mark()

        tracer?.trace("{}. Navigating | {}", page.id, task.url)

        checkState(fetchTask, driver)

        // href has the higher priority to locate a resource
        require(task.url == page.url)
        val finalUrl = fetchTask.href ?: fetchTask.url
        val navigateEntry = NavigateEntry(finalUrl, page.id, task.url, pageReferrer = page.referrer)

        emit1(EmulateEvents.willNavigate, page, driver)

        checkState(fetchTask, driver)
        try {
            driver.navigate(navigateEntry)
        } finally {
            emit1(EmulateEvents.navigated, page, driver)
        }

        require(driver is AbstractWebDriver)

        val interactTask = InteractTask(task, settings, driver)

        val result = try {
            emit1(EmulateEvents.willInteract, page, driver)

            interact(interactTask)
        } finally {
            emit1(EmulateEvents.didInteract, page, driver)
        }

        return result
    }

    @Throws(NavigateTaskCancellationException::class, WebDriverException::class)
    private suspend fun captureLivePage(
        task: NavigateTask,
        driver: WebDriver,
        settings: BrowserSettings,
    ): InteractResult {
        val fetchTask = task.fetchTask

        checkState(fetchTask, task.driver)

        val result = InteractResult(ProtocolStatus.STATUS_SUCCESS, null)
        val page = task.page
        require(driver is AbstractWebDriver)

        tracer?.trace("InteractSettings: {}", task.interactSettings)

        if (result.state.isContinue) {
            updateMetaInfos(page, driver)
        }

        // With the scrolling operation finished, the page is stable and unlikely to experience significant updates.
        // Therefore, we can now proceed to calculate the document’s features.
        // TODO: driver.pageSource() might be huge so there might be a performance issue
        task.originalContentLength = driver.pageSource()?.length ?: 0

        // js might not injected
//        if (result.state.isContinue) {
//            emit1(EmulateEvents.willComputeFeature, page, driver)
//            emit1(EmulateEvents.featureComputed, page, driver)
//        }

        return result
    }

    @Throws(NavigateTaskCancellationException::class, WebDriverCancellationException::class)
    protected open suspend fun interact(task: InteractTask): InteractResult {
        val fetchTask = task.navigateTask.fetchTask
        checkState(fetchTask, task.driver)

        val result = InteractResult(ProtocolStatus.STATUS_SUCCESS, null)
        val page = task.page
        val driver = task.driver
        require(driver is AbstractWebDriver)

        tracer?.trace("InteractSettings: {}", task.interactSettings)

        emit1(EmulateEvents.willCheckDocumentState, page, driver)

        val hasScript = waitForJavascriptInjected(task, result)
        if (hasScript) {
            // Wait until the document is actually ready, or timeout.
            // wait for document be fully loaded, in another word, the DOM becomes settle
            waitForDocumentSettle(task, result)
        }

        if (result.protocolStatus.isSuccess) {
            task.driver.navigateEntry.documentReadyTime = Instant.now()
            emit1(EmulateEvents.documentFullyLoaded, page, driver)
        }

        if (result.state.isContinue) {
            emit1(EmulateEvents.willScroll, page, driver)

            if (hasScript) {
                scrollOnPage(task, result)
            }

            emit1(EmulateEvents.didScroll, page, driver)
        }

        if (result.state.isContinue && hasScript) {
            val selectors = task.page.options.waitNonBlank.split(",")
            if (selectors.isNotEmpty()) {
                waitForElementUntilNonBlank(task, selectors)
            }
        }

        if (result.state.isContinue) {
            updateMetaInfos(page, driver)
            // TODO: check if state.isContinue is necessary
            emit1(EmulateEvents.documentSteady, page, driver)
        }

        // With the scrolling operation finished, the page is stable and unlikely to experience significant updates.
        // Therefore, we can now proceed to calculate the document’s features.
        // TODO: driver.pageSource() might be huge so there might be a performance issue
        task.navigateTask.originalContentLength = driver.pageSource()?.length ?: 0
        if (result.state.isContinue) {
            emit1(EmulateEvents.willComputeFeature, page, driver)

            if (hasScript && !driver.ignoreDOMFeatures) {
                computeDocumentFeatures(task, result)
            }

            emit1(EmulateEvents.featureComputed, page, driver)
        }

        return result
    }

    /**
     * Wait until the document is actually ready, or timeout.
     * */
    @Throws(NavigateTaskCancellationException::class)
    protected open suspend fun waitForDocumentSettle(interactTask: InteractTask, result: InteractResult) {
        val page = interactTask.page
        val driver = interactTask.driver
        require(driver is AbstractWebDriver)

        when (interactTask.interactSettings.domSettlePolicy) {
            DomSettlePolicy.FIELDS_SETTLE -> waitForDocumentSettle1(interactTask, result)
            else -> waitForNetworkIdle(interactTask, result)
        }
    }

    /**
     * Wait until javascript is injected, or timeout.
     * The javascript can be failed to be injected, for example, the resource is an Excel, PDF, etc.
     * */
    protected open suspend fun waitForJavascriptInjected(interactTask: InteractTask, result: InteractResult): Boolean {
        val page = interactTask.page
        val driver = interactTask.driver

        var n = 10
        while (n-- > 0 && !isScriptInjected(driver)) {
            delay(1000)
        }

        if (n <= 0) {
            logger.warn("JavaScript is not injected | {}", page.href ?: page.url)
        }

        return n > 0
    }

    /**
     * Check if the script is injected.
     * */
    protected suspend fun isScriptInjected(driver: WebDriver): Boolean {
        // Ensure __pulsar_utils__ is defined. For some type of pages, the script can not be injected.
        val utils = driver.evaluate("typeof(__pulsar_utils__)")
        return utils == "function"
    }

    /**
     * Wait until the document is actually ready, or timeout.
     * */
    @Throws(NavigateTaskCancellationException::class)
    protected open suspend fun waitForDocumentSettle1(interactTask: InteractTask, result: InteractResult) {
        val driver = interactTask.driver
        require(driver is AbstractWebDriver)

        var status = ProtocolStatus.STATUS_SUCCESS
        val scriptTimeout = interactTask.interactSettings.scriptTimeout
        val fetchTask = interactTask.navigateTask.fetchTask
        val scrollCount = interactTask.interactSettings.autoScrollCount

        val initialScroll = if (scrollCount > 0) 5 else 0
        val delayMillis = 500L * 2
//        val maxRound = scriptTimeout.toMillis() / delayMillis
        val maxRound = 60

        // TODO: wait for expected data, ni, na, nn, nst, etc; required element
        val expression = String.format("__pulsar_utils__.waitForReady(%d)", initialScroll)
        var i = 0
        var message: Any? = null
        try {
            var msg: Any? = null
            while ((msg == null || msg == false) && i++ < maxRound && isActive && !fetchTask.isCanceled) {
                msg = evaluate(interactTask, expression)

                if (msg == null || msg == false) {
                    delay(delayMillis)
                }
            }
            message = msg
        } finally {
            if (message == null) {
                if (!fetchTask.isCanceled && !driver.isQuit && isActive) {
                    logger.warn(
                        "Timeout to wait for document ready after ${i.dec()} round, retry is supposed | {}",
                        interactTask.url
                    )
                    status = ProtocolStatus.retry(RetryScope.PRIVACY, "Timeout to wait for document ready")
                    result.state = FlowState.BREAK
                }
            } else if (message == "timeout") {
                // this will never happen since 1.10.0
                logger.debug("Hit max round $maxRound to wait for document | {}", interactTask.url)
            } else if (message is String && message.contains("chrome-error://")) {
                val browserError = responseHandler.createBrowserErrorResponse(message)
                status = browserError.status
                result.activeDOMMessage = browserError.activeDOMMessage
                result.state = FlowState.BREAK
            } else {
                if (tracer != null) {
                    val page = interactTask.page
                    val truncatedMessage = message.toString().substringBefore("urls")
                    tracer.trace("{}. DOM is ready after {} evaluation | {}", page.id, i, truncatedMessage)
                }
            }
        }

        result.protocolStatus = status
    }

    suspend fun waitForNetworkIdle(interactTask: InteractTask, result: InteractResult) {
        val driver = interactTask.driver
        require(driver is PulsarWebDriver)

        val pollMillis = 200L
        val maxRound = 60_000 / pollMillis
        var i = 0
        while (i++ < maxRound) {
            if (driver.isNetworkIdle) {
                break
            }
            delay(pollMillis)
        }

        result.protocolStatus = ProtocolStatus.STATUS_SUCCESS
    }

    /**
     * Scroll on page to ensure all the content is loaded, including lazy content.
     * */
    open suspend fun scrollOnPage(interactTask: InteractTask, result: InteractResult) {
        val interactSettings = interactTask.interactSettings
        if (interactSettings.autoScrollCount > 0) {
            val positions = interactTask.interactSettings.buildScrollPositions()
            // some website shows lazy content only when the page is in the front.
            val driver = interactTask.driver
            val bringToFront = interactTask.interactSettings.bringToFront
            if (bringToFront) {
                driver.bringToFront()
            }
            val scrollInterval = interactSettings.scrollInterval.toMillis()
            // evaluate(interactTask, positions, scrollInterval, bringToFront = bringToFront)
            positions.forEach {
                driver.scrollToMiddle(it)
                delay(scrollInterval)
            }
        }
    }

    private suspend fun updateMetaInfos(page: WebPage, driver: WebDriver) {
        // the node is created by injected javascript
        val urls = mutableMapOf(AppConstants.PULSAR_DOCUMENT_NORMALIZED_URI to page.url)
        urls.forEach { (rel, href) ->
            val js = """
                (() => {
                    const link = document.createElement('link');
                    link.rel = '$rel';
                    link.href = '$href';
                    document.head.appendChild(link);
                })()
            """.trimIndent().replace("\n", ";")

            val result = driver.evaluateDetail(js)
            if (result?.exception != null) {
                logger.warn("Failed to update meta info | $rel: $href | ${result.exception}")
            }
        }
    }

    protected open suspend fun waitForElementUntilNonBlank(
        interactTask: InteractTask, requiredElements: List<String>,
    ) {
        if (requiredElements.isNotEmpty()) {
            return
        }

        val expressions = requiredElements.map { "!!document.querySelector('$it')" }
        var scrollCount = 5

        val delayMillis = interactTask.interactSettings.scrollInterval.toMillis()
        var exists: Any? = null
        while (scrollCount-- > 0 && (exists == null || exists == false)) {
            counterJsWaits.inc()
            val verbose = false
            exists = expressions.all { expression -> true == evaluate(interactTask, expression, verbose) }
            delay(delayMillis)
        }
    }

    protected open suspend fun computeDocumentFeatures(interactTask: InteractTask, result: InteractResult) {
        val expression = "__pulsar_utils__.compute()"
        val message = evaluate(interactTask, expression)

        if (message is String) {
            result.activeDOMMessage = ActiveDOMMessage.fromJson(message)
            if (taskLogger.isDebugEnabled) {
                val page = interactTask.navigateTask.fetchTask.page
                taskLogger.debug("{}. {} | {}", page.id, result.activeDOMMessage?.trace, interactTask.url)
            }
        }
    }
}
