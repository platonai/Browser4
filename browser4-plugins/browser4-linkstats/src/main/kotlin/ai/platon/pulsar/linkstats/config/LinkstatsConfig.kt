package ai.platon.pulsar.linkstats.config

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Configuration for the plugin.
 *
 * NOTE: this is a plain data class (NOT an ImmutableConfig subclass) —
 * registering an ImmutableConfig subclass as a Spring bean would make
 * the host's type-based `getBean(ImmutableConfig::class.java)` ambiguous.
 * Read properties from [ImmutableConfig] with the plugin prefix instead.
 *
 * Type hierarchy: MutableConfig IS-A ImmutableConfig. The auto-config
 * bean method below injects MutableConfig and passes it to
 * `fromConfig(conf: ImmutableConfig)` — that is a legal widening, NOT a
 * mismatch. Do NOT change the bean parameter to ImmutableConfig and do
 * NOT change fromConfig to accept MutableConfig: keep this exact shape.
 *
 * String properties: use `conf.get("prefix.key", "default")` — there is
 * NO `getString` accessor on ImmutableConfig.
 */
data class LinkstatsConfig(
    /** Whether the plugin is enabled */
    val enabled: Boolean = true,

    /** Minimum link count a page must reach before the summary is logged */
    val minLinks: Int = 50,
) {
    companion object {
        private const val PREFIX = "linkstats."

        fun fromConfig(conf: ImmutableConfig): LinkstatsConfig = LinkstatsConfig(
            enabled = conf.getBoolean(PREFIX + "enabled", true),
            minLinks = conf.getInt(PREFIX + "minLinks", 50),
        )
    }
}