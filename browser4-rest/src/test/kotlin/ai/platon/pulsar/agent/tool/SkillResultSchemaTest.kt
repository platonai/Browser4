package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.boot.skill.SkillService
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.mcp.contract.ToolRegistryFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * `skill.list` is the one advertised tool that answers with a **top-level array**,
 * and `skill.info` with a nested detail object. Both now declare a result contract,
 * so both are validated against what the executor really returns.
 *
 * The service is mocked; what matters here is the payload shape, not skill storage.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("skill result contracts")
class SkillResultSchemaTest {

    private val summary = SkillRegistry.SkillSummary(
        id = "browser4-cli",
        name = "Browser4 CLI",
        description = "Drive a browser from the shell.",
        version = "1.0.0",
        tags = setOf("cli", "browser"),
    )

    private val detail = SkillService.SkillDetail(
        id = "browser4-cli",
        name = "Browser4 CLI",
        version = "1.0.0",
        description = "Drive a browser from the shell.",
        author = "platon.ai",
        tags = listOf("cli"),
        dependencies = listOf("browser4-rest"),
        skillMd = "# Browser4 CLI\n",
        scriptsPath = null,
        referencesPath = null,
        assetsPath = null,
        origin = null,
    )

    private fun executor(service: SkillService) = SkillMCPToolExecutor(service)

    private fun spec(method: String) = executor(mock<SkillService>()).getToolSpecs().getValue(method)

    @Test
    @DisplayName("list answers with an array of summaries that satisfies its schema")
    fun listMatchesItsSchema() = runBlocking {
        val service = mock<SkillService> { on { it.listSkills() } doReturn listOf(summary) }
        val spec = spec("list")
        assertNotNull(spec.outputSchema, "skill.list must declare its result contract")

        val payload = pulsarObjectMapper().writeValueAsString(
            executor(service).callFunctionOn("skill", "list", emptyMap(), service)
        )

        assertEquals(
            emptyList<String>(),
            ToolResultValidator.validate(spec, ToolResultValidator.parse(payload)).map { "${it.path} ${it.message}" },
            "skill.list answered with a payload that violates its own schema: $payload",
        )
    }

    @Test
    @DisplayName("info answers with a detail object that satisfies its schema")
    fun infoMatchesItsSchema() = runBlocking {
        val service = mock<SkillService> { on { it.getSkill("browser4-cli") } doReturn detail }
        val spec = spec("info")
        assertNotNull(spec.outputSchema, "skill.info must declare its result contract")

        val payload = pulsarObjectMapper().writeValueAsString(
            executor(service).callFunctionOn("skill", "info", mapOf("id" to "browser4-cli"), service)
        )

        assertEquals(
            emptyList<String>(),
            ToolResultValidator.validate(spec, ToolResultValidator.parse(payload)).map { "${it.path} ${it.message}" },
            "skill.info answered with a payload that violates its own schema: $payload",
        )
    }

    @Test
    @DisplayName("the array schema rejects a summary without its id, and the registry advertises it")
    fun schemasHaveTeeth() {
        val spec = ToolRegistryFixture.specs().single { it.domain == "skill" && it.method == "list" }
        assertNotNull(spec.outputSchema)

        val broken = """[{"name":"Browser4 CLI","description":"d","version":"1.0.0","tags":[]}]"""
        assertEquals(
            true,
            ToolResultValidator.validate(spec, ToolResultValidator.parse(broken)).isNotEmpty(),
            "a summary without 'id' must be reported as a violation",
        )
    }
}
