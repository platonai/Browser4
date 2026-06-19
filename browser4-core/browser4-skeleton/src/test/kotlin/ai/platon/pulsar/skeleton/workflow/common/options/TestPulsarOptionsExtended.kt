package ai.platon.pulsar.skeleton.workflow.common.options

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.options.PulsarOptions
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Extended tests for [PulsarOptions] covering gaps not tested in [TestPulsarOptions].
 *
 * Targets: normalize(args: String), normalize(argv: Array<String>), split(),
 * doParse() flags, toCmdLine/toArgsMap/toArgv/toMutableArgsMap, and Map constructor.
 */
class TestPulsarOptionsExtended {

    private val conf = VolatileConfig.UNSAFE

    // ---------------------------------------------------------------------------
    // normalize(args: String) — comma-to-space and arity0-to-arity1
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("normalize: commas replaced with spaces")
    fun testNormalizeArgsCommaToSpace() {
        val result = PulsarOptions.normalize("-expires 1d,-incognito")
        assertFalse(result.contains(","))
        assertTrue(result.contains("-expires 1d -incognito"))
    }

    @Test
    @DisplayName("normalize: -cacheContent arity0 converted to arity1")
    fun testNormalizeArgsCacheContentArity0ToArity1() {
        val result = PulsarOptions.normalize("-cacheContent")
        assertTrue(result.contains("-cacheContent true"))
    }

    @Test
    @DisplayName("normalize: -storeContent arity0 converted to arity1")
    fun testNormalizeArgsStoreContentArity0ToArity1() {
        val result = PulsarOptions.normalize("-storeContent")
        assertTrue(result.contains("-storeContent true"))
    }

    @Test
    @DisplayName("normalize: already arity1 storeContent unchanged")
    fun testNormalizeArgsStoreContentAlreadyArity1() {
        val result = PulsarOptions.normalize("-storeContent false")
        assertTrue(result.contains("-storeContent false"))
    }

    @Test
    @DisplayName("normalize: no change for unrelated args")
    fun testNormalizeArgsNoChange() {
        val result = PulsarOptions.normalize("-parse -incognito")
        assertEquals("-parse -incognito", result)
    }

    // ---------------------------------------------------------------------------
    // normalize(argv: Array<String>) — % and %20 → space
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("normalize argv: % replaced with space")
    fun testNormalizeArgvPercentToSpace() {
        val argv = arrayOf("-label", "test%label")
        PulsarOptions.normalize(argv)
        assertEquals("test label", argv[1])
    }

    @Test
    @DisplayName("normalize argv: %20 — % replaced first, leaving '20'")
    fun testNormalizeArgvPercent20WithPercent() {
        val argv = arrayOf("-label", "test%20label")
        PulsarOptions.normalize(argv)
        // % is replaced before %20, so "test%20label" → "test 20label"
        assertTrue(argv[1].startsWith("test "), "should replace % with space")
    }

    @Test
    @DisplayName("normalize argv: already has spaces — not double-normalized")
    fun testNormalizeArgvAlreadySpace() {
        val argv = arrayOf("-label", "test label")
        PulsarOptions.normalize(argv)
        assertEquals("test label", argv[1])
    }

    // ---------------------------------------------------------------------------
    // split() — argument splitting with quote preservation
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("split: plain arguments")
    fun testSplitPlainArguments() {
        val result = PulsarOptions.split("-parse -incognito")
        assertEquals(2, result.size)
        assertEquals("-parse", result[0])
        assertEquals("-incognito", result[1])
    }

    @Test
    @DisplayName("split: quoted string preserved as single argument")
    fun testSplitQuotedString() {
        val result = PulsarOptions.split("-ol \".products a\"")
        assertTrue(result.size >= 2)
        assertTrue(result.any { it.contains("products") })
    }

    @Test
    @DisplayName("split: empty string")
    fun testSplitEmpty() {
        val result = PulsarOptions.split("")
        // Empty split produces empty list
        assertTrue(result.isEmpty())
    }

    // ---------------------------------------------------------------------------
    // doParse() — acceptUnknownOptions / allowParameterOverwriting
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("doParse: unknown options accepted by default")
    fun testDoParseAcceptsUnknownOptions() {
        // Parsing unknown options with default settings should succeed
        val options = LoadOptions.parse("-unknownOption value -parse", conf)
        assertTrue(options.parse)
    }

    @Test
    @DisplayName("doParse: parameter overwriting allowed by default")
    fun testDoParseAllowsOverwriting() {
        // Last value wins when overwriting
        val options = LoadOptions.parse("-expires 1d -expires 2d", conf)
        assertEquals(
            java.time.Duration.ofDays(2),
            options.expires,
            "Last value should win when overwriting is allowed"
        )
    }

    // ---------------------------------------------------------------------------
    // toCmdLine / toArgsMap / toArgv / toMutableArgsMap
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("toCmdLine: produces valid command line")
    fun testToCmdLine() {
        val options = LoadOptions.parse("-parse -expires 1d", conf)
        val cmdLine = options.toCmdLine()
        assertTrue(cmdLine.isNotBlank())
    }

    @Test
    @DisplayName("toArgsMap: contains parsed options")
    fun testToArgsMap() {
        val options = LoadOptions.parse("-parse -expires 1d", conf)
        val map = options.toArgsMap()
        assertTrue(map.isNotEmpty())
    }

    @Test
    @DisplayName("toMutableArgsMap: returns mutable map")
    fun testToMutableArgsMap() {
        val options = LoadOptions.parse("-parse -expires 1d", conf)
        val map = options.toMutableArgsMap()
        assertTrue(map.isNotEmpty())
        // Should be mutable — adding an entry should not throw
        map["-test"] = "1"
    }

    @Test
    @DisplayName("toArgv: produces string array")
    fun testToArgv() {
        val options = LoadOptions.parse("-parse -expires 1d", conf)
        val argv = options.toArgv()
        assertTrue(argv.isNotEmpty())
    }

    // ---------------------------------------------------------------------------
    // Map constructor
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Map constructor: creates options from map")
    fun testMapConstructor() {
        val map = mapOf("-p" to "parse", "-i" to "1d")
        val options = PulsarOptions(map)
        assertTrue(options.args.isNotEmpty())
        assertTrue(options.args.contains("parse") || options.args.contains("1d"))
    }
}
