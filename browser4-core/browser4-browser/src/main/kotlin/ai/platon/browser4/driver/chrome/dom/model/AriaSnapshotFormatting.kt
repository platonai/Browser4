package ai.platon.browser4.driver.chrome.dom.model

import java.util.Locale

internal object AriaSnapshotFormatting {
    private val yamlKeywords = setOf("null", "true", "false", "~")
    private val whitespaceRegex = Regex("\\s+")

    fun render(children: List<RenderChild>): String {
        val lines = mutableListOf<String>()
        children.forEach { child -> visitChild(child, "", lines, renderCursorPointer = true) }
        return lines.joinToString("\n")
    }

    fun normalizeChildren(children: List<RenderChild>, accessibleName: String?): List<RenderChild> {
        if (children.size == 1 && children.first() is RenderChild.Text) {
            val childText = (children.first() as RenderChild.Text).value
            if (accessibleName != null && childText == accessibleName) {
                return emptyList()
            }
        }
        return children
    }

    fun normalizeText(value: String?): String? {
        return value
            ?.replace(whitespaceRegex, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun booleanAttribute(raw: String?): Boolean {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            null, "", "false", "0", "off", "no" -> false
            else -> true
        }
    }

    fun triState(raw: String?): TriState? {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            null, "", "false", "0", "off", "no" -> null
            "mixed" -> TriState.MIXED
            else -> TriState.TRUE
        }
    }

    private fun visitChild(
        child: RenderChild,
        indent: String,
        lines: MutableList<String>,
        renderCursorPointer: Boolean
    ) {
        when (child) {
            is RenderChild.Text -> lines += "$indent- text: ${yamlEscapeValueIfNeeded(child.value)}"
            is RenderChild.Node -> visitNode(child.node, indent, lines, renderCursorPointer)
        }
    }

    private fun visitNode(
        node: RenderNode,
        indent: String,
        lines: MutableList<String>,
        renderCursorPointer: Boolean
    ) {
        val key = indent + "- " + yamlEscapeKeyIfNeeded(createKey(node, renderCursorPointer))
        val singleInlinedTextChild = node.singleInlinedTextChild()

        when {
            node.children.isEmpty() && node.props.isEmpty() -> {
                lines += key
            }

            singleInlinedTextChild != null -> {
                lines += "$key: ${yamlEscapeValueIfNeeded(singleInlinedTextChild)}"
            }

            else -> {
                lines += "$key:"
                node.props.forEach { (name, value) ->
                    lines += "$indent  - /$name: ${yamlEscapeValueIfNeeded(value)}"
                }
                val childIndent = "$indent  "
                val inCursorPointer = node.ref != null && renderCursorPointer && node.cursorPointer
                node.children.forEach { child ->
                    when (child) {
                        is RenderChild.Text -> lines += "$childIndent- text: ${yamlEscapeValueIfNeeded(child.value)}"
                        is RenderChild.Node -> visitNode(
                            child.node,
                            childIndent,
                            lines,
                            renderCursorPointer && !inCursorPointer
                        )
                    }
                }
            }
        }
    }

    private fun createKey(node: RenderNode, renderCursorPointer: Boolean): String {
        var key = node.role
        node.name?.let { name ->
            if (name.length <= 900) {
                key += " " + jsonString(name)
            }
        }
        when (node.checked) {
            TriState.MIXED -> key += " [checked=mixed]"
            TriState.TRUE -> key += " [checked]"
            null -> Unit
        }
        if (node.disabled) {
            key += " [disabled]"
        }
        if (node.expanded) {
            key += " [expanded]"
        }
        node.level?.let { key += " [level=$it]" }
        when (node.pressed) {
            TriState.MIXED -> key += " [pressed=mixed]"
            TriState.TRUE -> key += " [pressed]"
            null -> Unit
        }
        if (node.selected) {
            key += " [selected]"
        }
        node.ref?.let { ref ->
            key += " [ref=$ref]"
            if (renderCursorPointer && node.cursorPointer) {
                key += " [cursor=pointer]"
            }
        }
        return key
    }

    private fun yamlEscapeKeyIfNeeded(value: String): String {
        return if (requiresYamlQuoting(value, isKey = true)) jsonString(value) else value
    }

    private fun yamlEscapeValueIfNeeded(value: String): String {
        return if (requiresYamlQuoting(value, isKey = false)) jsonString(value) else value
    }

    private fun requiresYamlQuoting(value: String, isKey: Boolean): Boolean {
        if (value.isEmpty()) {
            return true
        }
        if (value.trim() != value || '\n' in value || '\r' in value || '\t' in value) {
            return true
        }
        if (value.lowercase(Locale.ROOT) in yamlKeywords) {
            return true
        }
        if (value.startsWith("#") || value.startsWith("- ") || value.startsWith("!")) {
            return true
        }
        if (value.contains(": ") || value.endsWith(":")) {
            return true
        }
        if (!isKey && (value.startsWith("{") || value.startsWith("[") || value.startsWith("&") || value.startsWith("*"))) {
            return true
        }
        return false
    }

    private fun jsonString(value: String): String {
        return buildString {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }
    }

    sealed interface RenderChild {
        data class Text(val value: String) : RenderChild
        data class Node(val node: RenderNode) : RenderChild
    }

    enum class TriState {
        TRUE,
        MIXED
    }

    data class RenderNode(
        val role: String,
        val name: String?,
        val checked: TriState?,
        val disabled: Boolean,
        val expanded: Boolean,
        val level: String?,
        val pressed: TriState?,
        val selected: Boolean,
        val ref: String?,
        val cursorPointer: Boolean,
        val props: LinkedHashMap<String, String>,
        val children: List<RenderChild>
    )

    private fun RenderNode.singleInlinedTextChild(): String? {
        if (props.isNotEmpty() || children.size != 1) {
            return null
        }
        val child = children.first()
        return if (child is RenderChild.Text) child.value else null
    }
}
