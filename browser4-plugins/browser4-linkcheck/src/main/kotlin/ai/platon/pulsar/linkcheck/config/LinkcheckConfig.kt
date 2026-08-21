package ai.platon.pulsar.linkcheck.config

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Configuration for the linkcheck plugin.
 *
 * NOTE: this is a plain data class (NOT an ImmutableConfig subclass) —
 * registering an ImmutableConfig subclass as a Spring bean would make
 * the host's type-based `getBean(ImmutableConfig::class.java)` ambiguous.
 * Read properties from [ImmutableConfig] with the plugin prefix instead.
 *
 * String properties: use `conf.get("prefix.key", "default")` — there is
 * NO `getString` accessor on ImmutableConfig.
 */
data class LinkcheckConfig(
    /** Whether the plugin is enabled */
    val enabled: Boolean = true,

    /** Log level for the plugin */
    val logLevel: String = "info",
) {
    companion object {
        const val PREFIX = "linkcheck."

        fun fromConfig(conf: ImmutableConfig): LinkcheckConfig = LinkcheckConfig(
            enabled = conf.getBoolean("linkcheck.enabled", true),
            logLevel = conf.get("linkcheck.logLevel", "info"),
        )
    }
}
