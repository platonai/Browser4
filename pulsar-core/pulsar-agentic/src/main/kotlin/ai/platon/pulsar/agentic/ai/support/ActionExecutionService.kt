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
        return ToolCallExecutor.toolCallToExpression(toolCall)
    }
    
    /**
     * Parse a Kotlin function expression into its components as a ToolCall.
     * 
     * @param input The expression string to parse
     * @return Parsed ToolCall object
     */
    fun parseExpression(input: String): ToolCall? {
        return SimpleKotlinParser().parseFunctionExpression(input)
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
