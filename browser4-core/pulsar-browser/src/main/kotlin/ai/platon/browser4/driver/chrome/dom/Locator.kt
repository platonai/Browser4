package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.dom.model.MergedDOMTreeNode
import org.apache.commons.lang3.StringUtils

open class Locator(
    val type: Type,
    val selector: String,
) {
    enum class Type(val text: String) {
        CSS_PATH(""),
        XPATH("xpath"),
        HASH("hash"),
        BACKEND_NODE_ID("backend"),
        FRAME_BACKEND_NODE_ID("fbn"),
        NODE_ID("node"),
        INDEX("index");

        override fun toString() = text

        companion object {
            fun parse(str: String): Type? {
                return when (str) {
                    "" -> CSS_PATH
                    "css" -> CSS_PATH
                    "xpath" -> XPATH
                    "hash" -> HASH
                    "backend" -> BACKEND_NODE_ID
                    "node" -> NODE_ID
                    "fbn" -> FRAME_BACKEND_NODE_ID
                    "index" -> INDEX
                    else -> null
                }
            }
        }
    }

    val prefix get() = when (type) {
        Type.CSS_PATH -> ""
        else -> this@Locator.type.text + ":"
    }

    val absoluteSelector get() = "$prefix$selector"

    override fun hashCode() = absoluteSelector.hashCode()

    override fun equals(other: Any?): Boolean {
        return when (other) {
            is Locator -> absoluteSelector == other.absoluteSelector
            else -> false
        }
    }

    override fun toString(): String = absoluteSelector

    companion object {
        fun parse(selector: String): Locator? {
            var trimmed = selector.trim()

            // support playwright format: `e15`, where `e` stands for element and `15` is the backend node id. This is equivalent to `backend:15`
            if (trimmed.startsWith("e") && trimmed.length > 1 && StringUtils.isNumeric(trimmed.substring(1))) {
                trimmed = "backend:${trimmed.substring(1)}"
            }

            val parts = trimmed.split(':')
            if (parts.size == 1) {
                return if (parts[0].startsWith("//")) {
                    Locator(Type.XPATH, parts[0])
                } else Locator(Type.CSS_PATH, parts[0])
            }

            val type = Type.parse(parts[0]) ?: return null
            return Locator(type, trimmed.substringAfter(":"))
        }
    }
}

class FBNLocator(
    val frameId: String,
    val backendNodeId: Int
): Locator(Type.FRAME_BACKEND_NODE_ID, "$frameId$SEPARATOR$backendNodeId") {

    constructor(frameId: Int, backendNodeId: Int): this(frameId.toString(), backendNodeId)

    val ref get() = "e$backendNodeId"

    val isRelative: Boolean get() = StringUtils.isNumeric(frameId)

    val isAbsolute: Boolean get() = !isRelative

    companion object {
        const val SEPARATOR = ","
        const val PREFIX = "fbn:"
        const val SIMPLIFIED_PATTERN = "\\d+$SEPARATOR\\d+"
        val SIMPLIFIED_REGEX = SIMPLIFIED_PATTERN.toRegex()
        const val PATTERN = "$PREFIX$SIMPLIFIED_PATTERN"
        val REGEX = PATTERN.toRegex()

        fun parse(str: String): FBNLocator? {
            val trimmed = str.trim()
            val frameId = StringUtils.substringBetween(trimmed, ":", SEPARATOR)?.toIntOrNull() ?: 0
            val backendNodeId = trimmed.substringAfterLast(SEPARATOR).toIntOrNull() ?: return null
            return FBNLocator(frameId, backendNodeId)
        }

        fun parseRelaxed(selector: String?): FBNLocator? {
            var trimmed = selector?.trim() ?: return null

            // Multi selectors are supported: `cssPath`, `xpath:`, `backend:`, `node:`, `hash:`, `fbn`, `index`
            if (!trimmed.startsWith(PREFIX)) {
                trimmed = "$PREFIX$selector"
            }

            return parse(trimmed)
        }
    }
}

class LocatorMap {
    private val map = mutableMapOf<Locator, MergedDOMTreeNode>()

    fun put(locator: Locator, node: MergedDOMTreeNode): MergedDOMTreeNode? {
        return map.put(locator, node)
    }

    operator fun get(locator: Locator): MergedDOMTreeNode? {
        return map[locator]
    }

    fun put(type: Locator.Type, selector: String, node: MergedDOMTreeNode) {
        map[Locator(type, selector)] = node
    }

    fun toStringMap(): Map<String, MergedDOMTreeNode> {
        // Preserve insertion order similar to linkedMapOf in previous implementation
        val out = LinkedHashMap<String, MergedDOMTreeNode>(map.size)
        map.forEach { (k, v) ->
            out[k.absoluteSelector] = v
        }
        return out
    }
}
