package ${package};

import ai.platon.pulsar.skeleton.plugin.Browser4Plugin
import ai.platon.pulsar.skeleton.plugin.PluginManifest

/**
 * Main plugin class implementing the [Browser4Plugin] lifecycle interface.
 *
 * This is optional: you can create a plugin using only [PluginMount] beans
 * via auto-configuration. Implement [Browser4Plugin] if you need explicit
 * lifecycle hooks (onStartup/onShutdown) or a programmatic manifest.
 */
open class MyPlugin(
    override val manifest: PluginManifest = PluginManifest(
        name = "${pluginName}",
        version = "${version}",
        description = "${pluginDescription}",
        dependsOn = listOf("browser4-skeleton", "browser4-browser"),
        autoConfigurationClasses = listOf(
            "${package}.config.PluginAutoConfiguration"
        )
    )
) : Browser4Plugin {

    override fun onStartup() {
        // Called after Spring context is fully refreshed and all mount points are wired.
        // Use this for post-startup initialization.
        println("MyPlugin started!")
    }

    override fun onShutdown() {
        // Called when the application context is closing.
        // Use this to release resources, unregister handlers, etc.
        println("MyPlugin shutting down...")
    }
}
