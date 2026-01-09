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

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.CapabilityTypes.FETCH_MAX_CONTENT_LENGTH
import ai.platon.pulsar.common.config.CapabilityTypes.FETCH_PAGE_AUTO_EXPORT_LIMIT
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.common.event.AbstractEventEmitter
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.persist.PageDatum
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.protocol.browser.emulator.*
import ai.platon.pulsar.skeleton.common.files.ext.export
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.crawl.fetch.FetchTask
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriverCancellationException
import ai.platon.pulsar.skeleton.crawl.protocol.ForwardingResponse
import ai.platon.pulsar.skeleton.crawl.protocol.Response
import kotlinx.coroutines.delay
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.lang3.SystemUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

abstract class BrowserEmulatorImplBase(
    /**
     * Handle the response
     * */
    val responseHandler: BrowserResponseHandler,
    /**
     * The configuration of the emulator
     * */
    val immutableConfig: ImmutableConfig
) : AbstractEventEmitter<EmulateEvents>(), Parameterized, AutoCloseable {
    private val logger = getLogger(BrowserEmulatorImplBase::class)
    private val tracer = logger.takeIf { it.isTraceEnabled }

    val supportAllCharsets get() = immutableConfig.getBoolean(CapabilityTypes.PARSE_SUPPORT_ALL_CHARSETS, true)
    val charsetPattern = if (supportAllCharsets) SYSTEM_AVAILABLE_CHARSET_PATTERN else DEFAULT_CHARSET_PATTERN

    /**
     * The maximum length of the page source, 8M by default.
     * */
    protected val maxPageSourceLength = immutableConfig.getInt(FETCH_MAX_CONTENT_LENGTH, 8 * FileUtils.ONE_MB.toInt())

    private val registry = MetricsSystem.reg
    protected val pageSourceByteHistogram by lazy { registry.histogram(this, "hPageSourceBytes") }
    protected val pageSourceBytes by lazy { registry.meter(this, "pageSourceBytes") }

    protected val meterNavigates by lazy { registry.meter(this, "navigates") }
    protected val counterJsEvaluates by lazy { registry.counter(this, "jsEvaluates") }
    protected val counterJsWaits by lazy { registry.counter(this, "jsWaits") }
    protected val counterCancels by lazy { registry.counter(this, "cancels") }

    protected val closed = AtomicBoolean(false)

    /**
     * Whether the emulator is active.
     * */
    val isActive get() = !closed.get() && AppContext.isActive

    /**
     * Approximate number of exported web pages.
     * */
    private var exportCount = 0

    /**
     * Create a response for the task.
     * */
    open fun createResponse(task: NavigateTask): Response {
        if (!isActive) {
            return ForwardingResponse.canceled(task.page)
        }

        val pageDatum = task.pageDatum
        val length = task.pageSource.length

        pageDatum.pageCategory = responseHandler.pageCategorySniffer(pageDatum)
        pageDatum.protocolStatus = responseHandler.checkErrorPage(task.page, pageDatum.protocolStatus)
        pageDatum.lastBrowser = task.driver.browserType
        if (!pageDatum.protocolStatus.isSuccess) {
            // TODO: check the logic, protocolStatus might be set to failure not because of the browser's error page
            // The browser shows internal error page, which is no value to store
            task.pageSource = ""
            return createResponseWithDatum(task, pageDatum)
        }

        val isLocalFile = URLUtils.isLocalFile(task.url)
        val ignoreDOMFeatures = isLocalFile || (task.driver as AbstractWebDriver).ignoreDOMFeatures
        // Check whether the source code of the page is intact.
        val integrity = if (ignoreDOMFeatures) HtmlIntegrity.OK else
            responseHandler.htmlIntegrityChecker(task.pageSource, task.pageDatum)

        // Check browse timeout event, transform status to be success if the page source is good
        if (!isLocalFile && pageDatum.protocolStatus.isTimeout) {
            if (integrity.isOK) {
                // fetch timeout but content is OK
                pageDatum.protocolStatus = ProtocolStatus.STATUS_SUCCESS
            }
            responseHandler.emit(BrowserResponseEvents.browseTimeout)
        }

        pageDatum.headers.put(HttpHeaders.CONTENT_LENGTH, length.toString())
        if (integrity.isOK) {
            // Update page source, modify charset directive, do the caching stuff
            task.pageSource = responseHandler.normalizePageSource(task.url, task.pageSource).toString()

            pageSourceByteHistogram.update(length)
            pageSourceBytes.mark(length.toLong())
        } else {
            // The page seems to be broken, retry it
            pageDatum.protocolStatus = responseHandler.createProtocolStatusForBrokenContent(task.fetchTask, integrity)
            logBrokenPage(task.fetchTask, task.pageSource, integrity)
        }

        pageDatum.apply {
            lastBrowser = task.driver.browserType
            htmlIntegrity = integrity
            originalContentLength = task.originalContentLength
            content = task.pageSource.toByteArray(StandardCharsets.UTF_8)
        }

        // Update headers, metadata, do the logging stuff
        return createResponseWithDatum(task, pageDatum)
    }

    /**
     * Create a response with the page datum.
     * */
    open fun createResponseWithDatum(task: NavigateTask, pageDatum: PageDatum): ForwardingResponse {
        val headers = pageDatum.headers

        // The page content's encoding is already converted to UTF-8 by Web driver
        val utf8 = StandardCharsets.UTF_8.name()
        require(utf8 == "UTF-8") { "UTF-8 is expected" }

        headers.put(HttpHeaders.CONTENT_ENCODING, utf8)
        headers.put(HttpHeaders.Q_TRUSTED_CONTENT_ENCODING, utf8)
        headers.put(HttpHeaders.Q_RESPONSE_TIME, System.currentTimeMillis().toString())

        val urls = pageDatum.activeDOMUrls
        if (urls != null) {
            pageDatum.baseURI = urls.baseURI
            pageDatum.location = urls.location
            if (pageDatum.url != pageDatum.location) {
                // in-browser redirection
                // messageWriter?.debugRedirects(pageDatum.url, urls)
            }
        }

        val driver = task.driver as AbstractWebDriver
        if (!driver.isMockedPageSource) {
            exportIfNecessary(task)
        }

        return ForwardingResponse(task.page, pageDatum)
    }

    /**
     * Close the emulator.
     * */
    override fun close() {
        if (closed.compareAndSet(false, true)) {

        }
    }

    @Throws(NavigateTaskCancellationException::class)
    protected fun checkState() {
        if (!isActive) {
            throw NavigateTaskCancellationException("Emulator was closed")
        }
    }

    /**
     * Check the task state.
     * */
    @Throws(NavigateTaskCancellationException::class)
    protected fun checkState(driver: WebDriver) {
        checkState()

        require(driver is AbstractWebDriver)
        if (driver.isCanceled) {
            // the task is canceled, so the navigation is stopped, the driver is closed, the privacy context is reset
            // and all the running tasks should be redo
            throw WebDriverCancellationException("Web driver is canceled #${driver.id}", driver = driver)
        }
    }

    /**
     * Check the task state.
     * */
    @Throws(NavigateTaskCancellationException::class, WebDriverCancellationException::class)
    protected fun checkState(task: FetchTask, driver: WebDriver) {
        checkState()

        require(driver is AbstractWebDriver)
        if (driver.isCanceled) {
            // the task is canceled, so the navigation is stopped, the driver is closed, the privacy context is reset
            // and all the running tasks should run again.
            throw WebDriverCancellationException("Web driver is canceled #${driver.id}", driver = driver)
        }

        if (task.isCanceled) {
            // the task is canceled, so the navigation is stopped, the driver is closed, the privacy context is reset
            // and all the running tasks should run again.
            throw NavigateTaskCancellationException("Task #${task.batchTaskId}/${task.batchId} is canceled | ${task.url}")
        }
    }

    protected fun logBeforeNavigate(task: FetchTask, browserSettings: BrowserSettings) {
        if (logger.isTraceEnabled) {
            val settings = browserSettings.interactSettings
            logger.trace(
                "Navigate {}/{}/{} in [t{}]{} | {} | timeouts: {}/{}/{}",
                task.batchTaskId, task.batchSize, task.id,
                Thread.currentThread().id,
                if (task.nRetries <= 1) "" else "(${task.nRetries})",
                task.page.configuredUrl,
                settings.pageLoadTimeout, settings.scriptTimeout, settings.scrollInterval
            )
        }
    }

    /**
     * Preprocess page content.
     * */
    protected fun preprocessPageContent(content: String?): String {
        if (content == null) {
            return ""
        }

        val length = content.length
        if (length > maxPageSourceLength) {
            /**
             * Issue #43: OutOfMemoryError: Java heap space from BrowserEmulatorImplBase.createResponse,
             * caused by normalizePageSource().toString()
             * **/
            logger.warn("Too large page source: {}, truncate it to empty", Strings.compactFormat(length))
            return ""
        }

        return content
    }

    /**
     * Export the page if one of the following condition matches:
     * 1. The page is failed to fetch
     * 2. FETCH_MAX_EXPORT_COUNT > 0 and the export count is less than FETCH_MAX_EXPORT_COUNT
     * */
    private fun exportIfNecessary(task: NavigateTask) {
        try {
            exportIfNecessary0(task.pageSource, task.pageDatum.protocolStatus, task.page)
        } catch (e: Exception) {
            logger.warn("Failed to export webpage | {} | \n{}", task.url, e.stringify())
        }
    }

    /**
     * Export the page if one of the following condition matches:
     * 1. The page is failed to fetch
     * 2. FETCH_MAX_EXPORT_COUNT > 0 and the export count is less than FETCH_MAX_EXPORT_COUNT
     * */
    private fun exportIfNecessary0(pageSource: String, status: ProtocolStatus, page: WebPage) {
        if (logger.isInfoEnabled && !status.isSuccess) {
            export0(pageSource, status, page)
            return
        }

        if (pageSource.isEmpty()) {
            return
        }

        val maxExportCountPerRun = immutableConfig.getInt(FETCH_PAGE_AUTO_EXPORT_LIMIT, 2000)
        if (++exportCount < maxExportCountPerRun) {
            val path = export0(pageSource, status, page)
            val baseDir = path.parent

            if (exportCount % 100 == 0) {
                // check every 100 pages, so do not cost too much time
                val count = Files.list(baseDir).count()
                if (count > maxExportCountPerRun) {
                    // if there are too many files, move them to a new directory
                    val date = DateTimes.now("yyyyMMdd")
                    val ident = RandomStringUtils.secure().nextAlphanumeric(4)
                    val dest = baseDir.resolveSibling(baseDir.fileName.toString() + ".$date.$ident")
                    Files.move(baseDir, dest, StandardCopyOption.ATOMIC_MOVE)
                }
            }
        }
    }

    private fun export0(pageSource: String, status: ProtocolStatus, page: WebPage): Path {
        val path = AppFiles.export(status, pageSource, page)

        if (SystemUtils.IS_OS_WINDOWS) {
            // TODO: Issue 16 - https://github.com/platonai/browser4/issues/16
            // Not a good idea to create symbolic link on Windows, it requires administrator privilege
        } else {
            createSymbolicLink(path, page)
        }

        return path
    }

    private fun createSymbolicLink(path: Path, page: WebPage) {
        // Create a symbolic link with a url based, unique, shorter but less readable file name,
        // we can generate and refer to this path at any place
        val link = AppPaths.uniqueSymbolicLinkForUri(page.url)
        try {
            Files.deleteIfExists(link)
            AppFiles.createSymbolicLink(link, path)
        } catch (e: IOException) {
            logger.warn(e.toString())
        }
    }

    private fun logBrokenPage(task: FetchTask, pageSource: String, integrity: HtmlIntegrity) {
        if (!isActive) {
            return
        }

        val proxyEntry = task.proxyEntry
        val domain = task.domain
        val link = AppPaths.uniqueSymbolicLinkForUri(task.url)
        val readableLength = Strings.compactFormat(pageSource.length)

        if (proxyEntry != null) {
            val count = proxyEntry.servedDomains.count(domain)
            logger.warn(
                "{}. Page is {}({}) with {} in {}({}) | file://{}",
                task.page.id,
                integrity.name, readableLength,
                proxyEntry.display, domain, count, link
            )
        } else {
            logger.warn(
                "{}. Page is {}({}) | file://{} | {}",
                task.page.id, integrity.name, readableLength, link, task.url
            )
        }
    }

    protected suspend fun evaluate(
        interactTask: InteractTask, expressions: Iterable<String>, delayMillis: Long,
        bringToFront: Boolean = false, verbose: Boolean = false
    ) {
        expressions.asSequence()
            .mapNotNull { it.trim().takeIf { it.isNotBlank() } }
            .filterNot { it.startsWith("// ") }
            .filterNot { it.startsWith("# ") }
            .forEachIndexed { i, expression ->
                if (bringToFront && i % 2 == 0) {
                    interactTask.driver.bringToFront()
                }

                evaluate(interactTask, expression, verbose)
                delay(delayMillis)
            }
    }

    protected suspend fun evaluate(
        interactTask: InteractTask, expression: String, verbose: Boolean
    ): Any? {
        logger.takeIf { verbose }?.info("Evaluate expression >>>$expression<<<")
        val value = evaluate(interactTask, expression)
        if (value is String) {
            val s = Strings.removeNonPrintableChar(value)
            logger.takeIf { verbose }?.info("Result >>>$s<<<")
        } else if (value is Int || value is Long) {
            logger.takeIf { verbose }?.info("Result >>>$value<<<")
        }
        return value
    }

    @Throws(WebDriverCancellationException::class)
    protected suspend fun evaluate(interactTask: InteractTask, expression: String, delayMillis: Long = 0): Any? {
        if (!isActive) return null

        counterJsEvaluates.inc()
        checkState(interactTask.navigateTask.fetchTask, interactTask.driver)
        val result = interactTask.driver.evaluate(expression)
        if (delayMillis > 0) {
            delay(delayMillis)
        }
        return result
    }
}
