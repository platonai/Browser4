package ai.platon.pulsar.protocol.browser.emulator.util

import ai.platon.pulsar.skeleton.plugin.PluginMount

/**
 * Mount point for page category sniffers.
 *
 * Implementations return lists of [PageCategorySniffer] that will be added
 * to the [ChainedPageCategorySniffer] in the browser response handler pipeline.
 *
 * ## Example
 *
 * ```kotlin
 * @AutoConfiguration
 * class CaptchaAutoConfiguration : PageSnifferMount {
 *     override fun getPageSniffers(): List<PageCategorySniffer> =
 *         listOf(captchaPageCategorySniffer())
 * }
 * ```
 */
interface PageSnifferMount : PluginMount {
    /**
     * Page category sniffers to add to the chained sniffer pipeline.
     */
    fun getPageSniffers(): List<PageCategorySniffer> = emptyList()
}
