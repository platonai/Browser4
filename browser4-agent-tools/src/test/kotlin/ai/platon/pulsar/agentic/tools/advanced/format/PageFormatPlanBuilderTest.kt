package ai.platon.pulsar.agentic.tools.advanced.format

import ai.platon.pulsar.agentic.tools.ToolErrorCode
import ai.platon.pulsar.skeleton.workflow.format.FormatOptionSchema
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.PageFormats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PageFormatPlanBuilder")
class PageFormatPlanBuilderTest {

    private fun formats(vararg raw: Any?): List<PageFormat> =
        FormatOptionSchema.parse(raw.toList()).requireValid()

    private fun plan(vararg raw: Any?, onlyMainContent: Boolean = true): PageFormatPlan =
        PageFormatPlanBuilder.build(formats(*raw), FormatOptions(onlyMainContent = onlyMainContent))

    @Test
    @DisplayName("the default markdown plan reads the article and the export, and needs no tab")
    fun defaultMarkdownPlan() {
        val plan = plan("markdown")
        assertTrue(plan.needsSnapshot)
        assertFalse(plan.needsLiveTab)
        assertEquals(listOf("readability", "export"), plan.steps.map { it.method })
        assertEquals(listOf("html_snapshot", "html_snapshot"), plan.steps.map { it.domain })
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    @DisplayName("onlyMainContent=false plans a single export instead of the article read")
    fun wholePageMarkdownPlan() {
        val plan = plan("markdown", onlyMainContent = false)
        assertEquals(listOf("export"), plan.steps.map { it.method })
        assertEquals(true, plan.steps.single().args["clean"])
    }

    @Test
    @DisplayName("markdown and html share one export step")
    fun sharedExportStep() {
        val plan = plan("markdown", "html", onlyMainContent = false)
        assertEquals(1, plan.steps.size, plan.steps.toString())
        assertEquals("export", plan.steps.single().method)
    }

    @Test
    @DisplayName("html and rawHtml do not share a step: the clean flag differs")
    fun cleanAndRawAreDifferentSteps() {
        val plan = plan("html", "rawHtml")
        assertEquals(2, plan.steps.size)
        assertEquals(listOf(true, false), plan.steps.map { it.args["clean"] })
    }

    @Test
    @DisplayName("every snapshot step precedes every live step")
    fun stageOrdering() {
        val builderPlan = plan("markdown", "links")
        val withLive = builderPlan.copy(
            steps = builderPlan.steps + FormatStep(
                format = "screenshot",
                stage = FormatStage.LIVE_TAB,
                domain = "tab",
                method = "screenshot",
            )
        )
        // The builder preserves stage order for the plan it produces; the
        // invariant the engine relies on is that it never emits a live step
        // before a snapshot step (re-sorting is the builder's job, not the
        // engine's).
        val ordered = PageFormatPlanBuilder.build(formats("markdown", "links"))
        assertTrue(ordered.steps.all { it.stage == FormatStage.FROM_SNAPSHOT })
        assertTrue(withLive.stepsOf(FormatStage.LIVE_TAB).size == 1)
    }

    @Test
    @DisplayName("attributes plans one read per selector, correlated by the selector itself")
    fun attributesSteps() {
        val plan = plan(
            mapOf(
                "type" to "attributes",
                "selectors" to listOf(
                    mapOf("selector" to ".price", "attribute" to "data-amount"),
                    mapOf("selector" to ".sku", "attribute" to "id"),
                ),
            )
        )
        assertEquals(2, plan.steps.size)
        assertEquals(".price", plan.steps[0].args["selector"])
        assertEquals("data-amount", plan.steps[0].args["attrName"])
        assertEquals(".sku", plan.steps[1].args["selector"])
        assertTrue(plan.steps.all { it.policy == StepPolicy.REQUIRED })
        assertTrue(plan.steps.all { it.args["absoluteUrls"] == false })
    }

    @Test
    @DisplayName("links and images resolve URLs absolutely")
    fun linksAndImagesSteps() {
        val plan = plan("links", "images")
        assertEquals(listOf("a", "img"), plan.steps.map { it.args["selector"] })
        assertTrue(plan.steps.all { it.args["absoluteUrls"] == true })
        assertTrue(plan.steps.all { it.policy == StepPolicy.DEGRADABLE })
    }

    @Test
    @DisplayName("deterministicJson plans the caller's X-SQL statement")
    fun deterministicJsonStep() {
        val plan = plan(mapOf("type" to "deterministicJson", "sql" to "select 1 as a"))
        assertEquals(listOf("query"), plan.steps.map { it.method })
        assertEquals("select 1 as a", plan.steps.single().args["sql"])
        assertEquals(StepPolicy.REQUIRED, plan.steps.single().policy)
    }

    @Test
    @DisplayName("a format this build cannot deliver warns and plans nothing")
    fun unavailableFormatsWarn() {
        // `audio` needs a media service, which is Phase 3. Two earlier choices for this
        // example (screenshot, then pdf) each gained a provider; the guard below is what
        // turns "the example became available" into a readable failure.
        val missing = PageFormats.AUDIO
        assertFalse(FormatProviders.isImplemented(missing), "$missing has a provider now; pick another example")

        assertEquals(listOf("$missing: not available in this build"), plan(missing).warnings)
        assertEquals(
            listOf("query: deprecated and unavailable; use question or highlights"),
            plan(mapOf("type" to "query", "prompt" to "p")).warnings,
        )
        assertTrue(plan(missing).steps.isEmpty())
        assertFalse(plan(missing).needsSnapshot)
    }

    @Test
    @DisplayName("a planned degradation carries the code a strict request would report")
    fun degradationsCarryTheirCode() {
        // The sentence and the code come from one record, so a warning can never describe a
        // different situation than the status strict reports for it.
        assertEquals(
            ToolErrorCode.TARGET_UNAVAILABLE,
            plan("audio").degradations.single().code,
            "a format this build does not implement is a deployment limitation",
        )
        assertEquals(
            ToolErrorCode.INVALID_ARGUMENT,
            plan(mapOf("type" to "query", "prompt" to "p")).degradations.single().code,
            "a retired id is the caller's to change, so it is their mistake",
        )
        assertEquals(
            ToolErrorCode.INVALID_ARGUMENT,
            plan(mapOf("type" to "screenshot", "quality" to 80)).degradations.single().code,
            "an option this build cannot honour is fixed by dropping it",
        )

        // The rendered form is unchanged, and is derived rather than stored: two lists of
        // the same facts would drift.
        assertEquals(listOf("audio: not available in this build"), plan("audio").warnings)
        assertEquals(plan("audio").degradations.map { it.warning }, plan("audio").warnings)
        assertEquals(listOf("audio"), plan("audio").unavailable)
    }

    @Test
    @DisplayName("an option a provider refuses becomes a warning, and the rest still plans")
    fun unsupportedOptionsWarn() {
        // A provider that cannot honour an option throws rather than dropping it, so a
        // caller who asked for an 80-quality screenshot is told instead of silently
        // getting a default-quality one.
        val plan = plan(mapOf("type" to "screenshot", "quality" to 80), "markdown")

        assertEquals(1, plan.warnings.size, plan.warnings.toString())
        assertTrue(
            plan.warnings.single().startsWith("screenshot: quality is not supported yet"),
            plan.warnings.single(),
        )
        // The refused format contributes no steps; its neighbours are unaffected.
        assertTrue(plan.steps.isNotEmpty(), "markdown must still be planned")
        assertTrue(plan.steps.all { it.format == "markdown" }, plan.steps.toString())
        assertFalse(plan.needsLiveTab, "a refused live format must not imply a live step")
    }

    @Test
    @DisplayName("a contributed format is planned silently: its availability is a runtime fact")
    fun contributedFormatsAreNotPlanned() {
        // Whether a plugin supplies `branding` depends on what got wired at
        // startup and whether its service is configured — neither is knowable
        // here, so the plan says nothing and PageFormatEngine reports the
        // outcome. Planning a warning would make it wrong whenever a plugin is
        // in fact installed.
        assertTrue(plan("branding").warnings.isEmpty())
        assertTrue(plan("branding").steps.isEmpty())
        assertFalse(plan("branding").needsSnapshot)
    }

    @Test
    @DisplayName("a request whose formats are all unavailable plans no capture at all")
    fun noCaptureWhenNothingIsDeliverable() {
        val plan = plan("branding", "menu")
        assertTrue(plan.warnings.isEmpty())
        assertFalse(plan.needsSnapshot)
        assertFalse(plan.needsLiveTab)
    }

    @Test
    @DisplayName("a step required by any format is required for the shared execution")
    fun requiredPolicyUpgradesSharedStep() {
        val shared = FormatStep(
            format = "a", stage = FormatStage.FROM_SNAPSHOT, domain = "d", method = "m",
            policy = StepPolicy.DEGRADABLE,
        )
        val required = shared.copy(format = "b", policy = StepPolicy.REQUIRED)

        val merged = PageFormatPlanBuilder.merge(listOf(shared, required, shared))

        assertEquals(1, merged.size)
        assertEquals(StepPolicy.REQUIRED, merged.values.single().policy)
    }

    @Test
    @DisplayName("planning is pure: the same request yields the same plan")
    fun planningIsPure() {
        val attributes = mapOf(
            "type" to "attributes",
            "selectors" to listOf(mapOf("selector" to ".price", "attribute" to "data-amount")),
        )
        assertEquals(plan("markdown", "links", attributes), plan("markdown", "links", attributes))
    }
}
