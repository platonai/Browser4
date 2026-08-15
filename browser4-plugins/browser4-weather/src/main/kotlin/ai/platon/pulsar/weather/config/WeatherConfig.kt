package ai.platon.pulsar.weather.config

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig

/**
 * Configuration for the plugin.
 *
 * Define config properties with the [MutableConfig] prefix mechanism:
 * ```kotlin
 * val myProp: String get() = conf.getWithDefault("${prefix}.my-prop", "default")
 * ```
 */
class WeatherConfig(config: MutableConfig) : ImmutableConfig(config)