package ai.platon.pulsar.boot.plugin

import ai.platon.pulsar.skeleton.plugin.PluginManifest
import org.springframework.core.env.Environment

/**
 * Decides whether a plugin is activated.
 *
 * Plugins fall into two categories declared by [PluginManifest.defaultEnabled]:
 *
 * - **default-enabled** (`defaultEnabled: true`, the default): loaded unless
 *   explicitly disabled.
 * - **default-disabled / opt-in** (`defaultEnabled: false`): NOT loaded unless
 *   explicitly enabled.
 *
 * Explicit overrides (system property, env var, or Spring `Environment`):
 *
 * | Property / env var | Effect |
 * |---|---|
 * | `browser4.plugins.enable` / `BROWSER4_PLUGINS_ENABLE` | comma-separated plugin names to force-enable (opt-in plugins) |
 * | `browser4.plugins.disable` / `BROWSER4_PLUGINS_DISABLE` | comma-separated plugin names to force-disable |
 * | `browser4.plugins.enable-all` / `BROWSER4_PLUGINS_ENABLE_ALL` | `true` → activate every plugin unless explicitly disabled |
 *
 * Precedence: an explicit `disable` always wins; otherwise `enable` and
 * `enable-all` override the manifest default.
 */
class PluginLoadPolicy(
    private val enableAll: Boolean,
    private val enabledNames: Set<String>,
    private val disabledNames: Set<String>,
) {

    /**
     * Whether the plugin described by [manifest] should be activated.
     */
    fun isEnabled(manifest: PluginManifest): Boolean = disabledReason(manifest) == null

    /**
     * Why the plugin is not activated, or null when it is enabled.
     */
    fun disabledReason(manifest: PluginManifest): String? {
        if (manifest.name in disabledNames) {
            return "explicitly disabled via browser4.plugins.disable"
        }
        if (manifest.name in enabledNames || enableAll) {
            return null
        }
        if (!manifest.defaultEnabled) {
            return "default-disabled (opt-in). Enable with " +
                "-Dbrowser4.plugins.enable=${manifest.name} or browser4.plugins.enable-all=true"
        }
        return null
    }

    companion object {
        /**
         * Builds the policy from system properties with env-var fallback.
         *
         * Used by [PluginClasspathEnhancer], which runs before Spring starts and
         * therefore cannot read `application.properties` yet.
         */
        fun fromSystem(): PluginLoadPolicy {
            val enableAll = isTrue(property("browser4.plugins.enable-all"))
            val enabled = parseList(property("browser4.plugins.enable"))
            val disabled = parseList(property("browser4.plugins.disable"))
            return PluginLoadPolicy(enableAll, enabled, disabled)
        }

        /**
         * Builds the policy from the Spring [Environment], so `application.properties`
         * (or command-line `--browser4.plugins.enable=...`) also works.
         */
        fun fromEnvironment(environment: Environment): PluginLoadPolicy {
            val enableAll = environment.getProperty("browser4.plugins.enable-all", Boolean::class.java, false)
            val enabled = parseList(environment.getProperty("browser4.plugins.enable"))
            val disabled = parseList(environment.getProperty("browser4.plugins.disable"))
            return PluginLoadPolicy(enableAll, enabled, disabled)
        }

        private fun property(key: String): String? {
            return System.getProperty(key) ?: System.getenv(key.replace('.', '_').uppercase())
        }

        private fun parseList(value: String?): Set<String> {
            return value.orEmpty()
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        private fun isTrue(value: String?): Boolean {
            return value != null && (value.equals("true", ignoreCase = true) || value == "1")
        }
    }
}
