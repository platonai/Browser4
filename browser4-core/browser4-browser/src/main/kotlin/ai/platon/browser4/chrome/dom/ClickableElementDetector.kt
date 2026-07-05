package ai.platon.browser4.chrome.dom

import ai.platon.browser4.api.model.MergedDOMTreeNode
import ai.platon.browser4.api.model.NodeType

class ClickableElementDetector {
    /**
     * Check if this node is clickable/interactive using enhanced scoring
     */
    fun isInteractive(node: MergedDOMTreeNode): Boolean {
        // Skip non-element nodes
        if (node.nodeType != NodeType.ELEMENT_NODE) return false

        val tag = node.nodeName.lowercase()

        // remove html and body nodes
        if (tag == "html" || tag == "body") return false

        // IFRAME/FRAME elements should be interactive if large enough
        if (tag == "iframe" || tag == "frame") {
            val bounds = node.snapshotNode?.bounds
            if (bounds != null) {
                if (bounds.width > 100 && bounds.height > 100) return true
            }
        }

        // SEARCH ELEMENT DETECTION: Check for search-related classes and attributes
        run {
            val searchIndicators = setOf(
                "search",
                "magnify",
                "glass",
                "lookup",
                "find",
                "query",
                "search-icon",
                "search-btn",
                "search-button",
                "searchbox",
            )

            val attrs = node.attributes
            if (attrs.isNotEmpty()) {
                // class attribute
                val classAttr = getAttrIgnoreCase(attrs, "class")?.lowercase() ?: ""
                val classJoined = classAttr
                if (searchIndicators.any { it in classJoined }) return true

                // id attribute
                val idAttr = getAttrIgnoreCase(attrs, "id")?.lowercase() ?: ""
                if (searchIndicators.any { it in idAttr }) return true

                // data-* attributes
                for ((k, v) in attrs) {
                    if (k.lowercase().startsWith("data-")) {
                        val vLower = v.lowercase()
                        if (searchIndicators.any { it in vLower }) return true
                    }
                }
            }
        }

        // Enhanced accessibility property checks - direct clear indicators only
        node.axNode?.properties?.let { props ->
            for (prop in props) {
                val name = prop.name.lowercase()
                val truthy = isTruthy(prop.value)

                // aria disabled / hidden
                if ((name == "disabled" || name == "hidden") && truthy) return false

                // Direct interactiveness indicators
                if ((name == "focusable" || name == "editable" || name == "settable") && truthy) return true

                // Interactive state properties (presence indicates interactive widget)
                if (name == "checked" || name == "expanded" || name == "pressed" || name == "selected") return true

                // Form-related interactiveness
                if ((name == "required" || name == "autocomplete") && truthy) return true

                // Elements with keyboard shortcuts are interactive
                if (name == "keyshortcuts" && truthy) return true
            }
        }

        // ENHANCED TAG CHECK: Include truly interactive elements
        // Note: 'label' removed as in Python version
        val interactiveTags = setOf(
            "button",
            "input",
            "select",
            "textarea",
            "a",
            "details",
            "summary",
            "option",
            "optgroup",
        )
        if (tag in interactiveTags) return true

        // Tertiary check: elements with interactive attributes
        val attrs = node.attributes
        if (attrs.isNotEmpty()) {
            // Event handler / interactive attributes (case-insensitive on keys)
            val interactiveAttributes = setOf("onclick", "onmousedown", "onmouseup", "onkeydown", "onkeyup", "tabindex")
            val attrKeysLower = attrs.keys.map { it.lowercase() }.toSet()
            if (interactiveAttributes.any { it in attrKeysLower }) return true

            // Interactive ARIA roles
            getAttrIgnoreCase(attrs, "role")?.lowercase()?.let { role ->
                val interactiveRoles = setOf(
                    "button",
                    "link",
                    "menuitem",
                    "option",
                    "radio",
                    "checkbox",
                    "tab",
                    "textbox",
                    "combobox",
                    "slider",
                    "spinbutton",
                    "search",
                    "searchbox",
                )
                if (role in interactiveRoles) return true
            }
        }

        // Quaternary check: accessibility tree roles
        node.axNode?.role?.lowercase()?.let { role ->
            val interactiveAxRoles = setOf(
                "button",
                "link",
                "menuitem",
                "option",
                "radio",
                "checkbox",
                "tab",
                "textbox",
                "combobox",
                "slider",
                "spinbutton",
                "listbox",
                "search",
                "searchbox",
            )
            if (role in interactiveAxRoles) return true
        }

        // ICON AND SMALL ELEMENT CHECK: Elements that might be icons
        node.snapshotNode?.bounds?.let { b ->
            val w = b.width
            val h = b.height
            if (w >= 10 && w <= 50 && h >= 10 && h <= 50) {
                val iconAttributes = setOf("class", "role", "onclick", "data-action", "aria-label")
                val keysLower = attrs.keys.map { it.lowercase() }.toSet()
                if (iconAttributes.any { it in keysLower }) return true
            }
        }

        // Final fallback: cursor style indicates interactivity (for cases Chrome missed)
        node.snapshotNode?.cursorStyle?.let { cursor ->
            if (cursor.equals("pointer", ignoreCase = true)) return true
        }

        return false
    }

    private fun getAttrIgnoreCase(attrs: Map<String, String>, name: String): String? {
        val nameLower = name.lowercase()
        for ((k, v) in attrs) {
            if (k.lowercase() == nameLower) return v
        }
        return null
    }

    private fun isTruthy(value: Any?): Boolean {
        return when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.isNotBlank()
            else -> true
        }
    }
}
