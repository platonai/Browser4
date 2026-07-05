package ai.platon.browser4.chrome.protocol.transport

import ai.platon.browser4.chrome.RemoteChrome
import ai.platon.browser4.chrome.RemoteDevTools
import ai.platon.browser4.chrome.util.ChromeIOException
import ai.platon.browser4.chrome.util.ChromeServiceException
import ai.platon.browser4.api.model.BrowserTab
import ai.platon.browser4.api.model.ChromeVersion
import ai.platon.browser4.api.model.DevToolsConfig
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A [ChromeService] (a.k.a. [RemoteChrome]) that communicates with a browser
 * through the Browser4 Chrome Extension's WebSocket relay protocol.
 *
 * The extension accepts commands of the form:
 * ```
 * {"id": 1, "method": "chrome.debugger.attach",    "params": [{"tabId": 5}]}
 * {"id": 2, "method": "chrome.debugger.sendCommand", "params": [{"tabId": 5}, "Page.enable", {}]}
 * {"id": 3, "method": "chrome.tabs.create",         "params": [{"url": "https://..."}]}
 * {"id": 4, "method": "chrome.tabs.remove",         "params": [5]}
 * ```
 *
 * And emits events of the form:
 * ```
 * {"method": "chrome.tabs.onCreated",  "params": [{...tabInfo...}]}
 * {"method": "chrome.tabs.onRemoved",  "params": [5]}
 * {"method": "chrome.debugger.onEvent","params": [{"tabId": 5}, "Page.loadEventFired", {...}]}
 * {"method": "chrome.debugger.onDetach","params": [{"tabId": 5}]}
 * ```
 *
 * Responses carry the same `id` as the request:
 * ```
 * {"id": 1, "result": {...}}
 * ```
 *
 * The shared [pendingRequests] map and [idCounter] are used by both this
 * class and [ExtensionDevToolsService] so that all responses — regardless of
 * whether the command originated from a tab-management call or a CDP call —
 * are routed through a single channel.
 */
class ExtensionChromeService(
    private val messageSender: ExtensionMessageSender,
    private val sessionId: String
) : RemoteChrome {

    private val logger = LoggerFactory.getLogger(ExtensionChromeService::class.java)
    private val objectMapper = ObjectMapper()

    private val closed = AtomicBoolean(false)
    private val idCounter = AtomicLong(1)

    /** Shared pending-request map. Also used by [ExtensionDevToolsService]. */
    private val pendingRequests = ConcurrentHashMap<Long, CompletableFuture<JsonNode>>()

    /** Tabs known to this service, keyed by string tab id. */
    private val tabs = ConcurrentHashMap<String, BrowserTab>()

    /** DevTools services created per tab. */
    private val devToolsServices = ConcurrentHashMap<String, ExtensionDevToolsService>()

    /**
     * Synthetic version for extension-attached browsers.
     *
     * The Chrome Extension does not expose a `/json/version` endpoint so we
     * return a minimal placeholder.  Callers must handle null values for
     * [ChromeVersion.userAgent] and [ChromeVersion.webSocketDebuggerUrl].
     */
    private val cachedVersion = ChromeVersion()

    // ------------------------------------------------------------------
    // ChromeService
    // ------------------------------------------------------------------

    override val isActive: Boolean get() = !closed.get() && messageSender.isOpen
    override val host: String get() = "extension"
    override val port: Int get() = 0
    override val version: ChromeVersion get() = cachedVersion

    override fun canConnect(): Boolean = isActive

    override fun listTabs(): Array<BrowserTab> = tabs.values.toTypedArray()

    override fun createTab(): BrowserTab {
        return createTab("about:blank")
    }

    override fun createTab(url: String): BrowserTab {
        val id = nextId()
        val future = registerRequest(id)

        val message = buildCommand(id, "chrome.tabs.create", listOf(mapOf("url" to url)))
        send(message)

        val response = awaitAndCleanup(id, future, "createTab")
        val tabJson = response.get("result") ?: response
        val tab = parseTab(tabJson)
        tabs[tab.id] = tab
        return tab
    }

    override fun activateTab(tab: BrowserTab) {
        // The extension does not have an explicit activate command;
        // tab group management handles focus.
    }

    override fun closeTab(tab: BrowserTab) {
        val tabIdInt = tab.id.toIntOrNull()
        if (tabIdInt == null) {
            logger.warn("Cannot close tab with non-integer id: {}", tab.id)
            return
        }

        val id = nextId()
        val future = registerRequest(id)

        val message = buildCommand(id, "chrome.tabs.remove", listOf(tabIdInt))
        send(message)

        awaitAndCleanup(id, future, "closeTab")
        tabs.remove(tab.id)
        devToolsServices.remove(tab.id)?.close()
    }

    override fun createDevTools(tab: BrowserTab, config: DevToolsConfig): RemoteDevTools {
        val tabIdInt = tab.id.toIntOrNull()
            ?: throw ChromeServiceException("Invalid tab id for debugger attach: ${tab.id}")

        val attachId = nextId()
        val attachFuture = registerRequest(attachId)

        val attachMessage = buildCommand(attachId, "chrome.debugger.attach", listOf(mapOf("tabId" to tabIdInt)))
        send(attachMessage)

        awaitAndCleanup(attachId, attachFuture, "chrome.debugger.attach")

        val devTools = ExtensionDevToolsService(messageSender, tab, this)
        devToolsServices[tab.id] = devTools

        return devTools
    }

    // ------------------------------------------------------------------
    // Incoming message routing
    // ------------------------------------------------------------------

    /**
     * Called by [PulsarSessionManager.routeExtensionMessage] when a text
     * message arrives from the extension WebSocket.
     *
     * Routes responses (matched by `id`) to the correct pending future, and
     * dispatches events (matched by `type`) to the appropriate handler.
     */
    fun handleIncomingMessage(text: String) {
        val json: JsonNode = try {
            objectMapper.readTree(text)
        } catch (e: Exception) {
            logger.warn("Failed to parse extension message | sessionId={} | {}", sessionId, text.take(200), e)
            return
        }

        val id = json.get("id")?.asLong()
        val method = json.get("method")?.asText()
        val params = json.get("params")

        when {
            // Response to a pending request (has id, no method)
            id != null && method == null -> {
                val future = pendingRequests.remove(id)
                if (future != null) {
                    future.complete(json)
                } else {
                    logger.debug("No pending request for response id={} | sessionId={}", id, sessionId)
                }
            }

            // Event (has method)
            method != null -> handleEvent(method, params)

            // Unrecognized message
            else -> logger.debug("Unrecognized extension message (no id or method) | sessionId={} | {}",
                sessionId, text.take(200))
        }
    }

    // ------------------------------------------------------------------
    // Shared request infrastructure (also used by ExtensionDevToolsService)
    // ------------------------------------------------------------------

    /** Allocate the next request ID. */
    internal fun nextId(): Long = idCounter.getAndIncrement()

    /** Register a pending future for the given request ID. */
    internal fun registerRequest(id: Long): CompletableFuture<JsonNode> {
        if (closed.get()) throw ChromeIOException("Extension connection is closed")
        val future = CompletableFuture<JsonNode>()
        pendingRequests[id] = future
        return future
    }

    /** Remove a pending future (used on timeout / cancel). */
    internal fun cancelRequest(id: Long) {
        pendingRequests.remove(id)?.cancel(true)
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun handleEvent(type: String, params: JsonNode?) {
        when (type) {
            "chrome.tabs.onCreated" -> {
                val tabJson = params?.get(0) ?: return
                val tab = parseTab(tabJson)
                tabs[tab.id] = tab
            }

            "chrome.tabs.onRemoved" -> {
                val tabId = params?.get(0)?.asText() ?: params?.get(0)?.toString() ?: return
                tabs.remove(tabId)
                devToolsServices.remove(tabId)?.close()
            }

            "chrome.debugger.onEvent" -> {
                val sourceJson = params?.get(0) ?: return
                val tabId = sourceJson.get("tabId")?.asText() ?: return
                val cdpMethod = params.get(1)?.asText() ?: return
                val cdpParams = params.get(2)
                val cdpParamsJson = if (cdpParams != null) objectMapper.writeValueAsString(cdpParams) else "{}"
                devToolsServices[tabId]?.deliverCdpEvent(cdpMethod, cdpParamsJson)
            }

            "chrome.debugger.onDetach" -> {
                val sourceJson = params?.get(0) ?: return
                val tabId = sourceJson.get("tabId")?.asText() ?: return
                devToolsServices[tabId]?.close()
                devToolsServices.remove(tabId)
            }

            "extension.initialized" -> {
                if (params != null && params.isArray) {
                    for (tabJson in params) {
                        val tab = parseTab(tabJson)
                        tabs[tab.id] = tab
                    }
                }
                logger.info("Extension initialized | sessionId={} | tabs={}", sessionId, tabs.size)
            }

            else -> logger.debug("Unhandled extension event | type={} | sessionId={}", type, sessionId)
        }
    }

    private fun parseTab(json: JsonNode): BrowserTab {
        val tab = BrowserTab()
        tab.id = json.get("id")?.toString() ?: ""
        tab.url = json.get("url")?.asText()
        tab.title = json.get("title")?.asText()
        tab.type = json.get("type")?.asText() ?: "page"
        return tab
    }

    internal fun send(message: String) {
        if (!isActive) throw ChromeIOException("Extension connection is closed")
        messageSender.sendMessage(message)
    }

    /** Await a response and clean up the pending map on timeout/error. */
    private fun awaitAndCleanup(
        id: Long,
        future: CompletableFuture<JsonNode>,
        operation: String
    ): JsonNode {
        return try {
            future.get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            pendingRequests.remove(id)?.cancel(true)
            throw ChromeServiceException("$operation timed out or failed: ${e.message}", e)
        }
    }

    private fun buildCommand(id: Long, method: String, params: List<Any?>): String {
        val message = mapOf(
            "id" to id,
            "method" to method,
            "params" to params
        )
        return objectMapper.writeValueAsString(message)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        devToolsServices.values.forEach { it.close() }
        devToolsServices.clear()
        tabs.clear()
        pendingRequests.values.forEach { it.cancel(true) }
        pendingRequests.clear()
        messageSender.close()
    }
}
