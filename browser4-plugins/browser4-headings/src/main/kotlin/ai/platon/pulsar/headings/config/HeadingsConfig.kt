package ai.platon.pulsar.headings.config

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Configuration for the plugin.
 *
 * NOTE: this is a plain data class (NOT an ImmutableConfig subclass) —
 * registering an ImmutableConfig subclass as a Spring bean would make
 * the host's type-based `getBean(ImmutableConfig::class.java)` ambiguous.
 * Read properties from [ImmutableConfig] with the plugin prefix instead.
 */
data class HeadingsConfig(
    /** Whether the plugin is enabled */
    val enabled: Boolean = true,
) {
    companion object {
        private const val PREFIX = "headings."

        fun fromConfig(conf: ImmutableConfig): HeadingsConfig = HeadingsConfig(
            enabled = conf.getBoolean(PREFIX + "enabled", true),
        )
    }
}