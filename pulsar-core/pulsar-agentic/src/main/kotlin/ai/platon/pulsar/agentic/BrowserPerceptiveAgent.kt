package ai.platon.pulsar.agentic

import ai.platon.pulsar.agentic.ai.agent.detail.*
import ai.platon.pulsar.agentic.ai.todo.ToDoManager
import ai.platon.pulsar.agentic.tools.ActionValidator
import ai.platon.pulsar.browser.driver.chrome.dom.util.DomDebug
import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import kotlinx.coroutines.*
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Configuration for enhanced error handling, retry mechanisms and agent behavior.
 *
 * Each field tunes a specific aspect of the autonomous loop:
 * - maxSteps: Upper bound of observe->act iterations in a single resolve session.
 * - maxRetries: Retries for the high-level resolve() in case of transient/timeout errors.
 * - baseRetryDelayMs/maxRetryDelayMs: Exponential backoff parameters.
 * - consecutiveNoOpLimit: Abort after N consecutive steps without actionable tool calls.
 * - actionGenerationTimeoutMs / llmInferenceTimeoutMs: Timeouts for model inference.
 * - screenshotCaptureTimeoutMs / screenshotEveryNSteps: Screenshot cadence & timeout.
 * - memoryCleanupIntervalSteps / maxHistorySize: In-memory history retention & cleanup.
 * - enableAdaptiveDelays: Adds short delays based on average step execution time.
 * - enablePreActionValidation: Validates tool calls before execution for safety.
 * - actTimeoutMs / resolveTimeoutMs: Overall upper bound for act() and resolve().
 * - maxResultsToTry: Number of candidate actions to attempt per model generation in act().
 * - domSettleTimeoutMs / domSettleCheckIntervalMs: Stabilization of DOM before each step.
 * - allowLocalhost / allowedPorts: URL safety policy.
 * - maxSelectorLength / denyUnknownActions: Selector validation & unknown action policy.
 * - todo* flags: Control integration with persistent todolist.md planning & progress.
 */
data class AgentConfig(
    val maxSteps: Int = 100,
    val maxRetries: Int = 3,
    val baseRetryDelayMs: Long = 1_000,
    val maxRetryDelayMs: Long = 30_000,
    val consecutiveNoOpLimit: Int = 5,
    val actionGenerationTimeoutMs: Long = 30_000,
    val screenshotCaptureTimeoutMs: Long = 5_000,
    val enableStructuredLogging: Boolean = false,
    val logInferenceToFile: Boolean = true,
    val enableDebugMode: Boolean = false,
    val enablePerformanceMetrics: Boolean = true,
    val memoryCleanupIntervalSteps: Int = 50,
    val maxHistorySize: Int = 100,
    val enableAdaptiveDelays: Boolean = true,
    val enablePreActionValidation: Boolean = true,
    // New configuration options for fixes
    val actTimeoutMs: Long = 10.minutes.inWholeMilliseconds,
    val llmInferenceTimeoutMs: Long = 10.minutes.inWholeMilliseconds,
    val maxResultsToTry: Int = 3,
    val screenshotEveryNSteps: Int = 1,
    val domSettleTimeoutMs: Long = 5000,
    val domSettleCheckIntervalMs: Long = 100,
    val allowLocalhost: Boolean = false,
    val allowedPorts: Set<Int> = setOf(80, 443, 8080, 8443, 3000, 5000, 8000, 9000),
    val maxSelectorLength: Int = 1000,
    val denyUnknownActions: Boolean = false,
    // Overall timeout for resolve() to avoid indefinite hangs
    val resolveTimeoutMs: Long = 24.hours.inWholeMilliseconds,
    // Circuit breaker configuration
    val maxConsecutiveLLMFailures: Int = 5,
    val maxConsecutiveValidationFailures: Int = 8,
    // Checkpointing configuration
    val enableCheckpointing: Boolean = false,
    val checkpointIntervalSteps: Int = 10,
    val maxCheckpointsPerSession: Int = 5,
    // Enable per-step checkpointing for full context persistence and resume support
    val enableStepCheckpointing: Boolean = false,
    // --- todolist.md integration flags ---
    val enableTodoWrites: Boolean = true,
    val todoPlanWithLLM: Boolean = true,
    val todoWriteProgressEveryStep: Boolean = true,
    val todoProgressWriteEveryNSteps: Int = 1,
    val todoMaxProgressLines: Int = 200,
    val todoEnableAutoCheck: Boolean = true,
    val todoTagsFromToolCall: Boolean = true,
)

open class BrowserPerceptiveAgent constructor(
    session: AgenticSession,
    val maxSteps: Int = 100,
    config: AgentConfig = AgentConfig(maxSteps = maxSteps)
) : BrowserAgentActor(session, config) {
    private val logger = getLogger(BrowserPerceptiveAgent::class)
    private val slogger = StructuredAgentLogger(logger, config)

    protected val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    // A dedicated scope for all agent work so close() can cancel promptly
    protected val agentJob = SupervisorJob()
    protected val agentScope = CoroutineScope(Dispatchers.Default + agentJob)

    protected val todo: ToDoManager by lazy { ToDoManager(toolExecutor.fs, config, uuid, slogger) }
    protected val actionValidator = ActionValidator()

    protected val performanceMetrics = PerformanceMetrics()
    protected val consecutiveFailureCounter = AtomicInteger(0)
    protected val consecutiveLLMFailureCounter = AtomicInteger(0)
    protected val consecutiveValidationFailureCounter = AtomicInteger(0)
    protected val stepExecutionTimes = ConcurrentHashMap<Int, Long>()

    // New components for better separation of concerns
    protected val circuitBreaker = CircuitBreaker(
        maxLLMFailures = config.maxConsecutiveLLMFailures,
        maxValidationFailures = config.maxConsecutiveValidationFailures,
        maxExecutionFailures = 3
    )
    protected val retryStrategy = RetryStrategy(
        maxRetries = config.maxRetries,
        baseDelayMs = config.baseRetryDelayMs,
        maxDelayMs = config.maxRetryDelayMs
    )
    protected val retryCounter = AtomicInteger(0)
    protected val checkpointManager = CheckpointManager(baseDir.resolve("checkpoints"))

    constructor(
        driver: WebDriver, session: AgenticSession, maxSteps: Int = 100,
        config: AgentConfig = AgentConfig(maxSteps = maxSteps)
    ) : this(session, maxSteps = maxSteps, config = config) {
        session.bindDriver(driver)
    }

    /**
     * High-level problem resolution entry. Builds an ActionOptions and delegates to resolve(ActionOptions).
     */
    override suspend fun run(task: String): AgentHistory {
        val opts = ActionOptions(action = task)
        run(opts)
        return stateHistory
    }

    /**
     * Run an autonomous loop (observe -> act -> ...) attempting to fulfill the user goal described
     * in the ActionOptions. Applies retry and timeout strategies; records structured traces but keeps
     * stateHistory focused on executed tool actions only.
     */
    override suspend fun run(action: ActionOptions): AgentHistory {
        if (isClosed) {
            return stateHistory
        }

        try {
            withContext(agentScope.coroutineContext) { resolveInCoroutine(action) }
        } catch (_: CancellationException) {
            logger.info("Cancelled due to cancellation")
        } finally {
            stateManager.writeProcessTrace()
        }

        return stateHistory
    }

    /**
     * Executes a single observe->act cycle for a supplied ActionOptions. Times out after actTimeoutMs
     * to prevent indefinite hangs. Model may produce multiple candidate tool calls internally; only
     * one successful execution is recorded in stateHistory.
     */
    override suspend fun act(action: ActionOptions): ActResult {
        if (isClosed) {
            return ActResult(false, "USER interrupted", action = action.action)
        }

        return try {
            withContext(agentScope.coroutineContext) {
                super.act(action)
            }
        } catch (_: CancellationException) {
            ActResult(false, "USER interrupted", action = action.action)
        }
    }

    /**
     * Executes a tool call derived from a prior observation result. Performs patching (selector/url),
     * validation, and updates AgentState history on success or failure.
     */
    override suspend fun act(observe: ObserveResult): ActResult {
        if (isClosed) {
            return ActResult(false, "USER interrupted", action = observe.agentState.instruction)
        }

        return try {
            withContext(agentScope.coroutineContext) {
                super.act(observe)
            }
        } catch (_: CancellationException) {
            ActResult(false, "USER interrupted", action = observe.agentState.instruction)
        }
    }

    /**
     * Structured extraction: builds a rich prompt with DOM snapshot & optional JSON schema; performs
     * two-stage LLM calls (extract + metadata) and merges results with token/time metrics.
     */
    override suspend fun extract(options: ExtractOptions): ExtractResult {
        if (isClosed) {
            return ExtractResult(
                success = false,
                message = "USER interrupted",
                data = JsonNodeFactory.instance.objectNode()
            )
        }

        return try {
            withContext(agentScope.coroutineContext) {
                super.extract(options)
            }
        } catch (_: CancellationException) {
            ExtractResult(success = false, message = "USER interrupted", data = JsonNodeFactory.instance.objectNode())
        }
    }

    /**
     * Observes the page given an instruction, returning zero or more ObserveResult objects describing
     * candidate elements and potential actions (if returnAction=true).
     */
    override suspend fun observe(options: ObserveOptions): List<ObserveResult> {
        if (isClosed) {
            return emptyList()
        }

//        val context = options.getContext() ?: stateManager.buildInitExecutionContext(options, "observe")
//        options.setContext(context)
        val context = stateManager.getOrCreateActiveContext(options)

        if (!options.fromResolve) {
            return withContext(agentScope.coroutineContext) {
                if (isClosed) throw CancellationException("closed")
                super.observe(options)
            }
        }

        try {
            val results = withContext(agentScope.coroutineContext) {
                if (isClosed) throw CancellationException("closed")
                super.observe(options)
            }

            circuitBreaker.recordSuccess(CircuitBreaker.FailureType.LLM_FAILURE)
            consecutiveLLMFailureCounter.set(0) // Keep for backward compatibility

            return results
        } catch (e: Exception) {
            handleObserveException(e, context)
            return emptyList()
        }
    }

    fun stop() = close()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { agentJob.cancel(CancellationException("USER interrupted via close()")) }
            // Best-effort trace for visibility; avoid throwing
            runCatching {
                val last = stateHistory.states.lastOrNull()
                stateManager.addTrace(last, emptyMap(), event = "userClose", message = "🛑 USER CLOSE")
            }
        }
    }

    /**
     * Returns a concise summary of the latest agent state; if no history exists, returns a placeholder text.
     */
    override fun toString(): String {
        return stateHistory.states.lastOrNull()?.toString() ?: "(no history)"
    }

    protected data class ResolveResult(
        val context: ExecutionContext,
        val result: ActResult
    )

    private suspend fun resolveInCoroutine(action: ActionOptions): ResolveResult {
        val instruction = action.action
        val baseContext = stateManager.buildBaseExecutionContext(action, "resolve-init")
        stateManager.setActiveContext(baseContext)
        val sessionStartTime = baseContext.stepStartTime

        // Add start history for better traceability (meta record only)
        stateManager.addTrace(
            baseContext.agentState,
            mapOf(
                "session" to baseContext.sid,
                "goal" to Strings.compactInline(instruction, 160),
                "maxSteps" to config.maxSteps,
                "maxRetries" to config.maxRetries
            ),
            event = "resolveStart",
            message = "🚀 resolve START"
        )

        // Overall timeout to prevent indefinite hangs for a full resolve session
        // Calculate effective timeout accounting for potential retry delays
        val maxPossibleDelays = (0 until config.maxRetries).fold(0L) { acc, i -> acc + calculateRetryDelay(i) }
        val effectiveTimeout = config.resolveTimeoutMs + maxPossibleDelays

        return try {
            val result = withTimeout(effectiveTimeout) {
                resolveProblemWithRetry(action, baseContext)
            }

            val dur = Duration.between(sessionStartTime, Instant.now()).toMillis()
            // Not a single-step action, keep it out of AgentState history
            stateManager.addTrace(
                result.context.agentState, mapOf(
                    "session" to baseContext.sid,
                    "success" to result.result.success, "durationMs" to dur
                ), event = "resolveDone", message = "✅ resolve DONE"
            )

            result
        } catch (_: TimeoutCancellationException) {
            val msg = "⏳ Resolve timed out after ${effectiveTimeout}ms (base: ${config.resolveTimeoutMs}ms + " +
                    "retries: ${maxPossibleDelays}ms): $instruction"
            stateManager.addTrace(
                baseContext.agentState, mapOf(
                    "timeoutMs" to effectiveTimeout, "instruction" to Strings.compactInline(instruction, 160)
                ),
                event = "resolveTimeout",
                message = "⏳ resolve TIMEOUT"
            )
            val actResult = ActResult(success = false, message = msg, action = instruction)
            ResolveResult(baseContext, actResult)
        } finally {
            // clear history so the next task will have a clean operation trace for summary.
            // but we do not clear process trace which will be kept to trace all operations and states.
            // 20251122: DO NOT CLEAR HISTORY, is you want to run new task with a new context, use TaskScopedBrowserPerceptiveAgent
            // instead.
            // stateManager.clearHistory()
        }
    }

    protected suspend fun generateActions(context: ExecutionContext): ActionDescription {
        context.screenshotB64 = if (context.step % config.screenshotEveryNSteps == 0) {
            if (isClosed) return ActionDescription(
                context.instruction,
                context = context,
                exception = CancellationException("closed")
            )
            captureScreenshotWithRetry(context)
        } else null

        // Prepare messages for model
        val messages = promptBuilder.buildResolveMessageListAll(context)

        return try {
            if (isClosed) {
                throw CancellationException("closed")
            }

            val actionDescription = cta.generate(messages, context)
            requireNotNull(context.agentState.actionDescription) { "Filed should be set: context.agentState.actionDescription" }
            circuitBreaker.recordSuccess(CircuitBreaker.FailureType.LLM_FAILURE)
            consecutiveLLMFailureCounter.set(0) // Keep for backward compatibility

            actionDescription
        } catch (e: Exception) {
            handleObserveException(e, context)

            ActionDescription(
                context.instruction,
                context = context,
                exception = e,
                modelResponse = ModelResponse.INTERNAL_ERROR
            )
        }
    }

    private fun handleObserveException(e: Exception, context: ExecutionContext) {
        val failures = try {
            circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)
        } catch (cbError: CircuitBreakerTrippedException) {
            throw PerceptiveAgentError.PermanentError(cbError.message ?: "Circuit breaker tripped", cbError)
        }

        consecutiveLLMFailureCounter.set(failures) // Keep for backward compatibility
        logger.error("🤖❌ action.gen.fail sid={} failures={} msg={}", context.sid, failures, e.message, e)
        consecutiveFailureCounter.incrementAndGet()
    }

    protected suspend fun prepareStep(
        action: ActionOptions,
        ctxIn: ExecutionContext,
        noOpsIn: Int
    ): ExecutionContext {
        val context = ensureReadyForStep(action, "step", ctxIn)
        // requireNotNull(action.getContext()) { "Filed should be set: action.context" }

        val agentState = context.agentState
        val browserUseState = agentState.browserUseState
        val step = context.step
        val sid = context.sid

        var consecutiveNoOps = noOpsIn
        val unchangedCount = pageStateTracker.checkStateChange(browserUseState)
        if (unchangedCount >= 3) {
            logger.info("⚠️ loop.warn sid={} step={} unchangedSteps={}", sid, step, unchangedCount)
            consecutiveNoOps++
        }

        logger.info("▶️ step.exec sid={} step={}/{} noOps={}", sid, step, config.maxSteps, consecutiveNoOps)
        if (logger.isDebugEnabled) {
            logger.debug("🧩 dom={}", DomDebug.summarizeStr(browserUseState.domState, 5))
        }
        if (step % config.memoryCleanupIntervalSteps == 0) {
            performMemoryCleanup(context)
        }
        if (config.enableCheckpointing && step % config.checkpointIntervalSteps == 0) {
            runCatching { saveCheckpoint(context) }
                .onFailure { e -> logger.warn("💾❌ checkpoint.save.fail sid={} step={} msg={}", sid, step, e.message) }
        }

        return context
    }

    protected suspend fun ensureReadyForStep(
        action: ActionOptions, event: String, ctxIn: ExecutionContext
    ): ExecutionContext {
        val driver = activeDriver
        val url = driver.url()
        if (url.isBlank() || url == "about:blank") {
            val searchURL = if (AppContext.isCN) AppConstants.SEARCH_ENGINE_URL else AppConstants.SEARCH_ENGINE_EN_URL
            driver.navigateTo(searchURL)
        }

        // Only wait for DOM settle just before collection DOM tree data
//        val settleMs = action.domSettleTimeoutMs?.toLong()?.coerceAtLeast(0L) ?: config.domSettleTimeoutMs
//        if (settleMs > 0) {
//            pageStateTracker.waitForDOMSettle(settleMs, config.domSettleCheckIntervalMs)
//        }

        val instruction = action.action
        val step = ctxIn.step + 1
        val activeContext = stateManager.buildExecutionContext(instruction, step, event, baseContext = ctxIn)
        // action.setContext(activeContext!!)
        stateManager.setActiveContext(activeContext)

        return activeContext
    }

    private suspend fun doResolveProblem(
        initActionOptions: ActionOptions, initContext: ExecutionContext, attempt: Int
    ): ResolveResult {
        initializeResolution(initContext, attempt)
        var consecutiveNoOps = 0
        var context = initContext
        val startTime = Instant.now()
        try {
            val action = initActionOptions.copy(fromResolve = true)

            while (!isClosed && context.step < config.maxSteps) {
                val stepResult: StepProcessingResult
                try {
                    context = prepareStep(action, context, consecutiveNoOps)

                    stepResult = step(action, context, consecutiveNoOps)

                    context = stepResult.context
                    consecutiveNoOps = stepResult.consecutiveNoOps
                } finally {
                    stateManager.addToHistory(context.agentState)
                }

                if (stepResult.shouldStop) {
                    break
                }
            }

            val actResult = buildFinalActResult(initContext.instruction, context, startTime)

            return ResolveResult(context, actResult)
        } catch (_: CancellationException) {
            logger.info("""🛑 [USER interrupted] sid={} steps={}""", context.sid, context.step)
            val result = ActResult(success = false, message = "USER interrupted", action = initContext.instruction)
            return ResolveResult(context, result)
        } catch (e: Exception) {
            throw handleResolutionFailure(e, context, startTime)
        }
    }

    /**
     * Enhanced execution with comprehensive error handling and retry mechanisms
     * Returns the final summary with enhanced error handling.
     */
    private suspend fun resolveProblemWithRetry(action: ActionOptions, context: ExecutionContext): ResolveResult {
        var lastError: Exception? = null
        val sid = context.sid
        // activeContext = context
        val activeContext = stateManager.getActiveContext()

        for (attempt in 0..config.maxRetries) {
            try {
                val result = doResolveProblem(action, activeContext, attempt)
                // activeContext = result.context
                // stateManager.setActiveContext(result.context)

                return result
            } catch (e: Exception) {
                lastError = e
                logger.error("💥 resolve.unexpected attempt={} sid={} msg={}", attempt + 1, sid, e.message, e)

                cleanupPartialState(activeContext)
//                baseContext = stateManager.buildBaseExecutionContext(action, "resolve-init-recovery")
//                activeContext = baseContext
                val baseContext = stateManager.buildBaseExecutionContext(action, "resolve-init-recovery")
                stateManager.setActiveContext(baseContext)
            }
        }

        val actResult = ActResult(
            success = false,
            message = "Failed after ${config.maxRetries + 1} attempts. Last error: ${lastError?.message}",
            action = action.action
        )

        return ResolveResult(activeContext!!, actResult)
    }

    protected suspend fun initializeResolution(initContext: ExecutionContext, attempt: Int) {
        val sid = initContext.sid
        logger.info(
            "🚀 agent.start sid={} step={} url={} instr='{}' attempt={} maxSteps={} maxRetries={}",
            sid, initContext.step, initContext.targetUrl, Strings.compactInline(initContext.instruction, 100),
            attempt + 1, config.maxSteps, config.maxRetries
        )
        if (config.enableTodoWrites) {
            runCatching { todo.primeIfEmpty(initContext.instruction, initContext.targetUrl) }
                .onFailure { e -> slogger.logError("📝❌ todo.prime.fail", e, sid) }
        }
    }

    data class StepProcessingResult(
        val context: ExecutionContext,
        val consecutiveNoOps: Int,
        val shouldStop: Boolean
    )

    protected open suspend fun step(
        action: ActionOptions,
        context: ExecutionContext,
        noOpsIn: Int
    ): StepProcessingResult {
        var consecutiveNoOps = noOpsIn

        // val context = prepareStep(action, ctxIn, consecutiveNoOps)

        // Observe
        val actionDescription = generateActions(context)

        if (actionDescription.isReallyComplete) {
            onTaskCompletion(actionDescription, context)
            // Save final checkpoint when task is complete
            if (config.enableStepCheckpointing) {
                runCatching { saveStepCheckpoint(context, consecutiveNoOps, isComplete = true) }
                    .onFailure { e -> logger.warn("💾❌ step.checkpoint.fail sid={} step={} msg={}", context.sid, context.step, e.message) }
            }
            return StepProcessingResult(context, consecutiveNoOps, true)
        }

        consecutiveNoOps = 0
        // Act
        val detailedActResult = executeToolCall(actionDescription, context)

        val sid = context.sid
        val step = context.step
        val stepStartTime = context.stepStartTime

        if (detailedActResult != null) {
            stateManager.updateAgentState(context, detailedActResult)
            updateTodo(context, detailedActResult.actionDescription)
            updatePerformanceMetrics(step, context.stepStartTime, true)

            val tcResult = detailedActResult.toolCallResult
            val method = detailedActResult.actionDescription.toolCall?.method
            val preview = tcResult?.evaluate?.preview
            logger.info("🏁 step.done sid={} step={} method={} result={}", sid, step, method, preview)
        } else {
            consecutiveNoOps++
            val stop = handleConsecutiveNoOps(consecutiveNoOps, context)
            updatePerformanceMetrics(step, stepStartTime, false)
            if (stop) {
                // Save checkpoint when stopping due to no-ops
                if (config.enableStepCheckpointing) {
                    runCatching { saveStepCheckpoint(context, consecutiveNoOps, isComplete = false) }
                        .onFailure { e -> logger.warn("💾❌ step.checkpoint.fail sid={} step={} msg={}", context.sid, context.step, e.message) }
                }
                return StepProcessingResult(context, consecutiveNoOps, true)
            }
        }

        // Save step checkpoint after each successful step
        if (config.enableStepCheckpointing) {
            runCatching { saveStepCheckpoint(context, consecutiveNoOps, isComplete = false) }
                .onFailure { e -> logger.warn("💾❌ step.checkpoint.fail sid={} step={} msg={}", context.sid, context.step, e.message) }
        }

        delay(calculateAdaptiveDelay())
        return StepProcessingResult(context, consecutiveNoOps, false)
    }

    protected fun classifyError(e: Exception, step: Int) = retryStrategy.classifyError(e, "step $step")

    protected fun shouldRetryError(e: Exception) = retryStrategy.shouldRetry(e)

    protected fun calculateRetryDelay(attempt: Int) = retryStrategy.calculateDelay(attempt)

    protected fun cleanupPartialState(context: ExecutionContext) {
        try {
            logger.info("🧹 cleanup.partial sid={} step={}", context.sid, context.step)
            circuitBreaker.reset()
            consecutiveFailureCounter.set(0)
            consecutiveLLMFailureCounter.set(0)
            consecutiveValidationFailureCounter.set(0)
            actionValidator.clearCache()
            // pageStateTracker.waitForDOMSettle(1000, 100)
        } catch (e: Exception) {
            logger.warn("⚠️ cleanup.partial.fail sid={} msg={}", context.sid, e.message)
        }
    }

    private suspend fun executeToolCall(
        actionDescription: ActionDescription,
        context: ExecutionContext
    ): DetailedActResult? {
        context.agentState.event = "toolExec"

        val step = context.step
        val toolCall = actionDescription.toolCall ?: return null

        if (config.enablePreActionValidation && !actionValidator.validateToolCall(toolCall)) {
            val failures = try {
                circuitBreaker.recordFailure(CircuitBreaker.FailureType.VALIDATION_FAILURE)
            } catch (cbError: CircuitBreakerTrippedException) {
                throw PerceptiveAgentError.PermanentError(cbError.message ?: "Circuit breaker tripped", cbError)
            }
            consecutiveValidationFailureCounter.set(failures)
            logger.info(
                "🛑 tool.validate.fail sid={} step={} failures={} locator={} | {}({}) | {}",
                context.sid, context.step, failures, actionDescription.locator, toolCall.method, toolCall.arguments,
                actionDescription.cssFriendlyExpression
            )
            stateManager.addTrace(
                context.agentState,
                mapOf("step" to step, "tool" to toolCall.method),
                event = "validationFailed",
                message = "🛑 validation-failed"
            )
            return null
        }

        circuitBreaker.recordSuccess(CircuitBreaker.FailureType.VALIDATION_FAILURE)
        consecutiveValidationFailureCounter.set(0)

        return try {
            logger.info(
                "🛠️ tool.exec sid={} step={} tool={} args={}",
                context.sid,
                context.step,
                toolCall.method,
                toolCall.arguments
            )

            val toolCallResult = toolExecutor.execute(actionDescription, "resolve, #$step")
            // Discuss: should we sync browser state after tool call immediately? probably not.
            // stateManager.syncBrowserUseState(context)

            circuitBreaker.recordSuccess(CircuitBreaker.FailureType.EXECUTION_FAILURE)
            consecutiveFailureCounter.set(0)
            val summary = "✅ ${toolCall.method} executed successfully"

            stateManager.addTrace(
                context.agentState,
                mapOf("tool" to toolCall.method),
                event = "toolExecOk",
                message = summary
            )
            DetailedActResult(actionDescription, toolCallResult, success = true, summary)
        } catch (e: Exception) {
            val failures = try {
                circuitBreaker.recordFailure(CircuitBreaker.FailureType.EXECUTION_FAILURE)
            } catch (cbError: CircuitBreakerTrippedException) {
                throw PerceptiveAgentError.PermanentError(cbError.message ?: "Circuit breaker tripped", cbError)
            }
            consecutiveFailureCounter.set(failures)
            logger.error(
                "🛠️❌ tool.exec.fail sid={} step={} failures={} msg={}",
                context.sid,
                context.step,
                failures,
                e.message,
                e
            )
            stateManager.addTrace(
                context.agentState,
                mapOf("tool" to toolCall.method),
                event = "toolExecUnexpectedFail",
                message = "💥 unexpected failure"
            )
            null
        }
    }

    protected suspend fun updateTodo(context: ExecutionContext, actResult: ActResult) {
        val actionDescription = actResult.detail?.actionDescription
        requireNotNull(actionDescription) { "actionDescription should be set in actResult.additionalVariables" }
        updateTodo(context, actionDescription)
    }

    protected suspend fun updateTodo(context: ExecutionContext, actionDescription: ActionDescription) {
        if (!config.enableTodoWrites) return
        val sid = context.sessionId
        val step = context.step
        val toolCall = actionDescription.toolCall
        val observeElement = actionDescription.observeElement
        val progressInterval = config.todoProgressWriteEveryNSteps.coerceAtLeast(1)
        val writeEveryNStepsHit = progressInterval == 1 || step % progressInterval == 0
        val shouldWrite = config.todoWriteProgressEveryStep || writeEveryNStepsHit
        if (!shouldWrite) return
        val urlNow0 = activeDriver.currentUrl()
        runCatching {
            val appended = todo.appendProgress(step, toolCall, observeElement, urlNow0, actionDescription.summary)
            if (appended) {
                todo.updateProgressCounter()
            }
            if (config.todoEnableAutoCheck && config.todoTagsFromToolCall) {
                val tags = todo.buildTags(toolCall, urlNow0)
                if (tags.isNotEmpty()) todo.markPlanItemDoneByTags(tags)
            }
        }.onFailure { e -> slogger.logError("📝❌ todo.progress.fail", e, sid) }
    }

    protected fun performMemoryCleanup(context: ExecutionContext) {
        try {
            synchronized(stateManager) {
                if (stateHistory.states.size > config.maxHistorySize) {
                    val toRemove = stateHistory.states.size - config.maxHistorySize + 10
                    stateManager.clearUpHistory(toRemove)
                }
            }
            actionValidator.clearCache()
            logger.info("🧹 mem.cleanup sid={} step={} historySize={}", context.sid, context.step, stateHistory.size)
        } catch (e: Exception) {
            logger.error("🧹❌ mem.cleanup.fail sid={} msg={}", context.sid, e.message, e)
        }
    }

    protected suspend fun summarize(goal: String, ctxIn: ExecutionContext): SummarizeResult {
        val step = ctxIn.step + 1
        val context = stateManager.buildExecutionContext(goal, step, event = "summary", baseContext = ctxIn)
        stateManager.setActiveContext(context)

        return try {
            val (system, user) = promptBuilder.buildSummaryPrompt(goal, stateHistory)
            slogger.info("📝⏳ Generating final summary", context)
            val response = cta.chatModel.callUmSm(user, system)
            slogger.info(
                "📝✅ Summary generated successfully", context,
                mapOf("responseLength" to response.content.length, "responseState" to response.state)
            )
            SummarizeResult(context, response)
        } catch (e: Exception) {
            slogger.logError("📝❌ Summary generation failed", e, context.sessionId)
            SummarizeResult(
                context,
                modelResponse = ModelResponse("Failed to generate summary: ${e.message}", ResponseState.OTHER)
            )
        }
    }

    data class SummarizeResult(
        val context: ExecutionContext,
        val modelResponse: ModelResponse
    )

    protected suspend fun generateFinalSummary(instruction: String, context: ExecutionContext): SummarizeResult {
        return try {
            val result = summarize(instruction, context)
            stateManager.addTrace(
                context.agentState,
                mapOf("summaryPreview" to result.modelResponse.content.take(200)),
                event = "final",
                message = "🧾 FINAL"
            )
            persistTranscript(instruction, result.modelResponse, context)
            result
        } catch (e: Exception) {
            logger.error("📝❌ agent.summary.fail sid={} msg={}", context.sid, e.message, e)
            SummarizeResult(
                context = context,
                ModelResponse("Failed to generate summary: ${e.message}", ResponseState.OTHER)
            )
        }
    }

    protected fun persistTranscript(instruction: String, finalResp: ModelResponse, context: ExecutionContext) {
        runCatching {
            val ts = Instant.now().toEpochMilli()
            val path = baseDir.resolve("session-${ts}.log")
            slogger.info("🧾💾 Persisting execution transcript", context)
            val sb = StringBuilder()
            sb.appendLine("SESSION_ID: $uuid")
            sb.appendLine("TIMESTAMP: ${Instant.now()}")
            sb.appendLine("INSTRUCTION: $instruction")
            sb.appendLine("RESPONSE_STATE: ${finalResp.state}")
            sb.appendLine("EXECUTION_HISTORY:")
            stateHistory.states.forEach { sb.appendLine(it) }
            sb.appendLine()
            sb.appendLine("FINAL_SUMMARY:")
            sb.appendLine(finalResp.content)
            sb.appendLine()
            sb.appendLine("PERFORMANCE_METRICS:")
            sb.appendLine("Total steps: ${performanceMetrics.totalSteps}")
            sb.appendLine("Successful actions: ${performanceMetrics.successfulActions}")
            sb.appendLine("Failed actions: ${performanceMetrics.failedActions}")
            sb.appendLine("Retry count: ${retryCounter.get()}")
            sb.appendLine("Consecutive failures: ${consecutiveFailureCounter.get()}")
            Files.writeString(path, sb)
            slogger.info(
                "🧾✅ Transcript persisted successfully",
                context,
                mapOf("lines" to stateHistory.size + 10, "path" to path.toUri())
            )
        }.onFailure { e -> slogger.logError("🧾❌ Failed to persist transcript", e, context.sessionId) }
    }

    protected suspend fun handleConsecutiveNoOps(consecutiveNoOps: Int, context: ExecutionContext): Boolean {
        val step = context.step
        stateManager.addTrace(
            context.agentState,
            mapOf("step" to step, "consecutive" to consecutiveNoOps),
            event = "noop",
            message = "🕒 no-op"
        )
        logger.info("🕒 noop sid={} step={} consecutive={}", context.sid, step, consecutiveNoOps)
        if (consecutiveNoOps >= config.consecutiveNoOpLimit) {
            logger.info("⛔ noop.stop sid={} step={} limit={}", context.sid, step, config.consecutiveNoOpLimit)
            return true
        }
        if (isClosed) return true
        val delayMs = calculateConsecutiveNoOpDelay(consecutiveNoOps)
        delay(delayMs)
        val job = currentCoroutineContext()[Job]
        if (job == null || !job.isActive) {
            logger.info("🕒 noop cancelled sid={} step={}", context.sid, step)
            return true
        }
        return false
    }

    protected fun calculateConsecutiveNoOpDelay(consecutiveNoOps: Int): Long {
        val baseDelay = 250L
        val exponentialDelay = baseDelay * consecutiveNoOps
        return min(exponentialDelay, 5000L)
    }

    protected fun updatePerformanceMetrics(step: Int, stepStartTime: Instant, success: Boolean) {
        val stepTime = Duration.between(stepStartTime, Instant.now()).toMillis()
        stepExecutionTimes[step] = stepTime
        performanceMetrics.totalSteps += 1
        if (success) performanceMetrics.successfulActions += 1 else performanceMetrics.failedActions += 1
    }

    protected fun calculateAdaptiveDelay(): Long {
        if (!config.enableAdaptiveDelays) return 100L
        val avgStepTime = stepExecutionTimes.values.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        return when {
            avgStepTime < 500 -> 50L
            avgStepTime < 2000 -> 100L
            else -> 200L
        }
    }

    /**
     * Save a checkpoint at specific intervals (legacy behavior).
     */
    protected fun saveCheckpoint(context: ExecutionContext) {
        if (!config.enableCheckpointing) return
        saveCheckpointInternal(context, consecutiveNoOps = 0, isComplete = false)
    }

    /**
     * Save a step-level checkpoint with full execution state for resume support.
     *
     * @param context The current execution context
     * @param consecutiveNoOps The current count of consecutive no-op steps
     * @param isComplete Whether the task has been completed
     */
    protected fun saveStepCheckpoint(context: ExecutionContext, consecutiveNoOps: Int, isComplete: Boolean) {
        if (!config.enableStepCheckpointing) return
        saveCheckpointInternal(context, consecutiveNoOps, isComplete)
    }

    private fun saveCheckpointInternal(context: ExecutionContext, consecutiveNoOps: Int, isComplete: Boolean) {
        val checkpoint = AgentCheckpoint(
            sessionId = context.sessionId,
            currentStep = context.step,
            instruction = context.instruction,
            targetUrl = context.targetUrl,
            recentStateHistory = stateHistory.states.takeLast(20).map { AgentStateSnapshot.from(it) },
            totalSteps = performanceMetrics.totalSteps,
            successfulActions = performanceMetrics.successfulActions,
            failedActions = performanceMetrics.failedActions,
            failureCounts = circuitBreaker.getFailureCounts().mapKeys { it.key.name },
            configSnapshot = mapOf(
                "maxSteps" to config.maxSteps,
                "maxRetries" to config.maxRetries,
                "consecutiveNoOpLimit" to config.consecutiveNoOpLimit
            ),
            metadata = mapOf(
                "startTime" to startTime.toString()
            ),
            consecutiveNoOps = consecutiveNoOps,
            isComplete = isComplete,
            lastEvent = context.event,
            agentUuid = uuid.toString()
        )
        val path = checkpointManager.save(checkpoint)
        logger.info("💾 checkpoint.saved sid={} step={} path={}", context.sid, context.step, path)
        checkpointManager.pruneOldCheckpoints(context.sessionId, config.maxCheckpointsPerSession)
    }

    /**
     * Load a checkpoint for resumption.
     *
     * @param sessionId The session ID to load
     * @return The loaded checkpoint, or null if not found
     */
    fun loadCheckpoint(sessionId: String): AgentCheckpoint? {
        return checkpointManager.load(sessionId)
    }

    /**
     * Check if a checkpoint exists for the given session.
     *
     * @param sessionId The session ID to check
     * @return true if a checkpoint exists
     */
    fun hasCheckpoint(sessionId: String): Boolean {
        return checkpointManager.exists(sessionId)
    }

    /**
     * Resume execution from a previously saved checkpoint.
     *
     * This method loads the checkpoint from the given sessionId and continues
     * the task execution from where it left off.
     *
     * @param sessionId The session ID of the checkpoint to resume from
     * @return The agent history after resumption, or empty history if checkpoint not found
     */
    suspend fun resumeFromCheckpoint(sessionId: String): AgentHistory {
        val checkpoint = loadCheckpoint(sessionId)
        if (checkpoint == null) {
            logger.warn("💾❌ No checkpoint found for session: {}", sessionId)
            return stateHistory
        }

        if (checkpoint.isComplete) {
            logger.info("💾✅ Checkpoint already completed for session: {}", sessionId)
            return stateHistory
        }

        logger.info(
            "💾🔄 Resuming from checkpoint sid={} step={} instruction='{}'",
            sessionId.take(8), checkpoint.currentStep, checkpoint.instruction.take(50)
        )

        // Restore performance metrics
        performanceMetrics.totalSteps = checkpoint.totalSteps
        performanceMetrics.successfulActions = checkpoint.successfulActions
        performanceMetrics.failedActions = checkpoint.failedActions

        // Restore circuit breaker state
        checkpoint.failureCounts.forEach { (key, count) ->
            runCatching {
                val failureType = CircuitBreaker.FailureType.valueOf(key)
                repeat(count) { circuitBreaker.recordFailure(failureType) }
            }
        }

        // Create action options and resume
        val action = ActionOptions(action = checkpoint.instruction)
        return resumeRun(action, checkpoint)
    }

    /**
     * Resume run from a checkpoint with the given action options.
     */
    private suspend fun resumeRun(action: ActionOptions, checkpoint: AgentCheckpoint): AgentHistory {
        if (isClosed) {
            return stateHistory
        }

        try {
            withContext(agentScope.coroutineContext) {
                resumeResolveInCoroutine(action, checkpoint)
            }
        } catch (_: CancellationException) {
            logger.info("Cancelled due to cancellation during resume")
        } finally {
            stateManager.writeProcessTrace()
        }

        return stateHistory
    }

    /**
     * Resume resolve loop from a checkpoint.
     */
    private suspend fun resumeResolveInCoroutine(action: ActionOptions, checkpoint: AgentCheckpoint): ResolveResult {
        val instruction = action.action
        val baseContext = stateManager.buildBaseExecutionContext(action, "resume-init")
        stateManager.setActiveContext(baseContext)
        val sessionStartTime = baseContext.stepStartTime

        stateManager.addTrace(
            baseContext.agentState,
            mapOf(
                "session" to baseContext.sid,
                "goal" to Strings.compactInline(instruction, 160),
                "resumeFromStep" to checkpoint.currentStep,
                "maxSteps" to config.maxSteps
            ),
            event = "resumeStart",
            message = "🔄 resume START from step ${checkpoint.currentStep}"
        )

        val maxPossibleDelays = (0 until config.maxRetries).fold(0L) { acc, i -> acc + calculateRetryDelay(i) }
        val effectiveTimeout = config.resolveTimeoutMs + maxPossibleDelays

        return try {
            val result = withTimeout(effectiveTimeout) {
                doResumeResolveProblem(action, baseContext, checkpoint)
            }

            val dur = Duration.between(sessionStartTime, Instant.now()).toMillis()
            stateManager.addTrace(
                result.context.agentState, mapOf(
                    "session" to baseContext.sid,
                    "success" to result.result.success, "durationMs" to dur
                ), event = "resumeDone", message = "✅ resume DONE"
            )

            result
        } catch (_: TimeoutCancellationException) {
            val msg = "⏳ Resume timed out after ${effectiveTimeout}ms: $instruction"
            stateManager.addTrace(
                baseContext.agentState, mapOf(
                    "timeoutMs" to effectiveTimeout, "instruction" to Strings.compactInline(instruction, 160)
                ),
                event = "resumeTimeout",
                message = "⏳ resume TIMEOUT"
            )
            ResolveResult(baseContext, ActResult(success = false, message = msg, action = instruction))
        }
    }

    /**
     * Continue problem resolution from a checkpoint.
     */
    private suspend fun doResumeResolveProblem(
        initActionOptions: ActionOptions, initContext: ExecutionContext, checkpoint: AgentCheckpoint
    ): ResolveResult {
        var consecutiveNoOps = checkpoint.consecutiveNoOps
        var context = initContext
        val startTime = Instant.now()

        // Start from the next step after the checkpoint
        val resumeFromStep = checkpoint.currentStep

        logger.info(
            "🔄 resume.start sid={} resumeStep={} url={} instr='{}'",
            context.sid, resumeFromStep, checkpoint.targetUrl, Strings.compactInline(checkpoint.instruction, 100)
        )

        try {
            val action = initActionOptions.copy(fromResolve = true)

            // Continue from checkpoint step
            while (!isClosed && context.step < config.maxSteps) {
                val stepResult: StepProcessingResult
                try {
                    context = prepareStep(action, context, consecutiveNoOps)

                    // Skip steps that were already completed (use < to continue from the next step after checkpoint)
                    if (context.step < resumeFromStep) {
                        logger.info("⏭️ Skipping already completed step {} (resume from {})", context.step, resumeFromStep)
                        // Preserve consecutive no-ops count from checkpoint when skipping
                        continue
                    }

                    stepResult = step(action, context, consecutiveNoOps)

                    context = stepResult.context
                    consecutiveNoOps = stepResult.consecutiveNoOps
                } finally {
                    stateManager.addToHistory(context.agentState)
                }

                // Save step checkpoint after each step
                if (config.enableStepCheckpointing) {
                    runCatching { saveStepCheckpoint(context, consecutiveNoOps, stepResult.shouldStop) }
                        .onFailure { e -> logger.warn("💾❌ step.checkpoint.fail sid={} step={} msg={}", context.sid, context.step, e.message) }
                }

                if (stepResult.shouldStop) {
                    break
                }
            }

            val actResult = buildFinalActResult(initContext.instruction, context, startTime)
            return ResolveResult(context, actResult)
        } catch (_: CancellationException) {
            logger.info("🛑 [USER interrupted during resume] sid={} steps={}", context.sid, context.step)
            return ResolveResult(context, ActResult(success = false, message = "USER interrupted", action = initContext.instruction))
        } catch (e: Exception) {
            throw handleResolutionFailure(e, context, startTime)
        }
    }

    protected fun shouldTerminate(actionDescription: ActionDescription? = null): Boolean {
        return when {
            actionDescription == null -> false
            actionDescription.isComplete -> true
            actionDescription.expression?.contains("agent.done") == true -> true
            else -> false
        }
    }

    protected suspend fun onTaskCompletion(actResult: ActResult, context: ExecutionContext) {
        val actionDescription = actResult.detail?.actionDescription ?: return
        onTaskCompletion(actionDescription, context)
    }

    protected suspend fun onTaskCompletion(action: ActionDescription, context: ExecutionContext) {
        val step = context.step
        val sid = context.sessionId

        require(action.isComplete) { "Required action.isComplete" }
        // require(context.agentState.isComplete) { "Required context.agentState.isComplete" }
        context.agentState.also {
            it.isComplete = true
            it.summary = action.summary
            it.keyFindings = action.keyFindings
            it.nextSuggestions = action.nextSuggestions
        }

        logger.info("✅ task.complete sid={} step={} complete={}", sid.take(8), step, true)
        stateManager.addTrace(context.agentState, event = "complete", message = "#${step} complete")

        val files = fs.listOSFiles().filterNot { it.fileName.toString().contains("todolist.md") }
        if (files.isNotEmpty()) {
            logger.info("Agent files: \n{}", files.joinToString("\n") { it.toUri().toString() })
        } else {
            logger.info("No files used by this agent")
        }

        if (config.enableTodoWrites) {
            runCatching { todo.onTaskCompletion(context.instruction) }
                .onFailure { e -> slogger.logError("📝❌ todo.complete.fail", e, sid) }
        }
    }

    private suspend fun buildFinalActResult(
        instruction: String, cxtIn: ExecutionContext, startTime: Instant
    ): ActResult {
        val executionTime = Duration.between(startTime, Instant.now())

        logger.info("✅ agent.done sid={} steps={} dur={}", cxtIn.sid, cxtIn.step, executionTime)

        val result = generateFinalSummary(instruction, cxtIn)

        val summary = result.modelResponse
        val context = result.context
        val ok = summary.state != ResponseState.OTHER

        val agentState = result.context.agentState.also {
            it.instruction = instruction
            it.isComplete = true
            it.domain = "summary"
            it.event = "summary"
            it.method = "summary"
            it.step = context.step
        }
//        stateManager.addToHistory(agentState)

        return ActResult(
            success = ok,
            message = summary.content,
            action = context.instruction,
            result = context.agentState.toolCallResult
        )
    }

    private fun handleResolutionFailure(
        e: Exception, context: ExecutionContext, startTime: Instant
    ): PerceptiveAgentError {
        val executionTime = Duration.between(startTime, Instant.now())
        logger.error(
            "💥 agent.fail sid={} steps={} dur={} err={}",
            context.sid, context.step, executionTime, e.message, e
        )
        runCatching { stateManager.removeLastIfStep(context.step) }
            .onFailure { logger.warn("⚠️ rollback failed sid={} step={} msg={}", context.sid, context.step, e.message) }
        return classifyError(e, context.step)
    }
}
