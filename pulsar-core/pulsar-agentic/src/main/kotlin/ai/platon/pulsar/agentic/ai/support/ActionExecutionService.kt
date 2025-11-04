package ai.platon.pulsar.agentic.ai.support

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.ai.agent.detail.ActionValidator
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.ai.ToolCall
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import javax.script.ScriptEngineManager

/**
 * Unified service for executing agent actions and tool calls.
 * 
 * This service consolidates all action execution logic into a single, maintainable module.
 * It handles:
 * - Tool call execution for driver and browser domains
 * - Action validation
 * - Expression evaluation
 * - ToolCall to Expression conversion
 * 
 * ## Architecture
 * 
 * This service acts as the single entry point for all action/tool call operations,
 * delegating to specialized executors while managing validation and error handling centrally.
 * 
 * ## Example Usage
 * 
 * ```kotlin
 * val service = ActionExecutionService()
 * val result = service.execute("driver.click(\"#button\")", driver)
 * ```
 * 
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
class ActionExecutionService {
    private val logger = getLogger(this)
    private val engine = ScriptEngineManager().getEngineByExtension("kts")
    private val validator = ActionValidator()
    private val parser = SimpleKotlinParser()
    
    // Specialized executors for different domains
    private val webDriverExecutor = WebDriverToolCallExecutor()
    private val browserExecutor = BrowserToolCallExecutor()
    
    /**
     * Execute a tool call on a WebDriver.
     * 
     * @param toolCall The tool call to execute
     * @param driver The WebDriver instance
     * @return The result of the execution, or null if execution fails
     */
    suspend fun execute(toolCall: ToolCall, driver: WebDriver): Any? {
        require(toolCall.domain == "driver") { "Tool call domain should be `driver`" }
        
        // Validate before execution
        validator.validateToolCall(toolCall)
        
        val expression = convertToExpression(toolCall) 
            ?: throw IllegalArgumentException("Failed to convert to expression: $toolCall")
        
        return try {
            execute(expression, driver)
        } catch (e: Exception) {
            logger.warn("Error executing TOOL CALL: {} - {}", toolCall, e.brief())
            null
        }
    }
    
    /**
     * Execute a tool call on a Browser.
     * 
     * @param toolCall The tool call to execute
     * @param browser The Browser instance
     * @return The result of the execution, or null if execution fails
     */
    suspend fun execute(toolCall: ToolCall, browser: Browser): Any? {
        require(toolCall.domain == "browser") { "Tool call domain should be `browser`" }
        
        // Validate before execution
        validator.validateToolCall(toolCall)
        
        val expression = convertToExpression(toolCall) ?: return null
        
        return try {
            execute(expression, browser)
        } catch (e: Exception) {
            logger.warn("Error executing TOOL CALL: {} - {}", toolCall, e.brief())
            null
        }
    }
    
    /**
     * Execute an expression string on a WebDriver.
     * 
     * @param expression The expression to execute (e.g., "driver.click('#button')")
     * @param driver The WebDriver instance
     * @return The result of the execution
     */
    suspend fun execute(expression: String, driver: WebDriver): Any? {
        return webDriverExecutor.execute(expression, driver)
    }
    
    /**
     * Execute an expression string on a Browser.
     * 
     * @param expression The expression to execute
     * @param browser The Browser instance
     * @return The result of the execution
     */
    suspend fun execute(expression: String, browser: Browser): Any? {
        return browserExecutor.execute(expression, browser)
    }
    
    /**
     * Execute an expression with session binding for browser operations.
     * 
     * @param expression The expression to execute
     * @param browser The Browser instance
     * @param session The AgenticSession for driver binding
     * @return The result of the execution
     */
    suspend fun execute(expression: String, browser: Browser, session: AgenticSession): Any? {
        return browserExecutor.execute(expression, browser, session)
    }
    
    /**
     * Evaluate an expression using Kotlin script engine.
     * 
     * Slower and unsafe - use only when necessary.
     * 
     * @param expression The Kotlin expression to evaluate
     * @param variables Variables to bind in the script context
     * @return The evaluation result
     */
    fun eval(expression: String, variables: Map<String, Any>): Any? {
        return try {
            variables.forEach { (key, value) -> engine.put(key, value) }
            engine.eval(expression)
        } catch (e: Exception) {
            logger.warn("Error eval expression: {} - {}", expression, e.brief())
            null
        }
    }
    
    /**
     * Evaluate an expression with a WebDriver context.
     */
    fun eval(expression: String, driver: WebDriver): Any? {
        return eval(expression, mapOf("driver" to driver))
    }
    
    /**
     * Evaluate an expression with a Browser context.
     */
    fun eval(expression: String, browser: Browser): Any? {
        return eval(expression, mapOf("browser" to browser))
    }
    
    /**
     * Convert a ToolCall to its expression representation.
     * 
     * This centralizes the conversion logic that was previously in ToolCallExecutor.
     * 
     * @param toolCall The tool call to convert
     * @return The expression string, or null if conversion fails
     */
    fun convertToExpression(toolCall: ToolCall): String? {
        return toolCallToExpression(toolCall)
    }
    
    // Basic string escaper to safely embed values inside Kotlin string literals
    private fun String.esc(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    
    private fun toolCallToExpression(tc: ToolCall): String? {
        validator.validateToolCall(tc)

        val arguments = tc.arguments
        return when (tc.method) {
            // Navigation
            "open" -> arguments["url"]?.let { "driver.open(\"${it.esc()}\")" }
            "navigateTo" -> arguments["url"]?.let { "driver.navigateTo(\"${it.esc()}\")" }
            "goBack" -> "driver.goBack()"
            "goForward" -> "driver.goForward()"
            // Wait
            "waitForSelector" -> arguments["selector"]?.let { sel ->
                "driver.waitForSelector(\"${sel.esc()}\", ${(arguments["timeoutMillis"] ?: 5000)})"
            }
            // Status checking (first batch of new tools)
            "exists" -> arguments["selector"]?.let { "driver.exists(\"${it.esc()}\")" }
            "isVisible" -> arguments["selector"]?.let { "driver.isVisible(\"${it.esc()}\")" }
            "focus" -> arguments["selector"]?.let { "driver.focus(\"${it.esc()}\")" }
            // Basic interactions
            "click" -> arguments["selector"]?.esc()?.let {
                val modifier = arguments["modifier"]?.esc()
                val count = arguments["count"]?.toIntOrNull() ?: 1
                when {
                    modifier != null -> "driver.click(\"$it\", \"$modifier\")"
                    else -> "driver.click(\"$it\", $count)"
                }
            }
            "fill" -> arguments["selector"]?.let { s ->
                val text = arguments["text"]?.esc() ?: ""
                "driver.fill(\"${s.esc()}\", \"$text\")"
            }

            "press" -> arguments["selector"]?.let { s ->
                arguments["key"]?.let { k -> "driver.press(\"${s.esc()}\", \"${k.esc()}\")" }
            }

            "check" -> arguments["selector"]?.let { "driver.check(\"${it.esc()}\")" }
            "uncheck" -> arguments["selector"]?.let { "driver.uncheck(\"${it.esc()}\")" }
            // Scrolling
            "scrollDown" -> "driver.scrollDown(${arguments["count"] ?: 1})"
            "scrollUp" -> "driver.scrollUp(${arguments["count"] ?: 1})"
            "scrollBy" -> {
                val pixels = (arguments["pixels"] ?: 200.0).toString()
                val smooth = (arguments["smooth"] ?: true).toString()
                "driver.scrollBy(${pixels}, ${smooth})"
            }
            "scrollTo" -> arguments["selector"]?.let { "driver.scrollTo(\"${it.esc()}\")" }
            "scrollToTop" -> "driver.scrollToTop()"
            "scrollToBottom" -> "driver.scrollToBottom()"
            "scrollToMiddle" -> "driver.scrollToMiddle(${arguments["ratio"] ?: 0.5})"
            "scrollToScreen" -> arguments["screenNumber"]?.let { n -> "driver.scrollToScreen(${n})" }
            // Advanced clicks
            "clickTextMatches" -> arguments["selector"]?.let { s ->
                val pattern = arguments["pattern"]?.esc() ?: return@let null
                val count = arguments["count"] ?: 1
                "driver.clickTextMatches(\"${s.esc()}\", \"$pattern\", $count)"
            }

            "clickMatches" -> arguments["selector"]?.let { s ->
                val attr = arguments["attrName"]?.esc() ?: return@let null
                val pattern = arguments["pattern"]?.esc() ?: return@let null
                val count = arguments["count"] ?: 1
                "driver.clickMatches(\"${s.esc()}\", \"$attr\", \"$pattern\", $count)"
            }

            "clickNthAnchor" -> arguments["n"]?.let { n ->
                val root = arguments["rootSelector"] ?: "body"
                "driver.clickNthAnchor(${n}, \"${root.esc()}\")"
            }
            // Enhanced navigation
            "waitForNavigation" -> {
                val oldUrl = arguments["oldUrl"] ?: ""
                val timeout = arguments["timeoutMillis"] ?: 5000L
                "driver.waitForNavigation(\"${oldUrl.esc()}\", ${timeout})"
            }
            // Screenshots
            "captureScreenshot" -> {
                val sel = arguments["selector"]
                if (sel.isNullOrBlank()) "driver.captureScreenshot()" else "driver.captureScreenshot(\"${sel.esc()}\")"
            }
            // Timing
            "delay" -> "driver.delay(${arguments["millis"] ?: 1000})"
            // URL and document info
            "currentUrl" -> "driver.currentUrl()"
            "url" -> "driver.url()"
            "documentURI" -> "driver.documentURI()"
            "baseURI" -> "driver.baseURI()"
            "referrer" -> "driver.referrer()"
            "pageSource" -> "driver.pageSource()"
            "getCookies" -> "driver.getCookies()"
            // Additional status checking
            "isHidden" -> arguments["selector"]?.let { "driver.isHidden(\"${it.esc()}\")" }
            "visible" -> arguments["selector"]?.let { "driver.visible(\"${it.esc()}\")" }
            "isChecked" -> arguments["selector"]?.let { "driver.isChecked(\"${it.esc()}\")" }
            "bringToFront" -> "driver.bringToFront()"
            // Additional interactions
            "type" -> arguments["selector"]?.let { s ->
                arguments["text"]?.let { t -> "driver.type(\"${s.esc()}\", \"${t.esc()}\")" }
            }
            "scrollToViewport" -> arguments["n"]?.let { "driver.scrollToViewport(${it})" }
            "mouseWheelDown" -> "driver.mouseWheelDown(${arguments["count"] ?: 1}, ${arguments["deltaX"] ?: 0.0}, ${arguments["deltaY"] ?: 150.0}, ${arguments["delayMillis"] ?: 0})"
            "mouseWheelUp" -> "driver.mouseWheelUp(${arguments["count"] ?: 1}, ${arguments["deltaX"] ?: 0.0}, ${arguments["deltaY"] ?: -150.0}, ${arguments["delayMillis"] ?: 0})"
            "moveMouseTo" -> arguments["x"]?.let { x ->
                arguments["y"]?.let { y -> "driver.moveMouseTo(${x}, ${y})" }
            } ?: arguments["selector"]?.let { s ->
                "driver.moveMouseTo(\"${s.esc()}\", ${arguments["deltaX"] ?: 0}, ${arguments["deltaY"] ?: 0})"
            }
            "dragAndDrop" -> arguments["selector"]?.let { s ->
                "driver.dragAndDrop(\"${s.esc()}\", ${arguments["deltaX"] ?: 0}, ${arguments["deltaY"] ?: 0})"
            }
            // HTML and text extraction
            "outerHTML" -> arguments["selector"]?.let { "driver.outerHTML(\"${it.esc()}\")" } ?: "driver.outerHTML()"
            "textContent" -> "driver.textContent()"
            "selectFirstTextOrNull" -> arguments["selector"]?.let { "driver.selectFirstTextOrNull(\"${it.esc()}\")" }
            "selectTextAll" -> arguments["selector"]?.let { "driver.selectTextAll(\"${it.esc()}\")" }
            "selectFirstAttributeOrNull" -> arguments["selector"]?.let { s ->
                arguments["attrName"]?.let { a -> "driver.selectFirstAttributeOrNull(\"${s.esc()}\", \"${a.esc()}\")" }
            }
            "selectAttributes" -> arguments["selector"]?.let { "driver.selectAttributes(\"${it.esc()}\")" }
            "selectAttributeAll" -> arguments["selector"]?.let { s ->
                arguments["attrName"]?.let { a -> "driver.selectAttributeAll(\"${s.esc()}\", \"${a.esc()}\", ${arguments["start"] ?: 0}, ${arguments["limit"] ?: 10000})" }
            }
            "selectImages" -> arguments["selector"]?.let { "driver.selectImages(\"${it.esc()}\", ${arguments["offset"] ?: 1}, ${arguments["limit"] ?: Int.MAX_VALUE})" }
            // JavaScript evaluation
            "evaluate" -> arguments["expression"]?.let { "driver.evaluate(\"${it.esc()}\")" }
            // Browser-level operations
            "switchTab" -> arguments["tabId"]?.let { "browser.switchTab(\"${it.esc()}\")" }
            else -> null
        }
    }
    
    /**
     * Parse a Kotlin function expression into its components as a ToolCall.
     * 
     * @param input The expression string to parse
     * @return Parsed ToolCall object
     */
    fun parseExpression(input: String): ToolCall? {
        return parser.parseFunctionExpression(input)
    }
    
    /**
     * Validate a tool call before execution.
     * 
     * @param toolCall The tool call to validate
     * @return true if valid, false otherwise
     */
    fun validate(toolCall: ToolCall): Boolean {
        return validator.validateToolCall(toolCall)
    }
}
