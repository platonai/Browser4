package ai.platon.pulsar.agentic.event.detail

import ai.platon.pulsar.agentic.event.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Default implementation of [AgentFlowEventHandlers].
 *
 * Provides event handlers for the observe/act flow in agent operations.
 */
class DefaultAgentFlowEventHandlers : AgentFlowEventHandlers {
    override val onWillObserve: ObserveEventHandler = ObserveEventHandler()
    override val onDidObserve: ObserveEventHandler = ObserveEventHandler()

    override val onWillAct: ActEventHandler = ActEventHandler()
    override val onDidAct: ActEventHandler = ActEventHandler()

    override val onInferenceWillObserve: ExecutionContextAgentStateEventHandler = ExecutionContextAgentStateEventHandler()
    override val onInferenceDidObserve: ExecutionContextAgentStateEventHandler = ExecutionContextAgentStateEventHandler()

    override fun chain(other: AgentFlowEventHandlers): AgentFlowEventHandlers {
        onWillObserve.addLast(other.onWillObserve)
        onDidObserve.addLast(other.onDidObserve)
        onWillAct.addLast(other.onWillAct)
        onDidAct.addLast(other.onDidAct)
        onInferenceWillObserve.addLast(other.onInferenceWillObserve)
        onInferenceDidObserve.addLast(other.onInferenceDidObserve)
        return this
    }
}

/**
 * Default implementation of [ToolCallEventHandlers].
 *
 * Currently a placeholder for future tool call event handling.
 */
class DefaultToolCallEventHandlers : ToolCallEventHandlers {
    override fun chain(other: ToolCallEventHandlers): ToolCallEventHandlers {
        return this
    }
}

/**
 * Default implementation of [MCPEventHandlers].
 *
 * Currently a placeholder for future MCP event handling.
 */
class DefaultMCPEventHandlers : MCPEventHandlers {
    override fun chain(other: MCPEventHandlers): MCPEventHandlers {
        return this
    }
}

/**
 * Default implementation of [SkillEventHandlers].
 *
 * Currently a placeholder for future skill event handling.
 */
class DefaultSkillEventHandlers : SkillEventHandlers {
    override fun chain(other: SkillEventHandlers): SkillEventHandlers {
        return this
    }
}

/**
 * Default implementation of [ServerSideAgentEventHandlers].
 *
 * This implementation uses a [MutableSharedFlow] to broadcast events to all subscribers.
 * Events are emitted asynchronously and will not block the event producer.
 *
 * The shared flow is configured with:
 * - replay = 0: No replay for new subscribers (only receive new events)
 * - extraBufferCapacity = 64: Buffer up to 64 events if consumers are slow
 *
 * @see ServerSideAgentEventHandlers for interface documentation
 */
class DefaultServerSideAgentEventHandlers : ServerSideAgentEventHandlers {
    private val _eventFlow = MutableSharedFlow<ServerSideAgentEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    override val eventFlow: SharedFlow<ServerSideAgentEvent> = _eventFlow.asSharedFlow()

    override suspend fun onAgentEvent(
        eventType: String,
        agentId: String?,
        message: String?,
        metadata: Map<String, Any?>
    ) {
        emitEvent(
            ServerSideAgentEvent(
                eventType = eventType,
                eventPhase = "agent",
                agentId = agentId,
                message = message,
                metadata = metadata
            )
        )
    }

    override suspend fun onInferenceEvent(
        eventType: String,
        agentId: String?,
        message: String?,
        metadata: Map<String, Any?>
    ) {
        emitEvent(
            ServerSideAgentEvent(
                eventType = eventType,
                eventPhase = "inference",
                agentId = agentId,
                message = message,
                metadata = metadata
            )
        )
    }

    override suspend fun onToolEvent(
        eventType: String,
        agentId: String?,
        message: String?,
        metadata: Map<String, Any?>
    ) {
        emitEvent(
            ServerSideAgentEvent(
                eventType = eventType,
                eventPhase = "tool",
                agentId = agentId,
                message = message,
                metadata = metadata
            )
        )
    }

    override suspend fun onMCPEvent(
        eventType: String,
        agentId: String?,
        message: String?,
        metadata: Map<String, Any?>
    ) {
        emitEvent(
            ServerSideAgentEvent(
                eventType = eventType,
                eventPhase = "mcp",
                agentId = agentId,
                message = message,
                metadata = metadata
            )
        )
    }

    override suspend fun onSkillEvent(
        eventType: String,
        agentId: String?,
        message: String?,
        metadata: Map<String, Any?>
    ) {
        emitEvent(
            ServerSideAgentEvent(
                eventType = eventType,
                eventPhase = "skill",
                agentId = agentId,
                message = message,
                metadata = metadata
            )
        )
    }

    override suspend fun onEvent(
        eventType: String,
        eventPhase: String,
        agentId: String?,
        message: String?,
        metadata: Map<String, Any?>
    ) {
        emitEvent(
            ServerSideAgentEvent(
                eventType = eventType,
                eventPhase = eventPhase,
                agentId = agentId,
                message = message,
                metadata = metadata
            )
        )
    }

    private suspend fun emitEvent(event: ServerSideAgentEvent) {
        _eventFlow.emit(event)
    }
}

/**
 * Default implementation of [AgentEventHandlers].
 *
 * Composes all individual event handler types and provides a unified interface
 * for managing agent events.
 */
open class DefaultAgentEventHandlers(
    override var agentFlowHandlers: AgentFlowEventHandlers = DefaultAgentFlowEventHandlers(),
    override var toolCallEventHandlers: ToolCallEventHandlers = DefaultToolCallEventHandlers(),
    override var mcpEventHandlers: MCPEventHandlers = DefaultMCPEventHandlers(),
    override var skillEventHandlers: SkillEventHandlers = DefaultSkillEventHandlers(),
    override var serverSideAgentEventHandlers: ServerSideAgentEventHandlers = DefaultServerSideAgentEventHandlers()
) : AgentEventHandlers {

    override fun chain(other: AgentEventHandlers): AgentEventHandlers {
        agentFlowHandlers.chain(other.agentFlowHandlers)
        toolCallEventHandlers.chain(other.toolCallEventHandlers)
        mcpEventHandlers.chain(other.mcpEventHandlers)
        skillEventHandlers.chain(other.skillEventHandlers)
        return this
    }
}
