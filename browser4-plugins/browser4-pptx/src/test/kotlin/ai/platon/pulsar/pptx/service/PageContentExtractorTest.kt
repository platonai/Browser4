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
package ai.platon.pulsar.pptx.service

import ai.platon.pulsar.pptx.config.PptxConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [PageContentExtractor] JSON result parsing and chrome filtering.
 */
class PageContentExtractorTest {

    private val extractor = PageContentExtractor()

    /** Exposes the probe [PageContentExtractor.extract] evaluates in the page. */
    private class TestableExtractor(config: PptxConfig) : PageContentExtractor(config) {
        fun probe(): String = extractionScript
    }

    @Test
    @DisplayName("parseResult returns empty list for null input")
    fun testParseResultNull() {
        val result = extractor.parseResult(null)
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("parseResult returns empty list for blank string")
    fun testParseResultBlank() {
        val result = extractor.parseResult("")
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("parseResult returns empty list for null JSON string")
    fun testParseResultNullString() {
        val result = extractor.parseResult("null")
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("parseResult returns empty list for empty array JSON")
    fun testParseResultEmptyArray() {
        val result = extractor.parseResult("[]")
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("parseResult parses single title block")
    fun testParseSingleTitle() {
        val json = """[{"type":"title","text":"My Page","level":0}]"""
        val result = extractor.parseResult(json)

        assertEquals(1, result.size)
        assertEquals("title", result[0].type)
        assertEquals("My Page", result[0].text)
        assertEquals(0, result[0].level)
    }

    @Test
    @DisplayName("parseResult parses heading with level")
    fun testParseHeading() {
        val json = """[{"type":"heading","text":"Section One","level":2}]"""
        val result = extractor.parseResult(json)

        assertEquals(1, result.size)
        assertEquals("heading", result[0].type)
        assertEquals("Section One", result[0].text)
        assertEquals(2, result[0].level)
    }

    @Test
    @DisplayName("parseResult parses paragraph")
    fun testParseParagraph() {
        val json = """[{"type":"paragraph","text":"This is a paragraph of text."}]"""
        val result = extractor.parseResult(json)

        assertEquals(1, result.size)
        assertEquals("paragraph", result[0].type)
        assertEquals("This is a paragraph of text.", result[0].text)
    }

    @Test
    @DisplayName("parseResult parses image block")
    fun testParseImage() {
        val json = """[{"type":"image","src":"https://example.com/img.jpg","alt":"A photo","width":800,"height":600}]"""
        val result = extractor.parseResult(json)

        assertEquals(1, result.size)
        assertEquals("image", result[0].type)
        assertEquals("https://example.com/img.jpg", result[0].src)
        assertEquals("A photo", result[0].alt)
        assertEquals(800, result[0].width)
        assertEquals(600, result[0].height)
    }

    @Test
    @DisplayName("parseResult parses table with rows")
    fun testParseTable() {
        val json = """[{"type":"table","rows":[["Name","Age"],["Alice","30"],["Bob","25"]]}]"""
        val result = extractor.parseResult(json)

        assertEquals(1, result.size)
        assertEquals("table", result[0].type)
        assertEquals(3, result[0].rows?.size)
        assertEquals(listOf("Name", "Age"), result[0].rows?.get(0))
        assertEquals(listOf("Alice", "30"), result[0].rows?.get(1))
    }

    @Test
    @DisplayName("parseResult parses unordered list")
    fun testParseUnorderedList() {
        val json = """[{"type":"list","items":["Item A","Item B","Item C"],"ordered":false}]"""
        val result = extractor.parseResult(json)

        assertEquals(1, result.size)
        assertEquals("list", result[0].type)
        assertEquals(3, result[0].items?.size)
        assertEquals("Item A", result[0].items?.get(0))
        assertFalse(result[0].ordered!!)
    }

    @Test
    @DisplayName("parseResult parses ordered list")
    fun testParseOrderedList() {
        val json = """[{"type":"list","items":["First","Second"],"ordered":true}]"""
        val result = extractor.parseResult(json)

        assertEquals(1, result.size)
        assertEquals("list", result[0].type)
        assertTrue(result[0].ordered!!)
    }

    @Test
    @DisplayName("parseResult parses code block")
    fun testParseCode() {
        val json = """[{"type":"code","text":"println(\"hello\")"}]"""
        val result = extractor.parseResult(json)

        assertEquals(1, result.size)
        assertEquals("code", result[0].type)
        assertEquals("println(\"hello\")", result[0].text)
    }

    @Test
    @DisplayName("parseResult parses blockquote")
    fun testParseBlockquote() {
        val json = """[{"type":"blockquote","text":"To be or not to be"}]"""
        val result = extractor.parseResult(json)

        assertEquals(1, result.size)
        assertEquals("blockquote", result[0].type)
        assertEquals("To be or not to be", result[0].text)
    }

    @Test
    @DisplayName("parseResult parses multiple blocks in order")
    fun testParseMultipleBlocks() {
        val json = """
            [
                {"type":"title","text":"Test Page","level":0},
                {"type":"heading","text":"Introduction","level":2},
                {"type":"paragraph","text":"Welcome to the test."},
                {"type":"image","src":"img.png","alt":"","width":400,"height":300},
                {"type":"list","items":["One","Two"],"ordered":false}
            ]
        """.trimIndent()
        val result = extractor.parseResult(json)

        assertEquals(5, result.size)
        assertEquals("title", result[0].type)
        assertEquals("heading", result[1].type)
        assertEquals("paragraph", result[2].type)
        assertEquals("image", result[3].type)
        assertEquals("list", result[4].type)
    }

    @Test
    @DisplayName("parseResult returns empty list for malformed JSON")
    fun testParseMalformedJson() {
        val result = extractor.parseResult("{not valid json")
        assertTrue(result.isEmpty())
    }

    @Test
    @DisplayName("the extraction probe filters page chrome before it becomes slide content")
    fun testExtractionScriptExcludesPageChrome() {
        val script = PageContentExtractor.buildExtractionScript()

        assertFalse(
            script.contains(PageContentExtractor.EXCLUDE_SELECTOR_PLACEHOLDER),
            "the selector array must be spliced into the probe"
        )
        assertTrue(
            script.contains("node.closest(EXCLUDE_SELECTORS)"),
            "the probe must reject subtrees matching the chrome selectors"
        )
        // Wikipedia status badges, sidebars and edit links from the scenario report.
        // Attribute selectors appear JSON-escaped inside the probe's selector array.
        listOf(
            "#mw-indicators", ".mw-indicator", "#siteSub", "#catlinks", ".navbox", ".sidebar",
            ".mw-editsection", "[role=\\\"navigation\\\"]", "[aria-hidden=\\\"true\\\"]",
        ).forEach { selector ->
            assertTrue(script.contains(selector), "the probe must exclude '$selector'")
        }
    }

    @Test
    @DisplayName("configured exclude selectors are added to the built-in chrome selectors")
    fun testConfiguredExcludeSelectorsAreInjectedIntoTheProbe() {
        val probe = TestableExtractor(PptxConfig(excludeSelectors = listOf(".my-chrome", " #sponsored "))).probe()

        assertTrue(probe.contains("\".my-chrome\""), "the configured selector must reach the probe")
        assertTrue(probe.contains("\"#sponsored\""), "configured selectors must be trimmed")
        assertTrue(probe.contains("\"#mw-indicators\""), "built-in chrome selectors must survive")
    }

    @Test
    @DisplayName("jsonStringArray renders a JSON array and escapes quotes")
    fun testJsonStringArray() {
        assertEquals("""["nav",".sidebar"]""", PageContentExtractor.jsonStringArray(listOf("nav", ".sidebar")))
        assertEquals("[]", PageContentExtractor.jsonStringArray(emptyList()))
        assertEquals(
            """["[data-x=\"1\"]"]""",
            PageContentExtractor.jsonStringArray(listOf("""[data-x="1"]"""))
        )
    }

    @Test
    @DisplayName("CONTENT_EXTRACTION_SCRIPT keeps the selector placeholder for substitution")
    fun testExtractionScriptTemplateKeepsThePlaceholder() {
        assertTrue(
            PageContentExtractor.CONTENT_EXTRACTION_SCRIPT
                .contains(PageContentExtractor.EXCLUDE_SELECTOR_PLACEHOLDER)
        )
    }

    @Test
    @DisplayName("a short status table before the first heading is filtered out")
    fun testLeadingShortTableIsFilteredOut() {
        val blocks = listOf(
            ContentBlock(type = "title", text = "Solar System", level = 0),
            ContentBlock(type = "table", rows = listOf(listOf("Featured article"), listOf("Page semi-protected"))),
            ContentBlock(type = "heading", text = "Overview", level = 2),
            ContentBlock(type = "paragraph", text = "The Solar System is the gravitationally bound system."),
        )

        val filtered = extractor.filterLeadingChrome(blocks)

        assertEquals(3, filtered.size)
        assertEquals(listOf("title", "heading", "paragraph"), filtered.map { it.type })
    }

    @Test
    @DisplayName("a short list before the first heading is filtered out")
    fun testLeadingShortListIsFilteredOut() {
        val blocks = listOf(
            ContentBlock(type = "list", items = listOf("Jump to content", "Main page")),
            ContentBlock(type = "heading", text = "Overview", level = 2),
            ContentBlock(type = "paragraph", text = "Article text."),
        )

        val filtered = extractor.filterLeadingChrome(blocks)

        assertEquals(listOf("heading", "paragraph"), filtered.map { it.type })
    }

    @Test
    @DisplayName("a long leading table is kept as content")
    fun testLeadingLongTableIsKept() {
        val blocks = listOf(
            ContentBlock(
                type = "table",
                rows = listOf(
                    listOf("Name", "Value"),
                    listOf("A long descriptive cell that carries real data", "another, even longer descriptive cell value"),
                ),
            ),
            ContentBlock(type = "heading", text = "Overview", level = 2),
        )

        val filtered = extractor.filterLeadingChrome(blocks)

        assertEquals(blocks, filtered)
    }

    @Test
    @DisplayName("chrome is only filtered before the first heading, and never empties the deck")
    fun testFilteringStopsAtContentAndNeverEmptiesTheDeck() {
        // Nothing before the first heading — the whole list is left alone.
        val afterHeading = listOf(
            ContentBlock(type = "heading", text = "Overview", level = 2),
            ContentBlock(type = "table", rows = listOf(listOf("Featured article"))),
            ContentBlock(type = "paragraph", text = "Article text."),
        )
        assertEquals(afterHeading, extractor.filterLeadingChrome(afterHeading))

        // A page that is nothing but chrome must not lose its only block.
        val chromeOnly = listOf(
            ContentBlock(type = "title", text = "Status", level = 0),
            ContentBlock(type = "table", rows = listOf(listOf("All systems operational"))),
        )
        assertEquals(chromeOnly, extractor.filterLeadingChrome(chromeOnly))

        // A real lead paragraph ends the leading run: a later badge stays.
        val paragraphFirst = listOf(
            ContentBlock(type = "paragraph", text = "A real lead paragraph."),
            ContentBlock(type = "table", rows = listOf(listOf("Page semi-protected"))),
        )
        assertEquals(paragraphFirst, extractor.filterLeadingChrome(paragraphFirst))

        // ... while a badge before that paragraph is still leading chrome.
        val badgeBeforeParagraph = listOf(
            ContentBlock(type = "table", rows = listOf(listOf("Featured article"))),
            ContentBlock(type = "paragraph", text = "A real lead paragraph."),
        )
        assertEquals(listOf("paragraph"), extractor.filterLeadingChrome(badgeBeforeParagraph).map { it.type })
    }
}
