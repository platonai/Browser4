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
 * - multiAct: When true, each chained step creates a new step/context; when false, it reuses the last context.
 * - Timeouts are milliseconds. `domSettleTimeoutMs` waits for DOM to become stable; `timeoutMs` bounds the whole action.
 * - `resolve`: Internal flag indicating the action is triggered from the resolve loop (excluded from JSON).
 *
 * Notes / 说明:
 * - This data class is immutable. Prefer `copy(...)` to adjust values.
 * - `modelName`, `variables`, and `iframes` are reserved for future model/runtime options.
 * - `additionalContext` is deprecated and will be removed in future versions.
 *
 * @property action The user's action command or tool expression. 必填，用户动作/工具表达式。
 * @property multiAct Whether each chained step forms a new context (true) or reuses the last one (false). 是否每步新建上下文。
 * @property modelName Reserved: LLM/model name to use (e.g., "chatgpt-5.1"). 保留字段：模型名。
 * @property variables Reserved: Extra variables for prompt/tool. 保留字段：额外变量。
 * @property domSettleTimeoutMs Optional timeout in ms to wait for DOM settling. DOM 稳定等待超时（毫秒）。
 * @property timeoutMs Optional overall timeout in ms for this action. 动作总超时（毫秒）。
 * @property iframes Reserved: Whether to include iframes when acting. 保留字段：是否包含 iframe。
 * @property fromRunLoop Internal: true if invoked from resolve loop; excluded from JSON. 内部字段：是否由 resolve 驱动。
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
    val fromRunLoop: Boolean = true
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
    val removeBoilerpipe: Boolean = true,
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

/**
 * Structured extraction result.
 *
 * @property success Whether the extraction pipeline completed without error.
 * @property message Human-readable status message.
 * @property data The clean extraction payload (schema fields only, as returned
 * by the model) — task bookkeeping (token counts, timing, evaluation
 * metadata) is never merged into it.  When serialized through the tool layer,
 * the payload is transported in the envelope's `description` and the
 * [completed] flag is exposed at the envelope level.
 * @property completed Whether the extraction task is complete.  `true` whenever
 * usable content is present in [data] (a successful extraction must never
 * report `false`), otherwise falls back to the evaluator model's judgment.
 */
data class ExtractResult(
    val success: Boolean,
    val message: String = "",
    val data: JsonNode,
    val completed: Boolean = true
) {
    override fun toString(): String {
        return if (success) data.toString() else "Extract failed: $message"
    }
}

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
     * The execution session id of the most recent [run] call, or null when the agent
     * has not run yet. Each run starts a new execution session; all [AgentState]s
     * collected during that run carry this id so callers can build task-scoped views
     * of the accumulated [stateHistory].
     */
    val lastRunSessionId: String? get() = null

    /**
     * High-level problem resolution entry. Implementations drive the task to completion — the
     * only engine is the CLI tool loop — and may apply retry, timeout and step-budget policies.
     *
     * This is an internal implementation detail of `command.run` and is deliberately NOT exposed
     * as an MCP tool. External callers must use `command.run` (`browser4-cli agent run`, MCP tool
     * `command_run`), which owns task lifecycle, status, wait/resume, workspace isolation and the
     * access policy.
     *
     * @param task The user goal or instruction to fulfill.
     * @return The history of THIS run: a detached snapshot containing only the states
     * collected for this task, scoped by the run's execution session.
     */
    suspend fun run(task: String): AgentHistory

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
