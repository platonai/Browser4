package ai.platon.browser4.chrome.dom.util

import ai.platon.pulsar.chrome.dom.model.DefaultIncludeAttributes
import ai.platon.pulsar.chrome.dom.model.MergedDOMTreeNode
import ai.platon.pulsar.chrome.dom.model.NodeType

object DOMUtils {

    fun textContent(node: MergedDOMTreeNode): String {
        val sb = StringBuilder()

        fun appendToken(s: String?) {
            val t = s?.trim()
            if (!t.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(t)
            }
        }

        when (node.nodeType) {
            NodeType.TEXT_NODE -> appendToken(node.nodeValue)
            else -> {
                // Prefer accessible name if present
                appendToken(node.axNode?.name)
                // Include meaningful attributes
                if (node.attributes.isNotEmpty()) {
                    DefaultIncludeAttributes.ATTRIBUTES.forEach { key ->
                        node.attributes[key]?.let { appendToken(it) }
                    }
                }
            }
        }

        // Recurse into descendants
        node.children.forEach { appendToken(it.textContent()) }

        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun slimHTML(node: MergedDOMTreeNode): String {
        val tagName = node.nodeName.lowercase()
        val sb = StringBuilder()

        sb.append("<").append(tagName)

        // Include meaningful attributes
        if (node.attributes.isNotEmpty()) {
            DefaultIncludeAttributes.ATTRIBUTES.forEach { key ->
                node.attributes[key]?.let {
                    sb.append(" ").append(key).append("=\"").append(it).append("\"")
                }
            }
        }

        sb.append(">")

        // Recurse into descendants
        node.children.forEach { sb.append(it.slimHTML()) }

        sb.append("</").append(tagName).append(">")

        return sb.toString()
    }
}
