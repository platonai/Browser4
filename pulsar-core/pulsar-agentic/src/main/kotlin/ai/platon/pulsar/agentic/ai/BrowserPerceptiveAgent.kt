package ai.platon.pulsar.agentic.ai

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.ai.agent.*
import ai.platon.pulsar.agentic.ai.agent.detail.*
import ai.platon.pulsar.agentic.ai.support.ActionExecutionService
import ai.platon.pulsar.agentic.ai.support.AgentTool
import ai.platon.pulsar.agentic.ai.support.ToolCallExecutor
import ai.platon.pulsar.agentic.ai.tta.ActionDescription
import ai.platon.pulsar.agentic.ai.tta.ActionExecuteResult
import ai.platon.pulsar.agentic.ai.tta.TextToAction
import ai.platon.pulsar.agentic.ai.tta.TextToActionParams
import ai.platon.pulsar.agentic.ai.tta.ToolCallResults
import ai.platon.pulsar.browser.driver.chrome.dom.Locator
import ai.platon.pulsar.browser.driver.chrome.dom.model.BrowserUseState
import ai.platon.pulsar.browser.driver.chrome.dom.model.SnapshotOptions
import ai.platon.pulsar.browser.driver.chrome.dom.model.TabState
import ai.platon.pulsar.browser.driver.chrome.dom.util.DomDebug
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.Pson
import ai.platon.pulsar.common.stringify
import ai.platon.pulsar.external.ChatModelException
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import ai.platon.pulsar.skeleton.ai.*
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.slf4j.helpers.MessageFormatter
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Configuration for enhanced error handling and retry mechanisms
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
    val resolveTimeoutMs: Long = 24.hours.inWholeMilliseconds
)

class BrowserPerceptiveAgent constructor(
    val session: AgenticSession,
    val maxSteps: Int = 100,
    val config: AgentConfig = AgentConfig(maxSteps = maxSteps)
) : PerceptiveAgent {
    private val ownerLogger = getLogger(this)
    private val slogger = StructuredAgentLogger(ownerLogger, config)
    private val logger = ownerLogger

    private val activeDriver get() = session.getOrCreateBoundDriver()
    private val baseDir = AppPaths.get("agent")
    private val conf get() = (activeDriver as AbstractWebDriver).settings.config

    private val tta by lazy { TextToAction(conf) }
    private val inference by lazy { InferenceEngine(session, tta.chatModel) }
    private val domService get() = inference.domService
    private val promptBuilder = PromptBuilder()

    // Reuse ActionExecutionService for unified action execution (Medium Priority #14)
    private val actionExecutionService = ActionExecutionService()

    // Helper classes for better code organization
    private val pageStateTracker = PageStateTracker(session, config)
    private val actionValidator = ActionValidator()

    // Action validation cache
    private val validationCache = ConcurrentHashMap<String, Boolean>()

    // Enhanced state management

    private val _stateHistory = mutableListOf<AgentState>()
    private val _processTrace = mutableListOf<String>()
    private val performanceMetrics = PerformanceMetrics()
    private val retryCounter = AtomicInteger(0)
    private val consecutiveFailureCounter = AtomicInteger(0)
    private val stepExecutionTimes = ConcurrentHashMap<Int, Long>()

    override val uuid = UUID.randomUUID()
    override val stateHistory: List<AgentState> get() = _stateHistory
    override val processTrace: List<String> get() = _processTrace

    constructor(
        driver: WebDriver, session: AgenticSession, maxSteps: Int = 100,
        config: AgentConfig = AgentConfig(maxSteps = maxSteps)
    ) : this(session, maxSteps = maxSteps, config = config) {
        session.bindDriver(driver)
    }

    override suspend fun resolve(problem: String): ActResult {
        val opts = ActionOptions(action = problem)
        return resolve(opts)
    }

    /**
     * Run `observe -> act -> observe -> act -> ...` loop to resolve the problem.
     * */
    override suspend fun resolve(action: ActionOptions): ActResult {
        Files.createDirectories(baseDir)
        val startTime = Instant.now()
        val sessionId = uuid.toString()

        // Add start history for better traceability (meta record only)
        val goal = Strings.compactLog(action.action, 160)
        processTrace(
            "🚀 resolve START session=${sessionId.take(8)} goal='$goal' " +
                    "maxSteps=${config.maxSteps} maxRetries=${config.maxRetries}"
        )

        // Overall timeout to prevent indefinite hangs for a full resolve session
        return try {
            val result = withTimeout(config.resolveTimeoutMs) {
                resolveProblemWithRetry(action, sessionId, startTime)
            }
            val dur = Duration.between(startTime, Instant.now()).toMillis()
            // Not a single-step action, keep it out of AgentState history
            processTrace("✅ resolve DONE session=${sessionId.take(8)} success=${result.success} dur=${dur}ms")
            result
        } catch (_: TimeoutCancellationException) {
            val msg = "⏳ Resolve timed out after ${config.resolveTimeoutMs}ms: ${action.action}"
            processTrace("⏳ resolve TIMEOUT: ${Strings.compactLog(action.action, 160)}")
            ActResult(success = false, message = msg, action = action.action)
        }
    }

    override suspend fun act(action: String): ActResult {
        val opts = ActionOptions(action = action)
        return act(opts)
    }

    /**
     * Execution with comprehensive error handling and retry mechanism.
     * Returns the final summary with enhanced error handling.
     * High Priority #1: Added overall timeout to prevent indefinite hangs.
     */
    override suspend fun act(action: ActionOptions): ActResult {
        return try {
            withTimeout(config.actTimeoutMs) {
                val messages = AgentMessageList()
                doObserveAct(action, messages)
            }
        } catch (_: TimeoutCancellationException) {
            val msg = "⏳ Action timed out after ${config.actTimeoutMs}ms: ${action.action}"
            processTrace("⏳ act TIMEOUT: ${action.action}")
            ActResult(success = false, message = msg, action = action.action)
        }
    }

    override suspend fun act(observe: ObserveResult): ActResult {
        val oe = observe.observeElements?.firstOrNull()
            ?: return ActResult(false, "No observation", action = observe.method)
        val toolCall0 = oe.toolCall ?: return ActResult(false, "No tool call", action = observe.method)

        val agentState = observe.agentState

        val patch = patchToolCall(toolCall0, observe)
        val toolCall = patch.toolCall ?: return ActResult(patch.success, patch.message, action = patch.action)
        val method = toolCall.method

        return try {
            val action = ActionDescription(observeElement = oe)

            val toolCallResults = doToolCallExecute(toolCall, action)

            logger.info("✅ Action executed | {} | {}/{} | {}",
                method, observe.locator, oe.cssSelector, oe.cssFriendlyExpressions)

            val msg = MessageFormatter.arrayFormat(
                "✅ Action executed | {} | {}/{} | {}",
                arrayOf(method, observe.locator, oe.cssSelector, oe.cssFriendlyExpressions))

            // Record exactly once for this executed action
            updateAgentState(agentState, true, toolCall, toolCallResults, oe, message = msg.message)

            ActResult(success = true, message = msg.message, action = toolCall.method)
        } catch (e: Exception) {
            logger.error("❌ observe.act execution failed sid={} msg={}", uuid.toString().take(8), e.message, e)
            val msg = e.message ?: "Execution failed"

            // Record failed action attempt once
            updateAgentState(agentState, false, toolCall, null, oe, message = msg)

            ActResult(success = false, message = msg, action = toolCall.method)
        }
    }

    override suspend fun extract(instruction: String): ExtractResult {
        val opts = ExtractOptions(instruction = instruction, schema = null)
        return extract(opts)
    }

    override suspend fun extract(options: ExtractOptions): ExtractResult {
        val instruction = promptBuilder.initExtractUserInstruction(options.instruction)
        val requestId = UUID.randomUUID().toString()
        logExtractStart(instruction, requestId)

        val schemaJson = buildSchemaJsonFromMap(options.schema)
        return try {
            val browserUseState = getBrowserUseState()
            val agentState = options.agentState ?: AgentState(
                0, options.instruction ?: "", browserUseState = browserUseState
            )

            val scrollState = browserUseState.browserState.scrollState
            val params = ExtractParams(
                instruction = instruction,
                agentState = agentState,
                browserUseState = browserUseState,
                schema = schemaJson,
                chunksTotal = scrollState.viewportsTotal,
                requestId = requestId,
                logInferenceToFile = config.enableStructuredLogging,
            )

            val resultNode = inference.extract(params)
            addHistoryExtract(instruction, requestId, true)
            ExtractResult(success = true, message = "OK", data = resultNode)
        } catch (e: Exception) {
            logger.error("❌ extract.error requestId={} msg={}", requestId.take(8), e.message, e)
            addHistoryExtract(instruction, requestId, false)
            ExtractResult(
                success = false, message = e.message ?: "extract failed", data = JsonNodeFactory.instance.objectNode()
            )
        }
    }

    override suspend fun observe(instruction: String): List<ObserveResult> {
        val opts = ObserveOptions(instruction = instruction, returnAction = null)
        return observe(opts)
    }

    override suspend fun observe(options: ObserveOptions): List<ObserveResult> {
        val messages = AgentMessageList()

        val instruction = promptBuilder.initObserveUserInstruction(options.instruction)
        messages.addUser(instruction, name = "instruction")

        val driver = session.boundDriver
        val currentUrl = driver?.currentUrl()
        val browserUseState = getBrowserUseState()
        val agentState = options.agentState ?: AgentState(
            1, options.instruction ?: "", browserUseState = browserUseState, url = currentUrl
        )

        val params = ObserveParams(
            agentState = agentState,
            browserUseState = browserUseState,
            requestId = UUID.randomUUID().toString(),
            returnAction = options.returnAction ?: false,
            logInferenceToFile = config.enableStructuredLogging,
            fromAct = false
        )

        // Do OBSERVE
        val action = doObserveForActionDescription(params, messages)

        val observeElements = listOfNotNull(action.observeElement)
        val results = observeElements.map { ele ->
            ObserveResult(
                agentState = agentState,
                locator = ele.locator,
                domain = ele.domain?.ifBlank { null },
                method = ele.method?.ifBlank { null },
                arguments = ele.arguments?.takeIf { it.isNotEmpty() },
                description = ele.description ?: "(No comment ...)",
                screenshotContentSummary = ele.screenshotContentSummary,
                currentPageContentSummary = ele.currentPageContentSummary,
                evaluationPreviousGoal = ele.evaluationPreviousGoal,
                nextGoal = ele.nextGoal,
                backendNodeId = ele.backendNodeId,
                observeElements = observeElements,
            )
        }

        return results
    }

    private suspend fun getBrowserUseState(): BrowserUseState {
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

        val baseState = domService.getBrowserUseState(snapshotOptions = snapshotOptions)

        // Inject tabs information
        return injectTabsInfo(baseState)
    }

    /**
     * Inject tabs information into BrowserUseState.
     * Collects all tabs from the current browser and marks the active tab.
     */
    private suspend fun injectTabsInfo(baseState: BrowserUseState): BrowserUseState {
        val currentDriver = session.boundDriver ?: return baseState
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

    private suspend fun doToolCallExecute(toolCall: ToolCall, action: ActionDescription): ToolCallResults {
        val driver = activeDriver

        val callResult = when (toolCall.domain) {
            "driver" -> actionExecutionService.execute(toolCall, driver)
            "browser" -> actionExecutionService.execute(toolCall, driver.browser)
            else -> throw IllegalArgumentException("❓ Unsupported domain: ${toolCall.domain} | $toolCall")
        }

        val method = toolCall.method
        when (method) {
            "switchTab" -> onDidSwitchTab(callResult)
            "navigateTo" -> onDidNavigateTo(driver, toolCall, callResult)
        }

        return ToolCallResults(action.expressions, listOf(callResult), action = action)
    }

    data class ToolCallPatch(
        val success: Boolean, val message: String, val action: String, val toolCall: ToolCall? = null
    )

    private suspend fun patchToolCall(toolCall: ToolCall, observe: ObserveResult): ToolCallPatch {
        val observeElement =
            observe.observeElements?.firstOrNull() ?: return ToolCallPatch(false, "No observe element", action = "")
        val method = toolCall.method.trim().takeIf { it.isNotEmpty() }
        val locator = observe.locator?.let { Locator.parse(it) }

        if (method == null || locator == null) {
            val msg = "⚠️ No valuable observations were made"
            // Not an executed action
            processTrace(msg)
            return ToolCallPatch(success = false, message = msg, action = "")
        }

        val selectorActions = AgentTool.SELECTOR_ACTIONS
        val lowerMethod = method
        val backendNodeId = observe.backendNodeId
        val selector =
            observe.backendNodeId?.let { "backend:$backendNodeId" } ?: observe.locator?.takeIf { it.isNotBlank() }
        if (lowerMethod in selectorActions) {
            toolCall.arguments["selector"] = selector
            if (selector == null) {
                val msg = "⚠️ No selector observation were made $locator | $observe"
                processTrace(msg)
                return ToolCallPatch(success = false, message = msg, action = method)
            }
        }

        val driver = requireNotNull(activeDriver)
        if (lowerMethod == "waitForNavigation") {
            if (toolCall.arguments["oldUrl"].isNullOrBlank()) {
                toolCall.arguments["oldUrl"] = driver.currentUrl()
            }
            if (toolCall.arguments["timeoutMillis"] == null) {
                toolCall.arguments["timeoutMillis"] = 5000L.toString()
            }
        }

        if (lowerMethod == "navigateTo") {
            val url = toolCall.arguments["url"]
            if (url == null) {
                val msg = "⚠️ No url observation were made | " + Pson.toJson(observe)
                processTrace(msg)
                return ToolCallPatch(false, msg, action = method)
            }

            if (!actionValidator.isSafeUrl(url)) {
                val msg = "⛔ Blocked unsafe URL: $url"
                processTrace(msg)
                return ToolCallPatch(false, msg, action = method)
            }
        }

        if (config.enablePreActionValidation) {
            val ok = actionValidator.validateToolCall(observeElement.toolCall)
            if (!ok) {
                val msg = "🚫 Tool call validation failed for $lowerMethod with selector ${selector?.take(120)}"
                val sid = uuid.toString().take(8)
                val curUrl = driver.currentUrl()
                logger.info("🚫 observe_act.validate.fail sid={} url={} msg={} ", sid, curUrl, msg)
                processTrace(msg)
                return ToolCallPatch(false, msg, action = method)
            }
        }

        return ToolCallPatch(true, "", method, toolCall = toolCall)
    }

    /**
     * Handle switching to a new tab by binding the target driver to the session.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun onDidSwitchTab(result: Any?) {
        val frontDriver = session.boundBrowser?.frontDriver
        val boundDriver = session.boundDriver
        if (frontDriver == null) {
            logger.warn("⚠️ No driver is in front after switchTab")
            return
        }

        if (frontDriver == boundDriver) {
            logger.warn("⚠️ The bound driver does not change after switchTab")
            return
        }

        session.bindDriver(frontDriver)
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun onDidNavigateTo(driver: WebDriver, toolCall: ToolCall, toolCallResult: Any?) {
        driver.waitForNavigation()
        driver.waitForSelector("body")
        delay(3000)
    }

    private suspend fun doObserveAct(action: ActionOptions, messages: AgentMessageList): ActResult {
        // 1) Build instruction for action-oriented observe
        val toolCalls = AgentTool.SUPPORTED_TOOL_CALLS

        promptBuilder.buildObserveActToolSpecsPrompt(messages, toolCalls, action.variables)

        val instruction = promptBuilder.buildObserveActToolUsePrompt(action.action)
        messages.addUser(instruction, "instruction")

        val browserUseState = getBrowserUseState()
        val driver = session.boundDriver
        val currentUrl = driver?.currentUrl()
        val agentState = action.agentState ?: AgentState(
            step = 1,
            instruction = action.action,
            action = "observeAct",
            browserUseState = browserUseState,
            url = currentUrl
        )

        val params = ObserveParams(
            agentState = agentState,
            browserUseState = browserUseState,
            requestId = UUID.randomUUID().toString(),
            returnAction = true,
            logInferenceToFile = config.enableStructuredLogging,
            fromAct = true,
        )

        // 3) Run observe with returnAction=true and fromAct=true so LLM returns an actionable method/args
        val observeResults = doObserveForAct(params, messages)

        if (observeResults.isEmpty()) {
            val msg = "⚠️ doObserveAct: No actionable element found"
            processTrace(msg)
            return ActResult(false, msg, action = action.action)
        }

        val resultsToTry = observeResults.take(config.maxResultsToTry)
        var lastError: String? = null

        for ((index, chosen) in resultsToTry.withIndex()) {
            val method = chosen.method?.trim().orEmpty()
            if (method.isBlank()) {
                lastError = "LLM returned no method for candidate ${index + 1}"
                continue
            }

            // 5) Execute action, and optionally wait for navigation if caller provided timeoutMs
            val driver = activeDriver
            val oldUrl = driver.currentUrl()
            val execResult = try {
                act(chosen)
            } catch (e: Exception) {
                lastError = "Execution failed for candidate ${index + 1}: ${e.message}"
                logger.warn("⚠️ Failed to execute candidate {}: {}", index + 1, e.message)
                continue
            }

            if (!execResult.success) {
                lastError = "Candidate ${index + 1} failed: ${execResult.message}"
                continue
            }

            // If a timeout is provided and the action likely triggers navigation, wait for navigation
            val timeoutMs = action.timeoutMs?.toLong()?.takeIf { it > 0 }
            val maybeNavMethod = method in AgentTool.MAY_NAVIGATE_ACTIONS
            if (timeoutMs != null && maybeNavMethod) {
                // High Priority #4: Fail explicitly on navigation timeout
                val remainingTime = driver.waitForNavigation(oldUrl, timeoutMs)
                if (remainingTime <= 0) {
                    val navError = "⏳ Navigation timeout after ${timeoutMs}ms for action: ${action.action}"
                    logger.warn(navError)
                    processTrace("⏳ act NAVIGATION_TIMEOUT: ${action.action}")
                    return ActResult(success = false, message = navError, action = action.action)
                }
            }

            // Success! Return with original action text (act(chosen) already recorded one history entry)
            processTrace("✅ act SUCCESS (candidate ${index + 1}/${resultsToTry.size}): ${action.action}")
            return execResult.copy(action = action.action)
        }

        // All candidates failed
        val msg = "❌ All ${resultsToTry.size} candidates failed. Last error: $lastError"
        processTrace(msg)
        return ActResult(false, msg, action = action.action)
    }

    private suspend fun doObserveForAct(params: ObserveParams, messages: AgentMessageList): List<ObserveResult> {
        requireNotNull(messages.instruction) { "User instruction is required | $messages" }

        val actionDescription = doObserveForActionDescription(params, messages)

        val observeElements = listOfNotNull(actionDescription.observeElement)
        return observeElements.map { ele ->
            ObserveResult(
                agentState = params.agentState,
                locator = ele.locator,
                domain = ele.domain?.ifBlank { null },
                method = ele.method?.ifBlank { null },
                arguments = ele.arguments?.takeIf { it.isNotEmpty() },
                description = ele.description ?: "(No comment ...)",
                screenshotContentSummary = ele.screenshotContentSummary,
                currentPageContentSummary = ele.currentPageContentSummary,
                evaluationPreviousGoal = ele.evaluationPreviousGoal,
                nextGoal = ele.nextGoal,
                backendNodeId = ele.backendNodeId,
                observeElements = observeElements,
            )
        }
    }

    private suspend fun doObserveForActionDescription(
        params: ObserveParams, messages: AgentMessageList
    ): ActionDescription {
        val instruction = requireNotNull(messages.instruction) { "User instruction is required | $messages" }
        val requestId: String = params.requestId
        logObserveStart(instruction.content, requestId)
        return try {
            val actionDescription = withTimeout(config.llmInferenceTimeoutMs) {
                inference.observe(params, messages)
            }

            val results = listOfNotNull(actionDescription.observeElement)
            addHistoryObserve(instruction.content, requestId, results.size, results.isNotEmpty())
            return actionDescription
        } catch (e: Exception) {
            logger.error("❌ observeAct.observe.error requestId={} msg={}", requestId.take(8), e.message, e)
            addHistoryObserve(instruction.content, requestId, 0, false)
            ActionDescription(errors = e.stringify())
        }
    }

    /** Default rich extraction schema (JSON Schema string) */
    private val defaultExtractionSchemaJson: String by lazy {
        val schema = ExtractionSchema(
            listOf(
                ExtractionField("title", type = "string", description = "Page title"), ExtractionField(
                    "content", type = "string", description = "Primary textual content of the page", required = false
                ), ExtractionField(
                    name = "links",
                    type = "array",
                    description = "Important hyperlinks on the page",
                    required = false,
                    items = ExtractionField(
                        name = "link", type = "object", properties = listOf(
                            ExtractionField("text", type = "string", description = "Anchor text", required = false),
                            ExtractionField("href", type = "string", description = "Href URL", required = false)
                        ), required = false
                    )
                )
            )
        )
        schema.toJsonSchema()
    }

    private fun buildSchemaJsonFromMap(schema: Map<String, String>?): String {
        if (schema == null || schema.isEmpty()) return defaultExtractionSchemaJson
        return legacyMapToExtractionSchema(schema).toJsonSchema()
    }

    private fun logExtractStart(instruction: String, requestId: String) {
        logger.info(
            "🔍 extract.start requestId={} instruction='{}'",
            requestId.take(8),
            PromptBuilder.compactPrompt(instruction, 200)
        )
    }

    private fun logObserveStart(instruction: String, requestId: String) {
        logger.info(
            "👀 observe.start requestId={} instruction='{}'",
            requestId.take(8),
            PromptBuilder.compactPrompt(instruction, 200)
        )
    }

    /**
     * history management: strictly record one AgentState per executed action with complete fields
     */
    private fun updateAgentState(
        agentState: AgentState,
        success: Boolean,
        toolCall: ToolCall? = null,
        @Suppress("UNUSED_PARAMETER") toolCallResults: ToolCallResults? = null,
        observe: ObserveElement? = null,
        message: String? = null
    ): AgentState {
        val computedStep = agentState.step.takeIf { it > 0 } ?: ((stateHistory.lastOrNull()?.step ?: 0) + 1)
        val descPrefix = if (success) "OK" else "FAIL"
        val descMsg = buildString {
            append(descPrefix)
            if (!message.isNullOrBlank()) {
                append(": ")
                append(Strings.compactLog(message, 200))
            }
        }

        val agentState = agentState.copy(
            step = computedStep,
            domain = toolCall?.domain,
            action = toolCall?.method,
            description = descMsg,
            screenshotContentSummary = observe?.screenshotContentSummary,
            currentPageContentSummary = observe?.currentPageContentSummary,
            evaluationPreviousGoal = observe?.evaluationPreviousGoal,
            nextGoal = observe?.nextGoal,
        )
        addToHistory(agentState)
        return agentState
    }

    private fun addHistoryExtract(instruction: String, requestId: String, success: Boolean) {
        val compactPrompt = PromptBuilder.compactPrompt(instruction, 200)
        // Extraction is not a tool action; keep it in record history only
        processTrace("🔍 extract[$requestId] ${if (success) "OK" else "FAIL"} $compactPrompt")
    }

    private fun addHistoryObserve(instruction: String, requestId: String, size: Int, success: Boolean) {
        processTrace(
            "👀 observe[$requestId] ${if (success) "OK" else "FAIL"} ${
                PromptBuilder.compactPrompt(instruction, 200)
            } -> $size elements"
        )
    }

    /**
     * Enhanced execution with comprehensive error handling and retry mechanisms
     * Returns the final summary with enhanced error handling.
     */
    private suspend fun resolveProblemWithRetry(
        action: ActionOptions, sessionId: String, startTime: Instant
    ): ActResult {
        var lastError: Exception? = null

        for (attempt in 0..config.maxRetries) {
            val attemptNo = attempt + 1
            processTrace("🔁 resolve ATTEMPT ${attemptNo}/${config.maxRetries + 1}")
            try {
                val res = doResolveProblem(action, sessionId, startTime, attempt)
                processTrace("✅ resolve ATTEMPT $attemptNo OK")
                return res
            } catch (e: PerceptiveAgentError.TransientError) {
                lastError = e
                logger.error(
                    "🔄 resolve.transient attempt={} sid={} msg={}",
                    attempt + 1, sessionId.take(8), e.message, e
                )
                if (attempt < config.maxRetries) {
                    val backoffMs = calculateRetryDelay(attempt)
                    processTrace("🔁 resolve RETRY $attemptNo cause=Transient delay=${backoffMs}ms msg=${e.message}")
                    delay(backoffMs)
                }
            } catch (e: PerceptiveAgentError.TimeoutError) {
                lastError = e
                logger.error("⏳ resolve.timeout attempt={} sid={} msg={}", attempt + 1, sessionId.take(8), e.message, e)
                if (attempt < config.maxRetries) {
                    val baseBackoffMs = config.baseRetryDelayMs
                    processTrace("🔁 resolve RETRY $attemptNo cause=Timeout delay=${baseBackoffMs}ms msg=${e.message}")
                    delay(baseBackoffMs)
                }
            } catch (e: Exception) {
                lastError = e
                logger.error(
                    "💥 resolve.unexpected attempt={} sid={} msg={}",
                    attempt + 1,
                    sessionId.take(8),
                    e.message,
                    e
                )
                if (shouldRetryError(e) && attempt < config.maxRetries) {
                    val backoffMs = calculateRetryDelay(attempt)
                    processTrace("🔁 resolve RETRY $attemptNo cause=Unexpected delay=${backoffMs}ms msg=${e.message}")
                    delay(backoffMs)
                } else {
                    // Non-retryable error, exit loop
                    break
                }
            }
        }

        processTrace("❌ resolve FAIL after ${config.maxRetries + 1} attempts: ${lastError?.message}")
        return ActResult(
            success = false,
            message = "Failed after ${config.maxRetries + 1} attempts. Last error: ${lastError?.message}",
            action = action.action
        )
    }

    /**
     * Main execution logic with enhanced error handling and monitoring.
     * Returns the final summary with enhanced error handling.
     */
    private suspend fun doResolveProblem(
        action: ActionOptions, sessionId: String, startTime: Instant, attempt: Int
    ): ActResult {
        val userRequest = action.action
        val context = ExecutionContext(sessionId, 0, "execute", getCurrentUrl())

        val sid = context.sessionId.take(8)
        logger.info(
            "🚀 agent.start sid={} step={} url={} instr='{}' attempt={} maxSteps={} maxRetries={}",
            sid, context.stepNumber, context.targetUrl, Strings.compactLog(userRequest, 100),
            attempt + 1, config.maxSteps, config.maxRetries
        )

        // agent general guide
        val systemMsg = PromptBuilder().buildOperatorSystemPrompt()
        var consecutiveNoOps = 0
        var step = 0

        var prevAgentState: AgentState? = null

        try {
            while (step < config.maxSteps) {
                step++
                val stepStartTime = Instant.now()
                val stepContext = context.copy(stepNumber = step, actionType = "step")

                // Step setup: ensure URL and settle DOM
                ensureReadyForStep(action)

                // Build AgentState and snapshot after settling
                val (agentState, browserUseState) = buildAgentStateForStep(step, action, prevAgentState)

                // Detect unchanged state for heuristics
                val unchangedCount = pageStateTracker.checkStateChange(browserUseState)
                if (unchangedCount >= 3) {
                    logger.info("⚠️ loop.warn sid={} step={} unchangedSteps={}", sid, step, unchangedCount)
                    consecutiveNoOps++
                }

                logger.info("▶️ step.exec sid={} step={}/{} noOps={}", sid, step, config.maxSteps, consecutiveNoOps)
                if (logger.isDebugEnabled) {
                    logger.debug("🧩 dom={}", DomDebug.summarize(browserUseState.domState))
                }

                // Memory cleanup at intervals
                if (step % config.memoryCleanupIntervalSteps == 0) {
                    performMemoryCleanup(stepContext)
                }

                // Optional screenshot
                val screenshotB64 = if (step % config.screenshotEveryNSteps == 0) {
                    captureScreenshotWithRetry(stepContext)
                } else null

                // Prepare messages for model
                val messages = buildMessagesForStep(systemMsg, userRequest, screenshotB64, prevAgentState)

                //**
                // Observe and Generate Action
                //**

                // Generate the action for this step
                val params = TextToActionParams(messages, agentState, screenshotB64)
                val stepAction = generateStepAction(params, context)

                // We will use the following code pattern
//                val observeOptions = ObserveOptions(
//                    instruction = agentState.instruction,
//                    returnAction = true,
//                    agentState = agentState
//                )
//                val observeResults = observe(observeOptions)
//
//                observeResults.forEach { observeResult ->
//                    val actResult = act(observeResult)
//                }

                // Keep reference to previous AgentState for next loop
                prevAgentState = agentState

                if (stepAction == null) {
                    consecutiveNoOps++
                    val stop = handleConsecutiveNoOps(consecutiveNoOps, step, stepContext)
                    if (stop) break
                    continue
                }

                // Check for task completion
                if (shouldTerminate(stepAction)) {
                    handleTaskCompletion(stepAction, step, stepContext)
                    break
                }

                val hasModelToolCall = stepAction.toolCall != null
                if (!hasModelToolCall) {
                    consecutiveNoOps++
                    val stop = handleConsecutiveNoOps(consecutiveNoOps, step, stepContext)
                    if (stop) break
                    continue
                }

                // Reset consecutive no-ops counter when we have a valid action
                consecutiveNoOps = 0

                //**
                // Execute Tool Call
                //**

                // Execute the tool call with enhanced error handling
                val exec = executeToolCall(stepAction, step, stepContext)

                if (exec != null) {
                    val observe = exec.action.observeElement
                    val toolCall = observe?.toolCall

                    updateAgentState(
                        agentState, true, toolCall, exec.toolCallResults, observe = observe, message = exec.summary
                    )

                    updatePerformanceMetrics(step, stepStartTime, true)

                    logger.info("🏁 step.done sid={} step={} summary={}", sid, step, exec.summary)
                } else {
                    // Treat validation failures or execution skips as no-ops; no AgentState record
                    consecutiveNoOps++
                    val stop = handleConsecutiveNoOps(consecutiveNoOps, step, stepContext)
                    if (stop) break
                    updatePerformanceMetrics(step, stepStartTime, false)
                }

                // Adaptive delay based on performance metrics
                delay(calculateAdaptiveDelay())
            }

            val executionTime = Duration.between(startTime, Instant.now())
            logger.info("✅ agent.done sid={} steps={} dur={}", sid, step, executionTime.toString())

            val summary = generateFinalSummary(userRequest, context)
            val ok = summary.state != ResponseState.OTHER
            return ActResult(success = ok, message = summary.content, action = userRequest)
        } catch (e: Exception) {
            val executionTime = Duration.between(startTime, Instant.now())
            logger.error(
                "💥 agent.fail sid={} steps={} dur={} err={}",
                sid,
                step,
                executionTime.toString(),
                e.message,
                e
            )
            throw classifyError(e, step)
        }
    }

    // Enhanced helper methods for improved functionality

    override fun toString(): String {
        return stateHistory.lastOrNull()?.toString() ?: "(no history)"
    }

    // --- Modularized helpers for doResolveProblem ---

    private suspend fun ensureReadyForStep(action: ActionOptions) {
        val driver = activeDriver
        val url = driver.url()
        if (url.isBlank() || url == "about:blank") {
            driver.navigateTo(AppConstants.SEARCH_ENGINE_URL)
        }
        val settleMs = action.domSettleTimeoutMs?.toLong()?.coerceAtLeast(0L) ?: config.domSettleTimeoutMs
        if (settleMs > 0) {
            pageStateTracker.waitForDOMSettle(settleMs, config.domSettleCheckIntervalMs)
        }
    }

    private suspend fun buildAgentStateForStep(
        step: Int, action: ActionOptions, prevAgentState: AgentState?
    ): Pair<AgentState, BrowserUseState> {
        val browserUseState = getBrowserUseState()
        val currentUrl = activeDriver.currentUrl()
        val agentState = AgentState(
            step = step,
            instruction = action.action,
            browserUseState = browserUseState,
            url = currentUrl,
            prevState = prevAgentState
        )
        return agentState to browserUseState
    }

    private fun buildMessagesForStep(
        systemMsg: String, userRequest: String, screenshotB64: String?, prevAgentState: AgentState?
    ): AgentMessageList {
        val messages = AgentMessageList()
        messages.addSystem(systemMsg)
        messages.addLast("user", promptBuilder.buildUserRequestMessage(userRequest), name = "user_request")
        messages.addUser(promptBuilder.buildAgentStateHistoryMessage(stateHistory))
        if (screenshotB64 != null) messages.addUser(promptBuilder.buildBrowserVisionInfo())
        if (prevAgentState != null && prevAgentState.action in listOf("textContent", "selectFirstTextOrNull")) {
            val textContent = prevAgentState.toolCallResult?.result?.toString() ?: ""
            val message = promptBuilder.buildExtractTextContentMessage(prevAgentState, textContent)
            messages.addUser(message)
        }
        return messages
    }

    private suspend fun generateStepAction(params: TextToActionParams, context: ExecutionContext): ActionDescription? {
        return try {
            tta.generate(params)
        } catch (e: Exception) {
            logger.error("🤖❌ action.gen.fail sid={} msg={}", context.sessionId.take(8), e.message, e)
            consecutiveFailureCounter.incrementAndGet()
            null
        }
    }

    /**
     * Classifies errors for appropriate retry strategies
     */
    private fun classifyError(e: Exception, step: Int): PerceptiveAgentError {
        return when (e) {
            is PerceptiveAgentError -> e
            is TimeoutException -> PerceptiveAgentError.TimeoutError("⏳ Step $step timed out", e)
            is SocketTimeoutException -> PerceptiveAgentError.TimeoutError("⏳ Network timeout at step $step", e)
            is ConnectException -> PerceptiveAgentError.TransientError("🔄 Connection failed at step $step", e)
            is ChatModelException -> PerceptiveAgentError.TimeoutError("⏳ Chat model timeout at step $step", e)
            is UnknownHostException -> PerceptiveAgentError.TransientError(
                "🔄 DNS resolution failed at step $step", e
            )

            is IOException -> {
                when {
                    e.message?.contains("connection") == true -> PerceptiveAgentError.TransientError(
                        "🔄 Connection issue at step $step", e
                    )

                    e.message?.contains("timeout") == true -> PerceptiveAgentError.TimeoutError(
                        "⏳ Network timeout at step $step", e
                    )

                    else -> PerceptiveAgentError.TransientError("🔄 IO error at step $step: ${e.message}", e)
                }
            }

            is IllegalArgumentException -> PerceptiveAgentError.ValidationError(
                "🚫 Validation error at step $step: ${e.message}", e
            )

            is IllegalStateException -> PerceptiveAgentError.PermanentError(
                "🛑 Invalid state at step $step: ${e.message}", e
            )

            else -> PerceptiveAgentError.TransientError("💥 Unexpected error at step $step: ${e.message}", e)
        }
    }

    /**
     * Determines if an error should trigger a retry
     */
    private fun shouldRetryError(e: Exception): Boolean {
        return when (e) {
            is PerceptiveAgentError.TransientError, is PerceptiveAgentError.TimeoutError -> true
            is SocketTimeoutException, is ConnectException, is UnknownHostException -> true

            else -> false
        }
    }

    /**
     * Calculates retry delay with exponential backoff and jitter
     */
    private fun calculateRetryDelay(attempt: Int): Long {
        // Use multiplicative jitter so delay is monotonic w.r.t attempt
        val baseExp = config.baseRetryDelayMs * (2.0.pow(attempt.toDouble()))
        val jitterPercent = (0..30).random() / 100.0 // 0%..30% multiplicative jitter
        val withJitter = baseExp * (1.0 + jitterPercent)
        return min(withJitter.toLong(), config.maxRetryDelayMs)
    }

    /**
     * Captures screenshot with retry mechanism
     */
    private suspend fun captureScreenshotWithRetry(context: ExecutionContext): String? {
        return try {
            val screenshot = safeScreenshot(context)
            if (screenshot != null) {
                logger.info(
                    "📸✅ screenshot.ok sid={} step={} size={} ",
                    context.sessionId.take(8), context.stepNumber, screenshot.length
                )
            } else {
                logger.info("📸⚪ screenshot.null sid={} step={}", context.sessionId.take(8), context.stepNumber)
            }
            screenshot
        } catch (e: Exception) {
            logger.error("📸❌ screenshot.fail sid={} msg={}", context.sessionId.take(8), e.message, e)
            null
        }
    }

    private suspend fun getCurrentUrl(): String = activeDriver.currentUrl()

    private suspend fun executeToolCall(
        action: ActionDescription, step: Int, context: ExecutionContext
    ): ActionExecuteResult? {
        val toolCall = action.toolCall ?: return null

        if (config.enablePreActionValidation && !actionValidator.validateToolCall(toolCall)) {
            logger.info(
                "🛑 tool.validate.fail sid={} step={} locator={} | {}({}) | {}",
                context.sessionId.take(8), context.stepNumber, action.locator, toolCall.method, toolCall.arguments,
                action.cssFriendlyExpressions.joinToString()
            )
            // Validation failure is meta info
            processTrace("🛑 #$step validation-failed ${toolCall.method}")
            return null
        }

        return try {
            logger.info(
                "🛠️ tool.exec sid={} step={} tool={} args={}",
                context.sessionId.take(8), context.stepNumber, toolCall.method, toolCall.arguments
            )

            val toolCallResults = doToolCallExecute(toolCall, action)
            consecutiveFailureCounter.set(0) // Reset on success

            val summary = "✅ ${toolCall.method} executed successfully"
            processTrace(summary)
            ActionExecuteResult(action, toolCallResults, success = true, summary)
        } catch (e: Exception) {
            val failures = consecutiveFailureCounter.incrementAndGet()
            logger.error(
                "🛠️❌ tool.exec.fail sid={} step={} failures={} msg={}",
                context.sessionId.take(8), context.stepNumber, failures, e.message, e
            )

            processTrace("💥 #$step unexpected failure ${toolCall.method}")

            if (failures >= 3) {
                throw PerceptiveAgentError.PermanentError("🛑 Too many consecutive failures at step $step", e)
            }

            null
        }
    }

    private suspend fun handleConsecutiveNoOps(consecutiveNoOps: Int, step: Int, context: ExecutionContext): Boolean {
        // No-op is meta info only
        processTrace("🕒 #$step no-op (consecutive: $consecutiveNoOps)")
        logger.info("🕒 noop sid={} step={} consecutive={}", context.sessionId.take(8), step, consecutiveNoOps)

        if (consecutiveNoOps >= config.consecutiveNoOpLimit) {
            logger.info("⛔ noop.stop sid={} step={} limit={}",
                context.sessionId.take(8), step, config.consecutiveNoOpLimit)
            return true
        }

        val delayMs = calculateConsecutiveNoOpDelay(consecutiveNoOps)
        delay(delayMs)
        return false
    }

    private fun calculateConsecutiveNoOpDelay(consecutiveNoOps: Int): Long {
        val baseDelay = 250L
        val exponentialDelay = baseDelay * consecutiveNoOps
        return min(exponentialDelay, 5000L)
    }

    private fun shouldTerminate(action: ActionDescription): Boolean = action.isComplete

    private fun handleTaskCompletion(action: ActionDescription, step: Int, context: ExecutionContext) {
        logger.info("✅ task.complete sid={} step={} complete={}", context.sessionId.take(8), step, action.isComplete)
        processTrace("#$step complete: taskComplete=${action.isComplete}")
    }

    private fun updatePerformanceMetrics(step: Int, stepStartTime: Instant, success: Boolean) {
        val stepTime = Duration.between(stepStartTime, Instant.now()).toMillis()
        stepExecutionTimes[step] = stepTime
        performanceMetrics.totalSteps += 1
        if (success) performanceMetrics.successfulActions += 1 else performanceMetrics.failedActions += 1
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

    private fun performMemoryCleanup(context: ExecutionContext) {
        try {
            if (stateHistory.size > config.maxHistorySize) {
                val toRemove = stateHistory.size - config.maxHistorySize + 10
                _stateHistory.subList(0, toRemove).clear()
            }
            if (validationCache.size > 1000) {
                validationCache.clear()
            }
            logger.info("🧹 mem.cleanup sid={} step={}", context.sessionId.take(8), context.stepNumber)
        } catch (e: Exception) {
            logger.error("🧹❌ mem.cleanup.fail sid={} msg={}", context.sessionId.take(8), e.message, e)
        }
    }

    private suspend fun generateFinalSummary(instruction: String, context: ExecutionContext): ModelResponse {
        return try {
            val summary = summarize(instruction)
            // Final summary is meta info
            processTrace("🧾 FINAL ${summary.content.take(200)}")
            persistTranscript(instruction, summary)
            summary
        } catch (e: Exception) {
            logger.error("📝❌ agent.summary.fail sid={} msg={}", context.sessionId.take(8), e.message, e)
            ModelResponse("Failed to generate summary: ${e.message}", ResponseState.OTHER)
        }
    }

    private fun addToHistory(h: AgentState) {
        _stateHistory.add(h)
        if (stateHistory.size > config.maxHistorySize * 2) {
            _stateHistory.subList(0, config.maxHistorySize).clear()
        }
        _processTrace.add(h.toString())
    }

    @Suppress("unused")
    private fun processTrace(entries: Map<String, String>) {
        val time = LocalDateTime.now()
        val id = _processTrace.size + 1
        _processTrace.add("""$id\t$time - """ + entries.entries.joinToString(" ") { (k, v) -> "$k:$v" })
    }

    private fun processTrace(entry: String) {
        val time = LocalDateTime.now()
        val id = _processTrace.size + 1
        _processTrace.add("""$id\t$time - $entry""")
    }

    /**
     * Enhanced screenshot capture with comprehensive error handling
     */
    private suspend fun safeScreenshot(context: ExecutionContext): String? {
        return runCatching {
            slogger.info("📸⏳ Attempting to capture screenshot", context)
            val driver = requireNotNull(activeDriver)
            val screenshot = driver.captureScreenshot()
            if (screenshot != null) {
                slogger.info("📸✅ Screenshot captured successfully", context, mapOf("size" to screenshot.length))
            } else {
                slogger.info("📸⚪ Screenshot capture returned null", context)
            }
            screenshot
        }.onFailure { e ->
            slogger.logError("📸❌ Screenshot capture failed", e, context.sessionId)
        }.getOrNull()
    }

    /**
     * Enhanced transcript persistence with comprehensive logging
     */
    private suspend fun persistTranscript(instruction: String, finalResp: ModelResponse) {
        val currentUrl = getCurrentUrl()
        val context = ExecutionContext(uuid.toString(), 0, "persist_transcript", currentUrl)

        runCatching {
            val ts = Instant.now().toEpochMilli()
            val log = baseDir.resolve("session-${uuid}-${ts}.log")
            slogger.info("🧾💾 Persisting execution transcript", context, mapOf("path" to log.toString()))

            val sb = StringBuilder()
            sb.appendLine("SESSION_ID: ${uuid}")
            sb.appendLine("TIMESTAMP: ${Instant.now()}")
            sb.appendLine("INSTRUCTION: $instruction")
            sb.appendLine("RESPONSE_STATE: ${finalResp.state}")
            sb.appendLine("EXECUTION_HISTORY:")
            stateHistory.forEach { sb.appendLine(it) }
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

            Files.writeString(log, sb.toString())
            slogger.info(
                "🧾✅ Transcript persisted successfully",
                context,
                mapOf("lines" to stateHistory.size + 10, "path" to log.toString())
            )
        }.onFailure { e ->
            slogger.logError("🧾❌ Failed to persist transcript", e, context.sessionId)
        }
    }

    /**
     * Enhanced summary generation with error handling
     */
    private suspend fun summarize(goal: String): ModelResponse {
        val currentUrl = getCurrentUrl()
        val context = ExecutionContext(uuid.toString(), 0, "summarize", currentUrl)

        return try {
            val (system, user) = promptBuilder.buildSummaryPrompt(goal, stateHistory)
            slogger.info("📝⏳ Generating final summary", context)

            val response = tta.chatModel.callUmSm(user, system)

            slogger.info(
                "📝✅ Summary generated successfully", context, mapOf(
                    "responseLength" to response.content.length, "responseState" to response.state
                )
            )

            response
        } catch (e: Exception) {
            slogger.logError("📝❌ Summary generation failed", e, context.sessionId)
            ModelResponse(
                "Failed to generate summary: ${e.message}", ResponseState.OTHER
            )
        }
    }
}
