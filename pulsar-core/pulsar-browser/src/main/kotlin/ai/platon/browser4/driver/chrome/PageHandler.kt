package ai.platon.browser4.driver.chrome

import ai.platon.browser4.driver.chrome.dom.Locator
import ai.platon.browser4.driver.chrome.util.CDPReturnError
import ai.platon.browser4.driver.chrome.util.ChromeDriverException
import ai.platon.browser4.driver.chrome.util.ChromeRPCException
import ai.platon.cdt.kt.protocol.support.annotations.Experimental
import ai.platon.cdt.kt.protocol.support.annotations.Optional
import ai.platon.cdt.kt.protocol.support.annotations.ParamName
import ai.platon.cdt.kt.protocol.types.dom.Rect
import ai.platon.cdt.kt.protocol.types.page.Navigate
import ai.platon.cdt.kt.protocol.types.page.ReferrerPolicy
import ai.platon.cdt.kt.protocol.types.page.TransitionType
import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper

/**
 * NodeId does not explicitly prohibit 0, but as seen in the internal implementation (Chromium source code):
 * - All valid nodes are assigned NodeIds starting from 1
 * - `0` is reserved as an "invalid / null node"
 *
 * DOM.NodeId #
 * Unique DOM node identifier.
 * Type: integer
 *
 * DOM.BackendNodeId #
 * Unique DOM node identifier used to reference a node that may not have been pushed to the front-end.
 * Type: integer
 *
 * References:
 * - [NodeId](https://chromedevtools.github.io/devtools-protocol/tot/DOM/#type-NodeId)
 * */
 data class NodeRef constructor(
    val nodeId: Int = 0,
    // backend node id is more stable
    val backendNodeId: Int = 0,
    // objectId is ephemeral; do not cache across calls. Always resolve a fresh objectId when needed.
    val objectId: String? = null
) {
    /**
     * Check if the node may exist.
     *
     * At least one of nodeId and backendNodeId is positive.
     * */
    fun mayExist(): Boolean {
        return nodeId > 0 || backendNodeId > 0
    }

    fun isNull(): Boolean {
        return nodeId == 0 && backendNodeId == 0
    }
}

class PageHandler(
    private val devTools: RemoteDevTools,
    private val isolatedWorldManager: IsolatedWorldManager,
) {
    companion object {
        // see org.w3c.dom.Node.ELEMENT_NODE
        const val ELEMENT_NODE = 1
    }

    private val logger = getLogger(this)

    private val isActive get() = AppContext.isActive && devTools.isOpen
    private val pageAPI get() = devTools.page.takeIf { isActive }
    private val domAPI get() = devTools.dom.takeIf { isActive }
    private val cssAPI get() = devTools.css.takeIf { isActive }
    private val runtimeAPI get() = devTools.runtime.takeIf { isActive }

    val jsHandler = JsHandler(devTools, this, isolatedWorldManager)

    val mouse = Mouse(devTools)
    val keyboard = Keyboard(devTools)

    @Throws(ChromeDriverException::class)
    suspend fun navigate(@ParamName("url") url: String): Navigate? {
        return pageAPI?.navigate(url)
    }

    @Throws(ChromeDriverException::class)
    suspend fun navigate(
        @ParamName("url") url: String,
        @Optional @ParamName("referrer") referrer: String? = null,
        @Optional @ParamName("transitionType") transitionType: TransitionType? = null,
        @Optional @ParamName("frameId") frameId: String? = null,
        @Experimental @Optional @ParamName("referrerPolicy") referrerPolicy: ReferrerPolicy? = null
    ): Navigate? {
        return pageAPI?.navigate(url, referrer, transitionType, frameId, referrerPolicy)
    }

    suspend fun exists(selector: String): Boolean {
        val rootId = domAPI?.getDocument()?.nodeId ?: return false
        val nodeId = try {
            // Executes `querySelector` on a given node.
            domAPI?.querySelector(rootId, selector)
        } catch (e: CDPReturnError) {
            // code: -32000 message: "Could not find node with given id"
            // This exception is expected, will change this log to debug
            if (e.errorCode != -32000L) {
                logger.warn("Exception from domAPI?.querySelector | {} {} | {}", e.errorCode, e.errorMessage, e.brief())
            }
            null
        } catch (e: Exception) {
            logger.warn("Exception executing `querySelector` on node $rootId.", e)
            null
        }
        return nodeId != null && nodeId > 0
    }

    @Deprecated("Use resolveSelector instead", ReplaceWith("resolveSelector(selector)"))
    @Throws(ChromeDriverException::class)
    suspend fun querySelector(selector: String): NodeRef? {
        return resolveSelector(selector)
    }

    /**
     * Queries for an element using a CSS selector or backend node ID.
     *
     * Supports two selector formats:
     * 1. CSS selector: "div.class", "#id", etc.
     * 2. Backend node ID: "backend:123"
     *
     * @param selector CSS selector or "backend:nodeId" format
     * @return nodeId or null if not found
     */
    @Throws(ChromeDriverException::class)
    suspend fun resolveSelector(selector: String): NodeRef? {
        return try {
            resolveSelector0(selector)
        } catch (e: CDPReturnError) {
            // code: -32000 message: "Could not find node with given id"
            // This exception is expected, will change this log to debug
            if (e.errorCode != -32000L) {
                // -32000L is expected, no log needed
                logger.warn("Exception resolveSelector | {} {} | {}", e.errorCode, e.errorMessage, e.brief())
            }
            null
        } catch (e: Exception) {
            logger.warn("[Unexpected] exception ", e)
            null
        }
    }

    /**
     * Resolves a selector to a `NodeRef` object, which contains information about the DOM node.
     * This method supports two types of selectors:
     * 1. Regular CSS selector: Resolves to a `NodeRef` using `querySelectorOrNull`.
     * 2. Backend node ID selector: Resolves to a `NodeRef` using `resolveByBackendNodeId`.
     *
     * @param selector A string representing the selector. It can be:
     * - A CSS selector (e.g., "div.class", "#id").
     * - A backend node ID in the format "backend:123".
     * - A frame-backendNode in the format "fbn:FRAMEID,123"
     *
     * @return A `NodeRef` object if the selector resolves successfully, or `null` if not found.
     *
     * @throws ChromeDriverException If an error occurs during the resolution process.
     */
    @Throws(ChromeDriverException::class)
    private suspend fun resolveSelector0(selector: String): NodeRef? {
        // Parse the selector into a Locator object. If parsing fails, return null.
        val locator = Locator.parse(selector) ?: return null

        // Determine the type of the locator and resolve accordingly.
        val nodeRef = when (locator.type) {
            // For CSS_PATH type, use querySelectorOrNull to resolve the selector.
            Locator.Type.CSS_PATH -> resolveCSSSelector0(selector)

            Locator.Type.XPATH -> resolveXPath(locator.selector)

            // For BACKEND_NODE_ID type, parse the backend node ID and resolve it.
            Locator.Type.BACKEND_NODE_ID -> {
                val backendNodeId = locator.selector.toIntOrNull()
                if (backendNodeId == null) {
                    logger.warn("Invalid backend node ID format: '{}'", selector)
                    return null
                }
                resolveByBackendNodeId(backendNodeId)
            }

            // For FRAME_BACKEND_NODE_ID type, extract the backend node ID and resolve it.
            Locator.Type.FRAME_BACKEND_NODE_ID -> {
                val backendNodeId = selector.substringAfterLast(",").toIntOrNull() ?: return null
                resolveByBackendNodeId(backendNodeId)
            }

            else -> throw UnsupportedOperationException("Unsupported selector $selector")
        }

        // Return the resolved NodeRef or null if resolution failed.
        return nodeRef
    }

    /**
     * Gets a specific attribute value for the element matching the selector.
     *
     * @param selector CSS selector or "backend:nodeId" format
     * @param attrName Attribute name to retrieve
     * @return Attribute value or null if not found
     */
    @Throws(ChromeDriverException::class)
    suspend fun getAttribute(selector: String, attrName: String) =
        invokeOnElement(selector) { getAttribute(it, attrName) }

    @Throws(ChromeDriverException::class)
    suspend fun getAttribute(node: NodeRef, attrName: String): String? {
        if (node.isNull()) {
            return null
        }

        // `attributes`: n1, v1, n2, v2, n3, v3, ...
        val attributes = domAPI?.getAttributes(node.nodeId) ?: return null
        val nameIndex = attributes.indexOf(attrName)
        if (nameIndex < 0) {
            return null
        }
        val valueIndex = nameIndex + 1
        return attributes.getOrNull(valueIndex)
    }

    /**
     * Checks if the element matching the selector is visible.
     *
     * @param selector CSS selector or "backend:nodeId" format
     * @return true if visible, false otherwise
     */
    @Throws(ChromeDriverException::class)
    suspend fun isVisible(selector: String) = predicateOnElement(selector) { isVisible(it) }

    @Throws(ChromeDriverException::class)
    suspend fun isVisible(node: NodeRef): Boolean {
        if (node.isNull()) {
            return false
        }

        var isVisible = true

        val properties = cssAPI?.getComputedStyleForNode(node.nodeId)
        properties?.forEach { prop ->
            when (prop.name) {
                "display" if prop.value == "none" -> isVisible = false
                "visibility" if prop.value == "hidden" -> isVisible = false
                "opacity" if prop.value == "0" -> isVisible = false
            }
        }

        if (isVisible) {
            isVisible = ClickableDOM.create(pageAPI, domAPI, node)?.isVisible() ?: false
        }

        return isVisible
    }

    @Throws(ChromeDriverException::class)
    suspend fun isChecked(selector: String): Boolean {
        val expression = java.lang.String.format(
            """
((selector) => {
  const el = document.querySelector(selector);
  if (!el) return null;
  const role = el.getAttribute('role');
  const ariaChecked = el.getAttribute('aria-checked');

  // 原生 input[type=checkbox|radio]
  if (el.tagName === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) {
    return !!el.checked;
  }

  // ARIA 控件
  if (role === 'checkbox' || role === 'radio' || role === 'switch') {
    if (ariaChecked === 'true') return true;
    if (ariaChecked === 'mixed') return 'mixed';
    return false;
  }

  return null;
})('%s')
            """.trimIndent(), Strings.escapeJsString(selector)
        )

        val result = jsHandler.evaluateValue(expression)

        if (result is Boolean) return result
        return false
    }

    /**
     * This method fetches an element with `selector` and focuses it. If there's no
     * element matching `selector`, the method returns 0.
     *
     * Supports two selector formats:
     * 1. CSS selector: "input#username"
     * 2. Backend node ID: "backend:123"
     *
     * @param selector - A CSS selector or "backend:nodeId" format of an element to focus.
     * If there are multiple elements satisfying the selector, the first will be focused.
     * @returns NodeId which resolves when the element matching selector is
     * successfully focused. Returns 0 if there is no element matching selector.
     */
    @Throws(ChromeDriverException::class)
    suspend fun focusOnSelector(selector: String): NodeRef? {
        val nodeRef = resolveSelector(selector) ?: return null

        // Fix: Only use nodeId parameter, others should be null
        domAPI?.focus(nodeRef.nodeId)

        return nodeRef
    }

    /**
     * Scrolls the element into view if needed.
     *
     * @param selector CSS selector or "backend:nodeId" format
     * @param rect Optional rectangle to scroll into view
     * @return nodeId of the element, or null if not found
     */
    @Throws(ChromeDriverException::class)
    suspend fun scrollIntoViewIfNeeded(selector: String, rect: Rect? = null): NodeRef? {
        val node = resolveSelector(selector) ?: return null

        // Prefer smooth behavior when rect is not specified; otherwise honor rect via CDP first
        return try {
            if (rect == null) {
                // Try smooth scrolling via JS on the element itself
                if (trySmoothScroll(node)) return node
            }
            // Fallback or rect path: use CDP DOM API
            scrollIntoViewIfNeeded(node, selector, rect)
        } catch (e: ChromeRPCException) {
            logger.warn(
                "DOM.scrollIntoViewIfNeeded is not supported, fallback to Element.scrollIntoView | {} | {} | {}",
                node, e.message, selector
            )
            // Fallback to legacy helper (CSS-only); safe stringify to avoid quoting issues
            val safeSelector = pulsarObjectMapper().writeValueAsString(selector)
            jsHandler.evaluate("__pulsar_utils__.scrollIntoView($safeSelector)")
            node
        } catch (e: Exception) {
            logger.warn("scrollIntoViewIfNeeded failed | {} | {}", selector, e.brief())
            node
        }
    }

    /**
     * Scrolls the specified rect of the given node into view if not already visible.
     * Note: exactly one between nodeId, backendNodeId and objectId should be passed
     * to identify the node.
     * - nodeId Identifier of the node.
     * - backendNodeId Identifier of the backend node.
     * - objectId JavaScript object id of the node wrapper.
     * @param rect The rect to be scrolled into view, relative to the node's border box, in CSS pixels.
     * When omitted, center of the node will be used, similar to Element.scrollIntoView.
     */
    @Throws(ChromeDriverException::class)
    suspend fun scrollIntoViewIfNeeded(nodeRef: NodeRef, selector: String? = null, rect: Rect? = null): NodeRef? {
        val node = domAPI?.describeNode(nodeRef.nodeId, nodeRef.backendNodeId, nodeRef.objectId, null, false)
        if (node?.nodeType != ELEMENT_NODE) {
            logger.info("Node is not of type HTMLElement | {}", selector ?: node)
            return null
        }

        // If a rect is provided, honor it via CDP; otherwise prefer smooth behavior via JS
        return try {
            if (rect != null) {
                domAPI?.scrollIntoViewIfNeeded(node.nodeId, rect = rect)
                nodeRef
            } else {
                if (trySmoothScroll(nodeRef)) nodeRef else {
                    domAPI?.scrollIntoViewIfNeeded(node.nodeId, rect = null)
                    nodeRef
                }
            }
        } catch (e: ChromeRPCException) {
            // As a last resort, attempt legacy JS utility when a CSS selector is available
            if (!selector.isNullOrBlank()) {
                val safeSelector = pulsarObjectMapper().writeValueAsString(selector)
                jsHandler.evaluate("__pulsar_utils__.scrollIntoView($safeSelector)")
            }
            nodeRef
        }
    }

    /**
     * Try to perform smooth scrolling for the given node using Element.scrollIntoView with behavior:'smooth'.
     * This does not rely on querySelector and works even for backend node selectors.
     *
     * @return true if the call was issued without transport errors, false otherwise.
     */
    private suspend fun trySmoothScroll(nodeRef: NodeRef): Boolean {
        return try {
            // Resolve a fresh objectId every time; it's ephemeral and must not be cached
            val resolved = when {
                nodeRef.nodeId > 0 -> domAPI?.resolveNode(nodeRef.nodeId, null, null, null)
                nodeRef.backendNodeId > 0 -> domAPI?.resolveNode(null, nodeRef.backendNodeId, null, null)
                else -> null
            }
            val objectId = resolved?.objectId ?: return false
            try {
                // Execute on the element itself to avoid selector issues; center for stability
                val functionDeclaration = """
                    function() {
                        try {
                            this.scrollIntoView({behavior:'smooth', block:'center', inline:'nearest'});
                            return true;
                        } catch (e) { return false; }
                    }
                """.trimIndent()
                runtimeAPI?.callFunctionOn(
                    functionDeclaration, objectId = objectId, returnByValue = true,
                    userGesture = true, awaitPromise = true
                )
                true
            } finally {
                // Always release to avoid leaks
                try {
                    runtimeAPI?.releaseObject(objectId)
                } catch (e: Exception) {
                    logger.debug("Failed to release object {}", objectId, e)
                }
            }
        } catch (e: Exception) {
            // swallow and indicate failure; caller will fallback
            false
        }
    }

    @Throws(ChromeDriverException::class)
    private suspend fun resolveCSSSelector0(selector: String): NodeRef? {
        val rootId = domAPI?.getDocument()?.nodeId ?: return null

        val nodeId = try {
            domAPI?.querySelector(rootId, selector)
        } catch (e: CDPReturnError) {
            // code: -32000 message: "Could not find node with given id"
            // This exception is expected, will change this log to debug
            if (e.errorCode != -32000L) {
                logger.warn("Exception from domAPI?.querySelector | selector={}, errorCode={}, errorMessage={} | {}",
                    selector, e.errorCode, e.errorMessage, e.brief())
            }
            null
        } catch (e: Exception) {
            logger.warn("Unexpected exception from domAPI?.querySelector ", e)
            null
        }

        if (nodeId == null || nodeId == 0) {
            return null
        }

        val node = try {
            domAPI?.describeNode(nodeId, null, null, null, null)
        } catch (e: Exception) {
            logger.warn("Exception from domAPI?.describeNode | selector=$selector, nodeId=$nodeId", e)
            null
        }

        node ?: return null

        if (node.nodeId == 0 && node.backendNodeId == 0) {
            logger.info("Both nodeId and backendNodeId are not found (value: 0)")
            return null
        }

        return resolveNode(node.nodeId, node.backendNodeId)
    }

    /**
     * Resolves an XPath expression to a NodeRef.
     *
     * Uses DOM.performSearch and DOM.getSearchResults to locate the node,
     * then describes and resolves it to obtain stable node identifiers.
     *
     * @param xpath The XPath expression to resolve.
     * @return A NodeRef containing the resolved node identifiers, or null if not found.
     * @throws ChromeDriverException If an unexpected CDP error occurs.
     */
    @Throws(ChromeDriverException::class)
    private suspend fun resolveXPath(xpath: String): NodeRef? {
        return resolveXPath1(xpath)
    }

    @Throws(ChromeDriverException::class)
    private suspend fun resolveXPath1(xpath: String): NodeRef? {
        if (xpath.isBlank()) {
            return null
        }
        require(xpath.startsWith("//")) { "XPath must start with '//', got: $xpath" }

        val nodeId = try {
            domAPI?.getDocument()?.nodeId ?: return null

            val searchResult = domAPI?.performSearch(xpath, true) ?: return null
            val nodeId = if (searchResult.resultCount > 0) {
                // Only retrieve the first matching node if results exist
                val results = domAPI?.getSearchResults(searchResult.searchId, fromIndex = 0, toIndex = 1)
                // Clean up search results to avoid resource leak
                try { 
                    domAPI?.discardSearchResults(searchResult.searchId) 
                } catch (e: Exception) { 
                    logger.debug("Failed to discard search results for searchId={}", searchResult.searchId, e)
                }
                results?.firstOrNull()
            } else {
                null
            }
            nodeId
        } catch (e: CDPReturnError) {
            // code: -32000 message: "Could not find node with given id"
            // code: -32000 message: "Invalid search result range" (when toIndex > resultCount)
            // These exceptions are expected when element not found
            if (e.errorCode != -32000L) {
                logger.warn("Exception from domAPI?.performSearch/getSearchResults" +
                        " | xpath={}, errorCode={}, errorMessage={} | {}",
                    xpath, e.errorCode, e.errorMessage, e.brief())
            }
            null
        } catch (e: Exception) {
            logger.warn("Unexpected exception from domAPI?.performSearch/getSearchResults ", e)
            null
        }

        if (nodeId == null || nodeId == 0) {
            return null
        }

        val node = try {
            domAPI?.describeNode(nodeId, null, null, null, null)
        } catch (e: Exception) {
            logger.warn("Exception from domAPI?.describeNode | nodeId=$nodeId", e)
            null
        }

        node ?: return null

        if (node.nodeId == 0 && node.backendNodeId == 0) {
            logger.info("Both nodeId and backendNodeId are not found (value: 0)")
            return null
        }

        return resolveNode(node.nodeId, node.backendNodeId)
    }

    @Throws(ChromeDriverException::class)
    private suspend fun resolveByBackendNodeId(backendNodeId: Int): NodeRef? = resolveNode(null, backendNodeId)

    /**
     * Resolves a backend node ID to a regular node ID.
     *
     * @param backendNodeId The backend node ID
     * @return nodeId or null if resolution fails
     */
    @Throws(ChromeDriverException::class)
    private suspend fun resolveNode(nodeId: Int?, backendNodeId: Int?): NodeRef? {
        return try {
            // Protocol return error: "-32000/Either nodeId or backendNodeId must be specified."
            val remoteObject = if (nodeId != null) {
                domAPI?.resolveNode(nodeId, null, null, null)
            } else if (backendNodeId != null) {
                domAPI?.resolveNode(null, backendNodeId, null, null)
            } else {
                return null
            }

            val tempObjectId = remoteObject?.objectId
            if (tempObjectId == null) {
                logger.warn("Failed to resolve node: {}, {}", nodeId, backendNodeId)
                return null
            }

            // Use DOM.requestNode to get the nodeId from the runtime object
            val resolvedNodeId = domAPI?.requestNode(tempObjectId) ?: 0
            // Release the remote object to avoid memory leaks
            try { runtimeAPI?.releaseObject(tempObjectId) } catch (e: Exception) {
                logger.debug("Failed to release object {}", tempObjectId, e)
            }

            // Do NOT cache objectId; return only ids that are stable across calls
            NodeRef(resolvedNodeId, backendNodeId ?: 0, null)
        } catch (e: Exception) {
            logger.warn("Exception resolving backend node ID {}: {}", backendNodeId, e.message)
            null
        }
    }

    @Throws(ChromeDriverException::class)
    private suspend fun <T> invokeOnElement(selector: String, action: suspend (NodeRef) -> T): T? {
        val node = resolveSelector(selector) ?: return null

        return action(node)
    }

    @Throws(ChromeDriverException::class)
    private suspend fun predicateOnElement(selector: String, action: suspend (NodeRef) -> Boolean): Boolean {
        val node = resolveSelector(selector) ?: return false

        if (node.nodeId > 0) {
            return action(node)
        }

        return false
    }
}
