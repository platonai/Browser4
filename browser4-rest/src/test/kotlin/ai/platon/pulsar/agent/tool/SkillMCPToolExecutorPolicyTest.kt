package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.boot.skill.SkillService
import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.coding.AgentAccessPolicy
import ai.platon.pulsar.coding.ToolAccessDeniedException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Skill management rewrites the **global** skill registry, which every running task shares.
 * The MCP route carries no per-task preset, so it must honour the server-wide policy instead
 * of trusting the caller (P5, 2026-10-30).
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("skill management policy")
class SkillMCPToolExecutorPolicyTest {

    private val summary = SkillRegistry.SkillSummary(
        id = "browser4-cli",
        name = "Browser4 CLI",
        description = "Drive a browser from the shell.",
        version = "1.0.0",
        tags = setOf("cli"),
    )

    private fun executor(preset: AccessPreset, service: SkillService) = SkillMCPToolExecutor(
        service,
        accessPolicy = { AgentAccessPolicy(preset = preset) },
    )

    @Test
    @DisplayName("a narrowed server policy refuses skill deployment, uninstall and reload")
    fun narrowedPolicyRefusesDeployment() {
        val service = mock<SkillService>()
        val executor = executor(AccessPreset.READ, service)

        listOf(
            "install" to emptyMap<String, Any?>(),
            "uninstall" to mapOf<String, Any?>("id" to "browser4-cli"),
            "reload" to mapOf<String, Any?>("id" to "browser4-cli"),
        ).forEach { (method, args) ->
            val ex = assertThrows<ToolAccessDeniedException> {
                runBlocking { executor.callFunctionOn("skill", method, args, service) }
            }
            val message = ex.message.orEmpty()
            assertTrue(message.contains("TOOL_DENIED"), message)
            assertTrue(message.contains("access preset 'read'"), message)
        }
    }

    @Test
    @DisplayName("skill discovery stays available under a narrowed policy")
    fun narrowedPolicyKeepsDiscovery() = runBlocking {
        val service = mock<SkillService> { on { it.listSkills() } doReturn listOf(summary) }
        val listed = executor(AccessPreset.READ, service).callFunctionOn("skill", "list", emptyMap(), service)

        assertEquals(listOf(summary), listed)
    }

    @Test
    @DisplayName("full access lets the management method reach its own argument validation")
    fun fullAccessReachesTheService() {
        val service = mock<SkillService>()
        // No `path` argument: the call must fail on the missing argument, not on the policy.
        val ex = assertThrows<IllegalArgumentException> {
            runBlocking { executor(AccessPreset.FULL, service).callFunctionOn("skill", "install", emptyMap(), service) }
        }
        assertTrue(ex.message.orEmpty().contains("path"), ex.message.orEmpty())
    }
}
