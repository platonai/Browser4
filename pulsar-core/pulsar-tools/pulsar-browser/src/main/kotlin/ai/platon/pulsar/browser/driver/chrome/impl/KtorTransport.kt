package ai.platon.pulsar.browser.driver.chrome.impl

import ai.platon.pulsar.browser.driver.chrome.Transport
import ai.platon.pulsar.browser.driver.chrome.util.ChromeDriverException
import ai.platon.pulsar.browser.driver.chrome.util.ChromeIOException
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.getTracerOrNull
import ai.platon.pulsar.common.warnForClose
import com.codahale.metrics.SharedMetricRegistries
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.io.IOException
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Ktor-based WebSocket transport that supports Kotlin coroutines under the hood,
 * but exposes the legacy Transport interface for compatibility.
 */
class KtorTransport : Transport {
    private val logger = getLogger(this)
    private val tracer = getTracerOrNull(this)
    private val closed = AtomicBoolean()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var client: HttpClient? = null
    private var session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null
    private val messageConsumer = AtomicReference<Consumer<String>?>()

    private val metricsPrefix = "c.i.WebSocketClient"
    private val metrics = SharedMetricRegistries.getOrCreate(AppConstants.DEFAULT_METRICS_NAME)
    private val meterRequests = metrics.meter("$metricsPrefix.requests")

    private var uri: URI? = null

    val id = instanceSequencer.incrementAndGet()
    override val isOpen: Boolean
        get() = (session?.isActive == true) && !closed.get()

    override fun connect(uri: URI) {
        this.uri = uri
        try {
            client = HttpClient(CIO) {
                install(WebSockets)
            }

            val ws = runBlocking(Dispatchers.IO) {
                client!!.webSocketSession(urlString = uri.toString())
            }
            session = ws

            // Start a receiver loop to dispatch incoming text frames to the consumer
            scope.launch {
                try {
                    while (isActive && ws.isActive) {
                        val frame = ws.incoming.receiveCatching().getOrNull() ?: break
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            messageConsumer.get()?.accept(text)
                        }
                    }
                } catch (t: Throwable) {
                    if (!closed.get()) {
                        logger.error("Web socket error | {}\n>>> {} <<<", uri, t.brief())
                    }
                }
            }

            tracer?.trace("Connected to ws server {}", uri)
        } catch (e: Exception) {
            // Close resources if partially initialized
            runCatching { close() }
            val open = isOpen
            when (e) {
                is ChromeIOException -> throw e
                is IOException -> throw ChromeIOException("Failed connecting to ws server | $uri", e, open)
                else -> throw ChromeIOException("Failed connecting to ws server | $uri", e, open)
            }
        }
    }

    override fun send(message: String) {
        meterRequests.mark()
        val ws = session ?: throw ChromeIOException("WebSocket session is not open", isOpen = false)
        try {
            tracer?.trace("▶ Send {}", shortenMessage(message))
            runBlocking(Dispatchers.IO) {
                ws.send(Frame.Text(message))
            }
        } catch (e: CancellationException) {
            throw ChromeIOException("Failed to send message (cancelled)", e, isOpen)
        } catch (e: Exception) {
            throw ChromeIOException("Failed to send message", e, isOpen)
        }
    }

    override suspend fun sendDeferred(message: String) {
        meterRequests.mark()
        val ws = session ?: throw ChromeIOException("WebSocket session is not open", isOpen = false)
        try {
            tracer?.trace("▶ Send {}", shortenMessage(message))
            ws.send(Frame.Text(message))
        } catch (e: CancellationException) {
            throw ChromeIOException("Failed to send message (cancelled)", e, isOpen)
        } catch (e: Exception) {
            throw ChromeIOException("Failed to send message", e, isOpen)
        }
    }

    override fun sendAsync(message: String): Future<Void> {
        meterRequests.mark()
        val future = CompletableFuture<Void>()
        val ws = session ?: run {
            future.completeExceptionally(ChromeIOException("WebSocket session is not open", isOpen = false))
            return future
        }
        tracer?.trace("▶ Send {}", shortenMessage(message))
        scope.launch {
            try {
                ws.send(Frame.Text(message))
                future.complete(null)
            } catch (e: Exception) {
                future.completeExceptionally(ChromeIOException("Failed to send message, caused by ${e.message}", e, isOpen))
            }
        }
        return future
    }

    override fun addMessageHandler(consumer: Consumer<String>) {
        if (!messageConsumer.compareAndSet(null, consumer)) {
            throw ChromeDriverException("You are already subscribed to this web socket service.")
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val ws = session
            session = null
            scope.cancel()
            if (ws != null) {
                runCatching {
                    runBlocking(Dispatchers.IO) { ws.close(CloseReason(CloseReason.Codes.NORMAL, "")) }
                }.onFailure { warnForClose(this, it) }
            }
            runCatching { client?.close() }.onFailure { warnForClose(this, it) }
        }
    }

    override fun toString(): String {
        return uri?.toString() ?: "ws://"
    }

    private fun shortenMessage(message: String, length: Int = 500): String {
        return org.apache.commons.lang3.StringUtils.abbreviateMiddle(message, "...", length)
    }

    companion object {
        private val instanceSequencer = AtomicInteger()

        @Throws(ChromeIOException::class)
        fun create(uri: URI): Transport {
            return KtorTransport().also { it.connect(uri) }
        }
    }
}
