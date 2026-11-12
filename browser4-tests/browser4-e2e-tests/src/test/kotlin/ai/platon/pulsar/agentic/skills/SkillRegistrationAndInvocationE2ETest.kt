package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.context.StaticAgenticContext
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.core.api.ImmutableConfig
import ai.platon.pulsar.external.ChatModelFactory
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * End-to-end test for skill registration and invocation.
 *
 * This test validates the complete skill integration by:
 * 1. Verifying skill.debug.scraping is preloaded at system startup via SkillBootstrap
 * 2. Wiring the skill to CustomToolRegistry via SkillToolExecutor
 * 3. Creating an agent that can use the skill
 * 4. Running a task that invokes the skill
 * 5. Reading the step action descriptions back from the run history
 * 6. Validating that the actionDescription.toolCall is skill-related
 *
 * The run history is the source of truth: the removed `ContextToAction` inference
 * events no longer exist, so the tool calls are read from `history.states` instead.
 */
@Tag("E2ETest")
@Tag("skills")
@Tag("RequiresAI")
class SkillRegistrationAndInvocationE2ETest {

    companion object {
        private val conf = ImmutableConfig()

        @BeforeAll
        @JvmStatic
        fun checkLLM() {
            Assumptions.assumeTrue(ChatModelFactory.isModelConfigured(conf))
        }
    }

    @BeforeEach
    fun setup() {
        Assumptions.assumeTrue(ChatModelFactory.isModelConfigured(conf))
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    @DisplayName("test skill registration and invocation through agent")
    suspend fun testSkillRegistrationAndInvocationThroughAgent() {
        val session = StaticAgenticContext(autoRefresh = true).createSession()
        val driver = session.createBoundDriver()
        val agent = session.companionAgent
        assertNotNull(agent, "Agent should be created")

        val url = "https://agentskills.io/specification"
        driver.open(url)

        val task = """
            Use skill.debug.scraping to scrape $url
        """.trimIndent()

        val history = agent.run(task)

        // Step 5: Verify the agent ran and completed
        assertNotNull(history, "History should not be null")
        assertTrue(history.size > 0, "History should contain at least one execution")

        // Step 6: Read the step action descriptions back from the run history
        val actionDescriptions = history.states.mapNotNull { it.actionDescription }
        assertTrue(actionDescriptions.isNotEmpty(), "At least one ActionDescription should be present")

        // Step 8: Verify that at least one actionDescription contains a skill-related toolCall
        val skillRelatedToolCalls = actionDescriptions.mapNotNull { actionDescription ->
            val toolCall = actionDescription.toolCall
            if (toolCall != null && isSkillRelatedToolCall(toolCall)) toolCall else null
        }

        assertTrue(
            skillRelatedToolCalls.isNotEmpty(),
            "At least one ActionDescription should have a skill-related toolCall. " +
                    "Found ${actionDescriptions.size} ActionDescriptions, " +
                    "but none had skill-related toolCalls."
        )

        // Additional verification: ensure the toolCall has the expected domain
        val hasCorrectDomain = skillRelatedToolCalls.any { it.domain.startsWith("skill") }
        assertTrue(
            hasCorrectDomain,
            "At least one skill toolCall should have domain starting with 'skill'. " +
                    "Found domains: ${skillRelatedToolCalls.map { it.domain }}"
        )
    }

    /**
     * Determines if a toolCall is related to skills.
     * A toolCall is considered skill-related if:
     * - The domain starts with "skill"
     * - The domain contains "skill.debug.scraping"
     */
    private fun isSkillRelatedToolCall(toolCall: ToolCall): Boolean {
        return toolCall.domain.startsWith("skill") ||
                toolCall.domain.contains("skill.debug.scraping")
    }
}
