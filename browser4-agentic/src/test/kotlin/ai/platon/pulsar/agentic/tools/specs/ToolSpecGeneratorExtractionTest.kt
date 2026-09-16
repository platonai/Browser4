package ai.platon.pulsar.agentic.tools.specs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Specs extracted from source (`WebDriver.kt`, `PerceptiveAgent.kt`) drive the
 * JSON Schema of every generated tool, so their default/type fidelity is part of
 * the interface contract.
 */
@DisplayName("ToolSpecGenerator interface extraction")
class ToolSpecGeneratorExtractionTest {

    private val source = """
        interface Sample {
            /**
             * Navigate somewhere.
             */
            @MCP
            suspend fun noDefault(url: String)

            /**
             * Walk a few levels.
             */
            @MCP
            suspend fun withIntDefault(depth: Int = 3)

            /**
             * Greet someone.
             */
            @MCP
            suspend fun withStringDefault(name: String = "hi")

            /**
             * Optional value.
             */
            @MCP
            suspend fun withNullable(value: String? = null)
        }
    """.trimIndent()

    private fun specs() = ToolSpecGenerator.extractInterface("sample", source, "Sample").associateBy { it.method }

    @Test
    @DisplayName("an argument without a source default stays required")
    fun absentDefaultStaysAbsent() {
        val arg = specs()["noDefault"]!!.arguments.single()

        assertNull(arg.defaultValue, "A parameter with no default must not be marked optional")
        assertEquals("url: String", arg.expression)
        assertEquals("--url=<String>", arg.cliOptions)
    }

    @Test
    @DisplayName("declared defaults are kept, with string defaults unquoted")
    fun declaredDefaultsAreKept() {
        val byMethod = specs()

        assertEquals("3", byMethod["withIntDefault"]!!.arguments.single().defaultValue)
        assertEquals("hi", byMethod["withStringDefault"]!!.arguments.single().defaultValue)
        assertEquals("depth: Int = 3", byMethod["withIntDefault"]!!.arguments.single().expression)
    }

    @Test
    @DisplayName("an explicit null default keeps the argument optional")
    fun explicitNullDefaultIsOptional() {
        val arg = specs()["withNullable"]!!.arguments.single()

        assertEquals("null", arg.defaultValue)
        assertEquals("value: String? = null", arg.expression)
    }

    @Test
    @DisplayName("the rendered signature is callable text, never a data-class dump")
    fun signatureIsCallableText() {
        val byMethod = specs()

        assertEquals("sample.noDefault(url: String)", byMethod["noDefault"]!!.expression)
        assertEquals(
            "sample.withIntDefault(depth: Int = 3)",
            byMethod["withIntDefault"]!!.expression
        )
        assertFalse(
            byMethod.values.any { it.expression.contains("Arg(") },
            "Signatures must not leak the JVM data-class form: ${byMethod.values.map { it.expression }}"
        )
        assertTrue(byMethod.values.all { it.description?.isNotBlank() == true }, "Every spec needs a description")
    }
}
