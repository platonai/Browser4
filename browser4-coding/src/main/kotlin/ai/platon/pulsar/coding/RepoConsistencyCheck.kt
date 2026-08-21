package ai.platon.pulsar.coding

/**
 * Repo-governance consistency checks for Browser4 self-development.
 *
 * `coding.validate(type="repo-consistency")` verifies the invariants that keep
 * the build coherent when an agent adds/removes modules or bumps versions:
 *
 * 1. **VERSION** — the repo VERSION file exists and is a plausible `X.Y.Z(-SNAPSHOT)`.
 * 2. **Root pom** — the root aggregator `<project>` version matches VERSION.
 * 3. **BOM** — `browser4-dependencies` (the BOM) version matches VERSION.
 * 4. **Module registration** — every `<module>` in the default `<modules>` block
 *    resolves to a real directory; and every on-disk module directory is
 *    registered somewhere in the root pom (so `-am` reactor builds can find it).
 * 5. **Plugin SDK versions** — every in-repo `browser4-plugin.json` declares
 *    `sdkVersion` equal to VERSION, so bundled plugins always match the SDK
 *    they are built with.
 *
 * Pure string/regex analysis plus an existence callback — zero dependencies,
 * no network, no Maven invocation. Testable without a real checkout.
 */
object RepoConsistencyCheck {

    private val VERSION_PATTERN = Regex("""^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$""")
    private val MODULE_TAG = Regex("""<module>([^<]+)</module>""")
    private val ROOT_VERSION = Regex("""<artifactId>browser4</artifactId>\s*<version>([^<]+)</version>""")
    private val BOM_VERSION = Regex("""<artifactId>browser4-dependencies</artifactId>\s*<version>([^<]+)</version>""")
    private val MANIFEST_NAME = Regex(""""name"\s*:\s*"([^"]+)"""")
    private val SDK_VERSION_TAG = Regex(""""sdkVersion"\s*:\s*"([^"]*)"""")
    private val TRAILING_WS = Regex("""[ \t]+$""")

    /**
     * Run the consistency checks.
     *
     * @param versionContent content of the repo VERSION file (null when absent)
     * @param rootPom content of the root aggregator pom.xml (null when absent)
     * @param bomPom content of browser4-dependencies/pom.xml (null when absent)
     * @param moduleExists callback: does the module directory exist on disk?
     * @param onDiskModuleDirs top-level module directories found on disk
     *   (containing a pom.xml), checked for registration
     * @param pluginManifestContents contents of every in-repo
     *   `META-INF/browser4-plugin.json`, checked for `sdkVersion` == VERSION
     * @param staticModuleMap the static [ModuleMap.MODULES] snapshot (optional);
     *   when provided together with [liveModuleDirs], modules found in the live
     *   pom scan but missing from the snapshot are reported as errors (drift)
     * @param liveModuleDirs module directories found in the live pom scan (optional)
     * @param staticDependents the static [ModuleMap.DEPENDENTS] snapshot (optional)
     * @param liveDependentsOf reverse-edge lookup on the live pom graph: returns the
     *   modules that directly depend on [String] (optional); when both are provided,
     *   per-module reverse edges are compared as sets in both directions
     * @param moduleMapSource raw ModuleMap.kt source text (optional); checked for
     *   source hygiene (trailing whitespace, lines wider than 120 columns) as
     *   WARNING-level issues so hand-edited snapshots stay readable
     */
    fun check(
        versionContent: String?,
        rootPom: String?,
        bomPom: String?,
        moduleExists: (String) -> Boolean = { true },
        onDiskModuleDirs: List<String> = emptyList(),
        pluginManifestContents: List<String> = emptyList(),
        staticModuleMap: List<String> = emptyList(),
        liveModuleDirs: List<String> = emptyList(),
        staticDependents: Map<String, List<String>> = emptyMap(),
        liveDependentsOf: ((String) -> Set<String>)? = null,
        moduleMapSource: String? = null,
    ): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        // --- VERSION file ---
        val version = versionContent?.trim().orEmpty()
        when {
            version.isBlank() ->
                issues += ValidationIssue(Severity.ERROR, "VERSION file is missing or empty")
            !VERSION_PATTERN.matches(version) ->
                issues += ValidationIssue(
                    Severity.ERROR,
                    "VERSION '$version' is not a plausible version — expected X.Y.Z or X.Y.Z-SNAPSHOT"
                )
        }

        // --- Root aggregator pom ---
        if (rootPom.isNullOrBlank()) {
            issues += ValidationIssue(Severity.ERROR, "root pom.xml is missing or empty")
        } else {
            val rootVersion = ROOT_VERSION.find(rootPom)?.groupValues?.get(1)?.trim()
            if (rootVersion == null) {
                issues += ValidationIssue(
                    Severity.WARNING,
                    "Could not locate project <version> in root pom.xml (artifactId browser4 + <version> adjacency)"
                )
            } else if (version.isNotBlank() && rootVersion != version) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "Root pom version '$rootVersion' does not match VERSION '$version'"
                )
            }
        }

        // --- BOM ---
        if (bomPom.isNullOrBlank()) {
            issues += ValidationIssue(Severity.ERROR, "browser4-dependencies/pom.xml (BOM) is missing or empty")
        } else {
            val bomVersion = BOM_VERSION.find(bomPom)?.groupValues?.get(1)?.trim()
            if (bomVersion == null) {
                issues += ValidationIssue(
                    Severity.WARNING,
                    "Could not locate BOM <version> in browser4-dependencies/pom.xml"
                )
            } else if (version.isNotBlank() && bomVersion != version) {
                issues += ValidationIssue(
                    Severity.ERROR,
                    "BOM version '$bomVersion' does not match VERSION '$version'"
                )
            }
        }

        // --- Module registration (default reactor) ---
        if (!rootPom.isNullOrBlank()) {
            val defaultModules = firstModulesBlock(rootPom)
            defaultModules.forEach { module ->
                if (!moduleExists(module)) {
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "Module '$module' is registered in the root pom default <modules> but the directory does not exist"
                    )
                }
            }
        }

        // --- On-disk modules must be registered somewhere ---
        if (!rootPom.isNullOrBlank()) {
            val allRegistered = MODULE_TAG.findAll(rootPom).map { it.groupValues[1] }.toSet()
            onDiskModuleDirs.filter { it !in allRegistered }.forEach { dir ->
                issues += ValidationIssue(
                    Severity.WARNING,
                    "Module directory '$dir' exists on disk but is not registered in the root pom — " +
                        "reactor builds (-am) and CI may not pick it up"
                )
            }
        }

        // --- Plugin SDK versions must match VERSION ---
        pluginManifestContents.forEach { content ->
            val pluginName = MANIFEST_NAME.find(content)?.groupValues?.get(1) ?: "unknown"
            val declared = SDK_VERSION_TAG.find(content)?.groupValues?.get(1)
            when {
                // Unresolved archetype template placeholders (${pluginName}, ${browser4-version})
                // are exempt — they only exist in the archetype's template resources.
                declared?.contains("\${") == true -> Unit
                declared == null -> issues += ValidationIssue(
                    Severity.ERROR,
                    "Plugin manifest '$pluginName' is missing 'sdkVersion' — the host cannot verify SDK compatibility"
                )
                version.isNotBlank() && declared != version -> issues += ValidationIssue(
                    Severity.ERROR,
                    "Plugin '$pluginName' sdkVersion '$declared' does not match VERSION '$version'"
                )
            }
        }

        // --- ModuleMap snapshot ↔ live pom graph (drift check) ---
        // Mirrors ModuleMapDriftE2ETest: every real module must exist in the static
        // ModuleMap.MODULES snapshot, otherwise devTask planning / impact analysis
        // operate on stale topology.
        if (staticModuleMap.isNotEmpty() && liveModuleDirs.isNotEmpty()) {
            val missing = liveModuleDirs.filter { it !in staticModuleMap }.sorted()
            missing.forEach { dir ->
                issues += ValidationIssue(
                    Severity.ERROR,
                    "ModuleMap.MODULES is missing '$dir' — sync browser4-coding/src/main/kotlin/" +
                        "ai/platon/pulsar/coding/ModuleMap.kt (ModuleMapDriftE2ETest fails on drift)"
                )
            }
        }

        // --- ModuleMap.DEPENDENTS reverse-edge drift ---
        // Both directions as sets: live dependents of each live module must match the
        // static snapshot (and vice versa — static keys with no live module are reported).
        if (staticDependents.isNotEmpty() && liveDependentsOf != null && liveModuleDirs.isNotEmpty()) {
            (liveModuleDirs + staticDependents.keys).distinct().sorted().forEach { m ->
                val live = liveDependentsOf(m)
                val static = staticDependents[m].orEmpty().toSet()
                if (live != static) {
                    issues += ValidationIssue(
                        Severity.ERROR,
                        "ModuleMap.DEPENDENTS[$m] drifted: live={${live.sorted().joinToString(", ")}} " +
                            "static={${static.sorted().joinToString(", ")}} — sync ModuleMap.kt"
                    )
                }
            }
        }

        // --- ModuleMap source hygiene ---
        // Hand-edited DEPENDENTS entries used to land with trailing whitespace and
        // >120-column lines; flag them (WARNING) so the drift machinery never
        // produces unreadable snapshots.
        if (!moduleMapSource.isNullOrBlank()) {
            moduleMapSource.lineSequence().forEachIndexed { index, line ->
                if (TRAILING_WS.containsMatchIn(line)) {
                    issues += ValidationIssue(
                        Severity.WARNING, "ModuleMap.kt:${index + 1} has trailing whitespace"
                    )
                }
                if (line.length > 120) {
                    issues += ValidationIssue(
                        Severity.WARNING, "ModuleMap.kt:${index + 1} is ${line.length} columns wide (limit 120)"
                    )
                }
            }
        }

        return ValidationResult.of(issues)
    }

    /**
     * Whether [path] is an in-repo plugin manifest worth scanning: a file named
     * `browser4-plugin.json` that is NOT inside build output (`target/`) or
     * hidden directories (`.git`, `.worktrees`, `.claude`, ...). Hidden dirs
     * may hold other branches' checkouts (worktrees) whose manifests must not
     * be judged against this checkout's VERSION.
     */
    fun isPluginManifestPath(path: java.nio.file.Path): Boolean {
        if (!java.nio.file.Files.isRegularFile(path)) return false
        if (path.fileName.toString() != "browser4-plugin.json") return false
        return path.none { segment ->
            val name = segment.toString()
            name == "target" || name.startsWith(".")
        }
    }

    /**
     * The `<module>` entries of the FIRST `<modules>` block (the default active
     * reactor). Profile-scoped blocks are opt-in and are not checked for
     * directory existence.
     */
    private fun firstModulesBlock(pom: String): List<String> {
        val start = pom.indexOf("<modules>")
        if (start < 0) return emptyList()
        val end = pom.indexOf("</modules>", start)
        if (end < 0) return emptyList()
        return MODULE_TAG.findAll(pom.substring(start, end)).map { it.groupValues[1] }.toList()
    }
}
