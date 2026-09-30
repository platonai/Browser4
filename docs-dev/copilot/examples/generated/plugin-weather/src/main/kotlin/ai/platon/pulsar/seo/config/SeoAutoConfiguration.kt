/**
 * Copyright (c) Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.weather.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.weather.service.WeatherService
import ai.platon.pulsar.weather.tools.WeatherToolExecutor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Spring Boot auto-configuration for the browser4-weather plugin.
 *
 * Implements [ToolMount] so that [ai.platon.pulsar.boot.plugin.PluginManager]
 * automatically wires the SEO tools into the LLM agent tool registry.
 *
 * This is a minimal plugin — it only exposes tools, no browse event handlers.
 * Enabled by default. Disable with `weather.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["weather.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class WeatherAutoConfiguration(
    private val applicationContext: ApplicationContext,
) : ToolMount {

    private val logger = getLogger(WeatherAutoConfiguration::class)

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("seoToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            logger.warn("Failed to get seoToolExecutor for mount: {}", e.message)
            emptyList()
        }
    }

    @Bean(name = ["seoConfig"])
    @ConditionalOnMissingBean(name = ["seoConfig"])
    open fun seoConfig(conf: MutableConfig): WeatherConfig =
        WeatherConfig.fromConfig(conf)

    @Bean(name = ["seoService"])
    @ConditionalOnMissingBean(name = ["seoService"])
    open fun seoService(seoConfig: WeatherConfig): WeatherService =
        WeatherService(seoConfig)

    @Bean(name = ["seoToolExecutor"])
    @ConditionalOnMissingBean(name = ["seoToolExecutor"])
    open fun seoToolExecutor(seoService: WeatherService): WeatherToolExecutor =
        WeatherToolExecutor(seoService)
}
