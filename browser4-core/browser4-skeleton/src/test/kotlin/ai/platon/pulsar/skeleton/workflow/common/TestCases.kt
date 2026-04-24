package ai.platon.pulsar.skeleton.workflow.common

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.urls.URLUtils.splitUrlArgs
import ai.platon.pulsar.skeleton.common.options.LoadOptionDefaults
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.test.TestUrls
import com.google.common.collect.Lists
import com.google.common.collect.Sets
import org.apache.commons.lang3.StringUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.function.Consumer
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Created by Vincent on 16-7-20.
 * Copyright @ 2013-2016 Platon AI. All rights reserved
 */
class TestCases {

    @Test
    @Ignore
    @Throws(IOException::class)
    fun generateRegexUrlFilter() {
        val files = arrayOf(
            "config/seeds/aboard.txt",
            "config/seeds/bbs.txt",
            "config/seeds/national.txt",
            "config/seeds/papers.txt"
        )
        val lines: MutableList<String> = Lists.newArrayList()
        for (file in files) {
            lines.addAll(Files.readAllLines(Paths.get(file)))
        }
        val lines2: MutableSet<String?> = Sets.newHashSet()
        lines.forEach(Consumer { url: String? ->
            var pattern = StringUtils.substringBetween(url, "://", "/")
            pattern = "+http://$pattern/(.+)"
            lines2.add(pattern)
        })
        Files.write(Paths.get("/tmp/regex-urlfilter.txt"), StringUtils.join(lines2, "\n").toByteArray())
        printlnPro(lines2.size)
        printlnPro(StringUtils.join(lines2, ","))
    }

    @Test
    fun testSplitUrlArgs() {
        assertTrue { LoadOptionDefaults.storeContent }
//        val configuredUrl = "https://www.amazon.com/dp/B0E000001 -prst --expires PT1S --auto-flush --fetch-mode NATIVE --browser NONE"
        val configuredUrl = TestUrls.PRODUCT_DETAIL_URL
        val (url, args) = splitUrlArgs(configuredUrl)
        assertEquals(configuredUrl, url)
        assertEquals("", args)
        val options = LoadOptions.parse(args, VolatileConfig())
        assertEquals(Instant.EPOCH, options.taskTime)
        assertEquals("", options.toString())
    }
}

