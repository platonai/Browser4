package ai.platon.pulsar.common

import org.apache.commons.lang3.RandomStringUtils
import kotlin.test.*

class TestHtmlStrings {
    @Test
    fun testHTMLCharsetReplacer() {
        val html = "<html><head><meta charset=\"GBK\"></head><body><div>Hello World</div></body></html>"
        val html2 = HtmlUtils.replaceHTMLCharset(html, DEFAULT_CHARSET_PATTERN)
        // logPrintln(html2.toString())
        assertTrue { html2.toString().contains("<meta charset=\"UTF-8\">") }
    }

    @Test
    fun isBlankBody() {
        assertTrue(HtmlUtils.isBlankBody("....<body></body>...."))
        assertTrue(HtmlUtils.isBlankBody("....<body>\n</body>...."))
        assertTrue(HtmlUtils.isBlankBody("....<body>\t</body>...."))
        assertTrue(HtmlUtils.isBlankBody("....<body a=1 b=2></body>...."))
        assertTrue(HtmlUtils.isBlankBody("<script>....<body   >    </body>...."))
        assertTrue(HtmlUtils.isBlankBody("script....<body a='1'>     </body>...."))

        assertFalse(HtmlUtils.isBlankBody("....<body>    body </body>...."))
        assertFalse(HtmlUtils.isBlankBody("....<body a=1 b=2> 2> 1 </body>...."))
        assertFalse(HtmlUtils.isBlankBody("<script>....<body   > 2< 1 </body>...."))
        assertFalse(HtmlUtils.isBlankBody("script....<body a='1'>   &nbsp;    </body>...."))
    }

    @Test
    fun testRandomString() {
        val strings = IntRange(1, 20).map { RandomStringUtils.secure().nextAlphanumeric(5) }
        assertEquals(strings.size, strings.distinct().size)
    }
}
