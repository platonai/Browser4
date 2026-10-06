package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agent.tool.PageScrapeToolExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.rest.api.service.scrape.PageScrapeService
import ai.platon.pulsar.rest.api.service.scrape.SessionFormatToolDispatcher
import ai.platon.pulsar.rest.mcp.controller.CustomToolTargets
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [PageScrapeToolExecutor] (domain `page`) via [ToolMount], so
 * [ai.platon.pulsar.boot.plugin.PluginManager] discovers it and both the MCP
 * dispatcher and the LLM agent tool system can call `page.scrape`.
 *
 * [CustomToolTargets] is built here rather than injected because it is not a bean
 * today — the MCP controller and the embedded MCP server each construct their own.
 * The `beanResolver` is wired the same way in all three places, so a format step
 * resolves its receiver exactly as a client-driven tool call would; that identity
 * is the point of routing steps through the tool layer at all.
 */
@Configuration
class PageScrapeToolMountConfiguration(
    private val applicationContext: ApplicationContext,
    private val sessionManager: PulsarSessionManager,
) : ToolMount {

    private val customToolTargets: CustomToolTargets by lazy {
        CustomToolTargets(
            sessionManager,
            beanResolver = { type -> runCatching { applicationContext.getBean(type) }.getOrNull() },
        )
    }

    @Bean
    fun pageScrapeService(): PageScrapeService =
        PageScrapeService(SessionFormatToolDispatcher.factory(sessionManager, customToolTargets))

    @Bean
    fun pageScrapeToolExecutor(): PageScrapeToolExecutor = PageScrapeToolExecutor(pageScrapeService())

    override fun getToolExecutors(): List<ToolExecutor> = listOf(pageScrapeToolExecutor())
}
