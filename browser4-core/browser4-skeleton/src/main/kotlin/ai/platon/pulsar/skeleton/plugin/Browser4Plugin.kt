package ai.platon.pulsar.skeleton.plugin

/**
 * Base interface for Browser4 plugins.
 *
 * A plugin is a self-contained module that provides additional functionality to Browser4.
 * It can be loaded at runtime from the `plugins/` directory.
 *
 * Implementations should also implement one or more [PluginMount] sub-interfaces
 * to declare their integration points (e.g., [BrowseEventMount], [ToolMount]).
 *
 * ## Lifecycle
 *
 * 1. Plugin JAR is discovered from the `plugins/` directory
 * 2. Plugin's auto-configuration classes are registered with Spring
 * 3. Plugin beans are created by the Spring context
 * 4. [PluginMount] beans are discovered and wired into their mount points
 * 5. [onStartup] is called (post-context-refresh)
 * 6. [onShutdown] is called (on context close)
 */
interface Browser4Plugin {
    /**
     * The plugin manifest, typically loaded from `META-INF/browser4-plugin.json`.
     */
    val manifest: PluginManifest

    /**
     * Called after the Spring context is fully refreshed and all mount points are wired.
     * Use this for any post-startup initialization that depends on other services being ready.
     */
    fun onStartup() {}

    /**
     * Called when the application context is closing.
     * Use this to release resources, unregister handlers, etc.
     */
    fun onShutdown() {}
}
