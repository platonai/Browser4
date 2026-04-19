package ai.platon.pulsar.agentic.observability

import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.common.event.EventBus
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for EventBus mechanism that provides observability and testability
 * for PerceptiveAgent and InferenceEngine methods.
 */
@Tag("observability")
class EventBusObservabilityTest {

    companion object {
        private val capturedEvents = ConcurrentHashMap<String, MutableList<Map<String, Any?>>>()

        @BeforeAll
        @JvmStatic
        fun setupEventHandlers() {
            // Register event handlers using centralized event definitions
            AgenticEvents.getAllEventTypes().forEach { eventType ->
                EventBus.register(eventType) { payload ->
                    val map = payload as? Map<String, Any?> ?: return@register null
                    capturedEvents.computeIfAbsent(eventType) { mutableListOf() }.add(map)
                    payload
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            // Unregister all event handlers using centralized event definitions
            AgenticEvents.getAllEventTypes().forEach { eventType ->
                EventBus.unregister(eventType)
            }
        }
    }

    @BeforeEach
    fun clearCapturedEvents() {
        capturedEvents.clear()
    }

    @Test
    fun testEventBusRegistrationAndEmit() {
        val testEventType = "test.event"
        var handlerCalled = false
        var receivedPayload: Map<String, Any?>? = null

        EventBus.register(testEventType) { payload ->
            handlerCalled = true
            receivedPayload = payload as? Map<String, Any?>
            payload
        }

        val testPayload = mapOf("key" to "value", "number" to 42)
        EventBus.emit(testEventType, testPayload)

        // Give event bus time to process
        Thread.sleep(100)

        assertTrue(handlerCalled, "Event handler should be called")
        assertNotNull(receivedPayload, "Payload should be received")
        assertEquals("value", receivedPayload?.get("key"))
        assertEquals(42, receivedPayload?.get("number"))

        EventBus.unregister(testEventType)
    }

    @Test
    fun testPerceptiveAgentRunEvents() {
        val eventType = AgenticEvents.PerceptiveAgent.ON_WILL_RUN

        // Simulate emitting the event
        val testPayload = mapOf(
            "action" to "test action",
            "uuid" to "test-uuid"
        )
        EventBus.emit(eventType, testPayload)

        // Give event bus time to process
        Thread.sleep(100)

        val events = capturedEvents[eventType]
        assertNotNull(events, "Events should be captured")
        assertTrue(events!!.isNotEmpty(), "At least one event should be captured")
        assertEquals("test action", events[0]["action"])
        assertEquals("test-uuid", events[0]["uuid"])
    }

    @Test
    fun testPerceptiveAgentObserveEvents() {
        val willEventType = AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE
        val didEventType = AgenticEvents.PerceptiveAgent.ON_DID_OBSERVE

        // Simulate observing
        val options = mapOf("instruction" to "find button")
        EventBus.emit(willEventType, mapOf("options" to options, "uuid" to "test-uuid"))

        Thread.sleep(100)

        val results = listOf(mapOf("locator" to "1,123"))
        EventBus.emit(didEventType, mapOf(
            "options" to options,
            "uuid" to "test-uuid",
            "observeResults" to results
        ))

        Thread.sleep(100)

        val willEvents = capturedEvents[willEventType]
        val didEvents = capturedEvents[didEventType]

        assertTrue(willEvents != null, "Will events should be captured")
        assertTrue(didEvents != null, "Did events should be captured")
        assertTrue(willEvents!!.isNotEmpty())
        assertTrue(didEvents!!.isNotEmpty())
    }

    @Test
    fun testPerceptiveAgentActEvents() {
        val willEventType = AgenticEvents.PerceptiveAgent.ON_WILL_ACT
        val didEventType = AgenticEvents.PerceptiveAgent.ON_DID_ACT

        val action = mapOf("action" to "click button")
        EventBus.emit(willEventType, mapOf("action" to action, "uuid" to "test-uuid"))

        Thread.sleep(100)

        val result = mapOf("success" to true)
        EventBus.emit(didEventType, mapOf(
            "action" to action,
            "uuid" to "test-uuid",
            "result" to result
        ))

        Thread.sleep(100)

        val willEvents = capturedEvents[willEventType]
        val didEvents = capturedEvents[didEventType]

        assertTrue(willEvents != null)
        assertTrue(didEvents != null)
        assertTrue(willEvents!!.isNotEmpty())
        assertTrue(didEvents!!.isNotEmpty())
        @Suppress("UNCHECKED_CAST")
        assertEquals(true, (didEvents[0]["result"] as? Map<String, Any?>)?.get("success"))
    }

    @Test
    fun testPerceptiveAgentExtractEvents() {
        val willEventType = AgenticEvents.PerceptiveAgent.ON_WILL_EXTRACT
        val didEventType = AgenticEvents.PerceptiveAgent.ON_DID_EXTRACT

        val options = mapOf("instruction" to "extract data")
        EventBus.emit(willEventType, mapOf("options" to options, "uuid" to "test-uuid"))

        Thread.sleep(100)

        val result = mapOf("success" to true, "data" to mapOf("field" to "value"))
        EventBus.emit(didEventType, mapOf(
            "options" to options,
            "uuid" to "test-uuid",
            "result" to result
        ))

        Thread.sleep(100)

        val willEvents = capturedEvents[willEventType]
        val didEvents = capturedEvents[didEventType]

        assertTrue(willEvents != null)
        assertTrue(didEvents != null)
        assertTrue(willEvents!!.isNotEmpty())
        assertTrue(didEvents!!.isNotEmpty())
    }

    @Test
    fun testPerceptiveAgentSummarizeEvents() {
        val willEventType = AgenticEvents.PerceptiveAgent.ON_WILL_SUMMARIZE
        val didEventType = AgenticEvents.PerceptiveAgent.ON_DID_SUMMARIZE

        EventBus.emit(willEventType, mapOf(
            "instruction" to "summarize page",
            "selector" to null,
            "uuid" to "test-uuid"
        ))

        Thread.sleep(100)

        EventBus.emit(didEventType, mapOf(
            "instruction" to "summarize page",
            "selector" to null,
            "uuid" to "test-uuid",
            "result" to "Summary of the page"
        ))

        Thread.sleep(100)

        val willEvents = capturedEvents[willEventType]
        val didEvents = capturedEvents[didEventType]

        assertTrue(willEvents != null)
        assertTrue(didEvents != null)
        assertTrue(willEvents!!.isNotEmpty())
        assertTrue(didEvents!!.isNotEmpty())
        assertEquals("Summary of the page", didEvents[0]["result"])
    }

    @Test
    fun testInferenceEngineObserveEvents() {
        val willEventType = AgenticEvents.ContextToAction.ON_WILL_GENERATE
        val didEventType = AgenticEvents.ContextToAction.ON_DID_GENERATE

        val context = mapOf("step" to 1)
        val messages = mapOf("content" to "test message")

        EventBus.emit(willEventType, mapOf(
            "context" to context,
            "messages" to messages
        ))

        Thread.sleep(100)

        val actionDescription = mapOf("method" to "click")
        EventBus.emit(didEventType, mapOf(
            "context" to context,
            "messages" to messages,
            "actionDescription" to actionDescription
        ))

        Thread.sleep(100)

        val willEvents = capturedEvents[willEventType]
        val didEvents = capturedEvents[didEventType]

        assertTrue(willEvents != null)
        assertTrue(didEvents != null)
        assertTrue(willEvents!!.isNotEmpty())
        assertTrue(didEvents!!.isNotEmpty())
    }

    @Test
    fun testInferenceEngineExtractEvents() {
        val willEventType = AgenticEvents.InferenceEngine.ON_WILL_EXTRACT
        val didEventType = AgenticEvents.InferenceEngine.ON_DID_EXTRACT

        val params = mapOf("instruction" to "extract data", "schema" to emptyMap<String, Any>())
        EventBus.emit(willEventType, mapOf("params" to params))

        Thread.sleep(100)

        val result = mapOf("field" to "value")
        EventBus.emit(didEventType, mapOf(
            "params" to params,
            "result" to result
        ))

        Thread.sleep(100)

        val willEvents = capturedEvents[willEventType]
        val didEvents = capturedEvents[didEventType]

        assertTrue(willEvents != null)
        assertTrue(didEvents != null)
        assertTrue(willEvents!!.isNotEmpty())
        assertTrue(didEvents!!.isNotEmpty())
    }

    @Test
    fun testInferenceEngineSummarizeEvents() {
        val willEventType = AgenticEvents.InferenceEngine.ON_WILL_SUMMARIZE
        val didEventType = AgenticEvents.InferenceEngine.ON_DID_SUMMARIZE

        EventBus.emit(willEventType, mapOf(
            "instruction" to "summarize",
            "textContentLength" to 1000
        ))

        Thread.sleep(100)

        EventBus.emit(didEventType, mapOf(
            "instruction" to "summarize",
            "textContentLength" to 1000,
            "result" to "Summary text",
            "tokenUsage" to mapOf("total" to 500)
        ))

        Thread.sleep(100)

        val willEvents = capturedEvents[willEventType]
        val didEvents = capturedEvents[didEventType]

        assertTrue(willEvents != null)
        assertTrue(didEvents != null)
        assertTrue(willEvents!!.isNotEmpty())
        assertTrue(didEvents!!.isNotEmpty())
        assertEquals("Summary text", didEvents[0]["result"])
    }

    @Test
    fun testEventHandlerCanModifyPayload() {
        val eventType = "test.modify.event"

        EventBus.register(eventType) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            // Handler can modify or enrich the payload
            val modified = map.toMutableMap()
            modified["modified"] = true
            modified
        }

        val testPayload = mapOf("original" to "data")
        EventBus.emit(eventType, testPayload)

        Thread.sleep(100)

        EventBus.unregister(eventType)
    }

    @Test
    fun testMultipleHandlersForSameEvent() {
        val eventType = "test.multiple.handlers"
        var handler1Called = false
        var handler2Called = false

        val handler1 = EventBus.register(eventType) { payload ->
            handler1Called = true
            payload
        }

        // Register a second handler by overwriting
        EventBus.register(eventType) { payload ->
            handler2Called = true
            payload
        }

        EventBus.emit(eventType, mapOf("test" to "data"))

        Thread.sleep(100)

        // Only the last registered handler should be called (overwrite behavior)
        assertFalse(handler1Called, "First handler should not be called after overwrite")
        assertTrue(handler2Called, "Second handler should be called")

        EventBus.unregister(eventType)
    }

    @Test
    fun testEventBusWithNullPayload() {
        val eventType = "test.null.payload"
        var handlerCalled = false

        EventBus.register(eventType) { payload ->
            handlerCalled = true
            null // Handler can return null
        }

        EventBus.emit(eventType, mapOf<String, Any?>())

        Thread.sleep(100)

        assertTrue(handlerCalled, "Handler should be called even with null return")

        EventBus.unregister(eventType)
    }

    @Test
    fun testUnregisterEventHandler() {
        val eventType = "test.unregister"
        var callCount = 0

        EventBus.register(eventType) { payload ->
            callCount++
            payload
        }

        EventBus.emit(eventType, mapOf("test" to "1"))
        Thread.sleep(100)
        assertEquals(1, callCount)

        EventBus.unregister(eventType)

        EventBus.emit(eventType, mapOf("test" to "2"))
        Thread.sleep(100)
        // Count should still be 1 since handler was unregistered
        assertEquals(1, callCount)
    }
}
