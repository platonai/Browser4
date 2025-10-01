package ai.platon.pulsar.skeleton.ai

import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import ai.platon.pulsar.skeleton.ai.tta.ActionDescription
import ai.platon.pulsar.skeleton.ai.tta.ActionOptions
import ai.platon.pulsar.skeleton.crawl.fetch.driver.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.time.Duration

/**
 * Unit tests for WebDriverAgent termination logic using injected actionGenerator & executionStrategy.
 */
@Tag("UnitTest")
class AgentTerminationTest {

    private class StubBrowser: Browser {
        override val id: ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId = ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId("stub")
        override val instanceId: Int = 0
        override val host: String = "localhost"
        override val port: Int = 0
        override val userAgent: String = "stub-agent"
        override val navigateHistory: NavigateHistory = NavigateHistory()
        override val drivers: Map<String, WebDriver> = emptyMap()
        override val data: MutableMap<String, Any?> = mutableMapOf()
        override val isActive: Boolean = true
        override val isClosed: Boolean = false
        override val isConnected: Boolean = true
        override val settings: ai.platon.pulsar.browser.common.BrowserSettings = ai.platon.pulsar.browser.common.BrowserSettings()
        override val isIdle: Boolean = true
        override val isPermanent: Boolean = false
        override val readableState: String = "ACTIVE"
        override fun newDriver(): WebDriver { throw UnsupportedOperationException() }
        override fun newDriver(url: String): WebDriver { throw UnsupportedOperationException() }
        override suspend fun listDrivers(): List<WebDriver> = emptyList()
        override suspend fun findDriver(url: String): WebDriver? = null
        override suspend fun findDriver(urlRegex: Regex): WebDriver? = null
        override suspend fun findDrivers(urlRegex: Regex): List<WebDriver> = emptyList()
        override fun destroyDriver(driver: WebDriver) {}
        override fun destroyForcibly() {}
        override suspend fun clearCookies() {}
        override fun close() {}
    }

    /**
     * Minimal fake WebDriver implementing only required members for the agent tests.
     */
    private class FakeWebDriver: WebDriver {
        override val id: Int = 1
        override val parentSid: Int = 0
        override val browser: Browser = StubBrowser()
        override val frames: List<WebDriver> = emptyList()
        override val opener: WebDriver? = null
        override val outgoingPages: Set<WebDriver> = emptySet()
        override val browserType: ai.platon.pulsar.common.browser.BrowserType = ai.platon.pulsar.common.browser.BrowserType.PULSAR_CHROME
        override var navigateEntry: NavigateEntry = NavigateEntry("about:blank")
        override val navigateHistory: NavigateHistory = NavigateHistory().apply { add(navigateEntry) }
        override val data: MutableMap<String, Any?> = mutableMapOf()
        override val delayPolicy: Map<String, IntRange> = emptyMap()
        override val timeoutPolicy: Map<String, Duration> = emptyMap()
        override fun jvm(): JvmWebDriver { throw UnsupportedOperationException() }
        override suspend fun addInitScript(script: String) {}
        override suspend fun addBlockedURLs(urlPatterns: List<String>) {}
        override suspend fun addProbabilityBlockedURLs(urlPatterns: List<String>) {}
        override suspend fun setTimeouts(browserSettings: ai.platon.pulsar.browser.common.BrowserSettings) {}
        override suspend fun navigateTo(url: String) { navigateEntry = NavigateEntry(url); navigateHistory.add(navigateEntry) }
        override suspend fun navigateTo(entry: NavigateEntry) { navigateEntry = entry; navigateHistory.add(entry) }
        override suspend fun currentUrl(): String = navigateEntry.url
        override suspend fun url(): String = navigateEntry.url
        override suspend fun documentURI(): String = navigateEntry.url
        override suspend fun baseURI(): String = navigateEntry.url
        override suspend fun referrer(): String = ""
        override suspend fun pageSource(): String? = "<html></html>"
        override suspend fun chat(prompt: String, selector: String): ModelResponse = ModelResponse.EMPTY
        override suspend fun act(prompt: String) = ai.platon.pulsar.skeleton.ai.tta.InstructionResult.LLM_NOT_AVAILABLE
        override suspend fun act(action: ActionOptions): WebDriverAgent { throw UnsupportedOperationException() }
        override suspend fun act(action: ActionDescription) = ai.platon.pulsar.skeleton.ai.tta.InstructionResult.LLM_NOT_AVAILABLE
        override suspend fun instruct(prompt: String) = ai.platon.pulsar.skeleton.ai.tta.InstructionResult.LLM_NOT_AVAILABLE
        override suspend fun getCookies(): List<Map<String, String>> = emptyList()
        override suspend fun deleteCookies(name: String) {}
        override suspend fun deleteCookies(name: String, url: String?, domain: String?, path: String?) {}
        override suspend fun clearBrowserCookies() {}
        override suspend fun waitForSelector(selector: String, action: suspend () -> Unit): Duration = Duration.ZERO
        override suspend fun waitForSelector(selector: String, timeout: Duration, action: suspend () -> Unit): Duration = Duration.ZERO
        override suspend fun waitForNavigation(oldUrl: String, timeout: Duration): Duration = Duration.ZERO
        override suspend fun waitForPage(url: String, timeout: Duration): WebDriver? = null
        override suspend fun waitUntil(predicate: suspend () -> Boolean): Duration = Duration.ZERO
        override suspend fun waitUntil(timeout: Duration, predicate: suspend () -> Boolean): Duration = Duration.ZERO
        override suspend fun exists(selector: String): Boolean = false
        override suspend fun isVisible(selector: String): Boolean = false
        override suspend fun isChecked(selector: String): Boolean = false
        override suspend fun bringToFront() {}
        override suspend fun focus(selector: String) {}
        override suspend fun type(selector: String, text: String) {}
        override suspend fun fill(selector: String, text: String) {}
        override suspend fun press(selector: String, key: String) {}
        override suspend fun click(selector: String, count: Int) {}
        override suspend fun clickTextMatches(selector: String, pattern: String, count: Int) {}
        override suspend fun clickMatches(selector: String, attrName: String, pattern: String, count: Int) {}
        override suspend fun clickNthAnchor(n: Int, rootSelector: String): String? = null
        override suspend fun check(selector: String) {}
        override suspend fun uncheck(selector: String) {}
        override suspend fun scrollTo(selector: String) {}
        override suspend fun scrollDown(count: Int) {}
        override suspend fun scrollUp(count: Int) {}
        override suspend fun scrollToTop() {}
        override suspend fun scrollToBottom() {}
        override suspend fun scrollToMiddle(ratio: Double) {}
        override suspend fun scrollToScreen(screenNumber: Double) {}
        override suspend fun mouseWheelDown(count: Int, deltaX: Double, deltaY: Double, delayMillis: Long) {}
        override suspend fun mouseWheelUp(count: Int, deltaX: Double, deltaY: Double, delayMillis: Long) {}
        override suspend fun moveMouseTo(x: Double, y: Double) {}
        override suspend fun moveMouseTo(selector: String, deltaX: Int, deltaY: Int) {}
        override suspend fun dragAndDrop(selector: String, deltaX: Int, deltaY: Int) {}
        override suspend fun outerHTML(): String? = "<html></html>"
        override suspend fun outerHTML(selector: String): String? = null
        override suspend fun selectFirstTextOrNull(selector: String): String? = null
        override suspend fun selectTextAll(selector: String): List<String> = emptyList()
        override suspend fun selectFirstAttributeOrNull(selector: String, attrName: String): String? = null
        override suspend fun selectAttributes(selector: String): Map<String, String> = emptyMap()
        override suspend fun selectAttributeAll(selector: String, attrName: String, start: Int, limit: Int): List<String> = emptyList()
        override suspend fun setAttribute(selector: String, attrName: String, attrValue: String) {}
        override suspend fun setAttributeAll(selector: String, attrName: String, attrValue: String) {}
        override suspend fun selectFirstPropertyValueOrNull(selector: String, propName: String): String? = null
        override suspend fun selectPropertyValueAll(selector: String, propName: String, start: Int, limit: Int): List<String> = emptyList()
        override suspend fun setProperty(selector: String, propName: String, propValue: String) {}
        override suspend fun setPropertyAll(selector: String, propName: String, propValue: String) {}
        override suspend fun selectHyperlinks(selector: String, offset: Int, limit: Int): List<ai.platon.pulsar.common.urls.Hyperlink> = emptyList()
        override suspend fun selectAnchors(selector: String, offset: Int, limit: Int): List<ai.platon.pulsar.dom.nodes.GeoAnchor> = emptyList()
        override suspend fun selectImages(selector: String, offset: Int, limit: Int): List<String> = emptyList()
        override suspend fun evaluate(expression: String): Any? = null
        override suspend fun <T> evaluate(expression: String, defaultValue: T): T = defaultValue
        override suspend fun evaluateDetail(expression: String): JsEvaluation? = JsEvaluation(null)
        override suspend fun evaluateValue(expression: String): Any? = null
        override suspend fun <T> evaluateValue(expression: String, defaultValue: T): T = defaultValue
        override suspend fun evaluateValueDetail(expression: String): JsEvaluation? = JsEvaluation(null)
        override suspend fun captureScreenshot(): String? = null
        override suspend fun captureScreenshot(selector: String): String? = null
        override suspend fun captureScreenshot(rect: ai.platon.pulsar.common.math.geometric.RectD): String? = null
        override suspend fun clickablePoint(selector: String): ai.platon.pulsar.common.math.geometric.PointD? = null
        override suspend fun boundingBox(selector: String): ai.platon.pulsar.common.math.geometric.RectD? = null
        override suspend fun newJsoupSession(): org.jsoup.Connection { throw UnsupportedOperationException() }
        override suspend fun loadJsoupResource(url: String): org.jsoup.Connection.Response { throw UnsupportedOperationException() }
        override suspend fun loadResource(url: String): ai.platon.pulsar.browser.driver.chrome.NetworkResourceResponse { throw UnsupportedOperationException() }
        override suspend fun waitForNavigation(oldUrl: String): Duration = Duration.ZERO
        override suspend fun waitForSelector(selector: String): Duration = Duration.ZERO
        override suspend fun waitForSelector(selector: String, timeoutMillis: Long): Long = 0
        override suspend fun waitForNavigation(oldUrl: String, timeoutMillis: Long): Long = 0
        override suspend fun waitUntil(timeoutMillis: Long, predicate: suspend () -> Boolean): Long = 0
        override suspend fun delay(millis: Long) { kotlinx.coroutines.delay(millis) }
        override suspend fun pause() {}
        override suspend fun stop() {}
        override fun close() {}
    }

    private val fakeDriver = FakeWebDriver()

    @Test
    fun `Given stop tool call When executing agent Then terminates early`() = runBlocking {
        System.setProperty("pulsar.tta.disableLLM", "true")
        var step = 0
        val gen: suspend (String, WebDriver, String?) -> ActionDescription = { _, _, _ ->
            step++
            val json = if (step == 1) {
                // first step produce a non-terminating tool
                """{"tool_calls":[{"name":"click","args":{"selector":"#btn"}}]}"""
            } else {
                // second step produce stop
                """{"tool_calls":[{"name":"stop","args":{}}]}"""
            }
            ActionDescription(listOf(), null, ModelResponse(json, ResponseState.SUCCESS))
        }
        val execStrategy = object : ActionExecutionStrategy {
            override suspend fun execute(driver: WebDriver, actionDescription: ActionDescription, toolCallName: String, args: Map<String, Any?>): String? {
                return "exec $toolCallName"
            }
        }
        val agent = WebDriverAgent(fakeDriver, maxSteps = 10, actionGenerator = gen, executionStrategy = execStrategy)
        val result = agent.execute(ActionOptions("test stop"))
        val history = agent.historySnapshot()
        assertTrue(history.any { it.contains("stop()") }, "History should contain stop termination entry")
        assertTrue(history.count { it.startsWith("#") } <= 3, "Should not exceed 3 step history lines including stop")
        assertNotNull(result.content)
    }

    @Test
    fun `Given repeated no-op When executing agent Then stops after threshold`() = runBlocking {
        System.setProperty("pulsar.tta.disableLLM", "true")
        val gen: suspend (String, WebDriver, String?) -> ActionDescription = { _, _, _ ->
            // Always return empty tool_calls to trigger no-op path
            ActionDescription(listOf(), null, ModelResponse("""{"tool_calls":[]}""", ResponseState.SUCCESS))
        }
        val agent = WebDriverAgent(fakeDriver, maxSteps = 50, actionGenerator = gen, executionStrategy = object: ActionExecutionStrategy {
            override suspend fun execute(driver: WebDriver, actionDescription: ActionDescription, toolCallName: String, args: Map<String, Any?>): String? = null
        })
        agent.execute(ActionOptions("test noop"))
        val history = agent.historySnapshot()
        val noOpLines = history.filter { it.contains("no-op") }
        assertTrue(noOpLines.size >= 5, "Should record at least 5 consecutive no-ops")
        assertTrue(noOpLines.size <= 6, "Should stop shortly after threshold (5)")
    }
}

