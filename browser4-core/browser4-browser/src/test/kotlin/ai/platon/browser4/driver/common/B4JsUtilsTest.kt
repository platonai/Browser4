package ai.platon.browser4.driver.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class B4JsUtilsTest {

    @Test
    @DisplayName("toCDPCompatibleExpression keeps grouped expressions unchanged")
    fun tocdpcompatibleexpressionKeepsGroupedExpressionsUnchanged() {
        assertEquals("(1 + 2)", B4JsUtils.toCDPCompatibleExpression("(1 + 2)"))
    }

    @Test
    @DisplayName("toCDPCompatibleExpression keeps async calls unchanged")
    fun tocdpcompatibleexpressionKeepsAsyncCallsUnchanged() {
        assertEquals("asyncOperation()", B4JsUtils.toCDPCompatibleExpression("asyncOperation()"))
    }

    @Test
    @DisplayName("toCDPCompatibleExpression ignores string literals containing arrow syntax")
    fun tocdpcompatibleexpressionIgnoresStringLiteralsContainingArrowSyntax() {
        val script = "console.log(\"a => b\")"
        assertEquals(script, B4JsUtils.toCDPCompatibleExpression(script))
    }

    @Test
    @DisplayName("toCDPCompatibleExpression converts return object literals into expressions")
    fun tocdpcompatibleexpressionConvertsReturnObjectLiteralsIntoExpressions() {
        assertEquals("({ answer: 42 });", B4JsUtils.toCDPCompatibleExpression("return { answer: 42 }"))
    }

    @Test
    @DisplayName("toIIFEOrNull wraps async arrow functions")
    fun toiifeornullWrapsAsyncArrowFunctions() {
        assertEquals("(async () => 42)();", B4JsUtils.toIIFEOrNull("async () => 42"))
    }

    @Test
    @DisplayName("toIIFEOrNull wraps parenthesized function expressions")
    fun toiifeornullWrapsParenthesizedFunctionExpressions() {
        assertEquals(
            "((function() { return 42; }))();",
            B4JsUtils.toIIFEOrNull("(function() { return 42; })")
        )
    }

    @Test
    @DisplayName("toIIFEOrNull keeps parenthesized object literals as expressions")
    fun toiifeornullKeepsParenthesizedObjectLiteralsAsExpressions() {
        assertEquals("({ answer: 42 });", B4JsUtils.toIIFEOrNull("({ answer: 42 })"))
    }

    @Test
    @DisplayName("toIIFEOrNull returns null for non callable statements")
    fun toiifeornullReturnsNullForNonCallableStatements() {
        assertNull(B4JsUtils.toIIFEOrNull("const value = 42"))
    }
}

