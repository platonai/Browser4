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
package ai.platon.pulsar.pptx.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.pptx.integration.PptxBrowseEventHandler
import ai.platon.pulsar.pptx.service.PageContentExtractor
import ai.platon.pulsar.pptx.service.PptxGenerator
import ai.platon.pulsar.pptx.service.PptxImageDownloader
import ai.platon.pulsar.pptx.tools.PptxToolExecutor
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
 * Spring Boot auto-configuration for the browser4-pptx plugin.
 *
 * Creates the beans for page content extraction and PPTX generation.
 * Implements [BrowseEventMount] and [ToolMount] so that
 * [ai.platon.pulsar.boot.plugin.PluginManager] can automatically wire the
 * handlers and tools into the appropriate integration points.
 *
 * Enabled by default. Disable with `pptx.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["pptx.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class PptxAutoConfiguration(
    private val applicationContext: ApplicationContext
) : BrowseEventMount, ToolMount {

    private val logger = getLogger(PptxAutoConfiguration::class)

    // ---- Mount Points ----

    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        try {
            val handler = applicationContext.getBean("pptxBrowseEventHandler") as PptxBrowseEventHandler
            handlers.onDocumentSteady.addLast(handler)
            logger.info("PPTX browse event handler registered on onDocumentSteady via BrowseEventMount")
        } catch (e: Exception) {
            logger.warn("Failed to register PPTX browse event handler: {}", e.message)
        }
    }

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("pptxToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            logger.warn("Failed to get pptxToolExecutor for mount: {}", e.message)
            emptyList()
        }
    }

    // ---- Beans ----

    @Bean(name = ["pptxConfig"])
    @ConditionalOnMissingBean(name = ["pptxConfig"])
    open fun pptxConfig(conf: MutableConfig): PptxConfig {
        return PptxConfig.fromConfig(conf)
    }

    @Bean(name = ["pptxDownloadClient"])
    @ConditionalOnMissingBean(name = ["pptxDownloadClient"])
    open fun pptxDownloadClient(pptxConfig: PptxConfig): OkHttpClient {
        // The per-download timeout applies to both connect and read phases so a
        // slow/blocked image host cannot stall the whole PPTX generation past
        // the server's async request budget; the download phase as a whole is
        // additionally bounded by pptx.download.total-timeout.seconds.
        val timeoutSeconds = pptxConfig.downloadTimeoutSeconds.coerceAtLeast(1)
        val builder = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds * 2, TimeUnit.SECONDS)

        // Chrome reaches the image hosts through the OS system proxy (WinINET on
        // Windows); the JVM does not inherit it. PptxProxyResolver looks at every
        // configuration a user can reach (plugin key, env vars, JVM properties, OS
        // settings) so image downloads use the same egress path as the browser.
        // When nothing is configured, OkHttp's own default ProxySelector applies.
        val resolution = PptxProxyResolver.resolve(pptxConfig)
        if (resolution != null) {
            builder.proxySelector(PptxProxySelector(resolution.address, resolution.bypassPatterns))
            logger.info(
                "PPTX image download proxy enabled: {} (from {})",
                resolution.endpoint, resolution.source
            )
        } else {
            logger.info(
                "PPTX image downloads: no proxy configured for the plugin; using the JVM default proxy selector"
            )
        }

        return builder.build()
    }

    @Bean(name = ["pageContentExtractor"])
    @ConditionalOnMissingBean(name = ["pageContentExtractor"])
    open fun pageContentExtractor(pptxConfig: PptxConfig): PageContentExtractor {
        return PageContentExtractor(pptxConfig)
    }

    @Bean(name = ["pptxImageDownloader"])
    @ConditionalOnMissingBean(name = ["pptxImageDownloader"])
    open fun pptxImageDownloader(
        pptxConfig: PptxConfig,
        pptxDownloadClient: OkHttpClient,
    ): PptxImageDownloader {
        return PptxImageDownloader(pptxConfig, pptxDownloadClient)
    }

    @Bean(name = ["pptxGenerator"])
    @ConditionalOnMissingBean(name = ["pptxGenerator"])
    open fun pptxGenerator(
        pptxConfig: PptxConfig,
        pptxImageDownloader: PptxImageDownloader,
    ): PptxGenerator {
        return PptxGenerator(pptxConfig, pptxImageDownloader)
    }

    @Bean(name = ["pptxBrowseEventHandler"])
    @ConditionalOnMissingBean(name = ["pptxBrowseEventHandler"])
    open fun pptxBrowseEventHandler(
        pageContentExtractor: PageContentExtractor,
        pptxGenerator: PptxGenerator,
        pptxConfig: PptxConfig,
    ): PptxBrowseEventHandler {
        return PptxBrowseEventHandler(pageContentExtractor, pptxGenerator, pptxConfig)
    }

    @Bean(name = ["pptxToolExecutor"])
    @ConditionalOnMissingBean(name = ["pptxToolExecutor"])
    open fun pptxToolExecutor(
        pageContentExtractor: PageContentExtractor,
        pptxGenerator: PptxGenerator,
        pptxConfig: PptxConfig,
    ): PptxToolExecutor {
        return PptxToolExecutor(pageContentExtractor, pptxGenerator, pptxConfig)
    }
}
