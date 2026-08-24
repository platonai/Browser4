package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.chrome.Browser4WebDriver
import ai.platon.pulsar.chrome.PulsarBrowser
import ai.platon.pulsar.chrome.PulsarWebDriver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Unit tests for [AgentToolManager.onDidSwitchTab] rebinding behaviour.
 *
 * AbstractToolExecutor.callFunctionOn wraps non-serializable return values
 * (such as the WebDriver returned by BrowserToolExecutor.switchTab) into a
 * description Map, so the `evaluate.value as? WebDriver` branch can never
 * fire.  The rebinding must therefore resolve the target driver from the
 * tool-call arguments (tabId or index) against the bound browser instead of
 * relying on a possibly-stale `frontDriver`.
 */
class AgentToolManagerTest {

    private lateinit var session: AgenticSession
    private lateinit var agent: BasicBrowserAgent
    private lateinit var manager: AgentToolManager
    private lateinit var browser: AbstractBrowser
    private lateinit var boundDriver: WebDriver

    @BeforeEach
    fun setUp() {
        session = mockk(relaxed = true)
        agent = mockk(relaxed = true)
        every { agent.session } returns session
        manager = AgentToolManager(Paths.get("."), agent)

        browser = mockk(relaxed = true)
        boundDriver = mockk(relaxed = true)
        every { session.getOrCreateBoundDriver() } returns boundDriver
        every { boundDriver.browser } returns browser
        every { session.boundBrowser } returns browser
    }

    @Test
    @DisplayName("switchTab with tabId binds the driver resolved from the browser drivers map")
    fun switchTabByTabIdBindsResolvedDriver() = runBlocking {
        // The tab driver created by PulsarBrowser is a plain PulsarWebDriver;
        // bindSwappedDriver must swap it to a Browser4WebDriver so the session
        // bean registry (keyed by concrete class) replaces the bound driver
        // instead of adding a second bean that boundDriver never sees.
        val targetDriver = mockk<PulsarWebDriver>(relaxed = true)
        val staleFrontDriver: WebDriver = mockk(relaxed = true)
        val tabId = "161A46FDD7ACDCC0F040A913100D4517"
        val chromeTab = mockk<BrowserTab>(relaxed = true)
        val browserProtocol = mockk<BrowserProtocol>(relaxed = true)
        val pulsarBrowser = mockk<PulsarBrowser>(relaxed = true)
        every { targetDriver.guid } returns tabId
        every { targetDriver.chromeTab } returns chromeTab
        every { targetDriver.browserProtocol } returns browserProtocol
        every { targetDriver.browser } returns pulsarBrowser

        // Executor-side resolution (BrowserToolExecutor.switchTab).
        val executorDriver = mockk<AbstractWebDriver>(relaxed = true)
        every { browser.findDriverByGUID(tabId) } returns executorDriver
        // Manager-side resolution (onDidSwitchTab) — the drivers map keyed by GUID.
        every { browser.drivers } returns mapOf(tabId to targetDriver)
        // A stale frontDriver that the old fallback path would have bound.
        every { browser.frontDriver } returns staleFrontDriver

        manager.execute(
            ToolCall("browser", "switchTab", mutableMapOf<String, Any?>("tabId" to tabId))
        )

        // Exactly one bind, and it must be the swapped Browser4WebDriver
        // wrapping the tabId-resolved driver — never the raw PulsarWebDriver
        // (whose bean key would differ) and never the stale frontDriver.
        val bound = slot<WebDriver>()
        verify(exactly = 1) { session.bindDriver(capture(bound)) }
        assertTrue(bound.captured is Browser4WebDriver, "Expected Browser4WebDriver, got ${bound.captured::class}")
        assertEquals(tabId, bound.captured.guid)
    }

    @Test
    @DisplayName("switchTab with index binds the driver at that list index")
    fun switchTabByIndexBindsIndexedDriver() = runBlocking {
        val firstDriver = mockk<AbstractWebDriver>(relaxed = true)
        val secondDriver = mockk<AbstractWebDriver>(relaxed = true)
        coEvery { browser.listDrivers() } returns listOf(firstDriver, secondDriver)

        manager.execute(
            ToolCall("browser", "switchTab", mutableMapOf<String, Any?>("index" to 1))
        )

        verify(exactly = 1) { session.bindDriver(secondDriver) }
        verify(exactly = 0) { session.bindDriver(firstDriver) }
    }

    @Test
    @DisplayName("switchTab without tabId or index falls back to the front driver")
    fun switchTabWithoutTargetFallsBackToFrontDriver() = runBlocking {
        val frontDriver: WebDriver = mockk(relaxed = true)
        every { browser.frontDriver } returns frontDriver

        manager.execute(
            ToolCall("browser", "switchTab", mutableMapOf<String, Any?>())
        )

        verify(exactly = 1) { session.bindDriver(frontDriver) }
    }

    @Test
    @DisplayName("closeTab without target resolves the session-bound driver, not frontDriver")
    fun closeTabWithoutTargetResolvesBoundDriver() = runBlocking {
        // The session-bound driver defines "current tab": frontDriver may be
        // stale (destroyDriver never clears it) and must not be trusted.
        val bound = mockk<AbstractWebDriver>(relaxed = true)
        every { bound.guid } returns "BOUND-GUID"
        every { bound.browser } returns browser
        every { session.getOrCreateBoundDriver() } returns bound

        val staleFront: WebDriver = mockk(relaxed = true)
        every { browser.frontDriver } returns staleFront

        val executorDriver = mockk<AbstractWebDriver>(relaxed = true)
        every { browser.findDriverByGUID("BOUND-GUID") } returns executorDriver

        manager.execute(
            ToolCall("browser", "closeTab", mutableMapOf<String, Any?>())
        )

        verify(exactly = 1) { browser.destroyDriver(executorDriver) }
    }

    @Test
    @DisplayName("closeTab with explicit tabId does not override the target")
    fun closeTabWithExplicitTabIdIsNotOverridden() = runBlocking {
        val bound = mockk<AbstractWebDriver>(relaxed = true)
        every { bound.guid } returns "BOUND-GUID"
        every { bound.browser } returns browser
        every { session.getOrCreateBoundDriver() } returns bound

        val explicitDriver = mockk<AbstractWebDriver>(relaxed = true)
        every { browser.findDriverByGUID("EXPLICIT-GUID") } returns explicitDriver

        manager.execute(
            ToolCall("browser", "closeTab", mutableMapOf<String, Any?>("tabId" to "EXPLICIT-GUID"))
        )

        verify(exactly = 1) { browser.destroyDriver(explicitDriver) }
        verify(exactly = 0) { browser.findDriverByGUID("BOUND-GUID") }
    }

    @Test
    @DisplayName("closeTab repairs frontDriver when it points at the destroyed tab")
    fun closeTabRepairsDanglingFrontDriver() = runBlocking {
        val bound = mockk<AbstractWebDriver>(relaxed = true)
        every { bound.guid } returns "BOUND-GUID"
        every { bound.browser } returns browser
        every { session.getOrCreateBoundDriver() } returns bound

        // The browser's frontDriver still references the destroyed bound tab.
        val staleFront = mockk<AbstractWebDriver>(relaxed = true)
        every { staleFront.guid } returns "BOUND-GUID"
        every { browser.frontDriver } returns staleFront

        val executorDriver = mockk<AbstractWebDriver>(relaxed = true)
        every { browser.findDriverByGUID("BOUND-GUID") } returns executorDriver

        val remainingDriver = mockk<AbstractWebDriver>(relaxed = true)
        coEvery { browser.listDrivers() } returns listOf(remainingDriver)
        every { session.boundDriver } returns bound

        manager.execute(
            ToolCall("browser", "closeTab", mutableMapOf<String, Any?>())
        )

        verify(exactly = 1) { browser.frontDriver = remainingDriver }
    }

    @Test
    @DisplayName("navigate polls readyState instead of calling the no-op waitForNavigation in onDidNavigate")
    fun navigatePollsReadyStateInsteadOfNoOpWaitForNavigation() = runBlocking {
        // Same-URL navigation (SPA route): currentUrl never changes.
        coEvery { boundDriver.currentUrl() } returns "http://example.com"
        // The first readyState read (BrowserTabToolExecutor.waitForPotentialNavigation)
        // reports "loading", subsequent reads "complete" — the executor-level poll
        // consumes "loading" then "complete", and onDidNavigate's poll sees "complete".
        coEvery { boundDriver.evaluateValue("document.readyState") } returnsMany listOf(
            "loading", "complete", "complete"
        )
        coEvery { boundDriver.navigate(any<String>()) } returns Unit
        coEvery { boundDriver.waitForSelector(any(), any<Long>()) } returns 0L

        manager.execute(
            ToolCall("tab", "navigate", mutableMapOf<String, Any?>("url" to "http://example.com"))
        )

        // Regression: onDidNavigate used to call the no-arg driver.waitForNavigation(),
        // whose predicate is `"" != currentUrl()` — true as soon as the page has any
        // URL, so it returned immediately without waiting at all (and the oldUrl
        // overload can never complete for same-URL navigations). Both the executor
        // and the manager must now poll document.readyState instead.
        coVerify(exactly = 0) { boundDriver.waitForNavigation() }
        coVerify(atLeast = 2) { boundDriver.evaluateValue("document.readyState") }
    }

    @Test
    @DisplayName("onDidNavigate does not stack the 60s-default body wait when the document never becomes ready")
    fun onDidNavigateSkipsBodyWaitWhenDocumentNeverReady() = runBlocking {
        // The page context is wedged: evals throw (CDP evals return null /
        // the page is broken), so readyState never reads "complete".
        coEvery { boundDriver.currentUrl() } returns "http://example.com"
        coEvery { boundDriver.evaluateValue("document.readyState") } throws RuntimeException("wedged page")
        coEvery { boundDriver.navigate(any<String>()) } returns Unit
        coEvery { boundDriver.waitForSelector(any(), any<Long>()) } returns 0L

        manager.execute(
            ToolCall("tab", "navigate", mutableMapOf<String, Any?>("url" to "http://example.com"))
        )

        // Regression: onDidNavigate used to call the no-timeout
        // waitForSelector("body") after the poll, whose default timeout is 60s —
        // stacking a full-timeout dead wait on top of the exhausted poll (~90s
        // per navigation action when the page context is wedged). The fix
        // skips the body wait entirely when the document never became ready.
        coVerify(exactly = 0) { boundDriver.waitForSelector("body") }
        coVerify(exactly = 0) { boundDriver.waitForSelector("body", any<Long>()) }
    }
}
