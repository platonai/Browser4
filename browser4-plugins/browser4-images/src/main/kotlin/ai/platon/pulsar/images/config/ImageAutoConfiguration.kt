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
package ai.platon.pulsar.images.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.images.integration.ImageBrowseEventHandler
import ai.platon.pulsar.images.service.ImageDetector
import ai.platon.pulsar.images.service.ImageDownloader
import ai.platon.pulsar.images.tools.ImageToolExecutor
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
 * Spring Boot auto-configuration for the browser4-images plugin.
 *
 * Creates the beans for image detection and downloading.
 * Implements [BrowseEventMount] and [ToolMount] so that
 * [ai.platon.browser4.boot.plugin.PluginManager] can automatically wire the
 * handlers and tools into the appropriate integration points.
 *
 * Enabled by default. Disable with `image.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["image.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class ImageAutoConfiguration(
    private val applicationContext: ApplicationContext
) : BrowseEventMount, ToolMount {

    private val logger = getLogger(ImageAutoConfiguration::class)

    // ---- Mount Points ----

    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        try {
            val handler = applicationContext.getBean("imageBrowseEventHandler") as ImageBrowseEventHandler
            handlers.onDocumentSteady.addLast(handler)
            logger.info("Image browse event handler registered on onDocumentSteady via BrowseEventMount")
        } catch (e: Exception) {
            logger.warn("Failed to register image browse event handler: {}", e.message)
        }
    }

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("imageToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            logger.warn("Failed to get imageToolExecutor for mount: {}", e.message)
            emptyList()
        }
    }

    // ---- Beans ----

    @Bean(name = ["imageConfig"])
    @ConditionalOnMissingBean(name = ["imageConfig"])
    open fun imageConfig(conf: MutableConfig): ImageConfig {
        return ImageConfig.fromConfig(conf)
    }

    @Bean(name = ["imageDownloadClient"])
    @ConditionalOnMissingBean(name = ["imageDownloadClient"])
    open fun imageDownloadClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    @Bean(name = ["imageDetector"])
    @ConditionalOnMissingBean(name = ["imageDetector"])
    open fun imageDetector(imageConfig: ImageConfig): ImageDetector {
        return ImageDetector(imageConfig)
    }

    @Bean(name = ["imageDownloader"])
    @ConditionalOnMissingBean(name = ["imageDownloader"])
    open fun imageDownloader(
        imageConfig: ImageConfig,
        imageDownloadClient: OkHttpClient,
    ): ImageDownloader {
        return ImageDownloader(imageConfig, imageDownloadClient)
    }

    @Bean(name = ["imageBrowseEventHandler"])
    @ConditionalOnMissingBean(name = ["imageBrowseEventHandler"])
    open fun imageBrowseEventHandler(
        imageDetector: ImageDetector,
        imageDownloader: ImageDownloader,
        imageConfig: ImageConfig,
    ): ImageBrowseEventHandler {
        return ImageBrowseEventHandler(imageDetector, imageDownloader, imageConfig)
    }

    @Bean(name = ["imageToolExecutor"])
    @ConditionalOnMissingBean(name = ["imageToolExecutor"])
    open fun imageToolExecutor(
        imageDetector: ImageDetector,
        imageDownloader: ImageDownloader,
        imageConfig: ImageConfig,
    ): ImageToolExecutor {
        return ImageToolExecutor(imageDetector, imageDownloader, imageConfig)
    }
}
