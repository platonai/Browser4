package ai.platon.pulsar.chrome.handler

import ai.platon.pulsar.chrome.IsolatedWorldManager
import ai.platon.pulsar.chrome.handler.util.releaseNodeObjectIfNeeded
import ai.platon.pulsar.chrome.handler.util.resolveNodeObjectId
import ai.platon.pulsar.chrome.util.ChromeDriverException
import ai.platon.cdt.kt.protocol.types.runtime.CallFunctionOn
import ai.platon.cdt.kt.protocol.types.runtime.Evaluate
import ai.platon.pulsar.browser.impl.BrowserProtocol
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.js.JsUtils

class JsHandler(
    private val bp: BrowserProtocol,
    private val page: PageHandler,
    private val isolatedWorldManager: IsolatedWorldManager,
) {
    private val logger = getLogger(this)

    private val confuser get() = isolatedWorldManager.settings.confuser

    /**
     * Evaluates expression on global object and returns detailed evaluation result.
     *
     * @param script JavaScript expression to evaluate
     * @return Detailed evaluation result including remote object and exception details, or null if evaluation fails
     * @throws ai.platon.pulsar.driver.chrome.util.ChromeDriverException if the script fails to execute
     * */
    @Throws(ChromeDriverException::class)
    suspend fun evaluateDetail(script: String): Evaluate? {
        val expression: String = JsUtils.toCDPCompatibleExpression(script)

        val confusedExpr = confuser.confuse(expression)

        val isolatedContextId = isolatedWorldManager
            .getContextId(runCatching { bp.mainFrame().id }.getOrNull())
        if (isolatedContextId != null && isolatedContextId > 0) {
            val isolatedResult = evaluateInContext(confusedExpr, isolatedContextId, returnByValue = false)
            if (isolatedResult != null) {
                return isolatedResult
            }
        }

        return try {
            bp.evaluate(confusedExpr)
        } catch (e: Exception) {
            logger.warn("Failed to evaluate $script", e)
            null
        }
    }

    @Throws(ChromeDriverException::class)
    suspend fun callFunctionOn(selector: String, functionDeclaration: String): CallFunctionOn? {
        val node = page.dom.queryLocator(selector) ?: return null
        val resolved = resolveNodeObjectId(bp, node) ?: return null
        return try {
            bp.callFunctionOn(functionDeclaration, objectId = resolved.objectId, returnByValue = true)
        } finally {
            releaseNodeObjectIfNeeded(bp, resolved)
        }
    }

    /**
     * Evaluates expression on global object and returns the result value.
     *
     * @param script JavaScript expression to evaluate
     * @return Remote object value in case of primitive values or JSON values, or null if evaluation fails
     * @throws RuntimeException if the script execution results in an exception
     * */
    @Throws(ChromeDriverException::class)
    suspend fun evaluate(script: String): Any? {
        require(script.isNotBlank()) { "Script must not be blank" }
        val evaluate = evaluateDetail(script)

        val exception = evaluate?.exceptionDetails?.exception
        if (exception != null) {
            val message = "${exception.description}\n>>>$script<<<"
            logger.warn("Failed to evaluate JavaScript. $message")
            throw ChromeDriverException(message)
        }

        val result = evaluate?.result
        return result?.value
    }

    /**
     * Evaluates expression on global object with return by value and returns detailed evaluation result.
     * Supports execution in isolated world contexts for better security isolation.
     *
     * @param script JavaScript expression to evaluate
     * @return Detailed evaluation result with value returned, or null if evaluation fails
     * @throws ChromeDriverException if the script fails to execute
     * */
    @Throws(ChromeDriverException::class)
    suspend fun evaluateValueDetail(script: String): Evaluate? {
        val expression: String = JsUtils.toCDPCompatibleExpression(script)

        val confusedExpr = confuser.confuse(expression)

        val isolatedContextId = isolatedWorldManager
            .getContextId(runCatching { bp.mainFrame().id }.getOrNull())
        if (isolatedContextId != null && isolatedContextId > 0) {
            val isolatedResult = evaluateInContext(confusedExpr, isolatedContextId, returnByValue = true)
            if (isolatedResult != null) {
                return isolatedResult
            }
        }

        return try {
            bp.evaluate(confusedExpr, returnByValue = true)
        } catch (e: Exception) {
            logger.warn("Failed to evaluate $script", e)
            null
        }
    }

    /**
     * Evaluates expression on global object with return by value.
     * Returns the actual value rather than a remote object reference.
     *
     * @param script JavaScript expression to evaluate
     * @return The evaluated value, or null if evaluation fails or returns null
     * */
    @Throws(ChromeDriverException::class)
    suspend fun evaluateValue(script: String): Any? {
        require(script.isNotBlank()) { "Script must not be blank" }
        val evaluate = evaluateValueDetail(script)

        val exception = evaluate?.exceptionDetails?.exception
        if (exception != null) {
            logger.warn(exception.description + "\n>>>$script<<<")
        }

        return evaluate?.result?.value
    }

    /**
     * Evaluates a function on a DOM element and returns the result value.
     * Resolves the element by selector, calls the function, and properly releases resources.
     *
     * @param selector CSS selector to locate the element
     * @param functionDeclaration JavaScript function declaration to execute on the element
     * @return The evaluated value, or null if the element cannot be found or evaluation fails
     * */
    @Throws(ChromeDriverException::class)
    suspend fun evaluateValue(selector: String, functionDeclaration: String): Any? {
        require(selector.isNotBlank()) { "Selector must not be blank" }
        require(functionDeclaration.isNotBlank()) { "Function declaration must not be blank" }

        val result = callFunctionOn(selector, functionDeclaration)

        val exception = result?.exceptionDetails?.exception
        if (exception != null) {
            logger.warn(exception.description + "\n>>>$functionDeclaration<<<")
        }

        return result?.result?.value
    }

    /**
     * Evaluates JavaScript in a specific execution context.
     * Used internally to support isolated world execution.
     *
     * @param expression JavaScript expression to evaluate
     * @param contextId Execution context ID
     * @param returnByValue Whether to return the value or a remote object reference
     * @return Detailed evaluation result, or null if evaluation fails
     * */
    private suspend fun evaluateInContext(expression: String, contextId: Int, returnByValue: Boolean): Evaluate? {
        return bp.evaluate(expression = expression, contextId = contextId, returnByValue = returnByValue)
    }
}
