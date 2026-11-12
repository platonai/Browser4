package ai.platon.pulsar.agentic.inference.action

import ai.platon.pulsar.agentic.inference.AgentMessageList
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.model.DetailedActResult
import ai.platon.pulsar.api.model.BrowserUseState

const val OBSERVE_RESPONSE_COMPLETE_SCHEMA = """
{"taskComplete":bool,"success":bool,"errorCause":string?,"summary":string,"keyFindings":[string],"nextSuggestions":[string],"gates":[{"name":string,"ran":bool,"exitCode":int?,"ok":bool?,"detail":string?}],"filesChanged":[string],"deletedFiles":[string],"problems":[string]}
"""

/**
 * A runtime action node in the agent's internal execution graph.
 *
 * Think of this as "the full context for one step": user instruction + prompt/messages + agent/browser state
 * + parsed action + execution result.
 *
 * Graph fields:
 * - [prevAction]/[nextAction]/[parent]/[children] link nodes together for tracing.
 * - Be careful when serializing this type: it can contain cycles and very large object graphs.
 */
data class AgentAction(
    /**
     * Monotonic step number in the execution sequence.
     */
    val step: Int,

    /**
     * Original instruction or sub-goal for this step.
     */
    val userInstruction: String,

    /**
     * Prompt/response messages exchanged with the model.
     */
    val messages: AgentMessageList,

    /**
     * Mutable agent state at the time of this step.
     */
    val agentState: AgentState,

    /**
     * Browser-use state snapshot (DOM, viewport, etc.) captured for the step.
     */
    val browserUseState: BrowserUseState? = null,

    /**
     * Parsed action description computed from model output.
     */
    val actionDescription: ActionDescription? = null,

    /**
     * Detailed execution result after acting (timings, errors, side effects, etc.).
     */
    val detailedActResult: DetailedActResult? = null,

    /**
     * Previous action in the linear sequence, if any.
     */
    val prevAction: AgentAction? = null,

    /**
     * Next action in the linear sequence, if any.
     */
    val nextAction: AgentAction? = null,

    /**
     * Parent action in a hierarchical plan/tree.
     */
    val parent: AgentAction? = null,

    /**
     * Child actions created from this action (e.g. decomposition).
     */
    val children: List<AgentAction> = emptyList(),
)
