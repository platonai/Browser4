package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agent.tool.CrawlToolExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.rest.api.service.CrawlService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [CrawlToolExecutor] via [ToolMount] so it is discovered by
 * [ai.platon.browser4.boot.plugin.PluginManager] and made available to
 * both the MCP dispatcher and the LLM agent tool system.
 */
@Configuration
class CrawlToolMountConfiguration(
    private val crawlService: CrawlService,
) : ToolMount {

    @Bean
    fun crawlToolExecutor(): CrawlToolExecutor = CrawlToolExecutor(crawlService)

    override fun getToolExecutors(): List<ToolExecutor> = listOf(crawlToolExecutor())
}
