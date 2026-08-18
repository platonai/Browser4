package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.session.SessionKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.http.ResponseEntity
import java.util.Base64

@Suppress("UNCHECKED_CAST")
class PagesControllerTest {

    /** A 1x1 red PNG, base64-encoded. */
    private val PNG_B64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private fun webDriver(guid: String, title: String = "Page $guid", url: String = "https://example.com/$guid"): AbstractWebDriver {
        val wd = Mockito.mock(AbstractWebDriver::class.java)
        // doReturn avoids the UnfinishedStubbing pitfall when stubbing suspend
        // functions: the when() state machine must not cross coroutine
        // boundaries, and thenReturn() arguments must not touch other mocks.
        Mockito.doReturn(guid).`when`(wd).guid
        runBlocking {
            Mockito.doReturn(title).`when`(wd).title()
            Mockito.doReturn(url).`when`(wd).currentUrl()
        }
        return wd
    }

    private fun browser(drivers: List<AbstractWebDriver>, frontGuid: String?): AbstractBrowser {
        // Resolve values before stubbing — evaluating `it.guid` inside a
        // thenReturn() argument would trip Mockito's in-progress-stubbing guard.
        val driverMap = drivers.associateBy { it.guid }
        val front = drivers.firstOrNull { it.guid == frontGuid }
        val browser = Mockito.mock(AbstractBrowser::class.java)
        Mockito.`when`(browser.drivers).thenReturn(driverMap)
        Mockito.`when`(browser.frontDriver).thenReturn(front)
        return browser
    }

    private fun managedSession(
        id: String,
        kind: SessionKind = SessionKind.BROWSER4_LAUNCHED,
        browser: AbstractBrowser? = null,
    ): ManagedSession {
        val agenticSession = Mockito.mock(AgenticSession::class.java)
        if (browser != null) {
            Mockito.`when`(agenticSession.boundBrowser).thenReturn(browser)
        }
        return ManagedSession(id, agenticSession, null, kind = kind)
    }

    private fun controller(sessions: List<ManagedSession>): PagesController {
        val manager = Mockito.mock(PulsarSessionManager::class.java)
        Mockito.`when`(manager.getAllSessions()).thenReturn(sessions)
        Mockito.`when`(manager.getSession(anyString())).thenAnswer { inv ->
            sessions.firstOrNull { it.sessionId == inv.getArgument<String>(0) }
        }
        return PagesController(manager)
    }

    private fun pageItems(result: Map<String, Any?>): List<Map<String, Any?>> {
        return result["items"] as List<Map<String, Any?>>
    }

    @Test
    fun `pages lists tabs with active flag and screenshot urls`() = runBlocking {
        val tab1 = webDriver("tab-1", "Home", "https://example.com/")
        val tab2 = webDriver("tab-2", "Docs", "https://example.com/docs")
        val session = managedSession("s1", browser = browser(listOf(tab1, tab2), frontGuid = "tab-1"))

        val result = controller(listOf(session)).pages()

        assertEquals(2, result["total"])
        assertEquals(2, result["live"])
        assertEquals(0, result["placeholder"])
        assertEquals(1, result["sessions"])
        val items = pageItems(result)
        val first = items[0]
        assertEquals("s1", first["sessionId"])
        assertEquals("tab-1", first["guid"])
        assertEquals(true, first["active"])
        assertEquals(false, first["placeholder"])
        assertEquals("Home", first["title"])
        assertEquals("https://example.com/", first["url"])
        assertTrue((first["screenshotUrl"] as String).endsWith("/api/pages/s1/tab-1/screenshot.png"))
        val second = items[1]
        assertEquals(false, second["active"])
        assertEquals(false, second["placeholder"])
        assertTrue((second["screenshotUrl"] as String).endsWith("/api/pages/s1/tab-2/screenshot.png"))
    }

    @Test
    fun `pages marks swarm tabs as placeholders without screenshot urls`() = runBlocking {
        val tab1 = webDriver("sw-tab-1")
        val tab2 = webDriver("sw-tab-2")
        val session = managedSession("swarm", kind = SessionKind.SWARM, browser = browser(listOf(tab1, tab2), frontGuid = "sw-tab-1"))

        val result = controller(listOf(session)).pages()

        assertEquals(2, result["total"])
        assertEquals(0, result["live"])
        assertEquals(2, result["placeholder"])
        val items = pageItems(result)
        // Every swarm page is a placeholder; the active flag is factual
        // (which tab is frontmost) and does not affect display.
        val first = items.first { it["guid"] == "sw-tab-1" }
        assertEquals("SWARM", first["kind"])
        assertEquals(true, first["placeholder"])
        assertEquals(true, first["active"])
        assertNull(first["screenshotUrl"])
        val second = items.first { it["guid"] == "sw-tab-2" }
        assertEquals(true, second["placeholder"])
        assertEquals(false, second["active"])
        assertNull(second["screenshotUrl"])
    }

    @Test
    fun `pages falls back to first driver when no front driver`() = runBlocking {
        // frontDriver is only set after bringToFront(); a freshly opened
        // session must still report its first tab as the active page.
        val tab1 = webDriver("tab-1")
        val tab2 = webDriver("tab-2")
        val session = managedSession("s1", browser = browser(listOf(tab1, tab2), frontGuid = null))

        val result = controller(listOf(session)).pages()

        val items = pageItems(result)
        assertEquals(true, items[0]["active"])
        assertEquals(false, items[1]["active"])
    }

    @Test
    fun `pages skips sessions without a browser`() = runBlocking {
        val session = managedSession("no-browser")
        val result = controller(listOf(session)).pages()
        assertEquals(0, result["total"])
        assertTrue(pageItems(result).isEmpty())
    }

    @Test
    fun `screenshot loads asynchronously then returns png bytes`() = runBlocking {
        val tab = webDriver("tab-1")
        runBlocking {
            // doReturn avoids the UnfinishedStubbing pitfall when stubbing
            // suspend functions (the when() state machine must not cross
            // coroutine boundaries).
            Mockito.doReturn(PNG_B64).`when`(tab).screenshot()
        }
        val session = managedSession("s1", browser = browser(listOf(tab), frontGuid = "tab-1"))
        val controller = controller(listOf(session))

        // First request: capture is scheduled, nothing cached yet -> 202.
        val first = controller.screenshot("s1", "tab-1")
        assertEquals(202, first.statusCode.value())
        assertEquals("1", first.headers.getFirst("Retry-After"))

        // Poll until the background capture lands in the cache -> 200 PNG.
        val response = awaitPng(controller, "s1", "tab-1")
        assertEquals(200, response.statusCode.value())
        assertEquals("image/png", response.headers.contentType?.toString())
        val body = response.body as ByteArray
        assertTrue(body.contentEquals(Base64.getDecoder().decode(PNG_B64)), "PNG bytes must round-trip")
    }

    @Test
    fun `screenshot serves cached png without re-capturing`() = runBlocking {
        val tab = webDriver("tab-1")
        runBlocking {
            Mockito.doReturn(PNG_B64).`when`(tab).screenshot()
        }
        val session = managedSession("s1", browser = browser(listOf(tab), frontGuid = "tab-1"))
        val controller = controller(listOf(session))

        awaitPng(controller, "s1", "tab-1")

        // The cached capture is served immediately and the driver is not
        // asked for another screenshot.
        val response = controller.screenshot("s1", "tab-1")
        assertEquals(200, response.statusCode.value())
        assertEquals("image/png", response.headers.contentType?.toString())
        // Allow the single-threaded capture executor a moment to settle.
        delay(50)
        Mockito.verify(tab, Mockito.times(1)).screenshot()
        Unit
    }

    @Test
    fun `screenshot refresh forces a new capture`() = runBlocking {
        val tab = webDriver("tab-1")
        runBlocking {
            Mockito.doReturn(PNG_B64).`when`(tab).screenshot()
        }
        val session = managedSession("s1", browser = browser(listOf(tab), frontGuid = "tab-1"))
        val controller = controller(listOf(session))

        awaitPng(controller, "s1", "tab-1")

        // refresh=1 drops the cache and re-captures asynchronously.
        val refreshed = controller.screenshot("s1", "tab-1", refresh = true)
        assertEquals(202, refreshed.statusCode.value())
        val after = awaitPng(controller, "s1", "tab-1")
        assertEquals(200, after.statusCode.value())
        delay(50)
        Mockito.verify(tab, Mockito.times(2)).screenshot()
        Unit
    }

    @Test
    fun `screenshot forbids swarm sessions`() = runBlocking {
        val tab = webDriver("sw-tab-1")
        val session = managedSession("swarm", kind = SessionKind.SWARM, browser = browser(listOf(tab), frontGuid = "sw-tab-1"))

        val response = controller(listOf(session)).screenshot("swarm", "sw-tab-1")

        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `screenshot returns 404 for unknown session`() = runBlocking {
        val response = controller(emptyList()).screenshot("nope", "tab-1")
        assertEquals(404, response.statusCode.value())
    }

    @Test
    fun `screenshot returns 404 for unknown tab`() = runBlocking {
        val tab = webDriver("tab-1")
        val session = managedSession("s1", browser = browser(listOf(tab), frontGuid = "tab-1"))

        val response = controller(listOf(session)).screenshot("s1", "no-such-tab")

        assertEquals(404, response.statusCode.value())
    }

    @Test
    fun `screenshot reports 504 after a failed capture`() = runBlocking {
        val tab = webDriver("tab-1")
        runBlocking {
            Mockito.doReturn(null).`when`(tab).screenshot()
        }
        val session = managedSession("s1", browser = browser(listOf(tab), frontGuid = "tab-1"))
        val controller = controller(listOf(session))

        // Capture scheduled -> 202; the background task fails -> subsequent
        // polls fail fast with 504 until the failure TTL expires.
        val first = controller.screenshot("s1", "tab-1")
        assertEquals(202, first.statusCode.value())

        val response = awaitNon202(controller, "s1", "tab-1")
        assertEquals(504, response.statusCode.value())
    }

    /** Poll [PagesController.screenshot] until it stops answering 202. */
    private suspend fun awaitPng(
        controller: PagesController,
        sessionId: String,
        guid: String,
    ): ResponseEntity<Any> {
        var response = controller.screenshot(sessionId, guid)
        var attempts = 0
        while (response.statusCode.value() == 202 && attempts < 200) {
            delay(10)
            response = controller.screenshot(sessionId, guid)
            attempts++
        }
        return response
    }

    /** Poll until the response is no longer 202 (e.g. a 504 failure). */
    private suspend fun awaitNon202(
        controller: PagesController,
        sessionId: String,
        guid: String,
    ): ResponseEntity<Any> {
        var response = controller.screenshot(sessionId, guid)
        var attempts = 0
        while (response.statusCode.value() == 202 && attempts < 200) {
            delay(10)
            response = controller.screenshot(sessionId, guid)
            attempts++
        }
        return response
    }
}
