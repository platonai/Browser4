package ai.platon.pulsar.agentic.prompts

import ai.platon.pulsar.agentic.inference.PromptBuilder
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.agentic.skills.examples.WebScrapingSkill
import ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MainSystemPromptCustomSkillInjectionTest {

    private val skillContext = SkillContext(sessionId = "skill-prompt-test")

    private lateinit var customTools: CustomToolRegistry
    private lateinit var skills: SkillRegistry

    @BeforeEach
    fun setUp() = runBlocking {
        customTools = CustomToolRegistry.instance
        skills = SkillRegistry.instance

        customTools.clear()
        skills.clear(skillContext)
    }

    @AfterEach
    fun tearDown() = runBlocking {
        customTools.clear()
        skills.clear(skillContext)
    }

    @Test
    fun customSkillToolSpecsShouldAppearInSystemPromptToolList() = runBlocking {
        // Register a concrete skill so SkillToolExecutor can expose its tool-call specs.
        skills.register(WebScrapingSkill(), skillContext)

        // Register SkillToolExecutor to CustomToolRegistry under domain `skill`.
        // This is the condition for making skill tool-call specs prompt-visible.
        customTools.register(SkillToolExecutor(skills))

        // Main system prompt should contain tool list with custom tools appended.
        val prompt = buildMainSystemPromptV1()

        val compacted = PromptBuilder.compactPromptForHuman(prompt)
        assertTrue(prompt.contains("### Tool List"), compacted)
        assertTrue(prompt.contains("// CustomTool"), compacted)

        // A concrete method name of WebScrapingSkill should appear in the rendered prompt.
        // (We assert both domain prefix and method token to avoid accidental matches.)
        assertTrue(prompt.contains("skill."), compacted)
        assertTrue(prompt.contains("scraping"), compacted)

        // And registry should hold prompt-visible specs for the skill domain.
        val skillSpec = customTools.getToolCallSpecifications("skill")
        assertTrue(skillSpec.isNotEmpty())
    }

    @Test
    fun skillSummariesShouldAppearInSystemPromptWhenSkillsRegistered() = runBlocking {
        // Register a skill
        skills.register(WebScrapingSkill(), skillContext)

        // Build the system prompt
        val prompt = buildMainSystemPromptV1()

        // The prompt should contain the skill summaries section
        assertTrue(prompt.contains("### Available Skills"),
            "System prompt should contain skill summaries section when skills are registered")

        // The prompt should list the registered skill
        assertTrue(prompt.contains("Web Scraping"),
            "System prompt should list the Web Scraping skill")
        assertTrue(prompt.contains("web-scraping"),
            "System prompt should contain the skill ID")
    }

    @Test
    fun skillToolTypeDefinitionsShouldAppearInSystemPrompt() = runBlocking {
        // Build the system prompt (no skills needed for type definitions)
        val prompt = buildMainSystemPromptV1()

        // The prompt should contain skill tool type definitions
        assertTrue(prompt.contains("### Skill Tool Types"),
            "System prompt should contain skill tool type definitions header")

        // Check for SkillSummary type definition
        assertTrue(prompt.contains("data class SkillSummary"),
            "System prompt should contain SkillSummary type definition")

        // Check for SkillActivation type definition
        assertTrue(prompt.contains("data class SkillActivation"),
            "System prompt should contain SkillActivation type definition")

        // Check for SkillResult type definition
        assertTrue(prompt.contains("data class SkillResult"),
            "System prompt should contain SkillResult type definition")
    }
}
