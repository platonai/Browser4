package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [KotlinSemanticIndexer] — zero-dependency Kotlin symbol/reference
 * extraction. Always runs (no kotlin-compiler-embeddable required).
 */
class KotlinSemanticIndexerTest {

    private val sample = """
        package ai.platon.pulsar.weather.tools

        open class WeatherToolExecutor {

            override val domain = "weather"

            fun fetchWeather(): Any {
                return mapOf("ok" to true)
            }

            fun helper() {
                fetchWeather()
            }
        }
    """.trimIndent()

    @Test
    @DisplayName("symbols finds class, property, and functions")
    fun symbolsFindsDeclarations() {
        val symbols = KotlinSemanticIndexer().symbols(sample, "WeatherToolExecutor.kt")
        assertTrue(symbols.any { it.name == "WeatherToolExecutor" && it.kind == "class" },
            "expected class, got $symbols")
        assertTrue(symbols.any { it.name == "domain" && it.kind == "property" },
            "expected domain property, got $symbols")
        assertTrue(symbols.any { it.name == "fetchWeather" && it.kind == "function" },
            "expected fetchWeather function, got $symbols")
        assertTrue(symbols.any { it.name == "helper" && it.kind == "function" },
            "expected helper function, got $symbols")
    }

    @Test
    @DisplayName("symbols reports 1-based line numbers")
    fun symbolsLineNumbers() {
        val symbols = KotlinSemanticIndexer().symbols(sample)
        val weather = symbols.first { it.name == "WeatherToolExecutor" }
        assertEquals(2, weather.line, "class declared on line 2 (after package)")
        val domain = symbols.first { it.name == "domain" }
        assertEquals(4, domain.line)
    }

    @Test
    @DisplayName("symbols handles interfaces, objects, and suspend functions")
    fun symbolsKinds() {
        val src = """
            interface Repository { }
            object Registry { }
            suspend fun load() { }
        """.trimIndent()
        val symbols = KotlinSemanticIndexer().symbols(src)
        assertTrue(symbols.any { it.name == "Repository" && it.kind == "interface" })
        assertTrue(symbols.any { it.name == "Registry" && it.kind == "object" })
        assertTrue(symbols.any { it.name == "load" && it.kind == "function" })
    }

    @Test
    @DisplayName("references finds call sites and property usages")
    fun referencesFindsCalls() {
        val refs = KotlinSemanticIndexer().references(sample, "fetchWeather", "WeatherToolExecutor.kt")
        assertTrue(refs.any { it.snippet.contains("fetchWeather()") },
            "expected fetchWeather() call site, got $refs")
    }

    @Test
    @DisplayName("references does not count the declaration as a reference")
    fun referencesExcludesDeclaration() {
        val refs = KotlinSemanticIndexer().references(sample, "fetchWeather")
        // Only the call inside helper() should match — the `fun fetchWeather` line is excluded.
        assertTrue(refs.size == 1, "expected exactly 1 reference (the call), got $refs")
    }

    @Test
    @DisplayName("references finds receiver-style usages")
    fun referencesReceiverStyle() {
        val src = """
            val w = WeatherToolExecutor()
            val d = w.domain
        """.trimIndent()
        val refs = KotlinSemanticIndexer().references(src, "domain")
        assertTrue(refs.any { it.snippet.contains(".domain") })
    }

    @Test
    @DisplayName("references returns empty for unknown symbol")
    fun referencesUnknownSymbol() {
        assertTrue(KotlinSemanticIndexer().references(sample, "doesNotExist").isEmpty())
    }

    @Test
    @DisplayName("available probes the optional embeddable backend without throwing")
    fun availabilityProbe() {
        // Must not throw regardless of classpath.
        assertNotNull(KotlinSemanticIndexer.available)
    }

    // ==================== cross-file references ====================

    private val files: Map<String, String> = linkedMapOf(
        "src/main/Executor.kt" to sample,
        "src/main/Caller.kt" to """
            package ai.platon.pulsar.weather.tools

            open class Caller {
                fun run() {
                    val e = WeatherToolExecutor()
                    e.fetchWeather()
                }
            }
        """.trimIndent(),
        "src/main/Other.kt" to """
            package ai.platon.pulsar.weather.tools

            fun helper() {
                println("no reference here")
            }
        """.trimIndent(),
    )

    @Test
    @DisplayName("referencesInFiles finds usages across files, excluding the declaring file")
    fun referencesAcrossFiles() {
        val refs = KotlinSemanticIndexer().referencesInFiles(files, "fetchWeather")
        assertEquals(1, refs.size, "expected only the caller's usage, got $refs")
        assertEquals("src/main/Caller.kt", refs[0].path)
        assertTrue(refs[0].snippet.contains("e.fetchWeather()"), refs[0].snippet)
    }

    @Test
    @DisplayName("referencesInFiles with exclude=false includes the declaring file's call sites")
    fun referencesIncludingDeclaringFile() {
        val refs = KotlinSemanticIndexer().referencesInFiles(files, "fetchWeather", excludeDeclaringFiles = false)
        // Caller.kt usage + Executor.kt's internal helper() call (declaration itself is excluded by references()).
        assertEquals(2, refs.size, "got $refs")
    }

    @Test
    @DisplayName("referencesInFiles returns empty for unknown symbols")
    fun referencesAcrossFilesUnknown() {
        assertTrue(KotlinSemanticIndexer().referencesInFiles(files, "doesNotExist").isEmpty())
    }

    // ==================== inheritance chain ====================

    @Test
    @DisplayName("inheritanceChain walks the primary supertype across files")
    fun inheritanceChainAcrossFiles() {
        val src = mapOf(
            "Base.kt" to "open class AbstractToolExecutor { }\n",
            "Impl.kt" to "open class SeoToolExecutor : AbstractToolExecutor() {\n    override val domain = \"seo\"\n}\n",
        )
        val chain = KotlinSemanticIndexer().inheritanceChain(src, "SeoToolExecutor")
        assertEquals(listOf("SeoToolExecutor", "AbstractToolExecutor"), chain)
    }

    @Test
    @DisplayName("inheritanceChain handles generic supertypes and stops when no parent")
    fun inheritanceChainGenericsAndStop() {
        val src = mapOf(
            "A.kt" to "class A : Base<String>\n",
            "B.kt" to "open class Base<T> { }\n",
        )
        assertEquals(listOf("A", "Base"), KotlinSemanticIndexer().inheritanceChain(src, "A"))
        // No parent → single-element chain.
        assertEquals(listOf("Base"), KotlinSemanticIndexer().inheritanceChain(src, "Base"))
    }
}
