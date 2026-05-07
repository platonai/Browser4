package ai.platon.browser4.driver.chrome

import ai.platon.browser4.driver.chrome.dom.CDPSnapshotService
import ai.platon.browser4.driver.chrome.dom.Locator
import ai.platon.browser4.driver.chrome.dom.SnapshotService
import ai.platon.browser4.driver.chrome.dom.model.BrowserUseState
import ai.platon.browser4.driver.chrome.dom.model.ElementRefCriteria
import ai.platon.browser4.driver.chrome.dom.model.PageTarget
import ai.platon.browser4.driver.chrome.dom.model.SnapshotOptions
import ai.platon.browser4.driver.chrome.experimental.CDP
import ai.platon.browser4.driver.chrome.util.CDPReturnError
import ai.platon.browser4.driver.chrome.util.ChromeDriverException
import ai.platon.browser4.driver.chrome.util.ChromeRPCException
import ai.platon.cdt.kt.protocol.types.dom.Rect
import ai.platon.cdt.kt.protocol.types.page.Navigate
import ai.platon.cdt.kt.protocol.types.page.ReferrerPolicy
import ai.platon.cdt.kt.protocol.types.page.TransitionType
import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger

data class LocatorAndCssSelector(
    val locator: Locator,
    val cssSelector: String?,
)

class PageHandler(
    private val devTools: RemoteDevTools,
    private val isolatedWorldManager: IsolatedWorldManager,
) {
    companion object {
        // see org.w3c.dom.Node.ELEMENT_NODE
        const val ELEMENT_NODE = 1
    }

    private val logger = getLogger(this)

    /** Single access point for all CDP domain APIs. */
    private val cdp = CDP(devTools)

    private val isActive get() = AppContext.isActive && devTools.isOpen

    private var lastBrowserUseState: BrowserUseState? = null

    val snapshotService: SnapshotService by lazy { CDPSnapshotService(devTools) }

    val jsHandler: JsHandler = JsHandler(devTools, this, isolatedWorldManager)

    val mouse = Mouse(cdp)
    val keyboard = Keyboard(cdp)

    @Throws(ChromeDriverException::class)
    suspend fun navigate(url: String): Navigate? {
        return if (isActive) cdp.navigate(url) else null
    }

    @Throws(ChromeDriverException::class)
    suspend fun navigate(
        url: String,
        referrer: String? = null,
        transitionType: TransitionType? = null,
        frameId: String? = null,
        referrerPolicy: ReferrerPolicy? = null
    ): Navigate? {
        return if (isActive) cdp.navigate(url, referrer, transitionType, frameId, referrerPolicy) else null
    }

    suspend fun exists(locator: String): Boolean {
        val node = resolveLocator(locator)
        val nodeId = node?.nodeId
        return nodeId != null && nodeId > 0
    }

    /**
     * Queries for an element using a selector.
     *
     * Supports locator formats:
     * - CSS selector: "div.class", "#id", etc.
     * - XPath selector: "//div[@class='class']", etc.
     * - Backend node ID: "backend:123", "e1233"
     * - Frame backend node ID: "fbn:FRAMExID,123"
     *
     * @param locator The element locator, multiple formats are supported.
     * @return nodeId or null if not found
     */
    @Throws(ChromeDriverException::class)
    suspend fun queryLocator(locator: String): NodeRef? {
        return resolveLocator(locator)
    }

    /**
     * Queries for a list of elements using a selector.
     *
     * Supports four selector formats:
     * - CSS selector: "div.class", "#id", etc.
     * - XPath selector: "//div[@class='class']", etc.
     * - Backend node ID: "backend:123", "e1233"
     * - Frame backend node ID: "fbn:FRAMExID,123"
     *
     * @param locator The element locator, multiple formats are supported.
     * @return nodeId or null if not found
     */
    @Throws(ChromeDriverException::class)
    suspend fun queryLocatorAll(locator: String): List<NodeRef>? {
        return resolveLocatorAll(locator)
    }

    /**
     * Fetches the current ARIA snapshot of the page, which is a YAML representation of the accessibility tree.
     * */
    suspend fun ariaSnapshot(): String {
        val buState = snapshotService.getBrowserUseState(PageTarget(), SnapshotOptions())
        val snapshot = buState.domState.ariaSnapshot
        lastBrowserUseState = buState
        return snapshot
    }

    /**
     * Fetches the ARIA snapshot for the specified viewports only.
     *
     * @param viewportIndices The 1-based viewport indices to include.
     * @return The ARIA snapshot YAML covering only the requested viewports.
     */
    suspend fun ariaSnapshot(viewportIndices: List<Int>): String {
        val buState = snapshotService.getBrowserUseState(PageTarget(), SnapshotOptions())
        lastBrowserUseState = buState

        val scrollState = buState.browserState.scrollState
        val viewportHeight = scrollState.viewportHeight.toDouble()
        val serializableTree = buState.domState.serializableTree

        val sortedIndices = viewportIndices.distinct().sorted()
        // Merge contiguous viewport ranges into Y-axis ranges and build a combined NanoTree
        val nanoTrees = mergeViewportRanges(sortedIndices).map { (startIdx, endIdx) ->
            val startY = ((startIdx - 1) * viewportHeight).coerceAtLeast(0.0)
            val endY = endIdx * viewportHeight
            serializableTree.toNanoTreeInRange(startY, endY)
        }

        // Join snapshots from disjoint viewport ranges using YAML document separator
        return nanoTrees.joinToString("\n---\n") { it.ariaSnapshot }
    }

    /**
     * Merge contiguous 1-based viewport indices into (start, end) pairs for efficient range queries.
     * E.g., [1, 2, 3, 5, 7, 8] → [(1, 3), (5, 5), (7, 8)]
     */
    private fun mergeViewportRanges(sortedIndices: List<Int>): List<Pair<Int, Int>> {
        if (sortedIndices.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<Int, Int>>()
        var start = sortedIndices[0]
        var end = start
        for (i in 1 until sortedIndices.size) {
            if (sortedIndices[i] == end + 1) {
                end = sortedIndices[i]
            } else {
                result.add(start to end)
                start = sortedIndices[i]
                end = start
            }
        }
        result.add(start to end)
        return result
    }

    @Throws(ChromeDriverException::class)
    private suspend fun resolveLocatorAll(locator: String): List<NodeRef>? {
        return try {
            doResolveLocatorAll(locator)
        } catch (e: CDPReturnError) {
            // code: -32000 message: "Could not find node with given id"
            // This exception is expected, will change this log to debug
            if (e.errorCode != -32000L) {
                // -32000L is expected, no log needed
                logger.warn("Exception resolveSelectorAll | {} {} | {}", e.errorCode, e.errorMessage, e.brief())
            } else {
                logger.debug("Element not found for selector '{}'", locator)
            }
            null
        } catch (e: Exception) {
            logger.warn("[Unexpected] exception ", e)
            null
        }
    }

    @Throws(ChromeDriverException::class)
    private suspend fun doResolveLocatorAll(locator: String): List<NodeRef>? {
        // Parse the selector into a Locator object. If parsing fails, return null.
        val (l, cssSelector) = normalizeLocatorForNonJs(locator) ?: return null

        require(Locator.Type.CSS_PATH.text.isEmpty())

        // Determine the type of the locator and resolve accordingly.
        return when {
            // For CSS_PATH type, use resolveCSSSelectorAll0 to resolve the selector.
            cssSelector != null -> resolveCSSSelectorAll(cssSelector)

            l.type == Locator.Type.XPATH -> resolveXPathAll(l.selector)

            // For BACKEND_NODE_ID and FRAME_BACKEND_NODE_ID types, use the single resolver and wrap in list.
            l.type == Locator.Type.BACKEND_NODE_ID || l.type == Locator.Type.FRAME_BACKEND_NODE_ID -> {
                // Optimized: handle BACKEND_NODE_ID directly to avoid re-parsing in resolveSelector0
                if (l.type == Locator.Type.BACKEND_NODE_ID) {
                    val backendNodeId = l.selector.toIntOrNull()
                    if (backendNodeId != null) {
                        val node = resolveByBackendNodeId(backendNodeId)
                        if (node != null) listOf(node) else null
                    } else {
                        null
                    }
                } else {
                    // Fallback to resolveSelector0 for complex types like FRAME_BACKEND_NODE_ID
                    val nodeRef = doResolveLocator(l.selector)
                    if (nodeRef != null) listOf(nodeRef) else null
                }
            }

            else -> throw UnsupportedOperationException("Unsupported selector $locator")
        }
    }

    @Throws(ChromeDriverException::class)
    private suspend fun resolveCSSSelectorAll(selector: String): List<NodeRef>? {
        if (!isActive) return null
        val rootId = cdp.getDocument().nodeId ?: return null

        val nodeIds = try {
            cdp.querySelectorAll(rootId, selector)
        } catch (e: CDPReturnError) {
            if (e.errorCode != -32000L) {
                logger.warn(
                    "Exception from domAPI?.querySelectorAll | selector={}, errorCode={}, errorMessage={} | {}",
                    selector, e.errorCode, e.errorMessage, e.brief()
                )
            }
            null
        } catch (e: Exception) {
            logger.warn("Unexpected exception from domAPI?.querySelectorAll ", e)
            null
        }

        if (nodeIds.isNullOrEmpty()) {
            return null
        }

        // Optimized: map directly to NodeRefs without resolving each one via CDP if possible.
        // querySelectorAll returns nodeIds which are already valid.
        return nodeIds.map { nodeId -> NodeRef(nodeId, 0, null) }
    }

    @Throws(ChromeDriverException::class)
    private suspend fun resolveXPathAll(xpath: String): List<NodeRef>? {
        require(xpath.startsWith("//"))

        return try {
            if (!isActive) return null
            cdp.getDocument()?.nodeId ?: return null

            val searchResult = cdp.performSearch(xpath, true) ?: return null
            val nodeIds = if (searchResult.resultCount > 0) {
                // Retrieve all matching nodes
                val results =
                    cdp.getSearchResults(searchResult.searchId, fromIndex = 0, toIndex = searchResult.resultCount)
                // Clean up search results to avoid resource leak
                try {
                    cdp.discardSearchResults(searchResult.searchId)
                } catch (_: Exception) {
                }
                results
            } else {
                null
            }

            if (nodeIds.isNullOrEmpty()) {
                return null
            }

            return nodeIds.map { nodeId -> NodeRef(nodeId, 0, null) }
        } catch (e: CDPReturnError) {
            if (e.errorCode != -32000L) {
                logger.warn(
                    "Exception from domAPI?.performSearch/getSearchResults" +
                            " | xpath={}, errorCode={}, errorMessage={} | {}",
                    xpath, e.errorCode, e.errorMessage, e.brief()
                )
            }
            null
        } catch (e: Exception) {
            logger.warn("Unexpected exception from domAPI?.performSearch/getSearchResults ", e)
            null
        }
    }

    @Throws(ChromeDriverException::class)
    private suspend fun resolveLocator(locator: String): NodeRef? {
        return try {
            doResolveLocator(locator)
        } catch (e: CDPReturnError) {
            // code: -32000 message: "Could not find node with given id"
            // This exception is expected, will change this log to debug
            if (e.errorCode != -32000L) {
                // -32000L is expected, no log needed
                logger.warn("Exception resolveSelector | {} {} | {}", e.errorCode, e.errorMessage, e.brief())
            } else {
                logger.debug("Element not found for selector '{}'", locator)
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
     * @param locator A string representing the locator. It can be:
     * - A CSS selector (e.g., "div.class", "#id").
     * - A backend node ID in the format "backend:123".
     * - A frame-backendNode int the format "fbn:FRAMExID,123"
     *
     * @return A `NodeRef` object if the selector resolves successfully, or `null` if not found.
     *
     * @throws ChromeDriverException If an error occurs during the resolution process.
     */
    @Throws(ChromeDriverException::class)
    private suspend fun doResolveLocator(locator: String): NodeRef? {
        val (l, cssSelector) = normalizeLocatorForNonJs(locator) ?: return null

        // Ensure that the default locator type, CSS_PATH, has no prefix
        require(Locator.Type.CSS_PATH.text.isEmpty())

        // Determine the type of the locator and resolve accordingly.
        val nodeRef = when {
            // For CSS_PATH type, use querySelectorOrNull to resolve the selector.
            cssSelector != null -> resolveCSSSelector(cssSelector)

            l.type == Locator.Type.XPATH -> resolveXPath(l.selector)

            // For BACKEND_NODE_ID type, parse the backend node ID and resolve it.
            l.type == Locator.Type.BACKEND_NODE_ID -> {
                val backendNodeId = l.selector.toIntOrNull()
                if (backendNodeId == null) {
                    logger.warn("Invalid backend node ID format: '{}'", l.selector)
                    return null
                }
                resolveByBackendNodeId(backendNodeId)
            }

            // For FRAME_BACKEND_NODE_ID type, extract the backend node ID and resolve it.
            l.type == Locator.Type.FRAME_BACKEND_NODE_ID -> {
                val backendNodeId = l.selector.substringAfterLast(",").toIntOrNull() ?: return null
                resolveByBackendNodeId(backendNodeId)
            }

            else -> throw UnsupportedOperationException("Unsupported selector ${l.selector}")
        }

        // Return the resolved NodeRef or null if resolution failed.
        return nodeRef
    }

    /**
     * Gets a specific attribute value for the element matching the selector.
     *
     * @param locator The element locator, multiple formats are supported.
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
        if (!isActive) return null
        val attributes = cdp.getAttributes(node.nodeId) ?: return null
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
     * @param locator The element locator, multiple formats are supported.
     * @return true if visible, false otherwise
     */
    @Throws(ChromeDriverException::class)
    suspend fun isVisible(locator: String) = predicateOnElement(locator) { isVisible(it) }

    @Throws(ChromeDriverException::class)
    suspend fun isVisible(node: NodeRef): Boolean {
        if (node.isNull()) {
            return false
        }

        var isVisible = true

        val properties = if (isActive) cdp.getComputedStyleForNode(node.nodeId) else null
        properties?.forEach { prop ->
            when (prop.name) {
                "display" if prop.value == "none" -> isVisible = false
                "visibility" if prop.value == "hidden" -> isVisible = false
                "opacity" if prop.value == "0" -> isVisible = false
            }
        }

        if (isVisible) {
            isVisible = if (!isActive) {
                false
            } else {
                ClickableDOM.create(cdp, node)?.isVisible() ?: false
            }
        }

        return isVisible
    }

    @Throws(ChromeDriverException::class)
    suspend fun isChecked(selector: String): Boolean {
        return predicateOnElement(selector) { isChecked(it) }
    }

    @Throws(ChromeDriverException::class)
    suspend fun isChecked(node: NodeRef): Boolean {
        if (node.isNull()) {
            return false
        }

        return withNodeObjectId(devTools, node) { objectId ->
            val result = if (isActive) cdp.callFunctionOn(
                CheckableElementJs.IS_CHECKED_FUNCTION_DECLARATION,
                objectId = objectId,
                returnByValue = true,
                awaitPromise = true
            ) else null

            result?.result?.value as? Boolean ?: false
        } ?: false
    }

    /**
     * This method fetches an element with `selector` and focuses it. If there's no
     * element matching `selector`, the method returns 0.
     *
     * Supports two selector formats:
     * 1. CSS selector: "input#username"
     * 2. Backend node ID: "backend:123"
     *
     * @param locator - A CSS selector or "backend:nodeId" format of an element to focus.
     * If there are multiple elements satisfying the selector, the first will be focused.
     * @returns NodeId which resolves when the element matching selector is
     * successfully focused. Returns 0 if there is no element matching selector.
     */
    @Throws(ChromeDriverException::class)
    suspend fun focusOnSelector(locator: String): NodeRef? {
        val nodeRef = queryLocator(locator) ?: return null

        // Fix: Only use nodeId parameter, others should be null
        if (isActive) cdp.focus(nodeRef.nodeId)

        return nodeRef
    }

    /**
     * Scrolls the element into view if needed.
     *
     * @param locator CSS selector or "backend:nodeId" format
     * @param rect Optional rectangle to scroll into view
     * @return nodeId of the element, or null if not found
     */
    @Throws(ChromeDriverException::class)
    suspend fun scrollIntoViewIfNeeded(locator: String, rect: Rect? = null): NodeRef? {
        val node = resolveLocator(locator) ?: return null

        // Prefer smooth behavior when rect is not specified; otherwise honor rect via CDP first
        return try {
            if (rect == null) {
                // Try smooth scrolling via JS on the element itself
                if (trySmoothScroll(node)) return node
            }
            // Fallback or rect path: use CDP DOM API
            scrollIntoViewIfNeeded(node, locator, rect)
        } catch (e: ChromeRPCException) {
            logger.warn(
                "DOM.scrollIntoViewIfNeeded is not supported, fallback to Element.scrollIntoView | {} | {} | {}",
                node, e.message, locator
            )
            // Fallback to legacy helper (CSS-only); safe stringify to avoid quoting issues
            val safeSelector = normalizeLocatorForJs(locator)
            jsHandler.evaluate("__pulsar_utils__.scrollIntoView($safeSelector)")
            node
        } catch (e: Exception) {
            logger.warn("scrollIntoViewIfNeeded failed | {} | {}", locator, e.brief())
            node
        }
    }

    /**
     * Scrolls the specified rect of the given node into view if not already visible.
     * Note: exactly one of nodeId, backendNodeId and objectId should be passed
     * to identify the node.
     * - nodeId Identifier of the node.
     * - backendNodeId Identifier of the backend node.
     * - objectId JavaScript object id of the node wrapper.
     * @param rect The rect to be scrolled into view, relative to the node's border box, in CSS pixels.
     * When omitted, center of the node will be used, similar to Element.scrollIntoView.
     */
    @Throws(ChromeDriverException::class)
    suspend fun scrollIntoViewIfNeeded(nodeRef: NodeRef, selector: String? = null, rect: Rect? = null): NodeRef? {
        val node = if (isActive) cdp.describeNode(
            nodeRef.nodeId,
            nodeRef.backendNodeId,
            nodeRef.objectId,
            null,
            false
        ) else null
        if (node?.nodeType != ELEMENT_NODE) {
            logger.info("Node is not of type HTMLElement | {}", selector ?: node)
            return null
        }

        // If a rect is provided, honor it via CDP; otherwise prefer smooth behavior via JS
        return try {
            if (rect != null) {
                cdp.scrollIntoViewIfNeeded(node.nodeId, rect = rect)
                nodeRef
            } else {
                if (trySmoothScroll(nodeRef)) nodeRef else {
                    cdp.scrollIntoViewIfNeeded(node.nodeId, rect = null)
                    nodeRef
                }
            }
        } catch (e: ChromeRPCException) {
            // As a last resort, attempt legacy JS utility when a CSS selector is available
            if (!selector.isNullOrBlank()) {
                val safeSelector = normalizeLocatorForJs(selector)
                jsHandler.evaluate("__pulsar_utils__.scrollIntoView($safeSelector)")
            }
            nodeRef
        }
    }

    fun findCssSelectorFromLocator(locator: String): LocatorAndCssSelector? {
        val l = Locator.parse(locator) ?: return null

        if (l.type == Locator.Type.CSS_PATH) {
            return LocatorAndCssSelector(l, l.selector)
        }

        val backendNodeId = when (l.type) {
            Locator.Type.FRAME_BACKEND_NODE_ID -> l.selector.toIntOrNull()
            else -> null
        }

        if (backendNodeId != null) {
            val ref = ElementRefCriteria(backendNodeId = backendNodeId)
            val selector = snapshotService.findElement(ref)?.cssSelector()
            return LocatorAndCssSelector(l, selector)
        }

        return LocatorAndCssSelector(l, null)
    }

    /**
     * Normalize an element locator for use in non-JS contexts (e.g. CDP DOM APIs) by extracting a CSS selector if possible.
     * */
    fun normalizeLocatorForNonJs(locator: String): LocatorAndCssSelector? {
        return findCssSelectorFromLocator(locator)
    }

    /**
     * Normalize an element locator for use in JavaScript contexts by extracting a CSS selector if possible and escaping it for safe embedding in JS code.
     * */
    fun normalizeLocatorForJs(locator: String): String {
        val cssSelector = findCssSelectorFromLocator(locator)?.cssSelector

        // consider xpath
        val selector = cssSelector ?: locator
        val safeSelector = Strings.escapeJsString(selector)
        return safeSelector
    }

    /**
     * Try to perform smooth scrolling for the given node using Element.scrollIntoView with behavior:'smooth'.
     * This does not rely on querySelector and works even for backend node selectors.
     *
     * @return true if the call was issued without transport errors, false otherwise.
     */
    private suspend fun trySmoothScroll(nodeRef: NodeRef): Boolean {
        return try {
            withNodeObjectId(devTools, nodeRef) { objectId ->
                // Execute on the element itself to avoid selector issues; center for stability
                val functionDeclaration = """
                    function() {
                        try {
                            this.scrollIntoView({behavior:'smooth', block:'center', inline:'nearest'});
                            return true;
                        } catch (e) { return false; }
                    }
                """.trimIndent()
                cdp.callFunctionOn(
                    functionDeclaration, objectId = objectId, returnByValue = true,
                    userGesture = true, awaitPromise = true
                )
                true
            } ?: false
        } catch (e: Exception) {
            // swallow and indicate failure; caller will fallback
            false
        }
    }

    @Throws(ChromeDriverException::class)
    private suspend fun resolveCSSSelector(cssSelector: String): NodeRef? {
        if (!isActive) return null
        val rootId = cdp.getDocument().nodeId

        val nodeId = try {
            cdp.querySelector(rootId, cssSelector)
        } catch (e: CDPReturnError) {
            // code: -32000 message: "Could not find node with given id"
            // This exception is expected, will change this log to debug
            if (e.errorCode != -32000L) {
                logger.warn(
                    "Exception from domAPI?.querySelector | selector={}, errorCode={}, errorMessage={} | {}",
                    cssSelector, e.errorCode, e.errorMessage, e.brief()
                )
            }
            null
        } catch (e: Exception) {
            logger.warn("Unexpected exception from domAPI?.querySelector ", e)
            null
        }

        if (nodeId == null || nodeId == 0) {
            return null
        }

        return NodeRef(nodeId, 0, null)
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
        require(xpath.startsWith("//"))

        val nodeId = try {
            if (!isActive) return null
            cdp.getDocument()?.nodeId ?: return null

            val searchResult = cdp.performSearch(xpath, true) ?: return null
            val nodeId = if (searchResult.resultCount > 0) {
                // Only retrieve the first matching node if results exist
                val results = cdp.getSearchResults(searchResult.searchId, fromIndex = 0, toIndex = 1)
                // Clean up search results to avoid resource leak
                try {
                    cdp.discardSearchResults(searchResult.searchId)
                } catch (_: Exception) {
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
                logger.warn(
                    "Exception from domAPI?.performSearch/getSearchResults" +
                            " | xpath={}, errorCode={}, errorMessage={} | {}",
                    xpath, e.errorCode, e.errorMessage, e.brief()
                )
            }
            null
        } catch (e: Exception) {
            logger.warn("Unexpected exception from domAPI?.performSearch/getSearchResults ", e)
            null
        }

        if (nodeId == null || nodeId == 0) {
            return null
        }

        return NodeRef(nodeId, 0, null)
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
        // If we already have a nodeId, verify it's valid or just use it.
        // However, we need to return a NodeRef which might need backendNodeId if not provided.
        // Usually, resolveNode is called when we want to ensure we have a valid nodeId for a backendNodeId,
        // or we have a nodeId and want to get a stable reference.

        return try {
            // Protocol return error: "-32000/Either nodeId or backendNodeId must be specified."
            val remoteObject = if (nodeId != null && nodeId > 0) {
                // If nodeId is provided, we might not need to resolve it again unless we want to verify it exists
                // But the original code resolved it. Let's keep the behavior but check if it's necessary.
                // Resolving a nodeId returns a RemoteObject.
                cdp.resolveNodeByNodeId(nodeId)
            } else if (backendNodeId != null && backendNodeId > 0) {
                cdp.resolveNodeByBackendNodeId(backendNodeId)
            } else {
                return null
            }

            val tempObjectId = remoteObject?.objectId
            if (tempObjectId == null) {
                logger.warn("Failed to resolve node: {}, {}", nodeId, backendNodeId)
                return null
            }

            // Use DOM.requestNode to get the nodeId from the runtime object.
            // This is crucial when we started with a backendNodeId.
            // When started with nodeId, it should return the same nodeId.
            val resolvedNodeId = cdp.requestNode(tempObjectId) ?: 0

            // Release the remote object to avoid memory leaks
            try {
                cdp.releaseObject(tempObjectId)
            } catch (_: Exception) {
            }

            if (resolvedNodeId == 0) {
                return null
            }

            // Do NOT cache objectId; return only ids that are stable across calls
            NodeRef(resolvedNodeId, backendNodeId ?: 0, null)
        } catch (e: Exception) {
            logger.warn("Exception resolving backend node ID {}: {}", backendNodeId, e.message)
            null
        }
    }

    @Throws(ChromeDriverException::class)
    private suspend fun <T> invokeOnElement(locator: String, action: suspend (NodeRef) -> T): T? {
        val node = queryLocator(locator) ?: return null

        return action(node)
    }

    @Throws(ChromeDriverException::class)
    private suspend fun predicateOnElement(locator: String, action: suspend (NodeRef) -> Boolean): Boolean {
        val node = queryLocator(locator) ?: return false

        if (node.nodeId > 0) {
            return action(node)
        }

        return false
    }
}
