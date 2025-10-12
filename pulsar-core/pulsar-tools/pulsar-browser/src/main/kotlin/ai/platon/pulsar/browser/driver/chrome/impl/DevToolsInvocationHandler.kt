package ai.platon.pulsar.browser.driver.chrome.impl

import ai.platon.pulsar.browser.driver.chrome.MethodInvocation
import ai.platon.pulsar.browser.driver.chrome.RemoteDevTools
import ai.platon.pulsar.browser.driver.chrome.util.ChromeIOException
import ai.platon.pulsar.browser.driver.chrome.util.ChromeRPCException
import ai.platon.pulsar.browser.driver.chrome.util.KInvocationHandler
import com.github.kklisura.cdt.protocol.v2023.support.annotations.EventName
import com.github.kklisura.cdt.protocol.v2023.support.annotations.ParamName
import com.github.kklisura.cdt.protocol.v2023.support.annotations.ReturnTypeParameter
import com.github.kklisura.cdt.protocol.v2023.support.annotations.Returns
import com.github.kklisura.cdt.protocol.v2023.support.types.EventHandler
import com.github.kklisura.cdt.protocol.v2023.support.types.EventListener
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.concurrent.atomic.AtomicLong

class DevToolsInvocationHandler: KInvocationHandler {
    companion object {
        private const val EVENT_LISTENER_PREFIX = "on"
        private val ID_SUPPLIER = AtomicLong(1L)
    }

    lateinit var devTools: RemoteDevTools

    @Throws(ChromeIOException::class, ChromeRPCException::class)
    override suspend fun invoke(target: Any, method: Method, args: Array<Any>?): Any? {
        if (isEventSubscription(method)) {
            return handleEventSubscription(target, method, args)
        }

        val returnType = method.returnType
        val returnTypeClasses = method.getAnnotation(ReturnTypeParameter::class.java)
                ?.value?.map { it.java }?.toTypedArray()
        val returnProperty = method.getAnnotation(Returns::class.java)?.value
        val methodInvocation = createMethodInvocation(method, args)

        // Invokes a remote method and returns the result.
        return devTools.invoke(returnType, returnProperty, returnTypeClasses, methodInvocation)
    }

    private fun handleEventSubscription(target: Any, method: Method, args: Array<Any>?): EventListener {
        val domainName = method.declaringClass.simpleName
        val eventName: String = getEventName(method)
        val eventHandlerType: Class<*> = getEventHandlerType(method)
        return devTools.addEventListener(domainName, eventName, args!![0] as EventHandler<Any>, eventHandlerType)
    }

    private fun createMethodInvocation(method: Method, args: Array<Any>? = null): MethodInvocation {
        val domainName = method.declaringClass.simpleName
        val methodName = method.name
        return MethodInvocation(ID_SUPPLIER.getAndIncrement(), "$domainName.$methodName", buildMethodParams(method, args))
    }

    private fun buildMethodParams(method: Method, args: Array<Any>? = null): Map<String, Any> {
        val params: MutableMap<String, Any> = HashMap()
        val parameters = method.parameters
        if (args != null) {
            for (i in args.indices) {
                params[parameters[i].getAnnotation(ParamName::class.java).value] = args[i]
            }
        }
        return params
    }

    private fun getEventName(method: Method): String {
        return method.getAnnotation(EventName::class.java).value
    }

    private fun getEventHandlerType(method: Method): Class<*> {
        return (method.genericParameterTypes[0] as ParameterizedType).actualTypeArguments[0] as Class<*>
    }

    /**
     * Checks if given method has signature of event subscription.
     *
     * @param method Method to check.
     * @return True if this is event subscription method that is: EventListener on*(EventHandler)
     */
    private fun isEventSubscription(method: Method): Boolean {
        val name = method.name
        val parameters = method.parameters
        return (name.startsWith(EVENT_LISTENER_PREFIX)
                && EventListener::class.java == method.returnType
                && parameters != null
                && parameters.size == 1
                && EventHandler::class.java.isAssignableFrom(parameters[0].type))
    }
}
