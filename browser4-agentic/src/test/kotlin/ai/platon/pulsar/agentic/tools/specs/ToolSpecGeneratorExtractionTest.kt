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
    @DisplayName("the advertised overload is the one a JSON client can actually call")
    fun jsonFriendlyOverloadIsPreferredOverSourceOrder() {
        // The Kotlin-only form is declared last, so plain "last wins" would advertise
        // a required `NavigateEntry` — an argument no MCP client can send.
        val source = """
            interface Overloaded {
                /**
                 * Navigate to a URL.
                 */
                @MCP
                suspend fun navigate(url: String)

                /**
                 * Navigate with the driver's entry object.
                 */
                @MCP
                suspend fun navigate(entry: NavigateEntry)
            }
        """.trimIndent()
        val extracted = ToolSpecGenerator.extractInterface("overloaded", source, "Overloaded")
        val advertised = ToolSpecGenerator.selectPreferred(extracted)

        assertEquals("String", advertised["navigate"]!!.arguments.single().type)
        assertEquals(
            "overloaded.navigate(url: String)",
            advertised["navigate"]!!.expression,
            "the JSON-friendly overload must win regardless of declaration order",
        )
    }

    @Test
    @DisplayName("equally friendly overloads keep the last declaration")
    fun lastDeclarationWinsBetweenFriendlyOverloads() {
        val source = """
            interface Overloaded {
                /**
                 * Read the text.
                 */
                @MCP
                suspend fun text(selector: String)

                /**
                 * Read the text with a timeout.
                 */
                @MCP
                suspend fun text(selector: String, timeoutMillis: Long = 1000)
            }
        """.trimIndent()
        val advertised = ToolSpecGenerator.selectPreferred(
            ToolSpecGenerator.extractInterface("overloaded", source, "Overloaded"),
        )

        assertEquals(2, advertised["text"]!!.arguments.size, "the richer overload stays advertised")
    }

    @Test
    @DisplayName("an overload whose Kotlin-only argument is optional beats a required one")
    fun unfriendlyOverloadsFallBackToSourceOrder() {
        val source = """
            interface Overloaded {
                /**
                 * Wait for the given duration.
                 */
                @MCP
                suspend fun delay(duration: Duration)

                /**
                 * Wait for the given duration.
                 */
                @MCP
                suspend fun delay(millis: Long = 1000)
            }
        """.trimIndent()
        val advertised = ToolSpecGenerator.selectPreferred(
            ToolSpecGenerator.extractInterface("overloaded", source, "Overloaded"),
        )

        // `millis` carries a default, so it is not a *required* non-JSON argument:
        // this overload is friendly and wins over `duration`, which is not.
        assertEquals("millis", advertised["delay"]!!.arguments.single().name)
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
