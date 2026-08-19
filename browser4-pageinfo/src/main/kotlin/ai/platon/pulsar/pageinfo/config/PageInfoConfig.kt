package ai.platon.pulsar.pageinfo.config

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Configuration holder for the browser4-pageinfo plugin.
 *
 * All properties are read from [ImmutableConfig] with sensible defaults.
 */
data class PageInfoConfig(
    /** Maximum length of extracted meta/text values (longer values are truncated) */
    val maxMetaLength: Int = 2000,

    /** Whether to extract heading structure (h1-h6) */
    val includeHeadings: Boolean = true,

    /** Whether to count links and images on the page */
    val includeLinks: Boolean = false,
) {
    companion object {
        private const val PREFIX = "pageinfo."

        /**
         * Build a [PageInfoConfig] from the application configuration.
         */
        fun fromConfig(conf: ImmutableConfig): PageInfoConfig {
            return PageInfoConfig(
                maxMetaLength = conf.getInt("${PREFIX}max.meta.length", 2000),
                includeHeadings = conf.getBoolean("${PREFIX}include.headings", true),
                includeLinks = conf.getBoolean("${PREFIX}include.links", false),
            )
        }
    }
}
