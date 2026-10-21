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
package ai.platon.pulsar.markdown.tools

import ai.platon.pulsar.markdown.config.MarkdownConfig
import ai.platon.pulsar.markdown.service.FakeWebDriver
import ai.platon.pulsar.markdown.service.MarkdownConverter
import ai.platon.pulsar.markdown.service.ReaderService
import ai.platon.pulsar.markdown.service.SiteCrawler
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tool-level contract tests for [MarkdownToolExecutor]: the fields a caller
 * sees in the MCP result map.
 */
class MarkdownToolExecutorTest {

    private companion object {
        const val ROOT = "https://example.com/"
        const val SUB = "https://example.com/sub"
    }

    @TempDir
    lateinit var outputDir: Path

    private val httpClient = OkHttpClient()

    @Test
    @DisplayName("convert reports the file's link count and the page's internal link count separately")
    fun convertReportsFileAndPageLinkCounts() = runBlocking {
        val markdown = "# Example\n\nSee [one](https://example.com/1), [two](https://example.com/2) " +
            "and ![logo](https://example.com/logo.png).\n"
        val driver = FakeWebDriver(ROOT)
        driver.addPage(
            ROOT,
            title = "Example",
            markdown = markdown,
            linksJson = linksJson(SUB to true, "https://other.example.com/x" to false),
        )

        val result = newExecutor().callFunctionOn(
            "markdown", "convert", mapOf("outputPath" to outputDir.toString()), driver.driver
        ) as Map<*, *>

        // The page has one internal link (the external one is filtered out), the
        // file has two markdown links (the image is not a link).
        assertEquals(1, result["internalLinkCount"])
        assertEquals(2, result["linkCount"])
        assertEquals(1, result["imageCount"])

        val filePath = Path.of(result["filePath"].toString())
        val written = Files.readString(filePath)
        assertEquals(written.length, result["charCount"])
        assertEquals(2, Regex("""(?<!!)\[[^]]*]\([^)]*\)""").findAll(written).count())
    }

    @Test
    @DisplayName("crawl reports partitioned counters and the restored session tab")
    fun crawlReportsPartitionedCountersAndRestoredTab() = runBlocking {
        val driver = FakeWebDriver(ROOT)
        driver.addPage(ROOT, title = "Root", markdown = "# Root\n\nbody\n", linksJson = linksJson(SUB to true))
        driver.addPage(SUB, title = "Sub", markdown = "")

        val result = newExecutor().callFunctionOn(
            "markdown", "crawl", mapOf("maxDepth" to 1, "outputPath" to outputDir.toString()), driver.driver
        ) as Map<*, *>

        assertEquals(2, result["pagesAttempted"])
        assertEquals(1, result["pagesCrawled"])
        assertEquals(1, result["pagesFailed"])
        assertEquals(ROOT, result["entryUrl"])
        assertEquals(true, result["tabRestored"])
        assertEquals(ROOT, driver.currentUrl)

        @Suppress("UNCHECKED_CAST")
        val pages = result["pageResults"] as List<Map<*, *>>
        assertEquals(2, pages.size)
        assertEquals(1, pages.count { it["success"] == true })
        val failed = pages.single { it["success"] == false }
        assertEquals("No content extracted", failed["error"])
        assertEquals("", failed["filePath"])
    }

    @Test
    @DisplayName("convert without page content reports zero counts instead of the page link count")
    fun convertWithoutContentReportsZeroLinkCount() = runBlocking {
        val driver = FakeWebDriver(ROOT)
        driver.addPage(ROOT, title = "Empty", markdown = "")

        val result = newExecutor().callFunctionOn(
            "markdown", "convert", mapOf("outputPath" to outputDir.toString()), driver.driver
        ) as Map<*, *>

        assertEquals(0, result["linkCount"])
        assertEquals(0, result["charCount"])
        assertEquals("", result["filePath"])
        assertTrue(result["error"].toString().isNotBlank())
    }

    private fun newExecutor(): MarkdownToolExecutor {
        val config = MarkdownConfig(outputDir = outputDir.toString(), crawlDelayMs = 0)
        val converter = MarkdownConverter(config)
        val siteCrawler = SiteCrawler(config, converter, httpClient)
        return MarkdownToolExecutor(config, converter, siteCrawler, ReaderService(httpClient, siteCrawler))
    }

    private fun linksJson(vararg entries: Pair<String, Boolean>): String =
        entries.joinToString(prefix = "[", postfix = "]") { (url, isInternal) ->
            """{"href":"$url","text":"link","resolvedUrl":"$url","isInternal":$isInternal}"""
        }
}
