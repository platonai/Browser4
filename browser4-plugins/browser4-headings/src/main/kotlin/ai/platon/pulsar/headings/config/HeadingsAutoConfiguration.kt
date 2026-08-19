package ai.platon.pulsar.headings.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.headings.service.HeadingsService
import ai.platon.pulsar.headings.tools.HeadingsToolExecutor

/**
 * Auto-configuration for the browser4-headings plugin.
 *
 * Implements [ToolMount] so that PluginManager automatically registers
 * the plugin's tools into CustomToolRegistry (visible to the LLM agent).
 * Disable with `headings.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["headings.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class HeadingsAutoConfiguration(
    private val applicationContext: ApplicationContext,
) : ToolMount {

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("headingsToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Bean(name = ["headingsConfig"])
    @ConditionalOnMissingBean(name = ["headingsConfig"])
    open fun headingsConfig(config: MutableConfig) = HeadingsConfig.fromConfig(config)

    @Bean(name = ["headingsService"])
    @ConditionalOnMissingBean(name = ["headingsService"])
    open fun headingsService() = HeadingsService()

    @Bean(name = ["headingsToolExecutor"])
    @ConditionalOnMissingBean(name = ["headingsToolExecutor"])
    open fun headingsToolExecutor(service: HeadingsService) = HeadingsToolExecutor(service)
}