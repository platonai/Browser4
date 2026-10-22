package ai.platon.pulsar.coding

/**
 * High-level dev-task planner for Browser4 self-development.
 *
 * `coding.devTask("<task description>")` turns a natural-language task into an
 * executable plan following the AGENTS.md development flow: locate the affected
 * code → impact analysis → implement (agent contract) → compile check →
 * smallest-scope test → CDP trap check (browser-driver code) → repo-governance
 * validation → deterministic verification.
 *
 * The planner is PURE (no I/O): it parses the task text for module mentions,
 * file paths, and tool references, then emits ordered [PlanStep]s the executor
 * can render or execute. Bare filenames are resolved through the
 * [plan] `fileIndex` the caller builds from the live workspace — the planner
 * never walks the tree itself.
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
        /** Test class names mentioned in the task (FooTest / FooTests), for -Dtest scoping. */
        val testClasses: List<String>,
        /** `browser4-plugins/<name>` mentions that are NOT in the known module list — new modules to scaffold. */
        val newPluginModules: List<String>,
        /**
         * The module the **test** step binds to — the module that owns the named
         * test class, which is not necessarily [modules]' deepest entry (a task
         * can edit a driver file and add the test elsewhere). Single source of
         * truth for the rendered test step, the acceptance checklist and the
         * `verify(testModule=…)` call: running `-Dtest=` in a module that does
         * not contain the class executes zero tests and exits 0.
         */
        val testTarget: String? = null,
        val steps: List<PlanStep>,
        /**
         * Human/machine acceptance checklist the plan must satisfy before the
         * task counts as done (plan P1.2). Rendered as one `Acceptance: [...]`
         * line so a supervisor and the agent read the same contract.
         */
        val acceptance: List<String> = emptyList(),
    )

    // Extension alternatives sorted longest-first so `json` wins over `js`; the
    // trailing (?![\w]) prevents matching a prefix of a longer token
    // (e.g. "browser4-plugin.json" must yield "browser4-plugin.json", not ".js").
    private val FILE_PATTERN = Regex("""[\w./\\-]+\.(?:json|kt|kts|rs|java|scala|groovy|js|jsx|ts|tsx|py|go|rb|php|swift|sh|bash|ps1|md|xml|yaml|yml|toml|properties|sql|gradle|proto|h)(?![\w])""")
    private val CODING_TOOL_PATTERN = Regex("""coding\.([a-zA-Z]+)""")
    // FooTest / FooTests (uppercase start; bare "Test" in prose does not match).
    private val TEST_CLASS_PATTERN = Regex("""\b([A-Z][A-Za-z0-9]*(?:Test|Tests))\b""")

    /** Pseudo-tool of the "implement this, agent" contract step (never dispatched by the executor). */
    const val IMPLEMENT_TOOL = "coding.implement"

    /**
     * Parse a task description into a dev plan.
     *
     * @param task the natural-language task (e.g. "fix mouseWheel in
     *   PulsarWebDriver.kt and add a test in browser4-rest")
     * @param knownModules module paths to normalize mentions against. Defaults
     *   to the static [ModuleMap.MODULES] snapshot; callers with workspace
     *   access should pass the LIVE module list from [ModuleGraph] so mentions
     *   resolve against the real pom topology.
     * @param fileIndex bare filename → workspace-relative path, built by the
     *   caller from the live workspace. A bare filename (`PulsarWebDriver.kt`)
     *   is otherwise unresolvable from the repo root, which used to bind the
     *   plan to no module at all (eval K1). Empty = leave bare names untouched.
     * @return the plan; [DevPlan.modules]/[DevPlan.files] are the signals found,
     *   [DevPlan.steps] the ordered execution plan
     */
    fun plan(
        task: String,
        knownModules: List<String> = ModuleMap.MODULES,
        fileIndex: Map<String, String> = emptyMap(),
    ): DevPlan {
        val files = FILE_PATTERN.findAll(task).map { it.value.replace('\\', '/') }
            .map { raw -> if (raw.contains('/')) raw else fileIndex[raw] ?: raw }
            .distinct().toList()
        val newPluginModules = inferNewPluginModules(task, knownModules)
        // Explicit task mentions and file-derived modules are tracked separately:
        // the build targets the deepest affected module (a driver file pulls in
        // browser4-core/browser4-browser), while a named test class still binds
        // to the module the task EXPLICITLY names (eval K1 must not move
        // FillCursorTest out of browser4-rest).
        val explicitModules = dropAncestorSignals(inferExplicitModules(task, knownModules), newPluginModules)
        val fileModules = inferFileModules(files)
        val cliCrate = ModuleMap.CLI_CRATE in explicitModules ||
            files.any { it.contains("/cli/") || it.endsWith(".rs") } ||
            task.contains("cargo") || task.contains("cli command")
        val allSignals = (explicitModules + fileModules +
            if (cliCrate) listOf(ModuleMap.CLI_CRATE) else emptyList()).distinct()
        val modules = dropAncestorSignals(allSignals, newPluginModules)
        val driverFiles = files.filter {
            it.contains("/browser4-browser/") ||
                it.endsWith("PulsarWebDriver.kt") ||
                it.endsWith("Browser4WebDriver.kt")
        }
        val testClasses = TEST_CLASS_PATTERN.findAll(task).map { it.groupValues[1] }
            .filter { it != "Test" }.distinct().toList()
        val targetModule = selectTargetModule(modules, newPluginModules)
        val testTarget = selectTestTarget(modules, explicitModules, newPluginModules, knownModules, testClasses)
        val acceptance = buildAcceptance(targetModule, testTarget, cliCrate, testClasses, driverFiles.firstOrNull())
        val steps = buildSteps(
            task, modules, files, driverFiles, testClasses, newPluginModules,
            knownModules, targetModule, testTarget, acceptance, hasSignal = files.isNotEmpty() ||
            modules.isNotEmpty() || newPluginModules.isNotEmpty(),
        )

        return DevPlan(
            summary = summarize(task, modules, files, driverFiles, testClasses, newPluginModules),
            modules = modules,
            files = files,
            driverFiles = driverFiles,
            testClasses = testClasses,
            newPluginModules = newPluginModules,
            testTarget = testTarget,
            steps = steps,
            acceptance = acceptance,
        )
    }

    /** Bare filenames (no directory part) mentioned in [task] — the caller resolves them. */
    fun bareFileNames(task: String): List<String> =
        FILE_PATTERN.findAll(task)
            .map { it.value.replace('\\', '/') }
            .filter { !it.contains('/') }
            .distinct()
            .toList()

    // ==================== parsing ====================

    /** Modules the task text names explicitly (`browser4-*` tokens normalized against [knownModules]). */
    private fun inferExplicitModules(task: String, knownModules: List<String>): List<String> {
        val found = linkedSetOf<String>()
        // Normalize direct module mentions: "browser4-browser" → "browser4-core/browser4-browser".
        Regex("""browser4-[\w-]+(?=/[\w-]+)?""").findAll(task)
            .map { it.value }.distinct().toList()
            .forEach { mention ->
                val hit = knownModules.firstOrNull { it.endsWith(mention) || it == mention }
                if (hit != null) found.add(hit)
            }
        return found.toList()
    }

    /** Modules implied by the workspace-relative file paths in the task. */
    private fun inferFileModules(files: List<String>): List<String> =
        files.mapNotNull { inferModuleFromPath(it) }.distinct()

    /**
     * Drop aggregator signals: when both `browser4-plugins` and a module inside
     * it are mentioned, only the leaf is a real signal. Counting the aggregator
     * as a module made `impact` point at the parent and disabled test-class
     * binding (eval K3).
     */
    private fun dropAncestorSignals(modules: List<String>, newPluginModules: List<String>): List<String> {
        if (modules.isEmpty()) return modules
        val scope = (modules + newPluginModules).distinct()
        return modules.filter { module ->
            scope.none { other -> other != module && other.startsWith("$module/") }
        }
    }

    /**
     * The module a build/test step should target: the deepest module path wins
     * (a leaf like `browser4-plugins/browser4-seo` over its aggregator), and a
     * freshly scaffolded new module wins a tie against same-depth DEPENDENTS-key
     * mentions because it is listed first (eval K2).
     */
    private fun selectTargetModule(modules: List<String>, newPluginModules: List<String>): String? =
        (newPluginModules + modules)
            .filter { it != ModuleMap.CLI_CRATE }
            .maxByOrNull { module -> module.count { it == '/' } }

    /**
     * The module the test step binds to (single source of truth for the rendered
     * test command, the acceptance checklist and `verify(testModule=…)`).
     *
     * Binding priority: the module a class name implies (only when the task
     * names no module) → the module the task EXPLICITLY names → the deepest
     * affected module. Without the explicit-module step, a driver file being
     * edited alongside a test would move the test to the driver's module — and
     * `mvn test -pl <driver module> -Dtest=<class elsewhere>` then runs *zero*
     * tests while exiting 0 (`-Dsurefire.failIfNoSpecifiedTests=false` makes
     * that look green).
     */
    private fun selectTestTarget(
        modules: List<String>,
        explicitModules: List<String>,
        newPluginModules: List<String>,
        knownModules: List<String>,
        testClasses: List<String>,
    ): String? {
        val bindingCandidates = if (modules.isEmpty()) knownModules else emptyList()
        val testClassBound = testClasses.firstNotNullOfOrNull { inferModuleForTestClass(it, bindingCandidates) }
        val mentionedTarget = selectTargetModule(explicitModules, newPluginModules)
        return testClassBound ?: mentionedTarget ?: selectTargetModule(modules, newPluginModules)
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

    /** `browser4-plugins/<name>` mentions that do NOT exist in [knownModules] — new modules to scaffold. */
    private fun inferNewPluginModules(task: String, knownModules: List<String>): List<String> {
        return Regex("""browser4-plugins/browser4-[\w-]+""")
            .findAll(task).map { it.value }.distinct()
            .filter { it !in knownModules }
            .sorted()
            .toList()
    }

    /**
     * Best-effort mapping of a test class to the module that owns it, by matching
     * the class prefix against module basenames (PagetitleConfigTest →
     * browser4-plugins/browser4-pagetitle, PulsarWebDriverTest →
     * browser4-core/browser4-browser). Module short names strip the `browser4-`
     * prefix and hyphens, so camelCase class prefixes compare against kebab-case
     * module names. Returns null when no module matches.
     */
    private fun inferModuleForTestClass(testClass: String, knownModules: List<String>): String? {
        val prefix = testClass.removeSuffix("Tests").removeSuffix("Test").lowercase()
        if (prefix.length < 4) return null
        fun shortName(module: String): String =
            module.substringAfterLast('/').lowercase().removePrefix("browser4-").replace("-", "")
        return knownModules.firstOrNull { prefix.contains(shortName(it)) && shortName(it).length >= 4 }
            ?: knownModules.firstOrNull { shortName(it).contains(prefix) && prefix.length >= 4 }
    }

    // ==================== planning ====================

    /**
     * Complete the module prefix for workspace-relative paths that live inside
     * a NEW plugin module: `src/main/resources/pagetitle/countPages.js` must
     * resolve as `browser4-plugins/<name>/src/...` because the workspace root is
     * the repo root (v1.3 fixed bare filenames only). Paths already rooted at a
     * known module — or at a well-known top-level repo directory — are left
     * untouched, and no prefix is added when the task creates no new module.
     */
    private fun resolvePlannedPath(
        raw: String, knownModules: List<String>, newPluginModules: List<String>,
    ): String {
        val norm = raw.replace('\\', '/')
        if (!norm.contains('/')) return norm
        val firstSeg = norm.substringBefore('/')
        val moduleRooted = knownModules.any { it == firstSeg || it.startsWith("$firstSeg/") }
        if (moduleRooted || firstSeg in TOP_LEVEL_DIRS) return norm
        val newModule = newPluginModules.firstOrNull()
        return if (newModule != null) "$newModule/$norm" else norm
    }

    /**
     * The acceptance checklist (plan P1.2). Derived from the same signals the
     * steps are built from, so the two cannot drift.
     */
    private fun buildAcceptance(
        targetModule: String?,
        testTarget: String?,
        cliCrate: Boolean,
        testClasses: List<String>,
        trapFile: String?,
    ): List<String> {
        val items = mutableListOf<String>()
        if (cliCrate) items += "cargo test --bin browser4-cli"
        if (targetModule != null) items += "compile $targetModule"
        // The test item names the module that OWNS the class, not the deepest
        // compiled one — otherwise the checklist advertises a suite that the
        // test step does not run (and `-Dtest=` would match nothing there).
        if (testTarget != null && testClasses.isNotEmpty()) {
            items += "test ${testClasses.joinToString(",")} in $testTarget"
        }
        items += "validate repo-consistency"
        if (trapFile != null) items += "trapCheck $trapFile"
        return items
    }

    /** Escape a value for the single-line `tool(arg="…")` rendering. */
    private fun escapeArg(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace(Regex("[\\r\\n]+"), " ").trim()

    private fun buildSteps(
        task: String,
        modules: List<String>,
        files: List<String>,
        driverFiles: List<String>,
        testClasses: List<String>,
        newPluginModules: List<String>,
        knownModules: List<String>,
        targetModule: String?,
        testTarget: String?,
        acceptance: List<String>,
        hasSignal: Boolean,
    ): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        var order = 1

        // 0. New plugin modules: scaffold first — the rest of the plan (build/test/
        //    validate) then applies to the new module.
        newPluginModules.forEach { plugin ->
            val pluginName = plugin.substringAfterLast('/')
            steps += PlanStep(order++, "coding.scaffoldToDir",
                "Scaffold the new plugin module $pluginName (skeleton files, aggregator pom registration, ModuleMap sync)",
                "coding.scaffoldToDir(type=\"plugin\", dir=\"$plugin\", name=\"$pluginName\", pluginName=\"$pluginName\", verify=true)",
                mapOf("type" to "plugin", "dir" to plugin, "name" to pluginName,
                    "pluginName" to pluginName, "verify" to "true"))
        }

        // 1. Locate the code the task touches. Bare filenames (e.g.
        //    "HelloService.kt" with no directory part) do not resolve from the
        //    workspace root — list the owning module directory instead so the
        //    agent sees the real layout (e.g. right after scaffolding a new
        //    plugin module).
        val readPath = files.firstOrNull()?.let { resolvePlannedPath(it, knownModules, newPluginModules) }
        if (readPath != null) {
            if (readPath.contains('/')) {
                steps += PlanStep(order++, "coding.read",
                    "Read the file(s) the task touches to ground the change in real code",
                    "coding.read(path=\"$readPath\")", mapOf("path" to readPath))
            } else {
                val locateDir = newPluginModules.firstOrNull() ?: targetModule
                if (locateDir != null) {
                    steps += PlanStep(order++, "coding.listDir",
                        "Locate the file(s) the task touches inside $locateDir (bare filename given, no workspace-relative path)",
                        "coding.listDir(path=\"$locateDir\", maxDepth=8)",
                        mapOf("path" to locateDir, "maxDepth" to "8"))
                }
            }
        }

        // 2. Impact analysis — which module owns the change and who depends on it.
        //    Same "deepest module" rule as the build step (eval K2): the
        //    aggregator must never be the impact target while the build targets
        //    the leaf.
        val resolvedImpactFile = files.firstOrNull()
            ?.let { resolvePlannedPath(it, knownModules, newPluginModules) }
        val impactPath = when {
            resolvedImpactFile?.contains('/') == true -> resolvedImpactFile
            newPluginModules.isNotEmpty() -> newPluginModules.first()
            else -> targetModule
        }
        if (impactPath != null) {
            steps += PlanStep(order++, "coding.impact",
                "Assess the blast radius: owning module, transitive dependents, suggested test commands",
                "coding.impact(path=\"$impactPath\")", mapOf("path" to impactPath))
        }

        // 3. Implementation contract (plan P1.1). Not mechanically executable:
        //    it names the target files and the acceptance checklist so the agent
        //    (or `agent run`) has an explicit "what to change, when is it done".
        if (hasSignal) {
            val implementTargets = files.filter { it.contains('/') }.joinToString(",")
            steps += PlanStep(order++, IMPLEMENT_TOOL,
                "Implement the change in ${implementTargets.ifBlank { targetModule ?: "the affected module" }} " +
                    "— agent step, not mechanically executable",
                "coding.implement(task=\"${escapeArg(task)}\", " +
                    "files=\"${escapeArg(implementTargets)}\", " +
                    "acceptance=\"${escapeArg(acceptance.joinToString("; "))}\")",
                mapOf("task" to task, "files" to implementTargets,
                    "acceptance" to acceptance.joinToString("; ")))
        }

        // 4. Compile check of the affected module (or cargo for the CLI crate).
        if (targetModule != null) {
            steps += PlanStep(order++, "coding.mvnBuild",
                "Compile the affected module to catch Kotlin/Java errors before tests",
                "coding.mvnBuild(module=\"$targetModule\", goals=\"compile\")",
                mapOf("module" to targetModule, "goals" to "compile"))
        }
        if (ModuleMap.CLI_CRATE in modules || task.contains("cargo") || task.contains("cli command")) {
            steps += PlanStep(order++, "coding.shell",
                "Fast Rust CLI unit tests (no backend needed)",
                "coding.shell(command=\"${ModuleMap.cargoTestCommand()}\")",
                mapOf("command" to ModuleMap.cargoTestCommand()))
        }

        // 5. Smallest-scope test for the affected module. When the task names a
        //    test class (FooTest), scope with -Dtest=... instead of the whole suite.
        //    The binding is computed once in [selectTestTarget] and shared with
        //    the acceptance checklist / verify call, so the three cannot drift.
        if (testTarget != null) {
            val testClassArg = testClasses.joinToString(",")
            val command = ModuleMap.mavenTestCommand(testTarget, testClassArg.ifBlank { null })
            steps += PlanStep(order++, "coding.shell",
                if (testClassArg.isBlank()) "Run the module's smallest relevant test scope"
                else "Run the named test class(es) ($testClassArg) in their owning module — smallest scope",
                "coding.shell(command=\"$command\")",
                mapOf("command" to command))
        }

        // 6. CDP trap awareness for browser-driver code.
        driverFiles.take(1).forEach { file ->
            steps += PlanStep(order++, "coding.trapCheck",
                "Browser-driver code — check for the documented CDP pitfalls before editing",
                "coding.trapCheck(path=\"$file\")", mapOf("path" to file))
        }

        // 7. Repo governance: versions, module registration AND ModuleMap sync stay consistent.
        steps += PlanStep(order++, "coding.validate",
            "Verify repo governance (VERSION vs root pom vs BOM vs module registration vs ModuleMap snapshot)",
            "coding.validate(type=\"repo-consistency\")",
            mapOf("type" to "repo-consistency"))

        // 8. Deterministic verification gate (plan P0/P1.1): the verdict is the
        //    authority on completion, not the agent's own narrative.
        if (hasSignal) {
            val verifyModule = targetModule ?: ModuleMap.CLI_CRATE.takeIf { ModuleMap.CLI_CRATE in modules }
            if (verifyModule != null) {
                val trapArg = driverFiles.take(1).joinToString(",")
                steps += PlanStep(order++, "coding.verify",
                    "Deterministic verification gate — the VERDICT_JSON verdict decides whether the task is done",
                    "coding.verify(module=\"$verifyModule\", checks=\"compile,repo-consistency,trapCheck\"" +
                        (if (trapArg.isBlank()) "" else ", files=\"$trapArg\"") + ")",
                    buildMap {
                        put("module", verifyModule)
                        put("checks", "compile,repo-consistency,trapCheck")
                        if (trapArg.isNotBlank()) put("files", trapArg)
                    })
            }
        }

        // NOTE: no git-commit step here. Agents must not auto-commit; the
        // supervising caller decides when a change is ready to land.

        return steps
    }

    private fun summarize(
        task: String, modules: List<String>, files: List<String>,
        driverFiles: List<String>, testClasses: List<String>,
        newPluginModules: List<String>,
    ): String {
        val parts = mutableListOf<String>()
        if (modules.isNotEmpty()) parts.add("modules: ${modules.joinToString(", ")}")
        if (newPluginModules.isNotEmpty()) parts.add("new plugin modules: ${newPluginModules.joinToString(", ")}")
        if (files.isNotEmpty()) parts.add("files: ${files.joinToString(", ")}")
        if (testClasses.isNotEmpty()) parts.add("tests: ${testClasses.joinToString(", ")}")
        if (driverFiles.isNotEmpty()) parts.add("⚠ browser-driver code involved (CDP pitfalls apply)")
        return if (parts.isEmpty()) "No module/file signals found in the task — the agent should clarify scope."
        else parts.joinToString(" | ")
    }

    /** Repo top-level directories that never start a module-relative path. */
    private val TOP_LEVEL_DIRS = setOf(
        "cli", "docs", "docs-dev", "skills", "examples", "coworker", "bin", ".github", "target", "plugins",
    )
}
