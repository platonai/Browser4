package ai.platon.pulsar.heavy

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.collect.UrlFeeder
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.sleepSeconds
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.TaskLoop
import ai.platon.pulsar.skeleton.workflow.common.url.ListenableHyperlink
import ai.platon.pulsar.skeleton.browser.driver.AbstractWebDriver
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Test the performance of Browser4, every test url will be a local file, so the performance is not affected by network latency.
 *
 * The test first generate 10000 temporary files in the local file system, and then run the test.
 *
 * Notice: before we load the local files using Browser4, we have to transform the paths using [URLUtils.pathToLocalURL].
 * */
@Tag("HeavyTest")
class BrowserRotationTest : MassiveTestBase() {

    companion object {
        @JvmStatic
        @BeforeAll
        fun initPulsarSettings() {
            PulsarSettings.maxBrowserContexts(4).maxOpenTabs(8)
            // PulsarSettings.withTemporaryBrowser()
        }
    }

    @BeforeEach
    fun checkPreconditions() {
        Assumptions.assumeTrue { testFileCount > 0 }
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterEach
    fun deleteTemporaryContexts() {
        kotlin.runCatching { AppPaths.LOCAL_STORAGE_DIR.resolve("localfile-org").deleteRecursively() }
    }

    @Test
    fun testWithSequentialBrowser() {
        Assumptions.assumeTrue { testFileCount > 0 }
        PulsarSettings.withSequentialBrowsers()
        runAndAwait()
    }

    @Test
    fun testWithTemporaryBrowser() {
        Assumptions.assumeTrue { testFileCount > 0 }
        PulsarSettings.withTemporaryBrowser()
        runAndAwait()
    }

    private fun runAndAwait() {
        if (testFileCount == 0) {
            printlnPro("Skip the test since testFileCount is 0")
            return
        }

        Assumptions.assumeTrue { testFileCount > 0 }
        val links = testPaths.asSequence().map { URLUtils.pathToLocalURL(it) }.map { createHyperlink(it) }.toList()

        links.forEach {
            session.delete(it.url)
            session.submit(it, "-refresh -dropContent")
        }

        session.context.await()
        val feeder = session.context.getBean(TaskLoop::class).urlFeeder as UrlFeeder
        while (!Thread.currentThread().isInterrupted && feeder.isNotEmpty()) {
            sleepSeconds(1)
        }
        session.context.await()
    }

    private fun createHyperlink(url: String): ListenableHyperlink {
        val link = ListenableHyperlink(url, "")
        val le = link.eventHandlers.loadEventHandlers
        val be = link.eventHandlers.browseEventHandlers

        le.onLoaded.addLast { page ->
            page.protocolStatus = ProtocolStatus.STATUS_NOTFETCHED
            page.fetchRetries = 0
            page.retryDelay = 15.seconds.toJavaDuration()
            null
        }

        le.onWillFetch.addLast { page ->
            page.protocolStatus = ProtocolStatus.STATUS_NOTFETCHED
            page.fetchRetries = 0
            page.retryDelay = 15.seconds.toJavaDuration()
//            page.maxRetries = 2
//            page.fetchRetries = 0
            null
        }

        be.onDocumentFullyLoaded.addLast { page, driver ->
            val text = driver.selectFirstTextOrNull("body")
            // check text
            delay(3000)
        }

        be.onDidInteract.addLast { page, driver ->
            require(driver is AbstractWebDriver)
            val browser = driver.browser
            val size = browser.navigateHistory.size
            val readableState = browser.readableState
            val display = browser.id.display
            if (size >= 30) {
                printlnPro("Closing browser #$display, served $size pages | $readableState | ${browser.id.contextDir}")
                browser.close()
            }
        }

        return link
    }
}
