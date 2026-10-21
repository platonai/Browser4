/**
 * Copyright (c) Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.weather.config

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Configuration holder for the browser4-weather plugin.
 *
 * All properties are read from [ImmutableConfig] with the `weather.` prefix
 * and have sensible defaults.
 */
data class WeatherConfig(
    /** Whether the plugin is enabled */
    val enabled: Boolean = true,
    /** Minimum recommended title length (chars) */
    val minTitleLength: Int = 10,
    /** Maximum recommended title length (chars) */
    val maxTitleLength: Int = 60,
    /** Minimum recommended description length (chars) */
    val minDescriptionLength: Int = 50,
    /** Maximum recommended description length (chars) */
    val maxDescriptionLength: Int = 160,
    /** Minimum recommended word count for "thin content" warning */
    val minWordCount: Int = 300,
) {
    companion object {
        private const val PREFIX = "weather."

        fun fromConfig(conf: ImmutableConfig): WeatherConfig = WeatherConfig(
            enabled = conf.getBoolean("${PREFIX}enabled", true),
            minTitleLength = conf.getInt("${PREFIX}title.min-length", 10),
            maxTitleLength = conf.getInt("${PREFIX}title.max-length", 60),
            minDescriptionLength = conf.getInt("${PREFIX}description.min-length", 50),
            maxDescriptionLength = conf.getInt("${PREFIX}description.max-length", 160),
            minWordCount = conf.getInt("${PREFIX}content.min-word-count", 300),
        )
    }
}
