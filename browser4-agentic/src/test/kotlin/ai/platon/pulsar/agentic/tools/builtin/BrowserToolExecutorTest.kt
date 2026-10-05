package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BrowserToolExecutorTest {

    private lateinit var browser: AbstractBrowser
    private lateinit var executor: BrowserToolExecutor

    @BeforeEach
    fun setUp() {
        browser = mockk(relaxed = true)
        executor = BrowserToolExecutor()
    }

    @Test
    @DisplayName("help returns available methods")
    fun helpReturnsAvailableMethods() {
        val help = executor.help()

        assertNotNull(help)
        assertTrue(help.isNotBlank())
        assertTrue(help.contains("Switch to a specific browser tab"))
    }

    @Test
    @DisplayName("help for switchTab method returns detailed help")
    fun helpForSwitchtabMethodReturnsDetailedHelp() {
        val help = executor.help("switchTab")

        assertNotNull(help)
        assertTrue(help.contains("Switch to a specific browser tab"))
        assertTrue(help.contains("switchTab"))
    }

    @Test
    @DisplayName("help for unknown method returns empty string")
    fun helpForUnknownMethodReturnsEmptyString() {
        val help = executor.help("unknownMethod")

        assertEquals("", help)
    }

    @Test
    @DisplayName("switchTab with invalid tab returns exception")
    fun switchtabWithInvalidTabReturnsException() = runBlocking {
        every { browser.findDriverByGUID(any()) } returns null

        val tc = ToolCall(
            domain = "browser",
            method = "switchTab",
            arguments = mutableMapOf("tabId" to "DEADBEEF000000000000000000000000")
        )

        val result = executor.callFunctionOn(tc, browser)

        assertNotNull(result.exception)
        assertTrue(result.exception?.cause?.message?.contains("not found") == true)
    }

    @Test
    @DisplayName("domain property is browser")
    fun domainPropertyIsBrowser() {
        assertEquals("browser", executor.domain)
    }

    @Test
    @DisplayName("closeTab without target destroys the live front driver")
    fun closeTabWithoutTargetDestroysLiveFrontDriver() = runBlocking {
        val front = mockk<AbstractWebDriver>(relaxed = true)
        every { front.guid } returns "FRONT-GUID"
        every { browser.frontDriver } returns front
        every { browser.drivers } returns mapOf("FRONT-GUID" to front)

        val result = executor.callFunctionOn(
            ToolCall("browser", "closeTab", mutableMapOf()),
            browser
        )

        assertNull(result.exception)
        verify(exactly = 1) { browser.destroyDriver(front) }
    }

    @Test
    @DisplayName("closeTab without target ignores a dangling front driver")
    fun closeTabWithoutTargetIgnoresDanglingFrontDriver() = runBlocking {
        // A frontDriver that destroyDriver never cleared after the tab was
        // closed: its guid is no longer a key of the browser's driver map.
        val staleFront = mockk<AbstractWebDriver>(relaxed = true)
        every { staleFront.guid } returns "STALE-GUID"
        every { browser.frontDriver } returns staleFront
        every { browser.drivers } returns emptyMap()

        val liveDriver = mockk<AbstractWebDriver>(relaxed = true)
        coEvery { browser.listDrivers() } returns listOf(liveDriver)

        val result = executor.callFunctionOn(
            ToolCall("browser", "closeTab", mutableMapOf()),
            browser
        )

        assertNull(result.exception)
        verify(exactly = 1) { browser.destroyDriver(liveDriver) }
        verify(exactly = 0) { browser.destroyDriver(staleFront) }
    }

    @Test
    @DisplayName("closeTab by GUID succeeds when the tab is gone on the first check")
    fun closeTabByGuidVerifiedGoneImmediately() = runBlocking {
        val executor = BrowserToolExecutor(
            closeVerifyPollMs = 10L,
            closeVerifyPollsPerAttempt = 2,
            closeMaxAttempts = 2,
        )
        val target = mockk<AbstractWebDriver>(relaxed = true)
        every { target.guid } returns "TAB-GUID"
        every { browser.findDriverByGUID("TAB-GUID") } returns target
        // Relaxed listDrivers() returns an empty list: the close took effect.

        val result = executor.callFunctionOn(
            ToolCall("browser", "closeTab", mutableMapOf("tabId" to "TAB-GUID")),
            browser
        )

        assertNull(result.exception)
        verify(exactly = 1) { browser.destroyDriver(target) }
    }

    @Test
    @DisplayName("closeTab retries when page recovery resurrects the tab, then succeeds")
    fun closeTabRetriesAfterResurrection() = runBlocking {
        val executor = BrowserToolExecutor(
            closeVerifyPollMs = 10L,
            closeVerifyPollsPerAttempt = 2,
            closeMaxAttempts = 3,
        )
        val target = mockk<AbstractWebDriver>(relaxed = true)
        every { target.guid } returns "TAB-GUID"
        every { browser.findDriverByGUID("TAB-GUID") } returns target
        // Attempt 0's two polls still see the tab (close not committed yet);
        // the first poll of attempt 1 (after the retry destroy) sees it gone.
        coEvery { browser.listDrivers() } returnsMany listOf(
            listOf(target), listOf(target), emptyList()
        )

        val result = executor.callFunctionOn(
            ToolCall("browser", "closeTab", mutableMapOf("tabId" to "TAB-GUID")),
            browser
        )

        assertNull(result.exception)
        // Initial destroy plus one retry against the resurrected driver.
        verify(exactly = 2) { browser.destroyDriver(target) }
    }

    @Test
    @DisplayName("closeTab fails loudly when the tab survives every attempt")
    fun closeTabFailsLoudlyWhenTabSurvives() = runBlocking {
        val executor = BrowserToolExecutor(
            closeVerifyPollMs = 10L,
            closeVerifyPollsPerAttempt = 2,
            closeMaxAttempts = 2,
        )
        val target = mockk<AbstractWebDriver>(relaxed = true)
        every { target.guid } returns "TAB-GUID"
        every { browser.findDriverByGUID("TAB-GUID") } returns target
        coEvery { browser.listDrivers() } returns listOf(target)

        val result = executor.callFunctionOn(
            ToolCall("browser", "closeTab", mutableMapOf("tabId" to "TAB-GUID")),
            browser
        )

        assertNotNull(result.exception)
        val message = result.exception?.cause?.message.orEmpty() + result.exception?.message.orEmpty()
        assertTrue(message.contains("TAB-GUID"), "failure must name the tab: $message")
        // One destroy per attempt — never reported as a phantom success.
        verify(exactly = 2) { browser.destroyDriver(target) }
    }

    @Test
    @DisplayName("switchTab records the switch on the browser even when bringToFront fails")
    fun switchTabRecordsSwitchWhenBringToFrontFails() = runBlocking {
        val tabDriver = mockk<AbstractWebDriver>(relaxed = true)
        every { tabDriver.guid } returns "TAB-GUID"
        coEvery { tabDriver.bringToFront() } throws IllegalStateException("no window")
        every { browser.findDriverByGUID("TAB-GUID") } returns tabDriver

        val result = executor.callFunctionOn(
            ToolCall("browser", "switchTab", mutableMapOf("tabId" to "TAB-GUID")),
            browser
        )

        // The failed CDP activation must not abort the switch: the browser's
        // front driver still follows the requested tab.
        assertNull(result.exception)
        verify(exactly = 1) { browser.frontDriver = tabDriver }
    }

    @Test
    @DisplayName("listTabs emits a real Boolean for the active marker")
    fun listTabsEmitsRealBooleanActiveMarker() = runBlocking {
        val tab0 = mockk<AbstractWebDriver>(relaxed = true)
        every { tab0.guid } returns "GUID-0"
        val tab1 = mockk<AbstractWebDriver>(relaxed = true)
        every { tab1.guid } returns "GUID-1"
        coEvery { browser.listDrivers() } returns listOf(tab0, tab1)
        every { browser.frontDriver } returns tab1

        val result = executor.callFunctionOn(
            ToolCall("browser", "listTabs", mutableMapOf()),
            browser
        )

        assertNull(result.exception)
        @Suppress("UNCHECKED_CAST")
        val tabs = result.value as? List<Map<String, Any?>>
        assertNotNull(tabs)
        assertEquals(2, tabs!!.size)
        // The active marker must be a JSON-serializable Boolean: the legacy
        // string "true"/"false" was silently read as false by every boolean
        // consumer (tab-list --json, the ▶ marker, page-info).
        assertTrue(tabs[0]["active"] is Boolean)
        assertTrue(tabs[1]["active"] is Boolean)
        assertEquals(false, tabs[0]["active"])
        assertEquals(true, tabs[1]["active"])
    }
}
