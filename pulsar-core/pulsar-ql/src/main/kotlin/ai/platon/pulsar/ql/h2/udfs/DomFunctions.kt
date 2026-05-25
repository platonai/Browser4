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
import ai.platon.pulsar.ql.context.SQLContexts
import ai.platon.pulsar.ql.h2.H2SessionFactory
import ai.platon.pulsar.ql.h2.domValue
import ai.platon.pulsar.ql.common.types.ValueDom
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
    @UDFunction
    @JvmStatic
    fun isNil(dom: ValueDom) = dom.isNil

    /**
     * Check if this is a not nil DOM
     */
    @UDFunction
    @JvmStatic
    fun isNotNil(dom: ValueDom) = dom.isNotNil

    /**
     * Get the value of the given attribute
     */
    @UDFunction
    @JvmStatic
    fun attr(dom: ValueDom, attrName: String) = dom.element.attr(attrName)

    /**
     * Get the value of the given attribute
     */
    @UDFunction
    @JvmStatic
    fun labels(dom: ValueDom) = dom.element.attr(A_LABELS)

    /**
     * Get the value of the given indicator
     */
    @UDFunction
    @JvmStatic
    fun feature(dom: ValueDom, featureName: String) = NodeFeature.getValue(featureName, dom.element)

    @UDFunction
    @JvmStatic
    fun hasAttr(dom: ValueDom, attrName: String) = dom.element.hasAttr(attrName)

    @UDFunction
    @JvmStatic
    fun style(dom: ValueDom, styleName: String) = dom.element.getStyle(styleName)

    @UDFunction
    @JvmStatic
    fun sequence(dom: ValueDom) = dom.element.sequence

    @UDFunction
    @JvmStatic
    fun depth(dom: ValueDom) = dom.element.depth

    @UDFunction
    @JvmStatic
    fun cssSelector(dom: ValueDom) = dom.element.cssSelector()

    @UDFunction
    @JvmStatic
    fun cssPath(dom: ValueDom) = dom.element.cssSelector()

    @UDFunction
    @JvmStatic
    fun siblingSize(dom: ValueDom) = dom.element.siblingNodes().size

    @UDFunction
    @JvmStatic
    fun siblingIndex(dom: ValueDom) = dom.element.siblingIndex()

    @UDFunction
    @JvmStatic
    fun elementSiblingSize(dom: ValueDom) = dom.element.siblingElements().size

    @UDFunction
    @JvmStatic
    fun elementSiblingIndex(dom: ValueDom) = dom.element.elementSiblingIndex()

    /**
     * The normalized uri, should be the same as WebPage.url, which is also the key in the database
     * */
    @UDFunction
    @JvmStatic
    fun uri(dom: ValueDom): String {
        return dom.element.ownerDocument.normalizedURI ?: ""
    }

    /**
     * uri = WebPage.url which is the permanent internal address, it might not still available to access the target.
     * And location = WebPage.location or baseUri = WebPage.baseUrl is the last working address, it might redirect to url,
     * or it might have additional random parameters.
     * WebPage.location may be different from url, it's generally normalized.
     *
     * @return a {@link java.lang.String} object.
     */
    @UDFunction
    @JvmStatic
    fun baseUri(dom: ValueDom) = dom.element.baseUri()

    @UDFunction
    @JvmStatic
    fun absUrl(dom: ValueDom, attributeKey: String) = dom.element.absUrl(attributeKey)

    /**
     * WebPage.url is the permanent internal address, it might not still available to access the target,
     * while WebPage.location or WebPage.baseUrl is the last working address, it might redirect to url,
     * or it might have additional random parameters.
     * WebPage.location may be different from url, it's generally normalized.
     *
     * @return a {@link java.lang.String} object.
     */
    @UDFunction
    @JvmStatic
    fun location(dom: ValueDom) = dom.element.location

    @UDFunction
    @JvmStatic
    fun childNodeSize(dom: ValueDom) = dom.element.childNodeSize()

    @UDFunction
    @JvmStatic
    fun childElementSize(dom: ValueDom) = dom.element.children().size

    @UDFunction
    @JvmStatic
    fun tagName(dom: ValueDom) = dom.element.tagName()

    @UDFunction
    @JvmStatic
    fun href(dom: ValueDom) = dom.element.attr("href")

    @UDFunction
    @JvmStatic
    fun absHref(dom: ValueDom) = dom.element.absUrl("href")

    @UDFunction
    @JvmStatic
    fun src(dom: ValueDom) = dom.element.attr("src")

    @UDFunction
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

    @UDFunction
    @JvmStatic
    fun hasText(dom: ValueDom) = dom.element.hasText()

    @UDFunction
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

    @UDFunction
    @JvmStatic
    fun textLen(dom: ValueDom) = dom.element.text().length

    @UDFunction
    @JvmStatic
    fun textLength(dom: ValueDom) = dom.element.text().length

    @UDFunction
    @JvmStatic
    fun ownText(dom: ValueDom) = dom.element.ownText()

    @UDFunction
    @JvmStatic
    fun ownTexts(dom: ValueDom) = ValueArray.get(dom.element.ownTexts().map { ValueString.get(it) }.toTypedArray())

    @UDFunction
    @JvmStatic
    fun ownTextLen(dom: ValueDom) = dom.element.ownText().length

    @UDFunction
    @JvmStatic
    fun wholeText(dom: ValueDom) = dom.element.wholeText()

    @UDFunction
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

    @UDFunction
    @JvmStatic
    fun data(dom: ValueDom) = dom.element.data()

    @UDFunction
    @JvmStatic
    fun id(dom: ValueDom) = dom.element.id()

    @UDFunction
    @JvmStatic
    fun className(dom: ValueDom) = dom.element.className()

    @UDFunction
    @JvmStatic
    fun classNames(dom: ValueDom) = dom.element.classNames()

    @UDFunction
    @JvmStatic
    fun hasClass(dom: ValueDom, className: String) = dom.element.hasClass(className)

    @UDFunction
    @JvmStatic
    fun value(dom: ValueDom) = dom.element.`val`()

    @UDFunction
    @JvmStatic
    fun ownerDocument(dom: ValueDom): ValueDom {
        if (dom.isNil) return ValueDom.NIL
        val documentNode = dom.element.extension.ownerDocumentNode ?: return ValueDom.NIL
        return ValueDom.get(documentNode as Document)
    }

    @UDFunction
    @JvmStatic
    fun ownerBody(dom: ValueDom): ValueDom {
        if (dom.isNil) return ValueDom.NIL
        val ownerBody = dom.element.extension.ownerBody ?: return ValueDom.NIL
        return ValueDom.get(ownerBody as Element)
    }

    @UDFunction
    @JvmStatic
    fun documentVariables(dom: ValueDom): ValueDom {
        if (dom.isNil) return ValueDom.NIL
        val ownerBody = dom.element.extension.ownerBody ?: return ValueDom.NIL
        val meta = ownerBody.selectFirstOrNull(PULSAR_META_INFORMATION_SELECTOR) ?: return ValueDom.NIL
        return ValueDom.get(meta)
    }

    @UDFunction
    @JvmStatic
    fun parent(dom: ValueDom): ValueDom {
        if (dom.isNil) return ValueDom.NIL
        return ValueDom.get(dom.element.parent())
    }

    @UDFunction
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

    @UDFunction
    @JvmStatic
    fun parentName(dom: ValueDom): String {
        if (dom.isNil) return "nil"
        return parent(dom).element.uniqueName
    }

    @UDFunction
    @JvmStatic
    fun dom(dom: ValueDom) = dom

    @UDFunction
    @JvmStatic
    fun html(dom: ValueDom) = dom.element.slimCopy().html()

    @UDFunction
    @JvmStatic
    fun outerHtml(dom: ValueDom) = dom.element.slimCopy().outerHtml()

    @UDFunction
    @JvmStatic
    fun slimHtml(dom: ValueDom) = dom.element.slimHtml

    @UDFunction
    @JvmStatic
    fun minimalHtml(dom: ValueDom) = dom.element.minimalHtml

    @UDFunction
    @JvmStatic
    fun uniqueName(dom: ValueDom) = dom.element.uniqueName

    @UDFunction
    @JvmStatic
    fun links(dom: ValueDom): ValueArray {
        val elements = dom.element.getElementsByTag("a")
        return toValueArray(elements)
    }

    @UDFunction
    @JvmStatic
    fun ch(dom: ValueDom) = getFeature(dom, CH)

    @UDFunction
    @JvmStatic
    fun tn(dom: ValueDom) = getFeature(dom, TN)

    @UDFunction
    @JvmStatic
    fun img(dom: ValueDom) = getFeature(dom, IMG)

    @UDFunction
    @JvmStatic
    fun a(dom: ValueDom) = getFeature(dom, A)

    @UDFunction
    @JvmStatic
    fun sib(dom: ValueDom) = getFeature(dom, SIB)

    @UDFunction
    @JvmStatic
    fun c(dom: ValueDom) = getFeature(dom, C)

    @UDFunction
    @JvmStatic
    fun dep(dom: ValueDom) = getFeature(dom, DEP)

    @UDFunction
    @JvmStatic
    fun seq(dom: ValueDom) = getFeature(dom, SEQ)

    @UDFunction
    @JvmStatic
    fun top(dom: ValueDom): Double {
        return getFeature(dom, TOP)
    }

    @UDFunction
    @JvmStatic
    fun left(dom: ValueDom): Double {
        return getFeature(dom, LEFT)
    }

    @UDFunction
    @JvmStatic
    fun width(dom: ValueDom): Double {
        return getFeature(dom, WIDTH).coerceAtLeast(1.0)
    }

    @UDFunction
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
