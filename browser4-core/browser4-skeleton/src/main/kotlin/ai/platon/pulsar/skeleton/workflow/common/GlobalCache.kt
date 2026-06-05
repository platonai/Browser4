package ai.platon.pulsar.skeleton.workflow.common

import ai.platon.pulsar.common.collect.ConcurrentUrlPool
import ai.platon.pulsar.common.collect.UrlPool
import ai.platon.pulsar.common.config.CapabilityTypes.GLOBAL_DOCUMENT_CACHE_SIZE
import ai.platon.pulsar.common.config.CapabilityTypes.GLOBAL_PAGE_CACHE_SIZE
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.dom.FeaturedDocument
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

typealias PageCatch = CaffeineExpiringCache<String, WebPage>

typealias DocumentCatch = CaffeineExpiringCache<String, FeaturedDocument>

/**
 * Lightweight "fetching" tracker backed by a Caffeine cache with time-based
 * expiration.  Replaces the previous [java.util.concurrent.ConcurrentSkipListSet]
 * so that URLs that are never removed (e.g. due to a bug or exception) expire
 * automatically rather than leaking memory.
 */
class FetchingCache {

    private val cache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(100_000)
        .build()

    fun isFetching(url: String) = cache.getIfPresent(url) != null

    fun add(url: String) {
        cache.put(url, true)
    }

    fun addAll(urls: Iterable<String>) {
        urls.forEach { cache.put(it, true) }
    }

    fun remove(url: String) {
        cache.invalidate(url)
    }

    fun removeAll(urls: Iterable<String>) {
        cache.invalidateAll(urls)
    }

    fun clear() = cache.invalidateAll()

    operator fun contains(url: String) = cache.getIfPresent(url) != null
}

/**
 * The global cache.
 * */
open class GlobalCache(val conf: ImmutableConfig) {
    /**
     * The page cache capacity
     * */
    private val pageCacheCapacity = conf.getLong(GLOBAL_PAGE_CACHE_SIZE, CaffeineExpiringCache.DEFAULT_CAPACITY)
    /**
     * The document cache capacity
     * */
    private val documentCacheCapacity = conf.getLong(GLOBAL_DOCUMENT_CACHE_SIZE, CaffeineExpiringCache.DEFAULT_CAPACITY)
    /**
     * A url pool contains many url caches, the urls added to the pool will be processed in Main loops.
     * */
    open var urlPool: UrlPool = ConcurrentUrlPool(conf).apply { initialize() }
    /**
     * Fetching cache holds the URLs being fetched.
     *
     * URLs are cached before being fetched and removed from the cache after retrieval.
     *
     * The cache is used to avoid fetching the same URL multiple times.
     * */
    open val fetchingCache = FetchingCache()
    /**
     * The global page cache, a page will be removed automatically if it's expired or the cache is full.
     * */
    open val pageCache = PageCatch(ttl = CaffeineExpiringCache.DEFAULT_TTL, capacity = pageCacheCapacity)
    /**
     * The global document cache, a document will be removed automatically if it's expired or the cache is full.
     * */
    open val documentCache = DocumentCatch(ttl = CaffeineExpiringCache.DEFAULT_TTL, capacity = documentCacheCapacity)

    /**
     * Reset all caches. After this operation, all caches will be empty.
     * */
    fun resetCaches() {
        fetchingCache.clear()
        pageCache.clear()
        documentCache.clear()
        urlPool = ConcurrentUrlPool(conf).apply { initialize() }
    }

    /**
     * Clear all caches. After this operation, all caches will be empty.
     * */
    fun clearCaches() {
        fetchingCache.clear()
        pageCache.clear()
        documentCache.clear()
        urlPool.clear()
    }

    /**
     * Clear page cache and document cache. After this operation, page cache and document cache will be empty.
     * */
    fun clearPDCaches() {
        pageCache.clear()
        documentCache.clear()
    }

    /**
     * Put the page and the document in the cache.
     * */
    fun putPDCache(page: WebPage, document: FeaturedDocument) {
        val url = page.url
        pageCache.putDatum(url, page)
        documentCache.putDatum(url, document)
    }

    /**
     * Remove items specified by the url from page cache and document cache.
     * */
    fun removePDCache(url: String) {
        pageCache.remove(url)
        documentCache.remove(url)
    }
}
