package ai.platon.pulsar.agentic.model

import ai.platon.pulsar.agentic.ObserveOptions
import ai.platon.pulsar.agentic.agents.AgentConfig
import ai.platon.pulsar.agentic.inference.ExtractParams
import ai.platon.pulsar.agentic.inference.ObserveParams
import ai.platon.pulsar.common.serialize.json.Pson
import org.codehaus.jackson.annotate.JsonIgnore
import java.time.Instant
import java.util.UUID

/**
 * Captures the runtime context for a single agent execution step.
 *
 * An [ExecutionContext] is the glue object passed through observe/act/extract flows. It keeps the original
 * instruction, the current step/event identity, the current [AgentState], and the shared session-level artifacts
 * that later prompts and persistence rely on.
 *
 * Although this is a `data class`, it is intentionally stateful:
 * - A new instance is created for each step/event pair, but [sessionId] stays stable across a multistep run.
 * - [agentState] is updated in place as the agent observes, acts, and records results for the current step.
 * - [stateHistory] is shared session history, not a defensive copy, so later contexts see prior successful steps.
 * - [screenshotB64] may be attached after construction when a prompt needs the latest screenshot.
 *
 * Important usage notes:
 * - Prefer building contexts through `AgentStateManager`; it enforces step progression, state inheritance, and
 *   persistence side effects. Constructing this class directly is mainly appropriate for tests.
 * - Keep [instruction] stable throughout a multistep session. Advance the workflow with [step] and [event] instead
 *   of rewriting the goal mid-session.
 * - [uuid] is unique per context instance and is used as the request identifier for downstream extraction calls.
 *
 * @property step The logical step number. `0` is used for standalone or bootstrap contexts; positive values represent
 * the ordered steps of a multistep run.
 * @property instruction The user goal being executed. This should remain consistent across all contexts in one session.
 * @property screenshotB64 The latest screenshot snapshot, when one is attached for prompting or debugging.
 * @property event The lifecycle event handled by this context, such as `act`, `observe`, `extract`, or `summary`.
 * @property targetUrl The page URL the step is expected to operate on. This is often inherited from the previous state.
 * @property agentState The mutable state snapshot for the current step.
 * @property stateHistory The shared history of successful states accumulated during the current session.
 * @property config Agent configuration flags that influence prompt generation and persistence behavior.
 * @property sessionId Stable session identifier reused by all related contexts in the same run.
 * @property stepStartTime Timestamp marking when this step context was created.
 */
data class ExecutionContext constructor(
    val step: Int,

    val instruction: String = "",
    val screenshotB64: String? = null,

    val event: String,
    val targetUrl: String? = null,

    val agentState: AgentState,
    val stepStartTime: Instant = Instant.now(),
    val stateHistory: AgentHistory,
    val stateHistoryPath: String? = null,

    val config: AgentConfig,
    val sessionId: String,
    val uuid: String = UUID.randomUUID().toString(),
) {
    /**
     * Short session identifier for compact logs and human-readable traces.
     */
    @get:JsonIgnore
    val sid get() = sessionId.take(8)

    /**
     * Convenience access to the previous step's state, if this context was derived from an earlier one.
     */
    @get:JsonIgnore
    val prevAgentState: AgentState? get() = agentState.prevState

    /**
     * Serializes this context for persistence or debugging.
     */
    fun toJson(): String {
        return Pson.toJson(this)
    }

    override fun toString(): String {
        return "step: $step, event: $event, targetUrl: $targetUrl, sessionId: $sessionId, instruction: $instruction"
    }
}

/**
 * Builds observe parameters that preserve the current context and merge caller-provided observe options with
 * agent-level configuration.
 */
fun ExecutionContext.createObserveParams(
    options: ObserveOptions,
    fromAct: Boolean,
    multistep: Boolean
): ObserveParams {
    return ObserveParams(
        context = this,
        returnAction = options.returnAction ?: false,
        logInferenceToFile = config.logInferenceToFile,
        fromAct = fromAct,
        multistep = multistep
    )
}

/**
 * Convenience factory for the common "observe in order to act" flow, where the model is expected to return an
 * actionable result and logging should honor the current agent configuration.
 */
fun ExecutionContext.createObserveActParams(multistep: Boolean): ObserveParams {
    return ObserveParams(
        context = this,
        fromAct = true,
        returnAction = true,
        multistep = multistep,
        logInferenceToFile = config.logInferenceToFile,
    )
}

/**
 * Builds extraction parameters anchored to this context so extraction uses the same instruction, state snapshot,
 * and a unique request identifier tied to this step.
 */
fun ExecutionContext.createExtractParams(schema: ExtractionSchema): ExtractParams {
    return ExtractParams(
        instruction = instruction,
        agentState = agentState,
        schema = schema,
        requestId = uuid,
    )
}
