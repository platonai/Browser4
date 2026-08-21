package ai.platon.pulsar.linkcheck.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.linkcheck.service.LinkcheckService
import ai.platon.pulsar.linkcheck.tools.LinkcheckToolExecutor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Auto-configuration for the browser4-linkcheck plugin.
 *
 * Implements [ToolMount] so that PluginManager automatically registers
 * the plugin's tools into CustomToolRegistry (visible to the LLM agent).
 * Disable with `linkcheck.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["linkcheck.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class LinkcheckAutoConfiguration(
    private val applicationContext: ApplicationContext,
) : ToolMount {

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("linkcheckToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Bean(name = ["linkcheckConfig"])
    @ConditionalOnMissingBean(name = ["linkcheckConfig"])
    open fun linkcheckConfig(config: MutableConfig) = LinkcheckConfig.fromConfig(config)

    @Bean(name = ["linkcheckService"])
    @ConditionalOnMissingBean(name = ["linkcheckService"])
    open fun linkcheckService() = LinkcheckService()

    @Bean(name = ["linkcheckToolExecutor"])
    @ConditionalOnMissingBean(name = ["linkcheckToolExecutor"])
    open fun linkcheckToolExecutor(service: LinkcheckService) = LinkcheckToolExecutor(service)
}
