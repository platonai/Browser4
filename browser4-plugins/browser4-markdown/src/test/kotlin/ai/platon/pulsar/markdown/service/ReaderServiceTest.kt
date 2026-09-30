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
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * Tests for [ReaderService] — the zero-token article reading pipeline
 * (llms.txt → content negotiation → Readability extraction).
 *
 * Uses a JDK-built-in HTTP server, so no extra test dependencies.
 */
class ReaderServiceTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private val config = MarkdownConfig(includeSourceUrl = false)
    private val httpClient = OkHttpClient()
    private lateinit var service: ReaderService

    private val articleHtml = """
        <html><head>
          <title>How Rust Conquered the Kernel</title>
          <meta name="author" content="Ada Lovelace">
          <meta property="og:site_name" content="Systems Weekly">
        </head><body>
          <nav><a href="/">Home</a> <a href="/news">News</a></nav>
          <article>
            <h1>How Rust Conquered the Kernel</h1>
            <p>Rust brings memory safety to the Linux kernel without sacrificing performance.
               This article explores the history, the design, and the community effort behind
               the largest incremental rewrite in kernel history.</p>
            <p>The first Rust code landed in Linux 6.1, guarded by a strict configuration flag.
               Since then, device drivers and filesystems have begun migrating to safe
               abstractions that eliminate entire classes of vulnerabilities.</p>
            <h2>History</h2>
            <p>The story starts in 2016 with a tiny kernel module written by a student as a
               proof of concept, long before the Rust for Linux mailing list existed.</p>
            <h2>Criticism</h2>
            <p>Critics point to the learning curve and the difficulty of auditing unsafe
               blocks in the kernel's constrained environment and toolchain.</p>
          </article>
          <footer>Copyright 2026 Systems Weekly.</footer>
        </body></html>
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"
        service = ReaderService(httpClient, SiteCrawler(config, MarkdownConverter(config), httpClient))
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun context(path: String, handler: (String) -> Pair<String, String>) {
        server.createContext(path) { exchange ->
            val accept = exchange.requestHeaders.getFirst("Accept") ?: ""
            val (contentType, body) = handler(accept)
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun redirectContext(path: String, location: String, status: Int = 302) {
        server.createContext(path) { exchange ->
            exchange.responseHeaders.add("Location", location)
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
    }

    // =========================================================================
    // Pipeline: extractor
    // =========================================================================

    @Test
    fun `read extracts article markdown from an HTML page`() {
        context("/article") { "text/html" to articleHtml }
        val result = service.read("$baseUrl/article")

        assertEquals("extractor", result.source)
        assertEquals("How Rust Conquered the Kernel", result.title)
        assertEquals("Ada Lovelace", result.byline)
        assertEquals("Systems Weekly", result.siteName)
        assertTrue(result.markdown.contains("Rust brings memory safety"))
        assertTrue(result.markdown.contains("## History"))
        assertTrue(result.markdown.contains("## Criticism"))
        // nav/footer content must not leak into the markdown
        assertTrue(!result.markdown.contains("Home"))
        assertTrue(!result.markdown.contains("Copyright 2026"))
        assertTrue(result.charCount > 0)
    }

    @Test
    fun `read fails loudly for pages without readable content`() {
        context("/tiny") { "text/html" to "<html><body><p>tiny page</p></body></html>" }
        assertThrows(IllegalArgumentException::class.java) {
            service.read("$baseUrl/tiny")
        }
    }

    // =========================================================================
    // Pipeline: llms.txt
    // =========================================================================

    @Test
    fun `read uses llms-txt when discovered`() {
        context("/llms.txt") { "text/plain" to "# Systems Weekly\n\n- [Home](/)\n- [News](/news)\n" }
        context("/") { "text/html" to articleHtml }

        val result = service.read("$baseUrl/", ReadOptions(llms = LlmsMode.DISCOVER))

        assertEquals("llms", result.source)
        assertEquals("Systems Weekly", result.title)
        assertTrue(result.markdown.contains("[News](/news)"))
    }

    @Test
    fun `read discovers llms-txt by walking up the path`() {
        context("/docs/llms.txt") { "text/plain" to "# Docs Index\n\n- [Guide](/docs/guide)\n" }
        context("/docs/guide") { "text/html" to articleHtml }

        val result = service.read("$baseUrl/docs/guide", ReadOptions(llms = LlmsMode.DISCOVER))

        assertEquals("llms", result.source)
        assertEquals("Docs Index", result.title)
        assertTrue(result.markdown.contains("[Guide](/docs/guide)"))
    }

    @Test
    fun `read llms index mode formats and filters links`() {
        context("/llms.txt") {
            "text/plain" to "# Docs\n\n- [Authentication](/docs/auth)\n- [Channels](/docs/channels)\n"
        }

        val result = service.read("$baseUrl/", ReadOptions(llms = LlmsMode.INDEX, filter = "auth"))

        assertEquals("llms-index", result.source)
        assertTrue(result.markdown.contains("[Authentication](http://127.0.0.1:${server.address.port}/docs/auth)"))
        assertTrue(!result.markdown.contains("Channels"))
    }

    @Test
    fun `read llms full mode filters sections`() {
        context("/llms-full.txt") {
            "text/plain" to "# Guide\n\nIntro.\n\n## Setup\n\nInstall.\n\n## Further reading\n\nNext.\n"
        }

        val result = service.read("$baseUrl/", ReadOptions(llms = LlmsMode.FULL, filter = "Setup"))

        assertEquals("llms-full", result.source)
        assertTrue(result.markdown.contains("## Setup"))
        assertTrue(result.markdown.contains("Install."))
        assertTrue(!result.markdown.contains("Further reading"))
    }

    // =========================================================================
    // Markdown fallbacks: {path}.md sibling and llms.txt link routing
    // =========================================================================

    @Test
    fun `read falls back to path-md sibling`() {
        context("/docs/guide") { "text/html" to "<html><body><h1>HTML page</h1><p>tiny</p></body></html>" }
        context("/docs/guide.md") { "text/markdown" to "# Markdown Guide\n\nServed from the .md sibling.\n" }

        val result = service.read("$baseUrl/docs/guide")

        assertEquals("path-markdown", result.source)
        assertEquals("Markdown Guide", result.title)
        assertTrue(result.markdown.contains("Served from the .md sibling"))
    }

    @Test
    fun `read routes through llms-txt link to markdown source`() {
        // No {path}.md sibling here on purpose: only the llms.txt link (matched
        // via the slug heuristic) points at a markdown source, so routing must
        // kick in before the extractor.
        context("/docs/guide") { "text/html" to articleHtml }
        context("/llms.txt") { "text/plain" to "# Docs\n\n- [Guide](/docs/guide-source.md)\n" }
        context("/docs/guide-source.md") { "text/markdown" to "# Guide from llms link\n\nFull markdown.\n" }

        val result = service.read("$baseUrl/docs/guide")

        assertEquals("llms-link", result.source)
        assertTrue(result.markdown.contains("Guide from llms link"))
    }

    // =========================================================================
    // Redirect handling (hop-by-hop SSRF protection)
    // =========================================================================

    @Test
    fun `read blocks cross-domain redirect hops`() {
        redirectContext("/redirect", "http://evil.example.com/page")

        val e = assertThrows(IllegalArgumentException::class.java) {
            service.read("$baseUrl/redirect", ReadOptions(allowedDomains = listOf("127.0.0.1")))
        }
        assertTrue(e.message!!.contains("evil.example.com"))
    }

    @Test
    fun `read follows allowed redirects and reports finalUrl`() {
        redirectContext("/start", "/article")
        context("/article") { "text/html" to articleHtml }

        val result = service.read("$baseUrl/start")

        assertEquals("extractor", result.source)
        assertEquals("$baseUrl/article", result.finalUrl)
        assertTrue(result.markdown.contains("Rust brings memory safety"))
    }

    // =========================================================================
    // Body cap
    // =========================================================================

    @Test
    fun `read truncates oversized bodies`() {
        val big = "x".repeat(2 * 1024 * 1024 + 64)
        context("/big") { "text/markdown" to big }

        val result = service.read("$baseUrl/big")

        assertEquals("negotiation", result.source)
        assertTrue(result.truncated)
        assertTrue(result.markdown.length <= 2 * 1024 * 1024)
    }

    // =========================================================================
    // Pipeline: content negotiation
    // =========================================================================

    @Test
    fun `read uses negotiated markdown response`() {
        context("/negotiation") { accept ->
            if (accept.contains("text/markdown")) {
                "text/markdown" to "# Negotiated Title\n\nDirect markdown from the server.\n"
            } else {
                "text/html" to articleHtml
            }
        }

        val result = service.read("$baseUrl/negotiation")

        assertEquals("negotiation", result.source)
        assertEquals("Negotiated Title", result.title)
        assertTrue(result.markdown.contains("Direct markdown from the server"))
    }

    // =========================================================================
    // Flags
    // =========================================================================

    @Test
    fun `requireMd fails when only heuristic extraction is possible`() {
        context("/article") { "text/html" to articleHtml }
        assertThrows(IllegalArgumentException::class.java) {
            service.read("$baseUrl/article", ReadOptions(requireMd = true))
        }
    }

    @Test
    fun `allowedDomains rejects foreign hosts`() {
        context("/article") { "text/html" to articleHtml }
        assertThrows(IllegalArgumentException::class.java) {
            service.read("$baseUrl/article", ReadOptions(allowedDomains = listOf("example.com")))
        }
    }

    @Test
    fun `allowedDomains accepts matching hosts`() {
        context("/article") { "text/html" to articleHtml }
        val result = service.read("$baseUrl/article", ReadOptions(allowedDomains = listOf("127.0.0.1")))
        assertEquals("extractor", result.source)
    }

    @Test
    fun `outline returns heading tree`() {
        val outline = service.buildOutline(articleHtml)
        assertEquals(listOf("- How Rust Conquered the Kernel", "  - History", "  - Criticism"), outline)
    }

    @Test
    fun `filter keeps only matching sections`() {
        val filtered = service.filterSections(articleHtml, "History")
        assertTrue(filtered.contains("History"))
        assertTrue(filtered.contains("proof of concept"))
        assertTrue(!filtered.contains("Criticism"))
        assertTrue(!filtered.contains("Rust brings memory safety"))
    }

    @Test
    fun `filter fails when no section matches`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.filterSections(articleHtml, "nonexistent-section")
        }
    }
}
