package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.chrome.dom.util.DomDebug
import ai.platon.pulsar.agentic.*
import ai.platon.pulsar.agentic.inference.RequestTokenLimitExceededException
import ai.platon.pulsar.agentic.inference.TokenBudgetExceededException
import ai.platon.pulsar.agentic.inference.chat.AgentToolCallLoop
import ai.platon.pulsar.agentic.inference.chat.ToolLoopCompressor
import ai.platon.pulsar.agentic.inference.detail.*
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.builtin.TaskCompletion
import ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.agentic.tools.specs.ToolSpecification
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.Pson
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

// File-level constants previously in companion object
private const val COMPACT_INLINE_SESSION_LENGTH = 160
private const val COMPACT_INLINE_INSTRUCTION_LENGTH = 100
private const val CLI_SYSTEM_PROMPT_MAX_CHARS = 120_000
private const val CLI_CONTINUE_NUDGE =
    "If the task is fully finished, call system.taskComplete with the final report now. " +
        "Otherwise continue working with tools. Do not answer in plain text unless you are done."
/** Explicit tool domains exposed to the CLI engine (design v0.2 §4.4). */
private val CLI_ENGINE_DOMAINS = setOf("coding", "cli", "system")

open class RobustBrowserAgent(
    session: AgenticSession, val maxSteps: Int = 100, config: AgentConfig = AgentConfig(maxSteps = maxSteps)
) : BasicBrowserAgent(session, config) {
    private val logger = getLogger(RobustBrowserAgent::class)
    private val slogger = StructuredAgentLogger(logger, config)

    /**
     * Per-task override for [AgentConfig.consecutiveNoOpLimit], set by
     * [ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner] when the caller
     * passes a noop limit with the task (e.g. `agent run --noop-limit 10`). Long coding
     * chains (compile-fix-test loops) benefit from a higher tolerance than the default.
     */
    @Volatile
    var noopLimitOverride: Int? = null

    /**
     * Per-task engine override (design v0.2), set by
     * [ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner] when the
     * caller chooses the CLI tool-loop engine. Falls back to [AgentConfig.runEngine].
     */
    @Volatile
    var runEngineOverride: RunEngine? = null

    internal val effectiveRunEngine: RunEngine get() = runEngineOverride ?: config.runEngine

    /**
     * Per-agent scratch workspace for CLI-engine (browser) tasks. Helper files
     * the model writes via coding.* and cli.run land here — never the backend
     * working directory / repository root (workspace isolation, P1).
     */
    internal val agentWorkspaceDir: Path by lazy {
        baseDir.resolve("workspace").also { Files.createDirectories(it) }
    }

    private val noopLimit: Int get() = (noopLimitOverride ?: config.consecutiveNoOpLimit).coerceAtLeast(1)

    /**
     * Coding mode override (see [BasicBrowserAgent.codingMode]): set by
     * [ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner] when the task is
     * detected as a pure coding task. Skips the search-engine navigation and screenshots.
     */
    @Volatile
    override var codingMode: Boolean = false

    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    // A dedicated scope for all agent work so close() can cancel promptly
    private val agentJob = SupervisorJob()
    private val agentScope = CoroutineScope(Dispatchers.Default + agentJob)

    private val stepExecutionTimes = ConcurrentHashMap<Int, Long>()

    // New components for better separation of concerns
    private val circuitBreaker = CircuitBreaker(
        maxLLMFailures = config.maxConsecutiveLLMFailures,
        maxValidationFailures = config.maxConsecutiveValidationFailures,
        maxExecutionFailures = 3
    )
    private val retryStrategy = RetryStrategy(
        maxRetries = config.maxRetries, baseDelayMs = config.baseRetryDelayMs, maxDelayMs = config.maxRetryDelayMs
    )
    private val retryCounter = AtomicInteger(0)

    private val transcriptPersister = TranscriptPersister(
        stateManager = stateManager,
        stateHistory = stateHistory,
        slogger = slogger,
        agentUuid = uuid,
        circuitBreaker = circuitBreaker,
        retryCounter = retryCounter,
    )

    /**
     * High-level problem resolution entry. Builds an ActionOptions and delegates to resolve(ActionOptions).
     */
    override suspend fun run(task: String): AgentHistory {
        val opts = ActionOptions(action = task)
        return run(opts)
    }

    /**
     * Run an autonomous loop (observe -> act -> ...) attempting to fulfill the user goal described
     * in the ActionOptions. Applies retry and timeout strategies; records structured traces but keeps
     * stateHistory focused on executed tool actions only.
     *
     * @param action The action options containing the user's goal and configuration
     * @return The history of THIS run: a detached snapshot scoped by the run's execution session,
     * so callers never see other runs' states and later trims of the shared history cannot
     * mutate this result.
     * @throws CancellationException if the agent is closed or the operation is canceled
     */
    override suspend fun run(action: ActionOptions): AgentHistory {
        _lastRunSessionId = null
        resetInnerToolExecutions()
        onWillRun(action)

        // The first context created below starts this run's execution session; all
        // contexts (and their agent states) of this run share that session id.
        val contextCountBefore = stateManager.contexts.size

        try {
            val ctx = agentScope.coroutineContext.minusKey(Job)

            val result = withContext(ctx) { resolveProblemInCoroutine(action) }

            onDidRun(action, result.result)

            // Surface abnormal terminations (no-op limit, max steps, exhausted
            // retries) to the caller instead of returning a history that LOOKS
            // successful. Runners (e.g. StatefulAgentRunner) mark the task
            // failed when the exception propagates — without this, MAX_STEPS
            // aborts were reported as "completed" with status 200.
            result.result.exception?.let { throw it }
        } catch (e: CancellationException) {
            logger.info("🛑 run.cancelled reason={}", e.message ?: "user cancellation")
            throw e
        } finally {
            stateManager.writeAllProcessTrace()
            _lastRunSessionId = stateManager.contexts.getOrNull(contextCountBefore)?.sessionId
        }

        return stateHistory.snapshotFor(_lastRunSessionId)
    }

    /**
     * Executes a single observe->act cycle for a supplied ActionOptions. Times out after actTimeoutMs
     * to prevent indefinite hangs. Model may produce multiple candidate tool calls internally; only
     * one successful execution is recorded in stateHistory.
     *
     * @param action The action to execute
     * @return Result of the action execution
     */
    override suspend fun act(action: ActionOptions): ActResult {
        return try {
            val ctx = agentScope.coroutineContext.minusKey(Job)
            withContext(ctx) {
                super.act(action)
            }
        } catch (e: CancellationException) {
            logger.info("🛑 act.cancelled action={}", action.action.take(50))
            ActResultHelper.failed(e, action.action)
        }
    }

    /**
     * Executes a tool call derived from a prior observation result. Performs patching (selector/url),
     * validation, and updates AgentState history on success or failure.
     *
     * @param observe The observation result containing the action to execute
     * @return Result of the action execution
     */
    override suspend fun act(observe: ObserveResult): ActResult {
        return try {
            val ctx = agentScope.coroutineContext.minusKey(Job)
            withContext(ctx) {
                super.act(observe)
            }
        } catch (e: CancellationException) {
            logger.info("🛑 act.cancelled instruction={}", observe.agentState.instruction.take(50))
            ActResultHelper.failed(e, action = observe.actionDescription?.instruction ?: "")
        }
    }

    /**
     * Structured extraction: builds a rich prompt with DOM snapshot & optional JSON schema; performs
     * two-stage LLM calls (extract + metadata) and merges results with token/time metrics.
     *
     * @param options Extraction options including schema and target elements
     * @return Extraction result with structured data
     */
    override suspend fun extract(options: ExtractOptions): ExtractResult {
        return try {
            val ctx = agentScope.coroutineContext.minusKey(Job)
            withContext(ctx) {
                super.extract(options)
            }
        } catch (e: CancellationException) {
            logger.info(
                "🛑 extract.cancelled instruction={}", options.instruction.take(50)
            )
            ExtractResult(
                success = false,
                message = "USER interrupted: ${e.message}",
                data = JsonNodeFactory.instance.objectNode()
            )
        }
    }

    /**
     * Observes the page given an instruction, returning zero or more ObserveResult objects describing
     * candidate elements and potential actions (if returnAction=true).
     */
    override suspend fun observe(options: ObserveOptions): List<ObserveResult> {
        val context = stateManager.getOrCreateActiveContext(options, "observe")

        val ctx = agentScope.coroutineContext.minusKey(Job)

        if (!options.fromResolve) {
            return withContext(ctx) {
                super.observe(options)
            }
        }

        try {
            val result = withContext(ctx) {
                onWillObserve(options)
                doObserve(options)
            }

            circuitBreaker.recordSuccess(CircuitBreaker.FailureType.LLM_FAILURE)

            onDidObserve(options, result)

            return result.observeResults
        } catch (e: Exception) {
            handleObserveException(e, context)
            return emptyList()
        }
    }

    fun stop() = close()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Record close event before cancelling operations to ensure it's captured
            runCatching {
                val last = stateHistory.states.lastOrNull()
                stateManager.addTrace(last, event = "userClose", message = "🛑 USER CLOSE")
            }.onFailure { logger.warn("Failed to record close trace: ${it.message}") }

            // Cancel agent job - this will propagate cancellation to all child jobs
            runCatching {
                agentJob.cancel(CancellationException("USER interrupted via close()"))
            }.onFailure {
                logger.warn("Agent job cancellation error: ${it.message}")
            }

            // Cancel any background CLI jobs (long crawl/swarm processes) so the
            // agent never leaves orphan subprocesses behind.
            if (isAgentToolManagerInitialized) {
                runCatching { agentToolManager.closeCliJobs() }
                    .onFailure { logger.warn("Failed to close CLI jobs: ${it.message}") }
            }

            // Close bound WebDriver if exists
            runCatching {
                session.boundDriver?.let { driver ->
                    driver.close()
                    session.unbindDriver(driver)
                }
            }.onFailure { logger.warn("Failed to close bound WebDriver: ${it.message}") }
        }
    }

    /**
     * Returns a concise summary of the latest agent state; if no history exists, returns a placeholder text.
     */
    override fun toString(): String {
        return stateHistory.states.lastOrNull()?.toString() ?: "(no history)"
    }

    // ─── Resolution pipeline ──────────────────────────────────────────────────

    private suspend fun resolveProblemInCoroutine(action: ActionOptions): ResolveResult {
        val instruction = action.action
        val baseContext = stateManager.buildBaseExecutionContext(action, "resolve-init")
        val sessionStartTime = baseContext.stepStartTime

        // Add start history for better traceability (meta record only)
        stateManager.addTrace(
            baseContext.agentState, event = "resolveStart", items = mapOf(
                "session" to baseContext.sid,
                "goal" to Strings.compactInline(instruction, COMPACT_INLINE_SESSION_LENGTH),
                "maxSteps" to config.maxSteps,
                "maxRetries" to config.maxRetries
            ), message = "🚀 resolve START"
        )

        // Overall timeout to prevent indefinite hangs for a full resolve session
        // Calculate effective timeout accounting for potential retry delays
        val maxPossibleDelays = (0 until config.maxRetries).fold(0L) { acc, i -> acc + calculateRetryDelay(i) }
        val effectiveTimeout = config.resolveTimeoutMs + maxPossibleDelays

        return try {
            val result = withTimeout(effectiveTimeout.milliseconds) {
                resolveProblemWithRetry(action, baseContext)
            }

            val dur = Duration.between(sessionStartTime, Instant.now()).toMillis()
            stateManager.addTrace(
                result.context.agentState, event = "resolveDone", items = mapOf(
                    "session" to baseContext.sid, "success" to result.result.isSuccess, "durationMs" to dur
                ), message = "✅ resolve DONE"
            )

            result
        } catch (e: TimeoutCancellationException) {
            stateManager.addTrace(
                baseContext.agentState, event = "resolveTimeout", items = mapOf(
                    "timeoutMs" to effectiveTimeout,
                    "instruction" to Strings.compactInline(instruction, COMPACT_INLINE_SESSION_LENGTH)
                ), message = "⏳ resolve TIMEOUT"
            )
            val actResult = ActResultHelper.failed(e, action = instruction)
            ResolveResult(baseContext, actResult)
        } finally {
            // clear history so the next task will have a clean operation trace for summary.
            // but we do not clear process trace which will be kept to trace all operations and states.
            // 20251122: DO NOT CLEAR HISTORY, is you want to run new task with a new context, use TaskScopedBrowserPerceptiveAgent
            // instead.
            // stateManager.clearHistory()
        }
    }

    private suspend fun resolveProblemWithRetry(action: ActionOptions, context: ExecutionContext): ResolveResult {
        var lastError: Exception? = null
        val sid = context.sid
        val activeContext = stateManager.getActiveContext()

        for (attempt in 0..config.maxRetries) {
            try {
                val result = doRunAgentLoop(action, activeContext, attempt)
                return result
            } catch (e: TokenBudgetExceededException) {
                // Budget breach is permanent — do not retry (each retry would
                // throw immediately anyway since the budget is already exceeded).
                logger.error("🛑 resolve.budget.sid={} msg={}", sid, e.message)
                return ResolveResult(activeContext, ActResultHelper.failed(e, action = action.action))
            } catch (e: RequestTokenLimitExceededException) {
                // Per-request limit breach halts the task: report status and
                // wait for the user to raise the limit before re-launching.
                logger.error("🛑 resolve.request-token-limit.sid={} msg={}", sid, e.message)
                return ResolveResult(activeContext, ActResultHelper.failed(e, action = action.action))
            } catch (e: Exception) {
                lastError = e
                logger.error("💥 resolve.unexpected attempt={} sid={} msg={}", attempt + 1, sid, e.message, e)

                cleanupPartialState(activeContext)
                stateManager.buildBaseExecutionContext(action, "resolve-init-recovery")
            }
        }

        val actResult = ActResultHelper.failed(lastError ?: Exception("Unknown error"), action.action)

        return ResolveResult(activeContext, actResult)
    }

    private suspend fun doRunAgentLoop(
        initActionOptions: ActionOptions, initContext: ExecutionContext, attempt: Int
    ): ResolveResult {
        initializeResolution(initContext, attempt)
        if (effectiveRunEngine == RunEngine.CLI_TOOL_LOOP) {
            return doRunCliAgentLoop(initActionOptions, initContext, attempt)
        }
        var consecutiveNoOps = 0
        // Text-only stall fuse: consecutive responses without ANY tool call and
        // without a completion marker. Text-only responses deliberately don't
        // count as no-ops (they're legitimate reasoning turns), but an agent
        // that only reasons without ever acting stalls forever — abort it.
        var consecutiveTextOnly = 0
        var lastStopReason: StopReason? = null
        var context = initContext
        val startTime = Instant.now()
        try {
            val action = initActionOptions.copy(fromRunLoop = true)

            while (!isClosed && context.step < config.maxSteps) {
                val stepResult: StepProcessingResult
                try {
                    val prepared = prepareStep(action, context, consecutiveNoOps)
                    context = prepared.context
                    consecutiveNoOps = prepared.noOps

                    if (prepared.stop) {
                        // The page state froze despite repeated browser interactions —
                        // the loop stops here without executing another act.
                        stepResult = StepProcessingResult(context, consecutiveNoOps, true, StopReason.NOOP_LIMIT)
                    } else {
                        stepResult = step(action, context, consecutiveNoOps)

                        require(stepResult.context.step == context.step) { "Step check failed" }
                        require(stepResult.context.agentState.actionDescription != null) { "Check failed: stepResult.context.agentState.actionDescription != null" }

                        context = stepResult.context
                        consecutiveNoOps = stepResult.consecutiveNoOps
                    }
                } finally {
                    stateManager.addToHistory(context.agentState)
                }

                if (stepResult.shouldStop) {
                    lastStopReason = stepResult.stopReason
                    break
                }

                // Text-only stall fuse (browser4.agent.textOnlyStallLimit, 0 = disabled).
                // A step that executed ≥1 internal tool (loop tool calls, e.g.
                // coding.read) is real work even when the final response carries
                // no ToolCall (overflow steps) — never count it as text idling.
                val lastToolCall = context.agentState.actionDescription?.toolCall
                val internalToolsExecuted = context.agentState.actionDescription?.internalToolsExecuted == true
                consecutiveTextOnly = nextTextOnlyStallCount(consecutiveTextOnly, lastToolCall, internalToolsExecuted)
                if (lastToolCall == null && !internalToolsExecuted) {
                    // `--noop-limit` (noopLimitOverride) also raises this fuse so the
                    // two no-progress fuses stay coupled when the operator tunes one.
                    val stallLimit = if (noopLimitOverride != null) noopLimit else config.textOnlyStallLimit
                    if (stallLimit > 0 && consecutiveTextOnly >= stallLimit) {
                        logger.info(
                            "🧵 textOnly.stall sid={} step={} consecutive={} limit={}",
                            context.sid, context.step, consecutiveTextOnly, stallLimit
                        )
                        lastStopReason = StopReason.NOOP_LIMIT
                        break
                    }
                }
            }

            // The loop exited without an explicit completion: it hit the no-op limit or
            // maxSteps while the task was still unfinished — report it as a failure rather
            // than letting the final summary mask an abnormal termination.
            val stopReason = lastStopReason
                ?: if (context.step >= config.maxSteps) StopReason.MAX_STEPS else null

            val actResult = buildFinalActResult(initContext.instruction, context, startTime, stopReason)

            return ResolveResult(context, actResult)
        } catch (e: CancellationException) {
            logger.info(
                "🛑 doResolve.cancelled sid={} steps={} reason={}",
                context.sid,
                context.step,
                e.message ?: "user interruption"
            )

            val result = ActResultHelper.failed(e, initContext.instruction)
            return ResolveResult(context, result)
        } catch (e: Exception) {
            throw handleResolutionFailure(e, context, startTime)
        }
    }

    // ─── CLI tool-loop engine (design v0.2) ────────────────────────────────

    /**
     * Native function-calling loop that drives browser4-cli subprocesses via
     * `cli.run`, following the bundled SKILL.md. Completion is signalled by the
     * model calling `system.taskComplete` (no JSON parsing anywhere).
     */
    private suspend fun doRunCliAgentLoop(
        initActionOptions: ActionOptions, initContext: ExecutionContext, attempt: Int
    ): ResolveResult {
        initializeResolution(initContext, attempt)
        var context = initContext
        val startTime = Instant.now()
        val completionRef = AtomicReference<ActionDescription?>()
        val toolExecutions = AtomicInteger(0)
        try {
            val action = initActionOptions.copy(fromRunLoop = true)
            val loop = buildCliToolLoop(action.action, completionRef, toolExecutions)
            var messages: List<ChatMessage> = listOf(
                SystemMessage.from(cliAgentSystemPrompt()),
                UserMessage.from(action.action),
            )
            var textOnlyStreak = 0
            var turn = 0
            val maxTurns = config.maxSteps.coerceAtLeast(1)
            logger.info("🚀 cli-agent.start sid={} turns={} instr='{}'",
                context.sid, maxTurns, Strings.compactInline(action.action, 100))

            // Completion rules:
            // 1. Preferred: the model calls system.taskComplete.
            // 2. Fallback: the model worked (≥1 tool executed in this run) and
            //    answers twice in plain text — the second text is the final
            //    report (the finish-gate's zero-tool guard still rejects
            //    fabricated completions).
            // 3. Pure text-only streak with no tools ever executed = stall.
            while (!isClosed && turn < maxTurns) {
                val toolsBefore = toolExecutions.get()
                val response = withTimeout(config.llmInferenceTimeoutMs.milliseconds) {
                    loop.generate(messages)
                }
                val completion = completionRef.get()
                if (completion != null && completion.isDecidedComplete) {
                    return completeCliRun(completion, context)
                }
                val text = response.content
                val executed = toolExecutions.get() > toolsBefore
                // Internal tool-loop overflow (maxIterations exhausted) is real
                // work, not a stall: keep going with a fresh generate() call.
                val overflow = response.modelError != null
                if (executed || overflow) {
                    textOnlyStreak = 0
                } else {
                    textOnlyStreak++
                    // Real work happened earlier and the model answers in text
                    // again — accept it as the final report.
                    if (toolExecutions.get() > 0 && textOnlyStreak >= 2) {
                        logger.info("✅ cli-agent.text-completion sid={} tools={}",
                            context.sid, toolExecutions.get())
                        val textCompletion = ActionDescription(
                            instruction = action.action,
                            isDecidedComplete = true,
                            summary = text,
                        )
                        return completeCliRun(textCompletion, context)
                    }
                    if (config.textOnlyStallLimit > 0 && textOnlyStreak >= config.textOnlyStallLimit) {
                        logger.info("⛔ cli-agent.text-only-stall sid={} consecutive={}",
                            context.sid, textOnlyStreak)
                        break
                    }
                }
                messages = if (text.isBlank()) {
                    messages + UserMessage.from(CLI_CONTINUE_NUDGE)
                } else {
                    messages + AiMessage.from(text) + UserMessage.from(CLI_CONTINUE_NUDGE)
                }
                turn++
            }

            val completion = completionRef.get()
            if (completion != null && completion.isDecidedComplete) {
                return completeCliRun(completion, context)
            }
            val stopReason = if (turn >= maxTurns) StopReason.MAX_STEPS else StopReason.NOOP_LIMIT
            logger.info("⛔ cli-agent.no-completion sid={} stop={}", context.sid, stopReason)
            val actResult = buildFinalActResult(initContext.instruction, context, startTime, stopReason)
            return ResolveResult(context, actResult)
        } catch (e: CancellationException) {
            logger.info(
                "🛑 cli-agent.cancelled sid={} reason={}",
                context.sid, e.message ?: "user interruption"
            )
            return ResolveResult(context, ActResultHelper.failed(e, initContext.instruction))
        } catch (e: Exception) {
            throw handleResolutionFailure(e, context, startTime)
        }
    }

    private fun buildCliToolLoop(
        instruction: String,
        completionRef: AtomicReference<ActionDescription?>,
        toolExecutions: AtomicInteger,
    ): AgentToolCallLoop {
        val allSpecs = agentToolManager.getLangChain4jToolSpecifications()
        val allRegistry = agentToolManager.getLangChain4jToolRegistry()
        // Explicit domain whitelist (robust to tool-name renames): filter the
        // reverse registry by ToolSpec.domain, then keep matching specs.
        val registry = allRegistry.filterValues { it.domain in CLI_ENGINE_DOMAINS }
        val specs = allSpecs.filter { it.name() in registry.keys }
        val taskCompleteName = ToolSpecificationConverter.toolName("system", "taskComplete")
        logger.info("cli-agent tools exposed: {} (of {} total)", specs.size, allSpecs.size)
        return AgentToolCallLoop(
            model = cta.chatModel,
            toolSpecifications = specs,
            coordinator = ToolExecutionCoordinator(agentToolManager, registry),
            // Browser tasks routinely exceed the default 12-round internal cap
            // (open → snapshot → type → submit → wait → extract); give the CLI
            // engine a higher ceiling, overflow is handled by the outer loop.
            maxIterations = config.toolLoopMaxIterations.coerceAtLeast(20),
            requestTokenLimiter = cta.requestTokenLimiter,
            compressor = cliToolLoopCompressor(specs),
            onToolExecuted = { toolExecutions.incrementAndGet() },
            onToolRequest = { name, argsJson ->
                if (name == taskCompleteName) {
                    runCatching {
                        val completion = TaskCompletion.fromJson(argsJson)
                        completionRef.set(
                            ActionDescription(
                                instruction = instruction,
                                isDecidedComplete = true,
                                summary = completion.summary,
                                keyFindings = completion.keyFindings,
                                filesChanged = completion.filesChanged,
                                problems = completion.problems,
                            )
                        )
                    }.onFailure { logger.warn("cli-agent taskComplete parse failed: {}", it.message) }
                }
            },
        )
    }

    /** Auto context compression for the CLI tool loop (long multi-step tasks). */
    private fun cliToolLoopCompressor(
        specs: List<dev.langchain4j.agent.tool.ToolSpecification>,
    ): ToolLoopCompressor? {
        val conf = session.sessionConfig
        if (!conf.getBoolean("browser4.agent.toolLoop.compressionEnabled", true)) return null
        return ToolLoopCompressor(
            enabled = true,
            thresholdTokens = conf.getLong("browser4.agent.toolLoop.compressionThresholdTokens", 60_000L)
                .coerceAtLeast(1_000L),
            retainTokens = conf.getLong("browser4.agent.toolLoop.retainTokens", 24_000L)
                .coerceAtLeast(1_000L),
            pruneThresholdChars = conf.getLong("browser4.agent.toolLoop.pruneThresholdChars", 1_500L)
                .toInt().coerceAtLeast(100),
            pruneHeadChars = conf.getLong("browser4.agent.toolLoop.pruneHeadChars", 800L)
                .toInt().coerceAtLeast(0),
            pruneTailChars = conf.getLong("browser4.agent.toolLoop.pruneTailChars", 400L)
                .toInt().coerceAtLeast(0),
        ) { prefix -> summarizeCliLoop(prefix, specs) }
    }

    private suspend fun summarizeCliLoop(
        prefix: List<ChatMessage>,
        toolSpecifications: List<dev.langchain4j.agent.tool.ToolSpecification>,
    ): String {
        val request = ChatRequest.builder()
            .messages(prefix + UserMessage.from(ToolLoopCompressor.COMPACTION_INSTRUCTION))
            .toolSpecifications(toolSpecifications)
            .maxOutputTokens(2_048)
            .build()
        val response = cta.chatModel.langChainChat(request, "cta-compaction")
        return response.aiMessage().text() ?: ""
    }

    /**
     * Finish a CLI-engine run: run the shared completion pipeline and record the
     * completed state into the agent history so callers (StatefulAgentRunner)
     * can surface the final summary (the loop itself creates no step states).
     */
    private suspend fun completeCliRun(
        completion: ActionDescription,
        context: ExecutionContext,
    ): ResolveResult {
        onTaskCompletion(completion, context)
        stateManager.addToHistory(context.agentState)
        return ResolveResult(context, ActResultHelper.complete(completion))
    }

    /** System prompt for the CLI tool-loop engine: role + bundled SKILL.md + hygiene rules. */
    private fun cliAgentSystemPrompt(): String = buildString {
        append(
            """
            You are a browser automation agent. Drive the browser EXCLUSIVELY through the
            browser4-cli tool via cli.run(...), following the bundled SKILL.md below.
            Use coding.* tools for file/workspace work when needed, and system.skillDoc(name)
            to read reference documents on demand.
            """.trimIndent()
        )
        append("\n\n### SKILL.md (bundled)\n\n")
        append(agentToolManager.system.skillDoc("SKILL.md").take(CLI_SYSTEM_PROMPT_MAX_CHARS))
        append(
            """

            ### Context hygiene
            - Prefer `snapshot -v 0 --stdout` and targeted `htmlsnapshot get` over full page dumps.
            - After navigation, take a snapshot before deciding the next action.
            - Your working directory is ${agentWorkspaceDir}; write helper files
              (e.g. JS for eval) with relative paths — never create files in the
              repository root.
            - When the task is finished, call system.taskComplete(
              summary=..., keyFindings=[...], filesChanged=[...], problems=[...]).
            """.trimIndent()
        )
    }

    private suspend fun step(action: ActionOptions, context: ExecutionContext, noOpsIn: Int): StepProcessingResult {
        var consecutiveNoOps = noOpsIn

        // Execute the tool call with enhanced error handling
        val actResult = act(action)

        if (actResult.isComplete) {
            onTaskCompletion(actResult, context)
            return StepProcessingResult(context, consecutiveNoOps, true, StopReason.COMPLETED)
        }

        if (!actResult.isSuccess) {
            // Only count failures of actual browser-interaction tool calls as no-ops.
            // A failed act WITHOUT a tool call (e.g. the model returned plain text while
            // reasoning, or a non-browser tool like fs/cli/coding failed) is a legitimate
            // intermediate state in reasoning-heavy tasks — counting it as a no-op is what
            // killed coding agents with "noop.stop limit=5" mid-task.
            val lastToolCall = actResult.detail?.actionDescription?.toolCall
            if (lastToolCall != null && ToolSpecification.isBrowserInteraction(lastToolCall.domain)) {
                consecutiveNoOps++
                val stop = handleConsecutiveNoOps(consecutiveNoOps, actResult, context)
                if (stop) {
                    return StepProcessingResult(context, consecutiveNoOps, true, StopReason.NOOP_LIMIT)
                }
            }
        }

        delay(calculateAdaptiveDelay().milliseconds)
        return StepProcessingResult(context, consecutiveNoOps, false)
    }

    // ─── Step preparation ─────────────────────────────────────────────────────

    private suspend fun prepareStep(
        action: ActionOptions, ctxIn: ExecutionContext, noOpsIn: Int
    ): PrepareStepResult {
        val context = buildExecutionContextForStep(action, "step", ctxIn)

        val prevAgentState = context.prevAgentState ?: return PrepareStepResult(context, noOpsIn, false)
        require(prevAgentState == ctxIn.agentState)

        val prevBrowserUseState = prevAgentState.browserUseState
        val step = context.step
        val sid = context.sid
        val prevToolCall = lastExecutedToolCall(context)
        if (step == 3 && logger.isDebugEnabled) {
            require(prevAgentState == context.agentState.prevState) { "Inconsistent step state" }
            logger.debug("Previous agent state: {}", Pson.toJson(prevAgentState))
            logger.debug("Agent state: {}", Pson.toJson(context.agentState))
        }
        val prevDomain = prevToolCall?.domain

        var consecutiveNoOps = noOpsIn
        if (ToolSpecification.isBrowserInteraction(prevDomain)) {
            // Only browser-interaction actions can change the WebPage state
            val unchangedCount = pageStateTracker.checkStateChange(prevBrowserUseState)
            if (unchangedCount >= 3) {
                logger.info("⚠️ loop.warn sid={} step={} unchangedSteps={} lastTool={}",
                    sid, step, unchangedCount, prevToolCall?.pseudoExpression)
                consecutiveNoOps++
                if (consecutiveNoOps >= noopLimit) {
                    // The page state froze despite repeated browser actions — treat it as a
                    // deadlock and stop the loop. The counter now flows back to the run loop
                    // (previously this increment was computed but never propagated).
                    val synthetic = ActResult(
                        message = "Page state unchanged for $unchangedCount consecutive steps",
                        action = action.action,
                        exception = IllegalStateException("Page state unchanged for $unchangedCount steps")
                    )
                    val stop = handleConsecutiveNoOps(consecutiveNoOps, synthetic, context)
                    return PrepareStepResult(context, consecutiveNoOps, stop)
                }
            }
            logger.info("▶️ step.exec sid={} step={}/{} noOps={} lastTool={}",
                sid, step, config.maxSteps, consecutiveNoOps, prevToolCall?.pseudoExpression)
        }

        if (logger.isDebugEnabled) {
            logger.debug("🧩 dom={}", DomDebug.summarizeStr(prevBrowserUseState.domState, 5))
        }

        return PrepareStepResult(context, consecutiveNoOps, false)
    }

    protected fun lastExecutedToolCall(context: ExecutionContext) =
        context.prevAgentState?.toolCallResult?.actionDescription?.toolCall
            ?: context.prevAgentState?.actionDescription?.toolCall

    private suspend fun buildExecutionContextForStep(
        action: ActionOptions, event: String, ctxIn: ExecutionContext
    ): ExecutionContext {
        // P4.5 lazy browser launch: coding tasks must not bind a driver (or
        // navigate) until the model explicitly calls a page tool — activeDriver
        // is a lazily-creating getter, so skip it entirely in coding mode.
        if (!codingMode) {
            val driver = activeDriver
            val url = driver.url()
            if (url.isBlank() || url == "about:blank") {
                val searchURL = SearchEngineSelector.selectBest()
                driver.navigate(searchURL)
            }
        }

        val instruction = action.action
        val step = ctxIn.step + 1
        val activeContext = stateManager.buildExecutionContext(instruction, step, event, baseContext = ctxIn)
        stateManager.setActiveContext(activeContext)

        return activeContext
    }

    private suspend fun initializeResolution(initContext: ExecutionContext, attempt: Int) {
        val sid = initContext.sid
        logger.info(
            "🚀 agent.start sid={} step={} url={} instr='{}' attempt={} maxSteps={} maxRetries={}",
            sid,
            initContext.step,
            initContext.targetUrl,
            Strings.compactInline(initContext.instruction, COMPACT_INLINE_INSTRUCTION_LENGTH),
            attempt + 1,
            config.maxSteps,
            config.maxRetries
        )
    }

    // ─── Error handling ───────────────────────────────────────────────────────

    private fun handleObserveException(e: Exception, context: ExecutionContext) {
        val failures = try {
            circuitBreaker.recordFailure(CircuitBreaker.FailureType.LLM_FAILURE)
        } catch (cbError: CircuitBreakerTrippedException) {
            throw PerceptiveAgentError.PermanentError(cbError.message ?: "Circuit breaker tripped", cbError)
        }

        logger.error("🤖❌ action.gen.fail sid={} failures={} msg={}", context.sid, failures, e.message, e)
    }

    private fun handleResolutionFailure(
        e: Exception, context: ExecutionContext, startTime: Instant
    ): PerceptiveAgentError {
        val executionTime = Duration.between(startTime, Instant.now())
        logger.error(
            "💥 agent.fail sid={} steps={} dur={} err={}", context.sid, context.step, executionTime, e.message, e
        )
        runCatching { stateManager.removeLastIfStep(context.step) }.onFailure {
            logger.warn(
                "⚠️ rollback failed sid={} step={} msg={}",
                context.sid,
                context.step,
                e.message
            )
        }
        return classifyError(e, context.step)
    }

    private fun classifyError(e: Exception, step: Int) = retryStrategy.classifyError(e, "step $step")

    private fun calculateRetryDelay(attempt: Int) = retryStrategy.calculateDelay(attempt)

    private fun cleanupPartialState(context: ExecutionContext) {
        try {
            logger.info("🧹 cleanup.partial sid={} step={}", context.sid, context.step)
            circuitBreaker.reset()
        } catch (e: Exception) {
            logger.warn("⚠️ cleanup.partial.fail sid={} msg={}", context.sid, e.message)
        }
    }

    // ─── NoOp handling ────────────────────────────────────────────────────────

    private suspend fun handleConsecutiveNoOps(
        consecutiveNoOps: Int,
        result: ActResult,
        context: ExecutionContext
    ): Boolean {
        val step = context.step
        val expression = result.weakTypeExpression
        stateManager.addTrace(
            context.agentState,
            event = "noop",
            items = mapOf("step" to step, "consecutive" to consecutiveNoOps),
            message = "🕒 no-op"
        )
        logger.info("🕒 noop sid={} step={} consecutive={} toolCall={} | result={}",
            context.sid, step, consecutiveNoOps, expression, result)
        if (consecutiveNoOps >= noopLimit) {
            logger.info("⛔ noop.stop sid={} step={} limit={} toolCall={}",
                context.sid, step, noopLimit, expression)
            return true
        }
        if (isClosed) {
            return true
        }
        val delayMs = calculateConsecutiveNoOpDelay(consecutiveNoOps)
        delay(delayMs.milliseconds)
        val job = currentCoroutineContext()[Job]
        if (job == null || !job.isActive) {
            logger.info("🕒 noop cancelled sid={} step={}", context.sid, step)
            return true
        }
        return false
    }

    private fun calculateConsecutiveNoOpDelay(consecutiveNoOps: Int): Long {
        val baseDelay = 250L
        val exponentialDelay = baseDelay * consecutiveNoOps
        return min(exponentialDelay, 5000L)
    }

    private fun calculateAdaptiveDelay(): Long {
        if (!config.enableAdaptiveDelays) return 100L
        val avgStepTime = stepExecutionTimes.values.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        return when {
            avgStepTime < 500 -> 50L
            avgStepTime < 2000 -> 100L
            else -> 200L
        }
    }

    // ─── Task completion ──────────────────────────────────────────────────────

    private suspend fun onTaskCompletion(actResult: ActResult, context: ExecutionContext) {
        val actionDescription = actResult.detail?.actionDescription ?: return
        onTaskCompletion(actionDescription, context)
    }

    private suspend fun onTaskCompletion(action: ActionDescription, context: ExecutionContext) {
        val step = context.step
        val sid = context.sessionId

        require(action.isDecidedComplete) { "Required action.isComplete" }

        // ── Finish-report hard validation (design §3.2) ─────────────────────
        // A completion claim with ZERO executed tool calls is a fabricated
        // narrative (observed: 35s / 1 step / 0 tool calls "all done"). Reject
        // it in strict mode so the task fails instead of reporting success.
        // Two sources: outer states (one per model turn with an executed tool)
        // and inner-loop executions recorded by the tool-calling coordinator.
        val runStates = stateHistory.snapshotFor(context.sessionId).states
        val outerExecuted = runStates.filter { it.toolCallResult != null }
        val totalExecuted = outerExecuted.size + innerToolExecutionCount
        if (totalExecuted == 0 && config.finishGateCheck.equals("strict", ignoreCase = true)) {
            throw IllegalStateException(
                "Finish report rejected: task completed with zero executed tool calls (false-completion guard)"
            )
        }
        // Gate cross-check: gates claiming `ran:true` should reference tools that
        // actually executed. Matches against outer states AND inner-loop names
        // (exact or method-substring); free-form gate names make strict failure
        // risky, so mismatches warn instead of failing.
        val executedNames = outerExecuted
            .mapNotNull { s -> s.actionDomain?.let { d -> "$d.${s.method}" } }
            .toSet() + innerToolExecutionNames
        action.gates.orEmpty().filter { it["ran"] == true }.forEach { gate ->
            val name = gate["name"]?.toString() ?: return@forEach
            val matched = executedNames.any { it.endsWith(name) || it.contains(name) }
            if (!matched) {
                logger.warn(
                    "⚠ finish.gate sid={} gate='{}' claims ran but no matching tool call executed; executed={}",
                    sid.take(8), name, executedNames.joinToString(",")
                )
            }
        }

        context.agentState.also {
            it.isComplete = true
            it.summary = action.summary
            it.keyFindings = action.keyFindings
            it.nextSuggestions = action.nextSuggestions
            it.gates = action.gates
            it.filesChanged = action.filesChanged
            it.problems = action.problems
            it.actionDescription = action
        }

        // Persist the completed state to the state audit stream. Without this, the final
        // "isComplete/summary" snapshot never reached state-history.jsonl — the last line
        // written there was the pre-completion state from updateAgentState.
        stateManager.writeAgentState(context.agentState, context.sessionId)

        logger.info("✅ task.complete sid={} step={} complete={}", sid.take(8), step, true)
        stateManager.addTrace(context.agentState, event = "complete", message = "#${step} complete")

        val files = fs.listOSFiles()
        if (files.isNotEmpty()) {
            logger.info("Agent data dir: \n{}", fs.dataDir.toUri())
            logger.info("Agent files: \n{}", files.joinToString("\n") { it.toUri().toString() })
        } else {
            logger.info("No files used by this agent")
        }
    }

    // ─── Final act result & summary ───────────────────────────────────────────

    private suspend fun buildFinalActResult(
        instruction: String, cxtIn: ExecutionContext, startTime: Instant, stopReason: StopReason? = null
    ): ActResult {
        val executionTime = Duration.between(startTime, Instant.now())

        logger.info("✅ agent.done sid={} steps={} dur={} stopReason={}", cxtIn.sid, cxtIn.step, executionTime, stopReason)

        val result = generateFinalSummary(instruction, cxtIn)

        val summary = result.modelResponse
        val context = result.context
        val ok = summary.state != ResponseState.OTHER
        val exception = when {
            // The loop terminated abnormally (no-op limit / max steps) while the task was
            // NOT complete: report failure so supervisors can distinguish success from abort.
            stopReason != null && stopReason != StopReason.COMPLETED -> IllegalStateException(
                "Agent loop stopped abnormally: $stopReason at step ${cxtIn.step}"
            )

            ok -> null
            else -> IllegalStateException("ResponseState: OTHER")
        }

        return ActResult(
            message = summary.content,
            action = context.instruction,
            result = context.agentState.toolCallResult,
            exception = exception
        )
    }

    private suspend fun generateFinalSummary(instruction: String, context: ExecutionContext): SummarizeResult {
        return try {
            val result = summarize(instruction, context)
            stateManager.addTrace(
                context.agentState,
                event = "final",
                items = mapOf("summaryPreview" to result.modelResponse.content.take(200)),
                message = "🧾 FINAL"
            )
            transcriptPersister.persist(instruction, result.modelResponse, context)
            result
        } catch (e: Exception) {
            logger.error("📝❌ agent.summary.fail sid={} msg={}", context.sid, e.message, e)
            SummarizeResult(
                context = context, ModelResponse("Failed to generate summary: ${e.message}", ResponseState.OTHER)
            )
        }
    }

    private suspend fun summarize(goal: String, ctxIn: ExecutionContext): SummarizeResult {
        val step = ctxIn.step + 1
        val context = stateManager.buildExecutionContext(goal, step, event = "summary", baseContext = ctxIn)
        stateManager.setActiveContext(context)

        return try {
            val (system, user) = promptBuilder.buildSummaryPrompt(goal, stateHistory)
            slogger.info("📝⏳ Generating final summary", context)
            val response = cta.chatModel.callUmSm(user, system)
            slogger.info(
                "📝✅ Summary generated successfully",
                context,
                mapOf("responseLength" to response.content.length, "responseState" to response.state)
            )
            SummarizeResult(context, response)
        } catch (e: Exception) {
            slogger.logError("📝❌ Summary generation failed", e, context.sessionId)
            SummarizeResult(
                context, modelResponse = ModelResponse("Failed to generate summary: ${e.message}", ResponseState.OTHER)
            )
        }
    }
}

/**
 * Next value of the text-only stall counter for one step.
 *
 * A step whose response parsed into a [ToolCall] — or one that executed ≥1
 * internal tool inside the tool-calling loop (overflow steps execute tools but
 * carry no parsed ToolCall) — resets the counter to 0; pure text responses
 * increment it (P0.2-2: real work must never count as text idling).
 */
internal fun nextTextOnlyStallCount(current: Int, toolCall: ToolCall?, internalToolsExecuted: Boolean): Int =
    if (toolCall == null && !internalToolsExecuted) current + 1 else 0
