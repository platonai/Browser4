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
package ai.platon.pulsar.media

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.plugin.Browser4Plugin
import ai.platon.pulsar.skeleton.plugin.Browser4Version
import ai.platon.pulsar.skeleton.plugin.PluginManifest

/**
 * Main plugin class for browser4-media.
 *
 * Implements the [Browser4Plugin] lifecycle interface, which provides:
 * - A programmatic [PluginManifest] declaring the plugin's identity, dependencies,
 *   and auto-configuration classes.
 * - [onStartup] / [onShutdown] hooks for initialization and cleanup.
 *
 * The plugin is wired into Browser4 via [ai.platon.pulsar.media.config.MediaAutoConfiguration],
 * which implements [ai.platon.pulsar.skeleton.plugin.BrowseEventMount] and
 * [ai.platon.pulsar.skeleton.plugin.ToolMount] to register the browse-phase video detection
 * handler and the `media.*` LLM agent tools respectively.
 */
open class MediaPlugin(
    override val manifest: PluginManifest = PluginManifest(
        name = "browser4-media",
        version = pluginVersion(),
        sdkVersion = Browser4Version.version,
        description = "Video detection, download and FFmpeg processing for Browser4",
        dependsOn = listOf("browser4-protocol", "browser4-agentic"),
        autoConfigurationClasses = listOf(
            "ai.platon.pulsar.media.config.MediaAutoConfiguration"
        )
    )
) : Browser4Plugin {

    private val logger = getLogger(MediaPlugin::class)

    override fun onStartup() {
        logger.info("browser4-media plugin started (version={})", manifest.version)
    }

    override fun onShutdown() {
        logger.info("browser4-media plugin shutting down")
    }

    companion object {
        /**
         * The plugin's own version, taken from the packaged JAR: the build filters
         * `${project.version}` into the JAR manifest's `Implementation-Version` (and into
         * `META-INF/browser4-plugin.json`, which is what `plugin list` displays), so a
         * hardcoded literal can never drift from the artifact again.
         *
         * Falls back to the host version ([Browser4Version.version]) when there is no JAR
         * manifest to read — an in-repo plugin is versioned in lockstep with the repository,
         * and running from a directory classpath (IDE, unit tests) has no manifest at all.
         */
        fun pluginVersion(): String {
            val implementationVersion = MediaPlugin::class.java.`package`?.implementationVersion
            return implementationVersion?.takeIf { it.isNotBlank() } ?: Browser4Version.version
        }
    }
}
