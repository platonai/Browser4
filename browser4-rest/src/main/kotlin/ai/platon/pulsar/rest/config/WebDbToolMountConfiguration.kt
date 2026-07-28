package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agent.tool.WebDbToolExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [WebDbToolExecutor] via [ToolMount] so it is discovered by
 * [ai.platon.browser4.boot.plugin.PluginManager] and made available to
 * both the MCP dispatcher and the LLM agent tool system.
 */
@Configuration
class WebDbToolMountConfiguration(
    private val sessionManager: PulsarSessionManager,
) : ToolMount {

    @Bean
    fun webDbToolExecutor(): WebDbToolExecutor =
        WebDbToolExecutor(sessionManager)

    override fun getToolExecutors(): List<ToolExecutor> = listOf(webDbToolExecutor())
}
