package ai.platon.browser4.chrome.protocol

import ai.platon.browser4.chrome.IsolatedWorldManager
import ai.platon.browser4.chrome.protocol.util.releaseNodeObjectIfNeeded
import ai.platon.browser4.chrome.protocol.util.resolveNodeObjectId
import ai.platon.browser4.chrome.util.ChromeDriverException
import ai.platon.cdt.kt.protocol.types.runtime.CallFunctionOn
import ai.platon.cdt.kt.protocol.types.runtime.Evaluate
import ai.platon.browser4.api.BrowserProtocol
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
     * @throws ai.platon.browser4.chrome.util.ChromeDriverException if the script fails to execute
     * */
    @Throws(ChromeDriverException::class)
    suspend fun evaluateDetail(script: String): Evaluate? {
        val expression: String = JsUtils.toCDPCompatibleExpression(script)

        val confusedExpr = confuser.confuse(expression)

        val isolatedContextId = isolatedWorldManager
            .getContextId(runCatching { bp.mainFrame().id }.getOrNull())
        if (isolatedContextId != null && isolatedContextId > 0) {
            try {
                val isolatedResult = evaluateInContext(confusedExpr, isolatedContextId, returnByValue = false)
                if (isolatedResult != null && isolatedResult.exceptionDetails == null) {
                    return isolatedResult
                }
            } catch (e: Exception) {
                logger.warn(
                    "Failed to evaluate in isolated world (context: {}), falling back to main world | {}",
                    isolatedContextId, e.message
                )
            }
        }

        // Propagate CDP communication errors — the RobustRPC layer handles retry
        return bp.evaluate(confusedExpr)
    }

    /**
     * Evaluates JavaScript in a specific frame's execution context.
     *
     * @param script JavaScript expression to evaluate
     * @param frameId The CDP frame ID (from getFrameTree)
     * @return Detailed evaluation result or null if the frame/context is not available
     */
    @Throws(ChromeDriverException::class)
    suspend fun evaluateDetailInFrame(script: String, frameId: String): Evaluate? {
        val expression: String = JsUtils.toCDPCompatibleExpression(script)
        val confusedExpr = confuser.confuse(expression)

        val isolatedContextId = isolatedWorldManager.getContextId(frameId)
        if (isolatedContextId != null && isolatedContextId > 0) {
            try {
                val isolatedResult = evaluateInContext(confusedExpr, isolatedContextId, returnByValue = false)
                if (isolatedResult != null && isolatedResult.exceptionDetails == null) {
                    return isolatedResult
                }
            } catch (e: Exception) {
                logger.warn(
                    "Failed to evaluate in isolated world for frame {} (context: {}), falling back | {}",
                    frameId, isolatedContextId, e.message
                )
            }
        }

        // Fall back to evaluating in the main world, scoped to the frame
        return try {
            bp.evaluate(confusedExpr)
        } catch (e: Exception) {
            logger.warn("Failed to evaluate in frame {}: {}", frameId, e.message)
            null
        }
    }

    /**
     * Lists all frame IDs in the current page, including nested iframes.
     * The main frame is always the first entry.
     *
     * @return List of frame IDs, or empty list if the frame tree is unavailable.
     */
    suspend fun listFrameIds(): List<String> {
        return try {
            val frameIds = mutableListOf<String>()
            val frameTree = runCatching { bp.getFrameTree() }.getOrNull() ?: return emptyList()
            collectFrameIds(frameTree, frameIds)
            frameIds
        } catch (e: Exception) {
            logger.warn("Failed to list frame IDs: {}", e.message)
            emptyList()
        }
    }

    private fun collectFrameIds(
        frameTree: ai.platon.cdt.kt.protocol.types.page.FrameTree,
        out: MutableList<String>
    ) {
        out.add(frameTree.frame.id)
        frameTree.childFrames?.forEach { collectFrameIds(it, out) }
    }

    /**
     * Evaluates [script] in the main frame. If the result indicates an empty document
     * (e.g., document.body.children.length == 0), tries child iframes.
     *
     * Returns a map of frame ID -> result value.  An empty map means no frames produced a result.
     */
    @Throws(ChromeDriverException::class)
    suspend fun evaluateInAllFrames(script: String): Map<String, Any?> {
        val frames = listFrameIds()
        if (frames.isEmpty()) {
            return mapOf("main" to evaluate(script))
        }

        val results = LinkedHashMap<String, Any?>()
        for (frameId in frames) {
            try {
                val result = evaluateDetailInFrame(script, frameId)
                if (result != null && result.exceptionDetails == null) {
                    results[frameId] = result.result.value
                }
            } catch (_: Exception) {
                // skip frames where evaluation fails
            }
        }
        return results
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
        return evaluateValueDetail(script, awaitPromise = false)
    }

    @Throws(ChromeDriverException::class)
    suspend fun evaluateValueDetail(script: String, awaitPromise: Boolean): Evaluate? {
        val expression: String = JsUtils.toCDPCompatibleExpression(script)

        val confusedExpr = confuser.confuse(expression)

        val isolatedContextId = isolatedWorldManager
            .getContextId(runCatching { bp.mainFrame().id }.getOrNull())
        if (isolatedContextId != null && isolatedContextId > 0) {
            try {
                val isolatedResult = evaluateInContext(confusedExpr, isolatedContextId, returnByValue = true, awaitPromise = awaitPromise)
                if (isolatedResult != null && isolatedResult.exceptionDetails == null) {
                    return isolatedResult
                }
            } catch (e: Exception) {
                logger.warn(
                    "Failed to evaluate in isolated world (context: {}), falling back to main world | {}",
                    isolatedContextId, e.message
                )
            }
        }

        // Propagate CDP communication errors — the RobustRPC layer handles retry
        return bp.evaluate(confusedExpr, returnByValue = true, awaitPromise = awaitPromise)
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
            val message = "${exception.description}\n>>>$script<<<"
            logger.warn("Failed to evaluate JavaScript. $message")
            throw ChromeDriverException(message)
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
            val message = "${exception.description}\n>>>$functionDeclaration<<<"
            logger.warn("Failed to evaluate JavaScript. $message")
            throw ChromeDriverException(message)
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
    private suspend fun evaluateInContext(expression: String, contextId: Int, returnByValue: Boolean, awaitPromise: Boolean = false): Evaluate? {
        return bp.evaluate(expression = expression, contextId = contextId, returnByValue = returnByValue, awaitPromise = awaitPromise)
    }
}
