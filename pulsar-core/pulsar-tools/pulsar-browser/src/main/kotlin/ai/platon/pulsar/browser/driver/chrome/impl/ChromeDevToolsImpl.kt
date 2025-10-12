package ai.platon.pulsar.browser.driver.chrome.impl

import ai.platon.pulsar.browser.driver.chrome.DevToolsConfig
import ai.platon.pulsar.browser.driver.chrome.MethodInvocation
import ai.platon.pulsar.browser.driver.chrome.RemoteDevTools
import ai.platon.pulsar.browser.driver.chrome.Transport
import ai.platon.pulsar.browser.driver.chrome.impl.EventDispatcher.Companion.ID_PROPERTY
import ai.platon.pulsar.browser.driver.chrome.util.*
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.common.sleepSeconds
import ai.platon.pulsar.common.warnForClose
import com.codahale.metrics.Gauge
import com.codahale.metrics.SharedMetricRegistries
import com.fasterxml.jackson.databind.JsonNode
import com.github.kklisura.cdt.protocol.v2023.support.types.EventHandler
import com.github.kklisura.cdt.protocol.v2023.support.types.EventListener
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.reflect.Method
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class CachedDevToolsInvocationHandlerProxies: KInvocationHandler {
    val commandHandler: DevToolsInvocationHandler = DevToolsInvocationHandler()
    val commands: MutableMap<Method, Any> = ConcurrentHashMap()

    init {
        // println("CommandHandler hashCode: " + commandHandler.hashCode())
    }

    // Typical proxy:
    //   - jdk.proxy1.$Proxy24
    // Typical methods:
    //   - public abstract void com.github.kklisura.cdt.protocol.v2023.commands.Page.enable()
    //   - public abstract com...page.Navigate com...Page.navigate(java.lang.String)
    override suspend fun invoke(proxy: Any, method: Method, args: Array<Any>?): Any? {
        return commands.computeIfAbsent(method) {
            ProxyClasses.createProxy(method.returnType, commandHandler)
        }
    }
}

abstract class ChromeDevToolsImpl(
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

    init {
        browserTransport.addMessageHandler(dispatcher)
        pageTransport.addMessageHandler(dispatcher)
    }

    /**
     * Invokes a remote method and returns the result.
     *
     * NOTE: this method blocks in java proxy objects.
     *
     * For example, when we call `devTools.page.navigate(url)`, the framework translates the function call to `invoke`
     * method, but `devTools.page.navigate(url)` is not a suspend function, so `invoke` has to be wrappered in
     * `runBlocking` method.
     *
     * @param returnProperty The property to return from the response.
     * @param clazz The class of the return type.
     * @param returnTypeClasses The classes of the return type.
     * @param method The method to invoke.
     * @param <T> The return type.
     * @return The result of the invocation.
     * */
    @Throws(ChromeRPCException::class)
    override suspend fun <T> invoke(
        clazz: Class<T>,
        returnProperty: String?,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T? {
        numInvokes.inc()
        lastActiveTime = Instant.now()

        // Send the request and await result in a coroutine-friendly way
        val message = dispatcher.serialize(method)
        // Non-blocking
        val rpcResult = sendAndReceive(method.id, method.method, returnProperty, message)

        if (rpcResult == null) {
            val methodName = method.method
            val readTimeout = config.readTimeout
            throw ChromeRPCTimeoutException("Response timeout $methodName | #${numInvokes.count}, ($readTimeout)")
        }

        return when {
            !rpcResult.isSuccess -> handleFailedFurther(rpcResult.result).let { throw ChromeRPCException(it.first.code, it.second) }
            Void.TYPE == clazz -> null
            returnTypeClasses != null -> dispatcher.deserialize(returnTypeClasses, clazz, rpcResult.result)
            else -> dispatcher.deserialize(clazz, rpcResult.result)
        }
    }

    @Throws(ChromeIOException::class, ChromeRPCException::class)
    override suspend fun invoke(method: String, params: Map<String, Any>?, sessionId: String?): RpcResult? {
        numInvokes.inc()
        lastActiveTime = Instant.now()

        val invocation = DevToolsInvocationHandler.createMethodInvocation(method, params)

        val returnProperty: String? = null

        // Non-blocking
        val message = dispatcher.serialize(invocation.id, invocation.method, invocation.params, sessionId)

        val rpcResult: RpcResult? = sendAndReceive(invocation.id, method, returnProperty, message)

        return rpcResult
    }

    @Throws(ChromeIOException::class, InterruptedException::class)
    private suspend fun sendAndReceive(
        methodId: Long, method: String, returnProperty: String?, rawMessage: String
    ): RpcResult? {
        val future = dispatcher.subscribe(methodId, returnProperty)

        sendToBrowser(method, rawMessage)

        // Await without blocking a thread; enforce the configured timeout.
        val timeoutMillis = config.readTimeout.toMillis()
        val result = withTimeoutOrNull(timeoutMillis) { future.deferred.await() }
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
    private fun handleFailedFurther(result: RpcResult): Pair<ErrorObject, String> {
        return handleFailedFurther(result.result)
    }

    @Throws(ChromeRPCException::class, IOException::class)
    private fun handleFailedFurther(error: JsonNode?): Pair<ErrorObject, String> {
        // Received an error
        val error = dispatcher.deserialize(ErrorObject::class.java, error)
        val sb = StringBuilder(error.message)
        if (error.data != null) {
            sb.append(": ")
            sb.append(error.data)
        }

        return error to sb.toString()
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
            runCatching { doClose() }.onFailure { warnForClose(this, it) }

            // Decrements the count of the latch, releasing all waiting threads if the count reaches zero.
            // If the current count is greater than zero then it is decremented. If the new count is zero then all
            // waiting threads are re-enabled for thread scheduling purposes.
            // If the current count equals zero then nothing happens.
            closeLatch.countDown()
        }
    }

    @Throws(Exception::class)
    private fun doClose() {
        waitUntilIdle(Duration.ofSeconds(10))

        logger.debug("Closing devtools client ...")

        pageTransport.close()
        browserTransport.close()
    }

    private fun waitUntilIdle(timeout: Duration) {
        val endTime = Instant.now().plus(timeout)
        while (dispatcher.hasFutures() && Instant.now().isBefore(endTime)) {
            sleepSeconds(1)
        }
    }
}
