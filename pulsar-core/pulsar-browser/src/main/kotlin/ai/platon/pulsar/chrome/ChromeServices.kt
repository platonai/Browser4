package ai.platon.pulsar.chrome

import ai.platon.pulsar.chrome.util.ChromeIOException
import ai.platon.pulsar.chrome.util.ChromeServiceException
import ai.platon.cdt.kt.protocol.ChromeDevTools
import ai.platon.cdt.kt.protocol.support.types.EventHandler
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.pulsar.browser.impl.BrowserTab
import ai.platon.pulsar.browser.impl.ChromeVersion
import ai.platon.pulsar.browser.impl.DevToolsConfig
import ai.platon.pulsar.browser.impl.MethodInvocation
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

interface ChromeDevToolsService : ChromeDevTools, AutoCloseable {

    val isOpen: Boolean

    suspend fun <T> invoke(
        clazz: Class<T>,
        returnProperty: String?,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T?

    suspend operator fun <T : Any> invoke(
        method: String,
        params: Map<String, Any?>?,
        returnClass: KClass<T>,
        returnProperty: String? = null
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

suspend inline operator fun <reified T : Any> RemoteDevTools.invoke(
    method: String, params: Map<String, Any?>?, returnProperty: String? = null
): T? = invoke(method, params, T::class, returnProperty)

// Compatibility
typealias RemoteChrome = ChromeService
typealias RemoteDevTools = ChromeDevToolsService
