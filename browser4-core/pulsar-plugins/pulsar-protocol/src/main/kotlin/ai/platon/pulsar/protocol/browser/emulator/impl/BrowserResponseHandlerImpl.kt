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

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.browser.BrowserErrorCode
import ai.platon.pulsar.common.config.CapabilityTypes.PARSE_SUPPORT_ALL_CHARSETS
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.event.AbstractEventEmitter
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.crawl.fetch.FetchTask
import ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserErrorPageException
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.protocol.Response
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.RetryScope
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.persist.model.ActiveDOMMessage
import ai.platon.pulsar.protocol.browser.emulator.*
import ai.platon.pulsar.protocol.browser.emulator.util.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

open class BrowserResponseHandlerImpl(
        private val immutableConfig: ImmutableConfig
): BrowserResponseHandler, AbstractEventEmitter<BrowserResponseEvents>() {
    protected val logger = LoggerFactory.getLogger(BrowserResponseHandlerImpl::class.java)!!
    protected val tracer = logger.takeIf { it.isTraceEnabled }
    protected val supportAllCharsets get() = immutableConfig.getBoolean(PARSE_SUPPORT_ALL_CHARSETS, true)
    protected val charsetPattern = if (supportAllCharsets) SYSTEM_AVAILABLE_CHARSET_PATTERN else DEFAULT_CHARSET_PATTERN

    private val registry = MetricsSystem.defaultMetricRegistry
    protected val pageSourceBytes by lazy { registry.meter(this, "pageSourceBytes") }
    protected val wrongProfile by lazy { registry.meter(this, "wrongProfile") }
    protected val bannedPages by lazy { registry.meter(this, "bannedPages") }
    protected val notFoundPages by lazy { registry.meter(this, "notFoundPages") }
    protected val missingFieldPages by lazy { registry.meter(this, "missingFieldPages") }

    protected val numNavigates = AtomicInteger()
    protected val smallPages by lazy { registry.meter(this, "smallPages") }
    protected val smallPageRate get() = 100 * smallPages.count / numNavigates.get()
    protected val smallPageRateHistogram by lazy { registry.histogram(this, "smallPageRate") }
    protected val emptyPages by lazy { registry.meter(this, "emptyPages") }

    override val pageCategorySniffer = ChainedPageCategorySniffer(immutableConfig).apply {
        addLast(DefaultPageCategorySniffer(immutableConfig))
    }

    override val htmlIntegrityChecker = ChainedHtmlIntegrityChecker(immutableConfig).apply {
        addLast(DefaultHtmlIntegrityChecker(immutableConfig))
    }

    init {
        attach()
    }

    override fun onInitPageCategorySniffer(sniffer: PageCategorySniffer) {
        pageCategorySniffer.addLast(sniffer)
    }

    override fun onInitHTMLIntegrityChecker(checker: HtmlIntegrityChecker) {
        htmlIntegrityChecker.addLast(checker)
    }

    override fun onWillCreateResponse(task: FetchTask, driver: WebDriver) {
        numNavigates.incrementAndGet()
    }

    override fun onResponseCreated(task: FetchTask, driver: WebDriver, response: Response) {

    }

    override fun checkErrorPage(page: WebPage, status: ProtocolStatus): ProtocolStatus {
        return status
    }

    /**
     * Normalize the page source.
     *
     * The browser has already converted source code to be UTF-8, so we replace the charset meta tags to be UTF-8.
     * TODO: or we insert a new metadata to indicate the charset
     */
    override fun normalizePageSource(url: String, pageSource: String): StringBuilder {
        val sb = HtmlUtils.replaceHTMLCharset(pageSource, charsetPattern, "UTF-8")
        return sb
    }

    override fun onBrowseTimeout(task: NavigateTask) {
        if (logger.isInfoEnabled) {
            val elapsed = Duration.between(task.startTime, Instant.now())
            val length = task.pageSource.length
            val link = AppPaths.uniqueSymbolicLinkForUri(task.page.url)

            val settings = task.driver.browser.settings.interactSettings
            logger.info(
                "Timeout ({}) after {} with {} timeouts: {}/{}/{} | file://{}",
                task.pageDatum.protocolStatus.minorName,
                elapsed,
                Strings.compactFormat(length),
                settings.pageLoadTimeout, settings.scriptTimeout, settings.scrollInterval,
                link)
        }
    }

    /**
     * Chrome redirected to the error page chrome-error://
     * This page should be text analyzed to determine the actual error.
     * */
    override fun createBrowserErrorResponse(message: String): BrowserErrorResponse {
        val activeDomMessage = ActiveDOMMessage.fromJson(message)
        val ec = activeDomMessage.trace?.status?.ec
        if (ec == null) {
            val status = ProtocolStatus.retry(RetryScope.PRIVACY, "Unknown error, no message")
            return BrowserErrorResponse(status, activeDomMessage)
        }

        val error = BrowserErrorCode.valueOfOrUnknown(ec)
        val exception = BrowserErrorPageException(error)
        val status = ProtocolStatus.retry(RetryScope.PRIVACY, exception)
        if (error.isUnknown()) {
            logger.info("Undocumented browser error $ec")
        }

        return BrowserErrorResponse(status, activeDomMessage)
    }

    override fun createProtocolStatusForBrokenContent(task: FetchTask, htmlIntegrity: HtmlIntegrity): ProtocolStatus {
        // RetryScope.PRIVACY requires a privacy context leak warning while RetryScope.CRAWL does not
        return when {
            // should cancel all running tasks and reset the privacy context and then re-fetch them
            htmlIntegrity.isRobotCheck || htmlIntegrity.isRobotCheck2 || htmlIntegrity.isRobotCheck3 ->
                ProtocolStatus.retry(RetryScope.PRIVACY, htmlIntegrity).also { bannedPages.mark() }
            htmlIntegrity.isWrongProfile ->
                ProtocolStatus.retry(RetryScope.CRAWL, htmlIntegrity).also { wrongProfile.mark() }
            htmlIntegrity.isForbidden -> ProtocolStatus.retry(RetryScope.PRIVACY, htmlIntegrity).also { bannedPages.mark() }
            htmlIntegrity.isNotFound -> ProtocolStatus.failed(ProtocolStatusCodes.SC_NOT_FOUND).also { notFoundPages.mark() }
            // must come after privacy context reset, PRIVACY_CONTEXT reset have the higher priority
            htmlIntegrity.isEmpty -> ProtocolStatus.retry(RetryScope.PRIVACY, htmlIntegrity).also { emptyPages.mark() }
            htmlIntegrity.isSmall -> ProtocolStatus.retry(RetryScope.CRAWL, htmlIntegrity).also {
                smallPages.mark()
                smallPageRateHistogram.update(smallPageRate)
            }
            htmlIntegrity.hasMissingField -> ProtocolStatus.retry(RetryScope.CRAWL, htmlIntegrity)
                .also { missingFieldPages.mark() }
            else -> ProtocolStatus.retry(RetryScope.CRAWL, htmlIntegrity)
        }
    }

    private fun attach() {
        on(BrowserResponseEvents.initPageCategorySniffer) { sniffer: PageCategorySniffer ->
            this.onInitPageCategorySniffer(sniffer)
        }
        on(BrowserResponseEvents.initHTMLIntegrityChecker) { checker: HtmlIntegrityChecker ->
            this.onInitHTMLIntegrityChecker(checker)
        }
        on(BrowserResponseEvents.willCreateResponse) { task: FetchTask, driver: WebDriver ->
            this.onWillCreateResponse(task, driver)
        }
        on(BrowserResponseEvents.responseCreated) { task: FetchTask, driver: WebDriver, response: Response ->
            this.onResponseCreated(task, driver, response)
        }
        on(BrowserResponseEvents.browseTimeout) { task: NavigateTask ->
            this.onBrowseTimeout(task)
        }
    }

    private fun detach() {
        BrowserResponseEvents.entries.forEach { off(it) }
    }
}
