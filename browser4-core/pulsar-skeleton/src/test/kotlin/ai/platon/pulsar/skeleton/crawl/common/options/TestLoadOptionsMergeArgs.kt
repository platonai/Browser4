package ai.platon.pulsar.skeleton.crawl.common.options

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class TestLoadOptionsMergeArgs {

    @Test
    fun testMergeArgsBasic() {
        val args1 = "-parse"
        val args2 = "-incognito -expires 1d"
        val merged = LoadOptions.mergeArgs(args1, args2, conf = VolatileConfig.UNSAFE)

        // Check that all expected options are present in the merged string
        assertTrue(merged.contains("-parse"))
        assertTrue(merged.contains("-incognito"))
        assertTrue(merged.contains("-expires"))

        // Parse the merged args and validate properties
        val options = LoadOptions.parse(merged, VolatileConfig.UNSAFE)
        assertTrue(options.parse)
        assertTrue(options.incognito)
        assertEquals(Duration.ofDays(1), options.expires)
    }

    @Test
    fun testMergeArgsOverrides() {
        val args1 = "-storeContent false"
        val args2 = "-storeContent true"
        val merged = LoadOptions.mergeArgs(args1, args2, conf = VolatileConfig.UNSAFE)

        // Parse the merged args and ensure later argument overrides earlier one
        val options = LoadOptions.parse(merged, VolatileConfig.UNSAFE)
        assertTrue(options.storeContent)
    }

    @Test
    fun testMergeArgsWithNulls() {
        // Ensure null arguments are safely skipped
        val merged = LoadOptions.mergeArgs(null, "-parse", null, "-incognito", conf = VolatileConfig.UNSAFE)
        val options = LoadOptions.parse(merged, VolatileConfig.UNSAFE)
        assertTrue(options.parse)
        assertTrue(options.incognito)
    }
}