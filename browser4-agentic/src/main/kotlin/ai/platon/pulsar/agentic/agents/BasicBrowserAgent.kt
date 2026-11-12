package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.*
import ai.platon.pulsar.agentic.common.AgentPaths
import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.agentic.inference.AgentStateManager
import ai.platon.pulsar.agentic.inference.InferenceEngine
import ai.platon.pulsar.agentic.inference.PromptBuilder
import ai.platon.pulsar.agentic.inference.action.ContextToAction
import ai.platon.pulsar.agentic.inference.detail.PageStateTracker
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.coding.CodingWorkspace
import ai.platon.pulsar.common.event.EventBus
import ai.platon.pulsar.common.getLogger
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

abstract class BasicBrowserAgent constructor(
    override val session: AgenticSession,
    val config: AgentConfig
) : PerceptiveAgent {
    private val logger = getLogger(BasicBrowserAgent::class)
    private val _startTime: Instant = Instant.now()
    private val _uuid: UUID = UUID.randomUUID()
    private val _baseDir: Path = AgentPaths.resolveTimedDirectory(_startTime).resolve(_uuid.toString())
    private val _logDir = getAgentLogDir()

    internal val cta by lazy { ContextToAction(session.sessionConfig, agentToolManager) }
    protected val inference by lazy { InferenceEngine(this) }
    protected val promptBuilder = PromptBuilder()

    private val lazyAgentToolManager by lazy {
        toolManagerInitialized.set(true)
        AgentToolManager(_baseDir, this, initialWorkspaceRoot = workspaceRoot).also {
            // Inner (native tool-calling loop) executions don't produce outer
            // AgentStates — record them so the finish-report guard can see them.
            it.toolExecutionRecorder = ::recordInnerToolExecution
        }
    }

    /**
     * The directory this agent's tools write into.
     *
     * Workspace isolation: CLI-engine (browser) tasks get a per-agent scratch
     * workspace so helper files never pollute the repository root; coding tasks
     * keep [CodingWorkspace.workspaceRoot]. Exposed so a finished task can report
     * where its deliverables landed (see `AgentTaskStatus.workspace`).
     */
    val workspaceRoot: Path
        get() = (this as? RobustBrowserAgent)?.agentWorkspaceDir
            ?: CodingWorkspace.workspaceRoot

    private val toolManagerInitialized = AtomicBoolean(false)

    /** The [AgentToolManager] used by this agent for tool discovery and execution. */
    val agentToolManager: AgentToolManager get() = lazyAgentToolManager
    protected val fs get() = agentToolManager.fs

    /** True once the tool manager was actually created (avoids costly lazy init on close). */
    internal val isAgentToolManagerInitialized: Boolean get() = toolManagerInitialized.get()

    /**
     * Tools executed inside the native tool-calling loop (no outer AgentState
     * is created for those) — tracked by domain.method, resets per run. Used by
     * the finish-report guard (count) and the gate cross-check (names).
     */
    private val innerToolExecutions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val innerToolExecutionCount: Int get() = innerToolExecutions.size
    val innerToolExecutionNames: Set<String> get() = innerToolExecutions.toSet()

    /**
     * The `VERDICT_JSON` of the last `coding.verify` executed inside the native
     * tool loop.
     *
     * The completion report may be produced in the SAME turn as the verify call,
     * so the per-turn verdict recorder ([RobustBrowserAgent.lastVerificationVerdict]
     * equivalent) has not seen it yet. Capturing it here, at execution time,
     * gives the finish gate the actual verdict to compare a cited one against.
     */
    @Volatile
    var lastInnerVerdictJson: String? = null
        private set

    /** Record one inner-loop tool execution (called from [AgentToolManager.notifyToolExecuted]). */
    fun recordInnerToolExecution(domain: String, method: String, resultText: String? = null) {
        innerToolExecutions.add("$domain.$method")
        if (resultText == null) return
        if (method != "verify" && !resultText.contains("VERDICT_JSON:")) return
        ai.platon.pulsar.coding.VerificationReport.parseFromText(resultText)?.let {
            lastInnerVerdictJson = it.toJson()
        }
    }

    /** Reset the inner execution registry; invoked at the start of each run. */
    fun resetInnerToolExecutions() {
        innerToolExecutions.clear()
        lastInnerVerdictJson = null
    }

    val activeDriver get() = session.getOrCreateBoundDriver()
    val startTime get() = _startTime
    val baseDir: Path get() = _baseDir
    val logDir: Path get() = _logDir

    /**
     * Coding mode: the task operates on repository files/build with no browser page
     * needed. Overridden by [RobustBrowserAgent], which StatefulAgentRunner sets from
     * the task's EXPLICIT coding flag (`command.run`'s coding argument, the CLI's
     * `agent run --coding`) — never auto-detected. When true, page navigation and
     * screenshots are skipped so coding tasks don't pay browser overhead.
     */
    open val codingMode: Boolean get() = false

    protected val pageStateTracker = PageStateTracker(session, config)
    protected val stateManager by lazy { AgentStateManager(this, pageStateTracker) }

    override val uuid get() = _uuid
    override val stateHistory: AgentHistory get() = stateManager.stateHistory
    override val processTrace: List<ProcessTrace> get() = stateManager.processTrace

    /**
     * The execution session id of the most recent [run] call. Each run starts a new
     * execution session; states collected during the run carry this id (see
     * [AgentState.sessionId]), allowing task-scoped views of the shared history.
     */
    protected var _lastRunSessionId: String? = null
    override val lastRunSessionId: String? get() = _lastRunSessionId

    init {
        Files.createDirectories(baseDir)
    }

    /**
     * High-level task entry. The base class has no implementation: the observe-act engine that
     * used to drive it is gone, and the only engine left is the CLI tool loop implemented by
     * [RobustBrowserAgent].
     */
    abstract override suspend fun run(task: String): AgentHistory

    /**
     * Structured extraction: builds a rich prompt with DOM snapshot & optional JSON schema; performs
     * two-stage LLM calls (extract + evaluation metadata).  The returned [ExtractResult] carries the
     * CLEAN schema payload (fields exactly as extracted — no token/timing/metadata bookkeeping) plus
     * a truthful completion flag.
     */
    override suspend fun extract(options: ExtractOptions): ExtractResult {
        onWillExtract(options)

        val instruction = promptBuilder.initExtractUserInstruction(options.instruction)
        val context = stateManager.buildStandaloneExecutionContext(instruction, "extract")

        val result = try {
            val params = context.createExtractParams(options.schema)
            val inferenceResult = inference.extract(params)

            ExtractResult(
                success = true, message = "OK",
                data = inferenceResult.result,
                completed = inferenceResult.completed
            )
        } catch (e: Exception) {
            logger.error("❌ extract.error requestId={} msg={}", context.sid, e.message, e)

            ExtractResult(
                success = false, message = e.message ?: "extract failed",
                data = JsonNodeFactory.instance.objectNode(),
                completed = false
            )
        }

        onDidExtract(options, result)

        return result
    }

    /**
     * Convenience overload for structured extraction. When only an instruction string is provided,
     * it uses the built-in ExtractionSchema.DEFAULT.
     *
     * @param instruction The extraction instruction from the user.
     * @return The extraction result produced by the model.
     */
    override suspend fun extract(instruction: String): ExtractResult {
        val opts = ExtractOptions(instruction = instruction, ExtractionSchema.DEFAULT)
        return extract(opts)
    }

    /**
     * Convenience overload for structured extraction that constrains the result with a JSON schema.
     *
     * @param instruction The extraction instruction from the user.
     * @param schema The JSON schema used to constrain the returned data structure.
     * @return The extraction result produced by the model.
     */
    override suspend fun extract(instruction: String, schema: ExtractionSchema): ExtractResult {
        val opts = ExtractOptions(instruction = instruction, schema = schema)
        return extract(opts)
    }

    override suspend fun summarize(instruction: String?, selector: String?): String {
        onWillSummarize(instruction, selector)

        val textContent = activeDriver.textContent(selector) ?: return "(no text content)"
        val result = inference.summarize(instruction, textContent)

        onDidSummarize(instruction, selector, result)

        return result
    }

    protected fun onWillRun(action: ActionOptions) {
        val agentId = this.uuid.toString()
        // Emit AgentEventBus event for SSE streaming
        AgentEventBus.emitAgentEvent(
            eventType = AgenticEvents.PerceptiveAgent.ON_WILL_RUN,
            agentId = agentId,
            message = "Starting run with action: ${action.action.take(100)}",
            metadata = mapOf("action" to action.action)
        )

        // Keep existing EventBus for backward compatibility
        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_WILL_RUN, mapOf(
                "action" to action,
                "uuid" to uuid
            )
        )
    }

    protected fun onDidRun(action: ActionOptions, result: ActResult) {
        val agentId = this.uuid.toString()

        // Emit AgentEventBus event for SSE streaming
        AgentEventBus.emitAgentEvent(
            eventType = AgenticEvents.PerceptiveAgent.ON_DID_RUN,
            agentId = agentId,
            message = "Run completed",
            metadata = mapOf(
                "action" to action.action,
                "isComplete" to result.isComplete
            )
        )

        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_DID_RUN, mapOf(
                "action" to action,
                "uuid" to uuid,
                "result" to result,
                "stateHistory" to stateHistory
            )
        )
    }

    protected fun onWillAct(action: ActionOptions) {
        val agentId = this.uuid.toString()
        // Emit AgentEventBus event for SSE streaming
        AgentEventBus.emitAgentEvent(
            eventType = AgenticEvents.PerceptiveAgent.ON_WILL_ACT,
            agentId = agentId,
            message = "Starting action: ${action.action.take(100)}",
            metadata = mapOf("action" to action.action)
        )

        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_WILL_ACT, mapOf(
                "action" to action,
                "uuid" to uuid
            )
        )
    }

    protected fun onDidAct(action: ActionOptions, result: ActResult) {
        val agentId = this.uuid.toString()

        // Emit AgentEventBus event for SSE streaming
        AgentEventBus.emitAgentEvent(
            eventType = AgenticEvents.PerceptiveAgent.ON_DID_ACT,
            agentId = agentId,
            message = if (result.isSuccess) "Action completed successfully" else "Action failed: ${
                result.message.take(
                    100
                )
            }",
            metadata = mapOf(
                "action" to action.action,
                "success" to result.isSuccess,
                "isComplete" to result.isComplete
            )
        )

        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_DID_ACT, mapOf(
                "action" to action,
                "uuid" to uuid,
                "result" to result
            )
        )
    }

    protected fun onWillExtract(options: ExtractOptions) {
        val agentId = this.uuid.toString()
        // Emit AgentEventBus event for SSE streaming
        AgentEventBus.emitAgentEvent(
            eventType = AgenticEvents.PerceptiveAgent.ON_WILL_EXTRACT,
            agentId = agentId,
            message = "Starting extraction: ${options.instruction.take(100)}",
            metadata = mapOf("instruction" to options.instruction)
        )

        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_WILL_EXTRACT, mapOf(
                "options" to options,
                "uuid" to uuid
            )
        )
    }

    protected fun onDidExtract(options: ExtractOptions, result: ExtractResult) {
        val agentId = this.uuid.toString()

        // Emit AgentEventBus event for SSE streaming
        AgentEventBus.emitAgentEvent(
            eventType = AgenticEvents.PerceptiveAgent.ON_DID_EXTRACT,
            agentId = agentId,
            message = if (result.success) "Extraction completed successfully" else "Extraction failed: ${
                result.message.take(
                    100
                )
            }",
            metadata = mapOf(
                "instruction" to options.instruction,
                "success" to result.success
            )
        )

        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_DID_EXTRACT, mapOf(
                "options" to options,
                "uuid" to uuid,
                "result" to result
            )
        )
    }

    protected fun onWillSummarize(instruction: String?, selector: String?) {
        val agentId = this.uuid.toString()
        // Emit AgentEventBus event for SSE streaming
        AgentEventBus.emitAgentEvent(
            eventType = AgenticEvents.PerceptiveAgent.ON_WILL_SUMMARIZE,
            agentId = agentId,
            message = "Starting summarization",
            metadata = mapOf(
                "instruction" to instruction,
                "selector" to selector
            )
        )

        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_WILL_SUMMARIZE, mapOf(
                "instruction" to instruction,
                "selector" to selector,
                "uuid" to uuid
            )
        )
    }

    protected fun onDidSummarize(instruction: String?, selector: String?, result: String) {
        val agentId = this.uuid.toString()

        // Emit AgentEventBus event for SSE streaming
        AgentEventBus.emitAgentEvent(
            eventType = AgenticEvents.PerceptiveAgent.ON_DID_SUMMARIZE,
            agentId = agentId,
            message = "Summarization completed",
            metadata = mapOf(
                "instruction" to instruction,
                "resultLength" to result.length
            )
        )

        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_DID_SUMMARIZE, mapOf(
                "instruction" to instruction,
                "selector" to selector,
                "uuid" to uuid,
                "result" to result
            )
        )
    }

    override suspend fun clearHistory() {
        stateManager.clearHistory()
    }

    override fun close() {

    }

    private fun getAgentLogDir(): Path = AgentPaths.resolveTraceRunDir(startTime, uuid)
}
