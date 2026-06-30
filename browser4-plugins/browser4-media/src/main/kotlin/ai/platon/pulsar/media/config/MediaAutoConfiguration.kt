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
package ai.platon.pulsar.media.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.media.integration.MediaBrowseEventHandler
import ai.platon.pulsar.media.service.FFmpegProcessManager
import ai.platon.pulsar.media.service.MediaDownloader
import ai.platon.pulsar.media.service.VideoDetector
import ai.platon.pulsar.media.tools.MediaToolExecutor
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
 * Spring Boot auto-configuration for the browser4-media plugin.
 *
 * Creates the beans for video detection, downloading, and FFmpeg processing.
 * Implements [BrowseEventMount] and [ToolMount] so that
 * [ai.platon.browser4.boot.plugin.PluginManager] can automatically wire the
 * handlers and tools into the appropriate integration points.
 *
 * Enabled by default. Disable with `media.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["media.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class MediaAutoConfiguration(
    private val applicationContext: ApplicationContext
) : BrowseEventMount, ToolMount {

    private val logger = getLogger(MediaAutoConfiguration::class)

    // ---- Mount Points ----

    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        try {
            val handler = applicationContext.getBean("mediaBrowseEventHandler") as MediaBrowseEventHandler
            handlers.onDocumentSteady.addLast(handler)
            logger.info("Media browse event handler registered on onDocumentSteady via BrowseEventMount")
        } catch (e: Exception) {
            logger.warn("Failed to register media browse event handler: {}", e.message)
        }
    }

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("mediaToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            logger.warn("Failed to get mediaToolExecutor for mount: {}", e.message)
            emptyList()
        }
    }

    // ---- Beans ----

    @Bean(name = ["mediaConfig"])
    @ConditionalOnMissingBean(name = ["mediaConfig"])
    open fun mediaConfig(conf: MutableConfig): MediaConfig {
        return MediaConfig.fromConfig(conf)
    }

    @Bean(name = ["mediaDownloadClient"])
    @ConditionalOnMissingBean(name = ["mediaDownloadClient"])
    open fun mediaDownloadClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    @Bean(name = ["videoDetector"])
    @ConditionalOnMissingBean(name = ["videoDetector"])
    open fun videoDetector(): VideoDetector {
        return VideoDetector()
    }

    @Bean(name = ["mediaDownloader"])
    @ConditionalOnMissingBean(name = ["mediaDownloader"])
    open fun mediaDownloader(
        mediaConfig: MediaConfig,
        mediaDownloadClient: OkHttpClient,
    ): MediaDownloader {
        return MediaDownloader(mediaConfig, mediaDownloadClient)
    }

    @Bean(name = ["ffmpegProcessor"])
    @ConditionalOnMissingBean(name = ["ffmpegProcessor"])
    open fun ffmpegProcessor(mediaConfig: MediaConfig): FFmpegProcessManager {
        return FFmpegProcessManager(mediaConfig)
    }

    @Bean(name = ["mediaBrowseEventHandler"])
    @ConditionalOnMissingBean(name = ["mediaBrowseEventHandler"])
    open fun mediaBrowseEventHandler(
        videoDetector: VideoDetector,
        mediaConfig: MediaConfig,
    ): MediaBrowseEventHandler {
        return MediaBrowseEventHandler(videoDetector, mediaConfig)
    }

    @Bean(name = ["mediaToolExecutor"])
    @ConditionalOnMissingBean(name = ["mediaToolExecutor"])
    open fun mediaToolExecutor(
        videoDetector: VideoDetector,
        mediaDownloader: MediaDownloader,
        ffmpegProcessor: FFmpegProcessManager,
        mediaConfig: MediaConfig,
    ): MediaToolExecutor {
        return MediaToolExecutor(videoDetector, mediaDownloader, ffmpegProcessor, mediaConfig)
    }
}
