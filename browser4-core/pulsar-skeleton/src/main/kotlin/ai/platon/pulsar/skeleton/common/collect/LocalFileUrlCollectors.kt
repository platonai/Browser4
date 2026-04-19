package ai.platon.pulsar.skeleton.common.collect

import ai.platon.pulsar.common.Priority13
import ai.platon.pulsar.common.collect.LocalFileUrlLoader
import ai.platon.pulsar.common.collect.UrlTopic
import ai.platon.pulsar.common.collect.collector.AbstractPriorityDataCollector
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.common.urls.Hyperlinks
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

open class LocalFileHyperlinkCollector(
        /**
         * The path of the file source
         * */
        val path: Path,
        /**
         * The priority
         * */
        priority: Int = Priority13.NORMAL.value,
): AbstractPriorityDataCollector<UrlAware>(priority) {

    private val log = LoggerFactory.getLogger(LocalFileHyperlinkCollector::class.java)
    private val urlLoader = LocalFileUrlLoader(path)
    private val isLoaded = AtomicBoolean()
    private val cache: MutableList<Hyperlink> = Collections.synchronizedList(LinkedList())

    /**
     * The cache capacity, we assume that all items in the file are loaded into the cache
     * */
    override val capacity: Int = 1_000_000
    /**
     * The collector name
     * */
    override var name: String = path.fileName.toString()

    val fileName = path.fileName.toString()

    var loadArgs: String? = null

    val hyperlinks: List<Hyperlink> get() = ensureLoaded().cache

    override val size: Int get() = hyperlinks.size

    constructor(path: Path, priority: Priority13): this(path, priority.value)

    override fun hasMore() = hyperlinks.isNotEmpty()

    override fun collectTo(sink: MutableList<UrlAware>): Int {
        beforeCollect()

        val count = cache.removeFirstOrNull()?.takeIf { sink.add(it) }?.let { 1 } ?: 0

        return afterCollect(count)
    }

    @Synchronized
    override fun dump(): List<String> {
        return hyperlinks.map { it.toString() }
    }

    private fun ensureLoaded(): LocalFileHyperlinkCollector {
        if (isLoaded.compareAndSet(false, true)) {
            val remainingCapacity = capacity - cache.size
            val group = UrlTopic("", 0, priority, capacity)
            urlLoader.loadToNow(cache, remainingCapacity, group) {
                val args = LoadOptions.merge(it.args, loadArgs, VolatileConfig.UNSAFE).toString()
                Hyperlinks.toHyperlink(it).also { it.args = args }
            }

            val msg = if (loadArgs != null) " | $loadArgs " else ""
            log.info("Loaded total {} urls from file | $msg{}", cache.size, path)
        }

        return this
    }
}
