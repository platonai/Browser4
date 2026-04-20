package ai.platon.pulsar.skeleton.event

import ai.platon.pulsar.persist.WebPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The global EventBus for handling events.
 * */
object PulsarEventBus {

    /**
     * Background coroutine scope for non-blocking event emission.
     * Uses Dispatchers.Default for CPU-bound work and SupervisorJob to isolate failures.
     */
    private val eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * The page event handlers.
     *
     * The calling order rule:
     *
     * The more specific handlers has the opportunity to override the result of more general handlers.
     * */
    var pageEventHandlers: PageEventHandlers? = null

    /**
     * The server-side event handlers for broadcasting events to external listeners.
     *
     * NOTE: kept for backward compatibility as the default handlers.
     */
    var serverSideEventHandlers: ServerSideEventHandlers? = null

    /**
     * Per-coroutine override for [serverSideEventHandlers].
     *
     * We use ThreadLocal because it works across the existing thread-based execution model.
     * When used with [withServerSideEventHandlers], it is installed for the duration of the coroutine
     * context and restored automatically.
     */
    private val serverSideEventHandlersTL = ThreadLocal<ServerSideEventHandlers?>()

    /**
     * Runs [block] with [handlers] bound to the current coroutine execution context.
     */
    suspend fun <T> withServerSideEventHandlers(handlers: ServerSideEventHandlers?, block: suspend () -> T): T {
        return withContext(ServerSideEventHandlersContext(handlers)) {
            block()
        }
    }

    /**
     * Emits a crawl event to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     */
    fun emitCrawlEvent(eventType: String, url: String? = null, message: String? = null) {
        currentServerSideEventHandlers()?.let { handlers ->
            eventScope.launch {
                handlers.onCrawlEvent(eventType, url, message)
            }
        }
    }

    /**
     * Emits a load event to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     */
    fun emitLoadEvent(
        eventType: String,
        page: WebPage,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        currentServerSideEventHandlers()?.let { handlers ->
            eventScope.launch {
                handlers.onLoadEvent(eventType, page, message, metadata)
            }
        }
    }

    /**
     * Emits a browse event to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     */
    fun emitBrowseEvent(
        eventType: String,
        page: WebPage,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        currentServerSideEventHandlers()?.let { handlers ->
            eventScope.launch {
                handlers.onBrowseEvent(eventType, page, message, metadata)
            }
        }
    }

    private class ServerSideEventHandlersContext(
        private val handlers: ServerSideEventHandlers?
    ) : ThreadContextElement<ServerSideEventHandlers?> {
        companion object Key : CoroutineContext.Key<ServerSideEventHandlersContext>

        override val key: CoroutineContext.Key<ServerSideEventHandlersContext> = Key

        override fun updateThreadContext(context: CoroutineContext): ServerSideEventHandlers? {
            val previous = serverSideEventHandlersTL.get()
            serverSideEventHandlersTL.set(handlers)
            return previous
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: ServerSideEventHandlers?) {
            serverSideEventHandlersTL.set(oldState)
        }
    }

    /**
     * Returns the handlers for the current context (per-coroutine override first, then global fallback).
     */
    private fun currentServerSideEventHandlers(): ServerSideEventHandlers? {
        return serverSideEventHandlersTL.get() ?: serverSideEventHandlers
    }
}
