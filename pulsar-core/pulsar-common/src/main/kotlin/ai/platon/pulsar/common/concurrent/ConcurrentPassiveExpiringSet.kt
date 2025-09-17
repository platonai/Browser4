package ai.platon.pulsar.common.concurrent

import org.apache.commons.collections4.map.PassiveExpiringMap
import java.time.Duration
import java.util.*
import java.util.function.IntFunction

/**
 * Decorates a <code>Set</code> to evict expired entries once their expiration
 * time has been reached.
 *
 * Constructs a map decorator that decorates the given map and results in
 * entries NEVER expiring. If there are any elements already in the map
 * being decorated, they also will NEVER expire.
 */
class ConcurrentPassiveExpiringSet<E>(val ttl: Duration = Duration.ofSeconds(-1)): MutableSet<E> {
    /**
     * A fast yet short life least recently used cache
     */
    private val map = Collections.synchronizedMap(PassiveExpiringMap<E, Any>(ttl.toMillis()))

    override fun add(element: E): Boolean = map.put(element, {}) == null

    override fun remove(element: E): Boolean = map.remove(element) != null

    override fun clear() = map.clear()

    override val size: Int get() = map.size

    override fun contains(element: E): Boolean = map.containsKey(element)

    override fun containsAll(elements: Collection<E>): Boolean = map.keys.containsAll(elements)

    override fun isEmpty(): Boolean = map.isEmpty()

    override fun iterator(): MutableIterator<E> = map.keys.iterator()

    override fun addAll(elements: Collection<E>): Boolean {
        val size = map.size
        elements.map { add(it) }
        return size != map.size
    }

    override fun removeAll(elements: Collection<E>): Boolean = map.keys.removeAll(elements)

    override fun retainAll(elements: Collection<E>): Boolean = map.keys.retainAll(elements)

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true;
        }

        return other is ConcurrentPassiveExpiringSet<*> && map.keys == other.map.keys
    }

    override fun hashCode(): Int = map.keys.hashCode()
}
