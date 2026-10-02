/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
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
package ai.platon.pulsar.search

import ai.platon.pulsar.common.config.ImmutableConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration holder for web search.
 *
 * All properties are read from [ImmutableConfig] with sensible defaults,
 * mirroring the pattern used by [ai.platon.pulsar.captcha.CaptchaConfig].
 *
 * Resolved from `application.properties`:
 * - `search.provider` (`tavily`|`bocha`, default `tavily`)
 * - `search.taskTimeoutMillis` (default 60000)
 * - `search.tavily.api.key` / `search.tavily.base.url`
 * - `search.bocha.api.key` / `search.bocha.base.url`
 */
data class SearchConfig(
    /** Primary search provider. */
    val primaryProvider: SearchServiceProvider = SearchServiceProvider.TAVILY,

    /** Default per-task timeout for a search task to complete. */
    val taskTimeout: Duration = 60.seconds,

    /** API key for Tavily. */
    val tavilyApiKey: String? = null,

    /** Base URL for Tavily. */
    val tavilyBaseUrl: String = "https://api.tavily.com",

    /** API key for Bocha. */
    val bochaApiKey: String? = null,

    /** Base URL for Bocha. */
    val bochaBaseUrl: String = "https://api.bochaai.com/v1/web-search",
) {
    companion object {
        /**
         * Build a [SearchConfig] from the application configuration.
         *
         * Reads the same property keys that the original 4.13.x in-repo
         * implementation read via `@Value`, so existing `application.properties`
         * keep working without changes.
         */
        fun fromConfig(conf: ImmutableConfig): SearchConfig {
            return SearchConfig(
                primaryProvider = parseProvider(conf.get("search.provider", "tavily")),
                taskTimeout = conf.getLong("search.taskTimeoutMillis", 60_000L).milliseconds,
                tavilyApiKey = conf.get("search.tavily.api.key")
                    ?.ifBlank { null }
                    ?: conf.get("TAVILY_API_KEY")?.ifBlank { null },
                tavilyBaseUrl = conf.get("search.tavily.base.url", "https://api.tavily.com"),
                bochaApiKey = conf.get("search.bocha.api.key")
                    ?.ifBlank { null }
                    ?: conf.get("BOCHA_API_KEY")?.ifBlank { null },
                bochaBaseUrl = conf.get("search.bocha.base.url", "https://api.bochaai.com/v1/web-search"),
            )
        }

        private fun parseProvider(name: String): SearchServiceProvider {
            return try {
                SearchServiceProvider.valueOf(name.uppercase())
            } catch (_: IllegalArgumentException) {
                SearchServiceProvider.NONE
            }
        }
    }
}
