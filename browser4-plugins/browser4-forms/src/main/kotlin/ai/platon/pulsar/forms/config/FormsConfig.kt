package ai.platon.pulsar.forms.config

import ai.platon.pulsar.common.config.ImmutableConfig

data class FormsConfig(
    val maxFieldDetails: Int = 20,
    val includePerFormDetails: Boolean = true,
    val maxFormDetails: Int = 50,
) {
    companion object {
        private const val PREFIX = "forms."

        fun fromConfig(conf: ImmutableConfig): FormsConfig {
            return FormsConfig(
                maxFieldDetails = conf.getInt("${PREFIX}max.field.details", 20),
                includePerFormDetails = conf.getBoolean("${PREFIX}include.per.form.details", true),
                maxFormDetails = conf.getInt("${PREFIX}max.form.details", 50),
            )
        }
    }
}
