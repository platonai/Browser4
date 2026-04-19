@file:Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "unused")

package ai.platon.pulsar.agentic.event

import ai.platon.pulsar.agentic.ActionOptions
import ai.platon.pulsar.agentic.ObserveOptions
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.common.lang.AbstractChainedFunction1
import ai.platon.pulsar.common.lang.AbstractChainedPDFunction2
import kotlinx.coroutines.flow.SharedFlow

/**
 * Server-side agent event data structure.
 *
 * @property eventType The type of the event (e.g., "onWillObserve", "onDidAct", "onInferenceComplete").
 * @property eventPhase The phase of the event (e.g., "agent", "inference", "tool", "mcp", "skill").
 * @property agentId The unique identifier of the agent, if applicable.
 * @property message Optional message or description of the event.
 * @property timestamp The timestamp when the event was created.
 * @property metadata Additional metadata associated with the event.
 */
data class ServerSideAgentEvent(
    val eventType: String,
    val eventPhase: String,
    val agentId: String? = null,
    val message: String? = null,
    val timestamp: java.time.Instant = java.time.Instant.now(),
    val metadata: Map<String, Any?> = emptyMap()
)

open class ObserveEventHandler : AbstractChainedFunction1<ObserveOptions, Any?>() {
    override fun invoke(options: ObserveOptions): Any? {
        return super.invoke(param = options)
    }
}

open class ActEventHandler : AbstractChainedFunction1<ActionOptions, Any?>() {
    override fun invoke(options: ActionOptions): Any? {
        return super.invoke(param = options)
    }
}

open class ExecutionContextAgentStateEventHandler : AbstractChainedPDFunction2<ExecutionContext, AgentState, Any?>() {
    override suspend fun invoke(context: ExecutionContext, agentState: AgentState): Any? {
        return super.invoke(param = context, param2 = agentState)
    }
}

/**
 * Event handlers during the agent flow phase.
 *
 * Agent flow event handlers are triggered during observe and act operations.
 * They are ideal for:
 * - Monitoring agent behavior
 * - Logging and debugging
 * - Intercepting and modifying agent actions
 *
 * @see AgentEventHandlers for the complete event handler hierarchy
 */
interface AgentFlowEventHandlers {
    val onWillObserve: ObserveEventHandler
    val onDidObserve: ObserveEventHandler

    val onWillAct: ActEventHandler
    val onDidAct: ActEventHandler

    val onInferenceWillObserve: ExecutionContextAgentStateEventHandler
    val onInferenceDidObserve: ExecutionContextAgentStateEventHandler

    /**
     * Chains another agent flow event handler to the tail of this one.
     *
     * @param other The agent flow event handlers to chain
     * @return This handler instance for fluent chaining
     */
    fun chain(other: AgentFlowEventHandlers): AgentFlowEventHandlers
}

/**
 * Event handlers for tool call operations.
 *
 * @see AgentEventHandlers for the complete event handler hierarchy
 */
interface ToolCallEventHandlers {
    /**
     * Chains another tool call event handler to the tail of this one.
     *
     * @param other The tool call event handlers to chain
     * @return This handler instance for fluent chaining
     */
    fun chain(other: ToolCallEventHandlers): ToolCallEventHandlers
}

/**
 * Event handlers for MCP (Model Context Protocol) operations.
 *
 * @see AgentEventHandlers for the complete event handler hierarchy
 */
interface MCPEventHandlers {
    /**
     * Chains another MCP event handler to the tail of this one.
     *
     * @param other The MCP event handlers to chain
     * @return This handler instance for fluent chaining
     */
    fun chain(other: MCPEventHandlers): MCPEventHandlers
}

/**
 * Event handlers for skill operations.
 *
 * @see AgentEventHandlers for the complete event handler hierarchy
 */
interface SkillEventHandlers {
    /**
     * Chains another skill event handler to the tail of this one.
     *
     * @param other The skill event handlers to chain
     * @return This handler instance for fluent chaining
     */
    fun chain(other: SkillEventHandlers): SkillEventHandlers
}

/**
 * Server-side event handlers for capturing and broadcasting agent events to external listeners.
 *
 * This interface defines methods for receiving events from various agent phases (agent, inference, tool, mcp, skill)
 * and forwarding them to subscribed listeners through a reactive stream.
 *
 * Events can be emitted at any point during agent execution and will be
 * automatically forwarded to all subscribers through the event flow.
 *
 * ## Example Usage
 * ```kotlin
 * val eventHandlers = DefaultServerSideAgentEventHandlers()
 *
 * // Subscribe to events
 * eventHandlers.eventFlow.collect { event ->
 *     println("Received agent event: ${event.eventType} for agent ${event.agentId}")
 * }
 *
 * // Emit events during agent execution
 * eventHandlers.onAgentEvent("onWillObserve", "agent-123")
 * eventHandlers.onInferenceEvent("onInferenceComplete", "agent-123")
 * ```
 *
 * @see ServerSideAgentEvent for the event data structure
 * @see AgentEventBus for integration with the global agent event system
 */
interface ServerSideAgentEventHandlers {
    /**
     * The shared flow of server-side agent events.
     * Subscribers can collect from this flow to receive all events.
     */
    val eventFlow: SharedFlow<ServerSideAgentEvent>

    /**
     * Emits an agent-phase event.
     *
     * @param eventType The type of the event (e.g., "onWillObserve", "onDidAct").
     * @param agentId The unique identifier of the agent.
     * @param message Optional message describing the event.
     * @param metadata Additional metadata for the event.
     */
    suspend fun onAgentEvent(
        eventType: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    )

    /**
     * Emits an inference-phase event.
     *
     * @param eventType The type of the event (e.g., "onInferenceStart", "onInferenceComplete").
     * @param agentId The unique identifier of the agent.
     * @param message Optional message describing the event.
     * @param metadata Additional metadata for the event.
     */
    suspend fun onInferenceEvent(
        eventType: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    )

    /**
     * Emits a tool-phase event.
     *
     * @param eventType The type of the event (e.g., "onToolCall", "onToolResult").
     * @param agentId The unique identifier of the agent.
     * @param message Optional message describing the event.
     * @param metadata Additional metadata for the event.
     */
    suspend fun onToolEvent(
        eventType: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    )

    /**
     * Emits an MCP-phase event.
     *
     * @param eventType The type of the event (e.g., "onMCPRequest", "onMCPResponse").
     * @param agentId The unique identifier of the agent.
     * @param message Optional message describing the event.
     * @param metadata Additional metadata for the event.
     */
    suspend fun onMCPEvent(
        eventType: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    )

    /**
     * Emits a skill-phase event.
     *
     * @param eventType The type of the event (e.g., "onSkillInvoke", "onSkillComplete").
     * @param agentId The unique identifier of the agent.
     * @param message Optional message describing the event.
     * @param metadata Additional metadata for the event.
     */
    suspend fun onSkillEvent(
        eventType: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    )

    /**
     * Emits a generic event with custom phase.
     *
     * @param eventType The type of the event.
     * @param eventPhase The phase of the event.
     * @param agentId The unique identifier of the agent.
     * @param message Optional message describing the event.
     * @param metadata Additional metadata for the event.
     */
    suspend fun onEvent(
        eventType: String,
        eventPhase: String,
        agentId: String? = null,
        message: String? = null,
        metadata: Map<String, Any?> = emptyMap()
    )
}

/**
 * The central interface for managing all event handlers triggered at various stages of an agent's lifecycle.
 *
 * `AgentEventHandlers` organizes events into distinct groups:
 *
 * 1. **[AgentFlowEventHandlers]** - Events during observe and act operations
 *    - Before/after observe, before/after act, inference events
 * 2. **[ToolCallEventHandlers]** - Events during tool call operations
 * 3. **[MCPEventHandlers]** - Events during MCP operations
 * 4. **[SkillEventHandlers]** - Events during skill operations
 * 5. **[ServerSideAgentEventHandlers]** - Events for broadcasting to external listeners
 *
 * ## Usage Patterns
 *
 * ### Setting up event handlers
 * ```kotlin
 * val handlers = DefaultAgentEventHandlers()
 * handlers.agentFlowHandlers.onWillObserve.addLast { options ->
 *     println("About to observe with options: $options")
 * }
 * handlers.agentFlowHandlers.onDidAct.addLast { options ->
 *     println("Completed action: $options")
 * }
 * ```
 *
 * ### Using aliases for brevity
 * ```kotlin
 * handlers.af.onWillObserve.addLast { options -> }  // agentFlowHandlers
 * handlers.tc.chain(otherHandlers.tc)               // toolCallEventHandlers
 * ```
 *
 * @see AgentFlowEventHandlers for agent flow events
 * @see ToolCallEventHandlers for tool call events
 * @see MCPEventHandlers for MCP events
 * @see SkillEventHandlers for skill events
 * @see ServerSideAgentEventHandlers for server-side event broadcasting
 * @see AgentEventBus for setting global handlers
 */
interface AgentEventHandlers {
    /**
     * Event handlers during the agent flow stage.
     *
     * Manages observe and act operations.
     *
     * @see AgentFlowEventHandlers for detailed documentation
     */
    var agentFlowHandlers: AgentFlowEventHandlers

    /**
     * Event handlers during tool call operations.
     *
     * @see ToolCallEventHandlers for detailed documentation
     */
    var toolCallEventHandlers: ToolCallEventHandlers

    /**
     * Event handlers during MCP operations.
     *
     * @see MCPEventHandlers for detailed documentation
     */
    var mcpEventHandlers: MCPEventHandlers

    /**
     * Event handlers during skill operations.
     *
     * @see SkillEventHandlers for detailed documentation
     */
    var skillEventHandlers: SkillEventHandlers

    /**
     * Server-side event handlers for broadcasting agent events to external listeners.
     *
     * @see ServerSideAgentEventHandlers for detailed documentation
     */
    var serverSideAgentEventHandlers: ServerSideAgentEventHandlers

    /**
     * Alias for [agentFlowHandlers].
     *
     * Provides a shorter name for concise handler configuration.
     *
     * ## Example
     * ```kotlin
     * handlers.af.onWillObserve.addLast { options -> }
     * ```
     */
    var af
        get() = agentFlowHandlers
        set(value) {
            agentFlowHandlers = value
        }

    /**
     * Alias for [toolCallEventHandlers].
     *
     * Provides a shorter name for concise handler configuration.
     *
     * ## Example
     * ```kotlin
     * handlers.tc.chain(otherHandlers.tc)
     * ```
     */
    var tc
        get() = toolCallEventHandlers
        set(value) {
            toolCallEventHandlers = value
        }

    /**
     * Alias for [mcpEventHandlers].
     *
     * Provides a shorter name for concise handler configuration.
     */
    var mcp
        get() = mcpEventHandlers
        set(value) {
            mcpEventHandlers = value
        }

    /**
     * Alias for [skillEventHandlers].
     *
     * Provides a shorter name for concise handler configuration.
     */
    var sk
        get() = skillEventHandlers
        set(value) {
            skillEventHandlers = value
        }

    /**
     * Alias for [serverSideAgentEventHandlers].
     *
     * Provides a shorter name for concise handler configuration.
     */
    var sse
        get() = serverSideAgentEventHandlers
        set(value) {
            serverSideAgentEventHandlers = value
        }

    /**
     * Chains another agent event handler to the tail of this one.
     *
     * All handler groups are chained together.
     * Chained handlers execute in order: this handler's callbacks first,
     * then the other handler's callbacks.
     *
     * @param other The agent event handlers to chain
     * @return This handler instance for fluent chaining
     */
    fun chain(other: AgentEventHandlers): AgentEventHandlers
}

