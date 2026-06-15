package ai.platon.browser4.chrome.handler.transport

import ai.platon.browser4.chrome.RemoteChrome
import ai.platon.browser4.chrome.RemoteDevTools
import ai.platon.browser4.chrome.Transport
import ai.platon.browser4.chrome.util.ChromeIOException
import ai.platon.browser4.chrome.util.ChromeServiceException
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.pulsar.browser.common.CDTReflectiveMapper
import ai.platon.pulsar.browser.impl.BrowserTab
import ai.platon.pulsar.browser.impl.ChromeVersion
import ai.platon.pulsar.browser.impl.DevToolsConfig
import ai.platon.pulsar.common.NetUtil
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnForClose
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DevToolsEventListener(
    val key: String,
    val handler: EventHandler<Any>,
    val paramType: Class<*>,
    private val devTools: RemoteDevTools
): EventListener, Comparable<DevToolsEventListener> {
    override fun off() {
        unsubscribe()
    }

    override fun unsubscribe() {
        devTools.removeEventListener(this)
    }

    override fun compareTo(other: DevToolsEventListener): Int {
        return this.key.compareTo(other.key)
    }
}

internal class ChromeImpl(
    override var host: String = LOCALHOST,
    override var port: Int = 0
) : RemoteChrome {
    companion object {
        const val ABOUT_BLANK_PAGE = "about:blank"
        const val LOCALHOST = "localhost"

        const val LIST_TABS = "json/list"
        const val CREATE_TAB = "json/new"
        const val ACTIVATE_TAB = "json/activate"
        const val CLOSE_TAB = "json/close"
        const val VERSION = "json/version"
    }

    enum class HttpMethod {
        CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, TRACE
    }

    private val logger = getLogger(this)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * DevTools map, the key is the Chrome tab id.
     * */
    private val remoteDevTools = ConcurrentHashMap<String, RemoteDevTools>()
    private val closed = AtomicBoolean()

    override val isActive get() = !closed.get()

    private val _version: Lazy<ChromeVersion> = lazy { refreshVersion() }

    /**
     * The Chrome version.
     * */
    override val version get() = _version.value

    constructor(port: Int) : this(LOCALHOST, port)

    @Throws(ChromeIOException::class)
    override fun listTabs(): Array<BrowserTab> {
        return try {
            request(Array<BrowserTab>::class.java, HttpMethod.GET, "http://%s:%d/%s", host, port, LIST_TABS)
                ?: throw ChromeServiceException("Failed to list tabs, unexpected null response")
        } catch (e: ChromeIOException) {
            if (isActive) {
                throw e
            } else {
                arrayOf()
            }
        }
    }

    @Throws(ChromeIOException::class)
    override fun createTab(): BrowserTab {
        return createTab(ABOUT_BLANK_PAGE)
    }

    @Throws(ChromeIOException::class)
    override fun createTab(url: String): BrowserTab {
        val browserTab =
            request(BrowserTab::class.java, HttpMethod.PUT, "http://%s:%d/%s?%s", host, port, CREATE_TAB, url)
                ?: throw ChromeIOException("Failed to create tab, unexpected null response | $url")
        return browserTab
    }

    @Throws(ChromeIOException::class)
    override fun activateTab(tab: BrowserTab) {
        request(Void::class.java, HttpMethod.PUT, "http://%s:%d/%s/%s", host, port, ACTIVATE_TAB, tab.id)
    }

    @Throws(ChromeIOException::class)
    override fun closeTab(tab: BrowserTab) {
        if (!isActive || !canConnect()) {
            return
        }
        request(Void::class.java, HttpMethod.PUT, "http://%s:%d/%s/%s", host, port, CLOSE_TAB, tab.id)
    }

    @Throws(ChromeIOException::class)
    @Synchronized
    override fun createDevTools(tab: BrowserTab, config: DevToolsConfig): RemoteDevTools {
        return remoteDevTools.computeIfAbsent(tab.id) { createDevTools0(version, tab, config) }
    }

    override fun canConnect(): Boolean {
        val url = URI.create("http://$host:$port").toURL()
        return NetUtil.testHttpNetwork(url)
    }

    @Throws(ChromeIOException::class)
    private fun refreshVersion(): ChromeVersion {
        return request(ChromeVersion::class.java, HttpMethod.GET, "http://%s:%d/%s", host, port, VERSION)
            ?: throw ChromeIOException("Failed to get version")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            if (!canConnect()) {
                return
            }

            val devTools = remoteDevTools.values
            devTools.forEach { it.runCatching { close() }.onFailure { warnForClose(this, it) } }
            remoteDevTools.clear()
        }
    }

    @Throws(ChromeIOException::class)
    private fun createDevTools0(version: ChromeVersion, tab: BrowserTab, config: DevToolsConfig): RemoteDevTools {
        val browserUrl = version.webSocketDebuggerUrl
            ?: throw ChromeIOException("Invalid web socket url to browser")
        val browserTransport = KtorTransport.create(URI.create(browserUrl))

        // Connect to a tab via web socket
        val debuggerUrl = tab.webSocketDebuggerUrl
            ?: throw ChromeIOException("Invalid web socket url to page")
        val pageTransport = KtorTransport.create(URI.create(debuggerUrl))

        return ChromeDevToolsImpl(browserTransport, pageTransport, config)
    }

    /**
     * Sends a request and parses json response as type T.
     *
     * @param responseType Resulting class type.
     * @param path Path with optional params similar to String.format() params.
     * @param params Path params.
     * @param <T> Type of response type.
     * @return Response object.
     * @throws ChromeIOException If sending request fails due to any reason.
     */
    @Throws(ChromeIOException::class)
    private fun <T> request(
        responseType: Class<T>, method: HttpMethod, path: String, vararg params: Any
    ): T? {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null

        try {
            val uri = URI.create(String.format(path, *params))

            connection = uri.toURL().openConnection() as HttpURLConnection

            /**
             * Chrome 111 no longer accepts HTTP GET to create tabs, PUT is the correct verb.
             *
             * Issue #14: Using unsafe HTTP verb GET to invoke /json/new. This action supports only PUT verb.
             * @see [#14](https://github.com/platonai/exotic-amazon/issues/14)
             *
             * Chrome-devtools-java-client issue #87: Doesn't work with chrome v111 #87
             * @see [#87](https://github.com/kklisura/chrome-devtools-java-client/issues/87)
             * */
            connection.requestMethod = method.toString()

            val responseCode = connection.responseCode
            if (HttpURLConnection.HTTP_OK == responseCode) {
                if (Void::class.java == responseType) {
                    return null
                }
                inputStream = connection.inputStream
                val body = readString(inputStream)
                return deserializeResponse(body, responseType)
            } else {
                inputStream = connection.errorStream
                val responseBody = readString(inputStream)
                val message = "Received error ($responseCode) - ${connection.responseMessage}\n$responseBody"

                throw ChromeIOException(message)
            }
        } catch (e: ConnectException) {
            throw ChromeIOException("Failed connecting to Chrome", e)
        } catch (e: IOException) {
            throw ChromeIOException("Failed sending HTTP request", e)
        } finally {
            inputStream?.close()
            connection?.disconnect()
        }
    }

    /**
     * Converts input stream to string. If input string is null, it returns empty string.
     *
     * @param inputStream Input stream.
     * @return String
     * @throws IOException If conversion fails.
     */
    @Throws(IOException::class)
    private fun readString(inputStream: InputStream): String {
        var length: Int
        val buffer = ByteArray(1024)
        val result = ByteArrayOutputStream()
        while (inputStream.read(buffer).also { length = it } != -1) {
            result.write(buffer, 0, length)
        }
        return result.toString("UTF-8")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> deserializeResponse(body: String, responseType: Class<T>): T? {
        // ChromeVersion uses @Serializable + @SerialName for kebab-case keys
        if (responseType == ChromeVersion::class.java) {
            return json.decodeFromString(ChromeVersion.serializer(), body) as T
        }
        // Array types: parse as JsonArray, convert each element
        if (responseType.isArray) {
            val elementType = responseType.componentType ?: return null
            val jsonArray = CDTReflectiveMapper.parseJson(body) as? kotlinx.serialization.json.JsonArray
                ?: return null
            val result = java.lang.reflect.Array.newInstance(elementType, jsonArray.size) as T
            for ((i, item) in jsonArray.withIndex()) {
                java.lang.reflect.Array.set(result, i, CDTReflectiveMapper.deserialize(item, elementType as Class<Any>))
            }
            return result
        }
        // Default: use CDTReflectiveMapper
        return CDTReflectiveMapper.deserializeFromString(body, responseType)
    }
}
