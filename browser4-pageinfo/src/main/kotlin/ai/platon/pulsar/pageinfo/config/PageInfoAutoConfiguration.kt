package ai.platon.pulsar.pageinfo.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.pageinfo.service.PageInfoService
import ai.platon.pulsar.pageinfo.tools.PageInfoToolExecutor

/**
 * Auto-configuration for the browser4-pageinfo plugin.
 *
 * Implements [ToolMount] so that PluginManager automatically registers
 * the plugin's tools into CustomToolRegistry (visible to the LLM agent).
 * Disable with `pageinfo.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["pageinfo.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class PageInfoAutoConfiguration(
    private val applicationContext: ApplicationContext,
) : ToolMount {

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("pageInfoToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Bean(name = ["pageInfoConfig"])
    @ConditionalOnMissingBean(name = ["pageInfoConfig"])
    open fun pageInfoConfig(conf: ImmutableConfig) = PageInfoConfig.fromConfig(conf)

    @Bean(name = ["pageInfoService"])
    @ConditionalOnMissingBean(name = ["pageInfoService"])
    open fun pageInfoService() = PageInfoService()

    @Bean(name = ["pageInfoToolExecutor"])
    @ConditionalOnMissingBean(name = ["pageInfoToolExecutor"])
    open fun pageInfoToolExecutor(service: PageInfoService) = PageInfoToolExecutor(service)
}

