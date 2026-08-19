package ai.platon.pulsar.pagetitle.config

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Configuration for the plugin.
 *
 * NOTE: this is a plain data class (NOT an ImmutableConfig subclass) —
 * registering an ImmutableConfig subclass as a Spring bean would make
 * the host's type-based `getBean(ImmutableConfig::class.java)` ambiguous.
 * Read properties from [ImmutableConfig] with the plugin prefix instead.
 */
data class PagetitleConfig(
    /** Whether the plugin is enabled */
    val enabled: Boolean = true,
    /** Maximum length of a summarized title */
    val maxLength: Int = 200,
) {
    companion object {
        private const val PREFIX = "pagetitle."

        fun fromConfig(conf: ImmutableConfig): PagetitleConfig = PagetitleConfig(
            enabled = conf.getBoolean(PREFIX + "enabled", true),
            maxLength = conf.getInt(PREFIX + "maxLength", 200),
        )
    }
}
