package ai.platon.pulsar.agentic.inference.detail

import ai.platon.pulsar.agentic.ActResult
import ai.platon.pulsar.agentic.ObserveOptions
import ai.platon.pulsar.agentic.agents.AgentConfig
import ai.platon.pulsar.agentic.inference.ExtractParams
import ai.platon.pulsar.agentic.inference.ObserveParams
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.common.Strings
import java.time.Instant
import java.util.*

/**
 * A helper class to help ActResult keeping small.
 * */
object ActResultHelper {

    fun toString(actResult: ActResult): String {
        val eval = Strings.compactInline(actResult.tcEvalValue?.toString(), 50)
        return "[${actResult.action}] expr: ${actResult.expression} eval: $eval message: ${actResult.message}"
    }

    fun failed(message: String, action: String? = null) = ActResult(false, message, action)

    fun failed(message: String, detail: DetailedActResult) = ActResult(
        false,
        message,
        detail = detail,
    )

    fun complete(actionDescription: ActionDescription): ActResult {
        val detailedActResult = DetailedActResult(actionDescription, null, true, actionDescription.summary)
        // val toolCall = ToolCall("agent", "done")
        return ActResult(
            true,
            "completed",
            actionDescription.instruction,
            null,
            detailedActResult
        )
    }
}

/**
 * Enhanced error classification for better retry strategies
 */
sealed class PerceptiveAgentError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class TransientError(message: String, cause: Throwable? = null) : PerceptiveAgentError(message, cause)
    open class PermanentError(message: String, cause: Throwable? = null) : PerceptiveAgentError(message, cause)
    class TimeoutError(message: String, cause: Throwable? = null) : PerceptiveAgentError(message, cause)
    class ResourceExhaustedError(message: String, cause: Throwable? = null) : PerceptiveAgentError(message, cause)
    class ValidationError(message: String, cause: Throwable? = null) : PerceptiveAgentError(message, cause)
}

/**
 * Performance metrics for monitoring and optimization.
 * Thread-safe implementation using atomic fields for safe concurrent updates.
 * 
 * Note: Individual fields are updated atomically, but if you need to read
 * multiple fields consistently, external synchronization is required.
 */
data class PerformanceMetrics(
    @Volatile var totalSteps: Int = 0,
    @Volatile var successfulActions: Int = 0,
    @Volatile var failedActions: Int = 0,
    // Note: These fields are immutable (val) and should be replaced with new instances
    // if updates are needed, rather than mutating in place
    val averageActionTimeMs: Double = 0.0,
    val totalExecutionTimeMs: Long = 0,
    val memoryUsageMB: Double = 0.0,
    val retryCount: Int = 0,
    val consecutiveFailures: Int = 0
) {
    /**
     * Creates a new PerformanceMetrics with updated values.
     * Use this method to safely update metrics without race conditions.
     */
    fun withUpdates(
        totalSteps: Int = this.totalSteps,
        successfulActions: Int = this.successfulActions,
        failedActions: Int = this.failedActions,
        averageActionTimeMs: Double = this.averageActionTimeMs,
        totalExecutionTimeMs: Long = this.totalExecutionTimeMs,
        memoryUsageMB: Double = this.memoryUsageMB,
        retryCount: Int = this.retryCount,
        consecutiveFailures: Int = this.consecutiveFailures
    ): PerformanceMetrics = PerformanceMetrics(
        totalSteps, successfulActions, failedActions,
        averageActionTimeMs, totalExecutionTimeMs, memoryUsageMB,
        retryCount, consecutiveFailures
    )
}

/**
 * Execution context for a single agent step.
 * 
 * Represents the execution state for one step in the agent's autonomous loop.
 * Each context is created when entering a new step and contains:
 * - Current step number and instruction
 * - Reference to the current AgentState (mutable snapshot of browser state)
 * - Reference to shared AgentHistory (accumulated history across all steps)
 * - Configuration and session identifiers
 * 
 * **Lifecycle:**
 * 1. Created via `AgentStateManager.buildExecutionContext()` for each step
 * 2. Set as active via `setActiveContext()`, which appends to contexts list
 * 3. Used during step execution (observe, act, update state)
 * 4. Trimmed from contexts list when exceeding max size (100 contexts)
 * 
 * **Relationship to AgentStateManager:**
 * - `_baseContext`: First context created (step 0 or 1), never changed
 * - `_activeContext`: Points to this context when it's the current step
 * - `contexts`: All contexts created in session, includes this context
 * 
 * @property step Current step number in the execution sequence
 * @property instruction User's instruction for this context
 * @property screenshotB64 Base64-encoded screenshot (captured every N steps)
 * @property event Event name for this step (e.g., "observe", "act-1", "summary")
 * @property targetUrl Target URL for this step
 * @property agentState Current agent state (mutable, updated during step)
 * @property stateHistory Shared history of all executed states
 * @property config Agent configuration
 * @property sessionId Session identifier (same across all contexts in session)
 * @property stepStartTime When this step started
 * @property additionalContext Additional context data
 */
data class ExecutionContext constructor(
    var step: Int,

    var instruction: String = "",
    var screenshotB64: String? = null,

    var event: String,
    var targetUrl: String? = null,

    val agentState: AgentState,
    val stateHistory: AgentHistory,

    val config: AgentConfig,

    val sessionId: String,
    val stepStartTime: Instant = Instant.now(),
    val additionalContext: Map<String, Any> = emptyMap()
) {
    val sid get() = sessionId.take(8)

    val uuid = UUID.randomUUID().toString()

    val prevAgentState: AgentState? get() = agentState.prevState

    fun createObserveParams(
        options: ObserveOptions,
        fromAct: Boolean,
        resolve: Boolean
    ): ObserveParams {
        return ObserveParams(
            context = this,
            returnAction = options.returnAction ?: false,
            logInferenceToFile = config.logInferenceToFile,
            fromAct = fromAct,
            resolve = resolve
        )
    }

    fun createObserveActParams(resolve: Boolean): ObserveParams {
        return ObserveParams(
            context = this,
            fromAct = true,
            returnAction = true,
            resolve = resolve,
            logInferenceToFile = config.logInferenceToFile,
        )
    }

    fun createExtractParams(schema: ExtractionSchema): ExtractParams {
        return ExtractParams(
            instruction = instruction,
            agentState = agentState,
            schema = schema,
            requestId = uuid,
            logInferenceToFile = config.logInferenceToFile,
        )
    }
}
