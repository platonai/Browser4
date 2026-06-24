package ai.platon.browser4.chrome.dom.model

import ai.platon.pulsar.chrome.dom.model.NanoDOMTreeNode
import java.util.*

object NanoAriaSnapshotRenderer {
    fun render(root: NanoDOMTreeNode, options: AriaSnapshotOptions = AriaSnapshotOptions()): String {
        return AriaSnapshotFormatting.render(toRenderChildren(root, options))
    }

    private fun toRenderChildren(
        node: NanoDOMTreeNode,
        options: AriaSnapshotOptions,
        depth: Int = 0
    ): List<AriaSnapshotFormatting.RenderChild> {
        // --depth: stop recursing at max depth
        if (options.maxDepth >= 0 && depth > options.maxDepth) {
            return emptyList()
        }

        if (node.invisible == true) {
            return emptyList()
        }
        if (isTextNode(node)) {
            return AriaSnapshotFormatting.normalizeText(node.nodeValue)
                ?.takeIf { it.isNotEmpty() }
                ?.let { listOf(AriaSnapshotFormatting.RenderChild.Text(it)) }
                ?: emptyList()
        }

        val children = node.children.orEmpty()
            .flatMap { child -> toRenderChildren(child, options, depth + 1) }
            .let { AriaSnapshotFormatting.normalizeChildren(it, accessibleName(node)) }

        val role = role(node) ?: return children
        val props = renderProps(node, role, options)

        // --interactive: skip non-interactive nodes, promote their children
        if (options.interactive && !isInteractiveNode(node, role, props)) {
            return children
        }

        // --compact: skip generic/group/paragraph nodes that carry no semantic info
        if (options.compact && shouldCompact(node, role, props, children)) {
            return children
        }

        if (children.isEmpty() && props.isEmpty() && node.ref <= 0 && accessibleName(node).isNullOrEmpty()) {
            return emptyList()
        }

        return listOf(
            AriaSnapshotFormatting.RenderChild.Node(
                AriaSnapshotFormatting.RenderNode(
                    role = role,
                    name = accessibleName(node),
                    checked = AriaSnapshotFormatting.triState(
                        stringAttributes(node)["checked"] ?: stringAttributes(node)["aria-checked"]
                    ),
                    disabled = AriaSnapshotFormatting.booleanAttribute(
                        stringAttributes(node)["disabled"] ?: stringAttributes(node)["aria-disabled"]
                    ),
                    expanded = AriaSnapshotFormatting.booleanAttribute(
                        stringAttributes(node)["expanded"] ?: stringAttributes(node)["aria-expanded"]
                    ),
                    level = level(stringAttributes(node)),
                    pressed = AriaSnapshotFormatting.triState(
                        stringAttributes(node)["pressed"] ?: stringAttributes(node)["aria-pressed"]
                    ),
                    selected = AriaSnapshotFormatting.booleanAttribute(
                        stringAttributes(node)["selected"] ?: stringAttributes(node)["aria-selected"]
                    ),
                    ref = node.ref.takeIf { it > 0 }?.let { "e$it" },
                    cursorPointer = node.interactive == true,
                    props = props,
                    children = children
                )
            )
        )
    }

    private fun renderProps(
        node: NanoDOMTreeNode,
        role: String,
        options: AriaSnapshotOptions
    ): LinkedHashMap<String, String> {
        val attributes = stringAttributes(node)
        val props = linkedMapOf<String, String>()
        if (role == "link") {
            attributes["href"]?.takeIf { it.isNotBlank() }?.let { props["url"] = it }
        }
        // --urls: always include url for links even when the element would otherwise be collapsed
        if (options.urls && role == "link" && !props.containsKey("url")) {
            attributes["href"]?.takeIf { it.isNotBlank() }?.let { props["url"] = it }
        }
        if (role == "textbox") {
            val placeholder = attributes["placeholder"] ?: attributes["aria-placeholder"]
            if (!placeholder.isNullOrBlank() && placeholder != accessibleName(node)) {
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
        props: LinkedHashMap<String, String>,
        children: List<AriaSnapshotFormatting.RenderChild>
    ): Boolean {
        if (role != "generic" && role != "group" && role != "paragraph" && role != "section") {
            return false
        }
        val name = accessibleName(node)
        if (!name.isNullOrEmpty()) return false
        if (node.ref > 0) return false
        if (props.isNotEmpty()) return false
        // Node carries no identifying information — collapse it
        return true
    }

    private fun accessibleName(node: NanoDOMTreeNode): String? {
        val attributes = stringAttributes(node)
        val role = role(node)
        return AriaSnapshotFormatting.normalizeText(
            attributes["ax_name"]
                ?: attributes["aria-label"]
                ?: attributes["title"]
                ?: if (role == "img") attributes["alt"] else null
        )
    }

    private fun level(attributes: Map<String, String>): String? {
        val raw = attributes["level"] ?: attributes["aria-level"]
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun role(node: NanoDOMTreeNode): String? {
        val attributes = stringAttributes(node)
        val explicitRole = attributes["role"]?.trim()
        if (!explicitRole.isNullOrEmpty()) {
            return when {
                explicitRole.equals("none", ignoreCase = true) ||
                        explicitRole.equals("presentation", ignoreCase = true) -> null
                else -> explicitRole
            }
        }

        val role = implicitRole(node, attributes)
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
