package ai.platon.pulsar.my.config

import ai.platon.pulsar.common.config.ImmutableConfig

data class WordcountConfig(
    val enabled: Boolean = true,
) {
    companion object {
        const val PREFIX = "wordcount."

        fun fromConfig(conf: ImmutableConfig): WordcountConfig {
            return WordcountConfig(
                enabled = conf.getBoolean("${PREFIX}enabled", true),
            )
        }
    }
}
