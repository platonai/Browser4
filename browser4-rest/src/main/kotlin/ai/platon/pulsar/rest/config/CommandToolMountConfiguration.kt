package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agent.tool.CommandToolExecutor
import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [CommandToolExecutor] via [ToolMount] so it is discovered by
 * [ai.platon.browser4.boot.plugin.PluginManager] and made available to
 * both the MCP dispatcher and the LLM agent tool system.
 *
 * This replaces the lazy registration previously done in
 * [ai.platon.pulsar.rest.mcp.controller.MCPToolController.getCommandAgentToolManager].
 */
@Configuration
class CommandToolMountConfiguration(
    private val userCommandExecutor: UserCommandExecutor,
) : ToolMount {

    @Bean
    fun commandToolExecutor(): CommandToolExecutor = CommandToolExecutor(userCommandExecutor)

    override fun getToolExecutors(): List<ToolExecutor> = listOf(commandToolExecutor())
}
