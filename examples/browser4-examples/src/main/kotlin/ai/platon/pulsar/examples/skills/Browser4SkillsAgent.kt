package ai.platon.pulsar.examples.skills

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.agentic.skills.examples.WebScrapingSkill
import ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor
import ai.platon.pulsar.agentic.tools.CustomToolRegistry

/**
 * Example demonstrating the Skills system with Browser4 agent.
 *
 * This example shows how to:
 * 1. Register skills to a SkillRegistry
 * 2. Execute skills programmatically
 * 3. Use skills with an agent
 * 4. Discover skills by tags
 */
suspend fun main() {
    // Notes:
    // - Skills are *not* part of built-in ToolSpecification; they are exposed to the LLM via CustomToolRegistry.
    // - To make skills appear in the tool list sent to the LLM, we need to register:
    //   (1) SkillToolExecutor into CustomToolRegistry under domain `skill`
    //   (2) SkillToolTarget into AgentToolManager as the target object for domain `skill`

    // Create the agent (initializes BrowserAgentActor and its AgentToolManager auto-wiring)
    val agent = AgenticContexts.getOrCreateAgent()

    // Get or create the skill registry
    val registry = SkillRegistry.instance
    val context = SkillContext(sessionId = "browser4-skills-demo")

    // Register example skills
    val webScrapingSkill = WebScrapingSkill()
    registry.register(webScrapingSkill, context)

    // Wire skill tools so the LLM can see and call them.
    // BrowserAgentActor already auto-wires the per-agent target object for domain `skill`.
    // We only ensure the executor is registered (prompt-visible tool specs).
    val skillDomain = "skill"
    val customRegistry = CustomToolRegistry.instance
    if (!customRegistry.contains(skillDomain)) {
        customRegistry.register(SkillToolExecutor(registry))
    }

    // Use agent with a skill-oriented task
    val task = """
        Use skill.scraping.extract to scrape https://news.ycombinator.com/news
        """.trimIndent()

    val history = agent.run(task)
    println("Agent result: ${history.finalResult}")
}
