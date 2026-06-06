package ai.platon.browser4.chrome

import ai.platon.browser4.chrome.util.ChromeIOException
import ai.platon.browser4.chrome.util.ChromeServiceException
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.pulsar.browser.impl.BrowserTab
import ai.platon.pulsar.browser.impl.ChromeVersion
import ai.platon.pulsar.browser.impl.DevToolsConfig
import java.net.URI
import java.util.function.Consumer
import kotlin.reflect.KClass

interface Transport : AutoCloseable {
    val isOpen: Boolean

    @Throws(ChromeIOException::class)
    fun connect(uri: URI)

    @Throws(ChromeIOException::class)
    suspend fun send(message: String)

    fun addMessageHandler(consumer: Consumer<String>)
}

interface ChromeService : AutoCloseable {

    val isActive: Boolean

    val version: ChromeVersion

    val host: String

    val port: Int

    fun canConnect(): Boolean

    @Throws(ChromeServiceException::class)
    fun listTabs(): Array<BrowserTab>

    @Throws(ChromeServiceException::class)
    fun createTab(): BrowserTab

    @Throws(ChromeServiceException::class)
    fun createTab(url: String): BrowserTab

    @Throws(ChromeServiceException::class)
    fun activateTab(tab: BrowserTab)

    @Throws(ChromeServiceException::class)
    fun closeTab(tab: BrowserTab)

    @Throws(ChromeServiceException::class)
    fun createDevTools(tab: BrowserTab, config: DevToolsConfig = DevToolsConfig()): ChromeDevToolsService

    // Compatibility
    @Throws(ChromeServiceException::class)
    fun createDevToolsService(tab: BrowserTab): ChromeDevToolsService = createDevTools(tab, DevToolsConfig())

    // Compatibility
    @Throws(ChromeServiceException::class)
    fun createDevToolsService(tab: BrowserTab, config: DevToolsConfig = DevToolsConfig()): ChromeDevToolsService =
        createDevTools(tab, config)
}

/**
 * A minimal, native-image-friendly DevTools service interface.
 *
 * All CDP commands flow through [execute], which takes a method name string
 * (e.g. "Page.navigate") and a parameter map. Event subscriptions use
 * [addEventListener] / [removeEventListener].
 *
 * There is no dependency on the generated [ai.platon.cdt.kt.protocol.ChromeDevTools]
 * interface — this class does not extend it, and no dynamic proxies are involved.
 */
interface ChromeDevToolsService : AutoCloseable {

    val isOpen: Boolean

    /**
     * Executes a CDP command by method name.
     *
     * @param method  The CDP method name, e.g. "Page.navigate".
     * @param params  The command parameters, or null for parameter-less commands.
     * @param returnClass  The expected return type's [KClass].
     * @param returnProperty  Optional property name to extract from the result object
     *                        (corresponds to the `@Returns` annotation in the generated API).
     * @param returnTypeClasses  Optional array of type parameters for generic return
     *                           types (e.g. `arrayOf(Int::class.java)` for `List<Int>`).
     * @return The deserialized response, or null for void commands / empty results.
     */
    suspend fun <T : Any> execute(
        method: String,
        params: Map<String, Any?>?,
        returnClass: KClass<T>,
        returnProperty: String? = null,
        returnTypeClasses: Array<Class<out Any>>? = null
    ): T?

    fun awaitTermination()

    fun addEventListener(
        domainName: String,
        eventName: String,
        eventHandler: EventHandler<Any>,
        eventType: Class<*>
    ): EventListener

    fun removeEventListener(eventListener: EventListener)

    // Compatibility
    fun waitUntilClosed() = awaitTermination()
}

/**
 * Reified convenience for [ChromeDevToolsService.execute] so callers can write
 * `devTools.execute<Navigate>("Page.navigate", mapOf("url" to url))`
 * without passing a [KClass] explicitly.
 */
suspend inline fun <reified T : Any> RemoteDevTools.execute(
    method: String, params: Map<String, Any?>? = null, returnProperty: String? = null,
    returnTypeClasses: Array<Class<out Any>>? = null
): T? = execute(method, params, T::class, returnProperty, returnTypeClasses)

// Compatibility
typealias RemoteChrome = ChromeService
typealias RemoteDevTools = ChromeDevToolsService
