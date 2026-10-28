package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.api.model.JsEvaluation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A JS result the model cannot use must say so.
 *
 * The CLI only receives rendered TEXT (`MCPToolController` → `ToolResultTextRenderer`),
 * so `cdpType`/`cdpSubtype` were lost before any client saw them: an un-awaited
 * promise arrived as `{}` and an `undefined` as a bare `null`. Three `eval`s of a
 * 20-page batch silently lost their data in the 2026-10-28 run.
 */
@DisplayName("JS evaluation feedback")
class JsEvaluationFeedbackTest {

    private class EvalExecutor(private val evaluation: JsEvaluation) : AbstractToolExecutor() {
        override val domain = "tab"
        override val receiverClass: kotlin.reflect.KClass<*> = EvalExecutor::class
        override suspend fun callFunctionOn(
            domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
        ): Any = evaluation
    }

    private fun evaluate(evaluation: JsEvaluation): TcEvaluate = runBlocking {
        EvalExecutor(evaluation).callFunctionOn(
            ToolCall(domain = "tab", method = "eval", arguments = mutableMapOf("expression" to "x")),
            Any(),
        )
    }

    @Test
    @DisplayName("an unresolved Promise fails with an --await instruction")
    fun promiseIsAFailure() {
        val evaluated = evaluate(JsEvaluation(value = null, cdpType = "object", cdpSubtype = "promise"))

        assertFalse(evaluated.success, "a Promise is not a result")
        val text = evaluated.value?.toString().orEmpty()
        assertTrue(text.contains("Promise"), text)
        assertTrue(text.contains("--await"), "the fix must be named: $text")
        assertNotNull(evaluated.exception)
    }

    @Test
    @DisplayName("an undefined result explains how to return a value")
    fun undefinedCarriesANotice() {
        val evaluated = evaluate(JsEvaluation(value = null, cdpType = "undefined"))

        // Still a success: side-effecting expressions legitimately return undefined.
        assertTrue(evaluated.success)
        val text = evaluated.value?.toString().orEmpty()
        assertTrue(text.startsWith("null"), "text consumers key on the literal null: $text")
        assertTrue(text.contains("undefined"), text)
        assertTrue(text.contains("return"), "the fix must be named: $text")
    }

    @Test
    @DisplayName("a real value is untouched")
    fun realValueIsUnchanged() {
        val evaluated = evaluate(JsEvaluation(value = "B0ABC123", cdpType = "string"))

        assertTrue(evaluated.success)
        assertEquals("B0ABC123", evaluated.value)
        assertNull(evaluated.exception)
    }

    @Test
    @DisplayName("a JSON object that merely looks like a status is untouched")
    fun plainObjectIsUnchanged() {
        val value = mapOf("status" to "error", "message" to "per-item")
        val evaluated = evaluate(JsEvaluation(value = value, cdpType = "object"))

        assertTrue(evaluated.success)
        assertEquals(value, evaluated.value)
    }
}
