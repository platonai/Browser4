package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agent.tool.SearchToolExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.rest.api.service.search.SearchService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [SearchToolExecutor] via [ToolMount] so it is discovered by
 * [ai.platon.pulsar.boot.plugin.PluginManager] and made available to
 * both the MCP dispatcher and the LLM agent tool system.
 */
@Configuration
class SearchToolMountConfiguration(
    private val searchService: SearchService,
) : ToolMount {

    @Bean
    fun searchToolExecutor(): SearchToolExecutor = SearchToolExecutor(searchService)

    override fun getToolExecutors(): List<ToolExecutor> = listOf(searchToolExecutor())
}
