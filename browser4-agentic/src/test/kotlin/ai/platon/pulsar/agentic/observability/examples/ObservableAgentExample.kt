package ai.platon.pulsar.agentic.observability.examples

import ai.platon.pulsar.agentic.observability.AgentMetrics
import ai.platon.pulsar.agentic.observability.InferenceMetrics
import ai.platon.pulsar.agentic.observability.ToolMetrics
import ai.platon.pulsar.agentic.observability.TracingUtils
import io.opentelemetry.api.trace.SpanKind
import java.util.concurrent.atomic.AtomicInteger

/**
 * Example demonstrating how to integrate observability into agent code.
 */
class ObservableAgentExample {
    
    private val stepCounter = AtomicInteger(0)
    
    fun executeAgent(taskDescription: String) {
        AgentMetrics.recordAgentStart("example-agent")
        
        TracingUtils.withSpan(
            spanName = "agent.execute",
            attributes = mapOf(
                "agent.type" to "example-agent",
                "task.description" to taskDescription
            ),
            spanKind = SpanKind.SERVER
        ) { agentSpan ->
            try {
                val result = executeSteps()
                AgentMetrics.recordAgentCompleted("example-agent", totalSteps = stepCounter.get())
                result
            } catch (e: Exception) {
                TracingUtils.recordError(agentSpan, e)
                AgentMetrics.recordAgentFailed("example-agent", errorType = e.javaClass.simpleName)
                throw e
            }
        }
    }
    
    private fun executeSteps(): String {
        val currentStep = stepCounter.incrementAndGet()
        AgentMetrics.recordStepCompleted("example-agent", currentStep)
        return "Success"
    }
}
