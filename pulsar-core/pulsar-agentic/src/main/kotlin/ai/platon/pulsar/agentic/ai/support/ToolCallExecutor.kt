package ai.platon.pulsar.agentic.ai.support

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.ai.agent.detail.ActionValidator
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.ai.PerceptiveAgent
import ai.platon.pulsar.skeleton.ai.ToolCall
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import javax.script.ScriptEngineManager

/**
 * Executes WebDriver commands provided as string expressions.
 *
 * This class serves as a bridge between text-based automation commands and WebDriver actions.
 * It parses string commands and executes the corresponding WebDriver methods, enabling
 * script-based control of browser automation.
 *
 * ## Deprecation Notice:
 * This class is maintained for backward compatibility. New code should use [ActionExecutionService]
 * which provides a more unified and maintainable approach to action execution.
 *
 * ## Key Features:
 * - Supports a wide range of WebDriver commands, such as navigation, interaction, and evaluation.
 * - Provides error handling to ensure robust execution of commands.
 * - Includes a companion object for parsing function calls from string inputs.
 *
 * ## Example Usage:
 *
 * ```kotlin
 * // Deprecated approach
 * val executor = ToolCallExecutor()
 * val result = executor.execute("driver.open('https://example.com')", driver)
 * 
 * // Preferred approach
 * val service = ActionExecutionService()
 * val result = service.execute("driver.open('https://example.com')", driver)
 * ```
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
@Deprecated(
    message = "Use ActionExecutionService for better maintainability",
    replaceWith = ReplaceWith("ActionExecutionService", "ai.platon.pulsar.agentic.ai.support.ActionExecutionService")
)
open class ToolCallExecutor {
    private val logger = getLogger(this)
    private val engine = ScriptEngineManager().getEngineByExtension("kts")
    
    // Delegate to the new unified service
    private val service = ActionExecutionService()

    /**
     * Evaluate [expression].
     *
     * Slower and unsafe.
     *
     * ```kotlin
     * eval("""driver.click("#submit")""", driver)
     * ```
     * */
    fun eval(expression: String, driver: WebDriver): Any? {
        return service.eval(expression, driver)
    }

    fun eval(expression: String, browser: Browser): Any? {
        return service.eval(expression, browser)
    }

    fun eval(expression: String, agent: PerceptiveAgent): Any? {
        return eval(expression, mapOf("agent" to agent))
    }

    fun eval(expression: String, variables: Map<String, Any>): Any? {
        return service.eval(expression, variables)
    }

    /**
     * Executes a WebDriver command provided as a string expression.
     *
     * Parses the command string to extract the function name and arguments, then invokes
     * the corresponding WebDriver method. For example, the string "driver.open('https://example.com')"
     * would be parsed and the driver.open() method would be called with the URL argument.
     *
     * @param expression The expression(e.g., "driver.method(arg1, arg2)").
     * @param driver The WebDriver instance to execute the command on.
     * @return The result of the command execution, or null if the command could not be executed.
     */
    suspend fun execute(expression: String, driver: WebDriver): Any? {
        return service.execute(expression, driver)
    }

    suspend fun execute(expression: String, browser: Browser): Any? {
        return service.execute(expression, browser)
    }

    suspend fun execute(expression: String, browser: Browser, session: AgenticSession): Any? {
        return service.execute(expression, browser, session)
    }

    suspend fun execute(toolCall: ToolCall, driver: WebDriver): Any? {
        return service.execute(toolCall, driver)
    }

    suspend fun execute(toolCall: ToolCall, browser: Browser): Any? {
        return service.execute(toolCall, browser)
    }

    suspend fun execute(toolCall: ToolCall, agent: PerceptiveAgent): Any? {
        TODO("execute `toolCall` in browser domain")
    }

    companion object {
        /**
         * Parses a function call from a text string into its components.
         * Uses a robust state machine to correctly handle:
         * - Strings with commas and escaped quotes/backslashes
         * - Nested parentheses inside arguments
         * - Optional whitespace and trailing commas
         */
        fun parseKotlinFunctionExpression(input: String) = SimpleKotlinParser().parseFunctionExpression(input)

        // Basic string escaper to safely embed values inside Kotlin string literals
        private fun String.esc(): String = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        fun toolCallToExpression(tc: ToolCall): String? {
            ActionValidator().validateToolCall(tc)

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
    }
}
