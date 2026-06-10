package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.common.ExperimentalApi
import ai.platon.pulsar.dom.nodes.convertBox
import ai.platon.pulsar.ql.common.annotation.UDFGroup
import ai.platon.pulsar.ql.common.annotation.UDFunction
import ai.platon.pulsar.ql.common.types.ValueDom
import org.h2.value.ValueArray

/**
 * Created by Vincent on 18-02-06.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 */
@Suppress("unused")
@ExperimentalApi
@UDFGroup(namespace = "IN_BOX")
object BoxFunctions {

    @JvmStatic
    @UDFunction(description = "Select all elements inside the given CSS box, returning them as an array of DOMs")
    fun all(dom: ValueDom, box: String): ValueArray {
        return DomInlineSelectFunctions.inlineSelect(dom, convertBox(box))
    }

    @JvmStatic
    @UDFunction(description = "Select all elements inside the given CSS box with offset and limit, returning them as an array of DOMs")
    fun all(dom: ValueDom, box: String, offset: Int, limit: Int): ValueArray {
        return DomInlineSelectFunctions.inlineSelect(dom, convertBox(box), offset, limit)
    }

    @JvmStatic
    @UDFunction(description = "Select the first element inside the given CSS box and return it as a DOM")
    fun first(dom: ValueDom, box: String): ValueDom {
        return DomSelectFunctions.selectFirst(dom, convertBox(box))
    }

    @JvmStatic
    @UDFunction(description = "Select the nth element inside the given CSS box (1-based) and return it as a DOM")
    fun nth(dom: ValueDom, box: String, n: Int): ValueDom {
        return DomSelectFunctions.selectNth(dom, convertBox(box), n)
    }

    @JvmStatic
    @UDFunction(description = "Select the first element inside the given CSS box and return its text content")
    fun firstText(dom: ValueDom, box: String): String {
        return DomSelectFunctions.firstText(dom, convertBox(box))
    }

    @JvmStatic
    @UDFunction(description = "Select the nth element inside the given CSS box (1-based) and return its text content")
    fun nthText(dom: ValueDom, box: String, n: Int): String {
        return DomSelectFunctions.nthText(dom, convertBox(box), n)
    }

    @JvmStatic
    @UDFunction(description = "Select the first image inside the given CSS box and return its absolute src URL")
    fun firstImg(dom: ValueDom, box: String): String {
        return DomSelectFunctions.firstImg(dom, convertBox(box))
    }

    @JvmStatic
    @UDFunction(description = "Select the nth image inside the given CSS box (1-based) and return its absolute src URL")
    fun nthImg(dom: ValueDom, box: String, n: Int): String {
        return DomSelectFunctions.nthImg(dom, convertBox(box), n)
    }

    @JvmStatic
    @UDFunction(description = "Select the first anchor inside the given CSS box and return its absolute href URL")
    fun firstHref(dom: ValueDom, box: String): String {
        return DomSelectFunctions.firstHref(dom, convertBox(box))
    }

    @JvmStatic
    @UDFunction(description = "Select the nth anchor inside the given CSS box (1-based) and return its absolute href URL")
    fun nthHref(dom: ValueDom, box: String, n: Int): String {
        return DomSelectFunctions.nthHref(dom, convertBox(box), n)
    }

    @JvmStatic
    @UDFunction(description = "Select the first element inside the given CSS box and extract the first regex group from its text")
    fun firstRe1(dom: ValueDom, box: String, regex: String): String {
        return DomSelectFunctions.firstRe1(dom, convertBox(box), regex)
    }

    @JvmStatic
    @UDFunction(description = "Select the first element inside the given CSS box and extract the nth regex group from its text")
    fun firstRe1(dom: ValueDom, box: String, regex: String, group: Int): String {
        return DomSelectFunctions.firstRe1(dom, convertBox(box), regex, group)
    }

    @JvmStatic
    @UDFunction(description = "Select the first element inside the given CSS box and extract a key-value pair via regex (groups 1 and 2) from its text")
    fun firstRe2(dom: ValueDom, box: String, regex: String): ValueArray {
        return DomSelectFunctions.firstRe2(dom, convertBox(box), regex)
    }

    @JvmStatic
    @UDFunction(description = "Select the first element inside the given CSS box and extract a key-value pair via regex with custom group indices from its text")
    fun firstRe2(dom: ValueDom, box: String, regex: String, keyGroup: Int, valueGroup: Int): ValueArray {
        return DomSelectFunctions.firstRe2(dom, convertBox(box), regex, keyGroup, valueGroup)
    }
}
