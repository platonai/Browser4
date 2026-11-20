package ai.platon.pulsar.coding

import java.io.File

/**
 * Severity level for validation issues.
 */
enum class Severity { ERROR, WARNING, INFO }

/**
 * A single validation issue found in an artifact.
 */
data class ValidationIssue(
    val severity: Severity,
    val message: String,
    val file: String? = null
)

/**
 * Result of validating an artifact.
 */
data class ValidationResult(
    val valid: Boolean,
    val issues: List<ValidationIssue>
) {
    /**
     * Format the result as a human-readable string for the agent.
     */
    fun format(): String {
        if (issues.isEmpty()) return "✓ All checks passed."
        val sb = StringBuilder()
        if (!valid) sb.append("✗ Validation failed with ${issues.count { it.severity == Severity.ERROR }} error(s).\n")
        else sb.append("✓ Validation passed with ${issues.size} warning(s)/info.\n")
        issues.forEach { issue ->
            val icon = when (issue.severity) {
                Severity.ERROR -> "✗"
                Severity.WARNING -> "⚠"
                Severity.INFO -> "ℹ"
            }
            val filePart = issue.file?.let { " [$it]" } ?: ""
            sb.append("$icon ${issue.message}$filePart\n")
        }
        return sb.toString().trimEnd()
    }

    companion object {
        fun valid() = ValidationResult(true, emptyList())
        fun of(issues: List<ValidationIssue>) = ValidationResult(
            valid = issues.none { it.severity == Severity.ERROR },
            issues = issues
        )
    }
}

/**
 * Lightweight validators for Browser4's four core artifact types.
 *
 * All validation is in-process (no external tools, no LSP, no browser session).
 * The agent can supplement with:
 * - `coding.shell: mvn compile` for plugin compilation
 * - `tab.eval` for JS runtime testing
 * - `coding.shell: bash -n <file>` / `powershell -Command "..."` for script syntax
 *
 * Zero dependencies — pure string/regex analysis.
 */
object ArtifactValidator {

    // ==================== Plugin ====================

    /**
     * Validate a Browser4 plugin directory.
     *
     * @param baseDir plugin base directory (containing pom.xml, src/, etc.)
     * @return validation result with issues found
     */
    fun validatePlugin(baseDir: String): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        val dir = File(baseDir)

        if (!dir.isDirectory) {
            return ValidationResult(false, listOf(
                ValidationIssue(Severity.ERROR, "Plugin directory does not exist: $baseDir")
            ))
        }

        // Check pom.xml
        val pomFile = File(dir, "pom.xml")
        if (!pomFile.exists()) {
            issues += ValidationIssue(Severity.ERROR, "Missing pom.xml", "pom.xml")
        } else {
            issues += validatePomXml(pomFile.readText(), "pom.xml")
        }

        // Check plugin.json
        val pluginJsonFile = File(dir, "src/main/resources/META-INF/browser4-plugin.json")
        if (!pluginJsonFile.exists()) {
            issues += ValidationIssue(Severity.WARNING,
                "Missing browser4-plugin.json — plugin will not be recognized by the plugin loader",
                "src/main/resources/META-INF/browser4-plugin.json")
        } else {
            issues += validatePluginJson(pluginJsonFile.readText(), pluginJsonFile.relativeTo(dir).path)
        }

        // Check AutoConfiguration.imports
        val importsFile = File(dir, "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
        if (!importsFile.exists()) {
            issues += ValidationIssue(Severity.WARNING,
                "Missing AutoConfiguration.imports — Spring will not auto-configure this plugin",
                "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
        } else {
            val importsContent = importsFile.readText().trim()
            if (importsContent.isEmpty()) {
                issues += ValidationIssue(Severity.ERROR,
                    "AutoConfiguration.imports is empty",
                    importsFile.relativeTo(dir).path)
            } else {
                // Verify the class name looks valid
                importsContent.lines().filter { it.isNotBlank() }.forEach { line ->
                    val className = line.trim()
                    if (!className.matches(Regex("[a-z][a-z0-9.]*(\\.[A-Z][A-Za-z0-9]*)+"))) {
                        issues += ValidationIssue(Severity.WARNING,
                            "AutoConfiguration class name may be invalid: '$className'",
                            importsFile.relativeTo(dir).path)
                    }
                    // Check if corresponding Kotlin file exists
                    val expectedPath = "src/main/kotlin/" + className.replace('.', '/') + ".kt"
                    val kotlinFile = File(dir, expectedPath)
                    if (!kotlinFile.exists()) {
                        issues += ValidationIssue(Severity.WARNING,
                            "AutoConfiguration class '$className' has no corresponding .kt file at $expectedPath",
                            importsFile.relativeTo(dir).path)
                    } else {
                        // The auto-config must implement ToolMount, otherwise PluginManager
                        // will never register the executor into CustomToolRegistry.
                        val autoConfigContent = kotlinFile.readText()
                        if (!autoConfigContent.contains("ToolMount")) {
                            issues += ValidationIssue(Severity.WARNING,
                                "AutoConfiguration '$className' does not implement ToolMount — " +
                                    "the plugin's tools will NOT be registered for the LLM agent",
                                expectedPath)
                        }
                        if (!autoConfigContent.contains("getToolExecutors")) {
                            issues += ValidationIssue(Severity.WARNING,
                                "AutoConfiguration '$className' has no getToolExecutors() — " +
                                    "no tool executors will be exposed to the agent",
                                expectedPath)
                        }
                    }
                }
            }
        }

        // Check for at least one ToolExecutor. Match the CLASS declaration (not any
        // file merely mentioning "ToolExecutor", e.g. an AutoConfiguration that
        // wires/imports one) — otherwise config classes get mis-validated as executors.
        val toolExecutors = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val content = try { file.readText() } catch (_: Exception) { null }
                content?.takeIf {
                    it.contains(Regex("""(class|open class)\s+\w+ToolExecutor\b""")) ||
                        it.contains("AbstractToolExecutor")
                }?.let { file to it }
            }
            .toList()

        if (toolExecutors.isEmpty()) {
            issues += ValidationIssue(Severity.WARNING,
                "No ToolExecutor class found — plugin will not expose any tools to the agent")
        } else {
            // Validate each ToolExecutor's structure
            toolExecutors.forEach { (file, content) ->
                val relPath = file.relativeTo(dir).path
                issues += validateKotlinToolExecutor(content, relPath)
            }
        }

        // manifest.name must match the pom artifactId — PluginService.getPlugin()
        // matches by manifest name (or JAR file name), so a mismatch breaks lookup.
        val manifestName = Regex(""""name"\s*:\s*"([^"]+)""").find(
            pluginJsonFile.takeIf { it.exists() }?.readText().orEmpty()
        )?.groupValues?.get(1)
        if (manifestName != null && pomFile.exists()) {
            // The project artifactId is the one directly under <project> — strip the
            // <parent> block and <dependencies> so nested artifactIds don't fool us.
            val pomText = pomFile.readText()
            val artifactId = Regex("""(?s)<artifactId>\s*([^<\s]+)\s*</artifactId>""")
                .findAll(pomText.substringBefore("<dependencies>"))
                .map { it.groupValues[1] }
                .lastOrNull()
            if (artifactId != null && artifactId != manifestName) {
                issues += ValidationIssue(Severity.WARNING,
                    "plugin.json 'name' ('$manifestName') does not match pom <artifactId> ('$artifactId') — " +
                        "PluginService.getPlugin() lookup by name will fail",
                    "browser4-plugin.json")
            }
        }

        // Browser-side JS resources referenced by Service classes must exist
        // under src/main/resources — a loadResource("/seo/x.js") with no matching
        // file blows up at runtime with IllegalStateException.
        val resourcesDir = File(dir, "src/main/resources")
        val jsLoadRefs = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                Regex("""loadResource\("/([^"]+\.js)"\)""").findAll(f.readText())
                    .map { it.groupValues[1] }
            }
            .toList()
        jsLoadRefs.forEach { ref ->
            if (!File(resourcesDir, ref).exists()) {
                issues += ValidationIssue(Severity.WARNING,
                    "Service loads '/$ref' but no such file exists under src/main/resources — " +
                        "runtime loadResource will throw",
                    ref)
            }
        }

        // --- Semantic checks: unimplemented scaffold stubs ---
        // Structure-only validation passes scaffold stubs — flag obvious placeholders
        // so "validate ✓" no longer masks a plugin whose tools are still unimplemented
        // (observed in production: a stub JS returning {url, data:{}} passed validation
        // while the plugin was functionally empty).
        val stubMarker = Regex(
            """(?i)(TODO\s*:?\s*implement|not\s+implemented|NotImplementedError|UnsupportedOperationException|\bFIXME\b|\bplaceholder\b|\bstub\b)"""
        )
        val emptyDataReturn = Regex("""data\s*:\s*\{\s*\}""")
        dir.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "js", "ts") }
            .forEach { file ->
                val rel = file.relativeTo(dir).path
                val content = runCatching { file.readText() }.getOrDefault("")
                val marker = stubMarker.find(content)
                if (marker != null) {
                    issues += ValidationIssue(Severity.WARNING,
                        "Possible unimplemented scaffold stub — '${marker.value}' found; implement or remove before shipping",
                        rel)
                }
                if (file.extension == "js" && emptyDataReturn.containsMatchIn(content)) {
                    issues += ValidationIssue(Severity.WARNING,
                        "JS returns an empty data object ({}) — the tool likely returns no real payload yet",
                        rel)
                }
            }

        return ValidationResult.of(issues)
    }

    /**
     * Validate plugin.json content against the real [ai.platon.pulsar.skeleton.plugin.PluginManifest]
     * contract: `name` + `version` required; `description`, `dependsOn`, `autoConfigurationClasses` optional.
     */
    fun validatePluginJson(content: String, fileName: String = "browser4-plugin.json"): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Basic JSON validation (no external library)
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            issues += ValidationIssue(Severity.ERROR, "Invalid JSON: must start with '{' and end with '}'", fileName)
            return issues
        }

        // Required fields per PluginManifest (browser4-skeleton): name, version
        val requiredFields = listOf("name", "version")
        requiredFields.forEach { field ->
            if (!trimmed.contains(Regex(""""$field"\s*:"""))) {
                issues += ValidationIssue(Severity.ERROR, "Missing required field '$field' in plugin.json", fileName)
            }
        }

        // autoConfigurationClasses drives PluginService.isPluginLoaded() bean detection.
        if (!trimmed.contains(Regex(""""autoConfigurationClasses"\s*:"""))) {
            issues += ValidationIssue(Severity.WARNING,
                "No 'autoConfigurationClasses' — PluginService cannot detect the plugin as loaded by bean presence", fileName)
        }

        // sdkVersion (since 4.14): new plugins should declare the SDK they were
        // built against; the host uses it for compatibility checks.
        if (!trimmed.contains(Regex(""""sdkVersion"\s*:"""))) {
            issues += ValidationIssue(Severity.WARNING,
                "No 'sdkVersion' — host cannot verify SDK compatibility (plugins built before 4.14 are exempt)", fileName)
        }

        // dependsOn is part of the real manifest schema
        if (!trimmed.contains(Regex(""""dependsOn"\s*:"""))) {
            issues += ValidationIssue(Severity.INFO, "No 'dependsOn' — plugin JAR will not declare its dependencies", fileName)
        }

        // 'id' / 'domain' are NOT part of the real PluginManifest schema
        if (trimmed.contains(Regex(""""id"\s*:"""))) {
            issues += ValidationIssue(Severity.INFO,
                "'id' is not a PluginManifest field — the manifest key is 'name'", fileName)
        }
        if (trimmed.contains(Regex(""""domain"\s*:"""))) {
            issues += ValidationIssue(Severity.INFO,
                "'domain' is not a PluginManifest field — the tool domain lives on the ToolExecutor", fileName)
        }

        return issues
    }

    /**
     * Validate pom.xml content for a Browser4 plugin.
     */
    fun validatePomXml(content: String, fileName: String = "pom.xml"): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for parent POM
        if (!content.contains("<parent>")) {
            issues += ValidationIssue(Severity.WARNING, "Missing <parent> — should extend browser4-pdk", fileName)
        } else if (!content.contains("browser4-pdk")) {
            issues += ValidationIssue(Severity.WARNING, "Parent is not browser4-pdk", fileName)
        } else {
            // Parent version must be a resolvable literal, not an undefined ${revision} property
            if (content.contains("\${revision}")) {
                issues += ValidationIssue(Severity.ERROR,
                    "Parent <version> uses \${revision} which is not defined in this repository — " +
                        "use a literal version like 4.13.6-SNAPSHOT", fileName)
            }
            if (!content.contains("<version>")) {
                issues += ValidationIssue(Severity.WARNING, "Parent <version> missing — Maven cannot resolve the parent", fileName)
            }
        }

        // Check for artifactId (project-level, i.e. outside the <parent> block)
        val withoutParent = content.replace(Regex("(?s)<parent>.*?</parent>"), "")
        if (!withoutParent.contains(Regex("""<artifactId>\s*\S+\s*</artifactId>"""))) {
            issues += ValidationIssue(Severity.ERROR, "Missing <artifactId>", fileName)
        }

        // Check for packaging
        if (!content.contains("<packaging>")) {
            issues += ValidationIssue(Severity.INFO, "No explicit <packaging> — defaults to jar (usually correct)", fileName)
        }

        // Check that the compile classpath has the deps the generated code needs
        val requiredDeps = listOf(
            "browser4-agentic", "browser4-skeleton",
            "spring-boot-autoconfigure", "kotlin-stdlib"
        )
        requiredDeps.forEach { dep ->
            if (!content.contains("<artifactId>$dep</artifactId>")) {
                issues += ValidationIssue(Severity.WARNING,
                    "Missing dependency <artifactId>$dep</artifactId> (provided) — generated code may not compile", fileName)
            }
        }

        return issues
    }

    /**
     * Validate a Kotlin ToolExecutor file's structural consistency.
     */
    fun validateKotlinToolExecutor(content: String, fileName: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check for class declaration
        if (!content.contains(Regex("""class\s+\w+ToolExecutor"""))) {
            // Might be a different name, check for AbstractToolExecutor
            if (!content.contains("AbstractToolExecutor")) {
                issues += ValidationIssue(Severity.WARNING,
                    "File references ToolExecutor but does not extend AbstractToolExecutor", fileName)
            }
        }

        // Check for domain override
        if (!content.contains(Regex("""override\s+val\s+domain\s*="""))) {
            issues += ValidationIssue(Severity.WARNING, "Missing 'override val domain' — will use default", fileName)
        }

        // Browser-tool executors should receive the WebDriver (current page) as
        // receiver — a Unit receiver means tools cannot access the page.
        val receiverMatch = Regex("""override\s+val\s+receiverClass\s*=\s*([\w.]+)::class""").find(content)
        if (receiverMatch != null && receiverMatch.groupValues[1] !in setOf("WebDriver", "ai.platon.pulsar.api.WebDriver")) {
            issues += ValidationIssue(Severity.WARNING,
                "receiverClass is '${receiverMatch.groupValues[1]}' — browser tools should use WebDriver::class " +
                    "so they receive the current page", fileName)
        }

        // Check for callFunctionOn override
        if (!content.contains("callFunctionOn")) {
            issues += ValidationIssue(Severity.ERROR,
                "Missing callFunctionOn override — tool will not respond to any method", fileName)
        }

        // Check for toolSpec registration
        if (!content.contains("toolSpec[")) {
            issues += ValidationIssue(Severity.WARNING,
                "No toolSpec entries — executor has no registered tools", fileName)
        }

        // Check for package declaration
        val packageMatch = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(content)
        if (packageMatch == null) {
            issues += ValidationIssue(Severity.ERROR, "Missing package declaration", fileName)
        }

        // Delimiters are matched on a stack after skipping comments, strings, raw strings and
        // char literals: a ToolExecutor is mostly prompt/JSON string literals (`"{ref}"`), and the
        // old `count { it == '{' }` reported them as code.
        issues += checkKotlinDelimiters(content, fileName)

        return issues
    }

    /**
     * Kotlin delimiter check that understands comments, strings and char literals.
     *
     * A ToolExecutor is mostly prompt and JSON string literals (`"{ref}"`, `"{\"a\":1}"`), so the
     * old `count { it == '{' }` reported data as code — the same false positive the JS checker had,
     * and a false `Unbalanced braces` ERROR blocks a file that compiles. Comments (nestable in
     * Kotlin), raw `"""` strings, char literals and backtick identifiers are skipped, and closers
     * are matched on a stack so `)(` is caught where a count would call it balanced.
     *
     * A `${…}` template expression *is* code, and it can contain quotes of its own
     * (`"${map["key"]}"`), so the string is suspended at `${` and resumed by the `}` that brings
     * the delimiter depth back to where the interpolation started.
     */
    private fun checkKotlinDelimiters(content: String, fileName: String): List<ValidationIssue> {
        val closers = ArrayList<Char>()
        val openings = ArrayList<Int>()
        // One entry per open `${`: (was the surrounding literal raw, closers.size at entry).
        val interpolations = ArrayDeque<Pair<Boolean, Int>>()
        var inString = false
        var rawString = false
        var i = 0

        fun fail(message: String, at: Int) = listOf(ValidationIssue(
            Severity.ERROR, "Unbalanced $message at ${position(content, at)}", fileName))

        while (i < content.length) {
            if (inString) {
                val c = content[i]
                if (rawString) {
                    if (content.startsWith("\"\"\"", i)) {
                        inString = false
                        i += 3
                        continue
                    }
                } else {
                    when (c) {
                        '\\' -> {
                            i += 2
                            continue
                        }
                        '"' -> {
                            inString = false
                            i++
                            continue
                        }
                        // A plain Kotlin string cannot span a raw newline.
                        '\n' -> return fail("string literal: '\"' is never closed", i)
                    }
                }
                if (c == '$' && i + 1 < content.length && content[i + 1] == '{') {
                    interpolations.addLast(rawString to closers.size)
                    inString = false
                    i += 2
                    continue
                }
                i++
                continue
            }

            when {
                content.startsWith("//", i) -> {
                    while (i < content.length && content[i] != '\n') i++
                }
                content.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < content.length && depth > 0) {
                        when {
                            content.startsWith("/*", i) -> { depth++; i += 2 }
                            content.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                    if (depth > 0) return fail("block comment: '/*' is never closed", i)
                }
                content.startsWith("\"\"\"", i) -> {
                    inString = true
                    rawString = true
                    i += 3
                }
                content[i] == '"' -> {
                    inString = true
                    rawString = false
                    i++
                }
                content[i] == '\'' -> {
                    val end = skipKotlinQuoted(content, i, '\'')
                    if (end < 0) return fail("char literal: \"'\" is never closed", i)
                    i = end + 1
                }
                content[i] == '`' -> {
                    val end = content.indexOf('`', i + 1)
                    if (end < 0) return fail("backtick identifier is never closed", i)
                    i = end + 1
                }
                content[i] == '(' || content[i] == '[' || content[i] == '{' -> {
                    closers.add(closingDelimiter(content[i]))
                    openings.add(i)
                    i++
                }
                content[i] == ')' || content[i] == ']' || content[i] == '}' -> {
                    // This `}` ends a `${…}` expression: resume the literal around it.
                    if (content[i] == '}' && interpolations.isNotEmpty() &&
                        closers.size == interpolations.last().second
                    ) {
                        rawString = interpolations.removeLast().first
                        inString = true
                        i++
                        continue
                    }
                    val expected = closers.lastOrNull()
                        ?: return fail("'${content[i]}': nothing is open before it", i)
                    if (expected != content[i]) {
                        return fail(
                            "delimiters: '${openingDelimiter(content[i])}' at " +
                                "${position(content, openings.last())} is closed by '${content[i]}' " +
                                "before '$expected'",
                            i,
                        )
                    }
                    closers.removeLast()
                    openings.removeAt(openings.lastIndex)
                    i++
                }
                else -> i++
            }
        }

        if (inString) {
            return fail(
                if (rawString) "raw string: the triple quote is never closed"
                else "string literal: '\"' is never closed",
                content.length,
            )
        }
        if (closers.isNotEmpty()) {
            val open = openingDelimiter(closers.first())
            return listOf(ValidationIssue(
                Severity.ERROR,
                "Unbalanced '$open': ${closers.size} never closed, " +
                    "first at ${position(content, openings.first())}",
                fileName,
            ))
        }
        return emptyList()
    }

    /** Index of the closing [quote] of a single-line Kotlin literal; `\` escapes. */
    private fun skipKotlinQuoted(content: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < content.length) {
            when (content[i]) {
                '\\' -> i += 2
                quote -> return i
                // A plain Kotlin string/char literal cannot span a raw newline.
                '\n' -> return -1
                else -> i++
            }
        }
        return -1
    }

    // ==================== Skill ====================

    /**
     * Validate a SKILL.md file against the real [ai.platon.pulsar.agentic.skills.SkillDefinitionLoader]
     * contract: YAML frontmatter with `name` (must equal the directory name, kebab-case)
     * and `description` (1..1024 chars); optional `allowed-tools`.
     *
     * @param fileName should be the SKILL.md path (e.g. "skills/my-skill/SKILL.md")
     *                 so the name-vs-directory check can run.
     */
    fun validateSkill(content: String, fileName: String = "SKILL.md"): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        // Check for YAML frontmatter
        val frontmatterRegex = Regex("""^---\s*\n(.*?)\n---\s*\n""", RegexOption.DOT_MATCHES_ALL)
        val frontmatterMatch = frontmatterRegex.find(content)

        if (frontmatterMatch == null) {
            issues += ValidationIssue(Severity.ERROR,
                "Missing YAML frontmatter (--- ... --- at the top)", fileName)
        } else {
            val frontmatter = frontmatterMatch.groupValues[1]

            // Required fields per SkillDefinitionLoader: name + description
            val requiredFields = listOf("name", "description")
            requiredFields.forEach { field ->
                if (!frontmatter.contains(Regex("""^$field\s*:""", RegexOption.MULTILINE))) {
                    issues += ValidationIssue(Severity.ERROR,
                        "Missing required frontmatter field '$field' (loader requires name + description)", fileName)
                }
            }

            // Validate name: must match directory name, kebab-case (loader: validateSkillName)
            val nameMatch = Regex("""^name:\s*(\S+)""", RegexOption.MULTILINE).find(frontmatter)
            if (nameMatch != null) {
                val name = nameMatch.groupValues[1]
                if (!name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) {
                    issues += ValidationIssue(Severity.ERROR,
                        "Invalid skill name '$name' — only lowercase letters, digits and single hyphens (loader requirement)",
                        fileName)
                }
                if (name.contains("--")) {
                    issues += ValidationIssue(Severity.ERROR,
                        "Invalid skill name '$name' — must not contain consecutive hyphens ('--')", fileName)
                }
                // name must equal the directory the SKILL.md lives in
                val dirName = fileName.substringBeforeLast('/').substringAfterLast('/')
                if (dirName.isNotBlank() && dirName != "SKILL.md" && dirName != name) {
                    issues += ValidationIssue(Severity.ERROR,
                        "Skill name '$name' must match the directory name '$dirName' (loader requirement)",
                        fileName)
                }
            }

            // Validate description length: loader requires 1..1024
            val descMatch = Regex("""^description:\s*"?(.*?)"?\s*$""", RegexOption.MULTILINE).find(frontmatter)
            if (descMatch != null) {
                val desc = descMatch.groupValues[1].trim()
                if (desc.isEmpty()) {
                    issues += ValidationIssue(Severity.ERROR,
                        "Skill description is empty — loader requires 1..1024 chars", fileName)
                } else if (desc.length > 1024) {
                    issues += ValidationIssue(Severity.ERROR,
                        "Skill description length must be 1..1024, actual=${desc.length}", fileName)
                }
            }

            // allowed-tools is the loader's tool allow-list (space-separated)
            if (!frontmatter.contains(Regex("""^allowed-tools\s*:""", RegexOption.MULTILINE))) {
                issues += ValidationIssue(Severity.INFO,
                    "No 'allowed-tools' — loader will allow no tools unless specified", fileName)
            }
        }

        // Check for markdown structure
        if (!content.contains(Regex("""^#\s+""", RegexOption.MULTILINE))) {
            issues += ValidationIssue(Severity.WARNING,
                "No top-level heading (# Title) found", fileName)
        }

        // Check for at least one section
        val sectionCount = Regex("""^##\s+""", RegexOption.MULTILINE).findAll(content).count()
        if (sectionCount == 0) {
            issues += ValidationIssue(Severity.WARNING,
                "No section headers (##) found — skill should document when/how to use", fileName)
        }

        return ValidationResult.of(issues)
    }

    // ==================== Tool reference cross-checking ====================

    /**
     * Language/library APIs that look like `domain.method(` calls but are NOT
     * Browser4 agent tools.  These must be excluded so cross-referencing does
     * not produce false positives on ordinary code embedded in skills/scripts.
     */
    private val NOISE_DOMAINS: Set<String> = setOf(
        // JVM / Kotlin
        "java", "javax", "kotlin", "kotlinx", "org", "com", "io", "net", "jdk",
        // JS DOM / browser globals
        "document", "window", "console", "navigator", "location", "history",
        "localStorage", "sessionStorage", "screen", "performance", "fetch",
        // JS built-ins
        "JSON", "Math", "Array", "Object", "String", "Number", "Boolean",
        "Date", "RegExp", "Promise", "Map", "Set", "Symbol", "Error", "URL",
        // Python stdlib (common in scripts)
        "os", "sys", "re", "json", "time", "datetime", "pathlib", "subprocess",
        "typing", "collections", "functools", "itertools", "logging",
    )

    /**
     * Cross-check `domain.method(...)` references in an artifact (skill body,
     * README, scripts) against the set of tools the LLM can actually see/call.
     *
     * The known-tools map is assembled by the caller from:
     * - [ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationRenderer] builtin domain specs
     * - [ai.platon.pulsar.agentic.tools.CustomToolRegistry] registered executors (plugins etc.)
     *
     * Grading (false-positive-safe):
     * - ERROR for a domain that is definitely known but the method is not (typo in method)
     * - WARNING for an unknown domain (may be a plugin not installed in this deployment)
     *
     * @param content    artifact text to scan (SKILL.md body, README, etc.)
     * @param knownTools map of tool domain -> set of method names visible to the agent
     * @param fileName   artifact path for issue attribution
     */
    fun validateToolReferences(
        content: String,
        knownTools: Map<String, Set<String>>,
        fileName: String = "SKILL.md"
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (knownTools.isEmpty()) return issues

        // Match domain.method( — tolerates optional whitespace around the dot.
        // We require a call (parenthesis) to avoid matching package names (a.b.c),
        // maven coords (ai.platon.pulsar) and prose like "see tab.eval below".
        val callPattern = Regex("""(?<![.\w])([a-z][a-zA-Z0-9_]*)\s*\.\s*([a-zA-Z][a-zA-Z0-9_]*)\s*\(""")
        val seen = LinkedHashSet<String>()

        callPattern.findAll(content).forEach { m ->
            val domain = m.groupValues[1]
            val method = m.groupValues[2]
            val key = "$domain.$method"
            if (!seen.add(key)) return@forEach // dedupe repeated calls

            // Skip language/library APIs — not agent tools
            if (domain in NOISE_DOMAINS) return@forEach

            val methods = knownTools[domain]
            when {
                methods == null -> issues += ValidationIssue(Severity.WARNING,
                    "Unknown tool domain '$domain' in '$key(' — plugin may not be installed, or a typo? " +
                        "Known domains: ${knownTools.keys.sorted().joinToString(", ")}",
                    fileName)
                method !in methods -> issues += ValidationIssue(Severity.ERROR,
                    "Unknown tool method '$domain.$method' — not in {${methods.sorted().joinToString(", ")}}",
                    fileName)
            }
        }

        return issues
    }

    // ==================== JS ====================

    /**
     * Words after which a `/` opens a regex literal rather than a division.
     *
     * `return /re/.test(s)` is the case that matters in browser scripts; without it the scanner
     * would read the regex body as code and report delimiters that are inside the pattern.
     */
    private val REGEX_PRECEDING_KEYWORDS = setOf(
        "return", "typeof", "instanceof", "case", "delete", "void", "in", "of",
        "new", "do", "else", "yield", "await", "throw",
    )

    /**
     * Validate a browser JS script's structural integrity.
     *
     * This is a static check only. For runtime validation, the agent should
     * use `tab.eval` to execute the script and check for exceptions.
     */
    fun validateJs(content: String, fileName: String = "script.js"): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        // Delimiters are matched on a stack after skipping comments, strings, template literals
        // and regex literals. Counting characters was wrong in both directions: `1)` in a comment
        // produced a false ERROR (the agent rewrote its own prose comments to pass validation),
        // and a stray `)` in a comment could balance a genuinely missing one.
        issues += checkJsDelimiters(content, fileName)

        // The word heuristics below run on code only: `// no return value` used to satisfy the
        // "has return" check and `// eval( is dangerous` produced an eval warning.
        val code = jsCodeOnly(content)

        // Check for return statement (scripts should return a value)
        if (!code.contains("return ")) {
            issues += ValidationIssue(Severity.WARNING,
                "No 'return' statement found — script should return a value for the agent to read", fileName)
        }

        // Check for 'use strict' — a directive *is* a string literal, so this reads the source.
        if (!content.contains("'use strict'") && !content.contains("\"use strict\"")) {
            issues += ValidationIssue(Severity.INFO,
                "No 'use strict' directive — recommended for browser scripts", fileName)
        }

        // Check for common anti-patterns
        if (code.contains("document.write(")) {
            issues += ValidationIssue(Severity.WARNING,
                "document.write() detected — this can corrupt the page DOM", fileName)
        }

        if (code.contains("eval(") && !code.contains("'eval'")) {
            issues += ValidationIssue(Severity.WARNING,
                "eval() detected — can cause performance and security issues", fileName)
        }

        // Check for IIFE pattern (recommended for browser scripts)
        if (!code.contains("(function") && !code.contains("(()")) {
            issues += ValidationIssue(Severity.INFO,
                "No IIFE pattern — consider wrapping in (function(){...})() to avoid polluting global scope", fileName)
        }

        return ValidationResult.of(issues)
    }

    /**
     * JavaScript delimiter check that understands the language's lexical structure.
     *
     * Comments, string literals, template literals (including their `${…}` code spans) and regex
     * literals are skipped, and every closer must match the top of a stack — so `)(` is reported
     * where a raw `count()` would call the script balanced. A raw count made `coding_validate`
     * reject a valid script because a comment listed `1) 2)`, and the agent then rewrote its prose
     * to satisfy the checker before re-validating (2026-11-20 run). The same count also hid a
     * genuinely missing closer behind a `)` in a comment, which only failed later in `tab.eval`.
     *
     * A plain JS string cannot span a raw newline and a regex literal cannot either, so both
     * scanners roll back to "not a literal" instead of swallowing the rest of the script.
     */
    private fun checkJsDelimiters(content: String, fileName: String): List<ValidationIssue> {
        val closers = ArrayList<Char>()
        val openings = ArrayList<Int>()
        // Stack size at each `${` of a template literal: the `}` that brings the size back to it
        // closes the interpolation and the scan resumes inside the template.
        val templateDepths = ArrayDeque<Int>()
        var inTemplate = false
        var i = 0

        fun fail(message: String, at: Int) = listOf(ValidationIssue(
            Severity.ERROR, "Unbalanced $message at ${position(content, at)}", fileName))

        while (i < content.length) {
            val c = content[i]
            if (inTemplate) {
                when {
                    c == '\\' -> i += 2
                    c == '`' -> { inTemplate = false; i++ }
                    c == '$' && i + 1 < content.length && content[i + 1] == '{' -> {
                        templateDepths.addLast(closers.size)
                        inTemplate = false
                        i += 2
                    }
                    else -> i++
                }
                continue
            }
            when {
                c == '/' && i + 1 < content.length && content[i + 1] == '/' -> {
                    while (i < content.length && content[i] != '\n') i++
                }
                c == '/' && i + 1 < content.length && content[i + 1] == '*' -> {
                    val end = content.indexOf("*/", i + 2)
                    if (end < 0) return fail("block comment: '/*' is never closed", i)
                    i = end + 2
                }
                c == '\'' || c == '"' -> {
                    val end = skipQuoted(content, i)
                    if (end < 0) return fail("string literal: '$c' is never closed", i)
                    i = end + 1
                }
                c == '`' -> { inTemplate = true; i++ }
                c == '/' -> {
                    val end = skipRegex(content, i)
                    i = if (end > 0) end + 1 else i + 1
                }
                c == '(' || c == '[' || c == '{' -> {
                    closers.add(closingDelimiter(c))
                    openings.add(i)
                    i++
                }
                c == ')' || c == ']' || c == '}' -> {
                    if (c == '}' && templateDepths.isNotEmpty() && closers.size == templateDepths.last()) {
                        templateDepths.removeLast()
                        inTemplate = true
                        i++
                        continue
                    }
                    val expected = closers.lastOrNull()
                        ?: return fail("'$c': nothing is open before it", i)
                    if (expected != c) {
                        val opener = openingDelimiter(c)
                        return fail(
                            "delimiters: '$opener' at ${position(content, openings.last())} is closed " +
                                "by '$c' before '$expected'",
                            i,
                        )
                    }
                    closers.removeLast()
                    openings.removeAt(openings.lastIndex)
                    i++
                }
                else -> i++
            }
        }

        if (inTemplate) return fail("template literal: '`' is never closed", content.length)
        if (closers.isNotEmpty()) {
            val open = openingDelimiter(closers.first())
            return listOf(ValidationIssue(
                Severity.ERROR,
                "Unbalanced '$open': ${closers.size} never closed, " +
                    "first at ${position(content, openings.first())}",
                fileName,
            ))
        }
        return emptyList()
    }

    /** Index of the closing quote of the literal that starts at [start], or `-1`. */
    private fun skipQuoted(content: String, start: Int): Int {
        val quote = content[start]
        var i = start + 1
        while (i < content.length) {
            when (content[i]) {
                '\\' -> i += 2
                quote -> return i
                // A plain JS string cannot span a raw newline; reporting it beats skipping on.
                '\n' -> return -1
                else -> i++
            }
        }
        return -1
    }

    /**
     * Index of the closing `/` when the `/` at [start] opens a regex literal, otherwise `-1`.
     *
     * Whether a `/` starts a regex or a division depends on the token before it; the heuristic is
     * the same one every JS highlighter uses (an operator/keyword/opening delimiter can precede a
     * regex, an identifier or a number cannot). When in doubt the caller treats it as division,
     * which is the conservative choice: the only cost is a missed regex body.
     */
    private fun skipRegex(content: String, start: Int): Int {
        if (!regexCanStartAfter(previousToken(content, start))) return -1
        var i = start + 1
        var inClass = false
        while (i < content.length) {
            when (content[i]) {
                '\\' -> i += 2
                '\n' -> return -1
                '[' -> { inClass = true; i++ }
                ']' -> { inClass = false; i++ }
                '/' -> if (inClass) i++ else return i
                else -> i++
            }
        }
        return -1
    }

    /** Last significant token before [start]: a word, a single character, or `null` at the start. */
    private fun previousToken(content: String, start: Int): String? {
        var i = start - 1
        while (i >= 0 && content[i].isWhitespace()) i--
        if (i < 0) return null
        if (content[i].isLetterOrDigit() || content[i] == '_' || content[i] == '$') {
            val end = i + 1
            while (i >= 0 && (content[i].isLetterOrDigit() || content[i] == '_' || content[i] == '$')) i--
            return content.substring(i + 1, end)
        }
        return content[i].toString()
    }

    private fun regexCanStartAfter(token: String?): Boolean {
        if (token == null) return true
        if (token.length == 1) return token[0] in "([{,;:=!&|?+-*%<>~^"
        return token in REGEX_PRECEDING_KEYWORDS
    }

    private fun closingDelimiter(open: Char): Char = when (open) {
        '(' -> ')'
        '[' -> ']'
        else -> '}'
    }

    private fun openingDelimiter(close: Char): Char = when (close) {
        ')' -> '('
        ']' -> '['
        else -> '{'
    }

    /** `line N, col M` (1-based) for a character index — what the agent needs to find the spot. */
    private fun position(content: String, index: Int): String {
        val safe = index.coerceIn(0, content.length)
        var line = 1
        var lineStart = 0
        for (i in 0 until safe) {
            if (content[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return "line $line, col ${safe - lineStart + 1}"
    }

    /**
     * [content] with comments and literal bodies blanked to spaces, newlines kept.
     *
     * The word heuristics (`return `, `eval(`, `document.write(`, `(function`) looked at the raw
     * text, so prose satisfied them or tripped them. Blanking comments, strings, template text and
     * regex bodies lets the same cheap `contains` run on code only, and preserving positions means
     * any offset still matches the source.
     *
     * A `${…}` template expression *is* code and is kept: `eval(` inside one is a real call, while
     * the same words in the template text around it are data. The expression is followed by brace
     * depth, the same way [checkJsDelimiters] finds the `}` that resumes the template.
     */
    private fun jsCodeOnly(content: String): String {
        val out = StringBuilder(content.length)
        val templateDepths = ArrayDeque<Int>()
        var inTemplate = false
        var braceDepth = 0
        var i = 0

        fun blankTo(to: Int) {
            val stop = to.coerceAtMost(content.length)
            for (k in i until stop) out.append(if (content[k] == '\n') '\n' else ' ')
            i = stop
        }

        while (i < content.length) {
            val c = content[i]
            if (inTemplate) {
                when {
                    c == '\\' -> blankTo(i + 2)
                    c == '`' -> {
                        out.append(' ')
                        i++
                        inTemplate = false
                    }
                    c == '$' && i + 1 < content.length && content[i + 1] == '{' -> {
                        templateDepths.addLast(braceDepth)
                        out.append("  ")
                        i += 2
                        inTemplate = false
                    }
                    else -> blankTo(i + 1)
                }
                continue
            }
            when {
                c == '/' && i + 1 < content.length && content[i + 1] == '/' -> {
                    var j = i
                    while (j < content.length && content[j] != '\n') j++
                    blankTo(j)
                }
                c == '/' && i + 1 < content.length && content[i + 1] == '*' -> {
                    val end = content.indexOf("*/", i + 2)
                    blankTo(if (end < 0) content.length else end + 2)
                }
                c == '\'' || c == '"' -> {
                    val end = skipQuoted(content, i)
                    blankTo(if (end < 0) content.length else end + 1)
                }
                c == '`' -> {
                    out.append(' ')
                    i++
                    inTemplate = true
                }
                c == '/' -> {
                    val end = skipRegex(content, i)
                    if (end > 0) {
                        blankTo(end + 1)
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '{' -> {
                    braceDepth++
                    out.append(c)
                    i++
                }
                c == '}' -> {
                    if (templateDepths.isNotEmpty() && braceDepth == templateDepths.last()) {
                        // This `}` ends a `${…}`: blank it and resume the template text.
                        templateDepths.removeLast()
                        out.append(' ')
                        i++
                        inTemplate = true
                    } else {
                        braceDepth--
                        out.append(c)
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    // ==================== Script ====================

    /**
     * Validate a simple shell script (PS1 or Bash).
     */
    fun validateScript(content: String, fileName: String = "script"): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        val isPs1 = fileName.endsWith(".ps1")
        val isBash = fileName.endsWith(".sh") || content.startsWith("#!/")
        val detectedShell = when {
            isPs1 -> "powershell"
            isBash -> "bash"
            else -> {
                issues += ValidationIssue(Severity.WARNING,
                    "Cannot determine script type from filename '$fileName' — expected .ps1 or .sh", fileName)
                "unknown"
            }
        }

        when (detectedShell) {
            "powershell" -> {
                // Check for param block
                if (!content.contains("param(") && !content.contains("param (")) {
                    issues += ValidationIssue(Severity.INFO,
                        "No param() block — script cannot accept arguments", fileName)
                }

                // Check for error action preference
                if (!content.contains("ErrorActionPreference")) {
                    issues += ValidationIssue(Severity.INFO,
                        "No \$ErrorActionPreference set — errors may be silently ignored", fileName)
                }

                // Braces only, and only outside comments/strings/here-strings: `# usage: { -Path }`
                // and `'{"a":1}'` are prose and data, not blocks.
                issues += checkPowershellBraces(content, fileName)

                // Windows PowerShell 5.1 reads a BOM-less `.ps1` as ANSI, so a non-ASCII literal
                // is corrupted before the script runs (measured 2026-11-20: the same file prints
                // 中文 under `pwsh` and 涓枃 under `powershell`). `coding.write` writes UTF-8
                // without a BOM, so this is the default state of an agent-written script.
                if (content.any { it.code > 0x7F }) {
                    issues += ValidationIssue(Severity.WARNING,
                        "Non-ASCII text in a .ps1 — Windows PowerShell 5.1 reads a BOM-less script as " +
                            "ANSI and corrupts it; run it with `pwsh`, add a UTF-8 BOM, or keep it ASCII",
                        fileName)
                }
            }
            "bash" -> {
                // Check for shebang
                if (!content.startsWith("#!/")) {
                    issues += ValidationIssue(Severity.WARNING,
                        "Missing shebang line (#!/usr/bin/env bash) — script may execute with wrong interpreter", fileName)
                }

                // Check for set -e (error handling)
                if (!content.contains("set -e")) {
                    issues += ValidationIssue(Severity.INFO,
                        "No 'set -e' — script will continue after errors", fileName)
                }

                // Check for set -u (unset variable check)
                if (!content.contains("set -u") && !content.contains("set -eu")) {
                    issues += ValidationIssue(Severity.INFO,
                        "No 'set -u' — unset variables will expand to empty string", fileName)
                }

                // Check for quote usage (common issue: unquoted variables)
                val unquotedVarRegex = Regex("""\$\w+\s""")
                if (unquotedVarRegex.containsMatchIn(content) && !content.contains("\"$")) {
                    issues += ValidationIssue(Severity.INFO,
                        "Possible unquoted variables — consider using \"\$VAR\" instead of \$VAR", fileName)
                }
            }
        }

        return ValidationResult.of(issues)
    }

    // ==================== Shared Helpers ====================

    /**
     * PowerShell brace check that skips comments, quoted strings and here-strings.
     *
     * The raw `count { it == '{' }` this replaces reported `# usage: { -Path … }` and the JSON in
     * `$body = '{"a":1}'` as an unbalanced script — the same false positive the JS check had, and
     * the agent then rewrote working scripts to satisfy it. A brace in a string could also *hide*
     * a genuinely missing one. Only `{`/`}` are structural here: `@(…)` / `$(…)` are expressions,
     * and their parentheses are what the language itself balances.
     */
    private fun checkPowershellBraces(content: String, fileName: String): List<ValidationIssue> {
        val openings = ArrayList<Int>()
        var i = 0

        fun fail(message: String, at: Int) = listOf(ValidationIssue(
            Severity.ERROR, "Unbalanced $message at ${position(content, at)}", fileName))

        while (i < content.length) {
            when {
                content.startsWith("<#", i) -> {
                    val end = content.indexOf("#>", i + 2)
                    if (end < 0) return fail("block comment: '<#' is never closed", i)
                    i = end + 2
                }
                content[i] == '#' -> {
                    while (i < content.length && content[i] != '\n') i++
                }
                content[i] == '\'' -> {
                    val end = skipPowerShellSingleQuoted(content, i)
                    if (end < 0) return fail("string literal: \"'\" is never closed", i)
                    i = end + 1
                }
                content[i] == '"' -> {
                    val end = skipPowerShellDoubleQuoted(content, i)
                    if (end < 0) return fail("string literal: '\"' is never closed", i)
                    i = end + 1
                }
                content[i] == '@' && isHereStringStart(content, i) -> {
                    val quote = content[i + 1]
                    val terminator = "\n$quote@"
                    val end = content.indexOf(terminator, i + 2)
                    if (end < 0) return fail("here-string: '@$quote' is never closed", i)
                    i = end + terminator.length
                }
                content[i] == '{' -> {
                    openings.add(i)
                    i++
                }
                content[i] == '}' -> {
                    if (openings.isEmpty()) return fail("'}': nothing is open before it", i)
                    openings.removeAt(openings.lastIndex)
                    i++
                }
                else -> i++
            }
        }

        if (openings.isNotEmpty()) {
            return listOf(ValidationIssue(
                Severity.ERROR,
                "Unbalanced '{': ${openings.size} never closed, " +
                    "first at ${position(content, openings.first())}",
                fileName,
            ))
        }
        return emptyList()
    }

    /**
     * `@'` / `@"` followed immediately by a line break — that is what opens a here-string.
     *
     * The opener may follow an assignment (`$x = @"`); only the *terminator* has to start a line,
     * which the caller searches for as `\n'@` / `\n"@`.
     */
    private fun isHereStringStart(content: String, index: Int): Boolean {
        if (index + 1 >= content.length) return false
        val quote = content[index + 1]
        if (quote != '\'' && quote != '"') return false
        var i = index + 2
        if (i < content.length && content[i] == '\r') i++
        return i >= content.length || content[i] == '\n'
    }

    /** Index of the closing `'` of the literal at [start]; `''` is an escaped quote. */
    private fun skipPowerShellSingleQuoted(content: String, start: Int): Int {
        var i = start + 1
        while (i < content.length) {
            if (content[i] == '\'') {
                if (i + 1 < content.length && content[i + 1] == '\'') {
                    i += 2
                    continue
                }
                return i
            }
            i++
        }
        return -1
    }

    /** Index of the closing `"` of the literal at [start]; a backtick escapes, `""` is a quote. */
    private fun skipPowerShellDoubleQuoted(content: String, start: Int): Int {
        var i = start + 1
        while (i < content.length) {
            when (content[i]) {
                '`' -> i += 2
                '"' -> {
                    if (i + 1 < content.length && content[i + 1] == '"') i += 2 else return i
                }
                else -> i++
            }
        }
        return -1
    }
}
