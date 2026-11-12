package ai.platon.pulsar.agentic.observability.examples

import ai.platon.pulsar.agentic.ActResult
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
        EventBus.register(AgenticEvents.PerceptiveAgent.ON_WILL_RUN) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val uuid = map["uuid"] as? String
            if (uuid != null) {
                executionTimes[uuid] = Instant.now().toEpochMilli()
                println("⏱️  Starting agent run for uuid: $uuid")
            }
            payload
        }

        // Calculate duration
        EventBus.register(AgenticEvents.PerceptiveAgent.ON_DID_RUN) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val uuid = map["uuid"] as? String
            if (uuid != null) {
                val startTime = executionTimes[uuid]
                if (startTime != null) {
                    val duration = Instant.now().toEpochMilli() - startTime
                    println("⏱️  Agent run completed in ${duration}ms")
                }
            }
            payload
        }

        // Simulate run events
        val testUuid = "run-123"
        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_WILL_RUN, mapOf(
                "action" to mapOf("action" to "open the page"),
                "uuid" to testUuid
            )
        )

        Thread.sleep(150) // Simulate run time
        println()
    }

    /**
     * Example 3: Validating run results
     */
    private fun actionValidation() {
        println("--- Example 3: Validating run results ---")

        EventBus.register(AgenticEvents.PerceptiveAgent.ON_DID_RUN) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val result = map["result"] as? ActResult

            if (result != null) {
                println("🔍 Validating run result...")
                val isValid = result.message.isNotBlank()
                println("✅ Result validated: isSuccess=${result.isSuccess}, message=${result.message}")

                // You can enrich the payload
                val enriched = map.toMutableMap()
                enriched["validated"] = isValid
                enriched["validatedAt"] = Instant.now().toString()
                return@register enriched
            }

            payload
        }

        // Simulate a completed run
        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_DID_RUN, mapOf(
                "action" to mapOf("action" to "type the query"),
                "uuid" to "validation-test",
                "result" to ActResult(message = "Typed the query", action = "type"),
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
            AgenticEvents.PerceptiveAgent.ON_WILL_RUN,
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
        EventBus.emit(AgenticEvents.PerceptiveAgent.ON_WILL_RUN, mapOf("action" to "test"))
        Thread.sleep(50)
        EventBus.emit(AgenticEvents.PerceptiveAgent.ON_WILL_ACT, mapOf("action" to "test"))
        Thread.sleep(50)
        EventBus.emit(AgenticEvents.PerceptiveAgent.ON_WILL_RUN, mapOf("action" to "test2"))
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
            AgenticEvents.PerceptiveAgent.ON_WILL_RUN,
            AgenticEvents.PerceptiveAgent.ON_WILL_EXTRACT,
            AgenticEvents.PerceptiveAgent.ON_DID_RUN
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
        // Track run start/end
        EventBus.register(AgenticEvents.PerceptiveAgent.ON_WILL_RUN) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val uuid = map["uuid"] as? String
            if (uuid != null) {
                operationStartTimes["run-$uuid"] = Instant.now().toEpochMilli()
            }
            operationCounts.computeIfAbsent("run") { AtomicInteger(0) }.incrementAndGet()
            payload
        }

        EventBus.register(AgenticEvents.PerceptiveAgent.ON_DID_RUN) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val uuid = map["uuid"] as? String
            if (uuid != null) {
                val startTime = operationStartTimes["run-$uuid"]
                if (startTime != null) {
                    val duration = Instant.now().toEpochMilli() - startTime
                    operationDurations.computeIfAbsent("run") { mutableListOf() }.add(duration)
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
        EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_WILL_RUN)
        EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_DID_RUN)
        EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_WILL_ACT)
        EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_DID_ACT)
    }
}
