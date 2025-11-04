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
        private val conversionService = ActionExecutionService()
        
        /**
         * Parses a function call from a text string into its components.
         * Uses a robust state machine to correctly handle:
         * - Strings with commas and escaped quotes/backslashes
         * - Nested parentheses inside arguments
         * - Optional whitespace and trailing commas
         * 
         * @deprecated Use ActionExecutionService.parseExpression instead
         */
        @Deprecated("Use ActionExecutionService.parseExpression", ReplaceWith("ActionExecutionService().parseExpression(input)"))
        fun parseKotlinFunctionExpression(input: String) = conversionService.parseExpression(input)

        /**
         * Convert a ToolCall to its expression representation.
         * 
         * @deprecated Use ActionExecutionService.convertToExpression instead
         */
        @Deprecated("Use ActionExecutionService.convertToExpression", ReplaceWith("ActionExecutionService().convertToExpression(tc)"))
        fun toolCallToExpression(tc: ToolCall): String? = conversionService.convertToExpression(tc)
    }
}
