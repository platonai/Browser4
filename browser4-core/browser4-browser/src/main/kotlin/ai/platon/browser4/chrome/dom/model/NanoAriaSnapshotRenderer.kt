package ai.platon.browser4.chrome.dom.model

import ai.platon.pulsar.chrome.dom.model.NanoDOMTreeNode
import java.util.*

object NanoAriaSnapshotRenderer {
    fun render(root: NanoDOMTreeNode): String {
        return AriaSnapshotFormatting.render(toRenderChildren(root))
    }

    private fun toRenderChildren(node: NanoDOMTreeNode): List<AriaSnapshotFormatting.RenderChild> {
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
            .flatMap { child -> toRenderChildren(child) }
            .let { AriaSnapshotFormatting.normalizeChildren(it, accessibleName(node)) }

        val role = role(node) ?: return children
        val props = renderProps(node, role)

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

    private fun renderProps(node: NanoDOMTreeNode, role: String): LinkedHashMap<String, String> {
        val attributes = stringAttributes(node)
        val props = linkedMapOf<String, String>()
        if (role == "link") {
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
}
