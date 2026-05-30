package ai.platon.pulsar.chrome.dom.model

import ai.platon.browser4.chrome.dom.DOMSerializer
import ai.platon.browser4.chrome.dom.model.AriaSnapshotRenderer
import ai.platon.browser4.chrome.dom.model.NanoAriaSnapshotRenderer
import ai.platon.browser4.chrome.dom.util.CSSSelectorUtils
import ai.platon.browser4.chrome.dom.util.DOMUtils
import ai.platon.browser4.chrome.dom.util.InteractiveNodeListBuilder
import ai.platon.browser4.chrome.dom.util.InteractiveNodeListBuilder.Companion.estimatedSize
import ai.platon.browser4.chrome.dom.util.NanoDOMTreeBuilder
import ai.platon.pulsar.browser.common.BrowserSettings.Companion.VIEWPORT
import ai.platon.pulsar.browser.common.FBNLocator
import ai.platon.pulsar.browser.common.LocatorMap
import ai.platon.pulsar.common.math.geometric.DimI
import ai.platon.pulsar.common.math.roundTo
import ai.platon.pulsar.common.serialize.json.Pson
import com.fasterxml.jackson.annotation.JsonIgnore
import org.apache.commons.lang3.StringUtils
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.*
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * DOM node types based on the DOM specification.
 */
enum class NodeType(val value: Int) {
    ELEMENT_NODE(1),
    ATTRIBUTE_NODE(2),
    TEXT_NODE(3),
    CDATA_SECTION_NODE(4),
    ENTITY_REFERENCE_NODE(5),
    ENTITY_NODE(6),
    PROCESSING_INSTRUCTION_NODE(7),
    COMMENT_NODE(8),
    DOCUMENT_NODE(9),
    DOCUMENT_TYPE_NODE(10),
    DOCUMENT_FRAGMENT_NODE(11),
    NOTATION_NODE(12);

    companion object {
        fun fromValue(value: Int): NodeType =
            entries.find { it.value == value } ?: ELEMENT_NODE
    }
}

/**
 * Static attributes used for element hashing.
 */
object StaticAttributes {
    val ATTRIBUTES = setOf(
        "class", "id", "name", "type", "placeholder", "aria-label", "title",
        "role", "data-testid", "data-test", "data-cy", "data-selenium",
        "for", "required", "disabled", "readonly", "checked", "selected",
        "multiple", "href", "target", "rel", "aria-describedby",
        "aria-labelledby", "aria-controls", "aria-owns", "aria-live",
        "aria-atomic", "aria-busy", "aria-disabled", "aria-hidden",
        "aria-pressed", "aria-checked", "aria-selected", "tabindex",
        "alt", "src", "lang", "itemscope", "itemtype", "itemprop",
        "pseudo", "aria-valuemin", "aria-valuemax", "aria-valuenow",
        "aria-placeholder"
    )
}


/**
 * Default attributes to include in LLM serialization.
 */
object DefaultIncludeAttributes {
    val ATTRIBUTES = listOf(
        "title", "type", "checked", "id", "name", "role", "value",
        "placeholder", "data-date-format", "alt", "aria-label",
        "href", "src", "action", "target", "rel", "download",
        "aria-expanded", "data-state", "aria-checked", "aria-valuemin",
        "aria-valuemax", "aria-valuenow", "aria-placeholder", "pattern",
        "min", "max", "minlength", "maxlength", "step", "pseudo",
        "checked", "selected", "expanded", "pressed", "disabled",
        "invalid", "valuemin", "valuemax", "valuenow", "keyshortcuts",
        "haspopup", "multiselectable", "required", "valuetext", "level",
        "busy", "live", "ax_name"
    )

    val MORE_ATTRIBUTES = listOf(
        // Navigation and linking attributes
        "href", "src", "action", "target", "rel", "download",
        // Identification and styling
        "class",
    )
}

data class CompactRect(
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null
) {
    fun uncompact(): DOMRect {
        return DOMRect(x ?: 0.0, y ?: 0.0, width ?: 0.0, height ?: 0.0)
    }

    /**
     * Round every field to the nearest integer
     * */
    fun roundTo(decimals: Int = 1, mode: RoundingMode = RoundingMode.HALF_UP): CompactRect? {
        if (x == null && y == null && width == null && height == null) {
            return null
        }

        return CompactRect(
            x?.roundTo(decimals),
            y?.roundTo(decimals),
            width?.roundTo(decimals),
            height?.roundTo(decimals),
        )
    }

    fun round() = roundTo(1)
}

/**
 * DOM rectangle with coordinates.
 */
data class DOMRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
) {
    fun compact(): CompactRect? {
        if (x == 0.0 && y == 0.0 && width == 0.0 && height == 0.0) {
            return null
        }

        return CompactRect(
            x.takeIf { it != 0.0 }, y.takeIf { it != 0.0 },
            width.takeIf { it != 0.0 }, height.takeIf { it != 0.0 })
    }

    fun roundTo(decimals: Int = 1): DOMRect {
        return DOMRect(
            x.roundTo(decimals),
            y.roundTo(decimals),
            width.roundTo(decimals),
            height.roundTo(decimals),
        )
    }

    fun intersects(other: DOMRect): Boolean {
        return x < other.x + other.width &&
                x + width > other.x &&
                y < other.y + other.height &&
                y + height > other.y
    }

    fun area(): Double = width * height

    companion object {
        /**
         * Create DOMRect from CDP's 8-element bounds array: [x1, y1, x2, y2, x3, y3, x4, y4]
         */
        fun fromBoundsArray(bounds: List<Double>): DOMRect? {
            if (bounds.size < 8) return null
            val x = bounds[0]
            val y = bounds[1]
            val width = bounds[2] - bounds[0]
            val height = bounds[5] - bounds[1]
            return DOMRect(x, y, width, height).roundTo(1)
        }

        /**
         * Create DOMRect from CDP's 4-element rect array: [x, y, width, height]
         */
        fun fromRectArray(rect: List<Double>): DOMRect? {
            if (rect.size < 4) return null
            return DOMRect(rect[0], rect[1], rect[2], rect[3]).roundTo(1)
        }
    }
}

/**
 * Enhanced accessibility property.
 */
data class AXPropertyEx(
    val name: String,
    val value: Any? = null
)

/**
 * Enhanced accessibility node with essential AX tree information.
 */
data class AXNodeEx(
    val axNodeId: String,
    /**
     * 表示此节点是否被无障碍服务忽略。
     * 举例：
     * - 被 CSS display:none 隐藏的节点
     * - 纯装饰节点
     * - 没有语义的 wrapper（例如 <span> 容器）
     *
     * 如果 ignored = true，通常不出现在屏幕阅读器内容中。
     *
     * ignoredReasons:
     * - aria-hidden
     * - not-visible
     * - ancestor-ignored
     * */
    val ignored: Boolean = false,
    /**
     * 节点的语义角色，例如：
     * - button
     * - checkbox
     * - radio
     * - textbox
     * - link
     * - paragraph
     * - heading
     * */
    val role: String? = null,
    /**
     * 无障碍名称（Accessible Name），来源可能包括：
     * - <label>
     * - aria-label
     * - 文本节点
     * - alt 属性（图片）
     * */
    val name: String? = null,
    val description: String? = null,
    val properties: List<AXPropertyEx>? = null,
    val childIds: List<String>? = null,
    val backendNodeId: Int? = null,
    val frameId: String? = null
)

/**
 * Enhanced snapshot node data extracted from DOMSnapshot.
 */
data class SnapshotNodeEx constructor(
    val isClickable: Boolean? = null,
    val cursorStyle: String? = null,
    val bounds: DOMRect? = null,
    val clientRects: DOMRect? = null,
    val scrollRects: DOMRect? = null,
    val computedStyles: Map<String, String>? = null,
    val paintOrder: Int? = null,
    val stackingContexts: Int? = null,
    val absoluteBounds: DOMRect? = null
)

/**
 * Enhanced DOM tree node containing merged information from DOM, AX, and Snapshot trees.
 */
data class MergedDOMTreeNode constructor(
    // DOM Node data
    val nodeId: Int = 0,
    val backendNodeId: Int? = null,
    val nodeType: NodeType = NodeType.ELEMENT_NODE,
    val nodeName: String = "",
    val nodeValue: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val isScrollable: Boolean? = null,
    val isVisible: Boolean? = null,
    val absolutePosition: DOMRect? = null,

    // Frame information
    val targetId: String? = null,
    val frameId: String? = null,
    val sessionId: String? = null,

    // Tree structure
    val children: List<MergedDOMTreeNode> = emptyList(),
    val shadowRoots: List<MergedDOMTreeNode> = emptyList(),
    val contentDocument: MergedDOMTreeNode? = null,

    // Snapshot data
    val snapshotNode: SnapshotNodeEx? = null,

    // AX data
    val axNode: AXNodeEx? = null,

    // XPath and hash
    val xpath: String? = null,
    val elementHash: String? = null,
    val parentBranchHash: String? = null,

    // Visibility and interaction
    val isInteractable: Boolean? = null,
    val interactiveIndex: Int? = null
) {
    fun textContent(): String = DOMUtils.textContent(this)

    fun slimHTML(): String = DOMUtils.slimHTML(this)

    /**
     * Build a best-effort CSS selector for this node.
     * Strategy:
     * - If an id exists, prefer #id (or tag[id="..."] if id is not a valid identifier)
     * - Else, use up to a few stable classes: tag.class1.class2
     * - Else, fall back to stable attributes like data-*, aria-label, name, type, role
     * - Else, return the lowercase tag name (or "*")
     */
    fun cssSelector(): String = CSSSelectorUtils.generateCSSSelector(this)

    fun toJson() = Pson.toJson(this)

    fun toYaml() = Pson.toYaml(this)
}

typealias MergedDOMTree = MergedDOMTreeNode

/**
 * An optimized DOM tree.
 */
data class OptimizedDOMTreeNode(
    val originalNode: MergedDOMTreeNode,
    val children: List<OptimizedDOMTreeNode> = emptyList(),
    val shouldDisplay: Boolean = true,
    val interactiveIndex: Int? = null,
    val isNew: Boolean = false,
    val ignoredByPaintOrder: Boolean = false,
    val excludedByParent: Boolean = false,
    val isShadowHost: Boolean = false,
    val isCompoundComponent: Boolean = false
)

typealias OptimizedDOMTree = OptimizedDOMTreeNode

/**
 * DOM interacted element for agent interaction.
 *
 * @property elementHash A hash code calculated from the element
 * @property xpath The xpath of the node
 * @property bounds Bounds are in the page (document) absolute coordinate space.
 *      Origin is the document’s top‑left, not the viewport and not the element’s offset parent.
 *      Viewport coords = bounds - (window.scrollX, window.scrollY).
 *      For iframes, each document’s bounds are relative to its own document; accumulate frame offsets
 *      to get page/screen coords. clientRects/scrollRects here are treated in the same absolute
 *      document space as bounds.
 * @param isVisible If the element is visible
 * @property isInteractable If the element is interactive
 */
data class DOMInteractedElement(
    val elementHash: String,
    val xpath: String? = null,
    val bounds: DOMRect? = null,
    val isVisible: Boolean? = null,
    val isInteractable: Boolean? = null
)

/**
 * Cleaned original node without children_nodes and shadow_roots.
 * Enhanced with additional snapshot information for LLM consumption.
 * This prevents duplication since children nodes already contains them.
 */
data class CleanedDOMTreeNode constructor(
    /**
     * Locator format: `frameIndex,backendNodeId`
     * */
    val locator: String,
    val frameId: String?,
    val xpath: String?,
    val elementHash: String?,
    val nodeId: Int,
    val backendNodeId: Int?,

    val nodeType: Int,
    val nodeName: String,
    val nodeValue: String?,
    val attributes: Map<String, Any>?,
    val sessionId: String?,
    val isScrollable: Boolean?,   // null means false
    val isVisible: Boolean?,      // null means false
    val isInteractable: Boolean?, // null means false
    val interactiveIndex: Int?,

    val clientRects: CompactRect?,
    val scrollRects: CompactRect?,
    /** The absolute position bounding box. */
    val bounds: CompactRect?,
    val absoluteBounds: CompactRect? = null,
    /** A 1-based viewport index */
    val viewportIndex: Int? = null,

    val paintOrder: Int? = null,
    val stackingContexts: Int? = null,
    val contentDocument: CleanedDOMTreeNode?
    // Note: children_nodes and shadow_roots are intentionally omitted
) {
    fun toJson() = Pson.toJson(this)

    override fun hashCode(): Int {
        return toJson().hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CleanedDOMTreeNode

        return toJson() == other.toJson()
    }
}

data class InteractiveDOMTreeNode(
    /**
     * Locator format: `frameIndex,backendNodeId`
     * */
    val backendNodeId: String? = null,
    val slimHTML: String? = null,
    val textBefore: String? = null,
    val scrollable: Boolean? = null,   // null means false
    val invisible: Boolean? = null,    // null means false
    val viewportIndex: Int? = null,
    val clientRects: CompactRect? = null,
    @JsonIgnore
    val bounds: CompactRect? = null,
    @JsonIgnore
    val scrollRects: CompactRect? = null,
    @JsonIgnore
    val absoluteBounds: CompactRect? = null,
    @JsonIgnore
    val interactiveIndex: Int = 0,
    @JsonIgnore
    val prevInteractiveIndex: Int? = null,
    @JsonIgnore
    val nextInteractiveIndex: Int? = null,
) {
    fun isAnchor(): Boolean {
        return slimHTML?.startsWith("<a") == true
    }

    /**
     * String format:
     * ```
     * [locator]{viewport}(x,y,width,height)<slimNode>textContent</slimNode>Text-Before-This-Interactive-Element-And-After-Previous-Interactive-Element
     * ```
     * */
    override fun toString(): String {
        val b = bounds?.roundTo(0) ?: CompactRect()
        val bs = listOf(b.x, b.y, b.width, b.height)
            .map { it?.toInt() ?: 0 }
            .joinToString(",") { it.toString() }

        return buildString {
            append("[")
            append(backendNodeId)
            append("]")
            append("{")
            append(viewportIndex ?: 1)
            append("}")
            append("(")
            append(bs)
            append(")")
            append(slimHTML)
            textBefore?.let { append(it) }
        }
    }
}

class InteractiveDOMTreeNodeList(
    val nodes: List<InteractiveDOMTreeNode> = emptyList(),
) {
    @get:JsonIgnore
    val lazyJson by lazy { DOMSerializer.toJson(this) }

    @get:JsonIgnore
    val lazyString by lazy { toString() }

    fun estimatedSize() = nodes.sumOf { estimatedSize(it) }

    override fun toString(): String {
        return nodes.joinToString("\n")
    }
}

/**
 * Serializable DOM tree node structure. Enhanced with compound component marking and paint order information.
 */
data class SerializableDOMTreeNode(
    val shouldDisplay: Boolean? = null,
    val interactiveIndex: Int? = null,
    val ignoredByPaintOrder: Boolean? = null,
    val excludedByParent: Boolean? = null,
    val isCompoundComponent: Boolean? = null,
    val originalNode: CleanedDOMTreeNode? = null,
    val children: List<SerializableDOMTreeNode>? = null,
    val shouldShowScrollInfo: Boolean? = null,
    val scrollInfoText: String? = null
) {
    private val seenChunks = mutableListOf<Pair<Double, Double>>()

    fun buildInteractiveNodeList(currentViewportIndex: Int, lastViewportIndex: Int): InteractiveDOMTreeNodeList =
        InteractiveNodeListBuilder(this, false, currentViewportIndex, lastViewportIndex)
            .build()

    fun buildInteractiveNodeList(): InteractiveDOMTreeNodeList =
        InteractiveNodeListBuilder(this, true).build()

    fun toNanoTree(): NanoDOMTree = toNanoTreeInRange(0.0, 1000000.0)

    fun toNanoTreeUnfiltered(): NanoDOMTree {
        val builder = NanoDOMTreeBuilder(this, seenChunks)
        return builder.buildUnfiltered()
    }

    fun toNanoTreeInRange(startY: Double = 0.0, endY: Double = 100000.0): NanoDOMTree {
        val builder = NanoDOMTreeBuilder(this, seenChunks)
        return builder.build(startY, endY)
    }
}

typealias SerializableDOMTree = SerializableDOMTreeNode

/**
 * Serializable DOM tree node structure.
 * */
data class NanoDOMTreeNode(
    /**
     * Locator format: `frameIndex,backendNodeId`
     * */
    val locator: String? = null,
    val nodeName: String? = null,
    val nodeValue: String? = null,
    val attributes: Map<String, Any>? = null,
    val scrollable: Boolean? = null,   // null means false
    val interactive: Boolean? = null,  // null means false
    val invisible: Boolean? = null,    // null means false
    val scrollRects: CompactRect? = null,
    val children: List<NanoDOMTreeNode>? = null,

    @JsonIgnore
    val viewportIndex: Int? = null,    // The position of this DOM node falls within the nth viewport, 1-based
    @JsonIgnore
    val interactiveIndex: Int? = null,
    @JsonIgnore
    val clientRects: CompactRect? = null,
    @JsonIgnore
    val bounds: CompactRect? = null,
    @JsonIgnore
    val absoluteBounds: CompactRect? = null,
    @JsonIgnore
    val serializableTreeNode: SerializableDOMTree? = null,
) {
    @get:JsonIgnore
    val ariaSnapshot: String by lazy { NanoAriaSnapshotRenderer.render(this) }

    @get:JsonIgnore
    val ref: Int get() = FBNLocator.parseRelaxed(locator)?.backendNodeId ?: 0
}

typealias NanoDOMTree = NanoDOMTreeNode

data class DOMState constructor(
    val serializableTree: SerializableDOMTree,
    val interactiveNodes: List<SerializableDOMTreeNode> = listOf(),
    val frameIds: List<String> = listOf(),
    val selectorMap: Map<String, MergedDOMTreeNode> = mapOf(),
    val locatorMap: LocatorMap = LocatorMap(),
    @get:JsonIgnore
    val optimizedDOMTree: OptimizedDOMTree? = null
) {
    @get:JsonIgnore
    val ariaSnapshot: String get() = optimizedDOMTree?.let(AriaSnapshotRenderer::render)
        ?: serializableTree.toNanoTreeUnfiltered().ariaSnapshot

    fun getAbsoluteFBNLocator(locator: String?): FBNLocator? {
        if (locator == null) {
            return null
        }

        val fbnLocator = FBNLocator.parseRelaxed(locator) ?: return null
        if (fbnLocator.isAbsolute) {
            return fbnLocator
        }

        require(StringUtils.isNumeric(fbnLocator.frameId))
        val index = fbnLocator.frameId.toIntOrNull() ?: return null
        val absoluteFrameId = frameIds.getOrNull(index) ?: return null

        return FBNLocator(absoluteFrameId, fbnLocator.backendNodeId)
    }
}

data class ClientInfo(
    val datetime: String = OffsetDateTime.now().toString(),
    // time zone: "Asia/Shanghai"
    val timeZone: String = ZoneId.systemDefault().id,
    // locale: "zh_CN"
    val locale: Locale = Locale.getDefault(),
    val viewportWidth: Int = VIEWPORT.width,
    val viewportHeight: Int = VIEWPORT.height,
    val screenWidth: Int = VIEWPORT.width,
    val screenHeight: Int = VIEWPORT.height
)

/**
 * Reserved
 * */
data class FullClientInfo(
    val timeZone: String,
    val locale: Locale,
    val userAgent: String? = null,
    val devicePixelRatio: Double? = null,
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val colorDepth: Int? = null,
    val hardwareConcurrency: Int? = null,
    val deviceMemoryGB: Double? = null,
    val onLine: Boolean? = null,
    val networkEffectiveType: String? = null,
    val saveData: Boolean? = null,
    val prefersDarkMode: Boolean? = null,
    val prefersReducedMotion: Boolean? = null,
    val isSecureContext: Boolean? = null,
    val crossOriginIsolated: Boolean? = null,
    val doNotTrack: String? = null,
    val webdriver: Boolean? = null,
    val historyLength: Int? = null,
    val visibilityState: String? = null,
)

data class ScrollState constructor(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val viewport: DimI = VIEWPORT,
    val totalHeight: Double = VIEWPORT.height.toDouble(),
    val scrollYRatio: Double = 0.0,
) {
    val viewportsTotal get() = ceil(totalHeight / viewport.height).roundToInt()

    // Height in pixels of the page area above the current viewport. (被隐藏在视口上方的部分的高度)
    val hiddenTopHeight get() = y.roundToInt().coerceAtLeast(0)
    val hiddenBottomHeight
        get() = (totalHeight - hiddenTopHeight - viewport.height)
            .roundToInt().coerceAtLeast(0)
    val viewportHeight get() = viewport.height

    val processingViewport get() = (hiddenTopHeight / viewportHeight) + 1
}

/**
 * Tab state information for multi-tab browser context.
 */
data class TabState(
    val id: String,           // Tab ID, aligned with Browser.drivers key
    val driverId: Int? = null, // Driver ID for diagnostics
    val url: String,          // Current URL of the tab
    val title: String? = null, // Tab title
    val active: Boolean = false // Whether this is the active tab
)

data class BrowserState constructor(
    val url: String,
    val scrollState: ScrollState = ScrollState(),
    val goBackUrl: String? = null,
    val goForwardUrl: String? = null,
    val tabs: List<TabState> = emptyList(),
    val activeTabId: String? = null,
    val clientInfo: ClientInfo = ClientInfo(),
) {
    @get:JsonIgnore
    val lazyJson: String by lazy { DOMSerializer.toJson(this) }
}

