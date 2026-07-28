package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agent.tool.SwarmToolExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.rest.api.service.SwarmService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [SwarmToolExecutor] via [ToolMount] so it is discovered by
 * [ai.platon.browser4.boot.plugin.PluginManager] and made available to
 * both the MCP dispatcher and the LLM agent tool system.
 */
@Configuration
class SwarmToolMountConfiguration(
    private val swarmService: SwarmService,
) : ToolMount {

    @Bean
    fun swarmToolExecutor(): SwarmToolExecutor = SwarmToolExecutor(swarmService)

    override fun getToolExecutors(): List<ToolExecutor> = listOf(swarmToolExecutor())
}
