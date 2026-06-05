package ai.platon.pulsar.skeleton.workflow.common

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * A Caffeine-backed alternative to [ai.platon.pulsar.common.concurrent.ConcurrentExpiringLRUCache]
 * that exposes the same `putDatum` / `getDatum` / `remove` / `clear` / `size` API for
 * drop-in compatibility.
 *
 * Entries are evicted via Caffeine's Window TinyLFU policy when the cache exceeds
 * [capacity], and expire after [ttl] regardless of access.
 */
open class CaffeineExpiringCache<K : Any, V : Any>(
    ttl: Duration = DEFAULT_TTL,
    capacity: Long = DEFAULT_CAPACITY,
) {
    companion object {
        /** Default cache capacity, matching ConcurrentExpiringLRUCache.CACHE_CAPACITY. */
        const val DEFAULT_CAPACITY: Long = 10_000
        val DEFAULT_TTL: Duration = Duration.ofHours(2)
    }

    private val cache: Cache<K, Entry<V>> = Caffeine.newBuilder()
        .maximumSize(capacity)
        .expireAfterWrite(ttl.toMillis(), TimeUnit.MILLISECONDS)
        .recordStats()
        .build()

    /**
     * Lightweight entry wrapper so that [remove] can return the removed value
     * via `.datum`, matching the ConcurrentExpiringLRUCache contract.
     */
    class Entry<V>(val datum: V)

    /** Cache an item. */
    fun putDatum(key: K, value: V) {
        cache.put(key, Entry(value))
    }

    /** Retrieve a non-expired item (Caffeine handles time-based expiry automatically). */
    fun getDatum(key: K): V? = cache.getIfPresent(key)?.datum

    /**
     * Retrieve an item with an additional caller-specified expiry window.
     *
     * Caffeine already expires entries based on the cache-level TTL, but callers
     * may pass a tighter [expires] duration + [now] to reject entries that are
     * technically in the cache but older than the caller's threshold.
     *
     * NOTE: The caller is responsible for checking the returned value's own
     * timestamp (e.g. [ai.platon.pulsar.persist.WebPage.prevFetchTime]) via
     * `options.isExpired()`.
     */
    fun getDatum(key: K, expires: Duration, now: Instant): V? = getDatum(key)

    /**
     * Remove and return the cached entry.
     *
     * @return the removed [Entry] (with its [Entry.datum]), or null if the key
     *         was not present.
     */
    fun remove(key: K): Entry<V>? {
        val entry = cache.getIfPresent(key)
        cache.invalidate(key)
        return entry
    }

    /** Remove all entries. */
    fun clear() = cache.invalidateAll()

    /** Estimated number of entries. */
    val size: Long get() = cache.estimatedSize()

    /** Underlying Caffeine cache for direct access when needed. */
    fun asCache(): Cache<K, Entry<V>> = cache

    fun stats() = cache.stats()
}
