package ai.platon.pulsar.agentic.inference.action

import ai.platon.browser4.driver.chrome.dom.model.BrowserUseState
import ai.platon.pulsar.agentic.inference.AgentMessageList
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.model.DetailedActResult

/**
 * A terminal response indicating whether the overall task is complete.
 *
 * This structure is parsed in [TextToAction.modelResponseToActionDescription] when the model output contains
 * a `taskComplete` field.
 */
data class ModelObserveResponseComplete(
    /**
     * Whether the agent believes the whole task is complete.
     */
    val taskComplete: Boolean = false,

    /**
     * Whether the execution is considered successful when [taskComplete] is true.
     */
    val success: Boolean = false,

    /**
     * A short error cause when [success] is false.
     */
    val errorCause: String? = null,

    /**
     * High-level summary for the user.
     */
    val summary: String? = null,

    /**
     * Bullet-point findings extracted from the page/process.
     */
    val keyFindings: List<String>? = null,

    /**
     * Suggested next steps (follow-up queries, alternate actions, etc.).
     */
    val nextSuggestions: List<String>? = null,
)

const val OBSERVE_RESPONSE_COMPLETE_SCHEMA = """
{"taskComplete":bool,"success":bool,"errorCause":string?,"summary":string,"keyFindings":[string],"nextSuggestions":[string]}
"""

/**
 * A non-terminal observe response that lists interactive/target elements.
 */
data class ModelObserveResponseElements(
    /**
     * Elements returned by the model.
     *
     * Null means "the model didn't return this field"; empty list means "returned but no elements".
     */
    val elements: List<ModelObserveResponseElement>? = null
)

/**
 * An element + tool-call suggestion inferred from the current page.
 *
 * This is the primary JSON schema produced by the observe/action-generation prompts.
 *
 * Conversion rules (see [TextToAction.toObserveElement]):
 * - [ref] may be wrapped with brackets, and will be normalized using `removeSurrounding("[", "]")`.
 * - [arguments] is expected to be a list of `{name: ..., value: ...}` maps; names become argument keys.
 *
 * Serialization notes:
 * - [arguments] uses `Any` to allow booleans/numbers/strings/objects. Keep values JSON-serializable.
 *
 * See also: [OBSERVE_RESPONSE_ELEMENT_SCHEMA]
 */
data class ModelObserveResponseElement(
    /**
     * Tool domain (namespace) for the suggested action.
     */
    val domain: String? = null,

    /**
     * Tool method (function name) for the suggested action.
     */
    val method: String? = null,

    /**
     * Tool arguments represented as a list of maps.
     *
     * Typical shape:
     * `[{"name": "selector", "value": "..."}, {"name": "text", "value": "..."}]`
     */
    val arguments: List<Map<String, Any>?>? = null,

    /**
     * Human-friendly description of the element and/or the intended action.
     */
    val description: String? = null,

    /**
     * Web page node reference for DOM manipulation.
     * Specified explicitly for easy access and parsing, even if it's also present in [arguments].
     */
    val ref: String? = null,

    /**
     * Long-term memory to persist.
     */
    val memory: String? = null,

    /**
     * Model reasoning; optional debug information.
     */
    val thinking: String? = null,

    /**
     * Screenshot summary (if an image was provided).
     */
    val screenshotContentSummary: String? = null,

    /**
     * Current page summary as perceived by the model.
     */
    val currentPageContentSummary: String? = null,

    /**
     * Evaluation of the previous goal.
     */
    val evaluationPreviousGoal: String? = null,

    /**
     * Suggested next goal.
     */
    val nextGoal: String? = null,
)

const val OBSERVE_RESPONSE_ELEMENT_SCHEMA = """
{
  "elements": [
    {
      "domain": "Tool domain, such as `tab`",
      "method": "Method name, such as `click`",
      "arguments": [
        {
          "name": "Parameter name, such as `selector`",
          "value": "Parameter value, such as `e123`"
        }
      ],
      "description": "Description of the current tool selection",
      "screenshotContentSummary": "Summary of the current screenshot content if provided",
      "currentPageContentSummary": "Summary of the current web page text content, based on the accessibility tree or web content extraction results",
      "memory": "1–3 specific sentences describing this step and the overall progress. This should include information helpful for future progress tracking, such as the number of pages visited or items found.",
      "thinking": "A structured <think>-style reasoning block.",
      "evaluationPreviousGoal": "A concise one-sentence analysis of the previous action, clearly stating success, failure, or uncertainty.",
      "nextGoal": "A clear one-sentence statement of the next direct goal and action to take."
    }
  ]
}
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
