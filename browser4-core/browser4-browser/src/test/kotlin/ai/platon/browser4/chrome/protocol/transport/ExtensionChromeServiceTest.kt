package ai.platon.browser4.chrome.protocol.transport

import ai.platon.browser4.api.model.BrowserTab
import ai.platon.browser4.api.model.DevToolsConfig
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit tests for [ExtensionChromeService].
 *
 * Uses a [FakeMessageSender] that records outgoing messages and allows
 * the test to inject responses via [ExtensionChromeService.handleIncomingMessage].
 *
 * For asynchronous request/response methods (e.g., [ExtensionChromeService.createTab]),
 * the operation is submitted on a background thread while the test thread injects
 * the response.
 */
class ExtensionChromeServiceTest {

    private lateinit var fakeSender: FakeMessageSender
    private lateinit var service: ExtensionChromeService

    @BeforeEach
    fun setUp() {
        fakeSender = FakeMessageSender()
        service = ExtensionChromeService(fakeSender, "test-session")
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isActive is true when sender is open and not closed")
    fun testIsActive() {
        assertTrue(service.isActive)
        assertEquals("extension", service.host)
        assertEquals(0, service.port)
        assertNotNull(service.version)
    }

    @Test
    @DisplayName("isActive is false after close")
    fun testClose() {
        service.close()
        assertFalse(service.isActive)
        assertFalse(fakeSender.isOpen)
    }

    @Test
    @DisplayName("close is idempotent")
    fun testCloseIdempotent() {
        service.close()
        service.close()
        // No exception expected
    }

    @Test
    @DisplayName("canConnect mirrors isActive")
    fun testCanConnect() {
        assertTrue(service.canConnect())
        service.close()
        assertFalse(service.canConnect())
    }

    // ------------------------------------------------------------------
    // Tab creation (asynchronous)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createTab sends chrome.tabs.create and parses response")
    fun testCreateTab() {
        // Run createTab on a background thread since it blocks waiting for a response.
        val future = CompletableFuture.supplyAsync {
            service.createTab("https://example.com")
        }

        // Wait for the message to be sent, then inject the response.
        fakeSender.waitForMessage()
        val sent = ObjectMapper().readTree(fakeSender.lastSentMessage)
        assertEquals("chrome.tabs.create", sent.get("method").asText())
        val requestId = sent.get("id").asLong()

        service.handleIncomingMessage(
            """{"id":$requestId,"result":{"id":42,"url":"https://example.com","title":"Test","type":"page"}}"""
        )

        val tab = future.get(5, TimeUnit.SECONDS)
        assertEquals("42", tab.id)
        assertEquals("https://example.com", tab.url)
        assertEquals("Test", tab.title)
        assertEquals("page", tab.type)
    }

    @Test
    @DisplayName("createTab with no args defaults to about:blank")
    fun testCreateTabDefaults() {
        val future = CompletableFuture.supplyAsync { service.createTab() }

        fakeSender.waitForMessage()
        val sent = ObjectMapper().readTree(fakeSender.lastSentMessage)
        val params = sent.get("params")
        assertEquals("about:blank", params.get(0).get("url").asText())
        val requestId = sent.get("id").asLong()

        service.handleIncomingMessage(
            """{"id":$requestId,"result":{"id":1,"url":"about:blank","type":"page"}}"""
        )
        val tab = future.get(5, TimeUnit.SECONDS)
        assertEquals("1", tab.id)
    }

    // ------------------------------------------------------------------
    // Tab listing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listTabs returns tabs from event cache")
    fun testListTabs() {
        service.handleIncomingMessage(
            """{"method":"chrome.tabs.onCreated","params":[{"id":1,"url":"https://a.com","title":"A","type":"page"}]}"""
        )
        service.handleIncomingMessage(
            """{"method":"chrome.tabs.onCreated","params":[{"id":2,"url":"https://b.com","title":"B","type":"page"}]}"""
        )

        val tabs = service.listTabs()
        assertEquals(2, tabs.size)
        assertEquals("1", tabs[0].id)
        assertEquals("2", tabs[1].id)
    }

    // ------------------------------------------------------------------
    // Tab close
    // ------------------------------------------------------------------

    @Test
    @DisplayName("closeTab sends chrome.tabs.remove and removes from cache")
    fun testCloseTab() {
        service.handleIncomingMessage(
            """{"method":"chrome.tabs.onCreated","params":[{"id":55,"url":"https://x.com","type":"page"}]}"""
        )
        assertEquals(1, service.listTabs().size)

        val future = CompletableFuture.runAsync {
            val tab = BrowserTab().apply { id = "55" }
            service.closeTab(tab)
        }

        fakeSender.waitForMessage()
        val sent = ObjectMapper().readTree(fakeSender.lastSentMessage)
        assertEquals("chrome.tabs.remove", sent.get("method").asText())
        val requestId = sent.get("id").asLong()

        service.handleIncomingMessage("""{"id":$requestId,"result":{}}""")
        future.get(5, TimeUnit.SECONDS)

        assertEquals(0, service.listTabs().size)
    }

    @Test
    @DisplayName("closeTab with non-integer id logs and returns early")
    fun testCloseTabInvalidId() {
        val beforeCount = fakeSender.sentMessages.size
        val tab = BrowserTab().apply { id = "not-a-number" }
        service.closeTab(tab)
        // No message sent
        assertEquals(beforeCount, fakeSender.sentMessages.size)
    }

    // ------------------------------------------------------------------
    // DevTools creation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createDevTools sends chrome.debugger.attach")
    fun testCreateDevTools() {
        service.handleIncomingMessage(
            """{"method":"chrome.tabs.onCreated","params":[{"id":10,"url":"https://dev.com","type":"page"}]}"""
        )

        val future = CompletableFuture.supplyAsync {
            val tab = BrowserTab().apply { id = "10" }
            service.createDevTools(tab, DevToolsConfig())
        }

        fakeSender.waitForMessage()
        val sent = ObjectMapper().readTree(fakeSender.lastSentMessage)
        assertEquals("chrome.debugger.attach", sent.get("method").asText())
        val requestId = sent.get("id").asLong()

        service.handleIncomingMessage("""{"id":$requestId,"result":{}}""")
        val devTools = future.get(5, TimeUnit.SECONDS)

        assertNotNull(devTools)
        assertTrue(devTools.isOpen)
    }

    @Test
    @DisplayName("createDevTools with invalid tab id throws exception")
    fun testCreateDevToolsInvalidTabId() {
        val tab = BrowserTab().apply { id = "not-a-number" }
        assertThrows(Exception::class.java) {
            service.createDevTools(tab, DevToolsConfig())
        }
    }

    // ------------------------------------------------------------------
    // Response routing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("response with matching id completes pending future")
    fun testResponseRouting() {
        val future = service.registerRequest(100L)

        service.handleIncomingMessage("""{"id":100,"result":{"ok":true}}""")

        val response = future.get(1, TimeUnit.SECONDS)
        assertNotNull(response)
        assertEquals(100L, response.get("id").asLong())
        assertTrue(response.get("result").get("ok").asBoolean())
    }

    @Test
    @DisplayName("response with unknown id is silently ignored")
    fun testUnknownResponseId() {
        // Should not throw
        service.handleIncomingMessage("""{"id":999,"result":{}}""")
    }

    @Test
    @DisplayName("cancelRequest removes and cancels pending future")
    fun testCancelRequest() {
        val future = service.registerRequest(200L)
        assertFalse(future.isCancelled)
        service.cancelRequest(200L)
        assertTrue(future.isCancelled)
    }

    // ------------------------------------------------------------------
    // Event handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("chrome.tabs.onRemoved removes tab from cache")
    fun testTabRemovedEvent() {
        service.handleIncomingMessage(
            """{"method":"chrome.tabs.onCreated","params":[{"id":33,"url":"https://gone.com","type":"page"}]}"""
        )
        assertEquals(1, service.listTabs().size)

        service.handleIncomingMessage(
            """{"method":"chrome.tabs.onRemoved","params":[33]}"""
        )
        assertEquals(0, service.listTabs().size)
    }

    @Test
    @DisplayName("chrome.debugger.onEvent routes to correct tab's DevTools")
    fun testDebuggerEventRouting() {
        // Create a tab
        service.handleIncomingMessage(
            """{"method":"chrome.tabs.onCreated","params":[{"id":7,"url":"https://debug.com","type":"page"}]}"""
        )

        // Attach debugger
        val attachFuture = CompletableFuture.supplyAsync {
            val tab = BrowserTab().apply { id = "7" }
            service.createDevTools(tab, DevToolsConfig())
        }
        fakeSender.waitForMessage()
        val attachId = ObjectMapper().readTree(fakeSender.lastSentMessage).get("id").asLong()
        service.handleIncomingMessage("""{"id":$attachId,"result":{}}""")
        val devTools = attachFuture.get(5, TimeUnit.SECONDS)
        assertTrue(devTools.isOpen)

        // Send a CDP event for this tab
        service.handleIncomingMessage(
            """{"method":"chrome.debugger.onEvent","params":[{"tabId":7},"Page.loadEventFired",{"timestamp":123}]}"""
        )
        // Event dispatched without error — DevTools still open
        assertTrue(devTools.isOpen)
    }

    @Test
    @DisplayName("chrome.debugger.onDetach closes the tab's DevTools")
    fun testDebuggerDetachEvent() {
        service.handleIncomingMessage(
            """{"method":"chrome.tabs.onCreated","params":[{"id":8,"url":"https://detach.com","type":"page"}]}"""
        )
        val attachFuture = CompletableFuture.supplyAsync {
            val tab = BrowserTab().apply { id = "8" }
            service.createDevTools(tab, DevToolsConfig())
        }
        fakeSender.waitForMessage()
        val attachId = ObjectMapper().readTree(fakeSender.lastSentMessage).get("id").asLong()
        service.handleIncomingMessage("""{"id":$attachId,"result":{}}""")
        val devTools = attachFuture.get(5, TimeUnit.SECONDS)
        assertTrue(devTools.isOpen)

        service.handleIncomingMessage(
            """{"method":"chrome.debugger.onDetach","params":[{"tabId":8}]}"""
        )
        assertFalse(devTools.isOpen)
    }

    @Test
    @DisplayName("extension.initialized populates tab cache from array")
    fun testExtensionInitialized() {
        service.handleIncomingMessage(
            """{"method":"extension.initialized","params":[
                {"id":1,"url":"https://a.com","title":"A"},
                {"id":2,"url":"https://b.com","title":"B"}
            ]}"""
        )

        val tabs = service.listTabs()
        assertEquals(2, tabs.size)
        assertEquals("1", tabs[0].id)
        assertEquals("2", tabs[1].id)
    }

    @Test
    @DisplayName("malformed JSON message is caught and logged")
    fun testMalformedMessage() {
        // Should not throw
        service.handleIncomingMessage("not valid json {")
    }

    @Test
    @DisplayName("message with neither id nor type is logged and ignored")
    fun testMessageWithNoIdOrType() {
        // Should not throw
        service.handleIncomingMessage("""{"foo":"bar"}""")
    }

    @Test
    @DisplayName("nextId returns monotonically increasing values")
    fun testNextId() {
        val id1 = service.nextId()
        val id2 = service.nextId()
        val id3 = service.nextId()
        assertTrue(id2 > id1)
        assertTrue(id3 > id2)
    }

    // ------------------------------------------------------------------
    // Concurrent request management
    // ------------------------------------------------------------------

    @Test
    @DisplayName("multiple concurrent requests are routed correctly")
    fun testConcurrentRequests() {
        val f1 = service.registerRequest(1L)
        val f2 = service.registerRequest(2L)
        val f3 = service.registerRequest(3L)

        service.handleIncomingMessage("""{"id":2,"result":{"order":"second"}}""")
        service.handleIncomingMessage("""{"id":1,"result":{"order":"first"}}""")
        service.handleIncomingMessage("""{"id":3,"result":{"order":"third"}}""")

        assertEquals("first", f1.get().get("result").get("order").asText())
        assertEquals("second", f2.get().get("result").get("order").asText())
        assertEquals("third", f3.get().get("result").get("order").asText())
    }

    @Test
    @DisplayName("activateTab is a no-op")
    fun testActivateTab() {
        val tab = BrowserTab().apply { id = "1" }
        // Should not throw
        service.activateTab(tab)
    }
}

/**
 * In-memory [ExtensionMessageSender] for unit tests.
 *
 * Records sent messages and provides [waitForMessage] so tests can
 * synchronize with the async operation that produces a message.
 */
class FakeMessageSender : ExtensionMessageSender {
    private val open = AtomicBoolean(true)
    private val latch = java.util.concurrent.atomic.AtomicReference(
        java.util.concurrent.CountDownLatch(1)
    )

    @Volatile
    var lastSentMessage: String? = null
        private set

    val sentMessages = mutableListOf<String>()

    override val isOpen: Boolean get() = open.get()

    override fun sendMessage(text: String) {
        lastSentMessage = text
        sentMessages.add(text)
        // Signal the test thread that a message has been sent.
        latch.get().countDown()
    }

    override fun close() {
        open.set(false)
    }

    /**
     * Blocks until [sendMessage] is called, then resets the latch for the
     * next call.  Used by tests to synchronize with async operations.
     */
    fun waitForMessage() {
        latch.get().await(5, TimeUnit.SECONDS)
        // Reset the latch for the next message
        latch.set(java.util.concurrent.CountDownLatch(1))
    }
}
