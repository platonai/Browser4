package ai.platon.pulsar.agentic.skills.tools

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.skills.AbstractSkill
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillMetadata
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.agentic.skills.SkillResult
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Verifies that skills can be executed through AgentToolManager custom tool domain `skill`.
 *
 * This is a lightweight unit test: no real browser interaction. The skill under test is a local
 * echo implementation — the programmatic demo skills were removed together with the bundled
 * SKILL.md content.
 */
class SkillToolExecutorTest {

    /** Minimal in-test skill: echoes its params back so the dispatch path can be asserted. */
    private class EchoSkill : AbstractSkill() {
        override val metadata = SkillMetadata(
            id = "echo-skill",
            name = "Echo Skill",
            version = "1.0.0",
            description = "Echoes the parameters it receives (unit-test fixture)",
            author = "Browser4",
            tags = setOf("test")
        )

        override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
            return SkillResult.success(data = params, message = "echoed ${params.size} parameter(s)")
        }
    }

    private lateinit var registry: SkillRegistry
    private lateinit var ctx: SkillContext

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        registry = SkillRegistry.instance
        ctx = SkillContext(sessionId = "test-session")
        registry.clear(ctx)
        CustomToolRegistry.instance.unregister("skill")
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        registry.clear(ctx)
        CustomToolRegistry.instance.unregister("skill")
    }

    @Test
        @DisplayName("skill run executes registered skill")
    fun skillRunExecutesRegisteredSkill() = runBlocking {
        registry.register(EchoSkill(), ctx)

        val executor = SkillToolExecutor(registry)
        CustomToolRegistry.instance.register(executor)

        // We can bypass BrowserAgentActor and call the executor directly.
        val target = SkillToolTarget(ctx, registry)

        val tc = ToolCall(
            domain = "skill",
            method = "run",
            arguments = mutableMapOf(
                "id" to "echo-skill",
                "params" to mapOf(
                    "url" to "https://example.com",
                    "selector" to ".content",
                )
            ),
            // pseudoExpression = "skill.run(id=\"echo-skill\", params=...)"
        )

        val eval = executor.callFunctionOn(tc, target)
        val result = eval.value

        assertNotNull(result)
        assertTrue(result is SkillResult)
        val sr = result as SkillResult
        assertTrue(sr.success)
        val data = sr.data as? Map<*, *>
        assertEquals("https://example.com", data?.get("url"))
        assertEquals(".content", data?.get("selector"))
    }

    @Test
        @DisplayName("skill run fails with missing id")
    fun skillRunFailsWithMissingId() = runBlocking {
        val executor = SkillToolExecutor(registry)
        CustomToolRegistry.instance.register(executor)

        val tc = ToolCall(domain = "skill", method = "run", arguments = mutableMapOf())
        val eval = executor.callFunctionOn(tc, SkillToolTarget(ctx, registry))

        assertNotNull(eval.exception)
        assertTrue(eval.expression?.contains("skill.run") == true)
    }
}
