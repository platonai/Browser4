package ai.platon.pulsar.rest.config

import ai.platon.browser4.boot.skill.SkillService
import ai.platon.pulsar.agent.tool.SkillMCPToolExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [SkillMCPToolExecutor] via [ToolMount] so it is discovered by
 * [ai.platon.browser4.boot.plugin.PluginManager] and made available to
 * both the MCP dispatcher and the LLM agent tool system.
 *
 * This is for skill *management* tools (install, uninstall, reload, list, info).
 * The skill *execution* domain ("skill") is handled separately by
 * [ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor].
 */
@Configuration
class SkillMCPToolMountConfiguration(
    private val skillService: SkillService,
) : ToolMount {

    @Bean
    fun skillMCPToolExecutor(): SkillMCPToolExecutor = SkillMCPToolExecutor(skillService)

    override fun getToolExecutors(): List<ToolExecutor> = listOf(skillMCPToolExecutor())
}
