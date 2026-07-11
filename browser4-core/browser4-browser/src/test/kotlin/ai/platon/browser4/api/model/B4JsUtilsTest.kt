package ai.platon.browser4.api.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import ai.platon.pulsar.common.js.JsUtils
import org.junit.jupiter.api.Test

class JsUtilsTest {

    // ── toCDPCompatibleExpression ─────────────────────────────────────────────

    @Test
    @DisplayName("toCDPCompatibleExpression strips leading return keyword with space")
    fun toCdpCompatibleExpressionStripsLeadingReturnKeywordWithSpace() {
        val result = JsUtils.toCDPCompatibleExpression("return document.title")
        assertEquals("document.title", result)
    }

    @Test
    @DisplayName("toCDPCompatibleExpression strips leading return keyword followed by parenthesis")
    fun toCdpCompatibleExpressionStripsLeadingReturnFollowedByParenthesis() {
        // `return(expr)` → strip `return` → `(expr)` is a plain parenthesised expression, not a function call
        val result = JsUtils.toCDPCompatibleExpression("return(document.title)")
        assertEquals("(document.title);", result)
    }

    @Test
    @DisplayName("toCDPCompatibleExpression does not strip return inside an identifier")
    fun toCdpCompatibleExpressionDoesNotStripReturnInsideIdentifier() {
        val result = JsUtils.toCDPCompatibleExpression("returnValue")
        assertEquals("returnValue", result)
    }

    @Test
    @DisplayName("toCDPCompatibleExpression returns empty string for blank input")
    fun toCdpCompatibleExpressionReturnsEmptyForBlankInput() {
        assertEquals("", JsUtils.toCDPCompatibleExpression(""))
        assertEquals("", JsUtils.toCDPCompatibleExpression("   "))
        assertEquals("", JsUtils.toCDPCompatibleExpression("  \n  "))
    }

    @Test
    @DisplayName("toCDPCompatibleExpression wraps single-line bare arrow function as IIFE")
    fun toCdpCompatibleExpressionWrapsSingleLineArrowAsIife() {
        val result = JsUtils.toCDPCompatibleExpression("x => x * 2")
        assertEquals("(x => x * 2)();", result)
    }

    @Test
    @DisplayName("toCDPCompatibleExpression does not wrap assignment expression containing arrow as IIFE")
    fun toCdpCompatibleExpressionDoesNotWrapAssignmentContainingArrow() {
        // `const fn = x => x` is a statement, not a bare function expression
        val result = JsUtils.toCDPCompatibleExpression("const fn = x => x")
        assertEquals("const fn = x => x", result)
    }

    @Test
    @DisplayName("toCDPCompatibleExpression keeps async-prefixed calls as plain calls")
    fun toCdpCompatibleExpressionKeepsAsyncPrefixedCallsAsPlainCalls() {
        val result = JsUtils.toCDPCompatibleExpression("asyncOperation()")
        assertEquals("asyncOperation()", result)
    }

    // ── toExpression ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("toExpression strips leading return keyword")
    fun toExpressionStripsLeadingReturnKeyword() {
        assertEquals("document.title", JsUtils.toExpression("return document.title"))
    }

    @Test
    @DisplayName("toExpression strips leading return followed by parenthesis")
    fun toExpressionStripsLeadingReturnFollowedByParenthesis() {
        // Single-line: toExpression returns the trimmed line as-is (no semicolon appended)
        assertEquals("(x + 1)", JsUtils.toExpression("return(x + 1)"))
    }

    @Test
    @DisplayName("toExpression returns empty string for blank input")
    fun toExpressionReturnsEmptyForBlankInput() {
        assertEquals("", JsUtils.toExpression(""))
        assertEquals("", JsUtils.toExpression("   \n  "))
    }

    @Test
    @DisplayName("toExpression returns single trimmed line unchanged")
    fun toExpressionReturnsSingleLineTrimmed() {
        assertEquals("1 + 2", JsUtils.toExpression("  1 + 2  "))
    }

    // ── toIIFE ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toIIFE wraps named function declaration as IIFE")
    fun toIifeWrapsNamedFunctionDeclaration() {
        val result = JsUtils.toIIFE("function() { return 42 }")
        assertEquals("(function() { return 42 })();", result)
    }

    @Test
    @DisplayName("toIIFE wraps arrow function as IIFE with args")
    fun toIifeWrapsArrowFunctionWithArgs() {
        val result = JsUtils.toIIFE("x => x * 2", "5")
        assertEquals("(x => x * 2)(5);", result)
    }

    @Test
    @DisplayName("toIIFE returns empty string and does not throw for unrecognised input")
    fun toIifeReturnsEmptyStringForUnrecognisedInput() {
        val result = JsUtils.toIIFE("const a = 1;\nconst b = 2;\nreturn a + b;")
        assertEquals("", result)
    }

    // ── toIIFEOrNull ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("toIIFEOrNull returns null for plain statement sequence")
    fun toIifeOrNullReturnsNullForStatementSequence() {
        assertNull(JsUtils.toIIFEOrNull("const a = 1;\nconst b = 2;\nreturn a + b;"))
    }

    @Test
    @DisplayName("toIIFEOrNull does not double-wrap an already-invoked IIFE")
    fun toIifeOrNullDoesNotDoubleWrapAlreadyInvokedIife() {
        val iife = "(() => { return 3 })()"
        val result = JsUtils.toIIFEOrNull(iife)
        assertNotNull(result)
        assertTrue(result!!.trimEnd().endsWith(";"))
        assertTrue(!result.contains("()()"))
    }

    @Test
    @DisplayName("toIIFEOrNull recognises already-invoked IIFE with nested parens in args")
    fun toIifeOrNullRecognisesIifeWithNestedParensInArgs() {
        val iife = "(fn)(someFunc(x))"
        val result = JsUtils.toIIFEOrNull(iife)
        assertNotNull(result)
        // Should be normalised (trailing semicolon), not re-wrapped
        assertTrue(result!!.startsWith("(fn)"))
    }

    @Test
    @DisplayName("toIIFEOrNull wraps bare single-param arrow function")
    fun toIifeOrNullWrapsBareArrowFunction() {
        assertEquals("(x => x * 2)(5);", JsUtils.toIIFEOrNull("x => x * 2", "5"))
    }

    @Test
    @DisplayName("toIIFEOrNull does not wrap assignment expression containing arrow")
    fun toIifeOrNullDoesNotWrapAssignmentContainingArrow() {
        assertNull(JsUtils.toIIFEOrNull("const fn = x => x"))
    }

    @Test
    @DisplayName("toIIFEOrNull does not wrap async-prefixed calls")
    fun toIifeOrNullDoesNotWrapAsyncPrefixedCalls() {
        assertNull(JsUtils.toIIFEOrNull("asyncOperation()"))
    }

    @Test
    @DisplayName("toIIFEOrNull wraps raw object literal as expression")
    fun toIifeOrNullWrapsObjectLiteralAsExpression() {
        val obj = "{ answer: 42, label: 'ok' }"
        val result = JsUtils.toIIFEOrNull(obj)
        assertNotNull(result)
        assertEquals("({ answer: 42, label: 'ok' });", result)
    }

    @Test
    @DisplayName("toIIFEOrNull returns null for code block starting with brace containing semicolon")
    fun toIifeOrNullReturnsNullForCodeBlockWithSemicolon() {
        assertNull(JsUtils.toIIFEOrNull("{ const x = 1; return x; }"))
    }

    @Test
    @DisplayName("toIIFEOrNull returns null for code block starting with brace containing return keyword")
    fun toIifeOrNullReturnsNullForCodeBlockWithReturn() {
        assertNull(JsUtils.toIIFEOrNull("{ return document.title }"))
    }

    @Test
    @DisplayName("toIIFEOrNull wraps async function expression as IIFE")
    fun toIifeOrNullWrapsAsyncArrowFunction() {
        val result = JsUtils.toIIFEOrNull("async () => { return 1 }")
        assertNotNull(result)
        assertTrue(result!!.startsWith("(async () => { return 1 })"))
    }

    @Test
    @DisplayName("toIIFEOrNull wraps async function declaration as IIFE")
    fun toIifeOrNullWrapsAsyncFunctionDeclaration() {
        val result = JsUtils.toIIFEOrNull("async function() { return 1 }")
        assertNotNull(result)
        assertEquals("(async function() { return 1 })();", result)
    }

    @Test
    @DisplayName("toIIFEOrNull wraps parenthesised function expression as IIFE")
    fun toIifeOrNullWrapsParenthesisedFunctionExpression() {
        val result = JsUtils.toIIFEOrNull("(function(){ return 2 * 3 })")
        assertNotNull(result)
        assertEquals("((function(){ return 2 * 3 }))();", result)
    }
}
