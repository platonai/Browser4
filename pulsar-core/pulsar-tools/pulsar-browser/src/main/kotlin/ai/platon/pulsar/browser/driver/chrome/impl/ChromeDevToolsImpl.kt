package ai.platon.pulsar.browser.driver.chrome.impl

import ai.platon.pulsar.browser.driver.chrome.DevToolsConfig
import ai.platon.pulsar.browser.driver.chrome.MethodInvocation
import ai.platon.pulsar.browser.driver.chrome.RemoteDevTools
import ai.platon.pulsar.browser.driver.chrome.Transport
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
import kotlin.code
import kotlin.reflect.KClass

class CachedDevToolsInvocationHandlerProxies : KInvocationHandler {
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

    @Throws(ChromeIOException::class, ChromeRPCException::class)
    override suspend operator fun <T : Any> invoke(
        method: String, params: Map<String, Any?>?, returnClass: KClass<T>, returnProperty: String?
    ): T? {
        val invocation = DevToolsInvocationHandler.createMethodInvocation(method, params)

        // Non-blocking
        val message = dispatcher.serialize(invocation.id, invocation.method, invocation.params, null)

        val rpcResult = sendAndReceive(invocation.id, method, returnProperty, message) ?: return null
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
    ): T? {
        // Serialize the method invocation into a message to be sent to the remote server.
        val message = dispatcher.serialize(method)

        // Send the request and await the result in a coroutine-friendly way.
        val rpcResult = sendAndReceive(method.id, method.method, returnProperty, message)

        // If no result is received within the timeout, throw a timeout exception.
        if (rpcResult == null) {
            val methodName = method.method
            val readTimeout = config.readTimeout
            throw ChromeRPCTimeoutException("Response timeout $methodName | #${numInvokes.count}, ($readTimeout)")
        }

        // Handle the result based on its success status and the expected return type.
        return when {
            // If the result indicates failure, handle the error and throw an exception.
            !rpcResult.isSuccess -> handleFailedFurther(rpcResult.result).let {
                throw ChromeRPCException(
                    it.first.code,
                    it.second
                )
            }

            // If the expected return type is `Void`, return null.
            Void.TYPE == clazz -> null

            // If returnTypeClasses is provided, use it for deserialization.
            returnTypeClasses != null -> dispatcher.deserialize(returnTypeClasses, clazz, rpcResult.result)

            // Otherwise, deserialize the result using the provided class type.
            else -> dispatcher.deserialize(clazz, rpcResult.result)
        }
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
