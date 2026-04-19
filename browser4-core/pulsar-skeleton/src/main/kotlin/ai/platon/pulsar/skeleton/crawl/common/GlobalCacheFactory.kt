package ai.platon.pulsar.skeleton.crawl.common

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * The global cache factory.
 */
class GlobalCacheFactory(
    val immutableConfig: ImmutableConfig
) {
    companion object {
        private var globalCacheInstance: GlobalCache? = null

        /**
         * Set the global cache instance.
         * @param cache The global cache instance to set.
         */
        @Synchronized
        fun setGlobalCache(cache: GlobalCache) {
            globalCacheInstance = cache
        }
    }

    /**
     * Initialize the global cache with default settings.
     */
    private fun initializeGlobalCache(): GlobalCache {
        val cache = GlobalCache(immutableConfig)
        globalCacheInstance = cache
        return cache
    }

    /**
     * Get the global cache instance.
     */
    @get:Synchronized
    val globalCache: GlobalCache
        get() = globalCacheInstance ?: initializeGlobalCache()
}
