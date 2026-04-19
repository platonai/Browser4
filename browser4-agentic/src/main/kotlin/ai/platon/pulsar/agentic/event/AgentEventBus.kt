package ai.platon.pulsar.agentic.event

import ai.platon.pulsar.agentic.event.detail.DefaultAgentEventHandlers
import ai.platon.pulsar.agentic.event.detail.DefaultServerSideAgentEventHandlers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The global EventBus for handling agent events.
 *
 * AgentEventBus provides a centralized mechanism for:
 * - Managing agent event handlers
 * - Broadcasting server-side agent events to external listeners
 * - Supporting per-coroutine handler overrides for isolated event handling
 *
 * ## Architecture
 * ```
 * Agent Operation → Event Fired (e.g., onWillObserve)
 *     ↓
 * AgentEventBus.serverSideAgentEventHandlers.onAgentEvent()
 *     ↓
 * ServerSideAgentEvent emitted to SharedFlow
 *     ↓
 * SSE Stream to client (via REST API)
 * ```
 *
 * ## Example Usage
 * ```kotlin
 * // Set up global event handlers
 * AgentEventBus.agentEventHandlers = DefaultAgentEventHandlers()
 * AgentEventBus.serverSideAgentEventHandlers = DefaultServerSideAgentEventHandlers()
 *
 * // Emit events during agent execution
 * AgentEventBus.emitAgentEvent("onWillObserve", "agent-123", "Starting observation")
 *
 * // Use per-coroutine handlers for isolation
 * AgentEventBus.withServerSideAgentEventHandlers(customHandlers) {
 *     // Events in this block use customHandlers
 *     agent.observe(options)
 * }
 * ```
 *
 * @see AgentEventHandlers for the event handler hierarchy
 * @see ServerSideAgentEventHandlers for server-side event handling
 */
object AgentEventBus {

    /**
     * Background coroutine scope for non-blocking event emission.
     * Uses Dispatchers.Default for CPU-bound work and SupervisorJob to isolate failures.
     */
    private val eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * The agent event handlers.
     *
     * The calling order rule:
     * The more specific handlers has the opportunity to override the result of more general handlers.
     */
    var agentEventHandlers: AgentEventHandlers? = null

    /**
     * The server-side agent event handlers for broadcasting events to external listeners.
     *
     * NOTE: kept for backward compatibility as the default handlers.
     */
    var serverSideAgentEventHandlers: ServerSideAgentEventHandlers? = null

    /**
     * Per-coroutine override for [serverSideAgentEventHandlers].
     *
     * We use ThreadLocal because it works across the existing thread-based execution model.
     * When used with [withServerSideAgentEventHandlers], it is installed for the duration of the coroutine
     * context and restored automatically.
     */
    private val serverSideAgentEventHandlersTL = ThreadLocal<ServerSideAgentEventHandlers?>()

    /**
     * Runs [block] with [handlers] bound to the current coroutine execution context.
     *
     * This allows isolated event handling per command or request execution.
     *
     * @param handlers The server-side agent event handlers to use within the block
     * @param block The suspend function to execute with the specified handlers
     * @return The result of the block execution
     */
    suspend fun <T> withServerSideAgentEventHandlers(
        handlers: ServerSideAgentEventHandlers?,
        block: suspend () -> T
    ): T {
        return withContext(ServerSideAgentEventHandlersContext(handlers)) {
            block()
        }
    }

    /**
     * Emits an agent-phase event to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     *
     * @param eventType The type of the event (e.g., "onWillObserve", "onDidAct")
     * @param agentId The unique identifier of the agent
     * @param message Optional message describing the event
     * @param metadata Additional metadata for the event
     */
    fun emitAgentEvent(
        eventType: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        currentServerSideAgentEventHandlers()?.let { handlers ->
            eventScope.launch {
                handlers.onAgentEvent(eventType, agentId, message, metadata)
            }
        }
    }

    /**
     * Emits an inference-phase event to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     *
     * @param eventType The type of the event (e.g., "onInferenceStart", "onInferenceComplete")
     * @param agentId The unique identifier of the agent
     * @param message Optional message describing the event
     * @param metadata Additional metadata for the event
     */
    fun emitInferenceEvent(
        eventType: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        currentServerSideAgentEventHandlers()?.let { handlers ->
            eventScope.launch {
                handlers.onInferenceEvent(eventType, agentId, message, metadata)
            }
        }
    }

    /**
     * Emits a tool-phase event to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     *
     * @param eventType The type of the event (e.g., "onToolCall", "onToolResult")
     * @param agentId The unique identifier of the agent
     * @param message Optional message describing the event
     * @param metadata Additional metadata for the event
     */
    fun emitToolEvent(
        eventType: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        currentServerSideAgentEventHandlers()?.let { handlers ->
            eventScope.launch {
                handlers.onToolEvent(eventType, agentId, message, metadata)
            }
        }
    }

    /**
     * Emits an MCP-phase event to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     *
     * @param eventType The type of the event (e.g., "onMCPRequest", "onMCPResponse")
     * @param agentId The unique identifier of the agent
     * @param message Optional message describing the event
     * @param metadata Additional metadata for the event
     */
    fun emitMCPEvent(
        eventType: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        currentServerSideAgentEventHandlers()?.let { handlers ->
            eventScope.launch {
                handlers.onMCPEvent(eventType, agentId, message, metadata)
            }
        }
    }

    /**
     * Emits a skill-phase event to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     *
     * @param eventType The type of the event (e.g., "onSkillInvoke", "onSkillComplete")
     * @param agentId The unique identifier of the agent
     * @param message Optional message describing the event
     * @param metadata Additional metadata for the event
     */
    fun emitSkillEvent(
        eventType: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        currentServerSideAgentEventHandlers()?.let { handlers ->
            eventScope.launch {
                handlers.onSkillEvent(eventType, agentId, message, metadata)
            }
        }
    }

    /**
     * Emits a generic event with custom phase to server-side event handlers in a non-blocking manner.
     * This method can be called from any thread without blocking.
     *
     * @param eventType The type of the event
     * @param eventPhase The phase of the event (e.g., "agent", "inference", "tool", "mcp", "skill")
     * @param agentId The unique identifier of the agent
     * @param message Optional message describing the event
     * @param metadata Additional metadata for the event
     */
    fun emitEvent(
        eventType: String,
        eventPhase: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    ) {
        currentServerSideAgentEventHandlers()?.let { handlers ->
            eventScope.launch {
                handlers.onEvent(eventType, eventPhase, agentId, message, metadata)
            }
        }
    }

    /**
     * CoroutineContext element that manages ThreadLocal state for server-side agent event handlers.
     *
     * This context element ensures that the ThreadLocal is properly set when entering the context
     * and restored when exiting, enabling per-coroutine handler isolation.
     */
    private class ServerSideAgentEventHandlersContext(
        private val handlers: ServerSideAgentEventHandlers?
    ) : kotlinx.coroutines.ThreadContextElement<ServerSideAgentEventHandlers?> {
        companion object Key : CoroutineContext.Key<ServerSideAgentEventHandlersContext>

        override val key: CoroutineContext.Key<ServerSideAgentEventHandlersContext> = Key

        override fun updateThreadContext(context: CoroutineContext): ServerSideAgentEventHandlers? {
            val previous = serverSideAgentEventHandlersTL.get()
            serverSideAgentEventHandlersTL.set(handlers)
            return previous
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: ServerSideAgentEventHandlers?) {
            serverSideAgentEventHandlersTL.set(oldState)
        }
    }

    /**
     * Returns the handlers for the current context (per-coroutine override first, then global fallback).
     */
    private fun currentServerSideAgentEventHandlers(): ServerSideAgentEventHandlers? {
        return serverSideAgentEventHandlersTL.get() ?: serverSideAgentEventHandlers
    }
}
