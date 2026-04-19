package ai.platon.pulsar.agentic.event

import ai.platon.pulsar.agentic.ActionOptions
import ai.platon.pulsar.agentic.ObserveOptions
import ai.platon.pulsar.agentic.event.detail.DefaultAgentEventHandlers
import ai.platon.pulsar.agentic.event.detail.DefaultAgentFlowEventHandlers
import ai.platon.pulsar.agentic.event.detail.DefaultServerSideAgentEventHandlers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for AgentEventBus.
 *
 * Tests the event handling mechanisms including:
 * - Event emission
 * - Per-coroutine handler isolation
 * - Server-side event handlers
 */
class AgentEventBusTest {

    @BeforeEach
    fun setUp() {
        // Reset to clean state before each test
        AgentEventBus.agentEventHandlers = null
        AgentEventBus.serverSideAgentEventHandlers = null
    }

    @AfterEach
    fun tearDown() {
        // Clean up after tests
        AgentEventBus.agentEventHandlers = null
        AgentEventBus.serverSideAgentEventHandlers = null
    }

    @Test
    @DisplayName("test agent event handlers can be set and retrieved")
    fun testAgentEventHandlersSetAndGet() {
        assertNull(AgentEventBus.agentEventHandlers)

        val handlers = DefaultAgentEventHandlers()
        AgentEventBus.agentEventHandlers = handlers

        assertNotNull(AgentEventBus.agentEventHandlers)
        assertSame(handlers, AgentEventBus.agentEventHandlers)
    }

    @Test
    @DisplayName("test server side agent event handlers can be set and retrieved")
    fun testServerSideAgentEventHandlersSetAndGet() {
        assertNull(AgentEventBus.serverSideAgentEventHandlers)

        val handlers = DefaultServerSideAgentEventHandlers()
        AgentEventBus.serverSideAgentEventHandlers = handlers

        assertNotNull(AgentEventBus.serverSideAgentEventHandlers)
        assertSame(handlers, AgentEventBus.serverSideAgentEventHandlers)
    }

    @Test
    @DisplayName("test emit agent event is received by handler")
    fun testEmitAgentEvent() = runBlocking {
        val handlers = DefaultServerSideAgentEventHandlers()
        AgentEventBus.serverSideAgentEventHandlers = handlers

        // Start collecting before emitting (SharedFlow with replay=0 requires subscriber first)
        val eventDeferred = async {
            handlers.eventFlow.first()
        }

        // Give the collector time to start
        delay(50)

        // Emit an event
        AgentEventBus.emitAgentEvent(
            eventType = "onWillObserve",
            agentId = "test-agent-123",
            message = "Starting observation"
        )

        // Wait for the event to be received
        val event = withTimeout(1000) { eventDeferred.await() }

        assertEquals("onWillObserve", event.eventType)
        assertEquals("agent", event.eventPhase)
        assertEquals("test-agent-123", event.agentId)
        assertEquals("Starting observation", event.message)
    }

    @Test
    @DisplayName("test emit inference event has correct phase")
    fun testEmitInferenceEvent() = runBlocking {
        val handlers = DefaultServerSideAgentEventHandlers()
        AgentEventBus.serverSideAgentEventHandlers = handlers

        val eventDeferred = async {
            handlers.eventFlow.first()
        }

        delay(50)

        AgentEventBus.emitInferenceEvent(
            eventType = "onInferenceComplete",
            agentId = "test-agent-456",
            metadata = mapOf("tokens" to 100)
        )

        val event = withTimeout(1000) { eventDeferred.await() }

        assertEquals("onInferenceComplete", event.eventType)
        assertEquals("inference", event.eventPhase)
        assertEquals("test-agent-456", event.agentId)
        assertEquals(100, event.metadata["tokens"])
    }

    @Test
    @DisplayName("test emit tool event has correct phase")
    fun testEmitToolEvent() = runBlocking {
        val handlers = DefaultServerSideAgentEventHandlers()
        AgentEventBus.serverSideAgentEventHandlers = handlers

        val eventDeferred = async {
            handlers.eventFlow.first()
        }

        delay(50)

        AgentEventBus.emitToolEvent(
            eventType = "onToolCall",
            agentId = "test-agent",
            message = "Calling click tool"
        )

        val event = withTimeout(1000) { eventDeferred.await() }
        assertEquals("tool", event.eventPhase)
    }

    @Test
    @DisplayName("test emit MCP event has correct phase")
    fun testEmitMCPEvent() = runBlocking {
        val handlers = DefaultServerSideAgentEventHandlers()
        AgentEventBus.serverSideAgentEventHandlers = handlers

        val eventDeferred = async {
            handlers.eventFlow.first()
        }

        delay(50)

        AgentEventBus.emitMCPEvent(eventType = "onMCPRequest")

        val event = withTimeout(1000) { eventDeferred.await() }
        assertEquals("mcp", event.eventPhase)
    }

    @Test
    @DisplayName("test emit skill event has correct phase")
    fun testEmitSkillEvent() = runBlocking {
        val handlers = DefaultServerSideAgentEventHandlers()
        AgentEventBus.serverSideAgentEventHandlers = handlers

        val eventDeferred = async {
            handlers.eventFlow.first()
        }

        delay(50)

        AgentEventBus.emitSkillEvent(eventType = "onSkillInvoke")

        val event = withTimeout(1000) { eventDeferred.await() }
        assertEquals("skill", event.eventPhase)
    }

    @Test
    @DisplayName("test emit generic event with custom phase")
    fun testEmitGenericEvent() = runBlocking {
        val handlers = DefaultServerSideAgentEventHandlers()
        AgentEventBus.serverSideAgentEventHandlers = handlers

        val eventDeferred = async {
            handlers.eventFlow.first()
        }

        delay(50)

        AgentEventBus.emitEvent(
            eventType = "customEvent",
            eventPhase = "custom",
            agentId = "test"
        )

        val event = withTimeout(1000) { eventDeferred.await() }
        assertEquals("customEvent", event.eventType)
        assertEquals("custom", event.eventPhase)
    }

    @Test
    @DisplayName("test no event emitted when handler is null")
    fun testNoEventEmittedWhenHandlerNull() {
        // Ensure handler is null
        AgentEventBus.serverSideAgentEventHandlers = null

        // This should not throw an exception
        AgentEventBus.emitAgentEvent("onWillObserve", "agent-123")

        // Nothing to verify, just ensure no exception is thrown
    }

    @Test
    @DisplayName("test per coroutine handler isolation with withServerSideAgentEventHandlers")
    fun testWithServerSideAgentEventHandlers() = runBlocking {
        val globalHandlers = DefaultServerSideAgentEventHandlers()
        val localHandlers = DefaultServerSideAgentEventHandlers()

        AgentEventBus.serverSideAgentEventHandlers = globalHandlers

        // Start collector for local events before entering context
        val localEventDeferred = async {
            localHandlers.eventFlow.first()
        }

        delay(50)

        // Emit event using local handlers within context
        AgentEventBus.withServerSideAgentEventHandlers(localHandlers) {
            AgentEventBus.emitAgentEvent("localEvent", "local-agent")
        }

        // Wait for local event
        val localEvent = withTimeout(1000) { localEventDeferred.await() }
        assertEquals("localEvent", localEvent.eventType)

        // Start collector for global events
        val globalEventDeferred = async {
            globalHandlers.eventFlow.first()
        }

        delay(50)

        // Emit event using global handlers
        AgentEventBus.emitAgentEvent("globalEvent", "global-agent")

        val globalEvent = withTimeout(1000) { globalEventDeferred.await() }
        assertEquals("globalEvent", globalEvent.eventType)
    }
}

/**
 * Unit tests for DefaultAgentEventHandlers.
 */
class DefaultAgentEventHandlersTest {

    @Test
    @DisplayName("test default agent event handlers initialization")
    fun testDefaultAgentEventHandlersInit() {
        val handlers = DefaultAgentEventHandlers()

        assertNotNull(handlers.agentFlowHandlers)
        assertNotNull(handlers.toolCallEventHandlers)
        assertNotNull(handlers.mcpEventHandlers)
        assertNotNull(handlers.skillEventHandlers)
        assertNotNull(handlers.serverSideAgentEventHandlers)
    }

    @Test
    @DisplayName("test agent event handlers aliases work correctly")
    fun testAliases() {
        val handlers = DefaultAgentEventHandlers()

        // Verify aliases return the same objects
        assertSame(handlers.agentFlowHandlers, handlers.af)
        assertSame(handlers.toolCallEventHandlers, handlers.tc)
        assertSame(handlers.mcpEventHandlers, handlers.mcp)
        assertSame(handlers.skillEventHandlers, handlers.sk)
        assertSame(handlers.serverSideAgentEventHandlers, handlers.sse)
    }

    @Test
    @DisplayName("test agent flow event handlers chain works correctly")
    fun testAgentFlowHandlersChain() {
        val handlers1 = DefaultAgentFlowEventHandlers()
        val handlers2 = DefaultAgentFlowEventHandlers()

        var handler1Called = false
        var handler2Called = false

        handlers1.onWillObserve.addLast { _ ->
            handler1Called = true
            null
        }

        handlers2.onWillObserve.addLast { _ ->
            handler2Called = true
            null
        }

        // Chain handlers2 to handlers1
        handlers1.chain(handlers2)

        // Invoke should call both handlers
        handlers1.onWillObserve.invoke(ObserveOptions())

        assertTrue(handler1Called)
        assertTrue(handler2Called)
    }

    @Test
    @DisplayName("test agent event handlers chain works correctly")
    fun testAgentEventHandlersChain() {
        val handlers1 = DefaultAgentEventHandlers()
        val handlers2 = DefaultAgentEventHandlers()

        var handler1Called = false
        var handler2Called = false

        handlers1.agentFlowHandlers.onWillAct.addLast { _ ->
            handler1Called = true
            null
        }

        handlers2.agentFlowHandlers.onWillAct.addLast { _ ->
            handler2Called = true
            null
        }

        // Chain handlers2 to handlers1
        handlers1.chain(handlers2)

        // Invoke should call both handlers
        handlers1.agentFlowHandlers.onWillAct.invoke(ActionOptions(action = "test"))

        assertTrue(handler1Called)
        assertTrue(handler2Called)
    }
}

/**
 * Unit tests for DefaultServerSideAgentEventHandlers.
 */
class DefaultServerSideAgentEventHandlersTest {

    @Test
    @DisplayName("test server side agent event handlers emit events correctly")
    fun testEmitEvents() = runBlocking {
        val handlers = DefaultServerSideAgentEventHandlers()
        val events = CopyOnWriteArrayList<ServerSideAgentEvent>()

        val job = launch {
            handlers.eventFlow.collect { events.add(it) }
        }

        // Give the collector time to start
        delay(50)

        // Emit different types of events
        handlers.onAgentEvent("test1", "agent1")
        handlers.onInferenceEvent("test2", "agent2")
        handlers.onToolEvent("test3", "agent3")
        handlers.onMCPEvent("test4", "agent4")
        handlers.onSkillEvent("test5", "agent5")

        // Wait for events to be processed
        delay(100)

        assertEquals(5, events.size)
        assertEquals("agent", events[0].eventPhase)
        assertEquals("inference", events[1].eventPhase)
        assertEquals("tool", events[2].eventPhase)
        assertEquals("mcp", events[3].eventPhase)
        assertEquals("skill", events[4].eventPhase)

        job.cancel()
    }

    @Test
    @DisplayName("test server side agent event has correct timestamp")
    fun testEventTimestamp() = runBlocking {
        val handlers = DefaultServerSideAgentEventHandlers()
        val beforeEmit = java.time.Instant.now()

        val eventDeferred = async {
            handlers.eventFlow.first()
        }

        delay(50)

        handlers.onAgentEvent("test", "agent")

        val event = withTimeout(1000) { eventDeferred.await() }
        val afterEmit = java.time.Instant.now()

        assertTrue(event.timestamp >= beforeEmit)
        assertTrue(event.timestamp <= afterEmit)
    }
}

/**
 * Unit tests for ServerSideAgentEvent data class.
 */
class ServerSideAgentEventTest {

    @Test
    @DisplayName("test server side agent event default values")
    fun testDefaultValues() {
        val event = ServerSideAgentEvent(
            eventType = "testEvent",
            eventPhase = "testPhase"
        )

        assertEquals("testEvent", event.eventType)
        assertEquals("testPhase", event.eventPhase)
        assertNull(event.agentId)
        assertNull(event.message)
        assertNotNull(event.timestamp)
        assertTrue(event.metadata.isEmpty())
    }

    @Test
    @DisplayName("test server side agent event with all fields")
    fun testAllFields() {
        val metadata = mapOf("key" to "value", "count" to 42)
        val timestamp = java.time.Instant.now()

        val event = ServerSideAgentEvent(
            eventType = "testEvent",
            eventPhase = "agent",
            agentId = "agent-123",
            message = "Test message",
            timestamp = timestamp,
            metadata = metadata
        )

        assertEquals("testEvent", event.eventType)
        assertEquals("agent", event.eventPhase)
        assertEquals("agent-123", event.agentId)
        assertEquals("Test message", event.message)
        assertEquals(timestamp, event.timestamp)
        assertEquals("value", event.metadata["key"])
        assertEquals(42, event.metadata["count"])
    }

    @Test
    @DisplayName("test server side agent event copy")
    fun testCopy() {
        val original = ServerSideAgentEvent(
            eventType = "original",
            eventPhase = "agent",
            agentId = "agent-1"
        )

        val copy = original.copy(eventType = "modified")

        assertEquals("modified", copy.eventType)
        assertEquals("agent", copy.eventPhase)
        assertEquals("agent-1", copy.agentId)
    }
}
