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
package ai.platon.pulsar.search.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmSessionProvider
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.search.SearchConfig
import ai.platon.pulsar.search.SearchServiceProvider
import ai.platon.pulsar.search.controller.SearchController
import ai.platon.pulsar.search.provider.BochaProvider
import ai.platon.pulsar.search.provider.TavilyProvider
import ai.platon.pulsar.search.service.SearchProvider
import ai.platon.pulsar.search.service.SearchService
import ai.platon.pulsar.search.tools.SearchToolExecutor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Spring Boot auto-configuration for the browser4-search plugin.
 *
 * Wires the full search pipeline:
 *  - [SearchConfig] resolved from `application.properties`
 *  - the active [SearchProvider] (Tavily by default, Bocha if `search.provider=bocha`)
 *  - [SearchService] task lifecycle backend (Caffeine 100 LRU + 8-way concurrent coroutines)
 *  - [SearchController] REST endpoints under `api/search`
 *  - [SearchToolExecutor] MCP/LLM agent tool (`search.submit` / `status` / `result` / `cancel`)
 *
 * Implements [ToolMount] so [ai.platon.pulsar.boot.plugin.PluginManager] can
 * register the tool executor in the core registry.  Disable the whole plugin
 * with `search.enabled=false`; or set `search.provider=none` to keep the REST
 * endpoints available but degrade gracefully when no API key is configured.
 *
 * Gated on [SwarmSessionProvider] (the same fun interface used by the swarm
 * plugin) so this plugin never depends on a REST module class — the host
 * provides the session bean.
 *
 * Mirrors [ai.platon.pulsar.captcha.config.CaptchaAutoConfiguration] and
 * [ai.platon.pulsar.swarm.config.SwarmAutoConfiguration] in shape.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["ai.platon.pulsar.search.SearchConfig"])
@ConditionalOnProperty(name = ["search.enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(SwarmSessionProvider::class)
@Lazy
open class SearchAutoConfiguration(
    private val applicationContext: ApplicationContext,
) : ToolMount {

    private val logger = getLogger(SearchAutoConfiguration::class)

    // ---- Mount Points ----

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("searchToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            logger.warn("Failed to get searchToolExecutor for mount: {}", e.message)
            emptyList()
        }
    }

    // ---- Configuration ----

    @Bean(name = ["searchConfig"])
    @ConditionalOnMissingBean(name = ["searchConfig"])
    open fun searchConfig(conf: ImmutableConfig): SearchConfig {
        return SearchConfig.fromConfig(conf)
    }

    // ---- Provider selection ----

    @Bean(name = ["searchProvider"])
    @ConditionalOnMissingBean(name = ["searchProvider"])
    open fun searchProvider(searchConfig: SearchConfig): SearchProvider? {
        val primary = searchConfig.primaryProvider
        return when (primary) {
            SearchServiceProvider.TAVILY -> {
                if (searchConfig.tavilyApiKey.isNullOrBlank()) {
                    logger.warn(
                        "Tavily selected as search provider but no API key is configured; " +
                            "set TAVILY_API_KEY or search.tavily.api.key. " +
                            "Search tasks will fail until the key is provided."
                    )
                }
                TavilyProvider(
                    apiKey = searchConfig.tavilyApiKey ?: "",
                    baseUrl = searchConfig.tavilyBaseUrl,
                ).also {
                    logger.info("Tavily registered as the search provider")
                }
            }
            SearchServiceProvider.BOCHA -> {
                if (searchConfig.bochaApiKey.isNullOrBlank()) {
                    logger.warn(
                        "Bocha selected as search provider but no API key is configured; " +
                            "set BOCHA_API_KEY or search.bocha.api.key. " +
                            "Search tasks will fail until the key is provided."
                    )
                }
                BochaProvider(
                    apiKey = searchConfig.bochaApiKey ?: "",
                    baseUrl = searchConfig.bochaBaseUrl,
                ).also {
                    logger.info("Bocha registered as the search provider")
                }
            }
            SearchServiceProvider.NONE -> {
                logger.info("search.provider=none — no search provider configured; search endpoints disabled")
                null
            }
        }
    }

    // ---- Service ----

    @Bean(name = ["searchService"])
    @ConditionalOnMissingBean(name = ["searchService"])
    @ConditionalOnBean(SearchProvider::class)
    open fun searchService(
        sessionProvider: SwarmSessionProvider,
        searchProvider: SearchProvider,
        searchConfig: SearchConfig,
    ): SearchService {
        return SearchService(
            sessionProvider = sessionProvider,
            provider = searchProvider,
            defaultTaskTimeoutMillis = searchConfig.taskTimeout.inWholeMilliseconds,
        ).also {
            logger.info("SearchService initialized with timeout=${searchConfig.taskTimeout}")
        }
    }

    // ---- REST Controller ----

    @Bean(name = ["searchController"])
    @ConditionalOnMissingBean(name = ["searchController"])
    @ConditionalOnBean(SearchService::class)
    open fun searchController(searchService: SearchService): SearchController {
        return SearchController(searchService).also {
            logger.info("SearchController registered at api/search")
        }
    }

    // ---- LLM Agent Tool Executor ----

    @Bean(name = ["searchToolExecutor"])
    @ConditionalOnMissingBean(name = ["searchToolExecutor"])
    @ConditionalOnBean(SearchService::class)
    open fun searchToolExecutor(searchService: SearchService): SearchToolExecutor {
        return SearchToolExecutor(searchService).also {
            logger.info("SearchToolExecutor registered (domain=search)")
        }
    }
}
