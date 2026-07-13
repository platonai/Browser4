package ai.platon.browser4.chrome.protocol.transport

import ai.platon.browser4.chrome.RemoteDevTools
import ai.platon.browser4.chrome.Transport
import ai.platon.browser4.chrome.util.*
import ai.platon.cdt.kt.protocol.commands.*
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.browser4.api.model.DevToolsConfig
import ai.platon.browser4.api.model.MethodInvocation
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.common.warnForClose
import ai.platon.browser4.chrome.util.ChromeIOException
import ai.platon.browser4.chrome.util.ChromeRPCException
import ai.platon.browser4.chrome.util.ChromeRPCTimeoutException
import com.codahale.metrics.Gauge
import com.codahale.metrics.SharedMetricRegistries
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Caches Java dynamic proxies for CDP domain command interfaces (e.g., Page, DOM, Network).
 * Replaces the javassist-based [CachedDevToolsInvocationHandlerProxies] which relied on
 * abstract-class proxy generation — this version uses only standard [java.lang.reflect.Proxy].
 */
internal class DomainProxyCache(impl: Any) {
    val commandHandler: DevToolsInvocationHandler = DevToolsInvocationHandler(impl)
    private val proxies: MutableMap<Class<*>, Any> = ConcurrentHashMap()

    @Suppress("UNCHECKED_CAST")
    fun <T> getOrCreate(type: Class<T>): T = proxies.computeIfAbsent(type) {
        ProxyClasses.createProxy(type, commandHandler) as Any
    } as T
}

internal class ChromeDevToolsImpl(
    private val browserTransport: Transport,
    private val pageTransport: Transport,
    private val config: DevToolsConfig
) : RemoteDevTools, AutoCloseable {

    companion object {
        private val startTime = Instant.now()
        private var lastActiveTime = startTime
        private val idleTime get() = Duration.between(lastActiveTime, Instant.now())

        private val metrics = SharedMetricRegistries.getOrCreate(AppConstants.DEFAULT_METRICS_NAME)
        private val metricsPrefix = "c.i.BasicDevTools.global"
        private val numInvokes = metrics.counter("$metricsPrefix.invokes")
        val numAccepts = metrics.counter("$metricsPrefix.accepts")
        private val gauges = mapOf(
            "idleTime" to Gauge { idleTime.readable() }
        )

        init {
            gauges.forEach { (name, gauge) -> metrics.gauge("$metricsPrefix.$name") { gauge } }
        }
    }

    private val logger = LoggerFactory.getLogger(ChromeDevToolsImpl::class.java)

    private val closeLatch = CountDownLatch(1)
    private val closed = AtomicBoolean()
    override val isOpen get() = !closed.get() && pageTransport.isOpen

    private val dispatcher = EventDispatcher()

    /** Caches Java dynamic proxies for CDP domain command interfaces. */
    val domainCache = DomainProxyCache(this)

    init {
        browserTransport.addMessageHandler(dispatcher)
        pageTransport.addMessageHandler(dispatcher)
        // Circular reference: allow command handler to call back into devTools.invoke()
        domainCache.commandHandler.devTools = this
    }

    // ── ChromeDevTools domain properties ──────────────────────────────────────

    override val accessibility: Accessibility get() = domainCache.getOrCreate(Accessibility::class.java)
    override val animation: Animation get() = domainCache.getOrCreate(Animation::class.java)
    override val applicationCache: ApplicationCache get() = domainCache.getOrCreate(ApplicationCache::class.java)
    override val audits: Audits get() = domainCache.getOrCreate(Audits::class.java)
    override val backgroundService: BackgroundService get() = domainCache.getOrCreate(BackgroundService::class.java)
    override val browser: Browser get() = domainCache.getOrCreate(Browser::class.java)
    override val css: CSS get() = domainCache.getOrCreate(CSS::class.java)
    override val cacheStorage: CacheStorage get() = domainCache.getOrCreate(CacheStorage::class.java)
    override val cast: Cast get() = domainCache.getOrCreate(Cast::class.java)
    override val console: Console get() = domainCache.getOrCreate(Console::class.java)
    override val dom: DOM get() = domainCache.getOrCreate(DOM::class.java)
    override val domDebugger: DOMDebugger get() = domainCache.getOrCreate(DOMDebugger::class.java)
    override val domSnapshot: DOMSnapshot get() = domainCache.getOrCreate(DOMSnapshot::class.java)
    override val domStorage: DOMStorage get() = domainCache.getOrCreate(DOMStorage::class.java)
    override val database: Database get() = domainCache.getOrCreate(Database::class.java)
    override val debugger: Debugger get() = domainCache.getOrCreate(Debugger::class.java)
    override val deviceOrientation: DeviceOrientation get() = domainCache.getOrCreate(DeviceOrientation::class.java)
    override val emulation: Emulation get() = domainCache.getOrCreate(Emulation::class.java)
    override val headlessExperimental: HeadlessExperimental get() = domainCache.getOrCreate(HeadlessExperimental::class.java)
    override val io: IO get() = domainCache.getOrCreate(IO::class.java)
    override val indexedDb: IndexedDB get() = domainCache.getOrCreate(IndexedDB::class.java)
    override val input: Input get() = domainCache.getOrCreate(Input::class.java)
    override val inspector: Inspector get() = domainCache.getOrCreate(Inspector::class.java)
    override val layerTree: LayerTree get() = domainCache.getOrCreate(LayerTree::class.java)
    override val log: Log get() = domainCache.getOrCreate(Log::class.java)
    override val memory: Memory get() = domainCache.getOrCreate(Memory::class.java)
    override val network: Network get() = domainCache.getOrCreate(Network::class.java)
    override val overlay: Overlay get() = domainCache.getOrCreate(Overlay::class.java)
    override val page: Page get() = domainCache.getOrCreate(Page::class.java)
    override val performance: Performance get() = domainCache.getOrCreate(Performance::class.java)
    override val performanceTimeline: PerformanceTimeline get() = domainCache.getOrCreate(PerformanceTimeline::class.java)
    override val security: Security get() = domainCache.getOrCreate(Security::class.java)
    override val serviceWorker: ServiceWorker get() = domainCache.getOrCreate(ServiceWorker::class.java)
    override val storage: Storage get() = domainCache.getOrCreate(Storage::class.java)
    override val systemInfo: SystemInfo get() = domainCache.getOrCreate(SystemInfo::class.java)
    override val target: ai.platon.cdt.kt.protocol.commands.Target get() = domainCache.getOrCreate(ai.platon.cdt.kt.protocol.commands.Target::class.java)
    override val tethering: Tethering get() = domainCache.getOrCreate(Tethering::class.java)
    override val tracing: Tracing get() = domainCache.getOrCreate(Tracing::class.java)
    override val fetch: Fetch get() = domainCache.getOrCreate(Fetch::class.java)
    override val webAudio: WebAudio get() = domainCache.getOrCreate(WebAudio::class.java)
    override val webAuthn: WebAuthn get() = domainCache.getOrCreate(WebAuthn::class.java)
    override val media: Media get() = domainCache.getOrCreate(Media::class.java)
    override val heapProfiler: HeapProfiler get() = domainCache.getOrCreate(HeapProfiler::class.java)
    override val profiler: Profiler get() = domainCache.getOrCreate(Profiler::class.java)
    override val runtime: Runtime get() = domainCache.getOrCreate(Runtime::class.java)
    override val schema: Schema get() = domainCache.getOrCreate(Schema::class.java)

    @Throws(ChromeIOException::class, ChromeRPCException::class)
    override suspend operator fun <T : Any> invoke(
        method: String, params: Map<String, Any?>?, returnClass: KClass<T>, returnProperty: String?
    ): T? {
        val invocation = DevToolsInvocationHandler.createMethodInvocation(method, params)

        // Non-blocking
        val message = dispatcher.serialize(invocation.id, invocation.method, invocation.params, null)

        val rpcResult = sendAndReceive(invocation.id, method, returnProperty, message)
        if (rpcResult == null) {
            throw ChromeRPCTimeoutException(
                "No response | $method | #${numInvokes.count}, (${config.readTimeout})"
            )
        }
        val jsonNode = rpcResult.result ?: return null

        return dispatcher.deserialize(returnClass.java, jsonNode)
    }

    /**
     * Invokes a remote method and returns the result.
     *
     * This method is designed to be non-blocking, but it is often called in blocking methods
     * from Java proxy objects. For example, when calling `devTools.page.navigate(url)`, the
     * framework translates the function call to this `invoke` method. Since `devTools.page.navigate(url)`
     * is not a suspend function, this method is wrapped in `runBlocking` to ensure compatibility.
     *
     * @param clazz The class of the return type. This is used to deserialize the result into the expected type.
     * @param returnProperty The property to return from the response. This is optional and can be null.
     * @param returnTypeClasses An array of classes representing the return type. This is used for deserialization
     *                          when the return type involves generics or complex types.
     * @param method The `MethodInvocation` object containing details about the method to invoke, such as its ID,
     *               name, and parameters.
     * @param <T> The generic return type of the method.
     * @return The result of the invocation, deserialized into the specified type `T`, or null if the result is not available.
     * @throws ChromeRPCException If the remote procedure call fails or the result indicates an error.
     * @throws ChromeRPCTimeoutException If the response times out based on the configured read timeout.
     */
    @Throws(ChromeRPCException::class)
    override suspend fun <T> invoke(
        clazz: Class<T>,
        returnProperty: String?,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T? = invokeInternal(clazz, returnProperty, returnTypeClasses, method, null)

    @Throws(ChromeRPCException::class)
    internal suspend fun <T> invokeInternal(
        clazz: Class<T>,
        returnProperty: String?,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation,
        // for test purpose
        mockRpcResult: RpcResult? = null
    ): T? {
        numInvokes.inc()

        // Serialize the method invocation into a message to be sent to the remote server.
        val message = dispatcher.serialize(method)

        // Send the request and await the result in a coroutine-friendly way.
        val rpcResult = mockRpcResult ?: sendAndReceive(method.id, method.method, returnProperty, message)

        // If no result is received within the timeout, throw a timeout exception.
        if (rpcResult == null) {
            val methodName = method.method
            val readTimeout = config.readTimeout
            throw ChromeRPCTimeoutException("No response | $methodName | #${numInvokes.count}, ($readTimeout)")
        }

        // Handle the result based on its success status and the expected return type.
        return when {
            // If the result indicates failure, handle the error and throw an exception.
            !rpcResult.isSuccess -> {
                handleFailedFurther(rpcResult.result).let { e ->
                    //
                    // Known errors:
                    // * -32000L Could not find node with given id
                    if (e.errorCode != -32000L) {
                        // -32000L is expected and handled in higher layer, so no log needed
                        logger.info("Protocol return error. errorCode={}, errorMessage={} | request={}", e.errorCode, e.errorMessage, message)
                    }
                    throw e
                }
            }
            // If the expected return type is `Void`, return null.
            Void.TYPE == clazz -> null
            rpcResult.result == null -> null

            // If returnTypeClasses is provided, use it for deserialization.
            returnTypeClasses != null -> dispatcher.deserialize(returnTypeClasses, clazz, rpcResult.result)

            // Otherwise, deserialize the result using the provided class type.
            else -> dispatcher.deserialize(clazz, rpcResult.result)
        }
    }

    @Throws(ChromeIOException::class)
    private suspend fun sendAndReceive(
        methodId: Long, method: String, returnProperty: String?, rawMessage: String
    ): RpcResult? {
        val future = dispatcher.subscribe(methodId, returnProperty)

        sendToBrowser(method, rawMessage)

        // Await without blocking a thread; enforce the configured timeout.
        val timeoutMillis = config.readTimeout.toMillis()
        val result = withTimeoutOrNull(timeoutMillis.milliseconds) { future.deferred.await() }
        if (result == null) {
            // Ensure we don't leak the future if timed out
            dispatcher.unsubscribe(methodId)
        }

        return result
    }

    /**
     * Send the message to the server and return immediately
     * */
    private suspend fun sendToBrowser(method: String, message: String) {
        // See https://github.com/hardkoded/puppeteer-sharp/issues/796 to understand why we need handle Target methods
        // differently.
        if (method.startsWith("Target.")) {
            browserTransport.send(message)
        } else {
            pageTransport.send(message)
        }
    }

    @Throws(ChromeRPCException::class, IOException::class)
    private fun handleFailedFurther(result: RpcResult): CDPReturnError {
        return handleFailedFurther(result.result)
    }

    @Throws(ChromeRPCException::class, IOException::class)
    private fun handleFailedFurther(error: JsonNode?): CDPReturnError {
        // Received an error
        val error = dispatcher.deserialize(ErrorObject::class.java, error)
        val sb = StringBuilder(error.message)
        if (error.data != null) {
            sb.append(": ")
            sb.append(error.data)
        }

        return CDPReturnError(error.code, error.data, error.message, sb.toString())
    }

    override fun addEventListener(
        domainName: String,
        eventName: String, eventHandler: EventHandler<Any>, eventType: Class<*>
    ): EventListener {
        val key = "$domainName.$eventName"
        val listener = DevToolsEventListener(key, eventHandler, eventType, this)
        dispatcher.registerListener(key, listener)
        return listener
    }

    override fun removeEventListener(eventListener: EventListener) {
        val listener = eventListener as DevToolsEventListener
        dispatcher.unregisterListener(listener.key, listener)
    }

    /**
     * Waits for the DevTool to terminate.
     * */
    override fun awaitTermination() {
        try {
            // block the calling thread
            closeLatch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // discard all furthers in dispatcher?
            runCatching { runBlocking { doClose() } }.onFailure { warnForClose(this, it) }

            // Decrements the count of the latch, releasing all waiting threads if the count reaches zero.
            // If the current count is greater than zero then it is decremented. If the new count is zero then all
            // waiting threads are re-enabled for thread scheduling purposes.
            // If the current count equals zero then nothing happens.
            closeLatch.countDown()
        }
    }

    @Throws(Exception::class)
    private suspend fun doClose() {
        // Use shorter timeout if both transports are already closed/inactive
        // If either transport is still open, use full timeout for graceful shutdown
        val shutdownWaitTimeout = if (pageTransport.isOpen || browserTransport.isOpen) {
            Duration.ofSeconds(10)
        } else {
            Duration.ofSeconds(3)
        }

        waitUntilIdle(shutdownWaitTimeout)

        logger.debug("Closing devtools client ...")

        pageTransport.close()
        browserTransport.close()
    }

    private suspend fun waitUntilIdle(timeout: Duration) {
        val endTime = Instant.now().plus(timeout)
        while (dispatcher.hasFutures() && Instant.now().isBefore(endTime)) {
            delay(1.seconds)
        }
    }
}
