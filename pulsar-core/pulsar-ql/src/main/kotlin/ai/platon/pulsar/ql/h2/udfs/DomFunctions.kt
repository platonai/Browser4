package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.common.RegexExtractor
import ai.platon.pulsar.common.config.AppConstants.PULSAR_META_INFORMATION_SELECTOR
import ai.platon.pulsar.dom.features.NodeFeature
import ai.platon.pulsar.dom.features.defined.*
import ai.platon.pulsar.dom.nodes.A_LABELS
import ai.platon.pulsar.dom.nodes.node.ext.*
import ai.platon.pulsar.dom.select.selectFirstOrNull
import ai.platon.pulsar.ql.common.annotation.H2Context
import ai.platon.pulsar.ql.common.annotation.UDFGroup
import ai.platon.pulsar.ql.common.annotation.UDFunction
import ai.platon.pulsar.ql.common.types.ValueDom
import ai.platon.pulsar.ql.context.SQLContexts
import ai.platon.pulsar.ql.h2.H2SessionFactory
import ai.platon.pulsar.ql.h2.domValue
import kotlinx.coroutines.runBlocking
import org.h2.value.Value
import org.h2.value.ValueArray
import org.h2.value.ValueString
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.sql.Connection
import java.time.Duration

/**
 * Created by Vincent on 17-11-1.
 * Copyright @ 2013-2020 Platon AI. All rights reserved
 */
@Suppress("unused")
@UDFGroup(namespace = "DOM")
object DomFunctions {
    private val sqlContext get() = SQLContexts.create()

    @UDFunction(
        description = "Load the page specified by url from db, if absent or expired, " +
                "fetch it from the web, and then parse it into a document"
    )
    @JvmStatic
    fun load(@H2Context conn: Connection, configuredUrl: String): ValueDom {
        if (!sqlContext.isActive) return ValueDom.NIL

        val session = H2SessionFactory.getSession(conn)
        val page = runBlocking { session.load(configuredUrl) }
        return session.parseValueDom(page)
    }

    @UDFunction(description = "Fetch the page specified by url immediately, and then parse it into a document")
    @JvmStatic
    fun fetch(@H2Context conn: Connection, configuredUrl: String): ValueDom {
        if (!sqlContext.isActive) return ValueDom.NIL

        val h2session = H2SessionFactory.getH2Session(conn)
        val session = sqlContext.getSession(h2session.serialId)
        val normURL = session.normalize(configuredUrl).apply { options.expires = Duration.ZERO }

        val page = runBlocking { session.load(normURL) }
        return session.parseValueDom(page)
    }

    /**
     * Check if this is a nil DOM
     */
    @UDFunction(description = "Check if the DOM is nil (empty or invalid)")
    @JvmStatic
    fun isNil(dom: ValueDom) = dom.isNil

    /**
     * Check if this is a not nil DOM
     */
    @UDFunction(description = "Check if the DOM is not nil (valid and non-empty)")
    @JvmStatic
    fun isNotNil(dom: ValueDom) = dom.isNotNil

    /**
     * Get the value of the given attribute
     */
    @UDFunction(description = "Get the value of the given HTML attribute on the element")
    @JvmStatic
    fun attr(dom: ValueDom, attrName: String) = dom.element.attr(attrName)

    /**
     * Get the value of the A_LABELS attribute
     */
    @UDFunction(description = "Get the value of the A_LABELS attribute (node classification labels)")
    @JvmStatic
    fun labels(dom: ValueDom) = dom.element.attr(A_LABELS)

    /**
     * Get the value of the given computed feature
     */
    @UDFunction(description = "Get the computed feature value of the element by feature name")
    @JvmStatic
    fun feature(dom: ValueDom, featureName: String) = NodeFeature.getValue(featureName, dom.element)

    @UDFunction(description = "Check if the element has the given HTML attribute")
    @JvmStatic
    fun hasAttr(dom: ValueDom, attrName: String) = dom.element.hasAttr(attrName)

    @UDFunction(description = "Get the computed CSS style value of the element by style name")
    @JvmStatic
    fun style(dom: ValueDom, styleName: String) = dom.element.getStyle(styleName)

    @UDFunction(description = "Get the element's sequence number in the document")
    @JvmStatic
    fun sequence(dom: ValueDom) = dom.element.sequence

    @UDFunction(description = "Get the element's depth in the DOM tree")
    @JvmStatic
    fun depth(dom: ValueDom) = dom.element.depth

    @UDFunction(description = "Get the unique CSS selector path for the element")
    @JvmStatic
    fun cssSelector(dom: ValueDom) = dom.element.cssSelector()

    @UDFunction(description = "Alias for cssSelector — get the unique CSS selector path for the element")
    @JvmStatic
    fun cssPath(dom: ValueDom) = dom.element.cssSelector()

    @UDFunction(description = "Get the number of sibling nodes (including text nodes) of the element")
    @JvmStatic
    fun siblingSize(dom: ValueDom) = dom.element.siblingNodes().size

    @UDFunction(description = "Get the element's index among all sibling nodes")
    @JvmStatic
    fun siblingIndex(dom: ValueDom) = dom.element.siblingIndex()

    @UDFunction(description = "Get the number of sibling elements (Element nodes only) of the element")
    @JvmStatic
    fun elementSiblingSize(dom: ValueDom) = dom.element.siblingElements().size

    @UDFunction(description = "Get the element's index among all sibling elements")
    @JvmStatic
    fun elementSiblingIndex(dom: ValueDom) = dom.element.elementSiblingIndex()

    /**
     * The normalized URI — the same as WebPage.url, which is also the key in the database.
     * This is the permanent internal address; it may no longer be accessible directly.
     * */
    @UDFunction(description = "Get the page's normalized URI (the permanent internal address / database key)")
    @JvmStatic
    fun uri(dom: ValueDom): String {
        return dom.element.ownerDocument.normalizedURI ?: ""
    }

    /**
     * baseUri = WebPage.baseUrl is the last working address. It might have redirected from url
     * or it might have additional random parameters. Generally normalized.
     *
     * @return a {@link java.lang.String} object.
     */
    @UDFunction(description = "Get the element's base URI (the last working address of the page)")
    @JvmStatic
    fun baseUri(dom: ValueDom) = dom.element.baseUri()

    @UDFunction(description = "Resolve a relative URL attribute (e.g. 'href', 'src') to an absolute URL")
    @JvmStatic
    fun absUrl(dom: ValueDom, attributeKey: String) = dom.element.absUrl(attributeKey)

    /**
     * WebPage.location is the last working address. It might have redirected from url,
     * or it might have additional random parameters. Generally normalized.
     *
     * @return a {@link java.lang.String} object.
     */
    @UDFunction(description = "Get the page's location (the last working address, may differ from the URI)")
    @JvmStatic
    fun location(dom: ValueDom) = dom.element.location

    @UDFunction(description = "Get the number of child nodes (including text nodes) of the element")
    @JvmStatic
    fun childNodeSize(dom: ValueDom) = dom.element.childNodeSize()

    @UDFunction(description = "Get the number of child elements (Element nodes only) of the element")
    @JvmStatic
    fun childElementSize(dom: ValueDom) = dom.element.children().size

    @UDFunction(description = "Get the element's HTML tag name (e.g. 'DIV', 'A', 'SPAN')")
    @JvmStatic
    fun tagName(dom: ValueDom) = dom.element.tagName()

    @UDFunction(description = "Get the element's 'href' attribute value")
    @JvmStatic
    fun href(dom: ValueDom) = dom.element.attr("href")

    @UDFunction(description = "Get the absolute URL of the element's 'href' attribute")
    @JvmStatic
    fun absHref(dom: ValueDom) = dom.element.absUrl("href")

    @UDFunction(description = "Get the element's 'src' attribute value")
    @JvmStatic
    fun src(dom: ValueDom) = dom.element.attr("src")

    @UDFunction(description = "Get the absolute URL of the element's 'src' attribute")
    @JvmStatic
    fun absSrc(dom: ValueDom) = dom.element.absUrl("abs:src")

    @UDFunction(description = "Get the element title")
    @JvmStatic
    fun title(dom: ValueDom) = dom.element.attr("title")

    @UDFunction(description = "Get the document title")
    @JvmStatic
    fun docTitle(dom: ValueDom): String {
        val ele = dom.element
        if (ele is Document) {
            return ele.title()
        }

        return dom.element.ownerDocument()!!.title()
    }

    @UDFunction(description = "Check if the element has any text content")
    @JvmStatic
    fun hasText(dom: ValueDom) = dom.element.hasText()

    @UDFunction(description = "Get the element's full text content, optionally truncated to the given length")
    @JvmStatic
    @JvmOverloads
    fun text(dom: ValueDom, truncate: Int = Int.MAX_VALUE): String {
        val text = dom.element.text()
        return if (truncate > text.length) {
            text
        } else {
            text.substring(0, truncate)
        }
    }

    @UDFunction(description = "Get the length of the element's text content")
    @JvmStatic
    fun textLen(dom: ValueDom) = dom.element.text().length

    @UDFunction(description = "Get the length of the element's text content (alias for textLen)")
    @JvmStatic
    fun textLength(dom: ValueDom) = dom.element.text().length

    @UDFunction(description = "Get the element's own text (excluding text from child elements)")
    @JvmStatic
    fun ownText(dom: ValueDom) = dom.element.ownText()

    @UDFunction(description = "Get the own texts of the element and its children as a ValueArray")
    @JvmStatic
    fun ownTexts(dom: ValueDom) = ValueArray.get(dom.element.ownTexts().map { ValueString.get(it) }.toTypedArray())

    @UDFunction(description = "Get the length of the element's own text")
    @JvmStatic
    fun ownTextLen(dom: ValueDom) = dom.element.ownText().length

    @UDFunction(description = "Get the element's whole text (including text from child text nodes)")
    @JvmStatic
    fun wholeText(dom: ValueDom) = dom.element.wholeText()

    @UDFunction(description = "Get the length of the element's whole text")
    @JvmStatic
    fun wholeTextLen(dom: ValueDom) = dom.element.wholeText().length

    @UDFunction(description = "Extract the first group of the result of java.util.regex.matcher() over the node text")
    @JvmStatic
    fun re1(dom: ValueDom, regex: String): String {
        val text = text(dom)
        return RegexExtractor().re1(text, regex)
    }

    @UDFunction(description = "Extract the nth group of the result of java.util.regex.matcher() over the node text")
    @JvmStatic
    fun re1(dom: ValueDom, regex: String, group: Int): String {
        val text = text(dom)
        return ai.platon.pulsar.common.RegexExtractor().re1(text, regex, group)
    }

    @UDFunction(description = "Extract two groups of the result of java.util.regex.matcher() over the node text")
    @JvmStatic
    fun re2(dom: ValueDom, regex: String): ValueArray {
        val text = text(dom)
        val result = RegexExtractor().re2(text, regex)
        val array = arrayOf(ValueString.get(result.key), ValueString.get(result.value))
        return ValueArray.get(array)
    }

    @UDFunction(description = "Extract two groups(key and value) of the result of java.util.regex.matcher() over the node text")
    @JvmStatic
    fun re2(dom: ValueDom, regex: String, keyGroup: Int, valueGroup: Int): ValueArray {
        val text = text(dom)
        val result = RegexExtractor().re2(text, regex, keyGroup, valueGroup)
        val array = arrayOf(ValueString.get(result.key), ValueString.get(result.value))
        return ValueArray.get(array)
    }

    @UDFunction(description = "Get the element's combined data attributes")
    @JvmStatic
    fun data(dom: ValueDom) = dom.element.data()

    @UDFunction(description = "Get the element's 'id' attribute value")
    @JvmStatic
    fun id(dom: ValueDom) = dom.element.id()

    @UDFunction(description = "Get the element's 'class' attribute value")
    @JvmStatic
    fun className(dom: ValueDom) = dom.element.className()

    @UDFunction(description = "Get the element's class names as a set of strings")
    @JvmStatic
    fun classNames(dom: ValueDom) = dom.element.classNames()

    @UDFunction(description = "Check if the element has the given CSS class")
    @JvmStatic
    fun hasClass(dom: ValueDom, className: String) = dom.element.hasClass(className)

    @UDFunction(description = "Get the element's form value (e.g. the 'value' attribute of input elements)")
    @JvmStatic
    fun value(dom: ValueDom) = dom.element.`val`()

    @UDFunction(description = "Get the owner document of the element as a DOM")
    @JvmStatic
    fun ownerDocument(dom: ValueDom): ValueDom {
        if (dom.isNil) return ValueDom.NIL
        val documentNode = dom.element.extension.ownerDocumentNode ?: return ValueDom.NIL
        return ValueDom.get(documentNode as Document)
    }

    @UDFunction(description = "Get the owner body element of the element as a DOM")
    @JvmStatic
    fun ownerBody(dom: ValueDom): ValueDom {
        if (dom.isNil) return ValueDom.NIL
        val ownerBody = dom.element.extension.ownerBody ?: return ValueDom.NIL
        return ValueDom.get(ownerBody as Element)
    }

    @UDFunction(description = "Get the Pulsar meta-information element from the document head")
    @JvmStatic
    fun documentVariables(dom: ValueDom): ValueDom {
        if (dom.isNil) return ValueDom.NIL
        val ownerBody = dom.element.extension.ownerBody ?: return ValueDom.NIL
        val meta = ownerBody.selectFirstOrNull(PULSAR_META_INFORMATION_SELECTOR) ?: return ValueDom.NIL
        return ValueDom.get(meta)
    }

    @UDFunction(description = "Get the parent element of the element as a DOM")
    @JvmStatic
    fun parent(dom: ValueDom): ValueDom {
        if (dom.isNil) return ValueDom.NIL
        return ValueDom.get(dom.element.parent())
    }

    @UDFunction(description = "Get the nth ancestor element (0 = self, 1 = parent, 2 = grandparent, …) as a DOM")
    @JvmStatic
    fun ancestor(dom: ValueDom, n: Int): ValueDom {
        if (dom.isNil) return ValueDom.NIL

        var i = 0
        var p = dom.element.parent()
        while (p != null && i++ < n) {
            p = dom.element.parent()
        }

        return p?.let { domValue(it) } ?: ValueDom.NIL
    }

    @UDFunction(description = "Get the unique name of the parent element, or 'nil' if the DOM is nil")
    @JvmStatic
    fun parentName(dom: ValueDom): String {
        if (dom.isNil) return "nil"
        return parent(dom).element.uniqueName
    }

    @UDFunction(description = "Identity function — return the DOM as-is")
    @JvmStatic
    fun dom(dom: ValueDom) = dom

    @UDFunction(description = "Get the element's inner HTML (slim copy)")
    @JvmStatic
    fun html(dom: ValueDom) = dom.element.slimCopy().html()

    @UDFunction(description = "Get the element's outer HTML including the element itself (slim copy)")
    @JvmStatic
    fun outerHtml(dom: ValueDom) = dom.element.slimCopy().outerHtml()

    @UDFunction(description = "Get a slimmed-down version of the element's HTML")
    @JvmStatic
    fun slimHtml(dom: ValueDom) = dom.element.slimHtml

    @UDFunction(description = "Get a minimal version of the element's HTML")
    @JvmStatic
    fun minimalHtml(dom: ValueDom) = dom.element.minimalHtml

    @UDFunction(description = "Get the element's unique name identifier")
    @JvmStatic
    fun uniqueName(dom: ValueDom) = dom.element.uniqueName

    @UDFunction(description = "Get all <a> elements within the element as a ValueArray of DOMs")
    @JvmStatic
    fun links(dom: ValueDom): ValueArray {
        val elements = dom.element.getElementsByTag("a")
        return toValueArray(elements)
    }

    @UDFunction(description = "Get the character count feature (CH) — text length of the element")
    @JvmStatic
    fun ch(dom: ValueDom) = getFeature(dom, CH)

    @UDFunction(description = "Get the text node count feature (TN) of the element")
    @JvmStatic
    fun tn(dom: ValueDom) = getFeature(dom, TN)

    @UDFunction(description = "Get the image count feature (IMG) of the element")
    @JvmStatic
    fun img(dom: ValueDom) = getFeature(dom, IMG)

    @UDFunction(description = "Get the anchor count feature (A) of the element")
    @JvmStatic
    fun a(dom: ValueDom) = getFeature(dom, A)

    @UDFunction(description = "Get the sibling count feature (SIB) of the element")
    @JvmStatic
    fun sib(dom: ValueDom) = getFeature(dom, SIB)

    @UDFunction(description = "Get the child count feature (C) of the element")
    @JvmStatic
    fun c(dom: ValueDom) = getFeature(dom, C)

    @UDFunction(description = "Get the depth feature (DEP) — depth of the element in the DOM tree")
    @JvmStatic
    fun dep(dom: ValueDom) = getFeature(dom, DEP)

    @UDFunction(description = "Get the sequence feature (SEQ) — sequence number of the element")
    @JvmStatic
    fun seq(dom: ValueDom) = getFeature(dom, SEQ)

    @UDFunction(description = "Get the Y-coordinate of the element's bounding box")
    @JvmStatic
    fun top(dom: ValueDom): Double {
        return getFeature(dom, TOP)
    }

    @UDFunction(description = "Get the X-coordinate of the element's bounding box")
    @JvmStatic
    fun left(dom: ValueDom): Double {
        return getFeature(dom, LEFT)
    }

    @UDFunction(description = "Get the width of the element's bounding box (minimum 1.0)")
    @JvmStatic
    fun width(dom: ValueDom): Double {
        return getFeature(dom, WIDTH).coerceAtLeast(1.0)
    }

    @UDFunction(description = "Get the height of the element's bounding box (minimum 1.0)")
    @JvmStatic
    fun height(dom: ValueDom): Double {
        return getFeature(dom, HEIGHT).coerceAtLeast(1.0)
    }

    @UDFunction(description = "Get the area of the css box of a DOM, area = width * height")
    @JvmStatic
    fun area(dom: ValueDom): Double {
        return width(dom) * height(dom)
    }

    @UDFunction(description = "Get the aspect ratio of the DOM, aspect ratio = width / height")
    @JvmStatic
    fun aspectRatio(dom: ValueDom): Double {
        return width(dom) / height(dom)
    }

    private fun getFeature(dom: ValueDom, key: Int): Double {
        return dom.element.getFeature(key)
    }

    private fun toValueArray(elements: Elements): ValueArray {
        val values = arrayOf<Value>()
        for (i in 0 until elements.size) {
            values[i] = ValueDom.get(elements[i])
        }
        return ValueArray.get(values)
    }
}
