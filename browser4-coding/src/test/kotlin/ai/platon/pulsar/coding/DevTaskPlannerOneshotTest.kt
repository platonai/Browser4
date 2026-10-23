package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression tests for the oneshot dev-task plan (plan P1.1/P1.2/P1.7):
 * - bare filenames bind to their owning module (eval K1)
 * - `impact` targets the deepest module, never the aggregator (eval K2)
 * - an aggregator mention is not counted as an inferred module (eval K3)
 * - the plan carries an implementation contract and an acceptance checklist
 * - a signal-free task stays thin (governance validate only)
 */
class DevTaskPlannerOneshotTest {

    private val driverPath =
        "browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt"

    private fun stepsOf(plan: DevTaskPlanner.DevPlan, tool: String) =
        plan.steps.filter { it.tool == tool }

    // ==================== K1: bare filename ====================

    @Test
    @DisplayName("a bare driver filename resolves to its owning module through the file index")
    fun bareFilenameResolvesToModule() {
        val plan = DevTaskPlanner.plan(
            "fix cursor positioning in Browser4WebDriver.kt and add a test class FillCursorTest in browser4-rest",
            ModuleMap.MODULES,
            mapOf("Browser4WebDriver.kt" to driverPath),
        )

        assertTrue(plan.files.contains(driverPath), "files: ${plan.files}")
        assertTrue(plan.modules.contains("browser4-core/browser4-browser"), "modules: ${plan.modules}")

        // The read/impact/trap steps must use the resolved path, not the bare name.
        val read = stepsOf(plan, "coding.read")
        assertTrue(read.any { it.command.contains("path=\"$driverPath\"") }, "read: $read")
        val trap = stepsOf(plan, "coding.trapCheck")
        assertTrue(trap.any { it.command.contains("path=\"$driverPath\"") }, "trap: $trap")

        // The named test still binds to the module the task names.
        val shell = stepsOf(plan, "coding.shell")
        assertTrue(
            shell.any {
                it.command.contains("-pl browser4-rest") &&
                    it.command.contains("-Dtest=FillCursorTest") &&
                    it.command.contains("failIfNoSpecifiedTests=false")
            },
            "shell: $shell",
        )
    }

    @Test
    @DisplayName("the acceptance checklist and testTarget follow the named class, not the edited driver module")
    fun testBindingFollowsTheNamedClass() {
        val plan = DevTaskPlanner.plan(
            "fix cursor positioning in Browser4WebDriver.kt and add a test class FillCursorTest in browser4-rest",
            ModuleMap.MODULES,
            mapOf("Browser4WebDriver.kt" to driverPath),
        )

        // Compiling happens where the code change lands …
        assertEquals("browser4-core/browser4-browser", plan.steps.first { it.tool == "coding.mvnBuild" }.args["module"])
        // … but the test runs where the named class lives, and `testTarget` is the
        // single value every consumer (acceptance, verify) must agree on.
        assertEquals("browser4-rest", plan.testTarget, "testTarget: ${plan.testTarget}")
        assertTrue(
            plan.acceptance.any { it == "test FillCursorTest in browser4-rest" },
            "acceptance: ${plan.acceptance}",
        )
        assertTrue(
            plan.acceptance.any { it == "compile browser4-core/browser4-browser" },
            "acceptance: ${plan.acceptance}",
        )
    }

    @Test
    @DisplayName("without a named class the test step (whole module suite) binds the affected module; no signal binds nothing")
    fun testTargetWithoutTestClass() {
        val moduleOnly = DevTaskPlanner.plan("fix a bug in browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/ModuleMap.kt",
            ModuleMap.MODULES)
        assertEquals("browser4-coding", moduleOnly.testTarget, "testTarget: ${moduleOnly.testTarget}")

        val noSignal = DevTaskPlanner.plan("please improve the overall performance", ModuleMap.MODULES)
        assertNull(noSignal.testTarget, "testTarget: ${noSignal.testTarget}")
    }

    @Test
    @DisplayName("without a file index the bare name stays unresolved (caller's I/O is required)")
    fun bareFilenameWithoutIndexStaysBare() {
        val plan = DevTaskPlanner.plan(
            "fix cursor positioning in Browser4WebDriver.kt",
            ModuleMap.MODULES,
        )
        assertTrue(plan.files.contains("Browser4WebDriver.kt"), "files: ${plan.files}")
    }

    // ==================== K2/K3: aggregators ====================

    @Test
    @DisplayName("impact targets the deepest module, not the aggregator")
    fun impactTargetsDeepestModule() {
        val plan = DevTaskPlanner.plan(
            "fix a test in browser4-plugins/browser4-seo and run SeoServiceTest",
            ModuleMap.MODULES,
        )
        assertEquals(listOf("browser4-plugins/browser4-seo"), plan.modules, "aggregator leaked into signals")
        val impact = stepsOf(plan, "coding.impact")
        assertTrue(
            impact.any { it.command.contains("path=\"browser4-plugins/browser4-seo\"") },
            "impact: $impact",
        )
        val build = stepsOf(plan, "coding.mvnBuild")
        assertTrue(
            build.any { it.command.contains("module=\"browser4-plugins/browser4-seo\"") },
            "build: $build",
        )
    }

    @Test
    @DisplayName("a new plugin module mention does not count the aggregator as a signal")
    fun newPluginDoesNotLeakAggregator() {
        val plan = DevTaskPlanner.plan(
            "创建新插件 browser4-plugins/browser4-evalprobe，实现 src/main/resources/evalprobe/count.js 与 EvalprobeServiceTest",
            ModuleMap.MODULES,
        )
        assertFalse(plan.modules.contains("browser4-plugins"), "modules: ${plan.modules}")
        assertEquals(listOf("browser4-plugins/browser4-evalprobe"), plan.newPluginModules)
        assertEquals("coding.scaffoldToDir", plan.steps.first().tool)
        val build = stepsOf(plan, "coding.mvnBuild")
        assertTrue(
            build.any { it.command.contains("module=\"browser4-plugins/browser4-evalprobe\"") },
            "build: $build",
        )
    }

    // ==================== P1.1/P1.2: contract + acceptance ====================

    @Test
    @DisplayName("a signal-carrying plan has an implement contract and a verify gate")
    fun planCarriesImplementAndVerify() {
        val plan = DevTaskPlanner.plan(
            "fix a test in browser4-plugins/browser4-seo and run SeoServiceTest",
            ModuleMap.MODULES,
        )
        val implement = stepsOf(plan, DevTaskPlanner.IMPLEMENT_TOOL)
        assertEquals(1, implement.size, "expected exactly one implement step, got $implement")
        assertTrue(implement[0].command.contains("acceptance="), "implement: ${implement[0].command}")

        val verify = stepsOf(plan, "coding.verify")
        assertEquals(1, verify.size, "expected exactly one verify step, got $verify")
        assertTrue(
            verify[0].command.contains("module=\"browser4-plugins/browser4-seo\""),
            "verify: ${verify[0].command}",
        )

        // Ordering: read/impact → implement → compile → verify (last).
        val tools = plan.steps.map { it.tool }
        assertTrue(
            tools.indexOf(DevTaskPlanner.IMPLEMENT_TOOL) < tools.indexOf("coding.mvnBuild"),
            "tools: $tools",
        )
        assertEquals("coding.verify", tools.last(), "tools: $tools")
    }

    @Test
    @DisplayName("the acceptance checklist mirrors the plan's checks")
    fun acceptanceMirrorsPlan() {
        val plan = DevTaskPlanner.plan(
            "fix a test in browser4-plugins/browser4-seo and run SeoServiceTest",
            ModuleMap.MODULES,
        )
        assertTrue(plan.acceptance.any { it.contains("compile browser4-plugins/browser4-seo") }, "${plan.acceptance}")
        assertTrue(plan.acceptance.any { it.contains("SeoServiceTest") }, "${plan.acceptance}")
        assertTrue(plan.acceptance.any { it == "validate repo-consistency" }, "${plan.acceptance}")
    }

    @Test
    @DisplayName("implement is a contract step, never a git-commit step")
    fun noCommitStep() {
        val plan = DevTaskPlanner.plan(
            "fix a test in browser4-plugins/browser4-seo and run SeoServiceTest",
            ModuleMap.MODULES,
        )
        assertTrue(
            plan.steps.none { it.command.contains(Regex("\\bgit\\s+(add|commit|push)\\b")) },
            "steps: ${plan.steps.map { it.command }}",
        )
    }

    // ==================== thin plans ====================

    @Test
    @DisplayName("a signal-free task stays thin: governance validate only, no implement/verify")
    fun noSignalStaysThin() {
        val plan = DevTaskPlanner.plan("please improve the overall performance", ModuleMap.MODULES)
        assertEquals(listOf("coding.validate"), plan.steps.map { it.tool }, "steps: ${plan.steps.map { it.tool }}")
        assertTrue(plan.summary.contains("No module/file signals"), "summary: ${plan.summary}")
        assertEquals(listOf("validate repo-consistency"), plan.acceptance, "acceptance: ${plan.acceptance}")
    }

    @Test
    @DisplayName("planning is deterministic for the same input")
    fun deterministic() {
        val task = "fix a test in browser4-plugins/browser4-seo and run SeoServiceTest"
        val first = DevTaskPlanner.plan(task, ModuleMap.MODULES)
        val second = DevTaskPlanner.plan(task, ModuleMap.MODULES)
        assertEquals(first.steps.map { it.command }, second.steps.map { it.command })
        assertEquals(first.acceptance, second.acceptance)
        assertEquals(first.modules, second.modules)
    }
}
