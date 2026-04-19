package ai.platon.pulsar.agentic.tools.high.crawl.common

import ai.platon.pulsar.common.config.AppConstants.BROWSER_INTERACTIVE_ELEMENTS_SELECTOR
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.dom.nodes.node.ext.*
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor
import java.util.concurrent.atomic.AtomicReference

/**
 * Deprecated, use [ai.platon.browser4.driver.chrome.dom.SnapshotService] instead.
 * */
object DomUtils {

    const val SCREEN_OFFSET = -0.2f
    const val LONG_TEXT_LENGTH = 500

    fun selectNthScreenText(screenNumber: Float, document: FeaturedDocument): String {
        val sb = StringBuilder()
        var lastText = ""

        NodeTraversor.filter(object : NodeFilter {
            override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                // Check if the node is within the specified screen number range
                if (node.screenNumber < screenNumber - SCREEN_OFFSET || node.screenNumber > screenNumber + 1 + SCREEN_OFFSET) {
                    return NodeFilter.FilterResult.CONTINUE
                }

                if (!node.isVisible) {
                    return NodeFilter.FilterResult.CONTINUE
                }

                if (node is TextNode) {
                    if (node.numChars > 0) {
                        val text = node.cleanText
                        if (text.isNotBlank() && text != lastText) {
                            sb.append(text)
                            lastText = text
                        }
                    }
                } else {
                    val nodeName = node.nodeName().lowercase()
                    if (nodeName == "a") {
                        if (!sb.endsWith(" ") && !sb.endsWith("\n")) {
                            sb.append(" ")
                        }
                    } else if (nodeName in BROWSER_INTERACTIVE_ELEMENTS_SELECTOR) {
                        if (!sb.endsWith("\n")) {
                            sb.append("\n")
                        }
                    }
                }

                return NodeFilter.FilterResult.CONTINUE
            }
        }, document.body)

        return sb.toString()
    }

    fun selectNthScreenRichText(screenNumber: Float, document: FeaturedDocument): String {
        val sb = StringBuilder()
        val lastText = AtomicReference<String>()

        NodeTraversor.filter(object : NodeFilter {
            override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                // Check if the node is within the specified screen number range
                if (node.screenNumber < screenNumber - SCREEN_OFFSET || node.screenNumber > screenNumber + 1 + SCREEN_OFFSET) {
                    return NodeFilter.FilterResult.CONTINUE
                }

                if (!node.isVisible) {
                    return NodeFilter.FilterResult.CONTINUE
                }

                if (node is TextNode || node.isImage || node.isAnchor) {
                    return accumRichText(node, sb, lastText)
                } else if (node.nodeName().lowercase() in BROWSER_INTERACTIVE_ELEMENTS_SELECTOR) {
                    if (!sb.endsWith("\n")) {
                        sb.appendLine()
                    }
                }

                return NodeFilter.FilterResult.CONTINUE
            }
        }, document.body)

        return sb.toString()
    }

    fun accumRichText(
        node: Node,
        sb: StringBuilder,
        lastText: AtomicReference<String>
    ): NodeFilter.FilterResult {
        if (node.isImage) {
            if (node.width > 200 && node.height > 200) {
                val imageUrl = node.attr("abs:src")
                if (URLUtils.isStandard(imageUrl)) {
                    sb.appendLine("<img src=\"$imageUrl\" />")
                }
            }
            return NodeFilter.FilterResult.SKIP_CHILDREN
        } else if (node.isAnchor) {
            val anchorText = node.cleanText
            val anchorUrl = node.attr("abs:href")
            if (anchorText.isNotEmpty() && URLUtils.isStandard(anchorUrl)) {
                sb.appendLine("<a href=\"$anchorUrl\">$anchorText</a>")
            }
            return NodeFilter.FilterResult.SKIP_CHILDREN
        } else if (node is TextNode && node.numChars > 0) {
            val text = node.cleanText
            if (text.isNotEmpty() && lastText.get() != text) {
                sb.append(text)
                lastText.set(text)
            }
            return NodeFilter.FilterResult.CONTINUE
        }
        return NodeFilter.FilterResult.CONTINUE
    }
}
