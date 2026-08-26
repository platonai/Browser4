package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.*
import ai.platon.pulsar.agentic.common.AgentPaths
import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.agentic.inference.RequestTokenLimitExceededException
import ai.platon.pulsar.agentic.inference.TokenBudgetExceededException
import ai.platon.pulsar.agentic.inference.chat.*
import ai.platon.pulsar.agentic.inference.detail.*
import ai.platon.pulsar.agentic.memory.AgentMemory
import ai.platon.pulsar.agentic.memory.MemoryConfig
import ai.platon.pulsar.agentic.memory.MemoryScope
import ai.platon.pulsar.agentic.memory.MemoryToolExecutor
import ai.platon.pulsar.agentic.memory.MemoryToolTarget
import ai.platon.pulsar.agentic.memory.Sanitizer
import ai.platon.pulsar.agentic.memory.external.ExternalMemoryConfig
import ai.platon.pulsar.agentic.memory.external.MemoryExternalToolExecutor
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.agentic.tools.builtin.TaskCompletion
import ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.agentic.tools.specs.ToolSpecification
import ai.platon.pulsar.chrome.dom.util.DomDebug
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.MultiSinkMessageWriter
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.Pson
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.*
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

// File-level constants previously in companion object
private const val COMPACT_INLINE_SESSION_LENGTH = 160
private const val COMPACT_INLINE_INSTRUCTION_LENGTH = 100
private const val CLI_CONTINUE_NUDGE =
    "If the task is fully finished, call system.taskComplete with the final report now. " +
        "Otherwise continue working with tools. Do not answer in plain text unless you are done."
/** How often the CLI tool loop emits a liveness event (every N tool executions). */
private const val CLI_TOOL_PROGRESS_EVENT_INTERVAL = 5
/** Explicit tool domains exposed to the CLI engine (design v0.2 §4.4; memory §7.1). */
private val CLI_ENGINE_DOMAINS = setOf("coding", "b4", "system", "memory")

/** Whole domains kept in the CLI engine's INITIAL tool set. */
private val CLI_CORE_DOMAINS = setOf("b4", "system", "memory")

/**
 * Curated coding core exposed initially for CODING tasks (progressive
 * disclosure): the daily drivers. The long tail (symbols/references,
 * kt-symbols/kt-references, scaffold family, impact, runCode, lspServers,
 * tokenStats, ...) is reachable via system.listTools / system.exposeTools.
 */
private val CLI_CORE_CODING_METHODS = setOf(
    "shell", "shellOutput", "shellStatus",
    "read", "write", "append", "replace", "insertAfter", "editLines", "delete", "mkdir",
    "listDir", "glob", "grep", "stat", "diff", "changeSummary",
    "validate", "workspaceRoot", "devTask",
)

/**
 * Coding-domain methods exposed initially for BROWSING tasks: the most common
 * FILE tools only (helper scripts for eval, small data files, locating
 * things). The coding long tail — shell*, mvnBuild, validate, devTask,
 * editLines/insertAfter, symbols, kt-symbols, scaffold family, impact, ... —
 * stays hidden and is reachable via system.listTools / system.exposeTools
 * on demand.
 */
private val CLI_BROWSING_CODING_METHODS = setOf(
    "read", "write", "append", "replace",
    "listDir", "glob", "grep",
    "stat", "mkdir", "delete",
)

/**
 * System methods the CLI engine's contracts depend on — always present in the
 * INITIAL tool set regardless of `browser4.agent.toolLoop.initialToolSet`:
 * `system.taskComplete` is the engine's only completion signal, and the CLI
 * system prompt instructs the model to load the bundled SKILL.md via
 * `system.skillDoc`.
 */
private val CLI_CONTRACT_SYSTEM_METHODS = setOf("taskComplete", "skillDoc")

/**
 * The CLI engine's tool set, derived from the full reverse registry plus the
 * authoritative system-domain specs.
 *
 * The hardcoded `TOOL_CALL_SPECIFICATION` advertises only `system.help`, so
 * the registry alone would never carry `system.taskComplete` /
 * `system.skillDoc` — the tools the CLI engine's completion and SKILL.md
 * contracts depend on. Those come from [SystemToolExecutor.getToolSpecs] and
 * are force-included in the initial set whatever [initialToolSet] says
 * (strict function-calling models only call declared tools).
 *
 * Pure logic — no IO — so it is unit-testable without an agent session.
 */
internal data class CliEngineToolSet(
    /** Reverse registry (tool name → ToolSpec) for the execution coordinator. */
    val registry: Map<String, ToolSpec>,
    /** Full CLI-domain specifications (disclosure registry / prompt tracing). */
    val specs: List<dev.langchain4j.agent.tool.ToolSpecification>,
    /** The curated initial set actually sent to the model. */
    val initialSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>,
    /** Disclosure registry; non-empty enables system.listTools / exposeTools. */
    val disclosureSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>,
    /** The mode the initial selection actually used (after fallback). */
    val usedMode: String,
)

/**
 * Build the CLI engine's tool set.
 *
 * Task-adaptive initial exposure: browsing tasks (default) get the web-access
 * tools (`b4.*`), the system contract tools and the most common file tools;
 * coding tasks additionally get the full curated coding core (shell, build,
 * validate, devTask, ...). The long tail is always reachable via
 * `system.listTools` / `system.exposeTools`.
 *
 * @param allRegistry The full reverse registry (tool name → ToolSpec) from
 *   [AgentToolManager.getLangChain4jToolRegistry].
 * @param systemSpecs The authoritative system-domain specs from
 *   [SystemToolExecutor.getToolSpecs] (help/skillDoc/taskComplete).
 * @param codingMode True when the task was classified as a pure coding task
 *   (set by the task runner via `CodingTaskDetector` before the run).
 * @param coreMethodAllowlist Explicit per-domain method allowlist; when null
 *   the task-adaptive allowlist is used ([CLI_BROWSING_CODING_METHODS] for
 *   browsing tasks, [CLI_CORE_CODING_METHODS] for coding tasks).
 */
internal fun buildCliEngineToolSet(
    allRegistry: Map<String, ToolSpec>,
    systemSpecs: List<ToolSpec>,
    initialToolSet: String,
    disclosureEnabled: Boolean,
    codingMode: Boolean = false,
    coreDomains: Set<String> = CLI_CORE_DOMAINS,
    coreMethodAllowlist: Map<String, Set<String>>? = null,
): CliEngineToolSet {
    // Task-adaptive coding allowlist; explicit allowlists (callers/tests) win.
    val effectiveAllowlist = coreMethodAllowlist
        ?: if (codingMode) mapOf("coding" to CLI_CORE_CODING_METHODS)
        else mapOf("coding" to CLI_BROWSING_CODING_METHODS)

    // CLI-domain specs: the registry minus the hardcoded system section
    // (only system.help), plus the executor's authoritative system specs
    // (help/skillDoc/taskComplete). Duplicates collapse on the sanitized
    // tool name, keeping the first occurrence (the registry's merged help).
    val cliDomainSpecs = (
        allRegistry.values.filter { it.domain in CLI_ENGINE_DOMAINS && it.domain != "system" } +
            systemSpecs
        ).distinctBy { ToolSpecificationConverter.toolName(it.domain, it.method) }
    val registry = ToolSpecificationConverter.toRegistry(cliDomainSpecs)
    val specs = ToolSpecificationConverter.toToolSpecifications(cliDomainSpecs)

    if (!disclosureEnabled) {
        return CliEngineToolSet(registry, specs, specs, emptyList(), initialToolSet)
    }

    var selected = ToolDisclosureTools.selectInitialSpecs(cliDomainSpecs, initialToolSet, coreDomains, effectiveAllowlist)
    var usedMode = initialToolSet
    if (selected.isEmpty()) {
        // Misconfigured pattern list (non-CLI domains, or tokens that
        // matched nothing): degrade to the default core set instead of
        // leaving the model with no initial tools beyond the meta pair.
        selected = ToolDisclosureTools.selectInitialSpecs(cliDomainSpecs, "core", coreDomains, effectiveAllowlist)
        usedMode = "core"
    }

    // The completion + skill-doc contract survives every initialToolSet mode.
    val contractSpecs = systemSpecs
        .filter { it.method in CLI_CONTRACT_SYSTEM_METHODS }
        .filter { s -> selected.none { it.domain == s.domain && it.method == s.method } }
    val initialSpecs = ToolSpecificationConverter.toToolSpecifications(contractSpecs + selected)

    val disclosureSpecs = if (initialSpecs.size < specs.size) specs else emptyList()
    return CliEngineToolSet(registry, specs, initialSpecs, disclosureSpecs, usedMode)
}

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
     * the model writes via coding.* and b4.run land here — never the backend
     * working directory / repository root (workspace isolation, P1).
     */
    internal val agentWorkspaceDir: Path by lazy {
        baseDir.resolve("workspace").also { Files.createDirectories(it) }
    }

    /**
     * Test/runtime override of the agent memory root directory (defaults to
     * `<APP_DATA>/memory`). Lets tests isolate the memory files, and lets
     * multi-tenant deployments relocate them.
     */
    @Volatile
    internal var agentMemoryRootDirOverride: Path? = null

    /**
     * Generic agent memory (design: robust-browser-agent-memory-system-design.md):
     * L0 event log + query + scratchpad + run-start recall, wired into the CLI
     * engine loop below. Lazy: agents that never run the CLI loop pay nothing.
     */
    internal val agentMemory: AgentMemory by lazy {
        val memory = AgentMemory(
            MemoryScope(agentUuid = uuid.toString()),
            rootDir = agentMemoryRootDirOverride ?: AgentMemory.defaultRootDir(),
        )
        if (MemoryConfig.enabled) {
            // memory.* tools: the executor is stateless, so one global
            // registration suffices; per-agent dispatch binds the target.
            if (CustomToolRegistry.instance.get("memory") == null) {
                CustomToolRegistry.instance.register(
                    MemoryToolExecutor(),
                    MemoryToolExecutor().getToolSpecs().values.toList(),
                )
            }
            agentToolManager.registerCustomTarget("memory", MemoryToolTarget(memory))

            // L2 external memory bridge (M4): wait for the discovery handshake
            // (bounded), then register the discovered tools so the model can
            // call them through the normal tool loop.
            memory.externalBridge?.let { bridge ->
                val external = ExternalMemoryConfig.fromSystem()
                runBlocking { bridge.awaitConnected(external.connectTimeoutMs) }
                if (bridge.getToolSpecs().isNotEmpty()) {
                    if (CustomToolRegistry.instance.get(external.toolPrefix) == null) {
                        val executor = MemoryExternalToolExecutor(bridge, external.toolPrefix)
                        CustomToolRegistry.instance.register(
                            executor, executor.getToolSpecs().values.toList(),
                        )
                    }
                    agentToolManager.registerCustomTarget(external.toolPrefix, bridge)
                } else {
                    logger.warn(
                        "External memory bridge connected with no tools; registration skipped"
                    )
                }
            }
        }
        memoryInitialized.set(true)
        memory
    }

    internal val isAgentMemoryInitialized: Boolean get() = memoryInitialized.get()

    private val memoryInitialized = AtomicBoolean(false)

    /**
     * Start time of the most recent CLI-engine run, for memory completion
     * duration accounting ([AgentMemorySink.completed]).
     */
    @Volatile
    private var cliRunStartedAt: Instant? = null

    /**
     * Complete-prompt + per-tool run tracing for the CLI engine. Writes into
     * `<APP_DATA_DIR>/logs/agent/<start-time>/<agent-uuid>/` (typically
     * `~/.browser4/logs/agent/...`; see [AgentPaths]); in development a
     * `logs/agent` symlink at the project root points there:
     * - `cli-prompt/<ts>.<seq>.request.json` — the EXACT message list plus the
     *   tool specifications sent to the model on every round-trip;
     * - `cli-tool-trace.jsonl` — every executed tool with arguments, full
     *   result text and duration;
     * - `cli-events.jsonl` — run-level events (start, overflow, completion).
     * Gated by [AgentConfig.logInferenceToFile] (default true).
     */
    private val cliLoopTracer by lazy {
        CliLoopTracer(uuid, startTime, config.logInferenceToFile, cliViewToolNames(session.sessionConfig))
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
    @Deprecated("Use RunEngine.CLI_TOOL_LOOP path instead")
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

            // Close the agent memory (event log buffers + search index).
            if (isAgentMemoryInitialized) {
                runCatching { agentMemory.close() }
                    .onFailure { logger.warn("Failed to close agent memory: ${it.message}") }
            }
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

        return if (effectiveRunEngine == RunEngine.CLI_TOOL_LOOP) {
            doRunCliAgentLoop(initActionOptions, initContext)
        } else {
            doRunAgentLoopLegacy(initActionOptions, initContext, attempt)
        }
    }

    @Deprecated("This is the legacy branch for doRunAgentLoop, and will be removed in the further")
    private suspend fun doRunAgentLoopLegacy(
        initActionOptions: ActionOptions, initContext: ExecutionContext, attempt: Int
    ): ResolveResult {
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
     * `b4.run`, following the bundled SKILL.md. Completion is signalled by the
     * model calling `system.taskComplete` (no JSON parsing anywhere).
     */
    private suspend fun doRunCliAgentLoop(
        initActionOptions: ActionOptions, initContext: ExecutionContext
    ): ResolveResult {
        var context = initContext
        val startTime = Instant.now()
        cliRunStartedAt = startTime
        val completionRef = AtomicReference<ActionDescription?>()
        val toolExecutions = AtomicInteger(0)
        try {
            val action = initActionOptions.copy(fromRunLoop = true)
            // Initialize the agent memory BEFORE building the tool loop: the
            // memory.* tool specs are registered into CustomToolRegistry on
            // first access, and buildCliToolLoop snapshots the registry for
            // the whole run — a lazy init after this point would leave the
            // memory tools undisclosed to the model.
            val taskId = context.sid
            agentMemory.currentTaskId = taskId
            val loop = buildCliToolLoop(action.action, completionRef, toolExecutions)
            cliLoopTracer.logEvent("run.start", mapOf("instruction" to action.action))
            // Agent memory: observe the run and inject the recall section
            // (design §5 — static for the whole run, KV prefix preserved).
            val urlCandidate = Sanitizer.extractUrl(action.action)
            agentMemory.sink.taskStarted(
                taskId, uuid.toString(), action.action, "cli",
                urlCandidate = urlCandidate,
            )
            agentMemory.recordTaskDomain(urlCandidate)
            val memorySection = agentMemory.recall.recall(
                action.action, agentMemory.scope, excludeTaskId = taskId,
            )
            var messages: List<ChatMessage> = listOf(
                SystemMessage.from(cliAgentSystemPrompt() + memorySection),
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
                // Working memory: re-inject the scratchpad as the tail message
                // every round (replace-tail — all earlier prefixes stay intact
                // for KV reuse, and the compressor never touches the tail).
                val scratchpadText = agentMemory.scratchpad.render()
                val roundMessages =
                    if (scratchpadText != null) messages + UserMessage.from(scratchpadText) else messages
                val response = withTimeout(config.llmInferenceTimeoutMs.milliseconds) {
                    loop.generate(roundMessages)
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
                    // One text-only response after real work is the final
                    // report (the nudge already told the model to call
                    // taskComplete or continue with tools; a plain-text answer
                    // means it is done).
                    if (toolExecutions.get() > 0 && textOnlyStreak >= 1) {
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
                messages = when {
                    // Overflow: the inner loop's message list dies with the
                    // generate() call, so without this hand-off the model would
                    // restart the whole round from scratch and re-execute every
                    // already-done tool call (the observed 20-iteration churn).
                    // Feed the executed-tools + newest-results digest back so it
                    // resumes from the cut point instead.
                    overflow -> {
                        cliLoopTracer.logEvent("overflow", mapOf("modelError" to response.modelError!!))
                        messages + UserMessage.from(overflowContinuationMessage(response.modelError!!))
                    }
                    text.isBlank() -> messages + UserMessage.from(CLI_CONTINUE_NUDGE)
                    else -> messages + AiMessage.from(text) + UserMessage.from(CLI_CONTINUE_NUDGE)
                }
                turn++
            }

            val completion = completionRef.get()
            if (completion != null && completion.isDecidedComplete) {
                return completeCliRun(completion, context)
            }
            val stopReason = if (turn >= maxTurns) StopReason.MAX_STEPS else StopReason.NOOP_LIMIT
            logger.info("⛔ cli-agent.no-completion sid={} stop={}", context.sid, stopReason)
            agentMemory.sink.failed(
                context.sid, uuid.toString(),
                "CLI agent stopped without completion: $stopReason", step = turn,
            )
            agentMemory.consolidator?.schedule(context.sid)
            val actResult = buildFinalActResult(initContext.instruction, context, startTime, stopReason)
            return ResolveResult(context, actResult)
        } catch (e: CancellationException) {
            logger.info(
                "🛑 cli-agent.cancelled sid={} reason={}",
                context.sid, e.message ?: "user interruption"
            )
            return ResolveResult(context, ActResultHelper.failed(e, initContext.instruction))
        } catch (e: Exception) {
            // Failure memory event (user cancellation is deliberately excluded).
            agentMemory.sink.failed(context.sid, uuid.toString(), e.message ?: e.javaClass.simpleName)
            agentMemory.consolidator?.schedule(context.sid)
            throw handleResolutionFailure(e, context, startTime)
        }
    }

    private fun buildCliToolLoop(
        instruction: String,
        completionRef: AtomicReference<ActionDescription?>,
        toolExecutions: AtomicInteger,
    ): AgentToolCallLoop {
        val conf = session.sessionConfig
        val initialToolSet = conf.get("browser4.agent.toolLoop.initialToolSet") ?: "core"
        val disclosureEnabled = conf.getBoolean("browser4.agent.toolLoop.toolDisclosureEnabled", true)
        val toolSet = buildCliEngineToolSet(
            allRegistry = agentToolManager.getLangChain4jToolRegistry(),
            systemSpecs = agentToolManager.system.getToolSpecs().values.toList(),
            initialToolSet = initialToolSet,
            disclosureEnabled = disclosureEnabled,
            // Task-adaptive initial exposure (see buildCliEngineToolSet): set
            // by StatefulAgentRunner via CodingTaskDetector before the run.
            codingMode = codingMode,
        )
        if (toolSet.usedMode != initialToolSet) {
            logger.warn(
                "cli-agent initialToolSet '{}' matched no CLI tools; falling back to '{}'",
                initialToolSet, toolSet.usedMode
            )
        }
        val taskCompleteName = ToolSpecificationConverter.toolName("system", "taskComplete")
        // One traceability ledger shared by compressor, deduper and loop
        // (compaction-traceability-design.md): references stay resolvable
        // after compression. Every durable rewrite is mirrored to the
        // disk-side audit trail (cli-compactions.jsonl).
        val compactionLedger = CompactionLedger(
            enabled = conf.getBoolean("browser4.agent.toolLoop.compactionLedgerEnabled", true),
            onEntry = { entry -> cliLoopTracer.logLedgerEntry(entry) },
        )
        logger.info(
            "cli-agent tools exposed: {} initial (of {} CLI-domain, disclosure={}, mode='{}', profile={})",
            toolSet.initialSpecs.size, toolSet.specs.size, toolSet.disclosureSpecs.isNotEmpty(),
            toolSet.usedMode, if (codingMode) "coding" else "browsing",
        )
        return AgentToolCallLoop(
            model = cta.chatModel,
            toolSpecifications = toolSet.initialSpecs,
            allToolSpecifications = toolSet.disclosureSpecs,
            disclosureListingLimit = conf.getLong("browser4.agent.toolLoop.toolDisclosureListingLimit", 200L)
                .toInt().coerceIn(10, 1_000),
            coordinator = ToolExecutionCoordinator(agentToolManager, toolSet.registry),
            // Browser tasks routinely exceed the default 12-round internal cap
            // (open → snapshot → type → submit → wait → extract); give the CLI
            // engine a higher ceiling, overflow is handled by the outer loop
            // (the model resumes from the executed-tools digest).
            maxIterations = config.toolLoopMaxIterations.coerceAtLeast(40),
            requestTokenLimiter = cta.requestTokenLimiter,
            // One shared traceability ledger across compressor, deduper and
            // loop (compaction-traceability-design.md).
            compressor = cliToolLoopCompressor(toolSet.specs, compactionLedger),
            // Web-context optimization: repeated page views fold into
            // references/diffs instead of resending full snapshots.
            pageViewDeduper = cliPageViewDeduper(compactionLedger),
            maxOverflowRetries = conf.getLong("browser4.agent.toolLoop.maxOverflowRetries", 1L)
                .toInt().coerceIn(0, 5),
            compactionLedger = compactionLedger,
            // Complete prompt + run tracing: dump the exact request and every
            // tool execution (see CliLoopTracer). The specs are the ones the
            // model request actually carries (exposed set + meta tools), NOT
            // the full registry — the dump must reconstruct the real prompt.
            onBeforeGenerate = { msgs, specs -> cliLoopTracer.logPrompt(msgs, specs) },
            onModelResponse = { seq, response -> cliLoopTracer.logResponse(seq, response) },
            onToolResult = { req, result, durationMs ->
                cliLoopTracer.logTool(req, result, durationMs)
                // Agent memory: every executed tool becomes a ToolExecuted
                // event (sanitized at the sink boundary).
                val memory = agentMemory
                if (MemoryConfig.enabled) {
                    memory.sink.toolExecuted(
                        memory.currentTaskId ?: "unknown", uuid.toString(), req.name(),
                        req.arguments() ?: "{}",
                        ok = !result.text().trimStart().startsWith("[fail]"),
                        result.text(), durationMs, req.id() ?: "",
                    )
                }
            },
            // Page-view timeline: log every view tool execution with its
            // final (decorated) form so URL/fingerprint survive compaction
            // as a disk-side, machine-readable link index — and mirror the
            // same view into the agent memory (PageViewed events).
            onToolDecorated = { req, raw, decorated ->
                cliLoopTracer.logPageView(req, raw, decorated)
                val memory = agentMemory
                if (MemoryConfig.enabled) {
                    val text = decorated.text()
                    val viewType = when {
                        text == raw.text() -> "full"
                        text.contains(PageViewDeduper.DUPLICATE_MARKER) -> "reference"
                        text.contains(PageViewDeduper.DIFF_MARKER) -> "diff"
                        else -> "full"
                    }
                    val url = Sanitizer.extractUrl(text) ?: Sanitizer.extractUrl(req.arguments())
                    memory.sink.pageViewed(
                        memory.currentTaskId ?: "unknown", uuid.toString(),
                        url ?: "", "", viewType, sha256Brief(text),
                    )
                }
            },
            onToolExecuted = {
                val executed = toolExecutions.incrementAndGet()
                // The CLI engine can execute dozens of tools inside a single
                // step; without periodic events the status stays "Agent starting
                // up" until the whole step resolves. Throttled liveness events
                // let status/SSE observers see that the agent is still working.
                if (executed % CLI_TOOL_PROGRESS_EVENT_INTERVAL == 0) {
                    AgentEventBus.emitAgentEvent(
                        eventType = AgenticEvents.PerceptiveAgent.ON_TOOL_EXECUTED,
                        agentId = uuid.toString(),
                        message = "Executed $executed tool call(s)",
                        metadata = mapOf("toolExecutions" to executed)
                    )
                }
            },
            onToolRequest = { name, argsJson ->
                if (name == taskCompleteName) {
                    runCatching {
                        val completion = TaskCompletion.fromJson(argsJson)
                        if (completion.summary.isBlank()) {
                            logger.warn("cli-agent taskComplete rejected: blank summary")
                        } else {
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
                        }
                    }.onFailure { logger.warn("cli-agent taskComplete parse failed: {}", it.message) }
                }
            },
        )
    }

    /**
     * Continuation prompt handed back to the model after an internal tool-loop
     * overflow: the [modelError] carries a bounded digest of the executed tools
     * and their newest results, so the model resumes from the cut point instead
     * of re-executing the whole round (the overflowed loop's own message list
     * is discarded by [AgentToolCallLoop.generate]).
     */
    private fun overflowContinuationMessage(modelError: String): String = buildString {
        append(
            "Your previous tool round was cut short after exceeding the per-round " +
                "iteration cap. The tools that already executed and the newest results " +
                "are summarized below. Continue the task FROM THIS POINT — do not " +
                "re-execute tools that already succeeded unless you need their full output.\n\n"
        )
        append(modelError)
    }

    /**
     * Complete-prompt + run tracing for the CLI engine.
     *
     * Under the agent's aux log dir (`<aux>/agent/<startTime>/<uuid>/`):
     * - `cli-prompt/<ts>.<seq>.request.json` — one file per model round-trip:
     *   the EXACT `messages` list (system prompt, history, tool-call messages
     *   and full tool results) plus the tool specifications the model saw
     *   (the exposed set + disclosure meta tools — NOT the full registry),
     *   with an `estimatedTokens` fallback (chars/4 of the compact payload);
     * - `cli-usage.jsonl` — one line per model request: requestSeq (pairs with
     *   the request dump), real provider input/output/total tokens, finish
     *   reason;
     * - `cli-tool-trace.jsonl` — one line per executed tool: name, arguments,
     *   full result text, duration;
     * - `page-timeline.jsonl` — one line per page view: tool, callId,
     *   viewType (full/reference/diff), url, title, fingerprint — a
     *   machine-readable link index that survives context compaction;
     * - `cli-compactions.jsonl` — one line per compaction-ledger entry
     *   (registered/folded/pruned/compacted with token accounts);
     * - `cli-events.jsonl` — run-level events: run.start / overflow / complete.
     */
    private class CliLoopTracer(
        agentUuid: UUID,
        agentStartTime: Instant,
        private val enabled: Boolean,
        private val viewToolNames: Set<String>,
    ) {
        private val logger = getLogger(CliLoopTracer::class)

        private val runDir: Path by lazy {
            AgentPaths.resolveTraceRunDir(agentStartTime, agentUuid)
        }

        private val jsonlWriter by lazy { MultiSinkMessageWriter(runDir) }
        private val promptDir: Path by lazy {
            runDir.resolve("cli-prompt").also { Files.createDirectories(it) }
        }
        private val promptSeq = AtomicInteger(0)
        private val toolSeq = AtomicInteger(0)
        private val pageSeq = AtomicInteger(0)

        /** Dump the exact prompt (messages + tool specs) sent to the model. */
        fun logPrompt(
            messages: List<ChatMessage>,
            toolSpecifications: List<dev.langchain4j.agent.tool.ToolSpecification>,
        ) {
            if (!enabled) return
            try {
                val requestSeq = promptSeq.incrementAndGet()
                val basePayload = mapOf(
                    "requestSeq" to requestSeq,
                    "timestamp" to AppPaths.fromNow(),
                    "toolSpecifications" to toolSpecifications.map { spec ->
                        mapOf(
                            "name" to spec.name(),
                            "description" to (spec.description() ?: ""),
                            "parameters" to runCatching {
                                pulsarObjectMapper().writeValueAsString(spec.parameters())
                            }.getOrElse { "{}" },
                        )
                    },
                    "messages" to messages.map { serializeMessage(it) },
                    "messageCount" to messages.size,
                )
                // Fallback estimate persisted with the dump so token totals
                // stay answerable even when the provider reports no usage
                // (compact JSON chars / 4, English-heavy heuristic).
                val estimatedChars = pulsarObjectMapper().writeValueAsString(basePayload).length
                val payload = basePayload + ("estimatedTokens" to (estimatedChars / 4).coerceAtLeast(1))
                val path = promptDir.resolve("${AppPaths.fromNow()}.$requestSeq.request.json")
                Files.writeString(
                    path,
                    pulsarObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload),
                )
            } catch (e: Exception) {
                logger.warn("Failed to write CLI prompt log: {}", e.message)
            }
        }

        /**
         * Persist the real provider token usage for one model request, paired
         * with its request dump via `requestSeq`. Append-only JSONL so every
         * round's usage survives (the loop sums usage across rounds).
         */
        fun logResponse(requestSeq: Int, response: ChatResponse) {
            if (!enabled) return
            try {
                val usage = response.tokenUsage()
                val payload = mapOf(
                    "timestamp" to AppPaths.fromNow(),
                    "requestSeq" to requestSeq,
                    "inputTokens" to usage?.inputTokenCount(),
                    "outputTokens" to usage?.outputTokenCount(),
                    "totalTokens" to usage?.totalTokenCount(),
                    "finishReason" to response.finishReason()?.name,
                )
                jsonlWriter.writeTo(payload, runDir.resolve("cli-usage.jsonl"))
            } catch (e: Exception) {
                logger.warn("Failed to write CLI usage trace: {}", e.message)
            }
        }

        /** Append one executed tool (arguments + full result text + duration). */
        fun logTool(request: ToolExecutionRequest, result: ToolExecutionResultMessage, durationMs: Long) {
            if (!enabled) return
            try {
                val payload = mapOf(
                    "timestamp" to AppPaths.fromNow(),
                    "seq" to toolSeq.incrementAndGet(),
                    "tool" to request.name(),
                    "arguments" to (request.arguments() ?: "{}"),
                    "durationMs" to durationMs,
                    "resultText" to result.text(),
                )
                jsonlWriter.writeTo(payload, runDir.resolve("cli-tool-trace.jsonl"))
            } catch (e: Exception) {
                logger.warn("Failed to write CLI tool trace: {}", e.message)
            }
        }

        /**
         * Append one page view to the disk-side page timeline. Only view tools
         * ([viewToolNames]) are recorded; [raw] is the executed result and
         * [decorated] the message actually appended (identical → `full`,
         * duplicate fold → `reference`, diff → `diff`). URL/title are
         * best-effort extractions (from the CLI arguments, then from the
         * result text) so the timeline stays a machine-readable link index
         * even after the conversation is compacted.
         */
        fun logPageView(
            request: ToolExecutionRequest,
            raw: ToolExecutionResultMessage,
            decorated: ToolExecutionResultMessage,
        ) {
            if (!enabled) return
            val tool = request.name()
            if (!PageViewDeduper.matchesViewTool(tool, viewToolNames)) return
            try {
                val rawText = raw.text()
                if (rawText.isBlank()) return
                val decoratedText = decorated.text()
                val viewType = when {
                    decoratedText == rawText -> "full"
                    decoratedText.contains(PageViewDeduper.DUPLICATE_MARKER) -> "reference"
                    decoratedText.contains(PageViewDeduper.DIFF_MARKER) -> "diff"
                    else -> "full"
                }
                val payload = mapOf(
                    "timestamp" to AppPaths.fromNow(),
                    "seq" to pageSeq.incrementAndGet(),
                    "tool" to tool,
                    "callId" to raw.id(),
                    "viewType" to viewType,
                    "url" to extractPageUrl(request.arguments(), rawText),
                    "title" to extractPageTitle(rawText),
                    "fingerprint" to PageViewDeduper.fingerprintOf(rawText),
                    "textChars" to rawText.length,
                    "arguments" to (request.arguments() ?: "{}"),
                )
                jsonlWriter.writeTo(payload, runDir.resolve("page-timeline.jsonl"))
            } catch (e: Exception) {
                logger.warn("Failed to write CLI page timeline trace: {}", e.message)
            }
        }

        /**
         * Mirror one compaction-ledger entry to `cli-compactions.jsonl` — the
         * durable audit trail of every conversation rewrite (registered /
         * folded / pruned / compacted with token accounts). Survives the
         * in-memory ledger being discarded with the loop.
         */
        fun logLedgerEntry(entry: CompactionLedger.Entry) {
            if (!enabled) return
            try {
                val payload = when (entry) {
                    is CompactionLedger.Entry.ResultRegistered -> mapOf(
                        "type" to "registered",
                        "callId" to entry.callId,
                        "messageIndex" to entry.messageIndex,
                    )
                    is CompactionLedger.Entry.Folded -> mapOf(
                        "type" to "folded",
                        "callId" to entry.callId,
                        "originalIndex" to entry.originalIndex,
                        "compactIndex" to entry.compactIndex,
                    )
                    is CompactionLedger.Entry.Pruned -> mapOf(
                        "type" to "pruned",
                        "callId" to entry.callId,
                        "shadowedIndex" to entry.shadowedIndex,
                        "replacementIndex" to entry.replacementIndex,
                        "shadowedTokens" to entry.shadowedTokens,
                        "removedTokens" to entry.removedTokens,
                    )
                    is CompactionLedger.Entry.Compacted -> mapOf(
                        "type" to "compacted",
                        "compactionId" to entry.compactionId,
                        "reason" to entry.reason,
                        "shadowedRange" to "${entry.shadowedRange.first}-${entry.shadowedRange.last}",
                        "replacementIndex" to entry.replacementIndex,
                        "shadowedTokens" to entry.shadowedTokens,
                        "replacementTokens" to entry.replacementTokens,
                        "failure" to entry.failure,
                    )
                }
                jsonlWriter.writeTo(
                    mapOf("timestamp" to AppPaths.fromNow()) + payload,
                    runDir.resolve("cli-compactions.jsonl"),
                )
            } catch (e: Exception) {
                logger.warn("Failed to write CLI compaction ledger trace: {}", e.message)
            }
        }

        /** Best-effort page URL: from the CLI arguments, then from the result text. */
        private fun extractPageUrl(argumentsJson: String?, resultText: String): String? {
            argumentsJson?.let { args ->
                URL_IN_TEXT.find(args)?.value?.trimEnd('"', '\'', ')', ']')?.let { return it }
            }
            val fromText = resultText.lineSequence()
                .mapNotNull { line ->
                    URL_LINE_PATTERN.find(line)?.groupValues?.get(1)?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
                .firstOrNull()
            return fromText?.trimEnd('"', '\'', ')', ']')
        }

        /** Best-effort page title from the result text (first `title:`-ish line). */
        private fun extractPageTitle(resultText: String): String? =
            resultText.lineSequence()
                .mapNotNull { line -> TITLE_LINE_PATTERN.find(line)?.groupValues?.get(1)?.trim() }
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.take(200)

        companion object {
            /** Any http(s) URL inside the b4.run command string. */
            private val URL_IN_TEXT = Regex("""https?://[^\s"')\]]+""")
            /** `url: <value>` / `page url: <value>` / `URL = <value>` style lines. */
            private val URL_LINE_PATTERN = Regex(
                """(?im)^\s*(?:page\s+)?(?:url|URL)\s*[:=]\s*(\S.*)$"""
            )
            /** `title: <value>` / `page title: <value>` style lines. */
            private val TITLE_LINE_PATTERN = Regex(
                """(?im)^\s*(?:page\s+)?title\s*[:=]\s*(.+)$"""
            )
        }

        /** Append a run-level event (run.start / overflow / complete). */
        fun logEvent(event: String, detail: Map<String, Any?>) {
            if (!enabled) return
            try {
                val payload = mapOf(
                    "timestamp" to AppPaths.fromNow(),
                    "event" to event,
                ) + detail
                jsonlWriter.writeTo(payload, runDir.resolve("cli-events.jsonl"))
            } catch (e: Exception) {
                logger.warn("Failed to write CLI event trace: {}", e.message)
            }
        }

        private fun serializeMessage(message: ChatMessage): Map<String, Any?> = when (message) {
            is SystemMessage -> mapOf("type" to "system", "text" to message.text())
            is UserMessage -> mapOf("type" to "user", "text" to (message.singleText() ?: message.toString()))
            is AiMessage -> mapOf(
                "type" to "ai",
                "text" to (message.text() ?: ""),
                "toolExecutionRequests" to message.toolExecutionRequests().orEmpty().map { request ->
                    mapOf(
                        "name" to request.name(),
                        "arguments" to (request.arguments() ?: "{}"),
                    )
                },
            )
            is ToolExecutionResultMessage -> mapOf(
                "type" to "tool_result",
                "toolName" to message.toolName(),
                "text" to message.text(),
            )
            else -> mapOf("type" to "unknown", "text" to message.toString())
        }
    }

    /** Auto context compression for the CLI tool loop (long multi-step tasks). */
    private fun cliToolLoopCompressor(
        specs: List<dev.langchain4j.agent.tool.ToolSpecification>,
        ledger: CompactionLedger,
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
            retainLatestPageView = conf.getBoolean("browser4.agent.toolLoop.retainLatestPageView", true),
            viewToolNames = cliViewToolNames(conf),
            maxResultTokens = conf.getLong("browser4.agent.toolLoop.maxToolResultTokens", 20_000L)
                .coerceAtLeast(0L),
            // Knowledge documents (system.skillDoc results) must reach the
            // model whole — never shred them into head+tail shards.
            protectedToolNames = (conf.get("browser4.agent.toolLoop.protectedToolNames")
                ?: "system_skillDoc")
                .split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet(),
            ledger = ledger,
            requireShrink = conf.getBoolean("browser4.agent.toolLoop.requireShrink", true),
            summarizationRetries = conf.getLong("browser4.agent.toolLoop.summarizationRetries", 1L)
                .toInt().coerceIn(0, 5),
            audit = conf.getBoolean("browser4.agent.toolLoop.auditCompaction", true),
        ) { prefix -> summarizeCliLoop(prefix, specs) }
    }

    /**
     * Web-context deduper for the CLI agent: folds repeated page content at
     * append time. See docs-dev/copilot/web-page-context-optimization-design.md.
     */
    private fun cliPageViewDeduper(ledger: CompactionLedger): PageViewDeduper? {
        val conf = session.sessionConfig
        if (!conf.getBoolean("browser4.agent.toolLoop.pageViewDedupEnabled", true)) return null
        return PageViewDeduper(
            enabled = true,
            diffEnabled = conf.getBoolean("browser4.agent.toolLoop.pageViewDiffEnabled", true),
            diffMaxChars = conf.getLong("browser4.agent.toolLoop.pageViewDiffMaxChars", 3_000L)
                .toInt().coerceAtLeast(500),
            digestChars = conf.getLong("browser4.agent.toolLoop.pageViewDigestChars", 300L)
                .toInt().coerceAtLeast(100),
            duplicateFoldEnabled = conf.getBoolean("browser4.agent.toolLoop.duplicateFoldEnabled", true),
            viewToolNames = cliViewToolNames(conf),
            ledger = ledger,
        )
    }

    private fun cliViewToolNames(conf: ImmutableConfig): Set<String> =
        (conf.get("browser4.agent.toolLoop.viewToolNames")
            ?: "ariaSnapshot,textContent,snapshot,dump,htmlsnapshot,extract")
            .split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()

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
        cliLoopTracer.logEvent(
            "complete",
            mapOf(
                "summary" to (completion.summary ?: ""),
                "keyFindings" to (completion.keyFindings ?: emptyList<String>()),
                "filesChanged" to (completion.filesChanged ?: emptyList<String>()),
            )
        )
        onTaskCompletion(completion, context)
        stateManager.addToHistory(context.agentState)
        // Agent memory: record the successful completion (idempotent per task)
        // and schedule the L0→L1 knowledge deposit (PEM fusion, M3).
        agentMemory.sink.completed(
            context.sid, uuid.toString(), completion.summary ?: "",
            completion.keyFindings, completion.filesChanged, completion.problems,
            durationMs = cliRunStartedAt?.let { Duration.between(it, Instant.now()).toMillis() } ?: 0,
        )
        // User preference memory: explicit statements in the summary only
        // (e.g. "以后用中文输出" — no implicit inference).
        agentMemory.applyUserPreferences(completion.summary)
        agentMemory.consolidator?.schedule(context.sid)
        return ResolveResult(context, ActResultHelper.complete(completion))
    }

    /** Short SHA-256 hex (16 chars) of a text — page-view fingerprint. */
    private fun sha256Brief(text: String): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        digest.take(8).joinToString("") { "%02x".format(it) }
    }.getOrDefault(Integer.toHexString(text.hashCode()))

    /** System prompt for the CLI tool-loop engine: role + resident quick reference + hygiene rules. */
    private fun cliAgentSystemPrompt(): String = buildString {
        append(
            """
            You are a browser automation agent. Drive the browser EXCLUSIVELY through the
            browser4-cli tool via b4.run(...). A distilled CLI quick reference is embedded
            below — for full details fetch the bundled SKILL.md on demand with
            system.skillDoc("SKILL.md") and consult system.skillDoc(name) for topic
            reference docs.
            Use coding.* tools for file/workspace work when needed, and system.skillDoc(name)
            to read reference documents on demand.
            """.trimIndent()
        )
        append("\n\n### CLI Quick Reference (resident)\n\n")
        // Resident distilled skill: guaranteed present in every request (even
        // after compression), while the full SKILL.md stays on-demand.
        append(
            agentToolManager.system.skillDocStrict("quickstart.md")
                ?: agentToolManager.system.skillDocMetadata("SKILL.md")
        )
        append(
            """

            Load the complete SKILL.md with system.skillDoc("SKILL.md") before the first
            browser interaction, and use system.skillDoc("<reference>.md") for detailed
            topic guides (htmlsnapshot, x-sql, snapshot, crawl, swarm, ...) when needed.
            """.trimIndent()
        )
        append(
            """

            ### Context hygiene
            - Prefer `snapshot -v 0 --stdout` and targeted `htmlsnapshot get` over full page dumps.
            - After navigation, take a snapshot before deciding the next action.
            - Your working directory is ${agentWorkspaceDir}; write helper files
              (e.g. JS for eval) with relative paths — never create files in the
              repository root.
            - When you need to remember something across steps, write it with
              memory_note (the note stays visible and survives compression).
              When past tasks might be relevant, search them with memory_search.
            - When the task is finished, call system.taskComplete(
              summary=..., keyFindings=[...], filesChanged=[...], problems=[...]).
            """.trimIndent()
        )
    }

    @Deprecated("This is the legacy branch for agent.run(), and will be removed in the further")
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
    @Deprecated("This is the legacy branch for agent.run(), and will be removed in the further")
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
