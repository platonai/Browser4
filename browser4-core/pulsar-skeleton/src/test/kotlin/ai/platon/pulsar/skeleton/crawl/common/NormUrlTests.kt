
package ai.platon.pulsar.skeleton.crawl.common

import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test class for NormURL
 */
class NormUrlTests {
    private val defaultUrl = "https://shopee.co.th/กระเป๋าเป้ผู้ชาย-cat.49.1037.10297?page=1"
    private val defaultArgs = """
        -i 1s -ii 1s -ol ".shopee-search-item-result__item a" -sc 10
    """.trimIndent()
    private val defaultNormalizedArgs = LoadOptions.normalize(defaultArgs)
    private val defaultConfiguredUrl = "$defaultUrl $defaultNormalizedArgs"
    private val defaultNormalizedConfiguredUrl = "$defaultUrl $defaultNormalizedArgs"
    private val defaultOptions = LoadOptions.DEFAULT
    private val volatileConfig = defaultOptions.conf

    @Test
    fun testParse() {
        val url = defaultUrl
        val args = defaultNormalizedArgs
        val configuredUrl = defaultNormalizedConfiguredUrl
        val normURL = NormURL.parse(configuredUrl, volatileConfig)
        assertNormUrl(url, args, configuredUrl, normURL)
    }

    @Test
    fun testNILUrl() {
        val url = AppConstants.NIL_PAGE_URL
        val args = ""
        val configuredUrl = url
        assertNormUrl(url, args, configuredUrl, NormURL.createNil())
    }

    private fun assertNormUrl(url: String, args: String, configuredUrl: String, normURL: NormURL) {
        assertEquals(url, normURL.url.toString())
        assertEquals(url, normURL.urlString)
        assertEquals(configuredUrl, normURL.urlSpec)
        assertEquals(configuredUrl, normURL.configuredUrl)
        assertEquals(configuredUrl, normURL.toString())

        assertEquals(normURL.urlString, url)
    }
}
