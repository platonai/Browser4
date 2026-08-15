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
 *
 * Pure string/regex analysis plus an existence callback — zero dependencies,
 * no network, no Maven invocation. Testable without a real checkout.
 */
object RepoConsistencyCheck {

    private val VERSION_PATTERN = Regex("""^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$""")
    private val MODULE_TAG = Regex("""<module>([^<]+)</module>""")
    private val ROOT_VERSION = Regex("""<artifactId>browser4</artifactId>\s*<version>([^<]+)</version>""")
    private val BOM_VERSION = Regex("""<artifactId>browser4-dependencies</artifactId>\s*<version>([^<]+)</version>""")

    /**
     * Run the consistency checks.
     *
     * @param versionContent content of the repo VERSION file (null when absent)
     * @param rootPom content of the root aggregator pom.xml (null when absent)
     * @param bomPom content of browser4-dependencies/pom.xml (null when absent)
     * @param moduleExists callback: does the module directory exist on disk?
     * @param onDiskModuleDirs top-level module directories found on disk
     *   (containing a pom.xml), checked for registration
     */
    fun check(
        versionContent: String?,
        rootPom: String?,
        bomPom: String?,
        moduleExists: (String) -> Boolean = { true },
        onDiskModuleDirs: List<String> = emptyList(),
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

        return ValidationResult.of(issues)
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
