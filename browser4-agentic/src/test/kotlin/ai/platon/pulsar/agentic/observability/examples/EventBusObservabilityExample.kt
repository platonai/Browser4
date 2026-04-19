package ai.platon.pulsar.agentic.observability.examples

import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.common.event.EventBus
import ai.platon.pulsar.common.serialize.json.Pson
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Example demonstrating how to use the EventBus mechanism for observability
 * and monitoring of PerceptiveAgent and InferenceEngine operations.
 */
class EventBusObservabilityExample {

    private val logger = LoggerFactory.getLogger(EventBusObservabilityExample::class.java)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val example = EventBusObservabilityExample()
            example.runExample()
        }
    }

    /**
     * Demonstrates various EventBus usage patterns
     */
    fun runExample() {
        println("=== EventBus Observability Example ===\n")

        // Example 1: Simple event monitoring
        simpleEventMonitoring()

        // Example 2: Performance tracking
        performanceTracking()

        // Example 3: Action validation
        actionValidation()

        // Example 4: Event counting
        eventCounting()

        // Clean up
        cleanup()

        println("\n=== Example Completed ===")
    }

    /**
     * Example 1: Simple monitoring of agent events
     */
    private fun simpleEventMonitoring() {
        println("--- Example 1: Simple Event Monitoring ---")

        // Register handlers for agent events
        EventBus.register(AgenticEvents.PerceptiveAgent.ON_WILL_ACT) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val action = map["action"]
            println("🚀 Starting action execution: $action")
            payload
        }

        EventBus.register(AgenticEvents.PerceptiveAgent.ON_DID_ACT) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val result = map["result"]
            println("✅ Action completed with result: $result")
            payload
        }

        // Simulate emitting events
        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_WILL_ACT, mapOf(
                "action" to mapOf("action" to "click button"),
                "uuid" to "example-uuid"
            )
        )

        Thread.sleep(100)

        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_DID_ACT, mapOf(
                "action" to mapOf("action" to "click button"),
                "uuid" to "example-uuid",
                "result" to mapOf("success" to true, "message" to "Button clicked")
            )
        )

        Thread.sleep(100)
        println()
    }

    /**
     * Example 2: Tracking execution performance
     */
    private fun performanceTracking() {
        println("--- Example 2: Performance Tracking ---")

        val executionTimes = ConcurrentHashMap<String, Long>()

        // Track start time
        EventBus.register(AgenticEvents.ContextToAction.ON_WILL_GENERATE) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val context = map["context"] as? Map<String, Any?>
            val uuid = context?.get("uuid") as? String
            if (uuid != null) {
                executionTimes[uuid] = Instant.now().toEpochMilli()
                println("⏱️  Starting LLM inference for context: $uuid")
            }
            payload
        }

        // Calculate duration
        EventBus.register(AgenticEvents.ContextToAction.ON_DID_GENERATE) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val context = map["context"] as? Map<String, Any?>
            val uuid = context?.get("uuid") as? String
            if (uuid != null) {
                val startTime = executionTimes[uuid]
                if (startTime != null) {
                    val duration = Instant.now().toEpochMilli() - startTime
                    println("⏱️  LLM inference completed in ${duration}ms")
                }
            }
            payload
        }

        // Simulate inference events
        val testContext = mapOf("uuid" to "inference-123", "step" to 1)
        EventBus.emit(
            AgenticEvents.ContextToAction.ON_WILL_GENERATE, mapOf(
                "context" to testContext,
                "messages" to emptyList<Any>()
            )
        )

        Thread.sleep(150) // Simulate LLM processing

        EventBus.emit(
            AgenticEvents.ContextToAction.ON_DID_GENERATE, mapOf(
                "context" to testContext,
                "messages" to emptyList<Any>(),
                "actionDescription" to mapOf("method" to "click")
            )
        )

        Thread.sleep(100)
        println()
    }

    /**
     * Example 3: Validating action descriptions
     */
    private fun actionValidation() {
        println("--- Example 3: Action Validation ---")

        EventBus.register(AgenticEvents.ContextToAction.ON_DID_GENERATE) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val actionDescription = map["actionDescription"]

            if (actionDescription != null) {
                println("🔍 Validating action description...")

                // Simulate validation (in real code, you'd check ActionDescription fields)
                val actionMap = actionDescription as? Map<String, Any?>
                val method = actionMap?.get("method")

                if (method != null) {
                    println("✅ Action validated: method=$method")
                } else {
                    println("⚠️  Action validation failed: no method found")
                }

                // You can enrich the payload
                val enriched = map.toMutableMap()
                enriched["validated"] = method != null
                enriched["validatedAt"] = Instant.now().toString()
                return@register enriched
            }

            payload
        }

        // Simulate action generation
        EventBus.emit(
            AgenticEvents.ContextToAction.ON_DID_GENERATE, mapOf(
                "context" to mapOf("uuid" to "validation-test"),
                "messages" to emptyList<Any>(),
                "actionDescription" to mapOf("method" to "type", "arguments" to mapOf("text" to "hello"))
            )
        )

        Thread.sleep(100)
        println()
    }

    /**
     * Example 4: Counting different types of events
     */
    private fun eventCounting() {
        println("--- Example 4: Event Counting ---")

        val eventCounts = ConcurrentHashMap<String, AtomicInteger>()

        // Register counters for multiple event types
        listOf(
            AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE,
            AgenticEvents.PerceptiveAgent.ON_WILL_ACT,
            AgenticEvents.PerceptiveAgent.ON_WILL_EXTRACT
        ).forEach { eventType ->
            EventBus.register(eventType) { payload ->
                eventCounts.computeIfAbsent(eventType) { AtomicInteger(0) }.incrementAndGet()
                println("📊 Event count for $eventType: ${eventCounts[eventType]?.get()}")
                payload
            }
        }

        // Simulate various events
        EventBus.emit(AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE, mapOf("options" to "test"))
        Thread.sleep(50)
        EventBus.emit(AgenticEvents.PerceptiveAgent.ON_WILL_ACT, mapOf("action" to "test"))
        Thread.sleep(50)
        EventBus.emit(AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE, mapOf("options" to "test2"))
        Thread.sleep(50)
        EventBus.emit(AgenticEvents.PerceptiveAgent.ON_WILL_EXTRACT, mapOf("options" to "test"))
        Thread.sleep(100)

        println("\n📈 Final event counts:")
        eventCounts.forEach { (eventType, count) ->
            println("  $eventType: ${count.get()}")
        }
        println()
    }

    /**
     * Clean up all registered handlers
     */
    private fun cleanup() {
        println("--- Cleanup ---")
        listOf(
            AgenticEvents.PerceptiveAgent.ON_WILL_ACT,
            AgenticEvents.PerceptiveAgent.ON_DID_ACT,
            AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE,
            AgenticEvents.PerceptiveAgent.ON_WILL_EXTRACT,
            AgenticEvents.ContextToAction.ON_WILL_GENERATE,
            AgenticEvents.ContextToAction.ON_DID_GENERATE
        ).forEach { eventType ->
            EventBus.unregister(eventType)
        }
        println("✅ All event handlers unregistered")
    }
}

/**
 * Example showing how to create a custom event listener for logging
 */
class EventLogger {
    private val logger = LoggerFactory.getLogger(EventLogger::class.java)

    fun startLogging() {
        // Log all agent events
        listOf(
            AgenticEvents.PerceptiveAgent.ON_WILL_RUN,
            AgenticEvents.PerceptiveAgent.ON_DID_RUN,
            AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE,
            AgenticEvents.PerceptiveAgent.ON_DID_OBSERVE,
            AgenticEvents.PerceptiveAgent.ON_WILL_ACT,
            AgenticEvents.PerceptiveAgent.ON_DID_ACT,
            AgenticEvents.PerceptiveAgent.ON_WILL_EXTRACT,
            AgenticEvents.PerceptiveAgent.ON_DID_EXTRACT,
            AgenticEvents.PerceptiveAgent.ON_WILL_SUMMARIZE,
            AgenticEvents.PerceptiveAgent.ON_DID_SUMMARIZE
        ).forEach { eventType ->
            EventBus.register(eventType) { payload ->
                logger.info(
                    "Event: {}, Payload: {}", eventType,
                    Pson.toJson(payload).take(200)
                )
                payload
            }
        }
    }

    fun stopLogging() {
        listOf(
            AgenticEvents.PerceptiveAgent.ON_WILL_RUN,
            AgenticEvents.PerceptiveAgent.ON_DID_RUN,
            AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE,
            AgenticEvents.PerceptiveAgent.ON_DID_OBSERVE,
            AgenticEvents.PerceptiveAgent.ON_WILL_ACT,
            AgenticEvents.PerceptiveAgent.ON_DID_ACT,
            AgenticEvents.PerceptiveAgent.ON_WILL_EXTRACT,
            AgenticEvents.PerceptiveAgent.ON_DID_EXTRACT,
            AgenticEvents.PerceptiveAgent.ON_WILL_SUMMARIZE,
            AgenticEvents.PerceptiveAgent.ON_DID_SUMMARIZE
        ).forEach { eventType ->
            EventBus.unregister(eventType)
        }
    }
}

/**
 * Example showing how to create a metrics collector using EventBus
 */
class MetricsCollector {
    private val operationCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val operationDurations = ConcurrentHashMap<String, MutableList<Long>>()
    private val operationStartTimes = ConcurrentHashMap<String, Long>()

    fun startCollecting() {
        // Track observations
        EventBus.register(AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val uuid = map["uuid"] as? String
            if (uuid != null) {
                operationStartTimes["observe-$uuid"] = Instant.now().toEpochMilli()
            }
            operationCounts.computeIfAbsent("observe") { AtomicInteger(0) }.incrementAndGet()
            payload
        }

        EventBus.register(AgenticEvents.PerceptiveAgent.ON_DID_OBSERVE) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val uuid = map["uuid"] as? String
            if (uuid != null) {
                val startTime = operationStartTimes["observe-$uuid"]
                if (startTime != null) {
                    val duration = Instant.now().toEpochMilli() - startTime
                    operationDurations.computeIfAbsent("observe") { mutableListOf() }.add(duration)
                }
            }
            payload
        }

        // Track actions
        EventBus.register(AgenticEvents.PerceptiveAgent.ON_WILL_ACT) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val uuid = map["uuid"] as? String
            if (uuid != null) {
                operationStartTimes["act-$uuid"] = Instant.now().toEpochMilli()
            }
            operationCounts.computeIfAbsent("act") { AtomicInteger(0) }.incrementAndGet()
            payload
        }

        EventBus.register(AgenticEvents.PerceptiveAgent.ON_DID_ACT) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val uuid = map["uuid"] as? String
            if (uuid != null) {
                val startTime = operationStartTimes["act-$uuid"]
                if (startTime != null) {
                    val duration = Instant.now().toEpochMilli() - startTime
                    operationDurations.computeIfAbsent("act") { mutableListOf() }.add(duration)
                }
            }
            payload
        }
    }

    fun printMetrics() {
        println("\n=== Collected Metrics ===")
        operationCounts.forEach { (operation, count) ->
            println("$operation: ${count.get()} operations")
            val durations = operationDurations[operation]
            if (durations != null && durations.isNotEmpty()) {
                val avg = durations.average()
                val min = durations.minOrNull() ?: 0
                val max = durations.maxOrNull() ?: 0
                println("  Duration: avg=${avg.toLong()}ms, min=${min}ms, max=${max}ms")
            }
        }
    }

    fun stopCollecting() {
        EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE)
        EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_DID_OBSERVE)
        EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_WILL_ACT)
        EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_DID_ACT)
    }
}
