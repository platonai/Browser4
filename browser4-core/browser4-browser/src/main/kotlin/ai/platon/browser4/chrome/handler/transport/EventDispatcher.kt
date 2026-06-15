package ai.platon.browser4.chrome.handler.transport

import ai.platon.browser4.chrome.util.ChromeRPCException
import ai.platon.pulsar.browser.common.Utils
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.getTracerOrNull
import ai.platon.pulsar.common.stringify
import ai.platon.pulsar.browser.common.CDTReflectiveMapper
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
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
    val result: JsonElement?,
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

        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
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
        var patched = message

        if (force || patched.contains("clientSecurityState")) {
            patched = patched.replace("clientSecurityState", "clientSecurityState-Deleted")
        }

        return patched
    }

    @Throws(SerializationException::class)
    fun serialize(message: Any): String = JSON.encodeToString(JsonElement.serializer(), buildJsonElement(message))

    @Throws(SerializationException::class)
    fun serialize(id: Long, method: String, params: Map<String, Any>?, sessionId: String?): String {
        return buildJsonObject {
            put(ID_PROPERTY, id)
            put(METHOD_PROPERTY, method)
            if (params != null) put(PARAMS_PROPERTY, buildJsonElement(params))
            if (sessionId != null) put("sessionId", sessionId)
        }.toString()
    }

    /** Convert a map/collection into a JsonElement tree. */
    @Suppress("UNCHECKED_CAST")
    private fun buildJsonElement(value: Any): JsonElement {
        return when (value) {
            is Map<*, *> -> buildJsonObject {
                for ((k, v) in value) {
                    put(k.toString(), buildJsonElement(v ?: JsonNull))
                }
            }
            is List<*> -> buildJsonArray {
                for (v in value) {
                    add(buildJsonElement(v ?: JsonNull))
                }
            }
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Enum<*> -> JsonPrimitive(value.name)
            else -> JsonPrimitive(value.toString())
        }
    }

    @Throws(IOException::class)
    fun <T> deserialize(classParameters: Array<Class<*>>, parameterizedClazz: Class<T>, jsonNode: JsonElement): T {
        try {
            return CDTReflectiveMapper.deserialize(classParameters, parameterizedClazz, jsonNode)
        } catch (e: Exception) {
            logger.warn("Failed to deserialize class ${parameterizedClazz.name}\n", e)
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
    fun <T> deserialize(clazz: Class<T>, jsonNode: JsonElement?): T {
        if (jsonNode == null) {
            throw ChromeRPCException("Failed converting null response to clazz " + clazz.name)
        }

        try {
            return CDTReflectiveMapper.deserialize(jsonNode, clazz)
        } catch (e: Exception) {
            val message = """
                Failed converting response to clazz ${clazz.name}
                $jsonNode
                """.trimIndent()
            logger.warn(message, e)
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
        tracer?.trace("◀ Accept {}", Utils.abbreviateMiddle(message, "...", 20000))

        // TODO: add event handler before parsing, so that we can handle events even if the message is not fully compliant with the protocol, e.g., some fields are added/removed/renamed across Chrome versions. This is especially important for events, as they are not correlated by id and can be easily missed if the parsing fails.

        val message = patchMessageForProtocolChange(message)

        ChromeDevToolsImpl.numAccepts.inc()
        try {
            val jsonElement = CDTReflectiveMapper.parseJson(message)
            val jsonObj = jsonElement as? JsonObject ?: return
            val idElement = jsonObj[ID_PROPERTY]
            if (idElement != null && idElement !is JsonNull) {
                val id = idElement.jsonPrimitive.long
                val future = invocationFutures.remove(id)

                if (future != null) {
                    var resultElement = jsonObj[RESULT_PROPERTY]
                    val errorElement = jsonObj[ERROR_PROPERTY]
                    if (errorElement != null && errorElement !is JsonNull) {
                        logger.debug("Error node: {}", Utils.abbreviateMiddle(message, "...", 20000))
                        future.deferred.complete(RpcResult(false, errorElement, message))
                    } else {
                        if (future.returnProperty != null) {
                            resultElement = (resultElement as? JsonObject)?.get(future.returnProperty!!)
                        }

                        future.deferred.complete(RpcResult(true, resultElement, message))
                    }
                } else {
                    logger.warn("Received response with unknown invocation #{} - {}", id, jsonObj.toString())
                }
            } else {
                val methodElement = jsonObj[METHOD_PROPERTY]
                val paramsElement = jsonObj[PARAMS_PROPERTY]
                if (methodElement != null && methodElement !is JsonNull) {
                    handleEventAsync(methodElement.jsonPrimitive.content, paramsElement)
                }
            }
        } catch (e: Exception) {
            val msg = Utils.abbreviateMiddle(message, "...", 500)
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

    private fun handleEventAsync(name: String, params: JsonElement?) {
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
    private suspend fun handleEvent0(params: JsonElement?, unmodifiedListeners: Iterable<DevToolsEventListener>) {
        try {
            handleEvent1(params, unmodifiedListeners)
        } catch (e: IllegalArgumentException) {
            // Mismatched input — Chrome might have upgraded the protocol
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
    private suspend fun handleEvent1(params: JsonElement?, unmodifiedListeners: Iterable<DevToolsEventListener>) {
        var event: Any? = null
        for (listener in unmodifiedListeners) {
            if (event == null) {
                if (params == null) continue
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
