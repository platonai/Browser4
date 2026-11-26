package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEvent
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("Unit")
@Tag("Fast")
class SkillSummaryConfigTest {

    private val keys = listOf(
        "browser4.agent.skill.summary.enabled",
        "browser4.agent.skill.summary.registerMode",
        "browser4.agent.skill.summary.minRounds",
        "browser4.agent.skill.summary.minToolCalls",
        "browser4.agent.skill.summary.requireMutation",
        "browser4.agent.skill.summary.browseMinScore",
        "browser4.agent.skill.summary.dedupSimilarity",
        "browser4.agent.skill.summary.cooldownHours",
        "browser4.agent.skill.summary.draftsDir",
    )

    @AfterEach
    fun cleanup() {
        keys.forEach { System.clearProperty(it) }
    }

    @Test
    @DisplayName("TR-1.3 default config matches spec: disabled, draft, conservative thresholds")
    fun defaultConfigMatchesSpec() {
        val cfg = SkillSummaryConfig.load()
        assertFalse(cfg.enabled, "enabled must default to false")
        assertEquals(SkillSummaryConfig.RegisterMode.DRAFT, cfg.registerMode)
        assertEquals(1, cfg.minRounds)
        assertEquals(5, cfg.minToolCalls)
        assertTrue(cfg.requireMutation)
        assertEquals(6, cfg.browseMinScore)
        assertEquals(0.85, cfg.dedupSimilarity)
        assertEquals(6L, cfg.cooldownHours)
        assertEquals("", cfg.draftsDir)
    }

    @Test
    @DisplayName("TR-1.2 TYPE_SKILL_SUMMARIZE constant equals skill.summarize")
    fun eventConstantValue() {
        assertEquals("skill.summarize", AgentTaskEvent.TYPE_SKILL_SUMMARIZE)
    }

    @Test
    @DisplayName("TR-1.1 shouldRun is false when disabled (short-circuit, zero side effects)")
    fun shouldRunFalseWhenDisabled() {
        assertFalse(SkillSummarizer.shouldRun(SkillSummaryConfig(enabled = false)))
    }

    @Test
    @DisplayName("shouldRun is false when registerMode is off even if enabled")
    fun shouldRunFalseWhenOff() {
        assertFalse(
            SkillSummarizer.shouldRun(
                SkillSummaryConfig(enabled = true, registerMode = SkillSummaryConfig.RegisterMode.OFF),
            ),
        )
    }

    @Test
    @DisplayName("shouldRun is true when enabled and mode is draft or auto")
    fun shouldRunTrueWhenEnabled() {
        assertTrue(
            SkillSummarizer.shouldRun(
                SkillSummaryConfig(enabled = true, registerMode = SkillSummaryConfig.RegisterMode.DRAFT),
            ),
        )
        assertTrue(
            SkillSummarizer.shouldRun(
                SkillSummaryConfig(enabled = true, registerMode = SkillSummaryConfig.RegisterMode.AUTO),
            ),
        )
    }

    @Test
    @DisplayName("invalid property values fall back to defaults without throwing")
    fun invalidValuesFallBack() {
        System.setProperty("browser4.agent.skill.summary.minRounds", "not-a-number")
        System.setProperty("browser4.agent.skill.summary.enabled", "not-bool")
        System.setProperty("browser4.agent.skill.summary.minToolCalls", "-7")
        val cfg = SkillSummaryConfig.load()
        assertEquals(1, cfg.minRounds, "invalid int falls back to default")
        assertFalse(cfg.enabled, "invalid bool falls back to default false")
        assertEquals(0, cfg.minToolCalls, "negative coerced to 0")
    }

    @Test
    @DisplayName("maybeSummarize swallows summarize failure and never throws (NFR-1)")
    fun maybeSummarizeSwallowsErrors() {
        val previous = SkillSummarizer.summarizeFn
        SkillSummarizer.summarizeFn = { _, _ -> error("boom") }
        try {
            // 不抛异常即通过：原任务成功状态不受总结失败影响
            SkillSummarizer.maybeSummarize(
                AgentTaskStatus(),
                SkillSummaryConfig(enabled = true),
            )
        } finally {
            SkillSummarizer.summarizeFn = previous
        }
    }

    @Test
    @DisplayName("maybeSummarize does nothing when disabled (no exception path entered)")
    fun maybeSummarizeNoopWhenDisabled() {
        var called = false
        val previous = SkillSummarizer.summarizeFn
        SkillSummarizer.summarizeFn = { _, _ -> called = true }
        try {
            SkillSummarizer.maybeSummarize(AgentTaskStatus(), SkillSummaryConfig(enabled = false))
            assertFalse(called, "summarizeFn must not run when disabled")
        } finally {
            SkillSummarizer.summarizeFn = previous
        }
    }
}
