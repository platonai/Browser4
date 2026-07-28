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
package ai.platon.pulsar.images

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.images.config.ImageAutoConfiguration
import ai.platon.pulsar.skeleton.plugin.Browser4Plugin
import ai.platon.pulsar.skeleton.plugin.PluginManifest

/**
 * Main plugin class for browser4-images.
 *
 * Implements the [Browser4Plugin] lifecycle interface, which provides:
 * - A programmatic [PluginManifest] declaring the plugin's identity, dependencies,
 *   and auto-configuration classes.
 * - [onStartup] / [onShutdown] hooks for initialization and cleanup.
 *
 * The plugin is wired into Browser4 via [ImageAutoConfiguration], which implements
 * [BrowseEventMount] and [ToolMount] to register browse-phase event handlers and
 * LLM agent tools respectively.
 *
 * This class is optional in the PDK model — plugins can function using only
 * [PluginMount] beans via auto-configuration. However, implementing [Browser4Plugin]
 * provides explicit lifecycle management and serves as the canonical entry point
 * for the plugin.
 */
open class ImagesPlugin(
    override val manifest: PluginManifest = PluginManifest(
        name = "browser4-images",
        version = "4.12.0-rc.1",
        description = "Image detection and bulk download for Browser4",
        dependsOn = listOf("browser4-protocol", "browser4-agentic"),
        autoConfigurationClasses = listOf(
            "ai.platon.pulsar.images.config.ImageAutoConfiguration"
        )
    )
) : Browser4Plugin {

    private val logger = getLogger(ImagesPlugin::class)

    override fun onStartup() {
        logger.info("browser4-images plugin started (version={})", manifest.version)
    }

    override fun onShutdown() {
        logger.info("browser4-images plugin shutting down")
    }
}
