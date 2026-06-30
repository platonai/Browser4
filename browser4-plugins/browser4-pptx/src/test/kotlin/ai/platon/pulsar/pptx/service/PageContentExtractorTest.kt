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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [PageContentExtractor] JSON result parsing.
 */
class PageContentExtractorTest {

    private val extractor = PageContentExtractor()

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
}
