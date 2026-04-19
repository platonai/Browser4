package ai.platon.pulsar.agentic.support

import ai.platon.pulsar.agentic.common.SimpleKotlinParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class ToolCallExecutorTest {
    private val parser = SimpleKotlinParser()

    @Test
        @DisplayName("parse simple call without args")
    fun parseSimpleCallWithoutArgs() {
        val tc = parser.parseFunctionExpression("tab.goBack()")
        assertNotNull(tc)
        tc!!
        assertEquals("tab", tc.domain)
        assertEquals("goBack", tc.method)
        assertTrue(tc.arguments.isEmpty())
    }

    @Test
        @DisplayName("parse call with one arg")
    fun parseCallWithOneArg() {
        val tc = parser.parseFunctionExpression("tab.scrollToMiddle(0.4)")
        assertNotNull(tc)
        tc!!
        assertEquals("tab", tc.domain)
        assertEquals("scrollToMiddle", tc.method)
        assertEquals("0.4", tc.arguments["0"])
    }

    @Test
        @DisplayName("parse args with comma inside quotes")
    fun parseArgsWithCommaInsideQuotes() {
        val src = "tab.clickTextMatches(\"a.link\", \"hello, world\", 2)"
        val tc = parser.parseFunctionExpression(src)
        assertNotNull(tc)
        tc!!
        assertEquals("tab", tc.domain)
        assertEquals("clickTextMatches", tc.method)
        assertEquals("a.link", tc.arguments["0"]) // first arg
        assertEquals("hello, world", tc.arguments["1"]) // quoted comma preserved
        assertEquals("2", tc.arguments["2"]) // third arg
    }

    @Test
        @DisplayName("parse single-quoted arg with escapes")
    fun parseSingleQuotedArgWithEscapes() {
        val src = "tab.fill('#input', 'He said \\\'hi\\\' and \\\\path')"
        val tc = parser.parseFunctionExpression(src)
        assertNotNull(tc)
        tc!!
        assertEquals("tab", tc.domain)
        assertEquals("fill", tc.method)
        assertEquals("#input", tc.arguments["0"]) // unquoted
        assertEquals("He said 'hi' and \\path", tc.arguments["1"]) // unescaped
    }

    @Test
        @DisplayName("parse nested parentheses and comma inside string")
    fun parseNestedParenthesesAndCommaInsideString() {
        val arg = "(function(){ return (1,(2+3)); })()"
        val src = "tab.evaluate(\"$arg\")"
        val tc = parser.parseFunctionExpression(src)
        assertNotNull(tc)
        tc!!
        assertEquals("tab", tc.domain)
        assertEquals("evaluate", tc.method)
        assertEquals(arg, tc.arguments["0"]) // content preserved
    }

    @Test
        @DisplayName("parse trailing comma with one arg")
    fun parseTrailingCommaWithOneArg() {
        val src = "tab.click(\"a.link\",)"
        val tc = parser.parseFunctionExpression(src)
        assertNotNull(tc)
        tc!!
        assertEquals("click", tc.method)
        assertEquals(1, tc.arguments.size)
        assertEquals("a.link", tc.arguments["0"]) // single arg despite trailing comma
    }


    @Test
        @DisplayName("parse mixed whitespace and trailing comma")
    fun parseMixedWhitespaceAndTrailingComma() {
        val src = "tab.scrollToMiddle(   0.75   ,   )"
        val tc = parser.parseFunctionExpression(src)
        assertNotNull(tc)
        tc!!
        assertEquals("scrollToMiddle", tc.method)
        assertEquals(1, tc.arguments.size)
        assertEquals("0.75", tc.arguments["0"]) // trimmed numeric string
    }

    @Test
    fun testParseSimpleFunctionCall_validInput() {
        val input = "tab.open(\"https://t.tt\")"
        val result = parser.parseFunctionExpression(input)
        assertNotNull(result)
        assertEquals("tab", result?.domain)
        assertEquals("open", result?.method)
        assertEquals(mutableMapOf("0" to "https://t.tt"), result?.arguments)
    }

    @Test
    fun testParseSimpleFunctionCall_noArguments() {
        val input = "tab.scrollToTop()"
        val result = parser.parseFunctionExpression(input)
        assertNotNull(result)
        assertEquals("tab", result?.domain)
        assertEquals("scrollToTop", result?.method)
        assertTrue(result?.arguments?.isEmpty() ?: false)
    }

    @Test
    fun testParseSimpleFunctionCall_singleArgument() {
        val input = "tab.scrollToMiddle(0.4)"
        val result = parser.parseFunctionExpression(input)
        assertNotNull(result)
        assertEquals("tab", result?.domain)
        assertEquals("scrollToMiddle", result?.method)
        assertEquals(mutableMapOf("0" to "0.4"), result?.arguments)
    }

    @Test
    fun testParseSimpleFunctionCall_multipleArguments() {
        val input = "tab.mouseWheelUp(2, 200, 200)"
        val result = parser.parseFunctionExpression(input)
        assertNotNull(result)
        assertEquals("tab", result?.domain)
        assertEquals("mouseWheelUp", result?.method)
        assertEquals(mutableMapOf("0" to "2", "1" to "200", "2" to "200"), result?.arguments)
    }

    @Test
    fun testParseSimpleFunctionCall_multipleArgumentsWithSpaces() {
        val input = "tab.mouseWheelUp(2, 200, 200, 100)"
        val result = parser.parseFunctionExpression(input)
        assertNotNull(result)
        assertEquals("tab", result?.domain)
        assertEquals("mouseWheelUp", result?.method)
        assertEquals(mutableMapOf("0" to "2", "1" to "200", "2" to "200", "3" to "100"), result?.arguments)
    }

    @Test
    fun testParseSimpleFunctionCall_invalidInput() {
        val input = "tab.open(\"https://t.tt"
        val result = parser.parseFunctionExpression(input)
        assertNull(result)
    }

    @Test
    fun testParseSimpleFunctionCall_emptyInput() {
        val input = ""
        val result = parser.parseFunctionExpression(input)
        assertNull(result)
    }

    @Test
    fun testParseSimpleFunctionCall_noMethodCall() {
        val input = "tab"
        val result = parser.parseFunctionExpression(input)
        assertNull(result)
    }

    @Test
    fun testParseSimpleFunctionCall_noParentheses() {
        val input = "tab.open"
        val result = parser.parseFunctionExpression(input)
        assertNull(result)
    }

    @Test
    fun testParseSimpleFunctionCall_noObject() {
        val input = ".open(\"https://t.tt\")"
        val result = parser.parseFunctionExpression(input)
        assertNull(result)
    }

    @Test
    fun testParseSimpleFunctionCall_extraSpaces() {
        val input = "  tab  .  open  (  \"https://t.tt\"  )  "
        val result = parser.parseFunctionExpression(input)
        assertNotNull(result)
        assertEquals("tab", result?.domain)
        assertEquals("open", result?.method)
        assertEquals(mutableMapOf("0" to "https://t.tt"), result?.arguments)
    }

    @Test
    fun testParseSimpleFunctionCall_emptyArguments() {
        val input = "tab.open(   )"
        val result = parser.parseFunctionExpression(input)
        assertNotNull(result)
        assertEquals("tab", result?.domain)
        assertEquals("open", result?.method)
        assertTrue(result?.arguments?.isEmpty() ?: false)
    }

    @Test
    fun testParseSimpleFunctionCall_malformedArguments() {
        val input = "tab.open(\"https://t.tt\", )"
        val result = parser.parseFunctionExpression(input)
        assertEquals("tab", result?.domain)
        assertEquals("open", result?.method)
        assertEquals(mutableMapOf("0" to "https://t.tt"), result?.arguments)
    }

    @Test
    fun testParseSimpleFunctionCall_unquotedStringArgument() {
        val input = "tab.open(https://t.tt)"
        val result = parser.parseFunctionExpression(input)
        assertEquals("tab", result?.domain)
        assertEquals("open", result?.method)
        assertEquals(mutableMapOf("0" to "https://t.tt"), result?.arguments)
    }

    @Test
    fun testParseSimpleFunctionCall_specialCharactersInArgument() {
        val input = "tab.open(\"https://t.tt?query=123&param=abc\")"
        val result = parser.parseFunctionExpression(input)
        assertNotNull(result)
        assertEquals("tab", result?.domain)
        assertEquals("open", result?.method)
        assertEquals(mutableMapOf("0" to "https://t.tt?query=123&param=abc"), result?.arguments)
    }

    // Additional focused coverage
    @Test
        @DisplayName("parse input with trailing semicolon")
    fun parseInputWithTrailingSemicolon() {
        val input = "tab.open(\"https://t.tt\");"
        val result = parser.parseFunctionExpression(input)
        assertNotNull(result)
        assertEquals("tab", result?.domain)
        assertEquals("open", result?.method)
        assertEquals(mutableMapOf("0" to "https://t.tt"), result?.arguments)
    }

    @Test
        @DisplayName("parse double-quoted arg with escapes")
    fun parseDoubleQuotedArgWithEscapes() {
        val src = "tab.fill(\"#input\", \"He said \"hi\" and \\path\")"
        val tc = parser.parseFunctionExpression(src)
        assertNotNull(tc)
        tc!!
        assertEquals("tab", tc.domain)
        assertEquals("fill", tc.method)
        assertEquals("#input", tc.arguments["0"]) // unquoted
        assertEquals("He said \"hi\" and \\path", tc.arguments["1"]) // unescaped
    }
}
