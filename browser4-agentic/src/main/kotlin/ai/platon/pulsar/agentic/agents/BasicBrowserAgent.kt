package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.*
import ai.platon.pulsar.agentic.common.AgentPaths
import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.agentic.inference.AgentMessageList
import ai.platon.pulsar.agentic.inference.AgentStateManager
import ai.platon.pulsar.agentic.inference.InferenceEngine
import ai.platon.pulsar.agentic.inference.PromptBuilder
import ai.platon.pulsar.agentic.inference.action.ContextToAction
import ai.platon.pulsar.agentic.inference.detail.ActResultHelper
import ai.platon.pulsar.agentic.inference.detail.PageStateTracker
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.agentic.tools.AgentToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolSpecification
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.alwaysTrue
import ai.platon.pulsar.common.event.EventBus
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.serialize.json.Pson
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.text.MessageFormat
import java.time.Instant
import java.util.*

open class BasicBrowserAgent(
    override val session: AgenticSession,
    val config: AgentConfig
) : PerceptiveAgent {
    private val logger = getLogger(BasicBrowserAgent::class)
    private val _startTime: Instant = Instant.now()
    private val _uuid: UUID = UUID.randomUUID()
    private val _baseDir: Path = AgentPaths.resolveTimedDirectory(_startTime).resolve(_uuid.toString())
    private val _logDir = getAgentLogDir()

    protected val cta by lazy { ContextToAction(session.sessionConfig) }
    protected val inference by lazy { InferenceEngine(this) }
    protected val snapshotService get() = inference.snapshotService
    protected val promptBuilder = PromptBuilder()

    private val lazyToolExecutor by lazy {
        AgentToolExecutor(_baseDir, this)
    }

    /** The [AgentToolExecutor] used by this agent for tool discovery and execution. */
    val toolExtractor: AgentToolExecutor get() = lazyToolExecutor
    protected val fs get() = toolExtractor.fs

    val activeDriver get() = session.getOrCreateBoundDriver()
    val startTime get() = _startTime
    val baseDir: Path get() = _baseDir
    val logDir: Path get() = _logDir

    protected val pageStateTracker = PageStateTracker(session, config)
    protected val stateManager by lazy { AgentStateManager(this, pageStateTracker) }

    override val uuid get() = _uuid
    override val stateHistory: AgentHistory get() = stateManager.stateHistory
    override val processTrace: List<ProcessTrace> get() = stateManager.processTrace

    init {
        Files.createDirectories(baseDir)

        // Register event listeners for debugging
        var eventType = AgenticEvents.InferenceEngine.ON_WILL_OBSERVE
        EventBus.register(eventType) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val messages = map["messages"] as? AgentMessageList ?: return@register null
            // DO SOMETHING
            printlnPro(this, eventType + ">>> " + messages.messages.lastOrNull()?.content?.take(200))
            return@register messages
        }

        eventType = AgenticEvents.InferenceEngine.ON_DID_OBSERVE
        EventBus.register(eventType) { payload ->
            val map = payload as? Map<String, Any?> ?: return@register null
            val actionDescription = map["actionDescription"] as? ActionDescription ?: return@register null
            // DO SOMETHING
            printlnPro(this, Pson.toJson(actionDescription))
            return@register actionDescription
        }
    }

    override suspend fun run(action: ActionOptions): AgentHistory {
        onWillRun(action)

        var result = act(action)

        var i = 0
        while (!result.isComplete && i++ < config.maxSteps) {
            result = act(action)
        }

        onDidRun(action, result)

        return stateHistory
    }

    override suspend fun run(task: String): AgentHistory {
        val actionOptions = ActionOptions(action = task, multiAct = true)
        return run(actionOptions)
    }

    /**
     * Observes the page given an instruction, returning zero or more ObserveResult objects describing
     * candidate elements and potential actions (if returnAction=true).
     */
    override suspend fun observe(instruction: String): List<ObserveResult> {
        val opts = ObserveOptions(instruction = instruction, returnAction = null)
        return observe(opts)
    }

    override suspend fun observe(options: ObserveOptions): List<ObserveResult> {
        onWillObserve(options)

        val result = doObserve(options)

        onDidObserve(options, result)

        return result.observeResults
    }

    protected suspend fun doObserve(options: ObserveOptions): ObserveActResult {
        val context = stateManager.getOrCreateActiveContext(options, "observe")

        val result = doObserveActObserve(options, context, options.fromResolve)

        return result
    }

    /**
     * Convenience wrapper building ActionOptions from a raw action string.
     */
    override suspend fun act(action: String): ActResult {
        val opts = ActionOptions(action = action)
        return act(opts)
    }

    /**
     * Executes a single observe->act cycle for a supplied ActionOptions. Times out after actTimeoutMs
     * to prevent indefinite hangs. Model may produce multiple candidate tool calls internally; only
     * one successful execution is recorded in stateHistory.
     */
    override suspend fun act(action: ActionOptions): ActResult {
        onWillAct(action)

        val context = stateManager.getOrCreateActiveContext(action, "act")

        val result = try {
            withTimeout(config.actTimeoutMs) {
                doObserveAct(action)
            }
        } catch (e: TimeoutCancellationException) {
            val msg = "⏳ Action timed out after ${config.actTimeoutMs}ms: ${action.action}"
            stateManager.addTrace(
                context.agentState,
                items = mapOf("timeoutMs" to config.actTimeoutMs, "instruction" to action.action),
                event = "actTimeout",
                message = "⏳ act TIMEOUT"
            )
            ActResultHelper.failed(IllegalStateException(msg, e), action.action)
        }

        if (result.detail != null) {
            stateManager.addToHistory(context.agentState)
        }

        onDidAct(action, result)

        return result
    }

    override suspend fun act(observe: ObserveResult): ActResult {
        val instruction = observe.agentState.instruction
        val context = stateManager.getActiveContext()
        require(observe.agentState == context.agentState) { "Required: observe.agentState == context?.agentState" }

        val element = observe.observeElement
            ?: return ActResultHelper.failed(IllegalStateException("No observation to act"), instruction)
        val actionDescription =
            observe.actionDescription
                ?: return ActResultHelper.failed(IllegalStateException("No action description to act"), instruction)
        val originalToolCall =
            element.toolCall ?: return ActResultHelper.failed(IllegalStateException("No tool call to act"), instruction)
        val toolCall = toolExtractor.normalizeToolCall(originalToolCall)
        val method = toolCall.method

        logger.info("🛠️ tool.exec sid={} step={} tool={}", context.sid, context.step, toolCall.pseudoExpression)

        return try {
            val result = toolExtractor.execute(toolCall)
            // Discuss: should we sync browser state after tool call immediately? probably not.
            // stateManager.syncBrowserUseState(context)

            val state = if (result.isSuccess) "✅ success" else """☑️ executed"""
            val description = MessageFormat.format(
                "✅ tool.done | {0} {1} | {4} | {2}/{3}",
                method, state, element.backendNodeId, element.cssSelector, element.pseudoExpression
            )
            logger.info(description)

            val detailedResult = DetailedActResult(
                actionDescription = actionDescription,
                toolCallResult = result.copy(actionDescription = actionDescription),
                description = description
            )
            stateManager.updateAgentState(context, detailedResult)

            stateManager.addTrace(
                context.agentState,
                items = mapOf("tool" to method), event = "toolExecOk", message = description
            )

            detailedResult.toActResult()
        } catch (e: Exception) {
            logger.error("❌ observe.act execution failed sid={} msg={}", uuid.toString().take(8), e.message, e)

            val description = MessageFormat.format(
                "❌ observe.act execution failed | {0} | {1}/{2}",
                method, observe.backendNodeId, element.cssSelector
            )

            val failedResult = ToolCallResult(
                evaluate = TcEvaluate(actionDescription.pseudoExpression ?: toolCall.pseudoExpression, e),
                message = e.message,
                actionDescription = actionDescription
            )
            val detailedResult = DetailedActResult(
                actionDescription = actionDescription,
                toolCallResult = failedResult,
                description = description,
                exception = e
            )
            stateManager.updateAgentState(context, detailedResult)
            stateManager.addTrace(
                context.agentState,
                items = mapOf("tool" to method),
                event = "toolExecFailed",
                message = description
            )

            detailedResult.toActResult()
        }
    }

    /**
     * Structured extraction: builds a rich prompt with DOM snapshot & optional JSON schema; performs
     * two-stage LLM calls (extract + metadata) and merges results with token/time metrics.
     */
    override suspend fun extract(options: ExtractOptions): ExtractResult {
        onWillExtract(options)

        val instruction = promptBuilder.initExtractUserInstruction(options.instruction)
        val context = stateManager.buildStandaloneExecutionContext(instruction, "extract")

        val result = try {
            val params = context.createExtractParams(options.schema)
            val resultNode = inference.extract(params)

            ExtractResult(success = true, message = "OK", data = resultNode)
        } catch (e: Exception) {
            logger.error("❌ extract.error requestId={} msg={}", context.sid, e.message, e)

            ExtractResult(
                success = false, message = e.message ?: "extract failed", data = JsonNodeFactory.instance.objectNode()
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

    data class ObserveActResult(
        val observeResults: List<ObserveResult>,
        val actionDescription: ActionDescription,
    )

    protected fun onWillObserve(options: ObserveOptions) {
        val agentId = this.uuid.toString()
        // Emit AgentEventBus event for SSE streaming
        AgentEventBus.emitAgentEvent(
            eventType = AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE,
            agentId = agentId,
            message = "Starting observation",
            metadata = mapOf("instruction" to options.instruction?.take(100))
        )

        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE, mapOf(
                "options" to options,
                "uuid" to uuid
            )
        )
    }

    protected fun onDidObserve(options: ObserveOptions, result: ObserveActResult) {
        val agentId = this.uuid.toString()

        // Emit AgentEventBus event for SSE streaming
        AgentEventBus.emitAgentEvent(
            eventType = AgenticEvents.PerceptiveAgent.ON_DID_OBSERVE,
            agentId = agentId,
            message = "Observation completed",
            metadata = mapOf(
                "instruction" to options.instruction?.take(100),
                "resultCount" to result.observeResults.size
            )
        )

        EventBus.emit(
            AgenticEvents.PerceptiveAgent.ON_DID_OBSERVE, mapOf(
                "options" to options,
                "uuid" to uuid,
                "observeResults" to result.observeResults,
                "actionDescription" to result.actionDescription
            )
        )

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

    private suspend fun doObserveAct(options: ActionOptions): ActResult {
        val options = when {
            !options.fromRunLoop -> options.copy(action = promptBuilder.buildObserveActToolUsePrompt(options.action))
            else -> options
        }

        val context = stateManager.getActiveContext()

        val (observeResults, actionDescription) = doObserveActObserve(options, context, options.fromRunLoop)

        if (actionDescription.isDecidedComplete) {
            return ActResultHelper.complete(actionDescription)
        }

        if (observeResults.isEmpty()) {
            val msg = "⚠️ doObserveAct: No observe result"
            stateManager.addTrace(context.agentState, event = "observeActNoAction", message = msg)
            return ActResultHelper.failed(IllegalStateException(msg), action = options.action)
        }

        val resultsToTry = observeResults.take(config.maxResultsToTry)
        var lastError: String? = null
        val actResults = mutableListOf<ActResult>()
        // Take the first success action
        for ((index, chosen) in resultsToTry.withIndex()) {
            require(context.step == context.agentState.step) { "Required: context.step == context.agentState.step" }
            require(context.prevAgentState == context.agentState.prevState) { "Required: context.step == context.agentState.step" }

            val method = chosen.method?.trim()
            if (method == null) {
                lastError = "LLM returned no method for candidate ${index + 1}"
                continue
            }

            val actResult = try {
                val result = act(chosen)
                actResults.add(result)

                result
            } catch (e: Exception) {
                lastError = "Execution failed for candidate ${index + 1}: ${e.message}"
                logger.warn("⚠️ Failed to execute candidate {}: {}", index + 1, e.message)
                continue
            } finally {

            }

            if (!actResult.isSuccess) {
                lastError = "Candidate ${index + 1} failed: ${actResult.message}"
                continue
            }

            stateManager.addTrace(
                context.agentState,
                event = "actSuccess",
                items = mapOf("candidateIndex" to (index + 1), "candidateTotal" to resultsToTry.size),
                message = "✅ act SUCCESS"
            )

            return actResult
        }

        val msg = "❌ All ${resultsToTry.size} candidates failed. Last error: $lastError"
        stateManager.addTrace(
            context.agentState,
            event = "actAllFailed",
            items = mapOf("candidates" to resultsToTry.size),
            message = msg
        )

        return ActResultHelper.failed(IllegalStateException(msg), options.action)
    }

    private suspend fun doObserveActObserve(
        options: Any, context: ExecutionContext, multistep: Boolean
    ): ObserveActResult {
        val observeOptions = options as? ObserveOptions
        val drawOverlay = alwaysTrue() || (observeOptions?.drawOverlay ?: false)

        val params = when (options) {
            is ObserveOptions -> context.createObserveParams(
                options,
                fromAct = false,
                multistep = multistep
            )

            is ActionOptions -> context.createObserveActParams(multistep)
            else -> throw IllegalArgumentException("Not supported options | $options")
        }

        // Sync browser state just before observe
        stateManager.updateBrowserUseState(context)
        val interactiveElements = context.agentState.browserUseState.getAllInteractiveElements()
        try {
            if (drawOverlay) {
                snapshotService.addHighlights(interactiveElements)
            }

            // Only capture screenshot when the previous action was a browser-interaction action
            // (or on the first step when no previous action exists), to reduce token usage.
            val lastActionDomain = context.agentState.prevState?.actionDomain
            val needsScreenshot = ToolSpecification.isBrowserInteraction(lastActionDomain)
            val screenshotB64 = if (needsScreenshot) activeDriver.screenshot() else null
            val context = context.copy(screenshotB64 = screenshotB64)

            val actionDescription = withTimeout(config.llmInferenceTimeoutMs) {
                inference.observe(params, context)
            }

            val observeResults = actionDescription.toObserveResults(context.agentState)

            return ObserveActResult(observeResults, actionDescription)
        } finally {
            if (drawOverlay) {
                snapshotService.removeHighlights()
            }
        }
    }

    override suspend fun clearHistory() {
        stateManager.clearHistory()
    }

    override fun close() {

    }

    private fun getAgentLogDir(): Path {
        val agentId = uuid.toString()
        val auxLogDir = AppPaths.detectAuxiliaryLogDir().resolve("agent")
        return auxLogDir.resolve(AppPaths.fromTime(startTime)).resolve(agentId)
    }
}
