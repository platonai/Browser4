package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.WebDriver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
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
        val targetDriver: WebDriver = mockk(relaxed = true)
        val staleFrontDriver: WebDriver = mockk(relaxed = true)
        val tabId = "161A46FDD7ACDCC0F040A913100D4517"

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

        verify(exactly = 1) { session.bindDriver(targetDriver) }
        verify(exactly = 0) { session.bindDriver(staleFrontDriver) }
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
}
