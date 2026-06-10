package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.common.RegexExtractor
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.dom.nodes.A_LABELS
import ai.platon.pulsar.dom.nodes.node.ext.minimalHtml
import ai.platon.pulsar.dom.nodes.node.ext.slimHtml
import ai.platon.pulsar.dom.select.appendSelectorIfMissing
import ai.platon.pulsar.dom.select.select2
import ai.platon.pulsar.dom.select.selectFirstOrNull
import ai.platon.pulsar.ql.common.annotation.UDFGroup
import ai.platon.pulsar.ql.common.annotation.UDFunction
import ai.platon.pulsar.ql.common.types.ValueDom
import ai.platon.pulsar.ql.h2.DomToH2Queries
import org.h2.value.ValueArray
import org.h2.value.ValueFloat
import org.h2.value.ValueInt
import org.h2.value.ValueString
import org.jsoup.nodes.Element

/**
 * Created by Vincent on 17-11-1.
 * Copyright @ 2013-2023 Platon AI. All rights reserved.
 */
@Suppress("unused")
@UDFGroup(namespace = "DOM")
object DomSelectFunctions {

    @UDFunction(description = "Select the all the elements from a DOM by the given css query")
    @JvmStatic
    fun selectAll(dom: ValueDom, cssQuery: String): ValueArray {
        return DomToH2Queries.select(dom, cssQuery) { it }
    }

    @UDFunction(description = "Select the first element from a DOM by the given css query and return a DOM")
    @JvmStatic
    fun selectFirst(dom: ValueDom, cssQuery: String): ValueDom {
        return dom.takeIf { it.isNil }?:ValueDom.getOrNil(dom.element.selectFirstOrNull(cssQuery))
    }

    @UDFunction(description = "Select the nth element from a DOM by the given css query and return a DOM")
    @JvmStatic
    fun selectNth(dom: ValueDom, cssQuery: String, n: Int): ValueDom {
        return dom.takeIf { it.isNil }?:ValueDom.getOrNil(nthElement(dom.element, cssQuery, n))
    }

    @UDFunction(description = "Select all elements from a DOM by the given css query and return the element texts")
    @JvmStatic
    fun allTexts(dom: ValueDom, cssQuery: String): ValueArray {
        return DomToH2Queries.select(dom, cssQuery) { it.text() }
    }

    @UDFunction(description = "Select the first element from a DOM by the given css query and return the element text")
    @JvmStatic
    fun firstText(dom: ValueDom, cssQuery: String): String {
        return DomToH2Queries.selectFirstOrNull(dom, cssQuery) { it.text() } ?: ""
    }

    @UDFunction(description = "Select the nth element from a DOM by the given css query and return the element text")
    @JvmStatic
    fun nthText(dom: ValueDom, cssQuery: String, n: Int): String {
        return DomToH2Queries.selectNthOrNull(dom, cssQuery, n) { it.text() } ?: ""
    }

    @UDFunction(description = "Select all elements from a DOM by the given css query and return the element's own texts")
    @JvmStatic
    fun allOwnTexts(dom: ValueDom, cssQuery: String): ValueArray {
        return DomToH2Queries.select(dom, cssQuery) { it.ownText() }
    }

    @UDFunction(description = "Select the first element from a DOM by the given css query and return the element's own text")
    @JvmStatic
    fun firstOwnText(dom: ValueDom, cssQuery: String): String {
        return DomToH2Queries.selectFirstOrNull(dom, cssQuery) { it.ownText() } ?: ""
    }

    @UDFunction(description = "Select the nth element from a DOM by the given css query and return the element's own text")
    @JvmStatic
    fun nthOwnText(dom: ValueDom, cssQuery: String, n: Int): String {
        return DomToH2Queries.selectNthOrNull(dom, cssQuery, n) { it.ownText() } ?: ""
    }


    @UDFunction(description = "Select all elements from a DOM by the given css query and return the element whole texts")
    @JvmStatic
    fun wholeTexts(dom: ValueDom, cssQuery: String): ValueArray {
        return DomToH2Queries.select(dom, cssQuery) { it.wholeText() }
    }

    @UDFunction(description = "Select the first element from a DOM by the given css query and return the element whole text")
    @JvmStatic
    fun firstWholeText(dom: ValueDom, cssQuery: String): String {
        return DomToH2Queries.selectFirstOrNull(dom, cssQuery) { it.wholeText() } ?: ""
    }

    @UDFunction(description = "Select the nth element from a DOM by the given css query and return the element whole text")
    @JvmStatic
    fun nthWholeText(dom: ValueDom, cssQuery: String, n: Int): String {
        return DomToH2Queries.selectNthOrNull(dom, cssQuery, n) { it.wholeText() } ?: ""
    }







    @UDFunction(description = "Select all elements from a DOM by the given css query and return the element texts")
    @JvmStatic
    fun allSlimHtmls(dom: ValueDom, cssQuery: String): ValueArray {
        return DomToH2Queries.select(dom, cssQuery) { it.slimHtml }
    }

    @UDFunction(description = "Select the first element from a DOM by the given css query and return the element text")
    @JvmStatic
    fun firstSlimHtml(dom: ValueDom, cssQuery: String): String {
        return DomToH2Queries.selectFirstOrNull(dom, cssQuery) { it.slimHtml } ?: ""
    }

    @UDFunction(description = "Select the nth element from a DOM by the given css query and return the element text")
    @JvmStatic
    fun nthSlimHtml(dom: ValueDom, cssQuery: String, n: Int): String {
        return DomToH2Queries.selectNthOrNull(dom, cssQuery, n) { it.slimHtml } ?: ""
    }


    @UDFunction(description = "Select all elements from a DOM by the given css query and return the element texts")
    @JvmStatic
    fun allMinimalHtmls(dom: ValueDom, cssQuery: String): ValueArray {
        return DomToH2Queries.select(dom, cssQuery) { it.minimalHtml }
    }

    @UDFunction(description = "Select the first element from a DOM by the given css query and return the element text")
    @JvmStatic
    fun firstMinimalHtml(dom: ValueDom, cssQuery: String): String {
        return DomToH2Queries.selectFirstOrNull(dom, cssQuery) { it.minimalHtml } ?: ""
    }

    @UDFunction(description = "Select the nth element from a DOM by the given css query and return the element text")
    @JvmStatic
    fun nthMinimalHtml(dom: ValueDom, cssQuery: String, n: Int): String {
        return DomToH2Queries.selectNthOrNull(dom, cssQuery, n) { it.minimalHtml } ?: ""
    }


    @UDFunction(description = "Select all the element from a DOM by the given css query " +
            "and try to extract an integer from the element text, if the text is not an integer, fill the default value")
    @JvmStatic
    @JvmOverloads
    fun allIntegers(dom: ValueDom, cssQuery: String, defaultValue: Int = 0): ValueArray {
        return DomToH2Queries.select(dom, cssQuery) { ValueInt.get(Strings.getFirstInteger(it.text(), defaultValue)) }
    }

    @UDFunction(description = "Select the first element from a DOM by the given css query " +
            "and try to extract an integer from the element text")
    @JvmStatic
    fun firstInteger(dom: ValueDom, cssQuery: String, defaultValue: Int = 0): Int {
        val s = firstText(dom, cssQuery)
        return Strings.getFirstInteger(s, defaultValue)
    }

    @UDFunction(description = "Select the nth element from a DOM by the given css query " +
            "and try to extract an integer from the element text")
    @JvmStatic
    @JvmOverloads
    fun nthInteger(dom: ValueDom, cssQuery: String, n: Int, defaultValue: Int = 0): Int {
        val s = nthText(dom, cssQuery, n)
        return Strings.getFirstInteger(s, defaultValue)
    }

    @UDFunction(description = "Select all the element from a DOM by the given css query " +
            "and try to extract an number from the element text")
    @JvmStatic
    @JvmOverloads
    fun allFloats(dom: ValueDom, cssQuery: String, defaultValue: Float = 0.0f): ValueArray {
        return DomToH2Queries.select(dom, cssQuery) { ValueFloat.get(Strings.getFirstFloatNumber(it.text(), defaultValue)) }
    }

    /**
     * convert to ValueFloat manually,
     * TODO: kotlin.Float can not convert to ValueFloat automatically
     * */
    @UDFunction(description = "Select the first element from a DOM by the given css query " +
            "and try to extract an number from the element text")
    @JvmStatic
    fun firstFloat(dom: ValueDom, cssQuery: String, defaultValue: Float = 0.0f): ValueFloat {
        val s = firstText(dom, cssQuery)
        return Strings.getFirstFloatNumber(s, defaultValue).let { ValueFloat.get(it) }
    }

    @UDFunction(description = "Select the nth element from a DOM by the given css query " +
            "and try to extract an number from the element text")
    @JvmStatic
    @JvmOverloads
    fun nthFloat(dom: ValueDom, cssQuery: String, n: Int, defaultValue: Float = 0.0f): ValueFloat {
        val s = nthText(dom, cssQuery, n)
        return Strings.getFirstFloatNumber(s, defaultValue).let { ValueFloat.get(it) }
    }

    @UDFunction(description = "Select all the element from a DOM by the given css query " +
            "and return the attribute value associated by the attribute name")
    @JvmStatic
    @JvmOverloads
    fun allAttrs(dom: ValueDom, cssQuery: String = ":root", attrName: String): ValueArray {
        return DomToH2Queries.select(dom, cssQuery) { it.attr(attrName) }
    }

    @UDFunction(description = "Select the first element from a DOM by the given css query " +
            "and return the attribute value associated by the attribute name")
    @JvmStatic
    @JvmOverloads
    fun firstAttr(dom: ValueDom, cssQuery: String = ":root", attrName: String): String {
        return DomToH2Queries.selectFirstOrNull(dom, cssQuery) { it.attr(attrName) } ?: ""
    }

    @UDFunction(description = "Select the nth element from a DOM by the given css query " +
            "and return the attribute value associated by the attribute name")
    @JvmStatic
    fun nthAttr(dom: ValueDom, cssQuery: String, n: Int, attrName: String): String {
        return DomToH2Queries.selectNthOrNull(dom, cssQuery, n) { it.attr(attrName) } ?: ""
    }



    @UDFunction(description = "Select all the element from a DOM by the given css query " +
            "and return the attribute value associated by the attribute name")
    @JvmStatic
    @JvmOverloads
    fun allMultiAttrs(dom: ValueDom, cssQuery: String = ":root", attrNames: Array<String>): ValueArray {
        return DomToH2Queries.select(dom, cssQuery) { ele -> attrNames.map { ele.attr(it) } }
    }

    @UDFunction(description = "Select the first element from a DOM by the given css query " +
            "and return the attribute value associated by the attribute name")
    @JvmStatic
    @JvmOverloads
    fun firstMultiAttrs(dom: ValueDom, cssQuery: String = ":root", attrNames: Array<String>): List<String> {
        val result = DomToH2Queries.selectFirstOrNull(dom, cssQuery) { ele ->
            attrNames.map { ele.attr(it) }
        } ?: listOf()

        return result
    }

    @UDFunction(description = "Select the nth element from a DOM by the given css query " +
            "and return the attribute value associated by the attribute name")
    @JvmStatic
    fun nthMultiAttrs(dom: ValueDom, cssQuery: String, n: Int, attrNames: Array<String>): List<String> {
        val result = DomToH2Queries.selectNthOrNull(dom, cssQuery, n) { ele ->
            attrNames.map { ele.attr(it) }
        } ?: listOf()

        return result
    }


    @UDFunction(description = "Select all the image elements from a DOM by the given css query " +
            "and return the src of the image element")
    @JvmStatic
    @JvmOverloads
    fun allImgs(dom: ValueDom, cssQuery: String = ":root"): ValueArray {
        val q = appendSelectorIfMissing(cssQuery, "img")
        return DomToH2Queries.select(dom, q) { it.attr("abs:src") }
    }

    @UDFunction(description = "Select the first image element from a DOM by the given css query " +
            "and return the src of it")
    @JvmStatic
    @JvmOverloads
    fun firstImg(dom: ValueDom, cssQuery: String = ":root"): String {
        val q = appendSelectorIfMissing(cssQuery, "img")
        return DomToH2Queries.selectFirstOrNull(dom, q) { it.attr("abs:src") } ?: ""
    }

    @UDFunction(description = "Select the nth image element from a DOM by the given css query " +
            "and return the src of it")
    @JvmStatic
    fun nthImg(dom: ValueDom, cssQuery: String, n: Int): String {
        val q = appendSelectorIfMissing(cssQuery, "img")
        return DomToH2Queries.selectNthOrNull(dom, q, n) { it.attr("abs:src") } ?: ""
    }

    @UDFunction(description = "Select all anchor elements matching the CSS query and return their absolute href URLs as an array. Automatically appends 'a' to the CSS query.")
    @JvmStatic
    @JvmOverloads
    fun allHrefs(dom: ValueDom, cssQuery: String = ":root"): ValueArray {
        val q = appendSelectorIfMissing(cssQuery, "a")
        return DomToH2Queries.select(dom, q) { it.attr("abs:href") }
    }

    @UDFunction(description = "Select the first anchor element from a DOM by the given css query " +
            "and return the href of it")
    @JvmStatic
    @JvmOverloads
    fun firstHref(dom: ValueDom, cssQuery: String = ":root"): String {
        val q = appendSelectorIfMissing(cssQuery, "a")
        return DomToH2Queries.selectFirstOrNull(dom, q) { it.attr("abs:href") } ?: ""
    }

    @UDFunction(description = "Select the nth anchor element from a DOM by the given css query " +
            "and return the href of it")
    @JvmStatic
    fun nthHref(dom: ValueDom, cssQuery: String, n: Int): String {
        val q = appendSelectorIfMissing(cssQuery, "a")
        return DomToH2Queries.selectNthOrNull(dom, q, n) { it.attr("abs:href") } ?: ""
    }

    @UDFunction(description = "Select the first element from a DOM by the given css query " +
            "and return the label of it")
    @JvmStatic
    @JvmOverloads
    fun allNodesLabels(dom: ValueDom, cssQuery: String = ":root"): ValueArray {
        return allAttrs(dom, cssQuery, A_LABELS)
    }

    @UDFunction(description = "Select the first element matching the CSS query and return its A_LABELS attribute value")
    @JvmStatic
    @JvmOverloads
    fun firstNodeLabels(dom: ValueDom, cssQuery: String = ":root"): String {
        return firstAttr(dom, cssQuery, A_LABELS)
    }

    @UDFunction(description = "Select the nth element from a DOM by the given css query " +
            "and return the label of it")
    @JvmStatic
    fun nthNodeLabels(dom: ValueDom, cssQuery: String, n: Int): String {
        return nthAttr(dom, cssQuery, n, A_LABELS)
    }

    @UDFunction(description = "Extract the first regex group from the text of all elements (scoped to :root) and return as an array")
    @JvmStatic
    fun allRe1(dom: ValueDom, regex: String): ValueArray {
        return allRe1(dom, ":root", regex)
    }

    @UDFunction(description = "Select all elements matching the CSS query and extract the first regex group from each element's text, returning the results as an array")
    @JvmStatic
    fun allRe1(dom: ValueDom, cssQuery: String, regex: String): ValueArray {
        val extractor = RegexExtractor()
        return DomToH2Queries.select(dom, cssQuery) { extractor.re1(it.text(), regex) }
    }

    @UDFunction(description = "Select the first element from a DOM whose text matches the regex " +
            "and return the element text")
    @JvmStatic
    fun firstRe1(dom: ValueDom, regex: String): String {
        return firstRe1(dom, ":root", regex)
    }

    @UDFunction(description = "Select the first element from a DOM whose text matches the regex " +
            "and return the element text")
    @JvmStatic
    fun firstRe1(dom: ValueDom, cssQuery: String, regex: String): String {
        val text = text(selectFirst(dom, cssQuery))
        return RegexExtractor().re1(text, regex)
    }

    @UDFunction(description = "Select the first element from a DOM whose text matches the regex " +
            "and return the element text")
    @JvmStatic
    fun firstRe1(dom: ValueDom, cssQuery: String, regex: String, group: Int): String {
        val text = text(selectFirst(dom, cssQuery))
        return RegexExtractor().re1(text, regex, group)
    }

    @UDFunction(description = "Extract key-value pairs via regex from the text of all elements (scoped to :root) and return as an array")
    @JvmStatic
    fun allRe2(dom: ValueDom, regex: String): ValueArray {
        return allRe2(dom, ":root", regex)
    }

    @UDFunction(description = "Select all elements matching the CSS query and extract key-value pairs via regex (groups 1 and 2) from each element's text, returning the results as an array")
    @JvmStatic
    fun allRe2(dom: ValueDom, cssQuery: String, regex: String): ValueArray {
        val extractor = RegexExtractor()
        return DomToH2Queries.select(dom, cssQuery) { extractor.re2(it.text(), regex).toString() }
    }

    @UDFunction(description = "Select the first element matching the CSS query and extract a key-value pair via regex (groups 1 and 2) from its text, returning [key, value] as a ValueArray")
    @JvmStatic
    fun firstRe2(dom: ValueDom, cssQuery: String, regex: String): ValueArray {
        val text = text(selectFirst(dom, cssQuery))
        val result = RegexExtractor().re2(text, regex)
        val array = arrayOf(ValueString.get(result.key), ValueString.get(result.value))
        return ValueArray.get(array)
    }

    @UDFunction(description = "Select the first element matching the CSS query and extract a key-value pair via regex with custom group indices from its text, returning [key, value] as a ValueArray")
    @JvmStatic
    fun firstRe2(dom: ValueDom, cssQuery: String, regex: String, keyGroup: Int, valueGroup: Int): ValueArray {
        val text = text(selectFirst(dom, cssQuery))
        val result = RegexExtractor().re2(text, regex, keyGroup, valueGroup)
        val array = arrayOf(ValueString.get(result.key), ValueString.get(result.value))
        return ValueArray.get(array)
    }

    @UDFunction(description = "Select all elements matching the CSS query and extract key-value pairs via regex with custom group indices from each element's text, returning the results as an array")
    @JvmStatic
    fun allRe2(dom: ValueDom, cssQuery: String, regex: String, keyGroup: Int, valueGroup: Int): ValueArray {
        val extractor = RegexExtractor()
        return DomToH2Queries.select(dom, cssQuery) { extractor.re2(it.text(), regex, keyGroup, valueGroup).toString() }
    }

    private fun text(dom: ValueDom): String {
        return dom.element.text()
    }

    private fun nthElement(root: Element, cssQuery: String, n: Int): Element? {
        if (n < 1) throw IndexOutOfBoundsException("n should be in [1, )")
        val elements = root.select2(cssQuery)
        return if (elements.size >= n) { elements[n - 1] } else null
    }
}
