package ai.platon.pulsar.browser.driver.chrome.util

import ai.platon.pulsar.common.alwaysTrue
import ai.platon.pulsar.common.getLogger
import javassist.Modifier
import javassist.util.proxy.MethodHandler
import javassist.util.proxy.ProxyFactory
import kotlinx.coroutines.*
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.KVariance

open class SuspendAwareHandler(private val impl: Any) : InvocationHandler {
    private val eventHandlerScope = CoroutineScope(Dispatchers.Default) + CoroutineName("CDTHandler")

    @Suppress("UNCHECKED_CAST")
    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        val kFunc = impl::class.declaredFunctions.find { it.name == method.name } ?: return null
        val realArgs = args ?: emptyArray()

        // 检查是否是 suspend 函数
        return if (kFunc.isSuspend) {
            val cont = realArgs.last() as Continuation<Any?>
            eventHandlerScope.launch(cont.context) {
                val result = kFunc.callSuspend(impl, *realArgs.dropLast(1).toTypedArray())
                cont.resume(result)
            }
            COROUTINE_SUSPENDED
        } else {
            kFunc.call(impl, *realArgs)
        }
    }
}

object ReflectUtils {

    fun getJavaClass(method: Method): Class<*> {
        val pType = method.genericParameterTypes[0] as ParameterizedType
        val raw = pType.rawType as Class<*>
        val type = pType.actualTypeArguments[0]
        return when (type) {
            is Class<*> -> {
                // In Kotlin, declaration-site variance (out T) is compiled to use-site wildcards in Java.
                // However, depending on compilation details, we might see a concrete Class here.
                // If the raw type's first type parameter is covariant (OUT) and there's no lower bound,
                // we should conservatively return Any.
                val kParams = raw.kotlin.typeParameters
                val isOut = kParams.firstOrNull()?.variance == KVariance.OUT
                if (isOut) Any::class.java else type
            }
            is ParameterizedType -> type.rawType as Class<*>
            is java.lang.reflect.WildcardType -> {
                val lowerBounds = type.lowerBounds
                if (lowerBounds.isNotEmpty()) {
                    when (val lb = lowerBounds[0]) {
                        is Class<*> -> lb
                        is ParameterizedType -> lb.rawType as Class<*>
                        else -> Any::class.java
                    }
                } else {
                    Any::class.java
                }
            }
            else -> Any::class.java
        }
    }

    private fun unboxIfWrapper(c: Class<*>): Class<*> = when (c) {
        java.lang.Integer::class.java -> Int::class.javaPrimitiveType!!
        java.lang.Long::class.java -> Long::class.javaPrimitiveType!!
        java.lang.Boolean::class.java -> Boolean::class.javaPrimitiveType!!
        java.lang.Short::class.java -> Short::class.javaPrimitiveType!!
        java.lang.Byte::class.java -> Byte::class.javaPrimitiveType!!
        java.lang.Character::class.java -> Char::class.javaPrimitiveType!!
        java.lang.Float::class.java -> Float::class.javaPrimitiveType!!
        java.lang.Double::class.java -> Double::class.javaPrimitiveType!!
        else -> c
    }

    /**
     * Resolve an accurate return type for the given method. For suspend functions, the JVM signature
     * returns `Object` and the actual type is stored in the Continuation's generic argument.
     */
    fun resolveReturnType(method: Method): Pair<Class<*>, Array<Class<*>>?> {
        var typeParams: Array<Class<*>>? = null

        fun classFromType(t: java.lang.reflect.Type): Class<*> = when (t) {
            is Class<*> -> t
            is ParameterizedType -> t.rawType as Class<*>
            is java.lang.reflect.WildcardType -> {
                val lb = t.lowerBounds.firstOrNull()
                val ub = t.upperBounds.firstOrNull()
                when (lb) {
                    is Class<*> -> lb
                    is ParameterizedType -> lb.rawType as Class<*>
                    else -> when (ub) {
                        is Class<*> -> ub
                        is ParameterizedType -> ub.rawType as Class<*>
                        else -> Any::class.java
                    }
                }
            }
            else -> Any::class.java
        }

        val isSuspend = method.parameterTypes.lastOrNull()?.let { Continuation::class.java.isAssignableFrom(it) } == true
        if (isSuspend) {
            val lastGeneric = method.genericParameterTypes.last()
            if (lastGeneric is ParameterizedType) {
                val t = lastGeneric.actualTypeArguments.firstOrNull()
                if (t != null) {
                    val clazz = when (t) {
                        is Class<*> -> t
                        is ParameterizedType -> {
                            val rawType = t.rawType as Class<*>
                            val inferred = t.actualTypeArguments.map { arg -> classFromType(arg) }.toTypedArray()
                            if (inferred.isNotEmpty()) typeParams = inferred
                            rawType
                        }
                        is java.lang.reflect.WildcardType -> {
                            val bound = t.lowerBounds.firstOrNull() ?: t.upperBounds.firstOrNull()
                            when (bound) {
                                is ParameterizedType -> {
                                    val rawType = bound.rawType as Class<*>
                                    val inferred = bound.actualTypeArguments.map { arg -> classFromType(arg) }.toTypedArray()
                                    if (inferred.isNotEmpty()) typeParams = inferred
                                    rawType
                                }
                                is Class<*> -> bound
                                else -> classFromType(t)
                            }
                        }
                        else -> Any::class.java
                    }
                    return unboxIfWrapper(clazz) to typeParams
                }
            }
            return Any::class.java to null
        } else {
            val rt = method.genericReturnType
            return when (rt) {
                is Class<*> -> unboxIfWrapper(rt) to null
                is ParameterizedType -> {
                    val args = rt.actualTypeArguments
                    val clazz = if (args.isNotEmpty()) classFromType(args[0]) else classFromType(rt.rawType)
                    if (args.isNotEmpty() && args[0] is ParameterizedType) {
                        val inner = args[0] as ParameterizedType
                        val inferred = inner.actualTypeArguments.map { a -> classFromType(a) }.toTypedArray()
                        if (inferred.isNotEmpty()) typeParams = inferred
                    }
                    unboxIfWrapper(clazz) to typeParams
                }
                else -> Any::class.java to null
            }
        }
    }
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
        clazz: Class<T>, paramTypes: Array<Class<*>>, args: Array<Any>? = null, invocationHandler: SuspendAwareHandler
    ): T {
        try {
            val factory = ProxyFactory()
            factory.superclass = clazz
            factory.setFilter { Modifier.isAbstract(it.modifiers) }

            val methodHandler = MethodHandler { o, method, _, objects ->
                if (method.name == "navigate") {
                    println("Navigating ...")
                    println(method.returnType.javaClass)
                }

                // Example:
                // InvocationHandler:
                //   - a wrapper of CachedDevToolsInvocationHandlerProxies
                // Typical proxy:
                //   - jdk.proxy1.$Proxy24
                // Typical methods:
                //   - public abstract void ai.platon.pulsar.cdt.protocol.commands.Page.enable()
                //   - public abstract com...page.Navigate com...Page.navigate(java.lang.String)
                // TODO: blocking here
                invocationHandler.invoke(o, method, objects)
            }

            val proxy = factory.create(paramTypes, args, methodHandler)

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
        invocationHandler: SuspendAwareHandler
    ): T {
        if (isDebugEnabled) {
            debugParameters(clazz, paramTypes, args)
        }

        return createProxyFromAbstract(clazz, paramTypes, args, invocationHandler)
    }

    /**
     * Creates a proxy class to a given interface clazz supplied with invocation handler.
     *
     * @param clazz Proxy to class.
     * @param invocationHandler Invocation handler.
     * @param <T> Class type.
     * @return Proxy instance.
     */
    fun <T> createProxy(clazz: Class<T>, invocationHandler: SuspendAwareHandler?): T {
        val bridgeHandler = toJvmInvocationHandler(invocationHandler)

        val proxy = Proxy.newProxyInstance(clazz.classLoader, arrayOf<Class<*>>(clazz), bridgeHandler)

        @Suppress("UNCHECKED_CAST")
        return proxy as T
    }

    fun toJvmInvocationHandler(handler: SuspendAwareHandler?): InvocationHandler? {
        if (alwaysTrue()) {
            return handler
        }


        if (handler == null) {
            return null
        }

        val bridgeHandler = InvocationHandler { proxy, method, methodArgs ->
            if (isDebugEnabled) {
                // Typical proxy:
                //   - jdk.proxy1.$Proxy24
                // Typical methods:
                //   - public abstract void ai.platon.pulsar.cdt.protocol.commands.Page.enable()
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
     *   - public abstract ai.platon.pulsar.cdt.protocol.commands.Page ai.platon.pulsar.cdt.protocol.ChromeDevTools.getPage()
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
