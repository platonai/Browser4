package ai.platon.pulsar.agentic.inference

import ai.platon.browser4.driver.chrome.dom.model.BrowserUseState
import ai.platon.browser4.driver.chrome.dom.model.SnapshotOptions
import ai.platon.browser4.driver.chrome.dom.model.TabState
import ai.platon.pulsar.agentic.ActionOptions
import ai.platon.pulsar.agentic.ObserveOptions
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.inference.detail.PageStateTracker
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.model.DetailedActResult
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.agentic.model.ObserveElement
import ai.platon.pulsar.agentic.model.ProcessTrace
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.common.MessageWriter
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

const val AGENT_HISTORY_FILE_NAME = "state-history.jsonl"
const val TOOL_CALL_RESULT_FILE_NAME = "tool-call-result-history.jsonl"

/**
 * Manages agent state, execution contexts, and history tracking.
 *
 * This class is responsible for:
 * - Creating and managing execution contexts for each step
 * - Maintaining state history for all executed actions
 * - Tracking process traces for debugging
 * - Managing the lifecycle of contexts (creation, activation, cleanup)
 *
 * **Context Management**:
 * - `_baseContext`: The initial context created when an agent session starts
 * - `_activeContext`: The currently active context being processed
 * - `contexts`: List of all contexts created during the session (cleaned periodically)
 *
 * **State History**:
 * - `_stateHistory`: Contains AgentState objects for successfully executed actions
 * - Limited to `config.maxHistorySize` entries to prevent unbounded growth
 *
 * **Process Trace**:
 * - `_processTrace`: Detailed trace of all events including failures
 * - Limited to 200 entries to prevent memory leaks
 * - Written to disk for debugging via `writeProcessTrace()`
 *
 * @param agent The agent actor using this state manager
 * @param pageStateTracker Tracks page state changes for detecting progress
 */
class AgentStateManager(
    val agent: BasicBrowserAgent,
    val pageStateTracker: PageStateTracker,
) {
    private val logger = getLogger(this)

    // for non-logback logs
    private val logDir: Path get() = agent.logDir

    private val config get() = agent.config
    private val driver get() = agent.activeDriver as PulsarWebDriver

    // Context management - see class KDoc for detailed explanation
    // _baseContext: The initial context (first in contexts list)
    private lateinit var _baseContext: ExecutionContext

    // _activeContext: The currently active context (last in contexts list)
    private var _activeContext: ExecutionContext? = null

    // contexts: All execution contexts created during this session
    // Cleaned periodically to max 100 entries to prevent memory leaks
    private val _contexts: MutableList<ExecutionContext> = mutableListOf()
    private val _stateHistory = AgentHistory()
    private val _processTrace = mutableListOf<ProcessTrace>()

    val stateHistory: AgentHistory get() = _stateHistory
    val processTrace: List<ProcessTrace> get() = _processTrace
    val contexts: List<ExecutionContext> get() = _contexts

    /**
     * Build the base execution context for the initial action. This is used for as a base context for all
     * subsequent contexts in the session. The step is set to 0 to indicate that it's the initial context,
     * and the event name can be used to differentiate it from other contexts (e.g., "resolve-init").
     * This context may not have a valid instruction or agent state yet.
     * */
    suspend fun buildBaseExecutionContext(action: ActionOptions, event: String): ExecutionContext {
        val context = buildExecutionContext(action.action, 0, event)
        _baseContext = context
        setActiveContext(context)
        return context
    }

    /**
     * Get the currently active context, or create one if it doesn't exist.
     *
     * This method handles two scenarios:
     * 1. First call: Creates base context and sets it as active
     * 2. Multi-act mode: Creates new context based on previous active context
     *
     * @param action The action options
     * @param event The event name for this context
     * @return The active execution context
     */
    suspend fun getOrCreateActiveContext(action: ActionOptions, event: String): ExecutionContext {
        if (_activeContext == null) {
            _baseContext = buildInitExecutionContext(action, event)
            setActiveContext(_baseContext)
        } else if (action.multiAct) {
            require(!action.fromRunLoop)
            val lastActiveContext = getActiveContext()
            val step = lastActiveContext.step + 1
            val context = buildExecutionContext(
                action.action, step, event = event,
                baseContext = lastActiveContext
            )
            setActiveContext(context)
        }

        return _activeContext!!
    }

    suspend fun getOrCreateActiveContext(options: ObserveOptions, event: String): ExecutionContext {
        if (_activeContext == null) {
            _baseContext = buildInitExecutionContext(options, event)
            setActiveContext(_baseContext)
        }
        return _activeContext!!
    }

    /**
     * Get the currently active context.
     *
     * Note: This method requires the actor to be initialized (i.e., at least one context created).
     * Use `getOrCreateActiveContext()` if you want automatic context creation.
     *
     * @return The active execution context
     * @throws IllegalArgumentException if actor not initialized
     */
    fun getActiveContext(): ExecutionContext {
        val context = requireNotNull(_activeContext) { "Actor not initialized, call act(action: ActionOptions) first!" }
        require(context == _contexts.last()) { "Active context should be the last context in the list. Context list size: ${_contexts.size}" }
        return context
    }

    /**
     * Set the active context and add it to the contexts list.
     *
     * @param context The context to set as active
     */
    fun setActiveContext(context: ExecutionContext) {
        _activeContext = context
        if (_contexts.lastOrNull() == context) {
            logger.warn("Context has been already added | sid=${context.sid}")
            return
        }
        _contexts.add(context)
    }

    private suspend fun buildInitExecutionContext(action: ActionOptions, event: String): ExecutionContext {
        val context = buildExecutionContext(action.action, 1, event)
        return context
    }

    /**
     * Build the initial execution context for an observe event. This is used when an observe event is triggered without a prior act context (e.g., from an external trigger or at the start of a session).
     * The instruction is taken from the ObserveOptions, and the step is set to 1. The event name can be used to differentiate this context from act contexts. An optional base context can be provided for inheritance, but it's not required for the initial observe context.
     * @param options The observe options containing the instruction and other parameters
     * @param event The event name for this context (e.g., "observe-init")
     * @return A new ExecutionContext instance initialized with the instruction from the options, step set to 1, and the provided event name.
     * */
    private suspend fun buildInitExecutionContext(
        options: ObserveOptions,
        event: String
    ): ExecutionContext {
        val instruction = options.instruction ?: ""
        val context = buildExecutionContext(instruction, 1, event)
        return context
    }

    /**
     * Build a new execution context based on the provided instruction, step, event, and optional base context.
     *
     * The instruction should remain consistent across steps in the same session to maintain a clear goal. The step number should increment by 1 for each new context derived from the same base context. The event name can be used to differentiate between different types of contexts (e.g., "act", "observe", etc.) but should also follow a consistent naming convention for clarity.
     *
     * @param instruction The user's instruction (should be the same for all steps in the session)
     * @param step The current step number (starting from 1)
     * @param event The event name for this context (e.g., "act", "observe", "extract", etc.)
     * @param baseContext An optional base context that the new context can inherit from. If provided, the new context will validate that the instruction is the same and the step is incremented by 1 compared to the base context.
     * @return A new ExecutionContext instance with the provided parameters and inherited properties from the base context if applicable.
     * */
    suspend fun buildExecutionContext(
        instruction: String,
        step: Int,
        event: String,
        baseContext: ExecutionContext? = null
    ): ExecutionContext {
        val context = buildExecutionContextInternal(instruction, step, event, baseContext = baseContext)
        return context
    }

    /**
     * Build a standalone execution context for an instruction that is not part of a multistep process. This is used for
     * one-off instructions that don't require step tracking or inheritance from a base context.
     *
     * The step is set to 0 to indicate that it's a standalone context, and the event name can be used to differentiate
     * it from multistep contexts.
     *
     * @param instruction The user's instruction for this standalone context
     * @param event The event name for this context (e.g., "one-off", "external-trigger", etc.)
     * @return A new ExecutionContext instance for the standalone instruction, initialized with the provided instruction
     * and event, and step set to 0 to indicate it's not part of a multistep process.
     * */
    suspend fun buildStandaloneExecutionContext(instruction: String, event: String): ExecutionContext {
        val context = buildExecutionContextInternal(instruction, 0, event)
        return context
    }

    /**
     * The user's original instruction should be only one instruction for the whole session, and should not be
     * changed in different steps. The reason is that we want to keep the original instruction as the main goal
     * of the session, and use the step and event to track the progress.
     * If we change the instruction in different steps, it may cause confusion and make it harder to track the progress.
     *
     * @param instruction The user's instruction (should be the same for all steps in the session)
     * @param step The current step number (starting from 1)
     * @param event The event name for this context (e.g., "act", "observe", "extract", etc.)
     * @param baseContext An optional base context that the new context can inherit from. If provided, the new context
     * will validate that the instruction is the same and the step is incremented by 1 compared to the base context.
     * */
    private suspend fun buildExecutionContextInternal(
        instruction: String,
        step: Int,
        event: String,
        baseContext: ExecutionContext? = null
    ): ExecutionContext {
        val sessionId = baseContext?.sessionId ?: UUID.randomUUID().toString()
        val prevAgentState = baseContext?.agentState
        val currentAgentState = getAgentState(instruction, step, prevAgentState)

        if (baseContext != null) {
            require(instruction == baseContext.instruction) { "Instruction should be the same as base context. instruction=$instruction vs baseInstruction=${baseContext.instruction}" }
            require(step == baseContext.step + 1) { "Step should be incremented by 1 from base context. step=$step vs baseStep=${baseContext.step}" }
            val context = ExecutionContext(
                instruction = baseContext.instruction,
                step = step,
                event = event,
                targetUrl = prevAgentState?.browserUseState?.browserState?.url,
                sessionId = baseContext.sessionId,
                stepStartTime = Instant.now(),
                agentState = currentAgentState,
                config = baseContext.config,
                stateHistory = _stateHistory,
                stateHistoryPath = resolveSessionLogDir(sessionId).resolve(AGENT_HISTORY_FILE_NAME).toAbsolutePath().toString()
            )

            writeExecutionContext(context)
            writeAgentState(currentAgentState, context.sessionId)
            return context
        }

        val context = ExecutionContext(
            instruction = instruction,
            step = step,
            event = event,
            sessionId = sessionId,
            agentState = currentAgentState,
            config = config,
            stateHistory = _stateHistory,
            stateHistoryPath = resolveSessionLogDir(sessionId).resolve(AGENT_HISTORY_FILE_NAME).toAbsolutePath().toString()
        )
        writeExecutionContext(context)
        writeAgentState(currentAgentState, context.sessionId)
        return context
    }

    suspend fun getAgentState(instruction: String, step: Int, prevAgentState: AgentState? = null): AgentState {
        val browserUseState = getBrowserUseState()
        val agentState = AgentState(
            instruction = instruction,
            step = step,
            browserUseState = browserUseState,
            prevState = prevAgentState
        )
        return agentState
    }

    suspend fun updateBrowserUseState(context: ExecutionContext): BrowserUseState {
        val browserUseState = getBrowserUseState()
        context.agentState.browserUseState = browserUseState
        return browserUseState
    }

    fun updateAgentState(context: ExecutionContext, detailedActResult: DetailedActResult) {
        val observeElement = requireNotNull(detailedActResult.actionDescription.observeElement)
        val toolCall = requireNotNull(detailedActResult.actionDescription.toolCall)
        val toolCallResult = detailedActResult.toolCallResult
        // additional message appended to description
        val description = detailedActResult.description

        context.agentState.actionDescription = detailedActResult.actionDescription
        context.agentState.toolCallResult = toolCallResult

        require(context.agentState.toolCallResult?.actionDescription == context.agentState.actionDescription)

        updateAgentState(context, observeElement, toolCall, toolCallResult, description)

        writeActionResult(context, detailedActResult)
    }

    fun updateAgentState(
        context: ExecutionContext,
        observeElement: ObserveElement,
        toolCall: ToolCall,
        toolCallResult: ToolCallResult? = null,
        description: String? = null,
        exception: Exception? = null
    ) {
        val agentState = requireNotNull(context.agentState)
        val computedStep = agentState.step.takeIf { it > 0 } ?: ((stateHistory.states.lastOrNull()?.step ?: 0) + 1)

        agentState.apply {
            step = computedStep
            event = context.event
            domain = toolCall.domain
            method = toolCall.method
            this.description = description
            this.exception = exception

            screenshotContentSummary = observeElement.screenshotContentSummary
            currentPageContentSummary = observeElement.currentPageContentSummary
            evaluationPreviousGoal = observeElement.evaluationPreviousGoal
            nextGoal = observeElement.nextGoal
            thinking = observeElement.thinking

            this.toolCallResult = toolCallResult
        }

        writeAgentState(agentState, context.sessionId)
    }

    /**
     * Make sure add to history at every end of step
     * */
    fun addToHistory(state: AgentState) {
        val history = _stateHistory.states
        synchronized(this) {
            if (history.lastOrNull() === state) {
                return
            }

            history.add(state)
            if (history.size > config.maxHistorySize * 2) {
                // Keep the latest maxHistorySize entries
                val remaining = history.takeLast(config.maxHistorySize)
                history.clear()
                history.addAll(remaining)
            }

            writeHistory(state)
        }
    }

    fun addTrace(
        state: AgentState?, event: String, items: Map<String, Any?> = emptyMap(),
        message: String? = null
    ) {
        val step = state?.step ?: 0
        val msg = message ?: state?.toString()

        val isComplete = state?.isComplete == true
        val trace = if (isComplete) {
            ProcessTrace(
                step = step,
                event = event,
                isComplete = true,
                agentState = state.toString(),
                items = items,
                message = msg
            )
        } else {
            ProcessTrace(
                step = step,
                event = event,
                method = state?.method,
                isComplete = false,
                agentState = state.toString(),
                expression = state?.toolCallResult?.actionDescription?.pseudoExpression,
                tcEvalResult = state?.toolCallResult?.evaluate?.value?.toString(),
                items = items,
                message = msg
            )
        }

        _processTrace.add(trace)
        writeProcessTrace(trace)
    }

    fun writeExecutionContext(context: ExecutionContext) {
        val fileName = "context.log"
        val jsonFileName = "context.jsonl"
        val sessionLogDir = resolveSessionLogDir(context.sessionId)
        MessageWriter.writeOnce(sessionLogDir.resolve(fileName), context.toString())
        MessageWriter.writeOnce(sessionLogDir.resolve(jsonFileName), context.toJson())
    }

    fun writeAgentState(state: AgentState, sessionId: String) {
        val fileName = "state-history.log"
        val jsonFileName = AGENT_HISTORY_FILE_NAME
        val sessionLogDir = resolveSessionLogDir(sessionId)

        MessageWriter.writeOnce(sessionLogDir.resolve(fileName), "" + state.timestamp + " - " + state)
        MessageWriter.writeOnce(sessionLogDir.resolve(jsonFileName), state.toJson())
    }

    fun writeActionResult(context: ExecutionContext, result: DetailedActResult) {
        val fileName = "tool-call-result.log"
        val jsonFileName = TOOL_CALL_RESULT_FILE_NAME
        val sessionLogDir = resolveSessionLogDir(context.sessionId)

        MessageWriter.writeOnce(sessionLogDir.resolve(fileName), result.toString())
        MessageWriter.writeOnce(sessionLogDir.resolve(jsonFileName), result.toJson())
    }

    fun writeProcessTrace(trace: ProcessTrace) {
        val fileName = "agent-trace.log"
        val jsonFileName = "agent-trace.jsonl"
        val sessionId = _activeContext?.sessionId ?: "standalone"
        val sessionLogDir = resolveSessionLogDir(sessionId)
        MessageWriter.writeOnce(sessionLogDir.resolve(fileName), trace.toString())
        MessageWriter.writeOnce(sessionLogDir.resolve(jsonFileName), trace.toJson())
    }

    fun writeAllProcessTrace() {
        val sessionId = _activeContext?.sessionId ?: "standalone"
        val path = resolveSessionLogDir(sessionId).resolve("process_trace.log")
        MessageWriter.writeOnce(path, processTrace.joinToString("\n") { """🚩$it""" })
    }

    fun resolveSessionLogDir(sessionId: String): Path {
        val sessionLogDir = logDir.resolve("task-$sessionId")
        java.nio.file.Files.createDirectories(sessionLogDir)
        return sessionLogDir
    }

    fun clearHistory() {
        synchronized(this) {
            _stateHistory.states.clear()
        }
    }

    /**
     * Remove the last history entry if its step is >= provided step. Used for rollback on errors.
     */
    fun removeLastIfStep(step: Int) {
        synchronized(this) {
            val history = _stateHistory.states
            val last = history.lastOrNull()
            if (last != null && last.step >= step) {
                history.removeAt(history.size - 1)
            }
        }
    }

    private suspend fun getBrowserUseState(): BrowserUseState {
        pageStateTracker.waitForDOMSettle()

        val snapshotOptions = SnapshotOptions(
            maxDepth = 1000,
            includeAX = true,
            includeSnapshot = true,
            includeStyles = true,
            includePaintOrder = true,
            includeDOMRects = true,
            includeScrollAnalysis = true,
            includeVisibility = true,
            includeInteractivity = true
        )

        // Add timeout to prevent hanging on DOM snapshot operations
        return withTimeout(30_000) {
            val baseState = driver.snapshotService.getBrowserUseState(snapshotOptions = snapshotOptions)
            injectTabsInfo(baseState)
        }
    }

    /**
     * Inject tabs information into BrowserUseState.
     * Collects all tabs from the current browser and marks the active tab.
     */
    private suspend fun injectTabsInfo(baseState: BrowserUseState): BrowserUseState {
        val currentDriver = this.driver
        val browser = currentDriver.browser

        // fetch all drivers
        browser.listDrivers()
        val tabs = browser.drivers
            .filter { it.value is AbstractWebDriver && (it.value as AbstractWebDriver).isConnectable }
            .map { (tabId, driver) ->
                require(driver is AbstractWebDriver)
                require(tabId == driver.guid) { "Tab ID mismatch: tabId=$tabId vs driver.id=${driver.guid}" }

                val url = runCatching { driver.currentUrl() }
                    .onFailure { logger.warn("Failed to open web driver $driver", it) }
                    .getOrNull() ?: "about:blank"

                val title = runCatching { driver.evaluate("document.title", "") }.getOrNull() ?: ""

                TabState(
                    id = tabId, driverId = driver.id, url = url, title = title, active = (driver == currentDriver)
                )
            }

        val activeTabId = browser.drivers.entries.find { it.value == currentDriver }?.key

        val enhancedBrowserState = baseState.browserState.copy(
            tabs = tabs, activeTabId = activeTabId
        )

        return BrowserUseState(
            browserState = enhancedBrowserState, domState = baseState.domState
        )
    }

    private fun writeHistory(state: AgentState) {
        val sessionId = _activeContext?.sessionId ?: return
        val sessionLogDir = resolveSessionLogDir(sessionId)
        MessageWriter.writeOnce(sessionLogDir.resolve("history.log"), state.toString())
        MessageWriter.writeOnce(sessionLogDir.resolve("history.jsonl"), state.toJson())
    }
}
