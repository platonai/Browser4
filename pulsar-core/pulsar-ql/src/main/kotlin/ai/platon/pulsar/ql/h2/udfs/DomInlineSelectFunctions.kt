package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.dom.select.select2
import ai.platon.pulsar.ql.common.annotation.UDFGroup
import ai.platon.pulsar.ql.common.annotation.UDFunction
import ai.platon.pulsar.ql.common.types.ValueDom
import ai.platon.pulsar.ql.h2.DomToH2Queries
import org.h2.value.ValueArray
import org.h2.value.ValueString

/**
 * Created by Vincent on 17-11-1.
 * Copyright @ 2013-2023 Platon AI. All rights reserved.
 */
@Suppress("unused")
@UDFGroup(namespace = "DOM")
object DomInlineSelectFunctions {

    @UDFunction(description = "Select all elements matching the CSS query and return them as an array of DOMs")
    @JvmStatic
    fun inlineSelect(dom: ValueDom, cssQuery: String): ValueArray {
        val elements = dom.element.select2(cssQuery)
        return DomToH2Queries.toValueArray(elements)
    }

    @UDFunction(description = "Select all elements matching the CSS query with offset and limit, returning them as an array of DOMs")
    @JvmStatic
    fun inlineSelect(dom: ValueDom, cssQuery: String, offset: Int, limit: Int): ValueArray {
        val elements = dom.element.select2(cssQuery, offset, limit)
        return DomToH2Queries.toValueArray(elements)
    }

    @UDFunction(description = "Select all elements matching the CSS query and return their text content as an array (default offset=1, limit=40)")
    @JvmStatic
    fun inlineSelectText(dom: ValueDom, cssQuery: String): ValueArray {
        return inlineSelectText(dom, cssQuery, 1, 40)
    }

    @UDFunction(description = "Select all elements matching the CSS query with offset and limit, returning their text content as an array")
    @JvmStatic
    fun inlineSelectText(dom: ValueDom, cssQuery: String, offset: Int, limit: Int): ValueArray {
        val texts = dom.element.select2(cssQuery, offset, limit).map { ValueString.get(it.text()) }.toTypedArray()
        return ValueArray.get(texts)
    }
}
