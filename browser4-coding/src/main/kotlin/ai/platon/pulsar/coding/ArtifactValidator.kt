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
                        "use a literal version like 4.13.4-SNAPSHOT", fileName)
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

        // Simple brace balance check
        val braceBalance = content.count { it == '{' } - content.count { it == '}' }
        if (braceBalance != 0) {
            issues += ValidationIssue(Severity.ERROR,
                "Unbalanced braces: difference of $braceBalance", fileName)
        }

        // Simple paren balance check
        val parenBalance = content.count { it == '(' } - content.count { it == ')' }
        if (parenBalance != 0) {
            issues += ValidationIssue(Severity.ERROR,
                "Unbalanced parentheses: difference of $parenBalance", fileName)
        }

        return issues
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
     * Validate a browser JS script's structural integrity.
     *
     * This is a static check only. For runtime validation, the agent should
     * use `tab.eval` to execute the script and check for exceptions.
     */
    fun validateJs(content: String, fileName: String = "script.js"): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        // Check bracket balance
        issues += checkBracketBalance(content, '(', ')', fileName)
        issues += checkBracketBalance(content, '{', '}', fileName)
        issues += checkBracketBalance(content, '[', ']', fileName)

        // Check for return statement (scripts should return a value)
        if (!content.contains("return ")) {
            issues += ValidationIssue(Severity.WARNING,
                "No 'return' statement found — script should return a value for the agent to read", fileName)
        }

        // Check for 'use strict'
        if (!content.contains("'use strict'") && !content.contains("\"use strict\"")) {
            issues += ValidationIssue(Severity.INFO,
                "No 'use strict' directive — recommended for browser scripts", fileName)
        }

        // Check for common anti-patterns
        if (content.contains("document.write(")) {
            issues += ValidationIssue(Severity.WARNING,
                "document.write() detected — this can corrupt the page DOM", fileName)
        }

        if (content.contains("eval(") && !content.contains("'eval'")) {
            issues += ValidationIssue(Severity.WARNING,
                "eval() detected — can cause performance and security issues", fileName)
        }

        // Check for IIFE pattern (recommended for browser scripts)
        if (!content.contains("(function") && !content.contains("(()")) {
            issues += ValidationIssue(Severity.INFO,
                "No IIFE pattern — consider wrapping in (function(){...})() to avoid polluting global scope", fileName)
        }

        return ValidationResult.of(issues)
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

                // Check for brace balance
                issues += checkBracketBalance(content, '{', '}', fileName)
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
     * Check that opening and closing brackets are balanced.
     */
    private fun checkBracketBalance(
        content: String,
        open: Char,
        close: Char,
        fileName: String
    ): List<ValidationIssue> {
        val openCount = content.count { it == open }
        val closeCount = content.count { it == close }
        val diff = openCount - closeCount

        return if (diff != 0) {
            listOf(ValidationIssue(
                Severity.ERROR,
                "Unbalanced '$open$close': $openCount open vs $closeCount close (diff=$diff)",
                fileName
            ))
        } else {
            emptyList()
        }
    }
}

