package ai.platon.pulsar.browser.driver.chrome.util

import ai.platon.pulsar.common.getLogger
import javassist.Modifier
import javassist.util.proxy.ProxyFactory
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

interface KInvocationHandler {
    suspend fun invoke(proxy: Any, method: Method, args: Array<Any>?): Any?
}

object ProxyClasses {
    private val logger = getLogger(this)

    private val isDebugEnabled get() = logger.isDebugEnabled

    /**
     * Creates a proxy class to a given abstract clazz supplied with invocation handler for
     * un-implemented/abstract methods
     *
     * @param clazz Proxy to class
     * @param paramTypes Ctor param types
     * @param args Constructor args
     * @param invocationHandler Invocation handler
     * @param <T> Class type
     * @return Proxy instance <T>
     */
    @Throws(Exception::class)
    fun <T> createProxyFromAbstract(
        clazz: Class<T>, paramTypes: Array<Class<*>>, args: Array<Any>? = null, invocationHandler: InvocationHandler
    ): T {
        try {
            val factory = ProxyFactory()
            factory.superclass = clazz
            factory.setFilter { Modifier.isAbstract(it.modifiers) }

            val proxy = factory.create(paramTypes, args) { o, method, _, objects ->
                // Example:
                // InvocationHandler:
                //   - a wrapper of CachedDevToolsInvocationHandlerProxies
                // Typical proxy:
                //   - jdk.proxy1.$Proxy24
                // Typical methods:
                //   - public abstract void com.github.kklisura.cdt.protocol.v2023.commands.Page.enable()
                //   - public abstract com...page.Navigate com...Page.navigate(java.lang.String)
                invocationHandler.invoke(o, method, objects)
            }

            @Suppress("UNCHECKED_CAST")
            return proxy as T
        } catch (e: Exception) {
            throw RuntimeException("Failed creating proxy from abstract class | ${clazz.name}", e)
        }
    }

    /**
     * Pure Kotlin interface-proxy version that supports suspend functions via Continuation bridge.
     * Note: This expects [clazz] to be an interface. [paramTypes] and [args] are ignored.
     */
    @Throws(Exception::class)
    fun <T> createCoroutineSupportedProxyFromAbstract(
        clazz: Class<T>, paramTypes: Array<Class<*>>, args: Array<Any>? = null,
        invocationHandler: KInvocationHandler
    ): T {
        if (isDebugEnabled) {
            debugParameters(clazz, paramTypes, args)
        }

        val bridgeHandler = toJvmInvocationHandler(invocationHandler)!!

        return createProxyFromAbstract(clazz, paramTypes, args, bridgeHandler)
    }

    /**
     * Creates a proxy class to a given interface clazz supplied with invocation handler.
     *
     * @param clazz Proxy to class.
     * @param invocationHandler Invocation handler.
     * @param <T> Class type.
     * @return Proxy instance.
     */
    fun <T> createProxy(clazz: Class<T>, invocationHandler: KInvocationHandler?): T {
        val bridgeHandler = toJvmInvocationHandler(invocationHandler)

        if (isDebugEnabled) {
            // Example
            // class: com.github.kklisura.cdt.protocol.v2023.commands.Page
            val message = """
class: ${clazz.name}

        """.trimIndent()

            logger.info("createProxy: $message")
        }

        val proxy = Proxy.newProxyInstance(clazz.classLoader, arrayOf<Class<*>>(clazz), bridgeHandler)

        @Suppress("UNCHECKED_CAST")
        return proxy as T
    }

    fun toJvmInvocationHandler(handler: KInvocationHandler?): InvocationHandler? {
        if (handler == null) {
            return null
        }

        val bridgeHandler = InvocationHandler { proxy, method, methodArgs ->
            if (isDebugEnabled) {
                // Typical proxy:
                //   - jdk.proxy1.$Proxy24
                // Typical methods:
                //   - public abstract void com.github.kklisura.cdt.protocol.v2023.commands.Page.enable()
                //   - public abstract com...page.Navigate com...Page.navigate(java.lang.String)
                debugParameters(proxy, method, methodArgs)
            }

            when (method.name) {
                "equals" -> methodArgs?.getOrNull(0)?.let { proxy === it } ?: false
                "hashCode" -> System.identityHashCode(proxy)
                else -> {
                    runBlocking {
                        handler.invoke(proxy, method, methodArgs as Array<Any>?)
                    }
                }
            }
        }

        return bridgeHandler
    }

    /**
     * Example parameters:
     *
     * class: ai.platon.pulsar.browser.driver.chrome.impl.ChromeDevToolsImpl
     * paramTypes:
     *   - interface ai.platon.pulsar.browser.driver.chrome.Transport,
     *   - interface ai.platon.pulsar.browser.driver.chrome.Transport,
     *   - class ai.platon.pulsar.browser.driver.chrome.DevToolsConfig
     * args:
     *   - ws://localhost:4644/devtools/browser/fefcf5b0-eb7f-4158-8a07-d5be61024292,
     *   - ws://localhost:4644/devtools/page/8A485D7DE2D7E9A0971C47686A81B645,
     *   - ai.platon.pulsar.browser.driver.chrome.DevToolsConfig@257cc1fc
     * */
    private fun <T> debugParameters(clazz: Class<T>, paramTypes: Array<Class<*>>, args: Array<Any>? = null) {

        val message = """
Parameters:

class: ${clazz.name}
paramTypes: 
    - ${paramTypes.joinToString("\n    - ")}
args: 
    - ${args?.joinToString("\n    - ")}
"""

        logger.info(message)
    }

    /**
     * Example Parameters:
     *
     * proxy: ai.platon.pulsar.browser.driver.chrome.impl.ChromeDevToolsImpl_$$_jvst2b9_0@421a4ee1
     * method:
     *   - public abstract com.github.kklisura.cdt.protocol.v2023.commands.Page com.github.kklisura.cdt.protocol.v2023.ChromeDevTools.getPage()
     * methodArgs:
     *   -
     * */
    private fun debugParameters(proxy: Any, method: Method, args: Array<Any>?) {
        val message = """
Parameters:

proxy: ${proxy.javaClass.name}
method:
    - $method
methodArgs:
    - ${args?.joinToString("\n    - ")}
        """

        logger.info(message)
    }
}
