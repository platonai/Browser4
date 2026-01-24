package ai.platon.pulsar.agentic.inference.detail

import ai.platon.browser4.driver.chrome.dom.model.BrowserUseState
import ai.platon.browser4.driver.chrome.dom.model.SnapshotOptions
import ai.platon.browser4.driver.chrome.dom.model.TabState
import ai.platon.pulsar.agentic.ActionOptions
import ai.platon.pulsar.agentic.ObserveOptions
import ai.platon.pulsar.agentic.agents.BrowserAgentActor
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.MessageWriter
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.time.Instant
import java.util.*

/**
 * Manages agent state, execution contexts, and history tracking.
 * 
 * This class is the central coordinator for state management in the agent's
 * autonomous execution loop. It maintains three interconnected state representations:
 * execution contexts, state history, and process traces.
 * 
 * ## Responsibilities
 * - **Context Lifecycle**: Creating, activating, and cleaning up execution contexts
 * - **History Management**: Recording successfully executed actions in state history
 * - **Trace Logging**: Maintaining detailed process traces for debugging
 * - **Memory Management**: Preventing unbounded growth through periodic cleanup
 * 
 * ## Context Management Architecture
 * 
 * Three context references work together to track execution state:
 * 
 * ### `_baseContext` (Initial Context)
 * - **Purpose**: The first context created when a resolve() session starts
 * - **Lifecycle**: Created once via `buildBaseExecutionContext()`, never changes
 * - **Step Number**: Always 0 (metadata context, not in history)
 * - **Usage**: Provides session ID and configuration for subsequent contexts
 * 
 * ### `_activeContext` (Current Context)
 * - **Purpose**: The context currently being executed
 * - **Lifecycle**: Updated via `setActiveContext()` for each new step
 * - **Invariant**: Always equals `contexts.last()` when non-null
 * - **Usage**: Primary reference for current step's execution state
 * 
 * ### `contexts` (All Contexts List)
 * - **Purpose**: Complete history of all contexts created in this session
 * - **Lifecycle**: Grows with each step, trimmed when exceeding 100 entries
 * - **Cleanup**: Keeps most recent 50 contexts when trimming
 * - **Usage**: Provides full context history for debugging and rollback
 * 
 * ## State Transition Flow
 * 
 * ```
 * resolve() start:
 *   1. buildBaseExecutionContext(step=0) → _baseContext
 *   2. setActiveContext(_baseContext) → _activeContext, contexts += _baseContext
 * 
 * Each step in loop:
 *   1. buildExecutionContext(step=N, baseContext=previous) → newContext
 *   2. setActiveContext(newContext) → _activeContext, contexts += newContext
 *   3. Execute step (observe, act, update state)
 *   4. addToHistory(newContext.agentState) → _stateHistory.states += state
 *   5. addTrace(...) → _processTrace += trace
 * 
 * Cleanup (every 50 steps or on demand):
 *   - clearUpHistory() trims contexts, _stateHistory, _processTrace
 *   - Keeps most recent entries to prevent memory leaks
 * ```
 * 
 * ## State vs Trace vs Context
 * 
 * - **AgentState** (in _stateHistory): Successfully executed tool actions only
 * - **ProcessTrace** (in _processTrace): All events including failures, retries
 * - **ExecutionContext**: Execution metadata + reference to current AgentState
 * 
 * ## Memory Management
 * 
 * Automatic cleanup prevents unbounded growth:
 * - `_stateHistory.states`: Limited to `config.maxHistorySize * 2`, trimmed to `maxHistorySize`
 * - `contexts`: Limited to 100 entries, trimmed to 50
 * - `_processTrace`: Limited to 200 entries, trimmed to 100
 * 
 * @param agent The agent actor using this state manager
 * @param pageStateTracker Tracks page state changes for detecting progress
 */
class AgentStateManager(
    val agent: BrowserAgentActor,
    val pageStateTracker: PageStateTracker,
) {
    private val logger = getLogger(this)
    // for non-logback logs
    val auxLogDir: Path get() = AppPaths.detectAuxiliaryLogDir().resolve("agent")

    private val _stateHistory = AgentHistory()
    private val _processTrace = mutableListOf<ProcessTrace>()
    private val config get() = agent.config

    // Context management - see class KDoc for detailed explanation
    // _baseContext: The initial context (first in contexts list)
    private lateinit var _baseContext: ExecutionContext
    // _activeContext: The currently active context (last in contexts list)
    private var _activeContext: ExecutionContext? = null
    // contexts: All execution contexts created during this session
    // Cleaned periodically to max 100 entries to prevent memory leaks
    private val contexts: MutableList<ExecutionContext> = mutableListOf()

    val driver get() = agent.activeDriver as PulsarWebDriver
    val stateHistory: AgentHistory get() = _stateHistory
    val processTrace: List<ProcessTrace> get() = _processTrace

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
            require(!action.fromResolve)
            val lastActiveContext = getActiveContext()
            val step = lastActiveContext.step + 1
            val context = buildExecutionContext(action.action, step, event = "act-$step",
                baseContext = lastActiveContext)
            setActiveContext(context)
        }

        return _activeContext!!
    }

    suspend fun getOrCreateActiveContext(options: ObserveOptions): ExecutionContext {
        if (_activeContext == null) {
//            val instruction = promptBuilder.initObserveUserInstruction(options.instruction).instruction?.content
//            baseContext = buildInitExecutionContext(options.copy(instruction = instruction), "observe")
            _baseContext = buildInitExecutionContext(options, "observe")
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
        require(context == contexts.last()) { "Active context should be the last context in the list. Context list size: ${contexts.size}" }
        return context
    }

    /**
     * Set the active context and add it to the contexts list.
     * 
     * @param context The context to set as active
     */
    fun setActiveContext(context: ExecutionContext) {
        _activeContext = context
        if (contexts.lastOrNull() == context) {
            logger.warn("Context has been already added | sid=${context.sid}")
            return
        }
        contexts.add(context)
    }

    suspend fun buildBaseExecutionContext(action: ActionOptions, event: String): ExecutionContext {
        val context = buildExecutionContext(action.action, 0, event)
        // action.setContext(context)
        _baseContext = context
        return context
    }

    suspend fun buildInitExecutionContext(action: ActionOptions, event: String): ExecutionContext {
        val context = buildExecutionContext(action.action, 1, event)
        // action.setContext(context)
        return context
    }

    suspend fun buildInitExecutionContext(
        options: ObserveOptions,
        event: String,
        baseContext: ExecutionContext? = null
    ): ExecutionContext {
        val instruction = options.instruction ?: ""
        val context = buildExecutionContext(instruction, 1, event, baseContext = baseContext)
        // options.setContext(context)
        return context
    }

    suspend fun buildExecutionContext(
        /**
         * The user's instruction
         * */
        instruction: String,
        step: Int,
        event: String,
        /**
         * A base context that the new context can inherit from
         * */
        baseContext: ExecutionContext? = null
    ): ExecutionContext {
        val context = buildExecutionContext0(instruction, step, event, baseContext = baseContext)
        return context
    }

    suspend fun buildIndependentExecutionContext(
        /**
         * The user's instruction
         * */
        instruction: String,
        step: Int,
        event: String,
        /**
         * A base context that the new context can inherit from
         * */
        baseContext: ExecutionContext? = null
    ): ExecutionContext {
        val context = buildExecutionContext0(instruction, step, event, baseContext = baseContext)
        return context
    }

    private suspend fun buildExecutionContext0(
        /**
         * The user's instruction
         * */
        instruction: String,
        step: Int,
        event: String,
        /**
         * A base context that the new context can inherit from
         * */
        baseContext: ExecutionContext? = null
    ): ExecutionContext {
        // val step = (baseContext?.step ?: -1) + 1

        val sessionId = baseContext?.sessionId ?: UUID.randomUUID().toString()
        val prevAgentState = baseContext?.agentState
        val currentAgentState = getAgentState(instruction, step, prevAgentState)

        if (baseContext != null) {
            return ExecutionContext(
                instruction = baseContext.instruction,
                step = step,
                event = event,
                targetUrl = prevAgentState?.browserUseState?.browserState?.url,
                sessionId = baseContext.sessionId,
                stepStartTime = Instant.now(),
                agentState = currentAgentState,
                config = baseContext.config,
                stateHistory = _stateHistory
            )
        }

        return ExecutionContext(
            instruction = instruction,
            step = step,
            event = "init",
            sessionId = sessionId,
            agentState = currentAgentState,
            config = config,
            stateHistory = _stateHistory,
        )
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

    suspend fun syncBrowserUseState(context: ExecutionContext): BrowserUseState {
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

        updateAgentState(context, observeElement, toolCall, toolCallResult, description)
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
    }

    /**
     * Make sure add to history at every end of step
     * */
    fun addToHistory(state: AgentState) {
//        val trace = ProcessTrace(
//            state.step,
//            state.method,
//            agentState = state.toString()
//        )

        val history = _stateHistory.states
        synchronized(this) {
            history.add(state)
            if (history.size > config.maxHistorySize * 2) {
                // Keep the latest maxHistorySize entries
                val remaining = history.takeLast(config.maxHistorySize)
                history.clear()
                history.addAll(remaining)
            }
            // _processTrace.add(trace)
        }
    }

    fun addTrace(
        state: AgentState?, items: Map<String, Any?> = emptyMap(), event: String? = null, message: String? = null
    ) {
        val step = state?.step ?: 0
        val msg = message ?: state?.toString()

        val isComplete = state?.isComplete == true
        val trace = if (isComplete) {
            ProcessTrace(
                step = step,
                event = event ?: state.event,
                isComplete = true,
                agentState = state.toString(),
                items = items,
                message = msg
            )
        } else {
            ProcessTrace(
                step = step,
                event = event ?: state?.event,
                method = state?.method,
                isComplete = false,
                agentState = state.toString(),
                expression = state?.toolCallResult?.actionDescription?.pseudoExpression,
                tcEvalResult = state?.toolCallResult?.evaluate?.value,
                items = items,
                message = msg
            )
        }

        _processTrace.add(trace)
    }

    fun writeProcessTrace() {
        val path = auxLogDir.resolve("processTrace").resolve("processTrace_${AppPaths.fromNow()}.log")
        MessageWriter.writeOnce(path, processTrace.joinToString("\n") { """🚩$it""" })
    }

    fun clearUpHistory(toRemove: Int) {
        synchronized(this) {
            if (toRemove > 0) {
                val history = _stateHistory.states
                val safeToRemove = toRemove.coerceAtMost(history.size)
                if (safeToRemove > 0) {
                    val remaining = history.drop(safeToRemove)
                    history.clear()
                    history.addAll(remaining)
                }
            }
            
            // Also cleanup contexts list to prevent unbounded growth
            val maxContextsSize = 100
            if (contexts.size > maxContextsSize) {
                val toRemoveContexts = contexts.size - maxContextsSize / 2
                val remainingContexts = contexts.drop(toRemoveContexts)
                contexts.clear()
                contexts.addAll(remainingContexts)
                // Update active context reference if it was removed
                if (_activeContext != null && _activeContext !in contexts) {
                    _activeContext = contexts.lastOrNull()
                }
            }
            
            // Also cleanup process trace to prevent unbounded growth
            val maxTraceSize = 200
            if (_processTrace.size > maxTraceSize) {
                val toRemoveTrace = _processTrace.size - maxTraceSize / 2
                val remainingTrace = _processTrace.drop(toRemoveTrace)
                _processTrace.clear()
                _processTrace.addAll(remainingTrace)
            }
        }
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
            val baseState = driver.domService.getBrowserUseState(snapshotOptions = snapshotOptions)
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
        val tabs = browser.drivers.map { (tabId, driver) ->
            val url = try {
                driver.currentUrl()
            } catch (_: Exception) {
                "about:blank"
            }
            val title = try {
                driver.evaluate("document.title").toString().takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
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
}
