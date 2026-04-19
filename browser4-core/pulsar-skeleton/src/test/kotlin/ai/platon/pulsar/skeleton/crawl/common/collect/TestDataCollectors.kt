package ai.platon.pulsar.skeleton.crawl.common.collect

import ai.platon.browser4.common.B4ResourceLoader
import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.Priority13
import ai.platon.pulsar.common.collect.*
import ai.platon.pulsar.common.collect.collector.AbstractPriorityDataCollector
import ai.platon.pulsar.common.collect.collector.PriorityDataCollector
import ai.platon.pulsar.common.collect.collector.UrlCacheCollector
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.common.urls.PlainUrl
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.skeleton.common.collect.LocalFileHyperlinkCollector
import org.apache.commons.collections4.MultiValuedMap
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap
import org.junit.jupiter.api.DisplayName
import java.nio.file.Paths
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestDataCollectors : TestBase() {

    private val lowerCacheSize = 10

    @Test
    @DisplayName("When add a item to queue then queue is not empty")
    fun whenAddAItemToQueueThenQueueIsNotEmpty() {
        val source = LoadingUrlCache("", 0, TemporaryLocalFileUrlLoader())
        val sink = mutableListOf<UrlAware>()

        source.nReentrantQueue.add(PlainUrl(AppConstants.EXAMPLE_URL))
        assertTrue { source.size == 1 }
        val collector = UrlCacheCollector(source)
        assertTrue { collector.hasMore() }
        collector.collectTo(sink)

        assertTrue { sink.isNotEmpty() }
    }

    @Test
    @DisplayName("When add an item to LoadingurlCache then LoadingIterable has next")
    fun whenAddAnItemToLoadingurlcacheThenLoadingiterableHasNext() {
        val urlCache = LoadingUrlCache("", 0, TemporaryLocalFileUrlLoader())
        urlCache.nReentrantQueue.add(PlainUrl(AppConstants.EXAMPLE_URL))
        assertEquals(1, urlCache.size)

        val collectors: MutableList<PriorityDataCollector<UrlAware>> = Collections.synchronizedList(LinkedList())
        collectors += UrlCacheCollector(urlCache)
        val fetchQueueIterable = ConcurrentLoadingIterable(ChainedDataCollector(collectors), null, null, 10)

        assertTrue { fetchQueueIterable.regularCollector.hasMore() }
        assertTrue { fetchQueueIterable.iterator().hasNext() }
        assertEquals(AppConstants.EXAMPLE_URL, fetchQueueIterable.iterator().next().url)
    }

    @Test
    fun testDataCollectorSorting() {
        // Object information is erased
        val collectors = mutableListOf<AbstractPriorityDataCollector<UrlAware>>()
        urlPool.orderedCaches.forEach { (priority, urlCache) ->
            collectors += UrlCacheCollector(urlCache)
        }
        assertEquals(urlPool.orderedCaches.size, collectors.size)
        assertTrue { collectors.first().priority < collectors.last().priority }
//        collectors.sortedBy { it.priority }.forEach { logPrintln("$it ${it.priority}") }

        printlnPro("Adding another normal collector ...")
        val priority = Priority13.NORMAL.value
        val normalCollector = UrlCacheCollector(urlPool.normalCache)
        collectors += normalCollector
        assertEquals(2, collectors.count { it.priority == priority })
//        collectors.sortedBy { it.priority }.forEach { logPrintln("$it ${it.priority}") }

        val normalCollector2 = LocalFileHyperlinkCollector(Paths.get("/tmp/non-exist"), priority)
        collectors += normalCollector2
        assertEquals(3, collectors.count { it.priority == priority })
//        collectors.sortedBy { it.priority }.forEach { logPrintln("$it ${it.priority}") }
    }

    @Test
    fun testDataCollectorSorting2() {
        val collectors: MultiValuedMap<Int, AbstractPriorityDataCollector<UrlAware>> = ArrayListValuedHashMap()

        globalCache.urlPool.orderedCaches.forEach { (priority, urlCache) ->
            collectors.put(priority, UrlCacheCollector(urlCache))
        }
        assertEquals(urlPool.orderedCaches.size, collectors.keySet().size)
//        assertTrue { collectors.first().priority < collectors.last().priority }
        collectors.keySet().sorted().forEach { p -> printlnPro("$p ${collectors.get(p)}") }

        printlnPro("Adding 2nd normal collector ...")
        val priority = Priority13.NORMAL.value
        val normalCollector = UrlCacheCollector(urlPool.normalCache)
        collectors.put(priority, normalCollector)
        assertEquals(2, collectors.get(priority).size)
        collectors.keySet().sorted().forEach { p -> printlnPro("$p ${collectors.get(p)}") }

        printlnPro("Adding 3rd normal collector ...")
        val normalCollector2 = LocalFileHyperlinkCollector(Paths.get("/tmp/non-exist"), priority)
        collectors.put(priority, normalCollector2)
        assertEquals(3, collectors.get(priority).size)
        collectors.keySet().sorted().forEach { p -> printlnPro("$p ${collectors.get(p)}") }
    }

    @Test
    @DisplayName("When iterate through fetch iterable then items are correct")
    fun whenIterateThroughFetchIterableThenItemsAreCorrect() {
        val fetchIterable = UrlFeeder(urlPool, lowerCacheSize)
        val resourceURI = B4ResourceLoader.getURL("seeds/head100/best-sellers.txt").toURI()
        val collector = LocalFileHyperlinkCollector(Paths.get(resourceURI), Priority13.NORMAL)
        fetchIterable.addDefaultCollectors()
        fetchIterable.addCollector(collector)

        val cache = fetchIterable.urlPool.lower2Cache
        LinkExtractors.fromResource("seeds/head100/most-wished-for.txt")
            .mapTo(cache.nonReentrantQueue) { Hyperlink(it) }

        var i = 0
        fetchIterable.forEach {
            if (i < 100) {
                assertTrue("The $i-th urls should contains zgbs but is ${it.url}") { it.url.contains("zgbs") }
            } else {
                assertTrue("The $i-th urls should contains most-wished-for but is ${it.url}") { it.url.contains("most-wished-for") }
            }
            ++i
        }

        assertTrue { collector.hyperlinks.isEmpty() }
        assertTrue { cache.nonReentrantQueue.isEmpty() }
    }
}
