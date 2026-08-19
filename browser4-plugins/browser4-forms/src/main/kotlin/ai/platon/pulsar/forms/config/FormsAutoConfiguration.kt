package ai.platon.pulsar.forms.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.forms.service.FormsService
import ai.platon.pulsar.forms.tools.FormsToolExecutor

/**
 * Auto-configuration for the browser4-forms plugin.
 *
 * Implements [ToolMount] so that PluginManager automatically registers
 * the plugin's tools into CustomToolRegistry (visible to the LLM agent).
 * Disable with `forms.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["forms.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class FormsAutoConfiguration(
    private val applicationContext: ApplicationContext,
) : ToolMount {

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("formsToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Bean(name = ["formsConfig"])
    @ConditionalOnMissingBean(name = ["formsConfig"])
    open fun formsConfig(conf: ImmutableConfig) = FormsConfig.fromConfig(conf)

    @Bean(name = ["formsService"])
    @ConditionalOnMissingBean(name = ["formsService"])
    open fun formsService() = FormsService()

    @Bean(name = ["formsToolExecutor"])
    @ConditionalOnMissingBean(name = ["formsToolExecutor"])
    open fun formsToolExecutor(service: FormsService) = FormsToolExecutor(service)
}