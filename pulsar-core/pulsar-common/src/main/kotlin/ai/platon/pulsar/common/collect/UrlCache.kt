package ai.platon.pulsar.common.collect

import ai.platon.pulsar.common.Priority13
import ai.platon.pulsar.common.collect.queue.*
import ai.platon.pulsar.common.urls.UrlAware
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The url cache holds urls.
 *
 * A url cache contains several queues for different purpose: reentrant, non-reentrant and n-reentrant.
 * A reentrant queue accepts the same url multiple times, a non-reentrant queue accepts the same url only once,
 * and an n-reentrant queue accepts the same url for n times at most.
 *
 * The URL cache is expected to be very large and items may be loaded from external sources such as MongoDB.
 * */
interface UrlCache {
    /**
     * The cache name
     * */
    val name: String

    /**
     * The priority
     * */
    val priority: Int

    /**
     * A non-reentrant queue accepts the same url only once
     * */
    val nonReentrantQueue: Queue<UrlAware>

    /**
     * An n-reentrant queue accepts the same url for n times at most
     * */
    val nReentrantQueue: Queue<UrlAware>

    /**
     * A reentrant queue accepts the same url multiple times
     * */
    val reentrantQueue: Queue<UrlAware>

    /**
     * Create a list of all the queues
     * */
    val queues: List<Queue<UrlAware>>
        get() = listOf(nonReentrantQueue, nReentrantQueue, reentrantQueue)

    /**
     * The total size of all the queues
     * */
    val size get() = queues.sumOf { it.size }

    /**
     * The precise count of urls in the external source, since the external source can be very large,
     * retrieving the precise size can be very slow in some external source.
     * */
    val externalSize: Int get() = 0

    /**
     * The estimated, imprecise count of urls in the external source, it should be very fast.
     * */
    val estimatedExternalSize get() = 0

    /**
     * The estimated, imprecise count of all urls both in local cache and the external source.
     * */
    val estimatedSize get() = size + estimatedExternalSize

    /**
     * Remove dead urls.
     * */
    fun removeDeceased()

    /**
     * Clear the local cache.
     * */
    fun clear()

    /**
     * Clear both the local cache and external source.
     * */
    fun deepClear() = clear()
}

abstract class AbstractUrlCache(
    override val name: String,
    override val priority: Int
) : UrlCache {
    override fun removeDeceased() {
        val now = Instant.now()
        queues.forEach { it.removeIf { it.deadline < now } }
    }

    override fun clear() {
        queues.forEach { it.clear() }
    }
}

open class ConcurrentUrlCache(
    name: String = "",
    priority: Int = Priority13.NORMAL.value
) : AbstractUrlCache(name, priority) {
    override val nonReentrantQueue = ConcurrentNonReentrantQueue<UrlAware>()
    override val nReentrantQueue = ConcurrentNEntrantQueue<UrlAware>(3)
    override val reentrantQueue = ConcurrentLinkedQueue<UrlAware>()
}

/**
 * Contains a sets of loading queues which can load urls from external source using [urlLoader].
 * */
class LoadingUrlCache constructor(
    name: String,
    priority: Int,
    /**
     * A loader to load urls from external sources
     * */
    val urlLoader: ExternalUrlLoader,
    /**
     * The capacity for each queue
     * */
    val capacity: Int = LoadingQueue.DEFAULT_CAPACITY,
) : AbstractUrlCache(name, priority), Loadable<UrlAware> {

    companion object {
        const val G_NON_REENTRANT = 1
        const val G_N_ENTRANT = 2
        const val G_REENTRANT = 3
    }

    override val nonReentrantQueue = ConcurrentNonReentrantLoadingQueue(urlLoader, topic(G_NON_REENTRANT))
    override val nReentrantQueue = ConcurrentNEntrantLoadingQueue(urlLoader, topic(G_N_ENTRANT), 3)
    override val reentrantQueue = ConcurrentLoadingQueue(urlLoader, topic(G_REENTRANT))
    override val queues: List<Queue<UrlAware>> get() = listOf(nonReentrantQueue, nReentrantQueue, reentrantQueue)
    override val externalSize: Int
        get() = queues.filterIsInstance<LoadingQueue<UrlAware>>().sumOf { it.externalSize }
    override val estimatedExternalSize: Int
        get() = queues.filterIsInstance<LoadingQueue<UrlAware>>().sumOf { it.estimatedExternalSize }

    override fun load() {
        queues.filterIsInstance<Loadable<UrlAware>>().forEach { it.load() }
    }

    override fun loadNow(): Collection<UrlAware> {
        return queues.filterIsInstance<Loadable<UrlAware>>().flatMap { it.loadNow() }
    }

    override fun deepClear() {
        queues.filterIsInstance<LoadingQueue<UrlAware>>().forEach { it.deepClear() }
    }

    private fun topic(group: Int) = UrlTopic(name, group, priority, capacity)
}
