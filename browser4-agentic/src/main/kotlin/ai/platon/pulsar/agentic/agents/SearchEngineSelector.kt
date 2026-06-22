package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.NetUtil
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.AppConstants.SEARCH_ENGINE_URLS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Selects the best available search engine by testing network connectivity.
 * Falls back to geographically appropriate defaults when no engine is reachable.
 */
object SearchEngineSelector {

    /**
     * Tests each configured search engine URL for network reachability and returns
     * the first responsive one. Falls back to a region-appropriate default URL
     * when none of the configured engines respond.
     */
    suspend fun selectBest(): String {
        val searchURL = SEARCH_ENGINE_URLS.firstOrNull {
            withContext(Dispatchers.IO) { NetUtil.testHttpNetwork(it) }
        }

        if (searchURL != null) {
            return searchURL
        }

        return if (AppContext.isCN) AppConstants.SEARCH_ENGINE_URL else AppConstants.SEARCH_ENGINE_EN_URL
    }
}
