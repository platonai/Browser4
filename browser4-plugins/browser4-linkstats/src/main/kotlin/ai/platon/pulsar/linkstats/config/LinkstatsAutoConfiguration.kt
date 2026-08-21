package ai.platon.pulsar.linkstats.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.linkstats.integration.LinkstatsBrowseEventHandler
import ai.platon.pulsar.linkstats.service.LinkstatsService
import ai.platon.pulsar.linkstats.tools.LinkstatsToolExecutor
import ai.platon.pulsar.skeleton.event.BrowseEventHandlers
import ai.platon.pulsar.skeleton.plugin.BrowseEventMount
import org.slf4j.LoggerFactory

/**
 * Auto-configuration for the browser4-linkstats plugin.
 *
 * Implements [BrowseEventMount] and [ToolMount] so that PluginManager
 * automatically wires the browse event handler and the plugin's tools into
 * the appropriate integration points.
 *
 * Enabled by default. Disable with `linkstats.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["linkstats.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class LinkstatsAutoConfiguration(
    private val applicationContext: ApplicationContext,
) : BrowseEventMount, ToolMount {

    private val logger = LoggerFactory.getLogger(LinkstatsAutoConfiguration::class.java)

    // ---- Mount Points ----

    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        try {
            val handler =
                applicationContext.getBean("linkstatsBrowseEventHandler") as LinkstatsBrowseEventHandler
            handlers.onDocumentSteady.addLast(handler)
            logger.info("Linkstats browse event handler registered on onDocumentSteady via BrowseEventMount")
        } catch (e: Exception) {
            logger.warn("Failed to register linkstats browse event handler: {}", e.message)
        }
    }

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("linkstatsToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            logger.warn("Failed to get linkstatsToolExecutor for mount: {}", e.message)
            emptyList()
        }
    }

    // ---- Beans ----

    @Bean(name = ["linkstatsConfig"])
    @ConditionalOnMissingBean(name = ["linkstatsConfig"])
    open fun linkstatsConfig(conf: MutableConfig): LinkstatsConfig = LinkstatsConfig.fromConfig(conf)

    @Bean(name = ["linkstatsService"])
    @ConditionalOnMissingBean(name = ["linkstatsService"])
    open fun linkstatsService(): LinkstatsService = LinkstatsService()

    @Bean(name = ["linkstatsBrowseEventHandler"])
    @ConditionalOnMissingBean(name = ["linkstatsBrowseEventHandler"])
    open fun linkstatsBrowseEventHandler(
        service: LinkstatsService,
        config: LinkstatsConfig,
    ): LinkstatsBrowseEventHandler = LinkstatsBrowseEventHandler(service, config)

    @Bean(name = ["linkstatsToolExecutor"])
    @ConditionalOnMissingBean(name = ["linkstatsToolExecutor"])
    open fun linkstatsToolExecutor(service: LinkstatsService): LinkstatsToolExecutor =
        LinkstatsToolExecutor(service)
}