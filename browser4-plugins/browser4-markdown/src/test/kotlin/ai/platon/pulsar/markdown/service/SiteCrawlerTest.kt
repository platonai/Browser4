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
package ai.platon.pulsar.markdown.service

import ai.platon.pulsar.markdown.config.MarkdownConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [SiteCrawler]: crawl summary counters, session-tab restoration and
 * the HTTP fetch conversion path.
 *
 * Browser crawls run against [FakeWebDriver]; the fetch path runs against a
 * loopback [HttpServer], so no browser and no network access are needed.
 */
class SiteCrawlerTest {

    private companion object {
        const val ROOT = "https://example.com/"
        const val SUB = "https://example.com/sub"
        const val OTHER = "https://example.com/other"
        val H1_REGEX = Regex("""^# .*$""", RegexOption.MULTILINE)
    }

    @TempDir
    lateinit var outputDir: Path

    private val httpClient = OkHttpClient()

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.createContext("/page") { exchange ->
            val body = """
                <html><body>
                  <h1>Herman Melville - Moby-Dick</h1>
                  <p>Call me Ishmael. See <a href="/other">the other page</a> for more.</p>
                </body></html>
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    // =========================================================================
    // Crawl summary counters (issue: pagesCrawled included failed pages)
    // =========================================================================

    @Test
    @DisplayName("crawl summary partitions attempted pages into crawled and failed")
    fun crawlSummaryPartitionsAttemptedPages() = runBlocking {
        val driver = FakeWebDriver(ROOT)
        driver.addPage(ROOT, title = "Root", markdown = "# Root\n\nbody\n", linksJson = linksJson(SUB))
        driver.addPage(SUB, title = "Sub", markdown = "")

        val summary = newCrawler(maxDepth = 1).crawl(driver.driver, outputDir = outputDir)

        assertEquals(2, summary.pagesAttempted)
        assertEquals(1, summary.pagesCrawled)
        assertEquals(1, summary.pagesFailed)
        assertEquals(
            summary.pagesAttempted,
            summary.pagesCrawled + summary.pagesFailed,
            "pagesCrawled + pagesFailed must partition the pages attempted"
        )
        assertEquals(summary.pagesAttempted, summary.pageResults.size)
        assertEquals(1, summary.pageResults.count { it.success })
    }

    @Test
    @DisplayName("crawl page results separate links in the file from links on the page")
    fun crawlPageResultsReportFileAndPageLinkCounts() = runBlocking {
        val markdown = "# Root\n\n[one](https://example.com/1) and [two](https://example.com/2)\n"
        val driver = FakeWebDriver(ROOT)
        driver.addPage(ROOT, title = "Root", markdown = markdown, linksJson = linksJson(SUB, OTHER))

        val summary = newCrawler(maxDepth = 0).crawl(driver.driver, outputDir = outputDir)

        val page = summary.pageResults.single()
        assertTrue(page.success)
        assertEquals(2, page.linkCount, "linkCount describes the generated file")
        assertEquals(2, page.internalLinkCount, "internalLinkCount describes the page")
        assertEquals(Files.readString(Path.of(page.filePath)).length, page.charCount)
    }

    // =========================================================================
    // Session tab restoration (issue: crawl left the tab on the last page)
    // =========================================================================

    @Test
    @DisplayName("crawl restores the session tab to the entry page")
    fun crawlRestoresSessionTabToEntryPage() = runBlocking {
        val driver = FakeWebDriver(ROOT)
        driver.addPage(ROOT, title = "Root", markdown = "# Root\n\nbody\n", linksJson = linksJson(SUB))
        driver.addPage(SUB, title = "Sub", markdown = "# Sub\n\nbody\n")

        val summary = newCrawler(maxDepth = 1).crawl(driver.driver, outputDir = outputDir)

        assertEquals(ROOT, summary.entryUrl)
        assertTrue(summary.tabRestored, "the crawl must report that the tab was restored")
        assertEquals(ROOT, driver.currentUrl, "the user's page must survive the crawl")
        assertEquals(listOf(SUB, ROOT), driver.navigations)
    }

    @Test
    @DisplayName("crawlFrom restores the tab the session was on, not the start URL")
    fun crawlFromRestoresOriginalSessionTab() = runBlocking {
        val driver = FakeWebDriver(ROOT)
        driver.addPage(ROOT, title = "Root", markdown = "# Root\n\nbody\n")
        driver.addPage(OTHER, title = "Other", markdown = "# Other\n\nbody\n")

        val summary = newCrawler(maxDepth = 0).crawl(driver.driver, startUrl = OTHER, outputDir = outputDir)

        assertEquals(ROOT, summary.entryUrl)
        assertTrue(summary.tabRestored)
        assertEquals(ROOT, driver.currentUrl)
        assertEquals(listOf(OTHER, ROOT), driver.navigations)
    }

    // =========================================================================
    // Link discovery guard (issue: fragment anchors collapsed onto the page URL)
    // =========================================================================

    @Test
    @DisplayName("link discovery script skips fragment-only anchors and the page's own URL")
    fun linkDiscoveryScriptSkipsFragmentAnchorsAndPageUrl() {
        val script = MarkdownConverter.LINK_DISCOVERY_SCRIPT

        assertTrue(
            script.contains("href.startsWith('#')"),
            "fragment-only anchors must be skipped, not just the literal '#'"
        )
        assertFalse(
            script.contains("href === '#'"),
            "the literal '#' guard alone lets every other fragment anchor through"
        )
        assertTrue(
            script.contains("var pageUrl = window.location.href.split('#')[0];"),
            "the page's own URL must be computed for the self-link guard"
        )
        assertTrue(
            script.contains("if (cleanUrl === pageUrl) continue;"),
            "the page must not be reported as one of its own internal links"
        )
    }

    // =========================================================================
    // HTTP fetch: title, front matter and filename (issue: fetch skipped them)
    // =========================================================================

    @Test
    @DisplayName("htmlToMarkdown falls back to the first h1 when there is no title element")
    fun htmlToMarkdownFallsBackToHeadingTitle() {
        val html = "<html><body><h1>Herman Melville - Moby-Dick</h1><p>Call me Ishmael.</p></body></html>"

        val markdown = normalize(newCrawler().htmlToMarkdown(html, "https://httpbin.org/html"))

        assertTrue(
            markdown.startsWith("---\ntitle: \"Herman Melville - Moby-Dick\"\nurl: https://httpbin.org/html\n---"),
            "pages without a <title> must still get front matter: $markdown"
        )
        assertTrue(markdown.contains("Call me Ishmael."))
        assertEquals(listOf("# Herman Melville - Moby-Dick"), h1Lines(markdown))
    }

    @Test
    @DisplayName("htmlToMarkdown prefers the title element and falls back to og:title")
    fun htmlToMarkdownPrefersTitleElementThenOgTitle() {
        val withTitle = "<html><head><title>Real Title</title></head><body><h1>Body Heading</h1></body></html>"
        val titleFirst = normalize(newCrawler().htmlToMarkdown(withTitle, "https://example.com/a"))
        assertTrue(titleFirst.contains("title: \"Real Title\""))
        assertEquals(listOf("# Real Title"), h1Lines(titleFirst))
        assertFalse(titleFirst.contains("# Body Heading"), "the body h1 must not duplicate the title")

        val withOgTitle = """
            <html><head><meta property="og:title" content="OG Title"></head><body><p>Text.</p></body></html>
        """.trimIndent()
        val ogTitle = normalize(newCrawler().htmlToMarkdown(withOgTitle, "https://example.com/b"))
        assertTrue(ogTitle.contains("title: \"OG Title\""))
        assertEquals(listOf("# OG Title"), h1Lines(ogTitle))
    }

    @Test
    @DisplayName("fetch names the file from the resolved title and writes front matter")
    fun fetchUsesResolvedTitleForFrontMatterAndFilename() {
        val result = newCrawler().fetchAndConvert("$baseUrl/page", outputDir)

        assertTrue(result.success, "fetch should succeed: ${result.error}")
        assertEquals("Herman Melville - Moby-Dick", result.title)
        assertEquals("Herman-Melville-Moby-Dick.md", Path.of(result.filePath).fileName.toString())

        val written = Files.readString(Path.of(result.filePath))
        val markdown = normalize(written)
        assertTrue(markdown.startsWith("---\ntitle: \"Herman Melville - Moby-Dick\""))
        assertEquals(listOf("# Herman Melville - Moby-Dick"), h1Lines(markdown))
        assertEquals(written.length, result.charCount)
        assertEquals(1, result.linkCount, "linkCount must count the links written to the file")
    }

    @Test
    @DisplayName("fetch reports a failed HTTP response without writing a file")
    fun fetchReportsHttpFailure() {
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        val result = newCrawler().fetchAndConvert("$baseUrl/missing", outputDir)

        assertFalse(result.success)
        assertEquals("", result.filePath)
        assertTrue(result.error.orEmpty().startsWith("HTTP 404"))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun newCrawler(maxDepth: Int = 1, maxPages: Int = 5): SiteCrawler {
        val config = MarkdownConfig(
            outputDir = outputDir.toString(),
            maxDepth = maxDepth,
            maxPages = maxPages,
            crawlDelayMs = 0,
        )
        return SiteCrawler(config, MarkdownConverter(config), httpClient)
    }

    private fun linksJson(vararg urls: String): String =
        urls.joinToString(prefix = "[", postfix = "]") { url ->
            """{"href":"$url","text":"link","resolvedUrl":"$url","isInternal":true}"""
        }

    private fun normalize(markdown: String): String = markdown.replace("\r\n", "\n")

    private fun h1Lines(markdown: String): List<String> = H1_REGEX.findAll(markdown).map { it.value }.toList()
}
