/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
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
package ai.platon.pulsar.markdown.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.markdown.integration.MarkdownBrowseEventHandler
import ai.platon.pulsar.markdown.service.MarkdownConverter
import ai.platon.pulsar.markdown.service.SiteCrawler
import ai.platon.pulsar.markdown.tools.MarkdownToolExecutor
import ai.platon.pulsar.skeleton.event.BrowseEventHandlers
import ai.platon.pulsar.skeleton.plugin.BrowseEventMount
import okhttp3.OkHttpClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import java.util.concurrent.TimeUnit

/**
 * Spring Boot auto-configuration for the browser4-markdown plugin.
 *
 * Creates the beans for page-to-markdown conversion and site crawling.
 * Implements [BrowseEventMount] and [ToolMount] so that
 * [ai.platon.browser4.boot.plugin.PluginManager] can automatically wire the
 * handlers and tools into the appropriate integration points.
 *
 * Enabled by default. Disable with `markdown.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["markdown.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class MarkdownAutoConfiguration(
    private val applicationContext: ApplicationContext
) : BrowseEventMount, ToolMount {

    private val logger = getLogger(MarkdownAutoConfiguration::class)

    // ---- Mount Points ----

    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        try {
            val handler = applicationContext.getBean("markdownBrowseEventHandler") as MarkdownBrowseEventHandler
            handlers.onDocumentSteady.addLast(handler)
            logger.info("Markdown browse event handler registered on onDocumentSteady via BrowseEventMount")
        } catch (e: Exception) {
            logger.warn("Failed to register markdown browse event handler: {}", e.message)
        }
    }

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("markdownToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            logger.warn("Failed to get markdownToolExecutor for mount: {}", e.message)
            emptyList()
        }
    }

    // ---- Beans ----

    @Bean(name = ["markdownConfig"])
    @ConditionalOnMissingBean(name = ["markdownConfig"])
    open fun markdownConfig(conf: MutableConfig): MarkdownConfig {
        return MarkdownConfig.fromConfig(conf)
    }

    @Bean(name = ["markdownHttpClient"])
    @ConditionalOnMissingBean(name = ["markdownHttpClient"])
    open fun markdownHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Bean(name = ["markdownConverter"])
    @ConditionalOnMissingBean(name = ["markdownConverter"])
    open fun markdownConverter(markdownConfig: MarkdownConfig): MarkdownConverter {
        return MarkdownConverter(markdownConfig)
    }

    @Bean(name = ["siteCrawler"])
    @ConditionalOnMissingBean(name = ["siteCrawler"])
    open fun siteCrawler(
        markdownConfig: MarkdownConfig,
        markdownConverter: MarkdownConverter,
        markdownHttpClient: OkHttpClient,
    ): SiteCrawler {
        return SiteCrawler(markdownConfig, markdownConverter, markdownHttpClient)
    }

    @Bean(name = ["markdownBrowseEventHandler"])
    @ConditionalOnMissingBean(name = ["markdownBrowseEventHandler"])
    open fun markdownBrowseEventHandler(
        markdownConfig: MarkdownConfig,
        siteCrawler: SiteCrawler,
    ): MarkdownBrowseEventHandler {
        return MarkdownBrowseEventHandler(markdownConfig, siteCrawler)
    }

    @Bean(name = ["markdownToolExecutor"])
    @ConditionalOnMissingBean(name = ["markdownToolExecutor"])
    open fun markdownToolExecutor(
        markdownConfig: MarkdownConfig,
        markdownConverter: MarkdownConverter,
        siteCrawler: SiteCrawler,
    ): MarkdownToolExecutor {
        return MarkdownToolExecutor(markdownConfig, markdownConverter, siteCrawler)
    }
}
