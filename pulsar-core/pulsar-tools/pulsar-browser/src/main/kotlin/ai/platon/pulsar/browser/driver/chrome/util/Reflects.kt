package ai.platon.pulsar.browser.driver.chrome.util

import ai.platon.pulsar.browser.driver.chrome.impl.KInvocationHandler
import javassist.Modifier
import javassist.util.proxy.ProxyFactory
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

object ProxyClasses {
    /**
     * Creates a proxy class to a given interface clazz supplied with invocation handler.
     *
     * @param clazz Proxy to class.
     * @param invocationHandler Invocation handler.
     * @param <T> Class type.
     * @return Proxy instance.
    </T> */
    fun <T> createProxy(clazz: Class<T>, invocationHandler: KInvocationHandler?): T {
        return Proxy.newProxyInstance(clazz.classLoader, arrayOf<Class<*>>(clazz), invocationHandler) as T
    }

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
        clazz: Class<T>, paramTypes: Array<Class<*>>, args: Array<Any>? = null, invocationHandler: KInvocationHandler
    ): T {
        try {
            val factory = ProxyFactory()
            factory.superclass = clazz
            factory.setFilter { Modifier.isAbstract(it.modifiers) }

            return factory.create(paramTypes, args) { o, method, _, objects ->
                invocationHandler.invoke(o, method, objects)
            } as T
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
        clazz: Class<T>, paramTypes: Array<Class<*>>, args: Array<Any>? = null, invocationHandler: KInvocationHandler
    ): T {
        val bridgeHandler = java.lang.reflect.InvocationHandler { proxy, method, methodArgs ->
            when (method.name) {
                "equals" -> methodArgs?.getOrNull(0)?.let { proxy === it } ?: false
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Proxy(${clazz.simpleName})"
                else -> {
                    // Suspend function: last arg is a Continuation
                    if (methodArgs != null && methodArgs.isNotEmpty() && methodArgs.last() is Continuation<*>) {
                        @Suppress("UNCHECKED_CAST")
                        val cont = methodArgs.last() as Continuation<Any?>
                        val realArgs = if (methodArgs.size > 1) methodArgs.copyOf(methodArgs.size - 1) as Array<Any>? else null

                        val block: suspend () -> Any? = {
                            invocationHandler.invokeDeferred(proxy, method, realArgs)
                        }
                        block.startCoroutine(object : Continuation<Any?> {
                            override val context = cont.context
                            override fun resumeWith(result: Result<Any?>) {
                                cont.resumeWith(result)
                            }
                        })

                        COROUTINE_SUSPENDED
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        invocationHandler.invoke(proxy, method, methodArgs as Array<Any>?)
                    }
                }
            }
        }

        try {
            val factory = ProxyFactory()
            factory.superclass = clazz
            factory.setFilter { Modifier.isAbstract(it.modifiers) }

            return factory.create(paramTypes, args) { o, method, _, objects ->
                bridgeHandler.invoke(o, method, objects)
            } as T
        } catch (e: Exception) {
            throw RuntimeException("Failed creating proxy from abstract class | ${clazz.name}", e)
        }
    }
}
