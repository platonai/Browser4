package ai.platon.browser4.driver.chrome.impl

import ai.platon.browser4.driver.chrome.util.ChromeRPCException
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.getTracerOrNull
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.stringify
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.type.TypeFactory
import kotlinx.coroutines.*
import org.apache.commons.lang3.StringUtils
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Coroutine-friendly invocation result wrapper to avoid blocking the calling thread.
 */
data class RpcResult(
    val isSuccess: Boolean,
    val result: JsonNode?,
    val message: String? = null
)

/**
 * Coroutine-based future that completes when a response with the matching id arrives.
 */
class InvocationFuture(val returnProperty: String? = null) {
    val deferred: CompletableDeferred<RpcResult> = CompletableDeferred()
}

/** Error object returned from dev tools. */
internal class ErrorObject {
    var code: Long = 0
    var message: String = ""
    var data: String? = null
}

class EventDispatcher : Consumer<String>, AutoCloseable {
    companion object {
        const val ID_PROPERTY = "id"
        const val ERROR_PROPERTY = "error"
        const val RESULT_PROPERTY = "result"
        const val METHOD_PROPERTY = "method"
        const val PARAMS_PROPERTY = "params"

        val OBJECT_MAPPER = ObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true)
    }

    private val logger = getLogger(this)

    private val tracer = getTracerOrNull(this)

    private val closed = AtomicBoolean()
    private val invocationFutures: MutableMap<Long, InvocationFuture> = ConcurrentHashMap()
    private val eventListeners: ConcurrentHashMap<String, ConcurrentSkipListSet<DevToolsEventListener>> =
        ConcurrentHashMap()

    private val eventDispatcherScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("EventDispatcher"))

    val isActive get() = !closed.get()

    fun patchMessageForProtocolChange(message: String, force: Boolean = false): String {
        // Patch protocol changes if needed, e.g., some events might change their params structure across Chrome versions

        // normalization
//        val tree = OBJECT_MAPPER.readTree(message)
//        var patched = OBJECT_MAPPER.writeValueAsString(tree)
        var patched = message

//        if (force || patched.contains("initiatorIPAddressSpace")) {
//            // {"requestId":"E0AFE9D2F80AEFDD9FC970FB33C9F88E","blockedCookies":[],"headers":{"Accept-Ranges":"bytes","Connection":"keep-alive","Content-Length":"9629","Content-Type":"text/html","Date":"Mon, 09 Mar 2026 07:33:09 GMT","Keep-Alive":"timeout=60","Last-Modified":"Wed, 11 Feb 2026 05:51:38 GMT"},"resourceIPAddressSpace":"Loopback","statusCode":200,"headersText":"HTTP/1.1 200\r\nLast-Modified: Wed, 11 Feb 2026 05:51:38 GMT\r\nAccept-Ranges: bytes\r\nContent-Type: text/html\r\nContent-Length: 9629\r\nDate: Mon, 09 Mar 2026 07:33:09 GMT\r\nKeep-Alive: timeout=60\r\nConnection: keep-alive\r\n\r\n","cookiePartitionKey":{"topLevelSite":"http://127.0.0.1","hasCrossSiteAncestor":false},"cookiePartitionKeyOpaque":false,"exemptedCookies":[]}
//            // {"method":"Network.requestWillBeSentExtraInfo","params":{"requestId":"15224.2","associatedCookies":[],"connectTiming":{"requestTime":6489.538341},"clientSecurityState":{"initiatorIsSecureContext":true,"initiatorIPAddressSpace":"Loopback","privateNetworkRequestPolicy":"PermissionBlock"},"siteHasCookieInOtherPartition":false}}
//            // InvalidFormatException: Cannot deserialize value of type `ai.platon.cdt.kt.protocol.types.network.IPAddressSpace` from String "Loopback": not one of the values accepted for Enum class: [Public, Unknown, Local, Private]
//            patched =
//                patched.replace("\"initiatorIPAddressSpace\":\"Loopback\"", "\"initiatorIPAddressSpace\":\"Local\"")
//        }
//
//        // "resourceIPAddressSpace":"Loopback"
//        if (force || patched.contains("resourceIPAddressSpace")) {
//            patched = patched.replace("\"resourceIPAddressSpace\":\"Loopback\"", "\"resourceIPAddressSpace\":\"Local\"")
//        }
//
//        if (force || patched.contains("privateNetworkRequestPolicy")) {
//            // {"requestId":"31040.2","associatedCookies":[],"connectTiming":{"requestTime":5541.737004},"clientSecurityState":{"initiatorIsSecureContext":true,"initiatorIPAddressSpace":"Local","privateNetworkRequestPolicy":"PermissionBlock"},"siteHasCookieInOtherPartition":false}
//            // InvalidFormatException: Cannot deserialize value of type `ai.platon.cdt.kt.protocol.types.network.PrivateNetworkRequestPolicy` from String "PermissionBlock": not one of the values accepted for Enum class: [BlockFromInsecureToMorePrivate, Allow, WarnFromInsecureToMorePrivate]
//            patched = patched.replace(
//                "\"privateNetworkRequestPolicy\":\"PermissionBlock\"",
//                "\"privateNetworkRequestPolicy\":\"Allow\""
//            )
//        }

        if (force || patched.contains("clientSecurityState")) {
            patched = patched.replace("clientSecurityState", "clientSecurityState-Deleted")
        }

        // TODO: configurable patching rules, e.g., via regex or some mapping, to handle protocol changes in a more systematic way. The current approach is ad-hoc and only handles specific known changes.

        return patched
    }

    @Throws(JsonProcessingException::class)
    fun serialize(message: Any): String = OBJECT_MAPPER.writeValueAsString(message)

    @Throws(JsonProcessingException::class)
    fun serialize(id: Long, method: String, params: Map<String, Any>?, sessionId: String?): String {
        return OBJECT_MAPPER.writeValueAsString(
            mapOf(
                ID_PROPERTY to id,
                METHOD_PROPERTY to method,
                PARAMS_PROPERTY to params,
                "sessionId" to sessionId
            )
        )
    }

    @Throws(IOException::class)
    fun <T> deserialize(classParameters: Array<Class<*>>, parameterizedClazz: Class<T>, jsonNode: JsonNode): T {
        val typeFactory: TypeFactory = OBJECT_MAPPER.typeFactory

        val typeParamCount = parameterizedClazz.typeParameters.size
        val javaType: JavaType = when {
            // No parameters -> plain type
            classParameters.isEmpty() -> typeFactory.constructType(parameterizedClazz)

            // Single-parameter generics (List-like). Support nesting via right fold, e.g.,
            // classParameters [List, Double] with parameterizedClazz List -> List<List<Double>>
            typeParamCount <= 1 -> {
                var inner: JavaType = typeFactory.constructType(classParameters.last())
                for (i in classParameters.size - 2 downTo 0) {
                    inner = typeFactory.constructParametricType(classParameters[i], inner)
                }
                typeFactory.constructParametricType(parameterizedClazz, inner)
            }

            // Two-parameter generics (Map-like). Common case: K, V
            typeParamCount == 2 -> {
                if (classParameters.size == 2) {
                    typeFactory.constructParametricType(parameterizedClazz, classParameters[0], classParameters[1])
                } else {
                    // Interpret first as key, the rest as nested value: Map<K, VNested>
                    var valueType: JavaType = typeFactory.constructType(classParameters.last())
                    for (i in classParameters.size - 2 downTo 1) {
                        valueType = typeFactory.constructParametricType(classParameters[i], valueType)
                    }
                    val keyType: JavaType = typeFactory.constructType(classParameters[0])
                    typeFactory.constructParametricType(parameterizedClazz, keyType, valueType)
                }
            }

            // 3+ parameters: best-effort (no nesting). If nesting is needed, pass nested via classParameters accordingly.
            else -> typeFactory.constructParametricType(parameterizedClazz, *classParameters)
        }

        try {
            return OBJECT_MAPPER.readerFor(javaType).readValue(jsonNode)
        } catch (e: Exception) {
            logger.warn("Failed to deserialize class $javaType\n", e)
            throw e
        }
    }

    /**
     * A typical Server Side Event:
     * ```json
     * {"method":"Page.frameStartedLoading","params":{"frameId":"53F48CA08C50A3A72887CB9F15B293D5"}}
     * ```
     * */
    @Throws(IOException::class, ChromeRPCException::class)
    fun <T> deserialize(clazz: Class<T>, jsonNode: JsonNode?): T {
        if (jsonNode == null) {
            throw ChromeRPCException("Failed converting null response to clazz " + clazz.name)
        }

        try {
            // Here is a typical response sequence:
            // println(clazz)
            // RequestWillBeSent, RequestWillBeSentExtraInfo, ResponseReceivedExtra, ResponseReceived, LoadingFinished,
            return OBJECT_MAPPER.readerFor(clazz).readValue(jsonNode)
        } catch (e: MismatchedInputException) {
            val message = """
                Failed converting response to clazz ${clazz.name}
                $jsonNode
                """.trimIndent()
            logger.warn(message, e)
            throw e
        } catch (e: IOException) {
            // logger.warn("Failed converting response to clazz {}", clazz.name, e)
            throw e
        }
    }

    fun hasFutures() = invocationFutures.isNotEmpty()

    fun subscribe(id: Long, returnProperty: String?): InvocationFuture {
        return invocationFutures.computeIfAbsent(id) { InvocationFuture(returnProperty) }
    }

    fun unsubscribe(id: Long) {
        invocationFutures.remove(id)
    }

    fun unsubscribeAll() {
        // Complete any pending futures with a failed result to unblock waiters
        val ids = invocationFutures.keys.toList()
        ids.forEach { id ->
            invocationFutures.remove(id)?.deferred?.complete(RpcResult(false, null))
        }
    }

    fun registerListener(key: String, listener: DevToolsEventListener) {
        eventListeners.computeIfAbsent(key) { ConcurrentSkipListSet<DevToolsEventListener>() }.add(listener)
    }

    fun unregisterListener(key: String, listener: DevToolsEventListener) {
        eventListeners[key]?.removeIf { listener.handler == it.handler }
    }

    fun removeAllListeners() {
        eventListeners.clear()
    }

    @Throws(ChromeRPCException::class, IOException::class)
    override fun accept(message: String) {
        tracer?.trace("◀ Accept {}", StringUtils.abbreviateMiddle(message, "...", 20000))

        // TODO: add event handler before parsing, so that we can handle events even if the message is not fully compliant with the protocol, e.g., some fields are added/removed/renamed across Chrome versions. This is especially important for events, as they are not correlated by id and can be easily missed if the parsing fails.

        val message = patchMessageForProtocolChange(message)

        ChromeDevToolsImpl.numAccepts.inc()
        try {
            val jsonNode = OBJECT_MAPPER.readTree(message)
            val idNode = jsonNode.get(ID_PROPERTY)
            if (idNode != null) {
                val id = idNode.asLong()
                val future = invocationFutures.remove(id)

                if (future != null) {
                    var resultNode = jsonNode.get(RESULT_PROPERTY)
                    val errorNode = jsonNode.get(ERROR_PROPERTY)
                    if (errorNode != null) {
                        logger.debug("Error node: {}", StringUtils.abbreviateMiddle(message, "...", 20000))
                        future.deferred.complete(RpcResult(false, errorNode, message))
                    } else {
                        if (future.returnProperty != null) {
                            resultNode = resultNode?.get(future.returnProperty)
                        }

                        future.deferred.complete(RpcResult(true, resultNode, message))
                    }
                } else {
                    logger.warn("Received response with unknown invocation #{} - {}", id, jsonNode.asText())
                }
            } else {
                val methodNode = jsonNode.get(METHOD_PROPERTY)
                val paramsNode = jsonNode.get(PARAMS_PROPERTY)
                if (methodNode != null) {
                    handleEventAsync(methodNode.asText(), paramsNode)
                }
            }
        } catch (e: Exception) {
            val msg = StringUtils.abbreviateMiddle(message, "...", 500)
            logger.error("Failed to parse message | {} | {}", msg, e.stringify())
        }
    }

    /**
     * Closes the dispatcher. All event listeners will be removed and all waiting futures are signaled with failed.
     * */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            unsubscribeAll()
            removeAllListeners()
            eventDispatcherScope.cancel()
        }
    }

    private fun handleEventAsync(name: String, params: JsonNode) {
        val listeners = eventListeners[name] ?: return

        // make a copy
        val unmodifiedListeners = mutableSetOf<DevToolsEventListener>()
        synchronized(listeners) { listeners.toCollection(unmodifiedListeners) }
        if (unmodifiedListeners.isEmpty()) {
            return
        }

        // Handle event in a separate coroutine
        eventDispatcherScope.launch {
            handleEvent0(params, unmodifiedListeners)
        }
    }

    /**
     * Handles the event by deserializing the params and calling the event handler.
     *
     * Do not throw any exception, all exceptions are caught and logged.
     *
     * A typical Server Side Event:
     * ```json
     * {"method":"Page.frameStartedLoading","params":{"frameId":"53F48CA08C50A3A72887CB9F15B293D5"}}
     * ```
     *
     * @param params the params node
     * @param unmodifiedListeners the listeners
     * @throws ChromeRPCException if the event could not be handled
     * */
    private suspend fun handleEvent0(params: JsonNode, unmodifiedListeners: Iterable<DevToolsEventListener>) {
        try {
            handleEvent1(params, unmodifiedListeners)
        } catch (e: MismatchedInputException) {
            logger.warn("Mismatched input, Chrome might have upgraded the protocol | {}", e.message)
        } catch (t: Throwable) {
            logger.warn("Failed to handle event", t)
        }
    }

    /**
     * A typical Server Side Event:
     * ```json
     * {"method":"Page.frameStartedLoading","params":{"frameId":"53F48CA08C50A3A72887CB9F15B293D5"}}
     * ```
     * */
    @Throws(ChromeRPCException::class, IOException::class)
    private suspend fun handleEvent1(params: JsonNode, unmodifiedListeners: Iterable<DevToolsEventListener>) {
        var event: Any? = null
        for (listener in unmodifiedListeners) {
            if (event == null) {
                event = deserialize(listener.paramType, params)
            }

            try {
                listener.handler.onEvent(event)
            } catch (e: Exception) {
                logger.warn(
                    "Failed to handle event, rethrow ChromeRPCException. Enable debug logging to see the stack trace | {}",
                    e.message
                )
                logger.debug("Failed to handle event", e)
                // Let the exception throw again, they might be caught by RobustRPC, or somewhere else
                throw ChromeRPCException("Failed to handle event | ${listener.key}, ${listener.paramType}", e)
            }
        }
    }
}
