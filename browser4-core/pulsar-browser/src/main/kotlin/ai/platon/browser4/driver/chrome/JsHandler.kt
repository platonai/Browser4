package ai.platon.browser4.driver.chrome

import ai.platon.browser4.driver.chrome.util.ChromeDriverException
import ai.platon.cdt.kt.protocol.types.runtime.CallFunctionOn
import ai.platon.cdt.kt.protocol.types.runtime.Evaluate
import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.js.JsUtils
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue

class JsHandler(
    private val devTools: RemoteDevTools,
    private val pageHandler: PageHandler,
    private val isolatedWorldManager: IsolatedWorldManager,
) {
    private val logger = getLogger(this)

    private val isActive get() = AppContext.isActive && devTools.isOpen
    private val pageAPI get() = devTools.page.takeIf { isActive }
    private val domAPI get() = devTools.dom.takeIf { isActive }
    private val cssAPI get() = devTools.css.takeIf { isActive }
    private val runtimeAPI get() = devTools.runtime.takeIf { isActive }

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
        val expression: String = JsUtils.toCDPCompatibleExpression(script)

        val confusedExpr = confuser.confuse(expression)

        val isolatedContextId = isolatedWorldManager
            .getContextId(runCatching { pageAPI?.getFrameTree()?.frame?.id }.getOrNull())
        if (isolatedContextId != null && isolatedContextId > 0) {
            val isolatedResult = evaluateInContext(confusedExpr, isolatedContextId, returnByValue = false)
            if (isolatedResult != null) {
                return isolatedResult
            }
        }

        return try {
            runtimeAPI?.evaluate(confusedExpr)
        } catch (e: Exception) {
            logger.warn("Failed to evaluate $expression", e)
            null
        }
    }

    @Throws(ChromeDriverException::class)
    suspend fun callFunctionOn(selector: String, functionDeclaration: String): CallFunctionOn? {
        val node = pageHandler.querySelector(selector) ?: return null
        // Resolve a fresh objectId and ensure it's released after the call
        val resolved = try {
            when {
                node.nodeId > 0 -> domAPI?.resolveNode(node.nodeId, null, null, null)
                node.backendNodeId > 0 -> domAPI?.resolveNode(null, node.backendNodeId, null, null)
                else -> null
            }
        } catch (e: Exception) {
            null
        } ?: return null

        val oid = resolved.objectId ?: return null
        return try {
            runtimeAPI?.callFunctionOn(functionDeclaration, objectId = oid, returnByValue = true)
        } finally {
            try {
                runtimeAPI?.releaseObject(oid)
            } catch (_: Exception) {
            }
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
        val evaluate = if (script.contains("__pulsar_utils__")) {
            // Just for debugging purpose
            evaluateDetail(script)
        } else {
            evaluateDetail(script.trim())
        }

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
        val expression: String = JsUtils.toCDPCompatibleExpression(script)

        val confusedExpr = confuser.confuse(expression)

        val isolatedContextId = isolatedWorldManager
            .getContextId(runCatching { pageAPI?.getFrameTree()?.frame?.id }.getOrNull())
        if (isolatedContextId != null && isolatedContextId > 0) {
            val isolatedResult = evaluateInContext(confusedExpr, isolatedContextId, returnByValue = true)
            if (isolatedResult != null) {
                return isolatedResult
            }
        }

        return try {
            runtimeAPI?.evaluate(confusedExpr, returnByValue = true)
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
        return runtimeAPI?.evaluate(expression = expression, contextId = contextId, returnByValue = returnByValue)
    }

    private suspend fun evaluateInContext2(expression: String, contextId: Int, returnByValue: Boolean): Evaluate? {
        val params = mutableMapOf<String, Any?>(
            "expression" to expression,
            "contextId" to contextId,
            "returnByValue" to returnByValue,
            "awaitPromise" to true,
        )

        // runtimeAPI.evaluate()
        val raw = devTools.invoke<Map<String, Any?>>("Runtime.evaluate", params, null) ?: return null
        return runCatching {
            pulsarObjectMapper().convertValue<Evaluate>(raw)
        }.onFailure { e ->
            logger.warn("Failed to convert evaluation result to Evaluate type", e)
        }.getOrNull()
    }
}
