package ai.platon.browser4.chrome.handler.transport

import ai.platon.browser4.chrome.RemoteDevTools
import ai.platon.browser4.chrome.Transport
import ai.platon.browser4.chrome.util.*
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.pulsar.browser.impl.DevToolsConfig
import ai.platon.pulsar.browser.impl.MethodInvocation
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.common.warnForClose
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds

/**
 * A concrete, native-image-friendly implementation of [RemoteDevTools].
 *
 * All CDP commands flow through [execute] — there is no dynamic proxy, no javassist
 * bytecode generation, and no reflection-based method dispatch. The class is
 * instantiated directly via its constructor.
 */
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
        private val gauges = mapOf(
            "idleTime" to Gauge { idleTime.readable() }
        )

        init {
            gauges.forEach { (name, gauge) -> metrics.gauge("$metricsPrefix.$name") { gauge } }
        }
    }

    private val logger = LoggerFactory.getLogger(ChromeDevToolsImpl::class.java)

    private val idSupplier = AtomicLong(1L)
    private val closeLatch = CountDownLatch(1)
    private val closed = AtomicBoolean()
    override val isOpen get() = !closed.get() && pageTransport.isOpen

    private val dispatcher = EventDispatcher().apply {
        acceptCounter = metrics.counter("$metricsPrefix.accepts")
    }

    init {
        browserTransport.addMessageHandler(dispatcher)
        pageTransport.addMessageHandler(dispatcher)
    }

    /**
     * Executes a CDP command by method name and returns the deserialized result.
     *
     * This is the single entry point for all CDP command dispatch. It replaces the
     * reflection-based proxy system with a straightforward string-keyed dispatch.
     *
     * @param method The CDP method name, e.g. "Page.navigate".
     * @param params The command parameters, or null.
     * @param returnClass The expected return type's class.
     * @param returnProperty An optional property name to extract from the result object.
     * @return The deserialized response, or null for void commands or empty results.
     * @throws ChromeRPCException If the remote procedure call returns an error.
     * @throws ChromeRPCTimeoutException If the response is not received within the configured timeout.
     */
    @Throws(ChromeRPCException::class)
    override suspend fun <T : Any> execute(
        method: String, params: Map<String, Any?>?, returnClass: KClass<T>, returnProperty: String?,
        returnTypeClasses: Array<Class<out Any>>?
    ): T? {
        numInvokes.inc()
        lastActiveTime = Instant.now()

        val invocation = createMethodInvocation(method, params)
        val message = dispatcher.serialize(invocation)

        val rpcResult = sendAndReceive(invocation.id, method, returnProperty, message)

        if (rpcResult == null) {
            val readTimeout = config.readTimeout
            throw ChromeRPCTimeoutException("No response | $method | #${numInvokes.count}, ($readTimeout)")
        }

        return when {
            !rpcResult.isSuccess -> {
                handleFailedFurther(rpcResult.result).let { e ->
                    // Known errors:
                    // * -32000L Could not find node with given id
                    // -32000L is expected and handled in higher layer, so no log needed
                    if (e.errorCode != -32000L) {
                        logger.info(
                            "Protocol return error. errorCode={}, errorMessage={} | request={}",
                            e.errorCode, e.errorMessage, message
                        )
                    }
                    throw e
                }
            }

            Void.TYPE == returnClass.java -> null
            rpcResult.result == null -> null
            returnTypeClasses != null -> dispatcher.deserialize(returnTypeClasses, returnClass.java, rpcResult.result)
            else -> dispatcher.deserialize(returnClass.java, rpcResult.result)
        }
    }

    private fun createMethodInvocation(method: String, params: Map<String, Any?>?): MethodInvocation {
        val params0 = (params ?: emptyMap()).toMutableMap()
        // The 'id' field is transport-level metadata for request/response correlation,
        // not a CDP command parameter — extract it from params and use it as the methodId,
        // but don't leak it into the serialized message's params object.
        val methodId = params0.remove(EventDispatcher.ID_PROPERTY)?.toString()?.toLongOrNull()
            ?: idSupplier.getAndIncrement()
        val params1: Map<String, Any> = params0.entries
            .filter { it.value != null }
            .associate { it.key to it.value as Any }
        return MethodInvocation(methodId, method, params1)
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
     * Send the message to the server and return immediately.
     *
     * Target-related commands (Target.createTarget, Target.closeTarget, etc.) are sent via
     * the browser-level transport because they operate on the browser session, not a specific
     * page. All other commands go through the page-level transport.
     *
     * @see https://github.com/hardkoded/puppeteer-sharp/issues/796
     */
    private suspend fun sendToBrowser(method: String, message: String) {
        if (method.startsWith("Target.")) {
            browserTransport.send(message)
        } else {
            pageTransport.send(message)
        }
    }

    @Throws(ChromeRPCException::class, IOException::class)
    private fun handleFailedFurther(errorNode: JsonNode?): CDPReturnError {
        // When isSuccess=false, EventDispatcher stores the error node in RpcResult.result,
        // so the error JSON is in rpcResult.result, not in a dedicated error field.
        val error = dispatcher.deserialize(ErrorObject::class.java, errorNode)
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
     */
    override fun awaitTermination() {
        try {
            closeLatch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { runBlocking { doClose() } }.onFailure { warnForClose(this, it) }

            // Decrements the count of the latch, releasing all waiting threads if the count reaches zero.
            closeLatch.countDown()
        }
    }

    @Throws(Exception::class)
    private suspend fun doClose() {
        // Use shorter timeout if both transports are already closed/inactive.
        // If either transport is still open, use full timeout for graceful shutdown.
        val shutdownWaitTimeout = if (pageTransport.isOpen || browserTransport.isOpen) {
            Duration.ofSeconds(10)
        } else {
            Duration.ofSeconds(3)
        }

        waitUntilIdle(shutdownWaitTimeout)

        logger.debug("Closing devtools client ...")

        // Close transports first to stop new messages from arriving,
        // then clean up the dispatcher (pending futures, listeners, coroutine scope).
        runCatching { pageTransport.close() }
        runCatching { browserTransport.close() }
        dispatcher.close()
    }

    private suspend fun waitUntilIdle(timeout: Duration) {
        val endTime = Instant.now().plus(timeout)
        while (dispatcher.hasFutures() && Instant.now().isBefore(endTime)) {
            delay(100.milliseconds)
        }
    }
}
