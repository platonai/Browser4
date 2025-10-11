package ai.platon.pulsar.browser.driver.chrome.impl

import ai.platon.pulsar.browser.driver.chrome.*
import ai.platon.pulsar.browser.driver.chrome.util.*
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.common.sleepSeconds
import ai.platon.pulsar.common.warnForClose
import com.codahale.metrics.Gauge
import com.codahale.metrics.SharedMetricRegistries
import com.github.kklisura.cdt.protocol.v2023.support.types.EventHandler
import com.github.kklisura.cdt.protocol.v2023.support.types.EventListener
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

abstract class ChromeDevToolsImpl(
    private val browserTransport: Transport,
    private val pageTransport: Transport,
    private val config: DevToolsConfig
) : RemoteDevTools, AutoCloseable {

    companion object {
        private val instanceSequencer = AtomicInteger()

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
    private val id = instanceSequencer.incrementAndGet()

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
     * The method is blocking and will wait for the response.
     *
     * TODO: use non-blocking version
     *
     * @param returnProperty The property to return from the response.
     * @param clazz The class of the return type.
     * @param <T> The return type.
     * @return The result of the invocation.
     * */
    @Throws(InterruptedException::class)
    open operator fun <T> invoke(
        returnProperty: String,
        clazz: Class<T>,
        methodInvocation: MethodInvocation
    ): T? {
        try {
            return invoke0(returnProperty, clazz, null, methodInvocation)
        }  catch (e: ChromeIOException) {
            // TODO: if the connection is lost, we should close the browser and restart it
            throw ChromeRPCException("Web socket connection lost", e)
        } catch (e: InterruptedException) {
            logger.warn("Interrupted while invoke ${clazz::javaClass.name}.${methodInvocation.method}")
            Thread.currentThread().interrupt()
            return null
        } catch (e: IOException) {
            throw ChromeRPCException("Failed reading response message", e)
        }
    }
    
    /**
     * Invokes a remote method and returns the result.
     * The method is blocking and will wait for the response.
     *
     * TODO: use non-blocking version
     *
     * @param returnProperty The property to return from the response.
     * @param clazz The class of the return type.
     * @param returnTypeClasses The classes of the return type.
     * @param method The method to invoke.
     * @param <T> The return type.
     * @return The result of the invocation.
     * */
    @Throws(ChromeIOException::class, ChromeRPCException::class)
    override fun <T> invoke(
        returnProperty: String?,
        clazz: Class<T>,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T? {
        try {
            return invoke0(returnProperty, clazz, returnTypeClasses, method)
        } catch (e: InterruptedException) {
            logger.warn("Interrupted while invoke ${method.method}")
            Thread.currentThread().interrupt()
            return null
        }
    }

    @Throws(ChromeIOException::class, ChromeRPCException::class)
    override suspend fun <T> invokeDeferred(
        returnProperty: String?,
        clazz: Class<T>,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T? {
        try {
            return invoke0Deferred(returnProperty, clazz, returnTypeClasses, method)
        } catch (e: InterruptedException) {
            logger.warn("Interrupted while invoke ${method.method}")
            Thread.currentThread().interrupt()
            return null
        }
    }

    @Throws(ChromeIOException::class, InterruptedException::class, ChromeRPCException::class)
    private fun <T> invoke0(
        returnProperty: String?,
        clazz: Class<T>,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T? {
        numInvokes.inc()
        lastActiveTime = Instant.now()

        // blocks the current thread which is optimized by Kotlin since this method is running within
        // withContext(Dispatchers.IO), so it's OK for the client code to run efficiently.
        val (future, responded) = invoke1(returnProperty, method)
        
        if (!responded) {
            val methodName = method.method
            val readTimeout = config.readTimeout
            throw ChromeRPCTimeoutException("Response timeout $methodName | #${numInvokes.count}, ($readTimeout)")
        }
        
        return when {
            !future.isSuccess -> handleFailedFurther(future).let { throw ChromeRPCException(it.first.code, it.second) }
            Void.TYPE == clazz -> null
            returnTypeClasses != null -> dispatcher.deserialize(returnTypeClasses, clazz, future.result)
            else -> dispatcher.deserialize(clazz, future.result)
        }
    }

    @Throws(ChromeIOException::class, InterruptedException::class, ChromeRPCException::class)
    private suspend fun <T> invoke0Deferred(
        returnProperty: String?,
        clazz: Class<T>,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T? {
        numInvokes.inc()
        lastActiveTime = Instant.now()

        // blocks the current thread which is optimized by Kotlin since this method is running within
        // withContext(Dispatchers.IO), so it's OK for the client code to run efficiently.
        val (future, responded) = invoke1Deferred(returnProperty, method)

        if (!responded) {
            val methodName = method.method
            val readTimeout = config.readTimeout
            throw ChromeRPCTimeoutException("Response timeout $methodName | #${numInvokes.count}, ($readTimeout)")
        }

        return when {
            !future.isSuccess -> handleFailedFurther(future).let { throw ChromeRPCException(it.first.code, it.second) }
            Void.TYPE == clazz -> null
            returnTypeClasses != null -> dispatcher.deserialize(returnTypeClasses, clazz, future.result)
            else -> dispatcher.deserialize(clazz, future.result)
        }
    }

    @Throws(ChromeIOException::class, InterruptedException::class)
    private fun invoke1(
        returnProperty: String?,
        method: MethodInvocation
    ): Pair<InvocationFuture, Boolean> {
        val future = dispatcher.subscribe(method.id, returnProperty)
        val message = dispatcher.serialize(method)
        
        // See https://github.com/hardkoded/puppeteer-sharp/issues/796 to understand why we need handle Target methods
        // differently.
        if (method.method.startsWith("Target.")) {
            browserTransport.sendAsync(message)
        } else {
            pageTransport.sendAsync(message)
        }

        // await() blocks the current thread
        // 1. the current thread is optimized by Kotlin since this method is running within withContext(Dispatchers.IO)
        // 2. there are still better solutions to avoid blocking the current thread
        // 3. it is unclear whether there is a significant performance improvement by using non-blocking solution
        // 4. unfortunately, there is no easy way to combine the coroutine with the [ProxyClasses.createProxyFromAbstract]
        // 5. a possible solutions is to send CDP messages directly instead of using the proxy classes
        // 6. kotlin channel can help which do not block the current thread

        // see: https://ktor.io/docs/websocket-client.html
        val responded = future.await(config.readTimeout)
        dispatcher.unsubscribe(method.id)
        
        return future to responded
    }

    @Throws(ChromeIOException::class, InterruptedException::class)
    private suspend fun invoke1Deferred(
        returnProperty: String?,
        method: MethodInvocation
    ): Pair<InvocationFuture, Boolean> {
        val future = dispatcher.subscribe(method.id, returnProperty)
        val message = dispatcher.serialize(method)

        // See https://github.com/hardkoded/puppeteer-sharp/issues/796 to understand why we need handle Target methods
        // differently.
        if (method.method.startsWith("Target.")) {
            browserTransport.sendDeferred(message)
        } else {
            pageTransport.sendDeferred(message)
        }

        val responded = future.await(config.readTimeout)
        dispatcher.unsubscribe(method.id)

        return future to responded
    }

    @Throws(ChromeRPCException::class, IOException::class)
    private fun handleFailedFurther(future: InvocationFuture): Pair<ErrorObject, String> {
        // Received an error
        val error = dispatcher.deserialize(ErrorObject::class.java, future.result)
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
