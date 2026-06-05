package ai.platon.browser4.chrome.dom.model

import ai.platon.pulsar.chrome.dom.model.MergedDOMTreeNode
import ai.platon.pulsar.chrome.dom.model.NodeType
import ai.platon.pulsar.chrome.dom.model.OptimizedDOMTreeNode
import java.util.*

object AriaSnapshotRenderer {
    fun render(root: OptimizedDOMTreeNode): String {
        return AriaSnapshotFormatting.render(toRenderChildren(root))
    }

    private fun toRenderChildren(node: OptimizedDOMTreeNode): List<AriaSnapshotFormatting.RenderChild> {
        val original = node.originalNode
        if (shouldIgnoreNode(original)) {
            return emptyList()
        }

        if (isTextNode(original)) {
            return AriaSnapshotFormatting.normalizeText(original.nodeValue)
                ?.let { listOf(AriaSnapshotFormatting.RenderChild.Text(it)) }
                ?: emptyList()
        }

        val accessibleName = accessibleName(node)
        val children = node.children
            .flatMap { child -> toRenderChildren(child) }
            .let { AriaSnapshotFormatting.normalizeChildren(it, accessibleName) }

        val role = role(node) ?: return children
        val props = renderProps(node, role, accessibleName)
        val ref = original.backendNodeId.takeIf { it != null && it > 0 }?.let { "e$it" }

        if (children.isEmpty() && props.isEmpty() && ref == null && accessibleName.isNullOrEmpty()) {
            return emptyList()
        }

        if (shouldCollapseGenericNode(role, accessibleName, props, children)) {
            return children
        }

        return listOf(
            AriaSnapshotFormatting.RenderChild.Node(
                AriaSnapshotFormatting.RenderNode(
                    role = role,
                    name = accessibleName,
                    checked = AriaSnapshotFormatting.triState(rawState(node, "checked", "aria-checked")),
                    disabled = AriaSnapshotFormatting.booleanAttribute(rawState(node, "disabled", "aria-disabled")),
                    expanded = AriaSnapshotFormatting.booleanAttribute(rawState(node, "expanded", "aria-expanded")),
                    level = level(node),
                    pressed = AriaSnapshotFormatting.triState(rawState(node, "pressed", "aria-pressed")),
                    selected = AriaSnapshotFormatting.booleanAttribute(rawState(node, "selected", "aria-selected")),
                    ref = ref,
                    cursorPointer = hasCursorPointer(node),
                    props = props,
                    children = children
                )
            )
        )
    }

    private fun renderProps(
        node: OptimizedDOMTreeNode,
        role: String,
        accessibleName: String?
    ): LinkedHashMap<String, String> {
        val attributes = node.originalNode.attributes
        val axProperties = axProperties(node)
        val props = linkedMapOf<String, String>()

        if (role == "link") {
            val url = attributes["href"]?.takeIf { it.isNotBlank() }
                ?: axProperties["url"]?.takeIf { it.isNotBlank() }
            if (url != null) {
                props["url"] = url
            }
        }

        if (role == "textbox") {
            val placeholder = attributes["placeholder"] ?: attributes["aria-placeholder"]
            if (!placeholder.isNullOrBlank() && placeholder != accessibleName) {
                props["placeholder"] = placeholder
            }
        }

        AriaSnapshotFormatting.normalizeText(node.originalNode.axNode?.description)
            ?.takeIf { it != accessibleName }
            ?.let { props["description"] = it }

        val consumedProperties = setOf("checked", "disabled", "expanded", "level", "pressed", "selected", "url")
        axProperties.forEach { (name, value) ->
            if (name !in consumedProperties && !shouldOmitSupplementalProp(name, value)) {
                props.putIfAbsent(name, value)
            }
        }

        return props
    }

    private fun accessibleName(node: OptimizedDOMTreeNode): String? {
        val original = node.originalNode
        val role = role(node)
        val candidates = listOfNotNull(
            original.axNode?.name,
            original.attributes["aria-label"],
            original.attributes["title"],
            if (role.equals("generic", ignoreCase = true)) original.axNode?.description else null,
            if (role == "img") original.attributes["alt"] else null
        )
        return candidates.firstNotNullOfOrNull(AriaSnapshotFormatting::normalizeText)
    }

    private fun level(node: OptimizedDOMTreeNode): String? {
        return rawState(node, "level", "aria-level")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun role(node: OptimizedDOMTreeNode): String? {
        val role = node.originalNode.axNode?.role?.trim()
            ?: node.originalNode.attributes["role"]?.trim()
        return when {
            role.isNullOrEmpty() -> if (isTextNode(node.originalNode)) null else "generic"
            role.equals("none", ignoreCase = true) || role.equals("presentation", ignoreCase = true) -> null
            else -> role
        }
    }

    private fun rawState(node: OptimizedDOMTreeNode, propertyName: String, attributeName: String): String? {
        val attributes = node.originalNode.attributes
        return axProperties(node)[propertyName] ?: attributes[propertyName] ?: attributes[attributeName]
    }

    private fun axProperties(node: OptimizedDOMTreeNode): LinkedHashMap<String, String> {
        val properties = linkedMapOf<String, String>()
        node.originalNode.axNode?.properties.orEmpty().forEach { property ->
            val name = property.name.trim().lowercase(Locale.ROOT)
            val value = normalizePropertyValue(property.value) ?: return@forEach
            properties.putIfAbsent(name, value)
        }
        return properties
    }

    private fun normalizePropertyValue(value: Any?): String? {
        return when (value) {
            null -> null
            is Boolean -> value.toString().lowercase(Locale.ROOT)
            is String -> AriaSnapshotFormatting.normalizeText(value)
            else -> value.toString().trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun hasCursorPointer(node: OptimizedDOMTreeNode): Boolean {
        val snapshotNode = node.originalNode.snapshotNode
        val styleCursorPointer = node.originalNode.attributes["style"]
            ?.contains(Regex("""cursor\s*:\s*pointer""", RegexOption.IGNORE_CASE)) == true
        return if (snapshotNode != null) {
            snapshotNode.cursorStyle?.equals("pointer", ignoreCase = true) == true ||
                    snapshotNode.isClickable == true ||
                    styleCursorPointer
        } else {
            styleCursorPointer || node.originalNode.isInteractable == true || node.interactiveIndex != null
        }
    }

    private fun shouldCollapseGenericNode(
        role: String,
        accessibleName: String?,
        props: Map<String, String>,
        children: List<AriaSnapshotFormatting.RenderChild>
    ): Boolean {
        return role.equals("generic", ignoreCase = true) &&
                accessibleName.isNullOrEmpty() &&
                props.isEmpty() &&
                children.size == 1 &&
                children.first() is AriaSnapshotFormatting.RenderChild.Node
    }

    private fun shouldOmitSupplementalProp(name: String, value: String): Boolean {
        return when (name) {
            "focusable", "focused", "editable", "settable" -> true
            "invalid", "multiline", "readonly", "required" -> value.equals("false", ignoreCase = true)
            else -> false
        }
    }

    private fun shouldIgnoreNode(node: MergedDOMTreeNode): Boolean {
        val nodeName = node.nodeName.trim().lowercase(Locale.ROOT)
        return node.nodeType == NodeType.COMMENT_NODE ||
                nodeName == "#comment" ||
                nodeName == "comment" ||
                nodeName == "script" ||
                nodeName == "style"
    }

    private fun isTextNode(node: MergedDOMTreeNode): Boolean {
        val nodeName = node.nodeName.trim().lowercase(Locale.ROOT)
        return nodeName == "#text" || nodeName == "text"
    }
}
