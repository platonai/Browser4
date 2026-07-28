package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.skeleton.plugin.PluginMount

/**
 * Mount point for custom tool executors.
 *
 * Implementations return lists of [ToolExecutor] that will be registered
 * in [CustomToolRegistry]. The [PluginManager] discovers all [ToolMount] beans
 * and registers their executors automatically.
 *
 * ## Example
 *
 * ```kotlin
 * @AutoConfiguration
 * class CaptchaAutoConfiguration : ToolMount {
 *     override fun getToolExecutors(): List<ToolExecutor> =
 *         listOf(captchaToolExecutor())
 * }
 * ```
 */
interface ToolMount : PluginMount {
    /**
     * Tool executors to register in [CustomToolRegistry].
     * Each executor handles a specific domain (e.g., "captcha", "db").
     */
    fun getToolExecutors(): List<ToolExecutor> = emptyList()
}
