/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.protocol.browser.emulator.util

import ai.platon.pulsar.common.HtmlIntegrity
import ai.platon.pulsar.common.HtmlUtils
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.persist.PageDatum
import java.util.concurrent.CopyOnWriteArrayList

interface HtmlIntegrityChecker {
    fun isRelevant(url: String): Boolean
    operator fun invoke(pageSource: String, pageDatum: PageDatum): HtmlIntegrity
}

abstract class AbstractHtmlIntegrityChecker: HtmlIntegrityChecker {
    override fun isRelevant(url: String): Boolean = true
    override operator fun invoke(pageSource: String, pageDatum: PageDatum): HtmlIntegrity = HtmlIntegrity.OK
}

open class DefaultHtmlIntegrityChecker(val conf: ImmutableConfig): AbstractHtmlIntegrityChecker() {
    private val tracer = getLogger(DefaultHtmlIntegrityChecker::class).takeIf { it.isTraceEnabled }

    override operator fun invoke(pageSource: String, pageDatum: PageDatum): HtmlIntegrity {
        return checkHtmlIntegrity(pageSource)
    }

    /**
     * Check if the html is integral before field extraction, a further html integrity checking can be
     * applied after field extraction.
     * */
    private fun checkHtmlIntegrity(pageSource: String): HtmlIntegrity {
        val length = pageSource.length.toLong()

        return when {
            length == 0L -> HtmlIntegrity.EMPTY_0B
            length == 39L -> HtmlIntegrity.EMPTY_39B
            HtmlUtils.isBlankBody(pageSource) -> HtmlIntegrity.BLANK_BODY
            else -> checkHtmlIntegrity0(pageSource)
        }
    }

    /**
     * Check the html integrity by looking for specific tags and flags.
     * TODO: do not depend on injected flags in DOM, do not modify the DOM.
     * */
    private fun checkHtmlIntegrity0(pageSource: String): HtmlIntegrity {
        val p0 = pageSource.indexOf("</head>")
        val p1 = pageSource.indexOf("<body", p0)
        if (p1 <= 0) return HtmlIntegrity.OTHER
        val p2 = pageSource.indexOf(">", p1)
        if (p2 < p1) return HtmlIntegrity.OTHER
        // no any link, it's broken
        val p3 = pageSource.indexOf("<a", p2)
        if (p3 < p2) return HtmlIntegrity.NO_ANCHOR

        val bodyTag = pageSource.substring(p1, p2)
        tracer?.trace("Body tag: $bodyTag")
        // The javascript set data-error flag to indicate if the vision information of all DOM nodes is calculated
        val r = bodyTag.contains("data-error=\"0\"")
        if (!r) {
            return HtmlIntegrity.NO_JS_OK_FLAG
        }

        return HtmlIntegrity.OK
    }
}

open class ChainedHtmlIntegrityChecker(val conf: ImmutableConfig): AbstractHtmlIntegrityChecker() {
    private val checkers = CopyOnWriteArrayList<HtmlIntegrityChecker>()

    override fun isRelevant(url: String): Boolean = checkers.any { it.isRelevant(url) }

    override fun invoke(pageSource: String, pageDatum: PageDatum): HtmlIntegrity {
        return checkers.asSequence()
            .filter { it.isRelevant(pageDatum.url) }
            .map { it.invoke(pageSource, pageDatum) }
            .firstOrNull { it != HtmlIntegrity.OK }
            ?: HtmlIntegrity.OK
    }

    fun addFirst(checker: HtmlIntegrityChecker) {
        checkers.add(0, checker)
    }

    fun addLast(checker: HtmlIntegrityChecker): ChainedHtmlIntegrityChecker {
        checkers.add(checker)
        return this
    }

    fun remove(checker: HtmlIntegrityChecker): ChainedHtmlIntegrityChecker {
        checkers.remove(checker)
        return this
    }
}
