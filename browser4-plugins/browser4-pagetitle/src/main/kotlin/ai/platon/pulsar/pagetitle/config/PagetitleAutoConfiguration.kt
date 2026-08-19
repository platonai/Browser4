package ai.platon.pulsar.pagetitle.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.pagetitle.service.PagetitleService
import ai.platon.pulsar.pagetitle.tools.PagetitleToolExecutor
import ai.platon.pulsar.skeleton.event.BrowseEventHandlers
import ai.platon.pulsar.skeleton.plugin.BrowseEventMount
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Auto-configuration for the browser4-pagetitle plugin.
 *
 * Implements [ToolMount] so that PluginManager automatically registers
 * the plugin's tools into CustomToolRegistry (visible to the LLM agent).
 * Implements [BrowseEventMount] so that PluginManager registers browse event handlers.
 * Disable with `pagetitle.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["pagetitle.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class PagetitleAutoConfiguration(
    private val applicationContext: ApplicationContext,
) : ToolMount, BrowseEventMount {

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("pagetitleToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        handlers.onDocumentSteady.addLast { page, driver ->
            try {
                logger.info("page.url={}", page.url)
                logger.info("page.title={}", driver.title())
            } catch (e: Exception) {
                logger.warn("Failed to log page title info", e)
            }
        }
    }

    @Bean(name = ["pagetitleConfig"])
    @ConditionalOnMissingBean(name = ["pagetitleConfig"])
    open fun pagetitleConfig(config: MutableConfig) = PagetitleConfig.fromConfig(config)

    @Bean(name = ["pagetitleService"])
    @ConditionalOnMissingBean(name = ["pagetitleService"])
    open fun pagetitleService() = PagetitleService()

    @Bean(name = ["pagetitleToolExecutor"])
    @ConditionalOnMissingBean(name = ["pagetitleToolExecutor"])
    open fun pagetitleToolExecutor(service: PagetitleService) = PagetitleToolExecutor(service)

    companion object {
        private val logger = LoggerFactory.getLogger(PagetitleAutoConfiguration::class.java)
    }
}
