package ai.platon.pulsar.browser

import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.WebDriver

import ai.platon.pulsar.chrome.PulsarWebDriver
import ai.platon.pulsar.chrome.protocol.DirectChromeProtocol
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.common.printlnPro
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
    private val chromeLoggerName = "ai.platon.pulsar.chrome"
    private val transportLoggerName = "ai.platon.pulsar.chrome.protocol"
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
    fun whenNavigateAHtmlPageThenTheNavigateStateAreCorrect() = runWebDriverTestAndCompute(browser) { driver ->
        openAndCompute(interactiveUrl, driver, 1)

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
    @DisplayName("test evaluate 1+1")
    fun testEvaluate1Plus1() = runWebDriverTestAndCompute(testURL, browser) { driver ->
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

    // ── executeCdpCommand tests ───────────────────────────────────────

    @Test
    @DisplayName("executeCdpCommand Runtime.evaluate 1+1")
    fun testExecuteCdpCommandRuntimeEvaluate() =
        runWebDriverTestAndCompute(testURL, browser) { driver ->
            val result = driver.executeCdpCommand(
                "Runtime.evaluate",
                mapOf("expression" to "1 + 1")
            )
            assertIs<Map<*, *>>(result)
            val resultObj = result["result"] as Map<*, *>
            assertEquals("number", resultObj["type"])
            assertEquals(2, (resultObj["value"] as Number).toInt())
        }

    @Test
    @DisplayName("executeCdpCommand DOM.getDocument")
    fun testExecuteCdpCommandGetDocument() =
        runWebDriverTestAndCompute(testURL, browser) { driver ->
            val result = driver.executeCdpCommand("DOM.getDocument")
            assertIs<Map<*, *>>(result)
            assertNotNull(result["root"], "DOM.getDocument should return a root node")
        }

    @Test
    @DisplayName("executeCdpCommand Page.getNavigationHistory")
    fun testExecuteCdpCommandPageGetNavigationHistory() =
        runWebDriverTestAndCompute(testURL, browser) { driver ->
            val result = driver.executeCdpCommand("Page.getNavigationHistory")
            assertIs<Map<*, *>>(result)
            assertIs<List<*>>(result["entries"])
            assertTrue((result["currentIndex"] as Number).toInt() >= 0)
        }

    @Test
    @DisplayName("executeCdpCommand with invalid method throws")
    fun testExecuteCdpCommandInvalidMethodThrows() =
        runWebDriverTestAndCompute(testURL, browser) { driver ->
            try {
                driver.executeCdpCommand("NonExistent.Method")
                fail("Expected exception for invalid CDP method")
            } catch (e: Exception) {
                // expected — CDP rejects unknown methods
            }
        }

    private fun runWebDriverDOMEventTest(url: String, browser: Browser, block: suspend (WebDriver) -> Unit) {
        runBlocking {
            browser.newDriver().use { driver ->
                assertIs<PulsarWebDriver>(driver)

                val protocol = driver.implementation as DirectChromeProtocol

                protocol.dom.onAttributeModified { e ->
                    val message = MessageFormat.format("> {0}. node changed | {1} := {2}", e.nodeId, e.name, e.value)
                    printlnPro(message)
                }

                protocol.console.enable()
                driver.browserProtocol.onConsoleMessageAdded { e ->
                    printlnPro(e.message)
                }

                openAndCompute(url, driver)
                block(driver)
            }
        }
    }
}

