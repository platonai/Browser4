package ai.platon.pulsar.agentic

import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.common.ai.llm.MCP
import ai.platon.pulsar.common.Strings
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import java.util.*

/**
 * Options describing a single user action to be executed by the agent.
 *
 * Contract / 行为约定:
 * - action: Required. A natural-language instruction or a tool expression to execute.
 * - multiAct: When true, each act() call creates a new step/context in a chain; when false, it reuses the last context.
 * - Timeouts are milliseconds. `domSettleTimeoutMs` waits for DOM to become stable; `timeoutMs` bounds the whole action.
 * - `resolve`: Internal flag indicating the action is triggered from the resolve loop (excluded from JSON).
 *
 * Notes / 说明:
 * - This data class is immutable. Prefer `copy(...)` to adjust values.
 * - `modelName`, `variables`, and `iframes` are reserved for future model/runtime options.
 * - `additionalContext` is deprecated and will be removed in future versions.
 *
 * @property action The user's action command or tool expression. 必填，用户动作/工具表达式。
 * @property multiAct Whether each act forms a new chained context (true) or reuses the last one (false). 是否每次 act 新建上下文。
 * @property modelName Reserved: LLM/model name to use (e.g., "chatgpt-5.1"). 保留字段：模型名。
 * @property variables Reserved: Extra variables for prompt/tool. 保留字段：额外变量。
 * @property domSettleTimeoutMs Optional timeout in ms to wait for DOM settling. DOM 稳定等待超时（毫秒）。
 * @property timeoutMs Optional overall timeout in ms for this action. 动作总超时（毫秒）。
 * @property iframes Reserved: Whether to include iframes when observing/acting. 保留字段：是否包含 iframe。
 * @property fromResolve Internal: true if invoked from resolve loop; excluded from JSON. 内部字段：是否由 resolve 驱动。
 */
data class ActionOptions(
    val action: String,
    val multiAct: Boolean = false,
    val modelName: String? = null,
    val variables: Map<String, String>? = null,
    val domSettleTimeoutMs: Long? = null,
    val timeoutMs: Long? = null,
    val iframes: Boolean? = null,
    @get:JsonIgnore
    val fromResolve: Boolean = true
)

/**
 * Result returned after executing an action/tool call.
 *
 * Semantics / 语义:
 * - `success`: Execution status of the attempted action/tool call.
 * - `message`: Human-readable status or error description.
 * - `action`: The original user command that led to this result (if available).
 * - `result`: Structured tool-call output; may be null if the call didn't execute or failed early.
 * - `detail`: Internal rich details for diagnostics/tracing; excluded from JSON.
 *
 * Derived fields / 派生字段:
 * - `isComplete`: Whether the task has been determined to be complete by the agent.
 * - `expression`: The tool expression with weakly typed parameters, if any.
 * - `tcEvalValue`: The evaluation value returned by the tool, if any.
 *
 * The `toString()` provides a compact, log-friendly summary.
 *
 * @property message Additional status or error message. 状态/错误信息。
 * @property action The user action command that produced this result. 触发该结果的用户动作。
 * @property result The structured tool-call result payload. 工具调用结果。
 * @property detail Internal diagnostics detail; not serialized. 内部诊断信息（不序列化）。
 */
data class ActResult constructor(
    val message: String = "",
    val action: String? = null,
    val result: ToolCallResult? = null,
    val exception: Exception? = null,
    @get:JsonIgnore
    val detail: DetailedActResult? = null
) {
    /** Check if the overall task is complete according to the agent. */
    val isComplete: Boolean get() = detail?.actionDescription?.isReallyComplete == true

    val isSuccess: Boolean get() = exception == null && detail?.exception == null && result?.evaluate?.exception == null

    /** Expression with weak parameter types (if provided by the model/tool). */
    @get:JsonIgnore
    val weakTypeExpression get() = result?.actionDescription?.weakTypeExpression

    /** Evaluation value returned by the tool call, if available. */
    @get:JsonIgnore
    val tcEvalValue get() = result?.evaluate?.value

    override fun toString(): String {
        val eval = Strings.compactInline(tcEvalValue?.toString(), 50)
        return "[$action] expr: $weakTypeExpression eval: $eval message: $message"
    }
}

data class ExtractOptions(
    val instruction: String,
    val schema: ExtractionSchema,
    // reserved
    val modelName: String? = null,
    // reserved
    val modelClientOptions: Map<String, Any>? = null,
    val domSettleTimeoutMs: Long? = null,
    // reserved
    val selector: String? = null,
    // reserved
    val iframes: Boolean? = null,
    // reserved
    val frameId: String? = null
)

data class ExtractResult(
    val success: Boolean,
    val message: String = "",
    val data: JsonNode
) {
    override fun toString(): String {
        return "success: $success message: $message data: " + Strings.compactInline(data.toString(), 50)
    }
}

data class ObserveOptions(
    // the user's instruction
    val instruction: String? = null,
    val modelName: String? = null,
    val modelClientOptions: Map<String, Any>? = null,
    val domSettleTimeoutMs: Long? = null,
    // if true, the LLM should return a tool call for the next action
    val returnAction: Boolean? = null,

    // highlight interactive elements or not
    val drawOverlay: Boolean = true,
    // reserved
    val iframes: Boolean? = null,
    // reserved
    val frameId: String? = null,

    // from `resolve` loop or not
    val fromResolve: Boolean = false
)

data class ObserveResult constructor(
    // the domain of the tool call, `browser`, `tab`, `fs`, `agent`, etc
    val domain: String? = null,
    // the tool call method, `click`, `type`, `scrollBy`, etc
    val method: String? = null,
    // the tool call arguments
    val arguments: Map<String, Any?>? = null,
    // the tool call description
    val description: String? = null,

    val backendNodeId: Int? = null,

    val screenshotContentSummary: String? = null,
    val currentPageContentSummary: String? = null,
    val evaluationPreviousGoal: String? = null,
    val nextGoal: String? = null,
    val thinking: String? = null,

    // AI: completion summary
    var summary: String? = null,
    // AI: completion key findings
    var keyFindings: String? = null,
    // AI: completion next suggestions
    var nextSuggestions: List<String>? = null,

    val observeElement: ObserveElement? = null,

    // internal
    val actionDescription: ActionDescription? = null,

    // internal, always update before/after an action
    val agentState: AgentState
)

interface PerceptiveAgent : AutoCloseable {
    val uuid: UUID

    val session: AgenticSession

    /**
     * The agent state history exists to give the AI agent a concise, sequential memory of what has been done.
     * This helps the model select the next step and summarize outcomes.
     * For this to work well, the history should reflect only the agent’s actual, single-step actions
     * with clear success/failure signals and the observation context that impacted/was impacted by the action.
     * */
    val stateHistory: AgentHistory

    /**
     * The process trace.
     * */
    val processTrace: List<ProcessTrace>

    /**
     * High-level problem resolution entry. Implementations should construct an [ActionOptions]
     * from the raw problem string and delegate to [run] with the options.
     *
     * @param task The user goal or instruction to fulfill.
     * @return The final action result produced by the agent.
     */
    @MCP
    suspend fun run(task: String): AgentHistory

    /**
     * Run an autonomous loop (observe -> act -> ...) attempting to fulfill the user goal described
     * in the provided [ActionOptions]. Implementations may apply retry and timeout strategies; they
     * should record structured traces while keeping [stateHistory] focused on executed tool actions only.
     *
     * @param action The action options describing the user goal and context.
     * @return The final action result for the resolution attempt.
     */
    suspend fun run(action: ActionOptions): AgentHistory

    /**
     * Convenience overload to observe by instruction string. Implementations typically create
     * an [ObserveOptions] from the instruction and delegate to [observe].
     *
     * @param instruction The observation instruction from the user.
     * @return Zero or more observation results describing candidate elements and potential actions.
     */
    @MCP
    suspend fun observe(instruction: String): List<ObserveResult>

    /**
     * Observe the page given an instruction and options, returning zero or more [ObserveResult]
     * objects describing candidate elements and potential actions. When `returnAction=true`,
     * implementations may include an actionable method/arguments in the result.
     *
     * @param options Observation options including the instruction and flags.
     * @return A list of observation results; empty if nothing actionable is found.
     */
    suspend fun observe(options: ObserveOptions): List<ObserveResult>

    /**
     * Summarize text captured from the current page or a specific element and return a natural-language synopsis.
     *
     * Passing an [instruction] tailors the tone or focus of the summary; when null, the agent relies on a
     * default summarization prompt. Supplying a [selector] scopes the extraction to a particular DOM element;
     * leaving it null lets the implementation infer a sensible region (for example, the main article body).
     *
     * @param instruction Optional guidance for how the summary should be written.
     * @param selector Optional locator limiting which element's text is summarized.
     * @return The generated summary text.
     */
    @MCP
    suspend fun summarize(instruction: String? = null, selector: String? = null): String

    /**
     * Convenience wrapper building [ActionOptions] from a raw action string and delegating to [act].
     *
     * @param action The action to execute.
     * @return The result of executing the action.
     */
    @MCP
    suspend fun act(action: String): ActResult

    /**
     * Execute a single observe->act cycle for the supplied [ActionOptions]. Implementations may
     * apply a timeout to prevent indefinite hangs. Models may produce multiple candidate tool calls;
     * only one successful execution should be recorded in [stateHistory].
     *
     * @param action The action options to execute.
     * @return The action result for the attempted execution.
     */
    suspend fun act(action: ActionOptions): ActResult

    /**
     * Execute a tool call derived from a prior observation result. Implementations should perform
     * any necessary validation and update [stateHistory] on success or failure.
     *
     * @param observe The observation result containing the candidate action.
     * @return The result of executing the derived tool call.
     */
    suspend fun act(observe: ObserveResult): ActResult

    /**
     * Convenience overload for structured extraction. When only an instruction string is provided,
     * the implementation should use [ExtractionSchema.DEFAULT].
     *
     * @param instruction The extraction instruction from the user.
     * @return The extraction result produced by the model.
     */
    @MCP
    suspend fun extract(instruction: String): ExtractResult

    /**
     * Convenience overload for structured extraction that constrains the result with a JSON schema.
     *
     * @param instruction The extraction instruction from the user.
     * @param schema The JSON schema used to constrain the returned data structure.
     * @return The extraction result produced by the model.
     */
    @MCP
    suspend fun extract(instruction: String, schema: ExtractionSchema): ExtractResult

    /**
     * Structured extraction entry point. Implementations should build a rich prompt with the DOM
     * snapshot and optional JSON schema to produce a structured [JsonNode] payload.
     *
     * @param options Extraction options including instruction and schema.
     * @return The extraction result.
     */
    suspend fun extract(options: ExtractOptions): ExtractResult

    /**
     * Clear history so that new tasks remain unaffected by previous ones.
     * */
    suspend fun clearHistory()
}
