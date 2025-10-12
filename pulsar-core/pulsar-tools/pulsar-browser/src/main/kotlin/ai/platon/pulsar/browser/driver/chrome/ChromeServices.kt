package ai.platon.pulsar.browser.driver.chrome

import ai.platon.pulsar.browser.driver.chrome.util.ChromeIOException
import ai.platon.pulsar.browser.driver.chrome.util.ChromeRPCException
import ai.platon.pulsar.browser.driver.chrome.util.ChromeServiceException
import ai.platon.pulsar.common.ExperimentalApi
import com.fasterxml.jackson.databind.JsonNode
import com.github.kklisura.cdt.protocol.v2023.ChromeDevTools
import com.github.kklisura.cdt.protocol.v2023.support.types.EventHandler
import com.github.kklisura.cdt.protocol.v2023.support.types.EventListener
import java.net.URI
import java.util.concurrent.Future
import java.util.function.Consumer

interface Transport: AutoCloseable {
    val isOpen: Boolean
    
    @Throws(ChromeIOException::class)
    fun connect(uri: URI)

    @Throws(ChromeIOException::class)
    suspend fun sendAndReceive(message: String): String?

    @Throws(ChromeIOException::class)
    suspend fun send(message: String)
    
    fun addMessageHandler(consumer: Consumer<String>)
}

interface CoTransport: AutoCloseable {
    val isClosed: Boolean
    suspend fun connect(uri: URI)
    suspend fun send(message: String): String?
}

interface RemoteChrome: AutoCloseable {

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

interface RemoteDevTools: ChromeDevTools, AutoCloseable {

    val isOpen: Boolean
    
    @Throws(ChromeIOException::class, ChromeRPCException::class)
    fun <T> invoke(
            returnProperty: String?,
            clazz: Class<T>,
            returnTypeClasses: Array<Class<out Any>>?,
            method: MethodInvocation
    ): T?

    @Deprecated("Not a possible way")
    @Throws(ChromeIOException::class, ChromeRPCException::class)
    suspend fun <T> invokeDeferred(
        returnProperty: String?,
        clazz: Class<T>,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T?

    @Throws(ChromeIOException::class, ChromeRPCException::class)
    suspend fun send(method: String, params: Map<String, String?>?, sessionId: String? = null): String?

    @Throws(InterruptedException::class)
    fun awaitTermination()

    fun addEventListener(domainName: String, eventName: String, eventHandler: EventHandler<Any>, eventType: Class<*>): EventListener

    fun removeEventListener(eventListener: EventListener)
}

interface CoRemoteDevTools: ChromeDevTools, AutoCloseable {
    
    val isOpen: Boolean
    
    suspend operator fun <T> invoke(
        returnProperty: String?,
        clazz: Class<T>,
        returnTypeClasses: Array<Class<out Any>>?,
        method: MethodInvocation
    ): T?
    
    @Throws(InterruptedException::class)
    fun awaitTermination()
}
