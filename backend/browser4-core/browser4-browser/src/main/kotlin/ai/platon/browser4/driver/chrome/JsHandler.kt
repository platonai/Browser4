package ai.platon.browser4.driver.chrome

import ai.platon.browser4.driver.chrome.experimental.CDP
import ai.platon.browser4.driver.chrome.util.ChromeDriverException
import ai.platon.browser4.driver.common.B4JsUtils
import ai.platon.cdt.kt.protocol.types.runtime.CallFunctionOn
import ai.platon.cdt.kt.protocol.types.runtime.Evaluate
import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.getLogger

class JsHandler(
    private val devTools: RemoteDevTools,
    private val pageHandler: PageHandler,
    private val isolatedWorldManager: IsolatedWorldManager,
) {
    private val logger = getLogger(this)
    private val cdp = CDP(devTools)

    private val isActive get() = AppContext.isActive && devTools.isOpen

    private val confuser get() = isolatedWorldManager.settings.confuser

    /**
     * Evaluates expression on global object and returns detailed evaluation result.
     *
     * @param script JavaScript expression to evaluate
     * @return Detailed evaluation result including remote object and exception details, or null if evaluation fails
     * @throws ChromeDriverException if the script fails to execute
     * */
    @Throws(ChromeDriverException::class)
    suspend fun evaluateDetail(script: String): Evaluate? {
        val expression: String = B4JsUtils.toCDPCompatibleExpression(script)

        val confusedExpr = confuser.confuse(expression)

        val isolatedContextId = isolatedWorldManager
            .getContextId(runCatching { cdp.mainFrame().id }.getOrNull())
        if (isolatedContextId != null && isolatedContextId > 0) {
            val isolatedResult = evaluateInContext(confusedExpr, isolatedContextId, returnByValue = false)
            if (isolatedResult != null) {
                return isolatedResult
            }
        }

        return try {
            cdp.evaluate(confusedExpr)
        } catch (e: Exception) {
            logger.warn("Failed to evaluate $script", e)
            null
        }
    }

    @Throws(ChromeDriverException::class)
    suspend fun callFunctionOn(selector: String, functionDeclaration: String): CallFunctionOn? {
        val node = pageHandler.queryLocator(selector) ?: return null
        val resolved = resolveNodeObjectId(devTools, node) ?: return null
        return try {
            cdp.callFunctionOn(functionDeclaration, objectId = resolved.objectId, returnByValue = true)
        } finally {
            releaseNodeObjectIfNeeded(devTools, resolved)
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
        val evaluate = evaluateDetail(script.trim())

        val exception = evaluate?.exceptionDetails?.exception
        if (exception != null) {
            val errorMsg = "${exception.description}\n>>>$script<<<"
            logger.warn(errorMsg)
            throw ChromeDriverException(errorMsg)
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
        val expression: String = B4JsUtils.toCDPCompatibleExpression(script)

        val confusedExpr = confuser.confuse(expression)

        val isolatedContextId = isolatedWorldManager
            .getContextId(runCatching { cdp.mainFrame().id }.getOrNull())
        if (isolatedContextId != null && isolatedContextId > 0) {
            val isolatedResult = evaluateInContext(confusedExpr, isolatedContextId, returnByValue = true)
            if (isolatedResult != null) {
                return isolatedResult
            }
        }

        return try {
            cdp.evaluate(confusedExpr, returnByValue = true)
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
        return cdp.evaluate(expression = expression, contextId = contextId, returnByValue = returnByValue)
    }
}
