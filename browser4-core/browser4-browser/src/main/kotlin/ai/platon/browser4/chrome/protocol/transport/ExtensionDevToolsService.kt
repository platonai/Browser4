package ai.platon.browser4.chrome.handler.transport

import ai.platon.browser4.chrome.RemoteDevTools
import ai.platon.browser4.chrome.util.ChromeIOException
import ai.platon.browser4.chrome.util.ChromeRPCException
import ai.platon.cdt.kt.protocol.ChromeDevTools
import ai.platon.cdt.kt.protocol.commands.*
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.pulsar.browser.protocol.BrowserTab
import ai.platon.pulsar.browser.protocol.MethodInvocation
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

/**
 * A [RemoteDevTools] bound to a single tab via the Browser4 Chrome Extension
 * WebSocket relay.
 *
 * CDP commands (wrapped as `chrome.debugger.sendCommand`) and responses are
 * correlated through the parent [ExtensionChromeService]'s shared
 * request/response map.  CDP events from the extension are serialized into
 * wire format and dispatched via [EventDispatcher].
 *
 * Domain property accessors (page, dom, network, …) are stubbed — the
 * proxy-based invocation path used by ChromeDevToolsImpl is not available
 * for the extension transport.  Commands go through [invoke].
 */
internal class ExtensionDevToolsService(
    private val messageSender: ExtensionMessageSender,
    private val tab: BrowserTab,
    private val parent: ExtensionChromeService
) : RemoteDevTools {

    private val logger = LoggerFactory.getLogger(ExtensionDevToolsService::class.java)

    /** ObjectMapper matching EventDispatcher's wire format. */
    private val objectMapper = EventDispatcher.OBJECT_MAPPER

    private val closed = AtomicBoolean(false)
    private val closeLatch = CountDownLatch(1)

    /** Event dispatcher that routes incoming CDP events to registered listeners. */
    private val eventDispatcher = EventDispatcher()

    override val isOpen: Boolean get() = !closed.get() && messageSender.isOpen

    // ------------------------------------------------------------------
    // Core invoke — wraps CDP command in extension protocol
    // ------------------------------------------------------------------

    override suspend operator fun <T : Any> invoke(
        method: String,
        params: Map<String, Any?>?,
        returnClass: KClass<T>,
        returnProperty: String?
    ): T? {
        val tabIdInt = tab.id.toIntOrNull()
            ?: throw ChromeIOException("Invalid tab id: ${tab.id}")

        if (!isOpen) throw ChromeIOException("DevTools connection is closed for tab ${tab.id}")

        val id = parent.nextId()
        val future = parent.registerRequest(id)

        // Wrap in extension protocol: {id, method, params: [{tabId}, method, params]}
        val cdpParams = params ?: emptyMap()
        val extParams = listOf(mapOf("tabId" to tabIdInt), method, cdpParams)
        val message = objectMapper.writeValueAsString(mapOf(
            "id" to id,
            "method" to "chrome.debugger.sendCommand",
            "params" to extParams
        ))

        messageSender.sendMessage(message)

        val response: JsonNode = try {
            withContext(Dispatchers.IO) { future.get(30, TimeUnit.SECONDS) }
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
     * Compatibility overload (Java reflection style).
     */
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> invoke(
        clazz: Class<T>,
        returnProperty: String?,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T? {
        @Suppress("UNCHECKED_CAST")
        return invoke(
            method = method.method,
            params = method.params,
            returnClass = (clazz as Class<*>).kotlin as KClass<Any>,
            returnProperty = returnProperty
        ) as T
    }

    // ------------------------------------------------------------------
    // Event listeners
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Event delivery from ExtensionChromeService
    // ------------------------------------------------------------------

    /**
     * Deliver a CDP event from the extension to registered listeners.
     * The event is serialized to CDP wire format and fed through
     * [EventDispatcher.accept], which routes it to matching
     * [addEventListener] registrations.
     */
    fun deliverCdpEvent(cdpMethod: String, cdpParamsJson: String) {
        if (!isOpen) return

        try {
            val cdpMessage = objectMapper.writeValueAsString(mapOf(
                "method" to cdpMethod,
                "params" to objectMapper.readTree(cdpParamsJson)
            ))
            eventDispatcher.accept(cdpMessage)
        } catch (e: Exception) {
            logger.warn("Failed to deliver CDP event {} for tab {}: {}", cdpMethod, tab.id, e.message)
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun awaitTermination() {
        try {
            closeLatch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        eventDispatcher.close()

        // Best-effort detach via the shared connection
        val tabIdInt = tab.id.toIntOrNull()
        if (tabIdInt != null && messageSender.isOpen) {
            try {
                val detachId = parent.nextId()
                val detachFuture = parent.registerRequest(detachId)
                val message = objectMapper.writeValueAsString(mapOf(
                    "id" to detachId,
                    "method" to "chrome.debugger.detach",
                    "params" to listOf(mapOf("tabId" to tabIdInt))
                ))
                messageSender.sendMessage(message)
                detachFuture.get(5, TimeUnit.SECONDS)
            } catch (_: Exception) { /* best effort */ }
        }

        closeLatch.countDown()
    }

    // ------------------------------------------------------------------
    // ChromeDevTools domain properties (stubs for interface compliance)
    //
    // These are not used through the extension transport — all CDP
    // commands go through [invoke].  The properties exist only to
    // satisfy the [ChromeDevTools] super-interface contract.
    // ------------------------------------------------------------------

    override val accessibility: Accessibility get() = error("not available via extension transport; use invoke()")
    override val animation: Animation get() = error("not available via extension transport; use invoke()")
    override val applicationCache: ApplicationCache get() = error("not available via extension transport; use invoke()")
    override val audits: Audits get() = error("not available via extension transport; use invoke()")
    override val backgroundService: BackgroundService get() = error("not available via extension transport; use invoke()")
    override val browser: ai.platon.cdt.kt.protocol.commands.Browser get() = error("not available via extension transport; use invoke()")
    override val css: CSS get() = error("not available via extension transport; use invoke()")
    override val cacheStorage: CacheStorage get() = error("not available via extension transport; use invoke()")
    override val cast: Cast get() = error("not available via extension transport; use invoke()")
    override val console: Console get() = error("not available via extension transport; use invoke()")
    override val dom: DOM get() = error("not available via extension transport; use invoke()")
    override val domDebugger: DOMDebugger get() = error("not available via extension transport; use invoke()")
    override val domSnapshot: DOMSnapshot get() = error("not available via extension transport; use invoke()")
    override val domStorage: DOMStorage get() = error("not available via extension transport; use invoke()")
    override val database: Database get() = error("not available via extension transport; use invoke()")
    override val debugger: Debugger get() = error("not available via extension transport; use invoke()")
    override val deviceOrientation: DeviceOrientation get() = error("not available via extension transport; use invoke()")
    override val emulation: Emulation get() = error("not available via extension transport; use invoke()")
    override val headlessExperimental: HeadlessExperimental get() = error("not available via extension transport; use invoke()")
    override val io: IO get() = error("not available via extension transport; use invoke()")
    override val indexedDb: IndexedDB get() = error("not available via extension transport; use invoke()")
    override val input: Input get() = error("not available via extension transport; use invoke()")
    override val inspector: Inspector get() = error("not available via extension transport; use invoke()")
    override val layerTree: LayerTree get() = error("not available via extension transport; use invoke()")
    override val log: Log get() = error("not available via extension transport; use invoke()")
    override val memory: Memory get() = error("not available via extension transport; use invoke()")
    override val network: Network get() = error("not available via extension transport; use invoke()")
    override val overlay: Overlay get() = error("not available via extension transport; use invoke()")
    override val page: Page get() = error("not available via extension transport; use invoke()")
    override val performance: Performance get() = error("not available via extension transport; use invoke()")
    override val performanceTimeline: PerformanceTimeline get() = error("not available via extension transport; use invoke()")
    override val security: Security get() = error("not available via extension transport; use invoke()")
    override val serviceWorker: ServiceWorker get() = error("not available via extension transport; use invoke()")
    override val storage: Storage get() = error("not available via extension transport; use invoke()")
    override val systemInfo: SystemInfo get() = error("not available via extension transport; use invoke()")
    override val target: ai.platon.cdt.kt.protocol.commands.Target get() = error("not available via extension transport; use invoke()")
    override val tethering: Tethering get() = error("not available via extension transport; use invoke()")
    override val tracing: Tracing get() = error("not available via extension transport; use invoke()")
    override val fetch: Fetch get() = error("not available via extension transport; use invoke()")
    override val webAudio: WebAudio get() = error("not available via extension transport; use invoke()")
    override val webAuthn: WebAuthn get() = error("not available via extension transport; use invoke()")
    override val media: Media get() = error("not available via extension transport; use invoke()")
    override val heapProfiler: HeapProfiler get() = error("not available via extension transport; use invoke()")
    override val profiler: Profiler get() = error("not available via extension transport; use invoke()")
    override val runtime: Runtime get() = error("not available via extension transport; use invoke()")
    override val schema: Schema get() = error("not available via extension transport; use invoke()")
}
