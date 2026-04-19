package ai.platon.pulsar.skeleton.crawl.common.urls

import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.common.urls.*
import ai.platon.pulsar.skeleton.common.urls.CombinedUrlNormalizer
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.crawl.common.url.ParsableHyperlink
import com.google.gson.GsonBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HyperlinkTests {
    @Test
    fun testEquals() {
        var u1: UrlAware = Hyperlink(UrlCommon.urlString1)
        var u2: UrlAware = Hyperlink(UrlCommon.urlString1, "hello", Int.MAX_VALUE)
        assertEquals(u1, u2)

        u1 = Hyperlink(UrlCommon.urlString1)
        u2 = Hyperlink(UrlCommon.urlString1, "", args = "-i 0s")
        assertEquals(u1, u2)

        u1 = Hyperlink(UrlCommon.urlString1)
        u2 = StatefulHyperlink(UrlCommon.urlString1)
        assertEquals(u1, u2)

        u1 = Hyperlink(UrlCommon.urlString1)
        u2 = StatefulFatLink(UrlCommon.urlString1, tailLinks = listOf())
        assertEquals(u1, u2)

        u1 = Hyperlink(UrlCommon.urlString1)
        u2 = Hyperlink(UrlCommon.urlString2)
        assertNotEquals(u1, u2)

        assertEquals(Hyperlink(UrlCommon.urlString1), Hyperlink(UrlCommon.urlString1, "", args = "-i 0s"))
    }

    @Test
    fun testSerialization() {
        val u1 = Hyperlink(UrlCommon.urlString1)
        val gson = GsonBuilder().create()
        val json = gson.toJson(u1)
        printlnPro(json)
        assertTrue { json.contains(UrlCommon.urlString1) }
        val u2 = gson.fromJson(json, Hyperlink::class.java)
        printlnPro(u2)
        assertEquals(UrlCommon.urlString1, u2.url)
    }

    @Test
    fun testHyperlinkDatumSerialization() {
        val u1 = Hyperlink(UrlCommon.urlString1, "fully", 100, args = "-i 1s",
            referrer = "http://bar.tt/", href = "http://foo.com/sp?se=1")
        val gson = GsonBuilder().create()
        val json = gson.toJson(u1.data())
        printlnPro(json)
        assertTrue { json.contains(UrlCommon.urlString1) }
        val u2 = gson.fromJson(json, HyperlinkDatum::class.java)
        printlnPro(u2)
        assertEquals(u1.url, u2.url)
        assertEquals(u1.text, u2.text)
        assertEquals(u1.args, u2.args)
        assertEquals(u1.order, u2.order)
        assertEquals(u1.referrer, u2.referrer)
        assertEquals(u1.href, u2.href)
    }

    @Test
    fun testParsableHyperlinkEquality() {
        var u1: UrlAware = Hyperlink(UrlCommon.urlString1)
        var u2: UrlAware = Hyperlink(UrlCommon.urlString1, "hello", Int.MAX_VALUE)
        assertEquals(u1, u2)

        var u3: UrlAware = ParsableHyperlink(UrlCommon.urlString1) { page, document -> page.url }
        var u4: UrlAware = ParsableHyperlink(UrlCommon.urlString1) { page, document -> page.url }
        assertEquals(u3, u4)
        assertEquals(u1, u3)
    }

    @Test
    fun testParsableHyperlinkArgs() {
        var u1 = ParsableHyperlink(UrlCommon.urlString1) { page, _ -> page.url }.apply {
            href = UrlCommon.urlString2
        }.apply {
            args = "-parse -expires 10m -requireSize 200000"
        }

        assertTrue(u1.args) { u1.args?.contains("-requireSize 200000") == true }
    }

    @Test
    fun testParsableHyperlinkNormalization() {
        val options = LoadOptions.createUnsafe()
        var u1: UrlAware = ParsableHyperlink(UrlCommon.urlString1) { page, document -> page.url }.apply {
            href = UrlCommon.urlString2
        }
        var u2: UrlAware = ParsableHyperlink(UrlCommon.urlString1) { page, document -> page.url }.apply {
            href = UrlCommon.urlString3
        }

        val normalizer = CombinedUrlNormalizer()
        val normUrl1 = normalizer.normalize(u1, options, false)
        assertEquals(u1.href, normUrl1.href?.toString())
    }
}

