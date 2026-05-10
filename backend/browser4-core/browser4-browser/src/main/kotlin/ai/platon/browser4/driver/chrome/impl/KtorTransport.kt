package ai.platon.browser4.driver.chrome.impl

import ai.platon.browser4.driver.chrome.Transport
import ai.platon.browser4.driver.chrome.util.ChromeDriverException
import ai.platon.browser4.driver.chrome.util.ChromeIOException
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.getTracerOrNull
import ai.platon.pulsar.common.warnForClose
import com.codahale.metrics.SharedMetricRegistries
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import org.apache.commons.lang3.StringUtils
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
    private var session: DefaultClientWebSocketSession? = null
    private val messageConsumer = AtomicReference<Consumer<String>?>(null)

    private val metricsPrefix = "c.i.WebSocketClient"
    private val metrics = SharedMetricRegistries.getOrCreate(AppConstants.DEFAULT_METRICS_NAME)
    private val meterRequests = metrics.meter("$metricsPrefix.requests")

    private var uri: URI? = null

    val id = ID_SUPPLIER.incrementAndGet()

    override val isOpen: Boolean
        get() = (session?.isActive == true) && !closed.get()

    override fun connect(uri: URI) {
        // Normalize localhost to IPv4 on Windows to avoid potential IPv6-only bind issues
        val normalizedUri = normalizeUri(uri)
        this.uri = normalizedUri
        try {
            client = HttpClient(CIO) {
                install(WebSockets) {
                    pingInterval = DEFAULT_PING_INTERVAL_MS.milliseconds
                }
                install(HttpTimeout) {
                    connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MS
                    requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MS
                }
                engine {
                    endpoint {
                        connectAttempts = 1
                        connectTimeout = DEFAULT_CONNECT_TIMEOUT_MS
                        keepAliveTime = DEFAULT_KEEP_ALIVE_TIME_MS
                    }
                }
            }

            tracer?.trace("Connecting to ws {} ...", normalizedUri)
            val ws = runBlocking(Dispatchers.IO) {
                withTimeout(DEFAULT_CONNECT_TIMEOUT_MS) {
                    client!!.webSocketSession(urlString = normalizedUri.toString())
                }
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
                        logger.error("Web socket error | {}\n>>> {} <<<", this@KtorTransport.uri, t.brief())
                    }
                }
            }

            tracer?.trace("Connected to ws server {}", normalizedUri)
        } catch (e: Exception) {
            // Close resources if partially initialized
            runCatching { close() }
            val open = isOpen
            when (e) {
                is ChromeIOException -> throw e
                is TimeoutCancellationException -> throw ChromeIOException("Timed out connecting to ws server | $normalizedUri", e, open)
                is IOException -> throw ChromeIOException("Failed connecting to ws server | $normalizedUri", e, open)
                else -> throw ChromeIOException("Failed connecting to ws server | $normalizedUri", e, open)
            }
        }
    }

    override suspend fun send(message: String) {
        meterRequests.mark()
        val ws = session ?: return

        tracer?.trace("▶ Send {}", shortenMessage(message))

        ws.send(Frame.Text(message))
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
                    runBlocking(Dispatchers.IO) {
                        withTimeout(CLOSE_TIMEOUT_MS) {
                            ws.close(CloseReason(CloseReason.Codes.NORMAL, ""))
                        }
                    }
                }.onFailure {
                    logger.debug("WebSocket close timeout or error, forcing closure | {}", uri)
                    warnForClose(this, it)
                }
            }
            runCatching { client?.close() }.onFailure { warnForClose(this, it) }
        }
    }

    override fun toString(): String {
        return uri?.toString() ?: "ws://"
    }

    private fun shortenMessage(message: String, length: Int = 500): String {
        return StringUtils.abbreviateMiddle(message, "...", length)
    }

    /**
     * On Windows, “localhost” often resolves to IPv6 ::1 first. If Chrome is listening only on IPv4 127.0.0.1 for the
     * DevTools WebSocket, the handshake can silently stall in the socket layer, and Ktor’s webSocketSession may not
     * return quickly without an explicit timeout.
     * */
    private fun normalizeUri(uri: URI): URI {
        // Prefer IPv4 loopback for localhost to avoid potential IPv6-only bind issues on Windows
        return if (uri.host.equals("localhost", ignoreCase = true)) {
            URI(uri.scheme, uri.userInfo, "127.0.0.1", uri.port, uri.path, uri.query, uri.fragment)
        } else uri
    }

    companion object {
        private val ID_SUPPLIER = AtomicInteger()

        private const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 10_000
        private const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 20_000
        private const val DEFAULT_SOCKET_TIMEOUT_MS: Long = 20_000
        private const val DEFAULT_PING_INTERVAL_MS: Long = 15_000
        private const val DEFAULT_KEEP_ALIVE_TIME_MS: Long = 5_000
        private const val CLOSE_TIMEOUT_MS: Long = 3_000

        @Throws(ChromeIOException::class)
        fun create(uri: URI): Transport {
            return KtorTransport().also { it.connect(uri) }
        }
    }
}
