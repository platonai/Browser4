package ai.platon.pulsar.coding

/**
 * High-level dev-task planner for Browser4 self-development.
 *
 * `coding.devTask("<task description>")` turns a natural-language task into an
 * executable plan following the AGENTS.md development flow: locate the affected
 * code → impact analysis → compile check → smallest-scope test → CDP trap check
 * (browser-driver code) → repo-governance validation → commit guidance.
 *
 * The planner is PURE (no I/O): it parses the task text for module mentions,
 * file paths, and tool references, then emits ordered [PlanStep]s the executor
 * can either render (verify=false) or execute (verify=true).
 */
object DevTaskPlanner {

    /** One executable step of the plan. */
    data class PlanStep(
        val order: Int,
        val tool: String,
        val purpose: String,
        val command: String,
        val args: Map<String, String> = emptyMap(),
    )

    /** The parsed plan for a task. */
    data class DevPlan(
        val summary: String,
        val modules: List<String>,
        val files: List<String>,
        val driverFiles: List<String>,
        val steps: List<PlanStep>,
    )

    private val FILE_PATTERN = Regex("""[\w./\\-]+\.(?:kt|kts|rs|java|scala|groovy|js|jsx|ts|tsx|py|go|rb|php|swift|sh|bash|ps1|md|json|xml|yaml|yml|toml|properties|sql|gradle|proto|h)""")
    private val CODING_TOOL_PATTERN = Regex("""coding\.([a-zA-Z]+)""")

    /**
     * Parse a task description into a dev plan.
     *
     * @param task the natural-language task (e.g. "fix mouseWheel in
     *   PulsarWebDriver.kt and add a test in browser4-rest")
     * @return the plan; [DevPlan.modules]/[DevPlan.files] are the signals found,
     *   [DevPlan.steps] the ordered execution plan
     */
    fun plan(task: String): DevPlan {
        val files = FILE_PATTERN.findAll(task).map { it.value.replace('\\', '/') }
            .distinct().toList()
        val modules = inferModules(task, files)
        val driverFiles = files.filter { it.contains("/browser4-browser/") || it.endsWith("PulsarWebDriver.kt") }
        val steps = buildSteps(task, modules, files, driverFiles)

        return DevPlan(
            summary = summarize(task, modules, files, driverFiles),
            modules = modules,
            files = files,
            driverFiles = driverFiles,
            steps = steps,
        )
    }

    // ==================== parsing ====================

    /** Module names: explicit `browser4-*` mentions normalized against [ModuleMap.MODULES]. */
    private fun inferModules(task: String, files: List<String>): List<String> {
        val found = linkedSetOf<String>()

        // Normalize direct module mentions: "browser4-browser" → "browser4-core/browser4-browser".
        val mentions = Regex("""browser4-[\w-]+(?=/[\w-]+)?""").findAll(task)
            .map { it.value }.distinct().toList()
        mentions.forEach { mention ->
            val hit = ModuleMap.MODULES.firstOrNull { it.endsWith(mention) || it == mention }
            if (hit != null) found.add(hit)
        }

        // Infer from file paths (workspace-relative).
        files.forEach { file ->
            inferModuleFromPath(file)?.let { found.add(it) }
        }

        // CLI crate signals.
        if (files.any { it.contains("/cli/") || it.endsWith(".rs") } || "cli" in mentions) {
            found.add(ModuleMap.CLI_CRATE)
        }

        return found.toList()
    }

    /** Lightweight module inference from a relative path (mirrors the executor's). */
    private fun inferModuleFromPath(file: String): String? {
        val norm = file.replace('\\', '/')
        val idx = norm.indexOf("/src/")
        if (idx <= 0) return null
        val before = norm.substring(0, idx)
        val segments = before.split('/').filter { it.isNotEmpty() }
        return when {
            segments.size >= 2 && segments[segments.size - 2].startsWith("browser4-") ->
                segments.takeLast(2).joinToString("/")
            else -> segments.lastOrNull()
        }
    }

    // ==================== planning ====================

    private fun buildSteps(
        task: String,
        modules: List<String>,
        files: List<String>,
        driverFiles: List<String>,
    ): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        var order = 1

        // 1. Locate the code the task touches.
        val readPath = files.firstOrNull()
        if (readPath != null) {
            steps += PlanStep(order++, "coding.read",
                "Read the file(s) the task touches to ground the change in real code",
                "coding.read(path=\"$readPath\")", mapOf("path" to readPath))
        }

        // 2. Impact analysis — which module owns the change and who depends on it.
        val impactPath = files.firstOrNull() ?: modules.firstOrNull()
        if (impactPath != null) {
            steps += PlanStep(order++, "coding.impact",
                "Assess the blast radius: owning module, transitive dependents, suggested test commands",
                "coding.impact(path=\"$impactPath\")", mapOf("path" to impactPath))
        }

        // 3. Compile check of the affected module (or cargo for the CLI crate).
        val mavenModule = modules.firstOrNull { it != ModuleMap.CLI_CRATE }
        if (mavenModule != null) {
            steps += PlanStep(order++, "coding.mvnBuild",
                "Compile the affected module to catch Kotlin/Java errors before tests",
                "coding.mvnBuild(module=\"$mavenModule\", goals=\"compile\")",
                mapOf("module" to mavenModule, "goals" to "compile"))
        }
        if (ModuleMap.CLI_CRATE in modules || task.contains("cargo") || task.contains("cli command")) {
            steps += PlanStep(order++, "coding.shell",
                "Fast Rust CLI unit tests (no backend needed)",
                "coding.shell(command=\"${ModuleMap.cargoTestCommand()}\")",
                mapOf("command" to ModuleMap.cargoTestCommand()))
        }

        // 4. Smallest-scope test for the affected module.
        if (mavenModule != null) {
            steps += PlanStep(order++, "coding.shell",
                "Run the module's smallest relevant test scope",
                "coding.shell(command=\"${ModuleMap.mavenTestCommand(mavenModule)}\")",
                mapOf("command" to ModuleMap.mavenTestCommand(mavenModule)))
        }

        // 5. CDP trap awareness for browser-driver code.
        driverFiles.take(1).forEach { file ->
            steps += PlanStep(order++, "coding.trapCheck",
                "Browser-driver code — check for the documented CDP pitfalls before editing",
                "coding.trapCheck(path=\"$file\")", mapOf("path" to file))
        }

        // 6. Repo governance: versions and module registration stay consistent.
        steps += PlanStep(order++, "coding.validate",
            "Verify repo governance (VERSION vs root pom vs BOM vs module registration)",
            "coding.validate(type=\"repo-consistency\")",
            mapOf("type" to "repo-consistency"))

        // 7. Commit guidance.
        steps += PlanStep(order++, "coding.shell",
            "Commit the change on the current branch with a focused message",
            "git add -A && git commit -m \"<summary>\"",
            mapOf("command" to "git add -A && git commit"))

        return steps
    }

    private fun summarize(task: String, modules: List<String>, files: List<String>, driverFiles: List<String>): String {
        val parts = mutableListOf<String>()
        if (modules.isNotEmpty()) parts.add("modules: ${modules.joinToString(", ")}")
        if (files.isNotEmpty()) parts.add("files: ${files.joinToString(", ")}")
        if (driverFiles.isNotEmpty()) parts.add("⚠ browser-driver code involved (CDP pitfalls apply)")
        return if (parts.isEmpty()) "No module/file signals found in the task — the agent should clarify scope."
        else parts.joinToString(" | ")
    }
}
