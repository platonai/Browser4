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
     * A multi-file skeleton set extracted from a real directory (a plugin or
     * module). Each file carries its own per-file skeleton; [parameters] is the
     * UNION across all files, so re-instantiation with one value set renames
     * consistently everywhere.
     */
    data class SkeletonSet(
        val files: Map<String, Skeleton>,
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
        //    Order matters — replace the longest VALUES first so that e.g. the
        //    full package doesn't partially clobber class names.
        var template = content
        parameters.entries.sortedByDescending { it.value.length }
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

    /**
     * Extract a multi-file skeleton set from a real directory (a plugin/module).
     *
     * Semantics:
     * - `basePackage` is the COMMON package prefix across all files (e.g.
     *   "ai.platon.pulsar.seo" for packages "…seo.tools" and "…seo.config").
     *   Per-file suffixes (".tools", ".config") are preserved, so renaming the
     *   base renames every package line and import consistently.
     * - Shared identifiers (`domain`, `toolMethod`, `artifactId` from pom.xml,
     *   `pluginName` from plugin.json) become shared placeholders.
     * - Each class becomes a VALUE-NAMED placeholder (`{SeoToolExecutor}`,
     *   `{SeoAutoConfiguration}`): [instantiate] renames a class via its own key,
     *   or via `className` (the first class discovered, kept as a convenience
     *   alias). Cross-file references (an import of the executor class inside
     *   the AutoConfiguration) are parameterized the same way.
     *
     * @param files relative path → file content (text files only, keys use '/')
     * @return the multi-file skeleton set
     */
    fun extractDir(files: Map<String, String>): SkeletonSet {
        // 0. Directory-level package handling: parameterize the package line with
        //    the common prefix, preserving per-file suffixes.
        val packages = files.mapValues { (_, content) ->
            Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(content)?.groupValues?.get(1)
        }.filterValues { it != null }.mapValues { it.value!! }
        val commonPrefix = commonPackagePrefix(packages.values.toList())
        val preprocessed = if (commonPrefix.isEmpty()) {
            files
        } else {
            files.mapValues { (rel, content) ->
                val pkg = packages[rel]
                if (pkg == null) content else {
                    val suffix = pkg.removePrefix(commonPrefix)
                    val replacement = if (suffix.isEmpty()) "package {basePackage}" else "package {basePackage}$suffix"
                    Regex("""^package\s+${Regex.escape(pkg)}""", RegexOption.MULTILINE).replaceFirst(content, replacement)
                }
            }
        }

        // 1. Per-file extraction (className/domain/toolMethod → placeholders).
        val skeletons = preprocessed.mapValues { (relPath, content) ->
            extract(content, relPath.substringAfterLast('/'))
        }

        // 2. Each file's OWN class → value-named placeholder (distinct per file):
        //    `open class {className}` (from extract) becomes `open class {SeoToolExecutor}`.
        //    extract() already replaced every raw class-name occurrence, so only
        //    the placeholder needs converting (re-matching the raw name here
        //    would double-brace `{{SeoToolExecutor}}`).
        val classNamed = skeletons.mapValues { (_, sk) ->
            val cn = sk.parameters["className"]
            if (cn != null) sk.copy(template = sk.template.replace("{className}", "{$cn}")) else sk
        }

        // 3. Union of SHARED parameters (values must be consistent across files).
        val parameters = mutableMapOf<String, String>()
        if (commonPrefix.isNotEmpty()) parameters["basePackage"] = commonPrefix
        listOf("domain", "toolMethod").forEach { key ->
            skeletons.values.firstOrNull { it.parameters[key] != null }?.parameters[key]
                ?.let { parameters[key] = it }
        }
        // Plugin-level identifiers.
        files.forEach { (relPath, content) ->
            if (relPath.endsWith("pom.xml")) {
                // Skip the <parent> block — its artifactId (e.g. browser4-pdk)
                // is the parent BOM, not this module's own artifact.
                val withoutParent = content.replace(
                    Regex("""<parent>.*?</parent>""", RegexOption.DOT_MATCHES_ALL), "")
                Regex("""<artifactId>(browser4-[\w.-]+)</artifactId>""").find(withoutParent)
                    ?.let { parameters.putIfAbsent("artifactId", it.groupValues[1]) }
            } else if (relPath.endsWith("plugin.json")) {
                Regex(""""name"\s*:\s*"([^"]+)"""").find(content)
                    ?.let { parameters.putIfAbsent("pluginName", it.groupValues[1]) }
            }
        }
        if (parameters["pluginName"] == parameters["artifactId"]) parameters.remove("pluginName")

        // 4. Cross-file pass over SHARED values (longest first).
        val ordered = parameters.entries.sortedByDescending { it.value.length }
        val sharedTemplated = classNamed.mapValues { (_, sk) ->
            var t = sk.template
            ordered.forEach { (key, value) ->
                t = t.replace(Regex("""\b${Regex.escape(value)}\b"""), "{$key}")
            }
            t
        }

        // 5. Cross-file CLASS references: each class name found in OTHER files
        //    becomes its value-named placeholder too (e.g. an import of the
        //    executor inside the AutoConfiguration).
        val classNames = skeletons.mapNotNull { it.value.parameters["className"] }.distinct()
        val templated = sharedTemplated.mapValues { (rel, t) ->
            val ownClass = skeletons[rel]?.parameters?.get("className")
            var out = t
            classNames.forEach { cn ->
                if (cn != ownClass) {
                    out = out.replace(Regex("""\b${Regex.escape(cn)}\b"""), "{$cn}")
                }
            }
            out
        }

        // 6. Discovery metadata: expose className as the convenience rename key.
        //    The canonical class is the EXECUTOR (the file declaring a tool
        //    `domain`), independent of file-walk order — the tool executor is
        //    what scaffoldFromExample's className targets. Every class is also
        //    exposed as its own value-named key for independent renames.
        val executorClass = skeletons.entries
            .firstOrNull { it.value.parameters["domain"] != null }
            ?.value?.parameters?.get("className")
        (executorClass ?: classNames.firstOrNull())?.let { parameters["className"] = it }
        classNames.forEach { cn -> parameters.putIfAbsent(cn, cn) }

        return SkeletonSet(
            files = templated.mapValues { (relPath, tpl) -> Skeleton(tpl, parameters) },
            parameters = parameters,
        )
    }

    /**
     * Instantiate a [SkeletonSet] with new parameter values, consistently across
     * all files. Resolves shared placeholders (`{basePackage}`, `{domain}`),
     * class placeholders via their own key (`{SeoAutoConfiguration}`), and the
     * `className` convenience alias (renames the first discovered class and its
     * cross-file references).
     *
     * @return relative path → generated content; unresolved placeholders are
     *         left as-is for caller inspection
     */
    fun instantiate(set: SkeletonSet, params: Map<String, String>): Map<String, String> {
        return set.files.mapValues { (_, sk) ->
            val effective = mutableMapOf<String, String>()
            // Pass 1: {key} placeholders → new value (or discovered when not renamed).
            set.parameters.forEach { (key, discovered) ->
                effective[key] = params[key] ?: discovered
            }
            // Pass 2: {discoveredValue} placeholders (value-named class refs and
            // self placeholders) → the rename value of their owning key. An
            // explicit value-named key (params["SeoAutoConfiguration"]) wins over
            // the className alias; identity when nothing was renamed.
            set.parameters.forEach { (key, discovered) ->
                if (params.containsKey(key)) {
                    effective[discovered] = params[key]!!
                } else if (key != discovered) {
                    effective.putIfAbsent(discovered, discovered)
                }
            }
            var out = sk.template
            effective.entries.sortedByDescending { it.key.length }
                .forEach { (placeholder, value) ->
                    out = out.replace("{$placeholder}", value)
                }
            out
        }
    }

    /** Longest common dotted-prefix of the given packages. */
    private fun commonPackagePrefix(packages: List<String>): String {
        if (packages.isEmpty()) return ""
        var prefix = packages.first().split('.').toMutableList()
        for (p in packages.drop(1)) {
            val parts = p.split('.')
            var i = 0
            while (i < prefix.size && i < parts.size && prefix[i] == parts[i]) i++
            prefix = prefix.subList(0, i).toMutableList()
            if (prefix.isEmpty()) break
        }
        return prefix.joinToString(".")
    }
}
