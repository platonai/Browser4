package ai.platon.pulsar.browser

import ai.platon.browser4.driver.chrome.RemoteDevTools
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import ai.platon.pulsar.skeleton.workflow.fetch.driver.AbstractWebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.slf4j.LoggerFactory
import java.text.MessageFormat
import kotlin.test.*

class PulsarWebDriverCDPTests : WebDriverTestBase() {
    fun setLogLevel(loggerName: String?, level: Level?) {
        val targetLogger: Logger = LoggerFactory.getLogger(loggerName) as Logger
        targetLogger.level = level
    }

    private val browserLoggerName = "ai.platon.pulsar.protocol.browser"
    private val chromeLoggerName = "ai.platon.browser4.driver.chrome"
    private val transportLoggerName = "ai.platon.browser4.driver.chrome.impl"
    private val testURL get() = "$generatedAssetsBaseURL/interactive-4.html"

    fun increasesLogLevels() {
        setLogLevel(browserLoggerName, Level.TRACE)
        setLogLevel(chromeLoggerName, Level.TRACE)
        setLogLevel(transportLoggerName, Level.TRACE)
    }

    fun resetLogs() {
        setLogLevel(browserLoggerName, Level.INFO)
        setLogLevel(chromeLoggerName, Level.INFO)
        setLogLevel(transportLoggerName, Level.INFO)
    }

    @BeforeEach
    fun setup() {
        increasesLogLevels()
    }

    @AfterEach
    fun tearDown() {
        resetLogs()
    }

    @Test
    @Ignore("Disabled temporarily")
    fun whenNavigateAHtmlPageThenTheNavigateStateAreCorrect() = runEnhancedWebDriverTest(browser) { driver ->
        openEnhanced(interactiveUrl, driver, 1)

        val navigateEntry = driver.navigateEntry
        assertTrue("Expect mainFrameReceived") { navigateEntry.mainFrameReceived }
        assertTrue { navigateEntry.networkRequestCount.get() > 0 }
        assertTrue { navigateEntry.networkResponseCount.get() > 0 }

        require(driver is AbstractWebDriver)
        assertEquals(200, driver.mainResponseStatus)
        assertTrue { driver.mainResponseStatus == 200 }
        assertTrue { driver.mainResponseHeaders.isNotEmpty() }
        assertEquals(200, navigateEntry.mainResponseStatus)
        assertTrue { navigateEntry.mainResponseStatus == 200 }
        assertTrue { navigateEntry.mainResponseHeaders.isNotEmpty() }
    }

    @Test
    @DisplayName("test evaluate")
    fun testEvaluate() = runEnhancedWebDriverTest(testURL, browser) { driver ->
        val code = """1+1"""

        val result = driver.evaluate(code)
        assertEquals(2, result)
    }

    @Test
    @DisplayName("test DOM event")
    fun testDomEvent() = runWebDriverDOMEventTest(testURL, browser) { driver ->
        assertIs<PulsarWebDriver>(driver)

        val code = """1+1"""
        val result = driver.evaluate(code)
        assertEquals(2, result)
    }

    private fun runWebDriverDOMEventTest(url: String, browser: Browser, block: suspend (WebDriver) -> Unit) {
        runBlocking {
            browser.newDriver().use { driver ->
                assertIs<PulsarWebDriver>(driver)

                val devTools = driver.implementation as RemoteDevTools

                devTools.dom.onAttributeModified { e ->
                    val message = MessageFormat.format("> {0}. node changed | {1} := {2}", e.nodeId, e.name, e.value)
                    printlnPro(message)
                }

                devTools.console.enable()
                devTools.console.onMessageAdded { e ->
                    printlnPro(e.message)
                }

                openEnhanced(url, driver)
                block(driver)
            }
        }
    }
}

