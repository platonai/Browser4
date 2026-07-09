package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agent.tool.DomSnapshotToolExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [DomSnapshotToolExecutor] via [ToolMount] so it is discovered by
 * [ai.platon.browser4.boot.plugin.PluginManager] and made available to
 * both the MCP dispatcher and the LLM agent tool system.
 */
@Configuration
class DomSnapshotToolMountConfiguration(
    private val sessionManager: PulsarSessionManager,
    private val scrapeService: ScrapeService?,
) : ToolMount {

    @Bean
    fun domSnapshotToolExecutor(): DomSnapshotToolExecutor =
        DomSnapshotToolExecutor(sessionManager, scrapeService)

    override fun getToolExecutors(): List<ToolExecutor> = listOf(domSnapshotToolExecutor())
}
