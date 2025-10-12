package ai.platon.pulsar.browser.driver.chrome

import ai.platon.pulsar.browser.driver.chrome.impl.RpcResult
import ai.platon.pulsar.browser.driver.chrome.util.ChromeIOException
import ai.platon.pulsar.browser.driver.chrome.util.ChromeServiceException
import com.github.kklisura.cdt.protocol.v2023.ChromeDevTools
import com.github.kklisura.cdt.protocol.v2023.support.types.EventHandler
import com.github.kklisura.cdt.protocol.v2023.support.types.EventListener
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

interface RemoteChrome : AutoCloseable {

    val isActive: Boolean

    val version: ChromeVersion

    val host: String

    val port: Int

    fun canConnect(): Boolean

    @Throws(ChromeServiceException::class)
    fun listTabs(): Array<ChromeTab>

    @Throws(ChromeServiceException::class)
    fun createTab(): ChromeTab

    @Throws(ChromeServiceException::class)
    fun createTab(url: String): ChromeTab

    @Throws(ChromeServiceException::class)
    fun activateTab(tab: ChromeTab)

    @Throws(ChromeServiceException::class)
    fun closeTab(tab: ChromeTab)

    @Throws(ChromeServiceException::class)
    fun createDevTools(tab: ChromeTab, config: DevToolsConfig = DevToolsConfig()): RemoteDevTools
}

interface RemoteDevTools : ChromeDevTools, AutoCloseable {

    val isOpen: Boolean

    suspend fun <T> invoke(
        clazz: Class<T>,
        returnProperty: String?,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T?

    suspend fun invoke(method: String, params: Map<String, Any?>?): RpcResult?

    suspend fun <T : Any> invoke(
        method: String,
        params: Map<String, Any?>?,
        returnClass: KClass<T>,
        returnProperty: String? = null
    ): T?

    @Throws(InterruptedException::class)
    fun awaitTermination()

    fun addEventListener(
        domainName: String,
        eventName: String,
        eventHandler: EventHandler<Any>,
        eventType: Class<*>
    ): EventListener

    fun removeEventListener(eventListener: EventListener)
}

suspend inline fun <reified T : Any> RemoteDevTools.call(
    method: String, params: Map<String, Any?>?, returnProperty: String? = null
): T? = invoke(method, params, T::class, returnProperty)
