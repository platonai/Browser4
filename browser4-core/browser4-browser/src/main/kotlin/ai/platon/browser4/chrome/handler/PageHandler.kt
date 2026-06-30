package ai.platon.browser4.chrome.handler

import ai.platon.browser4.chrome.IsolatedWorldManager
import ai.platon.browser4.chrome.dom.CDPSnapshotService
import ai.platon.browser4.chrome.dom.model.AriaSnapshotOptions
import ai.platon.browser4.chrome.dom.model.ViewportSpec
import ai.platon.browser4.chrome.handler.util.CheckableElementJs
import ai.platon.browser4.chrome.handler.util.withNodeObjectId
import ai.platon.browser4.chrome.util.ChromeDriverException
import ai.platon.browser4.chrome.util.ChromeRPCException
import ai.platon.cdt.kt.protocol.types.dom.Rect
import ai.platon.cdt.kt.protocol.types.page.Navigate
import ai.platon.cdt.kt.protocol.types.page.ReferrerPolicy
import ai.platon.cdt.kt.protocol.types.page.TransitionType
import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.browser.protocol.BrowserProtocol
import ai.platon.pulsar.browser.protocol.NodeRef
import ai.platon.pulsar.chrome.dom.SnapshotService
import ai.platon.pulsar.chrome.dom.model.BrowserUseState
import ai.platon.pulsar.chrome.dom.model.NanoAriaSnapshotRenderer
import ai.platon.pulsar.chrome.dom.model.PageTarget
import ai.platon.pulsar.chrome.dom.model.SnapshotOptions
import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger

class PageHandler constructor(
    private val browserProtocol: BrowserProtocol,
    private val settings: BrowserSettings,
) {
    companion object {
        // see org.w3c.dom.Node.ELEMENT_NODE
        const val ELEMENT_NODE = 1
    }

    private val logger = getLogger(this)

    private val chromeProtocol = browserProtocol as RemoteChromeProtocol

    private val isActive get() = AppContext.isActive && chromeProtocol.isOpen

    private var lastBrowserUseState: BrowserUseState? = null

    val isolatedWorldManager = IsolatedWorldManager(browserProtocol, settings)

    val snapshot: SnapshotService by lazy { CDPSnapshotService(browserProtocol) }
    val js: JsHandler = JsHandler(browserProtocol, this, isolatedWorldManager)
    val dom: DOMHandler = DOMHandler(browserProtocol)
    val mouse = Mouse(browserProtocol)
    val keyboard = Keyboard(browserProtocol)

    @Throws(ChromeDriverException::class)
    suspend fun navigate(url: String): Navigate? {
        return if (isActive) browserProtocol.navigate(url) else null
    }

    @Throws(ChromeDriverException::class)
    suspend fun navigate(
        url: String,
        referrer: String? = null,
        transitionType: TransitionType? = null,
        frameId: String? = null,
        referrerPolicy: ReferrerPolicy? = null
    ): Navigate? {
        return if (isActive) browserProtocol.navigate(url, referrer, transitionType, frameId, referrerPolicy) else null
    }

    suspend fun exists(locator: String): Boolean {
        val node = dom.queryLocator(locator)
        val nodeId = node?.nodeId
        return nodeId != null && nodeId > 0
    }

    /**
     * Fetches the current ARIA snapshot of the page, which is a YAML representation of the accessibility tree.
     *
     * @param boxes When true, includes each element's bounding box as [box=x,y,width,height].
     * */
    suspend fun ariaSnapshot(): String {
        val options = AriaSnapshotOptions()
        val result = ariaSnapshot(options)
        lastBrowserUseState?.let { state ->
            return buildViewportHeader(state) + result + buildViewportFooter(state, options)
        }
        return result
    }

    /**
     * Fetches the ARIA snapshot for the specified viewports only.
     *
     * @param viewportIndices The 0-based viewport indices to include.
     * @param boxes When true, includes each element's bounding box as [box=x,y,width,height].
     * @return The ARIA snapshot YAML covering only the requested viewports.
     */
    suspend fun ariaSnapshot(viewportIndices: List<Int>): String {
        val buState = snapshot.getBrowserUseState(PageTarget(), SnapshotOptions())
        lastBrowserUseState = buState

        val scrollState = buState.browserState.scrollState
        val viewportHeight = scrollState.viewportHeight.toDouble()
        val serializableTree = buState.domState.serializableTree

        val sortedIndices = viewportIndices.distinct().sorted()
        // Merge contiguous viewport ranges into Y-axis ranges and build a combined NanoTree
        val nanoTrees = mergeViewportRanges(sortedIndices).map { (startIdx, endIdx) ->
            val startY = (startIdx * viewportHeight).coerceAtLeast(0.0)
            val endY = (endIdx + 1) * viewportHeight
            serializableTree.toNanoTreeInRange(startY, endY)
        }

        // Join snapshots from disjoint viewport ranges using YAML document separator
        val snapContent = nanoTrees.joinToString("\n---\n") { it.ariaSnapshot }
        return buildViewportHeader(buState) + snapContent + buildViewportFooter(buState, AriaSnapshotOptions())
    }

    /**
     * Fetches the ARIA snapshot with filtering [options] applied.
     *
     * Supports viewport filtering, CSS locator scoping ([AriaSnapshotOptions.selector]),
     * interactive-only mode, URL inclusion, compact mode, and depth limiting.
     *
     * @param options The filtering and rendering options.
     * @return The ARIA snapshot YAML with options applied.
     */
    suspend fun ariaSnapshot(options: AriaSnapshotOptions): String {
        // Resolve --locator to a backendNodeId if provided
        val rootBackendNodeId = options.selector?.let { locator ->
            dom.queryLocator(locator)?.let { node ->
                node.backendNodeId.takeIf { it > 0 }
            }
        }
        val resolvedOptions = options.copy(rootBackendNodeId = rootBackendNodeId)

        // Collect the full browser use state (expensive CDP part)
        val buState = snapshot.getBrowserUseState(PageTarget(), SnapshotOptions())
        lastBrowserUseState = buState

        // If viewports are specified, use existing viewport filtering then render with options
        val snapContent = if (resolvedOptions.viewports != null) {
            val viewportIndices = ViewportSpec.parse(resolvedOptions.viewports) ?: return buState.domState.renderedAriaSnapshot(resolvedOptions)
            val scrollState = buState.browserState.scrollState
            val viewportHeight = scrollState.viewportHeight.toDouble()
            val serializableTree = buState.domState.serializableTree

            val sortedIndices = viewportIndices.distinct().sorted()
            val nanoTrees = mergeViewportRanges(sortedIndices).map { (startIdx, endIdx) ->
                val startY = (startIdx * viewportHeight).coerceAtLeast(0.0)
                val endY = (endIdx + 1) * viewportHeight
                serializableTree.toNanoTreeInRange(startY, endY)
            }
            nanoTrees.joinToString("\n---\n") { NanoAriaSnapshotRenderer.render(it, resolvedOptions) }
        } else {
            buState.domState.renderedAriaSnapshot(resolvedOptions)
        }

        return buildViewportHeader(buState) + snapContent + buildViewportFooter(buState, resolvedOptions)
    }

    /**
     * Merge contiguous 0-based viewport indices into (start, end) pairs for efficient range queries.
     * E.g., [0, 1, 2, 4, 6, 7] → [(0, 2), (4, 4), (6, 7)]
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

    /**
     * Build a YAML comment header with viewport state metadata.
     * Helps AI agents understand the current viewport position and page dimensions.
     */
    private fun buildViewportHeader(buState: BrowserUseState): String {
        val s = buState.browserState.scrollState
        return buildString {
            appendLine("# Viewport State")
            appendLine("# - processingViewport: ${s.processingViewport}")
            appendLine("# - viewportHeight: ${s.viewportHeight}px")
            appendLine("# - viewportsTotal: ${s.viewportsTotal}")
            appendLine("# - hiddenTopHeight: ${s.hiddenTopHeight}px")
            appendLine("# - hiddenBottomHeight: ${s.hiddenBottomHeight}px")
            appendLine("#")
        }
    }

    /**
     * Build a YAML comment footer with viewport navigation guidance.
     * When the page spans multiple viewports, suggests reading the page viewport by
     * viewport — just like a human scrolls through a long page.
     */
    private fun buildViewportFooter(buState: BrowserUseState, options: AriaSnapshotOptions): String {
        val s = buState.browserState.scrollState
        if (s.viewportsTotal <= 1) return ""

        val currentViewport = s.processingViewport
        return buildString {
            appendLine("# ---")
            appendLine("# This page has ${s.viewportsTotal} viewports. You are currently viewing viewport $currentViewport.")
            appendLine("# To read the page viewport by viewport (like a human scrolling):")
            if (currentViewport > 0) {
                appendLine("#   snapshot --viewport=0          # view the top of the page")
            }
            if (currentViewport < s.viewportsTotal - 1) {
                val next = currentViewport + 1
                appendLine("#   snapshot --viewport=$next          # scroll down to viewport $next")
            }
            appendLine("#   snapshot --viewport=0-${s.viewportsTotal - 1}    # capture all viewports at once")
            appendLine("#   snapshot --viewport=all       # capture all viewports (same as above)")
            appendLine("#")
        }
    }

    /**
     * Gets a specific attribute value for the element matching the locator.
     *
     * @param locator The element locator, multiple formats are supported.
     * @param attrName Attribute name to retrieve
     * @return Attribute value or null if not found
     */
    @Throws(ChromeDriverException::class)
    suspend fun getAttribute(locator: String, attrName: String) =
        invokeOnElement(locator) { getAttribute(it, attrName) }

    @Throws(ChromeDriverException::class)
    suspend fun getAttribute(node: NodeRef, attrName: String): String? {
        if (node.isNull()) {
            return null
        }

        // `attributes`: n1, v1, n2, v2, n3, v3, ...
        if (!isActive) return null
        val attributes = browserProtocol.getAttributes(node.nodeId) ?: return null
        val nameIndex = attributes.indexOf(attrName)
        if (nameIndex < 0) {
            return null
        }
        val valueIndex = nameIndex + 1
        return attributes.getOrNull(valueIndex)
    }

    /**
     * Checks if the element matching the locator is visible.
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

        val properties = if (isActive) browserProtocol.getComputedStyleForNode(node.nodeId) else null
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
                ClickableDOM.create(browserProtocol, node)?.isVisible() ?: false
            }
        }

        return isVisible
    }

    @Throws(ChromeDriverException::class)
    suspend fun isChecked(locator: String): Boolean {
        return predicateOnElement(locator) { isChecked(it) }
    }

    @Throws(ChromeDriverException::class)
    suspend fun isChecked(node: NodeRef): Boolean {
        if (node.isNull()) {
            return false
        }

        return withNodeObjectId(browserProtocol, node) { objectId ->
            val result = if (isActive) browserProtocol.callFunctionOn(
                CheckableElementJs.IS_CHECKED_FUNCTION_DECLARATION,
                objectId = objectId,
                returnByValue = true,
                awaitPromise = true
            ) else null

            result?.result?.value as? Boolean ?: false
        } ?: false
    }

    /**
     * This method fetches an element with `locator` and focuses it. If there's no
     * element matching `locator`, the method returns 0.
     *
     * Supports two locator formats:
     * 1. CSS locator: "input#username"
     * 2. Backend node ID: "backend:123"
     *
     * @param locator - A CSS locator or "backend:nodeId" format of an element to focus.
     * If there are multiple elements satisfying the locator, the first will be focused.
     * @returns NodeId which resolves when the element matching locator is
     * successfully focused. Returns 0 if there is no element matching locator.
     */
    @Throws(ChromeDriverException::class)
    suspend fun focusOnSelector(locator: String): NodeRef? {
        val nodeRef = dom.queryLocator(locator) ?: return null

        // Fix: Only use nodeId parameter, others should be null
        if (isActive) browserProtocol.focus(nodeRef.nodeId)

        return nodeRef
    }

    /**
     * Scrolls the element into view if needed.
     *
     * @param locator CSS locator or "backend:nodeId" format
     * @param rect Optional rectangle to scroll into view
     * @return nodeId of the element, or null if not found
     */
    @Throws(ChromeDriverException::class)
    suspend fun scrollIntoViewIfNeeded(locator: String, rect: Rect? = null): NodeRef? {
        val node = dom.queryLocator(locator) ?: return null

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
            // TODO: check if it is necessary to fallback to use JavaScript to scrollIntoView
            val safeSelector = dom.normalizeLocator(locator, true)
            js.evaluate("__pulsar_utils__.scrollIntoView($safeSelector)")
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
    suspend fun scrollIntoViewIfNeeded(nodeRef: NodeRef, locator: String? = null, rect: Rect? = null): NodeRef? {
        val node = if (isActive) browserProtocol.describeNode(
            nodeRef.nodeId,
            nodeRef.backendNodeId,
            nodeRef.objectId,
            null,
            false
        ) else null
        if (node?.nodeType != ELEMENT_NODE) {
            logger.info("Node is not of type HTMLElement | {}", locator ?: node)
            return null
        }

        // If a rect is provided, honor it via CDP; otherwise prefer smooth behavior via JS
        return try {
            if (rect != null) {
                browserProtocol.scrollIntoViewIfNeeded(node.nodeId, rect = rect)
                nodeRef
            } else {
                if (trySmoothScroll(nodeRef)) nodeRef else {
                    browserProtocol.scrollIntoViewIfNeeded(node.nodeId, rect = null)
                    nodeRef
                }
            }
        } catch (_: ChromeRPCException) {
            // As a last resort, attempt legacy JS utility when a CSS locator is available
            // TODO: check if it is necessary to fallback to use JavaScript to scrollIntoView
            if (!locator.isNullOrBlank()) {
                val safeSelector = dom.normalizeSelector(locator, true) ?: locator
                js.evaluate("__pulsar_utils__.scrollIntoView($safeSelector)")
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
            withNodeObjectId(browserProtocol, nodeRef) { objectId ->
                // Execute on the element itself to avoid locator issues; center for stability
                val functionDeclaration = """
                    function() {
                        try {
                            this.scrollIntoView({behavior:'smooth', block:'center', inline:'nearest'});
                            return true;
                        } catch (e) { return false; }
                    }
                """.trimIndent()
                browserProtocol.callFunctionOn(
                    functionDeclaration, objectId = objectId, returnByValue = true,
                    userGesture = true, awaitPromise = true
                )
                true
            } ?: false
        } catch (_: Exception) {
            // swallow and indicate failure; caller will fall back
            false
        }
    }

    @Throws(ChromeDriverException::class)
    private suspend fun <T> invokeOnElement(locator: String, action: suspend (NodeRef) -> T): T? {
        val node = dom.queryLocator(locator) ?: return null

        return action(node)
    }

    @Throws(ChromeDriverException::class)
    private suspend fun predicateOnElement(locator: String, action: suspend (NodeRef) -> Boolean): Boolean {
        val node = dom.queryLocator(locator) ?: return false

        if (node.nodeId > 0) {
            return action(node)
        }

        return false
    }
}
