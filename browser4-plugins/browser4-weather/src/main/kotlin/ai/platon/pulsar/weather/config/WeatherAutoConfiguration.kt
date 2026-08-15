package ai.platon.pulsar.weather.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.weather.service.WeatherService
import ai.platon.pulsar.weather.tools.WeatherToolExecutor

/**
 * Auto-configuration for the browser4-weather plugin.
 *
 * Implements [ToolMount] so that PluginManager automatically registers
 * the plugin's tools into CustomToolRegistry (visible to the LLM agent).
 * Disable with `weather.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["weather.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class WeatherAutoConfiguration(
    private val applicationContext: ApplicationContext,
) : ToolMount {

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("weatherToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Bean(name = ["weatherConfig"])
    @ConditionalOnMissingBean(name = ["weatherConfig"])
    open fun weatherConfig(config: MutableConfig) = WeatherConfig(config)

    @Bean(name = ["weatherService"])
    @ConditionalOnMissingBean(name = ["weatherService"])
    open fun weatherService() = WeatherService()

    @Bean(name = ["weatherToolExecutor"])
    @ConditionalOnMissingBean(name = ["weatherToolExecutor"])
    open fun weatherToolExecutor(service: WeatherService) = WeatherToolExecutor(service)
}