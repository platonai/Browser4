package ai.platon.pulsar.browser.driver.chrome.impl

import ai.platon.pulsar.browser.driver.chrome.*
import ai.platon.pulsar.browser.driver.chrome.util.ChromeIOException
import ai.platon.pulsar.browser.driver.chrome.util.ChromeServiceException
import ai.platon.pulsar.browser.driver.chrome.util.ProxyClasses
import ai.platon.pulsar.browser.driver.chrome.util.SuspendAwareHandler
import ai.platon.pulsar.common.NetUtil
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.warnForClose
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ChromeImpl(
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
    private val objectMapper = ObjectMapper()

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
    override fun listTabs(): Array<ChromeTab> {
        return try {
            request(Array<ChromeTab>::class.java, HttpMethod.GET, "http://%s:%d/%s", host, port, LIST_TABS)
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
    override fun createTab(): ChromeTab {
        return createTab(ABOUT_BLANK_PAGE)
    }

    @Throws(ChromeIOException::class)
    override fun createTab(url: String): ChromeTab {
        val chromeTab =
            request(ChromeTab::class.java, HttpMethod.PUT, "http://%s:%d/%s?%s", host, port, CREATE_TAB, url)
                ?: throw ChromeIOException("Failed to create tab, unexpected null response | $url")
        return chromeTab
    }

    @Throws(ChromeIOException::class)
    override fun activateTab(tab: ChromeTab) {
        request(Void::class.java, HttpMethod.PUT, "http://%s:%d/%s/%s", host, port, ACTIVATE_TAB, tab.id)
    }

    @Throws(ChromeIOException::class)
    override fun closeTab(tab: ChromeTab) {
        if (!isActive || !canConnect()) {
            return
        }
        request(Void::class.java, HttpMethod.PUT, "http://%s:%d/%s/%s", host, port, CLOSE_TAB, tab.id)
    }

    @Throws(ChromeIOException::class)
    @Synchronized
    override fun createDevTools(tab: ChromeTab, config: DevToolsConfig): RemoteDevTools {
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
    private fun createDevTools0(version: ChromeVersion, tab: ChromeTab, config: DevToolsConfig): RemoteDevTools {
        val browserUrl = version.webSocketDebuggerUrl
            ?: throw ChromeIOException("Invalid web socket url to browser")
        val browserTransport = KtorTransport.create(URI.create(browserUrl))

        // Connect to a tab via web socket
        val debuggerUrl = tab.webSocketDebuggerUrl
            ?: throw ChromeIOException("Invalid web socket url to page")
        val pageTransport = KtorTransport.create(URI.create(debuggerUrl))

        val invocationHandler = CachedDevToolsInvocationHandlerProxies(this)

        val devTools: RemoteDevTools = createRemoteDevToolsProxy(browserTransport, pageTransport, config, invocationHandler)

        invocationHandler.commandHandler.devTools = devTools

        return devTools
    }

    private fun createRemoteDevToolsProxy(
        browserTransport: Transport, pageTransport: Transport, config: DevToolsConfig,
        invocationHandler: SuspendAwareHandler
    ): RemoteDevTools {
        // Create a class that inherits from ChromeDevToolsImpl and construct an object
        return ProxyClasses.createCoroutineSupportedProxyFromAbstract(
            ChromeDevToolsImpl::class.java,
            arrayOf(Transport::class.java, Transport::class.java, DevToolsConfig::class.java),
            arrayOf(browserTransport, pageTransport, config),
            invocationHandler
        )
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
                return objectMapper.readerFor(responseType).readValue(inputStream)
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
}
