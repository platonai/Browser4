package ai.platon.browser4.api.snapshot

import ai.platon.browser4.chrome.dom.model.AriaSnapshotOptions
import ai.platon.pulsar.chrome.dom.model.CompactRect
import ai.platon.pulsar.chrome.dom.model.NanoDOMTreeNode
import java.util.*

object NanoAriaSnapshotRenderer {
    fun render(root: NanoDOMTreeNode, options: AriaSnapshotOptions = AriaSnapshotOptions()): String {
        val counter = if (options.maxNodes > 0) NodeCounter(options.maxNodes) else null
        val children = toRenderChildren(root, options, counter = counter)
        val truncated = counter?.truncated == true
        val rendered = AriaSnapshotFormatting.render(children)
        return if (truncated) {
            "$rendered\n# Snapshot truncated at ${options.maxNodes} nodes. Use --selector, --interactive, or --compact to reduce output."
        } else rendered
    }

    private class NodeCounter(val max: Int) {
        var count = 0
        var truncated = false
        fun increment(): Boolean {
            if (count >= max) {
                truncated = true
                return false
            }
            count++
            return true
        }
    }

    private fun toRenderChildren(
        node: NanoDOMTreeNode,
        options: AriaSnapshotOptions,
        depth: Int = 0,
        counter: NodeCounter? = null
    ): List<AriaSnapshotFormatting.RenderChild> {
        // --max-nodes: stop when limit reached
        if (counter?.truncated == true) {
            return emptyList()
        }

        // --depth: stop recursing at max depth
        if (options.maxDepth >= 0 && depth > options.maxDepth) {
            return emptyList()
        }

        if (node.invisible == true || shouldIgnoreNode(node)) {
            return emptyList()
        }
        if (isTextNode(node)) {
            return AriaSnapshotFormatting.normalizeText(node.nodeValue)
                ?.let { listOf(AriaSnapshotFormatting.RenderChild.Text(it)) }
                ?: emptyList()
        }

        val attrs = stringAttributes(node)
        val accessibleName = accessibleName(node, attrs)
        val children = node.children.orEmpty()
            .flatMap { child -> toRenderChildren(child, options, depth + 1, counter) }
            .let { AriaSnapshotFormatting.normalizeChildren(it, accessibleName) }

        val role = role(node, attrs) ?: return children
        val props = renderProps(attrs, role, accessibleName, options)

        // --interactive: skip non-interactive nodes, promote their children
        if (options.interactive && !isInteractiveNode(node, role, props)) {
            return children
        }

        // --compact: skip generic/group/paragraph nodes that carry no semantic info
        if (options.compact && shouldCompact(node, role, accessibleName, props, children)) {
            return children
        }

        if (children.isEmpty() && props.isEmpty() && node.ref <= 0 && accessibleName.isNullOrEmpty()) {
            return emptyList()
        }

        // --max-nodes: count this node before rendering
        if (counter != null && !counter.increment()) {
            return emptyList()
        }

        // Collapse single-child generic wrappers that carry no identifying information
        if (shouldCollapseGenericNode(role, accessibleName, props, children, node.ref)) {
            return children
        }

        val box = if (options.boxes) {
            node.bounds?.let { b ->
                "${b.x?.toInt() ?: 0},${b.y?.toInt() ?: 0},${b.width?.toInt() ?: 0},${b.height?.toInt() ?: 0}"
            }
        } else null

        return listOf(
            AriaSnapshotFormatting.RenderChild.Node(
                AriaSnapshotFormatting.RenderNode(
                    role = role,
                    name = accessibleName,
                    checked = AriaSnapshotFormatting.triState(
                        attrs["checked"] ?: attrs["aria-checked"]
                    ),
                    disabled = AriaSnapshotFormatting.booleanAttribute(
                        attrs["disabled"] ?: attrs["aria-disabled"]
                    ),
                    expanded = AriaSnapshotFormatting.booleanAttribute(
                        attrs["expanded"] ?: attrs["aria-expanded"]
                    ),
                    level = level(attrs),
                    pressed = AriaSnapshotFormatting.triState(
                        attrs["pressed"] ?: attrs["aria-pressed"]
                    ),
                    selected = AriaSnapshotFormatting.booleanAttribute(
                        attrs["selected"] ?: attrs["aria-selected"]
                    ),
                    ref = node.ref.takeIf { it > 0 }?.let { "e$it" },
                    cursorPointer = node.interactive == true,
                    props = props,
                    children = children,
                    box = box
                )
            )
        )
    }

    private fun renderProps(
        attrs: Map<String, String>,
        role: String,
        accessibleName: String?,
        options: AriaSnapshotOptions
    ): LinkedHashMap<String, String> {
        val props = linkedMapOf<String, String>()
        if (role == "link") {
            attrs["href"]?.takeIf { it.isNotBlank() }?.let { props["url"] = it }
        }
        if (role == "textbox") {
            val placeholder = attrs["placeholder"] ?: attrs["aria-placeholder"]
            if (!placeholder.isNullOrBlank() && placeholder != accessibleName) {
                props["placeholder"] = placeholder
            }
        }
        return props
    }

    private fun isInteractiveNode(
        node: NanoDOMTreeNode,
        role: String,
        props: LinkedHashMap<String, String>
    ): Boolean {
        if (node.ref > 0) return true
        if (node.interactive == true) return true
        return role in INTERACTIVE_ROLES
    }

    private fun shouldCompact(
        node: NanoDOMTreeNode,
        role: String,
        accessibleName: String?,
        props: LinkedHashMap<String, String>,
        children: List<AriaSnapshotFormatting.RenderChild>
    ): Boolean {
        if (role != "generic" && role != "group" && role != "paragraph" && role != "section") {
            return false
        }
        if (!accessibleName.isNullOrEmpty()) return false
        if (node.ref > 0) return false
        if (props.isNotEmpty()) return false
        // Node carries no identifying information — collapse it
        return true
    }

    private fun shouldCollapseGenericNode(
        role: String,
        accessibleName: String?,
        props: Map<String, String>,
        children: List<AriaSnapshotFormatting.RenderChild>,
        ref: Int
    ): Boolean {
        return role == "generic" &&
                accessibleName.isNullOrEmpty() &&
                props.isEmpty() &&
                ref <= 0 &&
                children.size == 1 &&
                children.first() is AriaSnapshotFormatting.RenderChild.Node
    }

    private fun shouldIgnoreNode(node: NanoDOMTreeNode): Boolean {
        val nodeName = node.nodeName?.trim()?.lowercase(Locale.ROOT) ?: return false
        return nodeName == "script" || nodeName == "style"
                || nodeName == "#comment" || nodeName == "comment"
    }

    private fun accessibleName(node: NanoDOMTreeNode, attrs: Map<String, String>): String? {
        val role = role(node, attrs)
        val candidates = listOfNotNull(
            attrs["ax_name"],
            attrs["aria-label"],
            attrs["title"],
            if (role.equals("generic", ignoreCase = true)) attrs["ax_description"] else null,
            if (role == "img") attrs["alt"] else null,
            directTextContent(node)
        )
        return candidates.firstNotNullOfOrNull(AriaSnapshotFormatting::normalizeText)
    }

    /**
     * Collect text from direct text-node children only (not recursive), analogous to
     * how [AriaSnapshotRenderer] uses [ai.platon.browser4.chrome.dom.util.DOMUtils.textContent]
     * for accessible name computation. Non-recursive so parent elements don't inherit
     * text from deep descendants as their accessible name.
     */
    private fun directTextContent(node: NanoDOMTreeNode): String? {
        val tokens = node.children.orEmpty()
            .filter { c ->
                val name = c.nodeName?.lowercase()
                name == "#text" || name == "text"
            }
            .mapNotNull { c -> c.nodeValue?.trim()?.takeIf { it.isNotEmpty() } }
        return tokens.joinToString(" ").takeIf { it.isNotBlank() }?.trim()
    }

    private fun level(attributes: Map<String, String>): String? {
        val raw = attributes["level"] ?: attributes["aria-level"]
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun role(node: NanoDOMTreeNode, attrs: Map<String, String>): String? {
        val explicitRole = attrs["role"]?.trim()
        if (!explicitRole.isNullOrEmpty()) {
            return when {
                explicitRole.equals("none", ignoreCase = true) ||
                        explicitRole.equals("presentation", ignoreCase = true) -> null
                else -> explicitRole
            }
        }

        val role = implicitRole(node, attrs)
        return when {
            role.isNullOrEmpty() -> if (isTextNode(node)) null else "generic"
            else -> role
        }
    }

    private fun implicitRole(node: NanoDOMTreeNode, attributes: Map<String, String>): String? {
        val nodeName = node.nodeName?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when (nodeName) {
            "a" -> attributes["href"]?.takeIf { it.isNotBlank() }?.let { "link" }
            "button" -> "button"
            "img" -> "img"
            "option" -> "option"
            "select" -> if (
                attributes["multiple"]?.equals("true", ignoreCase = true) == true ||
                attributes["size"]?.toIntOrNull()?.let { it > 1 } == true
            ) {
                "listbox"
            } else {
                "combobox"
            }
            "summary" -> "button"
            "textarea" -> "textbox"
            "input" -> when (attributes["type"]?.trim()?.lowercase(Locale.ROOT)) {
                null, "", "email", "password", "tel", "text", "url" -> "textbox"
                "button", "image", "reset", "submit" -> "button"
                "checkbox" -> "checkbox"
                "number" -> "spinbutton"
                "radio" -> "radio"
                "range" -> "slider"
                "search" -> "searchbox"
                else -> null
            }
            else -> null
        }
    }

    private fun isTextNode(node: NanoDOMTreeNode): Boolean {
        val nodeName = node.nodeName?.trim()?.lowercase(Locale.ROOT)
        return nodeName == "#text" || nodeName == "text"
    }

    private fun stringAttributes(node: NanoDOMTreeNode): Map<String, String> {
        return node.attributes.orEmpty().mapValues { (_, value) -> value.toString() }
    }

    private val INTERACTIVE_ROLES = setOf(
        "button", "link", "textbox", "checkbox", "combobox", "searchbox",
        "spinbutton", "slider", "radio", "option", "listbox", "menuitem", "tab",
        "switch", "treeitem", "menuitemcheckbox", "menuitemradio"
    )
}
