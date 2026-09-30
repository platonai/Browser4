package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [ModuleMapSource] — parsing the on-disk ModuleMap.kt so
 * `validate repo-consistency` compares against the file that will be
 * compiled next instead of the (possibly stale) loaded class.
 */
class ModuleMapSourceTest {

    private val fixture = """
        package ai.platon.pulsar.coding

        object ModuleMap {

            /** Known modules (module dirs containing a real pom.xml, incl. aggregators). */
            val MODULES: List<String> = listOf(
                "browser4",
                "browser4-agentic",
                "browser4-plugins",
                "browser4-plugins/browser4-captcha",
                "browser4-plugins/browser4-hello",
                "browser4-rest",
            )

            /** module -> modules that directly depend on it. */
            val DEPENDENTS: Map<String, List<String>> = mapOf(
                "browser4-agentic" to listOf(
                    "browser4-boot",
                    "browser4-plugins/browser4-hello",
                ),
                "browser4" to listOf(
                    "browser4-agentic", "browser4-rest",
                ),
            )

            /** The Rust CLI crate (not a Maven module). */
            const val CLI_CRATE = "browser4-cli"
        }
    """.trimIndent()

    @Test
    @DisplayName("parses MODULES and DEPENDENTS from a well-formed ModuleMap.kt")
    fun parsesWellFormed() {
        val parsed = ModuleMapSource.parse(fixture)
        assertNotNull(parsed)
        assertEquals(
            listOf("browser4", "browser4-agentic", "browser4-plugins",
                "browser4-plugins/browser4-captcha", "browser4-plugins/browser4-hello", "browser4-rest"),
            parsed!!.modules
        )
        assertEquals(
            listOf("browser4-boot", "browser4-plugins/browser4-hello"),
            parsed.dependents["browser4-agentic"]
        )
        assertEquals(listOf("browser4-agentic", "browser4-rest"), parsed.dependents["browser4"])
    }

    @Test
    @DisplayName("missing MODULES or DEPENDENTS block yields null")
    fun missingBlockYieldsNull() {
        assertNull(ModuleMapSource.parse("object X { val A: List<String> = listOf(\"a\") }"))
        assertNull(ModuleMapSource.parse(""))
        assertNull(
            ModuleMapSource.parse(
                "val MODULES: List<String> = listOf(\"a\")\n\n// no DEPENDENTS here"
            )
        )
    }

    @Test
    @DisplayName("parses the real ModuleMap.kt shape (subset with closing paren)")
    fun parsesRealShape() {
        val parsed = ModuleMapSource.parse(fixture)
        assertNotNull(parsed)
        assertTrue(parsed!!.modules.contains("browser4-plugins/browser4-hello"))
        assertTrue(parsed.dependents["browser4-agentic"]!!.contains("browser4-plugins/browser4-hello"))
    }
}
