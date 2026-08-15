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
        // The baseline steps (validate + commit) still exist.
        assertTrue(plan.steps.any { it.tool == "coding.validate" })
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
        // Default static snapshot does not know a hypothetical weather plugin.
        val defaultPlan = DevTaskPlanner.plan("add a tool in browser4-weather")
        assertTrue(defaultPlan.modules.isEmpty(), "unknown module must not resolve by default: ${defaultPlan.modules}")

        // With the LIVE module list (e.g. from ModuleGraph), the mention resolves.
        val live = ModuleMap.MODULES + "browser4-plugins/browser4-weather"
        val plan = DevTaskPlanner.plan("add a tool in browser4-weather", knownModules = live)
        assertTrue("browser4-plugins/browser4-weather" in plan.modules,
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
}
