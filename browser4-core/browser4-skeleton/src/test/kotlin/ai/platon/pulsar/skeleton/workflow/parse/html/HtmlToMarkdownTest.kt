package ai.platon.pulsar.skeleton.workflow.parse.html

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HtmlToMarkdown")
class HtmlToMarkdownTest {

    private val converter = HtmlToMarkdown()

    private fun convert(html: String, baseUrl: String = "https://example.com/post"): String =
        converter.convert(html, baseUrl)

    @Test
    @DisplayName("blank input converts to an empty string")
    fun blankInput() {
        assertEquals("", convert(""))
        assertEquals("", convert("   "))
    }

    @Test
    @DisplayName("headings and paragraphs render in document order with a blank line between blocks")
    fun headingsAndParagraphs() {
        val markdown = convert("<h1>Title</h1><p>Body</p><h2>Sub</h2><p>More</p>")
        assertEquals("# Title\n\nBody\n\n## Sub\n\nMore", markdown)
    }

    @Test
    @DisplayName("all six heading levels map to their hash prefix")
    fun headingLevels() {
        val markdown = convert((1..6).joinToString("") { "<h$it>H$it</h$it>" })
        (1..6).forEach { level ->
            assertTrue(markdown.contains("${"#".repeat(level)} H$level"), markdown)
        }
    }

    @Test
    @DisplayName("links keep their text and resolve to absolute URLs")
    fun links() {
        val markdown = convert("""<p>See <a href="/docs">the docs</a> now.</p>""")
        assertEquals("See [the docs](https://example.com/docs) now.", markdown)
    }

    @Test
    @DisplayName("a link with empty text is dropped rather than emitted as an empty label")
    fun emptyLinkText() {
        val markdown = convert("""<p>a<a href="/x"></a>b</p>""")
        assertEquals("ab", markdown)
    }

    @Test
    @DisplayName("emphasis, inline code and strikethrough survive")
    fun inlineFormatting() {
        val markdown = convert("<p><strong>bold</strong> and <em>italic</em> and <code>x = 1</code> and <s>gone</s></p>")
        assertEquals("**bold** and *italic* and `x = 1` and ~~gone~~", markdown)
    }

    @Test
    @DisplayName("a hard break becomes a markdown line break")
    fun hardBreak() {
        val markdown = convert("<p>first<br>second</p>")
        assertEquals("first  \nsecond", markdown)
    }

    @Test
    @DisplayName("nested lists indent one level per depth")
    fun nestedLists() {
        val markdown = convert("<ul><li>a<ul><li>b<ul><li>c</li></ul></li></ul></li></ul>")
        assertEquals("- a\n  - b\n    - c", markdown)
    }

    @Test
    @DisplayName("ordered lists honour the start attribute")
    fun orderedListStart() {
        val markdown = convert("""<ol start="3"><li>x</li><li>y</li></ol>""")
        assertEquals("3. x\n4. y", markdown)
    }

    @Test
    @DisplayName("a list item keeps its own text beside a nested list")
    fun listItemWithNestedList() {
        val markdown = convert("<ul><li>parent<ul><li>child</li></ul></li><li>sibling</li></ul>")
        assertEquals("- parent\n  - child\n- sibling", markdown)
    }

    @Test
    @DisplayName("a table with a header row renders a markdown table")
    fun tableWithHeader() {
        val html = """
            <table>
              <thead><tr><th>Name</th><th>Price</th></tr></thead>
              <tbody><tr><td>Widget</td><td>10</td></tr></tbody>
            </table>
        """.trimIndent()
        assertEquals("| Name | Price |\n| --- | --- |\n| Widget | 10 |", convert(html))
    }

    @Test
    @DisplayName("a table without a header row still renders with an empty header")
    fun tableWithoutHeader() {
        val markdown = convert("<table><tr><td>a</td><td>b</td></tr></table>")
        assertEquals("|  |  |\n| --- | --- |\n| a | b |", markdown)
    }

    @Test
    @DisplayName("ragged rows are padded and pipes are escaped")
    fun raggedTable() {
        val html = "<table><tr><th>a</th><th>b</th><th>c</th></tr><tr><td>1</td><td>x|y</td></tr></table>"
        val markdown = convert(html)
        assertTrue(markdown.contains("| 1 | x\\|y |  |"), markdown)
    }

    @Test
    @DisplayName("a code block keeps its language and gets a fence")
    fun fencedCodeWithLanguage() {
        val markdown = convert("""<pre><code class="language-kotlin">val a = 1</code></pre>""")
        assertEquals("```kotlin\nval a = 1\n```", markdown)
    }

    @Test
    @DisplayName("a code block containing backticks gets a longer fence")
    fun codeFenceEscalation() {
        val markdown = convert("<pre><code>a ``` b</code></pre>")
        assertTrue(markdown.startsWith("````"), markdown)
        assertTrue(markdown.contains("a ``` b"), markdown)
        assertTrue(markdown.endsWith("````"), markdown)
    }

    @Test
    @DisplayName("blockquotes prefix every line, including blank ones")
    fun blockquote() {
        val markdown = convert("<blockquote><p>quoted</p><p>more</p></blockquote>")
        assertEquals("> quoted\n>\n> more", markdown)
    }

    @Test
    @DisplayName("images render with alt text and absolute sources")
    fun images() {
        assertEquals("![logo](https://example.com/logo.png)", convert("""<img src="/logo.png" alt="logo">"""))
        assertEquals("![image](https://example.com/a.png)", convert("""<img src="/a.png">"""))
    }

    @Test
    @DisplayName("data-URI images are skipped")
    fun dataUriImageSkipped() {
        assertEquals("", convert("""<p><img src="data:image/png;base64,AAAA"></p>"""))
    }

    @Test
    @DisplayName("scripts, styles and embedded content are removed")
    fun noiseRemoved() {
        val markdown = convert(
            """<div><script>var x = 1;</script><style>.a{color:red}</style>
               <p>keep</p><iframe src="//ads"></iframe><svg><circle/></svg></div>"""
        )
        assertEquals("keep", markdown)
        assertFalse(markdown.contains("var x"), markdown)
    }

    @Test
    @DisplayName("a horizontal rule and stray container text are preserved")
    fun ruleAndStrayText() {
        assertEquals("before\n\n---\n\nafter", convert("<p>before</p><hr><p>after</p>"))
        assertEquals("loose text", convert("<div>loose text</div>"))
    }

    @Test
    @DisplayName("definition lists render term and definition")
    fun definitionList() {
        val markdown = convert("<dl><dt>Term</dt><dd>Meaning</dd></dl>")
        assertEquals("**Term**\n\nMeaning", markdown)
    }

    @Test
    @DisplayName("whitespace is normalized so source indentation does not leak")
    fun whitespaceNormalization() {
        val markdown = convert("<p>a\n      b\t\tc</p>")
        assertEquals("a b c", markdown)
    }

    @Test
    @DisplayName("front matter carries the title and url when requested")
    fun frontMatter() {
        val withFrontMatter = HtmlToMarkdown(MarkdownOptions(includeFrontMatter = true))
        val markdown = withFrontMatter.convert(
            "<html><head><title>My \"Page\"</title></head><body><p>b</p></body></html>",
            "https://example.com/p",
        )
        assertTrue(markdown.startsWith("---\ntitle: \"My \\\"Page\\\"\"\nurl: https://example.com/p\n---"), markdown)
        assertTrue(markdown.endsWith("b"), markdown)
    }

    @Test
    @DisplayName("a source-url comment is used when front matter is off")
    fun sourceUrlComment() {
        val withSource = HtmlToMarkdown(MarkdownOptions(includeSourceUrl = true))
        val markdown = withSource.convert("<p>b</p>", "https://example.com/p")
        assertEquals("<!-- Source: https://example.com/p -->\n\nb", markdown)
    }

    @Test
    @DisplayName("exclude selectors drop matched elements before conversion")
    fun excludeSelectors() {
        val converter = HtmlToMarkdown(MarkdownOptions(excludeSelectors = listOf(".ad", "#banner")))
        val markdown = converter.convert("<div><p class=\"ad\">buy</p><p>keep</p><div id=\"banner\">x</div></div>")
        assertEquals("keep", markdown)
    }

    @Test
    @DisplayName("conversion is deterministic for the same input")
    fun deterministic() {
        val html = "<h1>t</h1><ul><li>a</li></ul><table><tr><th>h</th></tr><tr><td>c</td></tr></table>"
        assertEquals(convert(html), convert(html))
    }
}
