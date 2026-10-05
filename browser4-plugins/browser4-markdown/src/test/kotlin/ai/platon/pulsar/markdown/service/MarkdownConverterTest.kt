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
import org.junit.jupiter.api.Test
import kotlin.test.*

class MarkdownConverterTest {

    private val config = MarkdownConfig()
    private val converter = MarkdownConverter(config)

    @Test
    fun `parseLinks returns empty list for null input`() {
        val result = converter.parseLinks(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseLinks returns empty list for empty JSON array`() {
        val result = converter.parseLinks("[]")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseLinks returns empty list for blank input`() {
        val result = converter.parseLinks("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseLinks parses valid link JSON`() {
        val json = """
            [
                {"href": "/docs", "text": "Docs", "resolvedUrl": "https://example.com/docs", "isInternal": true},
                {"href": "https://other.com", "text": "External", "resolvedUrl": "https://other.com", "isInternal": false}
            ]
        """.trimIndent()

        val result = converter.parseLinks(json)
        assertEquals(2, result.size)
        assertEquals("/docs", result[0].href)
        assertEquals("Docs", result[0].text)
        assertTrue(result[0].isInternal)
        assertFalse(result[1].isInternal)
    }

    @Test
    fun `countMarkdownImages returns zero for blank input`() {
        assertEquals(0, converter.countMarkdownImages(""))
        assertEquals(0, converter.countMarkdownImages("   "))
    }

    @Test
    fun `countMarkdownImages counts markdown image references only`() {
        val md = """
            # Title

            ![logo](https://example.com/logo.png)

            Some text with a [link](https://example.com) and an inline ![icon](icon.svg) image.

            | cell | ![table image](https://example.com/t.jpg) |
        """.trimIndent()
        assertEquals(3, converter.countMarkdownImages(md))
    }

    @Test
    fun `extraction script emits heading hashes matching the html level`() {
        // Regression: h2 must become "## ..." (not "#### ..." from doubling).
        // The script must repeat a single '#' character by the heading level.
        assertTrue(
            MarkdownConverter.MARKDOWN_EXTRACTION_SCRIPT.contains("'#'.repeat(level)"),
            "heading emission should repeat a single '#' by level"
        )
        assertFalse(
            MarkdownConverter.MARKDOWN_EXTRACTION_SCRIPT.contains("'##'.repeat(level)"),
            "heading emission must not double the level"
        )
    }

    @Test
    fun `extraction script skips excluded elements inside inline formatting`() {
        // Regression: a <style> block nested in a paragraph/list item must not
        // leak its CSS text into the markdown output.
        val script = MarkdownConverter.MARKDOWN_EXTRACTION_SCRIPT
        val inlineIdx = script.indexOf("function inlineFormatting")
        assertTrue(inlineIdx >= 0, "inlineFormatting function should exist")
        val body = script.substring(inlineIdx, script.indexOf("// ---- Main extraction ----"))
        assertTrue(
            body.contains("isExcluded(child)"),
            "inlineFormatting should skip excluded elements such as style/script"
        )
    }

    @Test
    fun `extraction script does not double-emit images inside figures or containers`() {
        // Regression: an <img> inside <figure>/<p>/<td> was emitted once by the
        // IMG walker branch and again via the container's inline formatting.
        val script = MarkdownConverter.MARKDOWN_EXTRACTION_SCRIPT
        assertTrue(
            script.contains("node.closest('p,li,td,th,blockquote,figcaption,figure')"),
            "IMG branch should skip images handled by an ancestor container"
        )
    }
}

class MarkdownUtilsTest {

    @Test
    fun `validateUrl accepts http URL`() {
        val result = MarkdownUtils.validateUrl("http://example.com")
        assertEquals("http://example.com", result)
    }

    @Test
    fun `validateUrl accepts https URL`() {
        val result = MarkdownUtils.validateUrl("https://example.com/path?q=1")
        assertEquals("https://example.com/path?q=1", result)
    }

    @Test
    fun `validateUrl rejects blank input`() {
        assertFailsWith<IllegalArgumentException> {
            MarkdownUtils.validateUrl("")
        }
    }

    @Test
    fun `validateUrl rejects non-http scheme`() {
        assertFailsWith<IllegalArgumentException> {
            MarkdownUtils.validateUrl("ftp://example.com")
        }
        assertFailsWith<IllegalArgumentException> {
            MarkdownUtils.validateUrl("javascript:alert(1)")
        }
    }

    @Test
    fun `isSameDomain matches identical hosts`() {
        assertTrue(MarkdownUtils.isSameDomain("https://example.com/page", "https://example.com/other"))
    }

    @Test
    fun `isSameDomain matches subdomain to parent`() {
        // candidateHost.endsWith(".$baseHost") matches sub.example.com against example.com
        assertTrue(MarkdownUtils.isSameDomain("https://example.com", "https://sub.example.com/page"))
    }

    @Test
    fun `isSameDomain rejects different domains`() {
        assertFalse(MarkdownUtils.isSameDomain("https://example.com", "https://other.com"))
    }

    @Test
    fun `resolveUrl resolves relative path`() {
        val result = MarkdownUtils.resolveUrl("https://example.com/docs/", "page.html")
        assertEquals("https://example.com/docs/page.html", result)
    }

    @Test
    fun `resolveUrl resolves absolute path`() {
        val result = MarkdownUtils.resolveUrl("https://example.com/docs/", "/other/page.html")
        assertEquals("https://example.com/other/page.html", result)
    }

    @Test
    fun `resolveUrl strips fragment`() {
        val result = MarkdownUtils.resolveUrl("https://example.com", "/page#section")
        assertEquals("https://example.com/page", result)
    }

    @Test
    fun `resolveUrl returns null for non-http scheme`() {
        val result = MarkdownUtils.resolveUrl("https://example.com", "ftp://files.example.com")
        assertNull(result)
    }

    @Test
    fun `generateFilename sanitizes special characters`() {
        val result = MarkdownUtils.generateFilename("Hello: World / Test <b>")
        assertEquals("Hello_-World-_-Test-_b", result)
    }

    @Test
    fun `generateFilename truncates long titles`() {
        val longTitle = "a".repeat(200)
        val result = MarkdownUtils.generateFilename(longTitle, 50)
        assertTrue(result.length <= 50)
    }

    @Test
    fun `generateFilename falls back to page for blank input`() {
        val result = MarkdownUtils.generateFilename("---")
        assertTrue(result.isNotBlank())
        assertEquals("page", result)
    }

    @Test
    fun `sanitizeFilename removes path separators`() {
        val result = MarkdownUtils.sanitizeFilename("../../../etc/passwd")
        assertFalse(result.contains("/"))
        assertFalse(result.contains("\\"))
        assertFalse(result.startsWith("."))
    }

    @Test
    fun `isSamePathPrefix matches correct prefix`() {
        assertTrue(
            MarkdownUtils.isSamePathPrefix(
                "https://example.com/docs/page",
                "https://example.com/docs/other",
                "/docs/"
            )
        )
    }

    @Test
    fun `isSamePathPrefix rejects non-matching prefix`() {
        assertFalse(
            MarkdownUtils.isSamePathPrefix(
                "https://example.com/docs/page",
                "https://example.com/blog/post",
                "/docs/"
            )
        )
    }
}

class MarkdownConfigTest {

    @Test
    fun `default config has sensible values`() {
        val config = MarkdownConfig()
        assertEquals("downloads/markdown", config.outputDir)
        assertEquals(3, config.maxDepth)
        assertEquals(50, config.maxPages)
        assertTrue(config.sameDomainOnly)
        assertTrue(config.includeFrontMatter)
        assertEquals(500, config.crawlDelayMs)
        assertEquals(100, config.maxTitleLength)
    }

    @Test
    fun `copy allows overriding crawl parameters`() {
        val config = MarkdownConfig()
        val override = config.copy(maxDepth = 1, maxPages = 10)
        assertEquals(1, override.maxDepth)
        assertEquals(10, override.maxPages)
        // Other values unchanged
        assertEquals(config.outputDir, override.outputDir)
        assertEquals(config.sameDomainOnly, override.sameDomainOnly)
    }
}
