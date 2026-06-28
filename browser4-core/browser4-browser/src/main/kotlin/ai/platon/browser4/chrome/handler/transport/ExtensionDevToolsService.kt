package ai.platon.browser4.chrome.handler.transport

import ai.platon.browser4.chrome.RemoteDevTools
import ai.platon.browser4.chrome.util.ChromeIOException
import ai.platon.browser4.chrome.util.ChromeRPCException
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.pulsar.browser.protocol.BrowserTab
import ai.platon.pulsar.browser.protocol.MethodInvocation
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/**
 * A [ChromeDevToolsService] (a.k.a. [RemoteDevTools]) that translates CDP
 * method invocations into `chrome.debugger.sendCommand` messages over the
 * single extension WebSocket relay.
 *
 * Request/response correlation is delegated to the parent
 * [ExtensionChromeService] so that all responses — whether from tab-level
 * commands or CDP commands — are routed through the same pending-request map.
 *
 * Each [ExtensionDevToolsService] is bound to a single tab.  The parent
 * [ExtensionChromeService] routes incoming `chrome.debugger.onEvent` messages
 * to the correct tab's service via [dispatchEvent].
 */
internal class ExtensionDevToolsService(
    private val messageSender: ExtensionMessageSender,
    private val tab: BrowserTab,
    private val parent: ExtensionChromeService
) : RemoteDevTools {

    private val logger = LoggerFactory.getLogger(ExtensionDevToolsService::class.java)

    /** ObjectMapper matching the wire format expected by EventDispatcher. */
    private val objectMapper = ObjectMapper()
        .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true)

    private val closed = AtomicBoolean(false)
    private val closeLatch = CountDownLatch(1)

    /** Event dispatcher that routes incoming CDP events to registered listeners. */
    private val eventDispatcher = EventDispatcher()

    override val isOpen: Boolean get() = !closed.get() && messageSender.isOpen

    // ------------------------------------------------------------------
    // ChromeDevToolsService / RemoteDevTools
    // ------------------------------------------------------------------

    override suspend operator fun <T : Any> invoke(
        method: String,
        params: Map<String, Any?>?,
        returnClass: KClass<T>,
        returnProperty: String?
    ): T? {
        // Use the parent's shared ID counter and pending-request map so that
        // responses arriving on the single WebSocket channel are correctly
        // routed back to this invocation.
        val id = parent.nextId()
        val future = parent.registerRequest(id)

        val tabIdInt = tab.id.toIntOrNull()
            ?: throw ChromeIOException("Invalid tab id: ${tab.id}")
        val cdpParams = params ?: emptyMap()
        val extParams = listOf(mapOf("tabId" to tabIdInt), method, cdpParams)
        val paramsJson = objectMapper.writeValueAsString(extParams)
        val message = """{"id":$id,"type":"chrome.debugger.sendCommand","params":$paramsJson}"""

        if (!isOpen) throw ChromeIOException("DevTools connection is closed for tab ${tab.id}")

        messageSender.sendMessage(message)

        val response: JsonNode = try {
            future.get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            parent.cancelRequest(id)
            throw ChromeRPCException("CDP command '$method' timed out for tab ${tab.id}: ${e.message}", e)
        }

        val error = response.get("error")
        if (error != null && !error.isNull) {
            val errorMsg = error.get("message")?.asText() ?: error.toString()
            throw ChromeRPCException("CDP command '$method' error: $errorMsg")
        }

        val result = response.get("result")
        if (result == null || result.isNull) return null

        return if (returnProperty != null) {
            val propValue = result.get(returnProperty)
            if (propValue != null) objectMapper.treeToValue(propValue, returnClass.java)
            else null
        } else {
            objectMapper.treeToValue(result, returnClass.java)
        }
    }

    /**
     * Compatibility overload using the older [MethodInvocation] + Java reflection style.
     */
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> invoke(
        clazz: Class<T>,
        returnProperty: String?,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T? {
        val kClass = (clazz as Class<Any>).kotlin as KClass<T>
        return invoke(
            method = method.method,
            params = method.params,
            returnClass = kClass,
            returnProperty = returnProperty
        )
    }

    override fun addEventListener(
        domainName: String,
        eventName: String,
        eventHandler: EventHandler<Any>,
        eventType: Class<*>
    ): EventListener {
        val key = "$domainName.$eventName"
        val listener = DevToolsEventListener(key, eventHandler, eventType, this)
        eventDispatcher.registerListener(key, listener)
        return listener
    }

    override fun removeEventListener(eventListener: EventListener) {
        val listener = eventListener as DevToolsEventListener
        eventDispatcher.unregisterListener(listener.key, listener)
    }

    override fun awaitTermination() {
        try {
            closeLatch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ------------------------------------------------------------------
    // Event dispatch (called by ExtensionChromeService)
    // ------------------------------------------------------------------

    /**
     * Dispatch a CDP event from the extension to registered listeners.
     * Called by [ExtensionChromeService] when a `chrome.debugger.onEvent`
     * message arrives for this tab.
     *
     * The event is serialized into CDP wire format and routed through the
     * shared [EventDispatcher], exactly as if it had arrived on a native
     * CDP WebSocket.
     */
    fun dispatchEvent(cdpMethod: String, cdpParams: JsonNode?) {
        if (!isOpen) return

        try {
            // Build a CDP wire-format event message:
            // {"method": "Page.loadEventFired", "params": {...}}
            val paramsJson = if (cdpParams != null && !cdpParams.isNull) {
                objectMapper.writeValueAsString(cdpParams)
            } else {
                "{}"
            }
            val cdpMessage = """{"method":"$cdpMethod","params":$paramsJson}"""

            eventDispatcher.accept(cdpMessage)
        } catch (e: Exception) {
            logger.warn("Failed to dispatch CDP event {} for tab {}: {}", cdpMethod, tab.id, e.message)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        eventDispatcher.close()

        // Best-effort detach from this tab via the shared connection.
        try {
            val detachId = parent.nextId()
            val detachFuture = parent.registerRequest(detachId)
            val tabIdInt = tab.id.toIntOrNull()
            if (tabIdInt != null) {
                val paramsJson = objectMapper.writeValueAsString(listOf(mapOf("tabId" to tabIdInt)))
                val message = """{"id":$detachId,"type":"chrome.debugger.detach","params":$paramsJson}"""
                messageSender.sendMessage(message)
                detachFuture.get(5, TimeUnit.SECONDS)
            }
        } catch (_: Exception) { /* best effort */ }

        closeLatch.countDown()
    }
}
