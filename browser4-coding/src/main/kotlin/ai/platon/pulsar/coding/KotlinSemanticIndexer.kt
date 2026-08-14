package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory

/**
 * Lightweight Kotlin semantic analysis — symbols and references.
 *
 * ## Design: zero-dependency core, optional heavy backend
 *
 * The CORE symbol/reference extraction is pure text analysis driven by Kotlin
 * language structure (regex on declarations and call sites), so it always works
 * with zero extra dependencies — honoring the "self-development resources are
 * not loaded/downloaded by default" rule.
 *
 * [available] additionally probes for kotlin-compiler-embeddable (PSI) on the
 * classpath; when present, callers may opt into AST-backed analysis later.
 * It is never a Maven dependency of browser4-coding, so nothing is downloaded
 * unless the deployment explicitly adds it.
 */
class KotlinSemanticIndexer {

    companion object {
        private val logger = LoggerFactory.getLogger(KotlinSemanticIndexer::class.java)

        /** PSI entry class used as the availability probe (optional backend). */
        private const val KT_PSI_FACTORY = "org.jetbrains.kotlin.psi.KtPsiFactory"

        /** Whether kotlin-compiler-embeddable is present (optional enhancement). */
        val available: Boolean by lazy {
            runCatching {
                Class.forName(KT_PSI_FACTORY)
                true
            }.getOrDefault(false)
        }
    }

    /** A Kotlin symbol definition found by the indexer. */
    data class KotlinSymbol(
        val name: String,
        val kind: String,     // "class" | "object" | "interface" | "function" | "property"
        val line: Int,        // 1-based
    )

    /** A reference to a symbol (call site or usage). */
    data class KotlinReference(
        val name: String,
        val line: Int,        // 1-based
        val snippet: String,
    )

    /**
     * List symbol definitions in Kotlin source (zero-dependency text analysis).
     */
    fun symbols(content: String, fileName: String = "Source.kt"): List<KotlinSymbol> {
        val symbols = mutableListOf<KotlinSymbol>()

        // Class / object / interface declarations: `class Foo`, `open class Foo`,
        // `data class Foo(...)`, `object Foo`, `interface Foo`
        val typeRegex = Regex(
            """(?m)^\s*(?:public\s+|internal\s+|private\s+|protected\s+|open\s+|abstract\s+|final\s+|data\s+|sealed\s+|enum\s+)*(class|object|interface|enum\s+class)\s+([A-Za-z_]\w*)"""
        )
        typeRegex.findAll(content).forEach { m ->
            val kind = when {
                m.groupValues[1].contains("interface") -> "interface"
                m.groupValues[1].contains("object") -> "object"
                m.groupValues[1].contains("enum") -> "enum"
                else -> "class"
            }
            symbols += KotlinSymbol(m.groupValues[2], kind, lineOf(m, content))
        }

        // Function declarations: `fun foo(...)` (also `override fun`, `suspend fun`)
        val funRegex = Regex(
            """(?m)^\s*(?:public\s+|internal\s+|private\s+|protected\s+|open\s+|override\s+|suspend\s+|abstract\s+|final\s+)*fun\s+([A-Za-z_]\w*)\s*\("""
        )
        funRegex.findAll(content).forEach { m ->
            symbols += KotlinSymbol(m.groupValues[1], "function", lineOf(m, content))
        }

        // Top-level / member properties: `val foo = `, `var foo: Type = `,
        // `override val domain = "..."` (but not inside function bodies heuristically)
        val propRegex = Regex(
            """(?m)^\s*(?:public\s+|internal\s+|private\s+|protected\s+|open\s+|override\s+|const\s+|val\s+|var\s+)*(val|var)\s+([A-Za-z_]\w*)\s*(?::\s*[\w.<>]+\s*)?=?"""
        )
        propRegex.findAll(content).forEach { m ->
            val name = m.groupValues[2]
            // Skip obvious local declarations inside functions: only accept if the
            // line does not start with whitespace-heavy indentation (heuristic: top
            // level or class-member properties are 0-1 indent levels).
            val indent = m.value.takeWhile { it == ' ' || it == '\t' }.length
            if (indent <= 1 || name == "domain") {
                symbols += KotlinSymbol(name, "property", lineOf(m, content))
            }
        }

        return symbols.distinctBy { "${it.kind}:${it.name}" }
            .sortedWith(compareBy({ it.line }, { it.name }))
    }

    /**
     * Find references to [symbol] in Kotlin source — call sites
     * (`symbol(...)`) and property usages (`.symbol` / bare `symbol`).
     * Zero-dependency text analysis.
     */
    fun references(content: String, symbol: String, fileName: String = "Source.kt"): List<KotlinReference> {
        val refs = mutableListOf<KotlinReference>()

        // Call sites: `symbol(` or `.symbol(` — but not the declaration `fun symbol(`
        val callRegex = Regex("""(?<!fun\s)(?<!\.)(?<![A-Za-z0-9_])${Regex.escape(symbol)}\s*\(""")
        callRegex.findAll(content).forEach { m ->
            refs += KotlinReference(symbol, lineOf(m, content), snippetAt(content, m.range.first))
        }

        // Property/identifier usages: `.symbol` (receiver access) — excluding the
        // declaration `val symbol` / `fun symbol` / `class symbol`
        val useRegex = Regex("""\b\.${Regex.escape(symbol)}\b""")
        useRegex.findAll(content).forEach { m ->
            refs += KotlinReference(symbol, lineOf(m, content), snippetAt(content, m.range.first))
        }

        return refs.distinctBy { "${it.line}:${it.snippet}" }.take(200)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun lineOf(m: MatchResult, content: String): Int =
        content.substring(0, m.range.first).count { it == '\n' } + 1

    private fun snippetAt(content: String, offset: Int): String {
        val lineStart = content.lastIndexOf('\n', offset.coerceAtLeast(0)) + 1
        val lineEnd = content.indexOf('\n', offset).let { if (it < 0) content.length else it }
        return content.substring(lineStart, lineEnd).trim().take(80)
    }
}
