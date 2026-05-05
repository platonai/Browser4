package ai.platon.browser4.driver.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class B4JsUtilsTest {

    // ── toCDPCompatibleExpression ─────────────────────────────────────────────

    @Test
    @DisplayName("toCDPCompatibleExpression strips leading return keyword with space")
    fun toCdpCompatibleExpressionStripsLeadingReturnKeywordWithSpace() {
        val result = B4JsUtils.toCDPCompatibleExpression("return document.title")
        assertEquals("document.title", result)
    }

    @Test
    @DisplayName("toCDPCompatibleExpression strips leading return keyword followed by parenthesis")
    fun toCdpCompatibleExpressionStripsLeadingReturnFollowedByParenthesis() {
        // `return(expr)` → strip `return` → `(expr)` is a plain parenthesised expression, not a function call
        val result = B4JsUtils.toCDPCompatibleExpression("return(document.title)")
        assertEquals("(document.title);", result)
    }

    @Test
    @DisplayName("toCDPCompatibleExpression does not strip return inside an identifier")
    fun toCdpCompatibleExpressionDoesNotStripReturnInsideIdentifier() {
        val result = B4JsUtils.toCDPCompatibleExpression("returnValue")
        assertEquals("returnValue", result)
    }

    @Test
    @DisplayName("toCDPCompatibleExpression returns empty string for blank input")
    fun toCdpCompatibleExpressionReturnsEmptyForBlankInput() {
        assertEquals("", B4JsUtils.toCDPCompatibleExpression(""))
        assertEquals("", B4JsUtils.toCDPCompatibleExpression("   "))
        assertEquals("", B4JsUtils.toCDPCompatibleExpression("  \n  "))
    }

    @Test
    @DisplayName("toCDPCompatibleExpression wraps single-line bare arrow function as IIFE")
    fun toCdpCompatibleExpressionWrapsSingleLineArrowAsIife() {
        val result = B4JsUtils.toCDPCompatibleExpression("x => x * 2")
        assertEquals("(x => x * 2)();", result)
    }

    @Test
    @DisplayName("toCDPCompatibleExpression does not wrap assignment expression containing arrow as IIFE")
    fun toCdpCompatibleExpressionDoesNotWrapAssignmentContainingArrow() {
        // `const fn = x => x` is a statement, not a bare function expression
        val result = B4JsUtils.toCDPCompatibleExpression("const fn = x => x")
        assertEquals("const fn = x => x", result)
    }

    @Test
    @DisplayName("toCDPCompatibleExpression keeps async-prefixed calls as plain calls")
    fun toCdpCompatibleExpressionKeepsAsyncPrefixedCallsAsPlainCalls() {
        val result = B4JsUtils.toCDPCompatibleExpression("asyncOperation()")
        assertEquals("asyncOperation()", result)
    }

    // ── toExpression ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("toExpression strips leading return keyword")
    fun toExpressionStripsLeadingReturnKeyword() {
        assertEquals("document.title", B4JsUtils.toExpression("return document.title"))
    }

    @Test
    @DisplayName("toExpression strips leading return followed by parenthesis")
    fun toExpressionStripsLeadingReturnFollowedByParenthesis() {
        // Single-line: toExpression returns the trimmed line as-is (no semicolon appended)
        assertEquals("(x + 1)", B4JsUtils.toExpression("return(x + 1)"))
    }

    @Test
    @DisplayName("toExpression returns empty string for blank input")
    fun toExpressionReturnsEmptyForBlankInput() {
        assertEquals("", B4JsUtils.toExpression(""))
        assertEquals("", B4JsUtils.toExpression("   \n  "))
    }

    @Test
    @DisplayName("toExpression returns single trimmed line unchanged")
    fun toExpressionReturnsSingleLineTrimmed() {
        assertEquals("1 + 2", B4JsUtils.toExpression("  1 + 2  "))
    }

    // ── toIIFE ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toIIFE wraps named function declaration as IIFE")
    fun toIifeWrapsNamedFunctionDeclaration() {
        val result = B4JsUtils.toIIFE("function() { return 42 }")
        assertEquals("(function() { return 42 })();", result)
    }

    @Test
    @DisplayName("toIIFE wraps arrow function as IIFE with args")
    fun toIifeWrapsArrowFunctionWithArgs() {
        val result = B4JsUtils.toIIFE("x => x * 2", "5")
        assertEquals("(x => x * 2)(5);", result)
    }

    @Test
    @DisplayName("toIIFE returns empty string and does not throw for unrecognised input")
    fun toIifeReturnsEmptyStringForUnrecognisedInput() {
        val result = B4JsUtils.toIIFE("const a = 1;\nconst b = 2;\nreturn a + b;")
        assertEquals("", result)
    }

    // ── toIIFEOrNull ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("toIIFEOrNull returns null for plain statement sequence")
    fun toIifeOrNullReturnsNullForStatementSequence() {
        assertNull(B4JsUtils.toIIFEOrNull("const a = 1;\nconst b = 2;\nreturn a + b;"))
    }

    @Test
    @DisplayName("toIIFEOrNull does not double-wrap an already-invoked IIFE")
    fun toIifeOrNullDoesNotDoubleWrapAlreadyInvokedIife() {
        val iife = "(() => { return 3 })()"
        val result = B4JsUtils.toIIFEOrNull(iife)
        assertNotNull(result)
        assertTrue(result!!.trimEnd().endsWith(";"))
        assertTrue(!result.contains("()()"))
    }

    @Test
    @DisplayName("toIIFEOrNull recognises already-invoked IIFE with nested parens in args")
    fun toIifeOrNullRecognisesIifeWithNestedParensInArgs() {
        val iife = "(fn)(someFunc(x))"
        val result = B4JsUtils.toIIFEOrNull(iife)
        assertNotNull(result)
        // Should be normalised (trailing semicolon), not re-wrapped
        assertTrue(result!!.startsWith("(fn)"))
    }

    @Test
    @DisplayName("toIIFEOrNull wraps bare single-param arrow function")
    fun toIifeOrNullWrapsBareArrowFunction() {
        assertEquals("(x => x * 2)(5);", B4JsUtils.toIIFEOrNull("x => x * 2", "5"))
    }

    @Test
    @DisplayName("toIIFEOrNull does not wrap assignment expression containing arrow")
    fun toIifeOrNullDoesNotWrapAssignmentContainingArrow() {
        assertNull(B4JsUtils.toIIFEOrNull("const fn = x => x"))
    }

    @Test
    @DisplayName("toIIFEOrNull does not wrap async-prefixed calls")
    fun toIifeOrNullDoesNotWrapAsyncPrefixedCalls() {
        assertNull(B4JsUtils.toIIFEOrNull("asyncOperation()"))
    }

    @Test
    @DisplayName("toIIFEOrNull wraps raw object literal as expression")
    fun toIifeOrNullWrapsObjectLiteralAsExpression() {
        val obj = "{ answer: 42, label: 'ok' }"
        val result = B4JsUtils.toIIFEOrNull(obj)
        assertNotNull(result)
        assertEquals("({ answer: 42, label: 'ok' });", result)
    }

    @Test
    @DisplayName("toIIFEOrNull returns null for code block starting with brace containing semicolon")
    fun toIifeOrNullReturnsNullForCodeBlockWithSemicolon() {
        assertNull(B4JsUtils.toIIFEOrNull("{ const x = 1; return x; }"))
    }

    @Test
    @DisplayName("toIIFEOrNull returns null for code block starting with brace containing return keyword")
    fun toIifeOrNullReturnsNullForCodeBlockWithReturn() {
        assertNull(B4JsUtils.toIIFEOrNull("{ return document.title }"))
    }

    @Test
    @DisplayName("toIIFEOrNull wraps async function expression as IIFE")
    fun toIifeOrNullWrapsAsyncArrowFunction() {
        val result = B4JsUtils.toIIFEOrNull("async () => { return 1 }")
        assertNotNull(result)
        assertTrue(result!!.startsWith("(async () => { return 1 })"))
    }

    @Test
    @DisplayName("toIIFEOrNull wraps async function declaration as IIFE")
    fun toIifeOrNullWrapsAsyncFunctionDeclaration() {
        val result = B4JsUtils.toIIFEOrNull("async function() { return 1 }")
        assertNotNull(result)
        assertEquals("(async function() { return 1 })();", result)
    }

    @Test
    @DisplayName("toIIFEOrNull wraps parenthesised function expression as IIFE")
    fun toIifeOrNullWrapsParenthesisedFunctionExpression() {
        val result = B4JsUtils.toIIFEOrNull("(function(){ return 2 * 3 })")
        assertNotNull(result)
        assertEquals("((function(){ return 2 * 3 }))();", result)
    }
}
