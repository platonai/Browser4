package ai.platon.pulsar.skeleton.crawl.common

import ai.platon.pulsar.common.collect.ConcurrentUrlPool
import ai.platon.pulsar.common.collect.UrlPool
import ai.platon.pulsar.common.concurrent.ConcurrentExpiringLRUCache
import ai.platon.pulsar.common.concurrent.ConcurrentExpiringLRUCache.Companion.CACHE_CAPACITY
import ai.platon.pulsar.common.config.CapabilityTypes.GLOBAL_DOCUMENT_CACHE_SIZE
import ai.platon.pulsar.common.config.CapabilityTypes.GLOBAL_PAGE_CACHE_SIZE
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import java.util.concurrent.ConcurrentSkipListSet

typealias PageCatch = ConcurrentExpiringLRUCache<String, WebPage>

typealias DocumentCatch = ConcurrentExpiringLRUCache<String, FeaturedDocument>

class FetchingCache {

    private val fetchingUrls = ConcurrentSkipListSet<String>()

    fun isFetching(url: String) = fetchingUrls.contains(url)

    fun add(url: String) {
        fetchingUrls.add(url)
    }

    fun addAll(urls: Iterable<String>) {
        fetchingUrls.addAll(urls)
    }

    fun remove(url: String) {
        fetchingUrls.remove(url)
    }

    fun removeAll(urls: Iterable<String>) {
        fetchingUrls.removeAll(urls)
    }

    fun clear() = fetchingUrls.clear()

    operator fun contains(url: String) = fetchingUrls.contains(url)
}

/**
 * The global cache.
 * */
open class GlobalCache(val conf: ImmutableConfig) {
    /**
     * The page cache capacity
     * */
    private val pageCacheCapacity = conf.getUint(GLOBAL_PAGE_CACHE_SIZE, CACHE_CAPACITY)
    /**
     * The document cache capacity
     * */
    private val documentCacheCapacity = conf.getUint(GLOBAL_DOCUMENT_CACHE_SIZE, CACHE_CAPACITY)
    /**
     * A url pool contains many url caches, the urls added to the pool will be processed in crawl loops.
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
    open val pageCache = PageCatch(capacity = pageCacheCapacity)
    /**
     * The global document cache, a document will be removed automatically if it's expired or the cache is full.
     * */
    open val documentCache = DocumentCatch(capacity = documentCacheCapacity)

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

