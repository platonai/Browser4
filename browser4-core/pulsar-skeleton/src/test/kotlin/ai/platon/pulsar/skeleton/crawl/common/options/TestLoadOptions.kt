package ai.platon.pulsar.skeleton.crawl.common.options

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.skeleton.common.options.Condition
import ai.platon.pulsar.skeleton.common.options.LoadOptionDefaults
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.crawl.common.url.StatefulListenableHyperlink
import java.time.Duration
import kotlin.test.*

class TestLoadOptions {

    var i = PulsarContexts.createSession()
    val conf = i.sessionConfig
    val url = "http://abc.com"
    val taskName = AppPaths.fromUri(url)
    val args = "" +
            " -taskName $taskName" +
            " -itemExpires 10d" +
            " -expires 1000d" +
            " -outlink \".products a\"" +
            " -preferParallel true" +
            " -itemScrollCount 20 -itemScrollInterval 1s" +
            ""

    @Test
    fun testOptions() {
        val args0 = URLUtils.splitUrlArgs(url).second
        val options = i.options("$args0 $args")
        assertEquals(".products a", options.outLinkSelector)

        val (url, args) = URLUtils.splitUrlArgs("$url -incognito -expires 1s -ignoreFailure")
        val options2 = LoadOptions.parse(args, conf)
        val options3 = LoadOptions.merge(options, options2)
        assertOptions(options3)
    }

    @Test
    fun testNormalization() {
        assertTrue { i.context.isActive }

        val context = i.context as AbstractPulsarContext
        val normalizer = context.urlNormalizer
        val normalizerOrNull = context.urlNormalizerOrNull

        assertTrue { normalizer.urlNormalizers.isEmpty() }
        assertNotNull(normalizerOrNull)
    }

    @Test
    fun testSpecialChar() {
        val args = "-label com.br"
        val options = LoadOptions.parse(args)
        assertEquals("com.br", options.label)
    }

    @Test
    fun testArityOptions() {
        val options = LoadOptions.create(VolatileConfig.UNSAFE).apply {
            storeContent = true
            parse = true
        }
        val args = options.toString()

        assertEquals(true, LoadOptionDefaults.storeContent)
        assertFalse { "-storeContent" in options.modifiedParams.asMap().keys }
        assertTrue { args.contains("-parse") }
        assertEquals("-parse", args)
    }

    @Test
    fun testParameterOverwriting() {
        val args1 = "-parse -incognito -expires 1s -retry -storeContent false -cacheContent false"
        val args2 = "-incognito -expires 1d -storeContent true -cacheContent true"
        val args3 = "$args1 $args2"

        val options = LoadOptions.parse(args3, conf)
        assertTrue { options.incognito }
    }

    @Test
    fun testMergeOptions() {
        val args1 = "-parse -incognito -expires 1s -retry -storeContent false -cacheContent false"
        val args2 = "-incognito -expires 1d -storeContent true -cacheContent true"
        val options1 = i.options(args1)
        val options2 = i.options(args2)

        printlnPro(LoadOptions.merge(args1, args2, conf))
        assertMergedOptions(LoadOptions.merge(args1, args2, conf), "args1 merge args2")

        LoadOptions.merge(args2, null, conf).also {
            val message = "args2 merge null"
            assertTrue(message) { it.storeContent }
            assertTrue(message) { it.incognito }
            assertEquals(Duration.ofDays(1), it.expires, message)
        }

        val args3 = "-storeContent false"
        LoadOptions.merge(args2, args3, conf).also {
            val message = "args2 merge args3"
            assertTrue(message) { !it.storeContent }
            assertTrue(message) { it.incognito }
            assertEquals(Duration.ofDays(1), it.expires, message)
        }
    }

    private fun assertMergedOptions(options: LoadOptions, message: String) {
        assertTrue(message) { options.storeContent }
        assertTrue(message) { options.incognito }
        assertTrue(message) { options.parse }
        assertEquals(Duration.ofDays(1), options.expires, message)
    }

    @Test
    fun testOptionNames() {
        assertTrue { arrayOf("-l", "-label", "--label").all { it in LoadOptions.getOptionNames("label") } }
        assertTrue { arrayOf("-storeContent", "--store-content").all { it in LoadOptions.getOptionNames("storeContent") } }
    }

    @Test
    fun testOptionParams() {
        assertTrue { arrayOf("-l", "-label", "--label").all { it in LoadOptions.apiPublicOptionNames } }
        assertFalse { arrayOf("-storeContent", "--store-content").any { it in LoadOptions.apiPublicOptionNames } }
    }

    @Test
    fun testBooleanOptions() {
        var options = LoadOptions.parse("-incognito -expires 1s -ignoreFailure -ignoreFailure -storeContent false", conf)
        assertTrue(options.incognito)
        assertTrue(options.ignoreFailure)
        assertFalse(options.parse)
        assertFalse(options.storeContent)

        printlnPro("distinctBooleanParams: " + LoadOptions.arity1BooleanParams)

        val modifiedOptions = options.modifiedOptions
        printlnPro(modifiedOptions)
        val modifiedOptionsKeys = options.modifiedOptions.keys
        assertTrue { "incognito" in modifiedOptionsKeys }
        assertTrue { "expires" in modifiedOptionsKeys }
        assertTrue { "ignoreFailure" in modifiedOptionsKeys }
        assertTrue { "ignoreFailure" in modifiedOptionsKeys }
        assertFalse { "parse" in modifiedOptionsKeys }

        assertEquals(true, LoadOptionDefaults.storeContent)
        assertTrue { "storeContent" in modifiedOptionsKeys }

        val modifiedParams = options.modifiedParams
        printlnPro(modifiedParams)
        assertEquals(false, options.isDefault("storeContent"))
        assertEquals(false, modifiedParams["-storeContent"])
        assertEquals(true, modifiedParams["-ignoreFailure"])
        assertEquals(true, modifiedParams["-incognito"])

        val args = options.toString()
        printlnPro("args: $args")

        assertTrue { "-storeContent" in args }

        options = LoadOptions.parse(args, conf)
        printlnPro("options: $options")

        assertTrue(options.incognito)
        assertTrue(options.ignoreFailure)
        assertFalse(options.parse)
        assertFalse(options.storeContent)

        printlnPro("modifiedOptions: " + options.modifiedOptions)
        assertTrue(modifiedOptions.containsKey("ignoreFailure"))
        assertTrue(modifiedOptions.containsKey("expires"))
    }

    @Test
    fun testShowLoadOptions() {
        LoadOptions.helpList.forEach {
            printlnPro(it)
        }
    }

    @Test
    fun testModifiedOptions() {
        val options = LoadOptions.parse("-incognito -expires 1s -ignoreFailure", conf)
//        logPrintln(options.modifiedOptions)
        val modifiedOptions = options.modifiedOptions
        assertTrue(modifiedOptions.containsKey("ignoreFailure"))
        assertTrue(modifiedOptions.containsKey("expires"))
    }

    @Test
    fun testMerging() {
        val options = LoadOptions.parse("-incognito -expires 1s -ignoreFailure", conf)
        val args = "-label test-merging"
        val options2 = LoadOptions.merge(options, i.options(args))
        assertEquals("test-merging", options2.label)
        assertTrue { options2.incognito }
    }

    @Test
    fun testErase() {
        val args = "-incognito -expires 1s -ignoreFailure"
        val erasedArgs = "-erased -erased 1s -ignoreFailure"
        val args1 = LoadOptions.eraseOptions(args, "incognito", "expires", "")
        assertEquals(erasedArgs, args1, args1)
        val options = LoadOptions.parse(args1, conf)
        val reparsedArgs = "-ignoreFailure"
        assertEquals(reparsedArgs, options.toString(), options.toString())
    }

    @Test
    fun testOverride() {
        assertOptionEquals("-tl 50", "-tl 40 -tl 50")
    }

    @Test
    fun testClone() {
        val options = LoadOptions.parse("$args -incognito -expires 1s -ignoreFailure -storeContent false", conf)
        val clone = options.clone()
        assertEquals(options, clone)
        val clone2 = clone.clone()
        assertEquals(options, clone)
        assertEquals(options, clone2)
        assertEquals(clone, clone2)
    }

    @Test
    fun testEquality() {
        assertOptionEquals("", "")
        assertOptionEquals("", "-shouldIgnore")
        assertOptionEquals("", "-shouldIgnore2 2")
        assertOptionEquals("", "-shouldIgnore3 a b c")

        assertOptionEquals("-ignoreFailure", "-ignoreFailure -shouldIgnore")
        assertOptionEquals("-ignoreFailure", "-ignoreFailure -shouldIgnore2 2")
        assertOptionEquals("-ignoreFailure", "-ignoreFailure -shouldIgnore3 a b c")

        assertOptionEquals("-tl 50", "-tl 40 -tl 50")
        assertOptionEquals("-tl 40 -itemExpires 10", "-itemExpires 10 -tl 40")

        assertOptionNotEquals("-tl 10", "-tl 40")
        assertOptionNotEquals("-tl 10 -itemExpires 10", "-itemExpires 10 -tl 40")
        assertOptionNotEquals("-ignoreFailure -tl 40 -itemExpires 10", "-itemExpires 10 -tl 40")
    }

    @Test
    fun testNormalizeOptions() {
        val op = LoadOptions.parse(URLUtils.splitUrlArgs("$url -incognito -expires 1s -ignoreFailure").second, conf)
        val normURL = i.normalize(url, op)
        val options = normURL.options
        assertTrue(options.incognito)
        assertEquals(1, options.expires.seconds)
        assertTrue(options.ignoreFailure)
    }

    @Test
    fun testNormalizeOptions2() {
        val options = LoadOptions.parse(URLUtils.splitUrlArgs("$url $args -incognito -expires 1s -ignoreFailure -storeContent false").second, conf)
        val normURL = i.normalize(url, options)

        printlnPro(normURL.configuredUrl)
        val normUrl2 = i.normalize(normURL.configuredUrl, LoadOptions.parse("-tl 40 -itemExpires 1d", conf))

        assertTrue { normUrl2.options.ignoreFailure }

        assertOptions(normUrl2.options)
        assertEquals(40, normUrl2.options.topLinks)
        assertEquals(options.itemExpires, normUrl2.options.itemExpires)
    }

    @Test
    fun testNormalizeHyperlinkOptions() {
        val url = "https://www.amazon.com/Best-Sellers-Beauty/zgbs/beauty"
        val hyperlink = StatefulListenableHyperlink(url, "", args = "-i 0s")

        val normURL = i.normalize(hyperlink)
        assertEquals(0, normURL.options.expires.seconds)
    }

    @Test
    fun testHashCode() {
        val op = LoadOptions.parse(URLUtils.splitUrlArgs("$url -incognito -expires 1s -ignoreFailure").second, conf)
        printlnPro(op.hashCode())
    }

    @Test
    fun testNormalizeItemOptions() {
        val options = LoadOptions.parse(URLUtils.splitUrlArgs("$url -incognito -expires 1s -ignoreFailure").second, conf)
        val normURL = i.normalize(url, options)
        printlnPro(normURL.configuredUrl)

        val normUrl2 = i.normalize(normURL.configuredUrl, LoadOptions.parse("-tl 40 -itemExpires 1d", conf), toItemOption = true)
        printlnPro(normUrl2.configuredUrl)

        assertEquals(Duration.ofDays(1), normUrl2.options.expires)
        assertEquals(40, normUrl2.options.topLinks)
    }

    private fun assertOptions(options: LoadOptions) {
        assertTrue(options.incognito)
        // already corrected
        // assertEquals("\".products a\"", options.outLinkSelector)
        assertEquals(".products a", options.outLinkSelector)
        assertEquals(1, options.expires.seconds)
        assertEquals(20, options.itemScrollCount)
        assertEquals(1, options.itemScrollInterval.seconds)
        assertTrue(options.ignoreFailure)
    }

    private fun assertOptionEquals(expected: String, actual: String, msg: String? = null) {
        assertEquals(LoadOptions.parse(expected, conf), LoadOptions.parse(actual, conf), msg)
    }

    private fun assertOptionNotEquals(expected: String, actual: String, msg: String? = null) {
        assertNotEquals(LoadOptions.parse(expected, conf), LoadOptions.parse(actual, conf), msg)
    }
}

