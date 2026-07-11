package ai.platon.browser4.api.model

import ai.platon.browser4.api.model.MergedDOMTreeNode
import ai.platon.browser4.api.model.NodeType
import java.util.*

object CSSSelectorUtils {

    /**
     * Build a best-effort CSS selector for this node.
     * Strategy:
     * - If an id exists, prefer #id (or tag[id="..."] if id is not a valid identifier)
     * - Else, use up to a few stable classes: tag.class1.class2
     * - Else, fall back to stable attributes like data-*, aria-label, name, type, role
     * - Else, return the lowercase tag name (or "*")
     */
    fun generateCSSSelector(node: MergedDOMTreeNode): String {
        // Only meaningful for elements
        if (node.nodeType != NodeType.ELEMENT_NODE) {
            return node.nodeName.lowercase(Locale.ROOT).ifBlank { "*" }
        }

        val tag = node.nodeName.lowercase(Locale.ROOT).ifBlank { "*" }
        val id = node.attributes["id"]?.trim()?.takeIf { it.isNotEmpty() }

        fun isValidCssIdent(s: String): Boolean {
            // A permissive check: letters/underscore at start, then common safe chars
            return s.matches(Regex("[A-Za-z_][A-Za-z0-9_\\-:.]*"))
        }

        fun escAttrValue(v: String): String = v
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        // Prefer id when present
        if (id != null) {
            return if (isValidCssIdent(id)) "#$id" else "$tag[id=\"${escAttrValue(id)}\"]"
        }

        // Then prefer valuable classes
        val cls = node.attributes["class"]?.trim().orEmpty()
        if (cls.isNotEmpty()) {
            val rawClasses = cls.split("\\s+".toRegex())
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            fun isLikelyStable(c: String): Boolean {
                // Filter out ugly or hashed classes heuristically
                if (c.length >= 40) return false
                if (c.any { it in "./\\:\'\"" }) return false
                val letters = c.count { it.isLetter() }
                val digits = c.count { it.isDigit() }
                // Prefer those with letters and not purely digits; avoid overly random classes
                return letters >= 2
            }

            val stable = rawClasses.filter { isLikelyStable(it) }.take(3)
            if (stable.isNotEmpty()) {
                val classSel = stable.joinToString(separator = ".", prefix = ".") { it }
                return if (tag == "*") classSel else tag + classSel
            }
        }

        // Fallback to preferred attributes
        val preferredAttrs = listOf(
            "data-testid", "data-test", "data-cy", "data-selenium",
            "aria-label", "name", "type", "role", "title", "placeholder"
        )
        for (k in preferredAttrs) {
            val v = node.attributes[k]?.trim()?.takeIf { it.isNotEmpty() }
            if (v != null) {
                return "$tag[$k=\"${escAttrValue(v)}\"]"
            }
        }

        // Special-case input buttons with value
        if (tag == "input" || tag == "button") {
            val v = node.attributes["value"]?.trim()?.takeIf { it.isNotEmpty() }
            if (v != null) return "$tag[value=\"${escAttrValue(v)}\"]"
        }

        // Last resort: tag name
        return tag
    }
}
