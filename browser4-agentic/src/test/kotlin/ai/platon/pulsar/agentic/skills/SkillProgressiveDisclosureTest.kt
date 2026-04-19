package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor
import ai.platon.pulsar.agentic.skills.tools.SkillToolTarget
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SkillProgressiveDisclosureTest {

    @Test
    fun skillListReturnsSummariesOnly() = runBlocking {
        // Ensure skills are loaded via bootstrap (resources/skills)
        SkillBootstrap().initialize()

        val executor = SkillToolExecutor(SkillRegistry.instance)
        val target = SkillToolTarget(SkillContext(sessionId = "ut"))

        val result = executor.callFunctionOn(
            domain = "skill",
            functionName = "list",
            args = mapOf("maxDescriptionChars" to 120),
            receiver = target
        )

        @Suppress("UNCHECKED_CAST")
        val summaries = result as List<SkillRegistry.SkillSummary>

        assertTrue(summaries.isNotEmpty())
        assertTrue(summaries.any { it.id == "web-scraping" })

        // Heuristic check: description is truncated, not full SKILL.md body.
        summaries.forEach {
            assertTrue(it.description.length <= 120)
        }
    }

    @Test
    fun skillActivateReturnsFullSkillMd() = runBlocking {
        SkillBootstrap().initialize()

        val executor = SkillToolExecutor(SkillRegistry.instance)
        val target = SkillToolTarget(SkillContext(sessionId = "ut"))

        val result = executor.callFunctionOn(
            domain = "skill",
            functionName = "activate",
            args = mapOf("id" to "web-scraping"),
            receiver = target
        )

        val activation = result as SkillRegistry.SkillActivation
        assertTrue(activation.id == "web-scraping")
        assertNotNull(activation.skillMd)
        assertTrue(activation.skillMd.contains("# Web Scraping Skill"))
        assertTrue(activation.skillMd.contains("---"))
    }
}
