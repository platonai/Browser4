package ai.platon.pulsar.coding

/**
 * "Extract skeleton from real code" — the anti-staleness scaffold mechanism.
 *
 * Instead of hand-written templates (which go stale when the codebase evolves —
 * e.g. the `ai.platon.browser4.*` → `ai.platon.pulsar.*` package migration
 * touched 334 files), this extracts a parameterized skeleton from an existing,
 * real source file. The repository's own best code IS the template, so it never
 * drifts from reality.
 *
 * ## What gets parameterized (language-structure driven, not name-driven)
 *
 * - `package X.Y.Z`        → `{basePackage}`
 * - the class named after the file (`class Foo` / `open class Foo`) → `{className}`
 * - `override val domain = "x"` → `{domain}`
 * - `toolSpec["method"]`    → `{toolMethod}`
 * - imports of the form `import <basePackage>.*` → rewritten to the new package
 *
 * Everything else (annotations, bodies, comments) is preserved verbatim, so the
 * extracted skeleton is a faithful copy of the reference implementation with
 * only the volatile identifiers replaced.
 */
object SkeletonExtractor {

    /** A parameterized skeleton: the template text plus the parameters found. */
    data class Skeleton(
        val template: String,
        val parameters: Map<String, String>,
    )

    /**
     * Extract a skeleton from a Kotlin source file.
     *
     * @param content  raw file content
     * @param fileName file name (used to infer the main class name)
     * @return a [Skeleton] with placeholders `{key}` and the discovered values
     */
    fun extract(content: String, fileName: String): Skeleton {
        val parameters = mutableMapOf<String, String>()

        // 1. Package declaration.
        val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(content)
            ?.groupValues?.get(1)
        if (pkg != null) {
            parameters["basePackage"] = pkg
        }

        // 2. Main class: the class whose simple name equals the file base name
        //    (Foo.kt → class Foo), or any top-level class if none matches.
        val fileBase = fileName.removeSuffix(".kt")
        val classMatch = Regex(
            """(?m)^\s*(?:abstract\s+|open\s+|final\s+|data\s+|sealed\s+)*class\s+([A-Za-z_]\w*)"""
        ).findAll(content).map { it.groupValues[1] }.toList()
        val mainClass = classMatch.firstOrNull { it == fileBase } ?: classMatch.firstOrNull()
        if (mainClass != null) {
            parameters["className"] = mainClass
        }

        // 3. Tool domain: `override val domain = "..."`.
        Regex("""override\s+val\s+domain\s*=\s*"([^"]+)"""").find(content)
            ?.let { parameters["domain"] = it.groupValues[1] }

        // 4. First toolSpec-registered method.
        Regex("""toolSpec\["([^"]+)"\]""").find(content)
            ?.let { parameters["toolMethod"] = it.groupValues[1] }

        // 5. Build the template: replace discovered identifiers with placeholders.
        //    Order matters — replace the most specific (longest) first so that
        //    e.g. the full package doesn't partially clobber class names.
        var template = content
        parameters.entries.sortedByDescending { it.key.length }
            .forEach { (key, value) ->
                // Only replace whole-word occurrences (word boundaries); class-name
                // and package occurrences are safe, but we must not touch
                // `{domain}`-like strings inside the file accidentally — replace
                // exact identifier matches.
                template = template.replace(Regex("""\b${Regex.escape(value)}\b"""), "{$key}")
            }

        return Skeleton(template, parameters)
    }

    /**
     * Instantiate a [Skeleton] with new parameter values.
     *
     * @param params the desired values for each placeholder key
     * @return the generated source, with any unresolved placeholders left as-is
     *         (caller can inspect them)
     */
    fun instantiate(skeleton: Skeleton, params: Map<String, String>): String {
        var out = skeleton.template
        params.forEach { (key, value) ->
            out = out.replace("{$key}", value)
        }
        return out
    }
}
