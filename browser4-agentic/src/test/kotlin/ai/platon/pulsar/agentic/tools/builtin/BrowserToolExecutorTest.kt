package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import io.mockk.coEvery
import io.mockk.coVerify
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
        // A successful destroyDriver drops the driver from the registry and the
        // relaxed listDrivers() reports no survivor, which is what closeTab's
        // verification reads (see BrowserToolExecutor.destroyAndVerifyClosed).
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
        // Closing the live tab must drop it from the tab list: that list is both
        // how the target driver is resolved and what closeTab's verification
        // polls, so a driver that is never dropped reads as an uncommitted close.
        every { browser.destroyDriver(liveDriver) } answers {
            coEvery { browser.listDrivers() } returns emptyList()
        }

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

    @Test
    @DisplayName("listTabs marks the only tab active when no front driver was set yet")
    fun listTabsMarksSingleTabActiveWithoutFrontDriver() = runBlocking {
        // A freshly opened managed session never called bringToFront(), so
        // frontDriver is null. Every tab used to come back active=false, which
        // silently broke `.pages[] | select(.active)` in the CLI (page-info and
        // tab-list both render from this listing).
        val tab = mockk<AbstractWebDriver>(relaxed = true)
        every { tab.guid } returns "GUID-ONLY"
        coEvery { browser.listDrivers() } returns listOf(tab)
        every { browser.frontDriver } returns null

        val result = executor.callFunctionOn(
            ToolCall("browser", "listTabs", mutableMapOf()),
            browser
        )

        assertNull(result.exception)
        @Suppress("UNCHECKED_CAST")
        val tabs = result.value as? List<Map<String, Any?>>
        assertNotNull(tabs)
        assertEquals(1, tabs!!.size)
        assertEquals(true, tabs[0]["active"], "the only tab of a managed session is the active one")
        assertTrue(tabs[0]["active"] is Boolean)
    }

    @Test
    @DisplayName("listTabs falls back to the first tab when no front driver was set yet")
    fun listTabsFallsBackToFirstTabWithoutFrontDriver() = runBlocking {
        val tab0 = mockk<AbstractWebDriver>(relaxed = true)
        every { tab0.guid } returns "GUID-0"
        val tab1 = mockk<AbstractWebDriver>(relaxed = true)
        every { tab1.guid } returns "GUID-1"
        coEvery { browser.listDrivers() } returns listOf(tab0, tab1)
        every { browser.frontDriver } returns null

        val result = executor.callFunctionOn(
            ToolCall("browser", "listTabs", mutableMapOf()),
            browser
        )

        assertNull(result.exception)
        @Suppress("UNCHECKED_CAST")
        val tabs = result.value as? List<Map<String, Any?>>
        assertNotNull(tabs)
        // Exactly one tab is active — never none, never two.
        assertEquals(1, tabs!!.count { it["active"] == true }, "exactly one tab must be active: $tabs")
        assertEquals(true, tabs[0]["active"])
        assertEquals(false, tabs[1]["active"])
    }

    @Test
    @DisplayName("listTabs does not honour a front driver that is not among the listed tabs")
    fun listTabsIgnoresForeignFrontDriver() = runBlocking {
        val tab = mockk<AbstractWebDriver>(relaxed = true)
        every { tab.guid } returns "GUID-0"
        val foreign = mockk<AbstractWebDriver>(relaxed = true)
        every { foreign.guid } returns "GUID-FROM-ANOTHER-BROWSER"
        coEvery { browser.listDrivers() } returns listOf(tab)
        every { browser.frontDriver } returns foreign

        val result = executor.callFunctionOn(
            ToolCall("browser", "listTabs", mutableMapOf()),
            browser
        )

        @Suppress("UNCHECKED_CAST")
        val tabs = result.value as? List<Map<String, Any?>>
        assertEquals(
            1,
            tabs!!.count { it["active"] == true },
            "a stale/foreign front driver must not leave every tab inactive: $tabs",
        )
    }

    // ------------------------------------------------------------------
    // Tab indices must be stable: `AbstractBrowser.listDrivers()` is a
    // ConcurrentHashMap's values in HASH order, so numbering from it made
    // tab-list and a later tab-select disagree. Indices are now positions in a
    // stable creation order (AbstractWebDriver.id).
    // ------------------------------------------------------------------

    /** A tab mock whose creation id and guid are pinned. */
    private fun tab(id: Int, guid: String): AbstractWebDriver {
        val driver = mockk<AbstractWebDriver>(relaxed = true)
        every { driver.id } returns id
        every { driver.guid } returns guid
        return driver
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun listTabs(): List<Map<String, Any?>> {
        val result = executor.callFunctionOn(ToolCall("browser", "listTabs", mutableMapOf()), browser)
        assertNull(result.exception)
        return result.value as List<Map<String, Any?>>
    }

    private fun guidsAtIndices(tabs: List<Map<String, Any?>>): Map<Int, String> =
        tabs.associate { (it["index"] as Int) to it["guid"] as String }

    @Test
    @DisplayName("listTabs numbers tabs by creation order, not by the driver map's order")
    fun listTabsNumbersTabsByCreationOrder() = runBlocking {
        // The map hands them back in an order unrelated to their creation ids —
        // exactly what a ConcurrentHashMap does.
        val c = tab(3003, "GUID-C")
        val a = tab(1001, "GUID-A")
        val b = tab(2002, "GUID-B")
        coEvery { browser.listDrivers() } returns listOf(c, a, b)

        val tabs = listTabs()

        assertEquals(mapOf(0 to "GUID-A", 1 to "GUID-B", 2 to "GUID-C"), guidsAtIndices(tabs))
    }

    @Test
    @DisplayName("two listTabs calls over the same tabs publish identical indices")
    fun listTabsPublishesStableIndicesAcrossCalls() = runBlocking {
        val a = tab(1001, "GUID-A")
        val b = tab(2002, "GUID-B")
        val c = tab(3003, "GUID-C")
        coEvery { browser.listDrivers() } returns listOf(c, a, b)
        val first = listTabs()

        // Same tabs, different iteration order — a rehash or an internal
        // re-insertion is enough for this to happen for real.
        coEvery { browser.listDrivers() } returns listOf(b, c, a)
        val second = listTabs()

        assertEquals(guidsAtIndices(first), guidsAtIndices(second))
    }

    @Test
    @DisplayName("an index still addresses the same tab after an unrelated tab is closed")
    fun indexSurvivesUnrelatedTabClose() = runBlocking {
        val a = tab(1001, "GUID-A")
        val b = tab(2002, "GUID-B")
        val c = tab(3003, "GUID-C")
        // Hash order is deliberately NOT creation order, so the old code would
        // have numbered C first and this assertion would fail.
        coEvery { browser.listDrivers() } returns listOf(b, c, a)
        val before = listTabs()
        assertEquals(mapOf(0 to "GUID-A", 1 to "GUID-B", 2 to "GUID-C"), guidsAtIndices(before))

        // C is closed by someone else: A and B keep their indices.
        coEvery { browser.listDrivers() } returns listOf(b, a)
        val after = listTabs()

        assertEquals(mapOf(0 to "GUID-A", 1 to "GUID-B"), guidsAtIndices(after))

        // …and index 0 still SWITCHES to A, not to whichever tab the map lists first.
        val switched = executor.callFunctionOn(
            ToolCall("browser", "switchTab", mutableMapOf("index" to 0)),
            browser
        )
        assertNull(switched.exception)
        coVerify(exactly = 1) { a.bringToFront() }
        coVerify(exactly = 0) { b.bringToFront() }
        verify { browser.frontDriver = a }
    }

    @Test
    @DisplayName("a tab opened later sorts last and leaves existing indices untouched")
    fun indexSurvivesUnrelatedTabOpen() = runBlocking {
        val a = tab(1001, "GUID-A")
        val b = tab(2002, "GUID-B")
        coEvery { browser.listDrivers() } returns listOf(b, a)
        assertEquals(mapOf(0 to "GUID-A", 1 to "GUID-B"), guidsAtIndices(listTabs()))

        // A new tab created now has a higher id, so it is appended.
        val d = tab(4004, "GUID-D")
        coEvery { browser.listDrivers() } returns listOf(d, b, a)

        assertEquals(
            mapOf(0 to "GUID-A", 1 to "GUID-B", 2 to "GUID-D"),
            guidsAtIndices(listTabs())
        )
    }

    @Test
    @DisplayName("closeTab resolves an index in the same stable order")
    fun closeTabResolvesIndexInStableOrder() = runBlocking {
        val a = tab(1001, "GUID-A")
        val b = tab(2002, "GUID-B")
        val c = tab(3003, "GUID-C")
        coEvery { browser.listDrivers() } returns listOf(c, a, b)
        coEvery { browser.findDriverByGUID(any()) } returns null

        val closed = executor.callFunctionOn(
            ToolCall("browser", "closeTab", mutableMapOf("index" to 1)),
            browser
        )

        assertNull(closed.exception, closed.exception?.cause?.message)
        // Index 1 is B by creation order — with hash order it would have been A.
        verify(exactly = 1) { browser.destroyDriver(b) }
        verify(exactly = 0) { browser.destroyDriver(a) }
    }
}

