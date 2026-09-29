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
        // A successful destroyDriver drops the driver from the registry, which is
        // the evidence closeTab looks for (see BrowserToolExecutor.isTabListed).
        every { browser.destroyDriver(front) } answers {
            every { browser.drivers } returns emptyMap()
        }

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
        every { liveDriver.guid } returns "LIVE-GUID"
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
    @DisplayName("closeTab verification accepts a tab that disappears inside the grace window")
    fun closeTabVerificationAcceptsATabThatDisappearsInsideTheGraceWindow() = runBlocking {
        // The CDP teardown races the verification: Target.closeTarget resolves
        // while the browser still lists the target for a moment.
        var probes = 0
        val closed = executor.awaitTabGone(graceMillis = 500, pollMillis = 20) {
            probes++
            probes <= 2
        }

        assertTrue(closed, "A tab the browser drops inside the window is a successful close")
        assertEquals(3, probes, "The tab must be probed until it is gone")
    }

    @Test
    @DisplayName("closeTab verification fails a tab that stays listed for the whole window")
    fun closeTabVerificationFailsATabThatStaysListedForTheWholeWindow() = runBlocking {
        var probes = 0
        val closed = executor.awaitTabGone(graceMillis = 150, pollMillis = 20) {
            probes++
            true
        }

        assertFalse(closed, "A tab still listed after the grace window was never closed")
        assertTrue(probes > 1, "The tab must be watched inside the window, got $probes probe(s)")
    }

    @Test
    @DisplayName("closeTab verification keeps watching an unreadable tab list instead of failing the close")
    fun closeTabVerificationKeepsWatchingAnUnreadableTabList() = runBlocking {
        // No readable tab list is no evidence of an open tab: a close that
        // cannot be verified must not be reported as a failure.
        var probes = 0
        val closed = executor.awaitTabGone(graceMillis = 150, pollMillis = 20) {
            probes++
            null
        }

        assertTrue(closed, "An unreadable tab list must not fail the close that just happened")
        assertTrue(probes > 1, "An unreadable tab list must not end the verification early, got $probes probe(s)")
    }

    @Test
    @DisplayName("closeTab verification returns immediately when the close already landed")
    fun closeTabVerificationReturnsImmediatelyWhenTheCloseAlreadyLanded() = runBlocking {
        var probes = 0
        val closed = executor.awaitTabGone(graceMillis = 5_000, pollMillis = 20) {
            probes++
            false
        }

        assertTrue(closed)
        assertEquals(1, probes, "A tab that is already gone needs no second probe")
    }

    @Test
    @DisplayName("closeTab verification flags a close that left the driver registered")
    fun closeTabVerificationFlagsACloseThatLeftTheDriverRegistered() = runBlocking {
        // destroyDriver is a no-op for a driver the browser does not recognize,
        // so the registry still holds the guid: the close never happened.
        val stubborn = mockk<AbstractWebDriver>(relaxed = true)
        every { stubborn.guid } returns "STUBBORN-GUID"
        every { browser.findDriverByGUID("STUBBORN-GUID") } returns stubborn
        every { browser.drivers } returns mapOf("STUBBORN-GUID" to stubborn)

        val result = executor.callFunctionOn(
            ToolCall("browser", "closeTab", mutableMapOf("tabId" to "STUBBORN-GUID")),
            browser
        )

        assertNotNull(result.exception)
        assertTrue(
            result.exception?.cause?.message?.contains("still open after destroyDriver") == true,
            "Expected the failed close to be surfaced, got: ${result.exception?.cause?.message}"
        )
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
}
