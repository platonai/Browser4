package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [DevTaskPlanner] — the high-level dev-task entry that turns a
 * natural-language task into an executable plan following the AGENTS.md flow.
 * Pure parsing, always runs.
 */
class DevTaskPlannerTest {

    @Test
    @DisplayName("driver-file task infers the browser module and flags CDP pitfalls")
    fun driverTaskPlan() {
        val plan = DevTaskPlanner.plan(
            "fix the mouseWheel race in PulsarWebDriver.kt under browser4-core/browser4-browser/src/main/kotlin")
        assertTrue("browser4-core/browser4-browser" in plan.modules, "modules: ${plan.modules}")
        assertTrue(plan.driverFiles.isNotEmpty(), "driver file must be flagged")
        assertTrue(plan.steps.any { it.tool == "coding.trapCheck" }, "trapCheck step expected")
        assertTrue(plan.steps.any { it.tool == "coding.mvnBuild" }, "mvnBuild step expected")
        assertTrue(plan.steps.any { it.tool == "coding.validate" }, "repo-consistency step expected")
        assertTrue(plan.steps.any { it.tool == "coding.impact" }, "impact step expected")
    }

    @Test
    @DisplayName("test-class task infers browser4-agentic and includes test steps")
    fun testTaskPlan() {
        val plan = DevTaskPlanner.plan("add a unit test for CodingToolExecutorTest in browser4-agentic/src/test")
        assertTrue("browser4-agentic" in plan.modules, "modules: ${plan.modules}")
        assertTrue(plan.steps.any { it.command.contains("mvn test -pl browser4-agentic") },
            "test command expected: ${plan.steps}")
    }

    @Test
    @DisplayName("browser4-plugin.json is extracted whole, not truncated to .js")
    fun jsonFileNotTruncatedToJs() {
        val plan = DevTaskPlanner.plan("update the manifest browser4-plugin.json of browser4-plugins/browser4-seo")
        assertTrue(plan.files.any { it.endsWith("browser4-plugin.json") },
            "full .json name expected, got: ${plan.files}")
        assertFalse(plan.files.any { it.endsWith("browser4-plugin.js") },
            ".js truncation must not happen: ${plan.files}")
    }

    @Test
    @DisplayName("unknown browser4-plugins/<name> mention is a new plugin module with a scaffold step")
    fun newPluginModuleDetection() {
        val plan = DevTaskPlanner.plan("创建新插件 browser4-plugins/browser4-testprobe，实现 getWordCount 工具")
        assertTrue("browser4-plugins/browser4-testprobe" in plan.newPluginModules,
            "new plugin modules: ${plan.newPluginModules}")
        val scaffold = plan.steps.firstOrNull { it.tool == "coding.scaffoldToDir" }
        assertNotNull(scaffold, "scaffold step expected: ${plan.steps}")
        assertEquals("browser4-plugins/browser4-testprobe", scaffold!!.args["dir"])
        // The build step targets the NEW module.
        assertTrue(plan.steps.any { it.tool == "coding.mvnBuild" && it.command.contains("browser4-testprobe") },
            "mvnBuild should target the new module: ${plan.steps}")
    }

    @Test
    @DisplayName("existing plugin mention is not treated as new")
    fun existingPluginNotNew() {
        val plan = DevTaskPlanner.plan("fix a test in browser4-plugins/browser4-seo")
        assertTrue(plan.newPluginModules.isEmpty(),
            "existing module must not be flagged as new: ${plan.newPluginModules}")
    }

    @Test
    @DisplayName("test class binds to its owning module via camelCase→kebab matching")
    fun testClassOwningModuleBinding() {
        val plan = DevTaskPlanner.plan("补充 HeadingsConfigTest 用例")
        assertTrue(plan.testClasses.contains("HeadingsConfigTest"), "testClasses: ${plan.testClasses}")
        val testStep = plan.steps.firstOrNull { it.tool == "coding.shell" && it.command.contains("-Dtest=") }
        assertNotNull(testStep, "test step expected: ${plan.steps}")
        assertTrue(testStep!!.command.contains("-pl browser4-plugins/browser4-headings"),
            "test must run in the owning module, got: ${testStep.command}")
    }

    @Test
    @DisplayName("CLI task includes cargo steps and the CLI crate")
    fun cliTaskPlan() {
        val plan = DevTaskPlanner.plan("add a new browser4-cli command with a batch test in cli/browser4-cli/src/commands.rs")
        assertTrue(ModuleMap.CLI_CRATE in plan.modules, "modules: ${plan.modules}")
        assertTrue(plan.steps.any { it.command.contains("cargo test --bin browser4-cli") },
            "cargo step expected: ${plan.steps}")
    }

    @Test
    @DisplayName("no-signal task warns about scope")
    fun noSignalPlan() {
        val plan = DevTaskPlanner.plan("please improve the overall performance")
        assertTrue(plan.modules.isEmpty(), "no modules should be inferred: ${plan.modules}")
        assertTrue(plan.summary.contains("No module/file signals"), plan.summary)
        // The baseline validate step still exists.
        assertTrue(plan.steps.any { it.tool == "coding.validate" })
    }

    @Test
    @DisplayName("test command tolerates upstream modules with no matching tests (-am + -Dtest)")
    fun testCommandToleratesNoSpecifiedTests() {
        val plan = DevTaskPlanner.plan(
            "run SeoServiceTest and SeoConfigTest in browser4-plugins/browser4-seo/src/test")
        val testStep = plan.steps.first { it.command.contains("-Dtest=") }
        assertTrue(testStep.command.contains("-Dsurefire.failIfNoSpecifiedTests=false"),
            "test command must tolerate upstream modules with no matching tests: ${testStep.command}")
    }

    @Test
    @DisplayName("plans never include a git-commit step")
    fun noCommitStep() {
        val plan = DevTaskPlanner.plan(
            "Create a new plugin browser4-plugins/browser4-testprobe and add WordcountService.kt")
        assertFalse(plan.steps.any { it.command.contains("git commit") },
            "agents must not auto-commit: ${plan.steps.map { it.command }}")
    }

    @Test
    @DisplayName("bare filename in a new-plugin task yields a locate step on the module dir")
    fun bareFilenameLocateStep() {
        val plan = DevTaskPlanner.plan(
            "Create a new plugin browser4-plugins/browser4-testprobe and implement HelloService.kt")
        val locate = plan.steps.firstOrNull { it.tool == "coding.listDir" }
        assertNotNull(locate, "locate step expected: ${plan.steps}")
        assertEquals("browser4-plugins/browser4-testprobe", locate!!.args["path"])
        assertFalse(plan.steps.any { it.tool == "coding.read" && it.command == "coding.read(path=\"HelloService.kt\")" },
            "bare filename must not be read from the workspace root: ${plan.steps}")
    }

    @Test
    @DisplayName("steps are ordered and numbered from 1")
    fun stepsOrdered() {
        val plan = DevTaskPlanner.plan("touch browser4-rest/src/main/kotlin/SomeController.kt")
        val orders = plan.steps.map { it.order }
        assertEquals((1..plan.steps.size).toList(), orders, "orders must be 1..N")
    }

    @Test
    @DisplayName("explicit module mention normalizes to the full module path")
    fun moduleMentionNormalized() {
        val plan = DevTaskPlanner.plan("the change is in browser4-browser and must not break browser4-rest")
        assertTrue("browser4-core/browser4-browser" in plan.modules, "modules: ${plan.modules}")
        assertTrue("browser4-rest" in plan.modules, "modules: ${plan.modules}")
    }

    @Test
    @DisplayName("knownModules drives normalization against the live graph")
    fun knownModulesParam() {
        // Default static snapshot does not know a hypothetical widgets plugin.
        val defaultPlan = DevTaskPlanner.plan("add a tool in browser4-widgets")
        assertTrue(defaultPlan.modules.isEmpty(), "unknown module must not resolve by default: ${defaultPlan.modules}")

        // With the LIVE module list (e.g. from ModuleGraph), the mention resolves.
        val live = ModuleMap.MODULES + "browser4-plugins/browser4-widgets"
        val plan = DevTaskPlanner.plan("add a tool in browser4-widgets", knownModules = live)
        assertTrue("browser4-plugins/browser4-widgets" in plan.modules,
            "mention must normalize against knownModules: ${plan.modules}")
    }

    @Test
    @DisplayName("pdk mention resolves when knownModules includes it")
    fun pdkMentionResolves() {
        val plan = DevTaskPlanner.plan("the plugin framework is in browser4-pdk")
        assertTrue("browser4-pdk" in plan.modules, "pdk must resolve (it is in the synced snapshot): ${plan.modules}")
    }

    @Test
    @DisplayName("named test classes scope the test step with -Dtest")
    fun testClassScoping() {
        val plan = DevTaskPlanner.plan(
            "fix the controller in browser4-rest/src/main/kotlin/XController.kt and add a regression " +
                "test XControllerTest")
        assertTrue("XControllerTest" in plan.testClasses, "test classes: ${plan.testClasses}")
        val testStep = plan.steps.first { it.tool == "coding.shell" && it.command.contains("-Dtest=") }
        assertTrue(testStep.command.contains("-Dtest=XControllerTest"),
            "test step must scope with -Dtest: ${testStep.command}")
        assertTrue(plan.summary.contains("tests: XControllerTest"), plan.summary)
    }

    @Test
    @DisplayName("prose words ending in Test do not become test classes")
    fun proseNotTestClass() {
        val plan = DevTaskPlanner.plan("write a test and run the module's test suite for browser4-rest")
        assertTrue(plan.testClasses.isEmpty(), "prose must not yield test classes: ${plan.testClasses}")
        // No -Dtest= in the test step.
        assertFalse(plan.steps.any { it.command.contains("-Dtest=") }, "no -Dtest expected: ${plan.steps}")
    }

    @Test
    @DisplayName("DEPENDENTS-key mentions do not hijack build/test binding from a new plugin module")
    fun dependentsKeysWithNewPluginTargetNewModule() {
        // P1.1 regression: the four DEPENDENTS keys have the same path depth as
        // browser4-plugins/<name> and previously won the tie-break (maxByOrNull
        // returns the first maximum), binding mvnBuild/test to
        // browser4-core/browser4-protocol instead of the freshly scaffolded module.
        val plan = DevTaskPlanner.plan(
            "在 browser4-agentic、browser4-core/browser4-protocol、browser4-core/browser4-skeleton、" +
                "browser4-pdk 的 DEPENDENTS 补 browser4-plugins/browser4-testprobe，并补 LinkcheckConfigTest")
        assertTrue("browser4-plugins/browser4-testprobe" in plan.newPluginModules,
            "new plugin modules: ${plan.newPluginModules}")
        val mvnBuild = plan.steps.first { it.tool == "coding.mvnBuild" }
        assertTrue(mvnBuild.command.contains("browser4-plugins/browser4-testprobe"),
            "mvnBuild must target the new module: ${mvnBuild.command}")
        assertFalse(mvnBuild.command.contains("browser4-core/browser4-protocol"),
            "mvnBuild must not target a DEPENDENTS key: ${mvnBuild.command}")
        val testStep = plan.steps.first { it.tool == "coding.shell" && it.command.contains("-Dtest=") }
        assertTrue(testStep.command.contains("-pl browser4-plugins/browser4-testprobe"),
            "test step must run in the new module: ${testStep.command}")
        assertFalse(testStep.command.contains("browser4-core/browser4-protocol"),
            "test step must not run in a DEPENDENTS key: ${testStep.command}")
    }

    @Test
    @DisplayName("slashed path inside a new plugin module gets the module prefix")
    fun slashedPathInNewPluginGetsModulePrefix() {
        // P1.2 regression: `src/main/resources/...` used to be read from the
        // workspace root (repo root) instead of the new plugin module.
        val plan = DevTaskPlanner.plan(
            "创建新插件 browser4-plugins/browser4-testprobe，实现 src/main/resources/testprobe/count.js")
        val read = plan.steps.first { it.tool == "coding.read" }
        assertEquals(
            "browser4-plugins/browser4-testprobe/src/main/resources/testprobe/count.js",
            read.args["path"], "read path must be module-prefixed: ${read.command}")
        val impact = plan.steps.first { it.tool == "coding.impact" }
        assertEquals(
            "browser4-plugins/browser4-testprobe/src/main/resources/testprobe/count.js",
            impact.args["path"], "impact path must be module-prefixed: ${impact.command}")
    }

    @Test
    @DisplayName("slashed paths rooted at a known module or repo top-level dir are not re-prefixed")
    fun rootedPathsAreNotReprefixed() {
        val knownModulePath = DevTaskPlanner.plan(
            "fix browser4-plugins/browser4-seo/src/main/resources/seo/x.js and create browser4-plugins/browser4-testprobe")
        assertEquals(
            "browser4-plugins/browser4-seo/src/main/resources/seo/x.js",
            knownModulePath.steps.first { it.tool == "coding.read" }.args["path"])

        val topLevelPath = DevTaskPlanner.plan(
            "create browser4-plugins/browser4-testprobe and update cli/browser4-cli/src/commands.rs")
        assertEquals(
            "cli/browser4-cli/src/commands.rs",
            topLevelPath.steps.first { it.tool == "coding.read" }.args["path"])
    }
}
