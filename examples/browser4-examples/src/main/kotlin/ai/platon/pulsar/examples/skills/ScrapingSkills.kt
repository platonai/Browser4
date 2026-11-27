package ai.platon.pulsar.examples.skills

import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.skills.AbstractSkill
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillMetadata
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.agentic.skills.SkillResult
import ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor
import ai.platon.pulsar.agentic.tools.CustomToolRegistry

/**
 * Example demonstrating the Skills system with Browser4 agent.
 *
 * This example shows how to:
 * 1. Register a skill to a SkillRegistry
 * 2. Expose the `skill` domain to the LLM through CustomToolRegistry
 * 3. Let the agent run it
 *
 * The skill below is a local echo implementation: the programmatic demo skills
 * (`web-scraping`, `form-filling`, `data-validation`) were removed, and the only bundled
 * skill today is `browser4-cli` (loaded from the classpath by `SkillBootstrap`).
 */
class ExampleEchoSkill : AbstractSkill() {
    override val metadata = SkillMetadata(
        id = "example-echo",
        name = "Example Echo",
        version = "1.0.0",
        description = "Echoes the parameters it receives (minimal Skill implementation)",
        author = "Browser4",
        tags = setOf("example")
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        return SkillResult.success(data = params, message = "Echoed ${params.size} parameter(s)")
    }
}

suspend fun main() {
    // Notes:
    // - Skills are *not* part of built-in ToolSpecification; they are exposed to the LLM via CustomToolRegistry.
    // - To make skills appear in the tool list sent to the LLM, we need to register:
    //   (1) SkillToolExecutor into CustomToolRegistry under domain `skill`
    //   (2) SkillToolTarget into AgentToolManager as the target object for domain `skill`

    // Create the agent (initializes BrowserAgentActor and its AgentToolManager auto-wiring)
    val agent = AgenticContexts.getOrCreateAgent() as BasicBrowserAgent

    // Get or create the skill registry
    val registry = SkillRegistry.instance
    val context = agent.agentToolManager.skillContext

    // Register an example skill
    registry.register(ExampleEchoSkill(), context)

    // Wire skill tools so the LLM can see and call them.
    // BrowserAgentActor already auto-wires the per-agent target object for domain `skill`.
    // We only ensure the executor is registered (prompt-visible tool specs).
    val customRegistry = CustomToolRegistry.instance
    if (!customRegistry.contains("skill")) {
        customRegistry.register(SkillToolExecutor(registry))
    }

    // Use agent with a skill-oriented task
    val task = """
        Call the `skill` tool with method `run`, id `example-echo`, and params
        `{"message": "hello from the Browser4 examples"}`. Report what it returns.
        """.trimIndent()

    val history = agent.run(task)
    println("Agent result: ${history.finalResult}")
}
