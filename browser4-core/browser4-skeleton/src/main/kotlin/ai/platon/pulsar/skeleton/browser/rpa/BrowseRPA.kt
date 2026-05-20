package ai.platon.pulsar.skeleton.browser.rpa

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.CheckState
import ai.platon.pulsar.common.Runtimes
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.browser.driver.AbstractWebDriver
import ai.platon.pulsar.skeleton.browser.driver.NavigateEntry
import ai.platon.pulsar.skeleton.browser.driver.WebDriver
import ai.platon.pulsar.persist.WebPage
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

interface BrowseRPA {
    suspend fun warnUpBrowser(page: WebPage, driver: WebDriver)
    suspend fun waitForReferrer(page: WebPage, driver: WebDriver)
    suspend fun waitForPreviousPage(page: WebPage, driver: WebDriver)
    suspend fun visit(url: String, driver: WebDriver)
}

open class DefaultBrowseRPA: BrowseRPA {
    companion object {
        const val PREV_PAGE_WILL_READY = 0
        const val PREV_PAGE_READY = 1
        const val PREV_PAGE_NEVER_READY = 2
    }

    private val isActive get() = AppContext.isActive
    private val logger = getLogger(this)

    override suspend fun warnUpBrowser(page: WebPage, driver: WebDriver) {
        page.referrer?.let { visit(it, driver) }
    }

    override suspend fun waitForReferrer(page: WebPage, driver: WebDriver) {
        val referrer = page.referrer ?: return
        val referrerVisited = driver.browser.navigateHistory.contains(referrer)
        if (!referrerVisited) {
            logger.debug("Visiting the referrer | {}", referrer)
            visit(referrer, driver)
        }
    }

    override suspend fun waitForPreviousPage(page: WebPage, driver: WebDriver) {
        var tick = 0
        var checkState = checkPreviousPage(driver)
        while (tick++ <= 180 && checkState.code == PREV_PAGE_WILL_READY) {
            if (checkState.message.isBlank()) {
                // No previous page, the browser has just started, don't crowd into.
                Runtimes.randomDelay(1_000, 10_000)
                break
            }

            // The last page does not load completely, wait for it.
            val shouldReport = (tick > 150 && tick % 10 == 0)
            if (shouldReport) {
                val urlToWait = checkState.message
                logger.info("Waiting for page | {} | {} <- {}", tick, urlToWait, page.url)
            }

            delay(1000L)
            checkState = checkPreviousPage(driver)
        }
    }

    override suspend fun visit(url: String, driver: WebDriver) {
        val display = driver.browser.id.display
        logger.info("Visiting with browser #{} | {}", display, url)

        driver.navigate(url)
        driver.waitForSelector("body")
        var n = 2 + Random.nextInt(5)
        while (n-- > 0 && isActive) {
            val deltaY = 100.0 + 20 * Random.nextInt(10)
            driver.mouseWheel(deltaY = deltaY)
            Runtimes.randomDelay(500, 500)
        }

        logger.debug("Visited | {}", url)
    }

    private fun checkPreviousPage(driver: WebDriver): CheckState {
        val navigateHistory = driver.browser.navigateHistory
        val now = Instant.now()

        val testNav = navigateHistory.history.lastOrNull { mayWaitFor(it, driver.navigateEntry) }

        require(driver is AbstractWebDriver)
        val code = when {
            !isActive -> PREV_PAGE_NEVER_READY
            !driver.isWorking -> PREV_PAGE_NEVER_READY
            testNav == null -> PREV_PAGE_WILL_READY
            testNav.documentReadyTime > now -> PREV_PAGE_WILL_READY
            Duration.between(testNav.documentReadyTime, now).seconds > 10 -> PREV_PAGE_READY
            Duration.between(testNav.lastActiveTime, now).seconds > 60 -> PREV_PAGE_NEVER_READY
            else -> PREV_PAGE_WILL_READY
        }

        return CheckState(code, testNav?.url ?: "")
    }

    private fun mayWaitFor(currentEntry: NavigateEntry, testEntry: NavigateEntry): Boolean {
        val now = Instant.now()

        val may = testEntry.pageId > 0
                && !testEntry.stopped
                && testEntry.createTime < currentEntry.createTime
                && Duration.between(testEntry.lastActiveTime, now).seconds < 30

        return may
    }
}
