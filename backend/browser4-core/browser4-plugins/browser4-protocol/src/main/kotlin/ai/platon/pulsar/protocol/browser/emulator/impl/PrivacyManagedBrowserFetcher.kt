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

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.stringify
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.protocol.browser.emulator.AbstractBrowserFetcher
import ai.platon.pulsar.protocol.browser.emulator.BrowserEmulator
import ai.platon.pulsar.protocol.browser.emulator.IncognitoBrowserFetcher
import ai.platon.pulsar.protocol.browser.emulator.context.BrowserPrivacyManager
import ai.platon.pulsar.skeleton.common.persist.ext.browseEventHandlers
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.workflow.fetch.FetchResult
import ai.platon.pulsar.skeleton.workflow.fetch.FetchTask
import ai.platon.pulsar.skeleton.workflow.fetch.WebDriverFetcher
import ai.platon.pulsar.skeleton.workflow.fetch.driver.*
import ai.platon.pulsar.skeleton.workflow.protocol.ForwardingResponse
import ai.platon.pulsar.skeleton.workflow.protocol.Response
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by Vincent on 18-1-1.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 */
open class BrowserWebDriverFetcher(
    val browserEmulator: BrowserEmulator,
    val conf: ImmutableConfig
) : WebDriverFetcher {
    private val logger = LoggerFactory.getLogger(BrowserWebDriverFetcher::class.java)!!

    enum class EventType {
        willFetch,
        fetched
    }

    @Throws(Exception::class)
    override suspend fun fetchDeferred(url: String, driver: WebDriver): FetchResult {
        return fetchDeferred(FetchTask.create(url, conf.toVolatileConfig()), driver)
    }

    @Throws(Exception::class)
    override suspend fun fetchDeferred(task: FetchTask, driver: WebDriver): FetchResult {
        emit(EventType.willFetch, task.page, driver)

        val result = try {
            browserEmulator.visit(task, driver)
        } catch (e: IllegalWebDriverStateException) {
            logger.warn("Illegal webdriver, cancel the task | {}", e.message)
            FetchResult.canceled(task)
        }

        emit(EventType.fetched, task.page, driver)

        return result
    }

    private suspend fun emit(type: EventType, page: WebPage, driver: WebDriver) {
        val event = page.browseEventHandlers ?: return
        when (type) {
            EventType.willFetch -> notify(type.name) { event.onWillFetch(page, driver) }
            EventType.fetched -> notify(type.name) { event.onFetched(page, driver) }
        }
    }

    private suspend fun notify(name: String, action: suspend () -> Unit) {
        val e = kotlin.runCatching { action() }.exceptionOrNull()

        if (e != null) {
            handleEventException(name, e)
        }
    }

    private fun handleEventException(name: String, e: Throwable) {
        when (e) {
            is WebDriverCancellationException -> logger.info("Web driver is cancelled")
            is WebDriverException -> logger.warn(e.brief("[Ignored][$name] "))
            is Exception -> logger.warn(e.brief("[Ignored][$name] "))
            else -> logger.error(e.stringify("[Unexpected][$name] "))
        }
    }
}

/**
 * Created by Vincent on 18-1-1.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 */
open class PrivacyManagedBrowserFetcher constructor(
    val browserManager: BrowserManager,
    override val privacyManager: BrowserPrivacyManager,
    override val browserEmulator: BrowserEmulator,
    override val conf: ImmutableConfig,
    private val closeCascaded: Boolean = false
) : AbstractBrowserFetcher(), IncognitoBrowserFetcher {
    private val logger = LoggerFactory.getLogger(PrivacyManagedBrowserFetcher::class.java)!!

    override val webdriverFetcher by lazy { BrowserWebDriverFetcher(browserEmulator, conf) }

    private val closed = AtomicBoolean()
    private val illegalState = AtomicBoolean()
    override val isActive get() = !illegalState.get() && !closed.get() && AppContext.isActive

    /**
     * Fetch page content
     * */
    @Throws(Exception::class)
    override suspend fun fetchContentDeferred(page: WebPage): Response {
        if (!isActive) {
            return ForwardingResponse.canceled(page)
        }

        if (page.isInternal) {
            logger.warn("Unexpected internal page | {}", page.url)
            return ForwardingResponse.canceled(page)
        }

        val task = FetchTask.create(page)
        return fetchDeferred(task)
    }

    /**
     * Fetches the content of a web page asynchronously using the provided fetch task.
     *
     * This method attempts to retrieve a web driver for the given page. If a specific driver is found,
     * it is used to fetch the page content. Otherwise, the task is executed within a privacy context
     * using the `PrivacyManager` to obtain a driver.
     *
     * @param task The fetch task containing the details of the page to be fetched.
     * @return A `Response` object containing the result of the fetch operation.
     * @throws Exception If an error occurs during the fetch process.
     */
    @Throws(Exception::class)
    suspend fun fetchDeferred(task: FetchTask): Response {
        // Attempt to get a specified web driver for the page
        val driver = getWebDriver(task.page)
        if (driver != null) {
            // Use the specified driver to fetch the page content
            return webdriverFetcher.fetchDeferred(task, driver).response
        }

        // If no driver is specified, execute the task within a privacy context
        // The PrivacyManager will provide a driver for the task
        return privacyManager.run(task) { _, driver2 ->
            webdriverFetcher.fetchDeferred(task, driver2)
        }.response
    }

    override fun reset() {
    }

    override fun cancel(page: WebPage) {
    }

    override fun cancelAll() {
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            if (closeCascaded) {
                browserEmulator.close()
                privacyManager.close()
            }
        }
    }

    /**
     * Get the web driver for the given page.
     * * If the page has a specified web driver, it will be used.
     * * If the page has a specified browser, a random web driver from that browser will be used.
     * * If neither is specified, the PrivacyManager will be used to get a web driver.
     * */
    private fun getWebDriver(page: WebPage): WebDriver? {
        val driver = getSpecifiedWebDriver(page)
        if (driver != null) {
            return driver
        }

        val browser = getSpecifiedBrowser(page) ?: return null

        // Only when the browser is specified, we can get a random driver, otherwise,
        // we will use the PrivacyManager to get a driver
        return getRandomWebDriver(browser)
    }

    /**
     * Get the specified web driver.
     *
     * You can set the web driver using the following ways:
     *
     * ```kotlin
     * session.bindDriver(driver)
     * ```
     *
     * Or internally:
     *
     * ```kotlin
     * page.putBean(driver)
     * session.sessionConfig.putBean(driver)
     * ```
     *
     * @param page The page to fetch
     * */
    private fun getSpecifiedWebDriver(page: WebPage): WebDriver? {
        require(page is AbstractWebPage)
        // Specified driver is always used
        val driver = page.getBeanOrNull(WebDriver::class.java)
            ?: page.conf.getBeanOrNull(WebDriver::class) // page.conf data inherits from session.sessionConfig
            ?: page.conf.getBeanOrNull(WebDriver::class.java)
        return driver as? WebDriver
    }

    private fun getSpecifiedBrowser(page: WebPage): Browser? {
        val browser = page.getBeanOrNull(Browser::class.java)
            ?: page.conf.getBeanOrNull(Browser::class)
            ?: page.conf.getBeanOrNull(Browser::class.java)
        return browser as? Browser
    }

    private fun getRandomWebDriver(browser: Browser): WebDriver {
        val drivers = browser.drivers.values
        if (drivers.isEmpty()) {
            return browser.newDriver()
        }

        return drivers.shuffled().firstOrNull() ?: browser.newDriver()
    }
}
