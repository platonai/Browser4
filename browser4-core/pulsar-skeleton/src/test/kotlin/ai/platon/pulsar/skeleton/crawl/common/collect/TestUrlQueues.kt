package ai.platon.pulsar.skeleton.crawl.common.collect

import ai.platon.pulsar.common.collect.queue.ConcurrentNEntrantQueue
import ai.platon.pulsar.common.collect.queue.ConcurrentNonReentrantQueue
import ai.platon.pulsar.common.urls.ComparableUrlAware
import ai.platon.pulsar.common.urls.Hyperlink
import kotlin.test.*
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestUrlQueues {
    val urls = IntRange(1, 100)
        .map { "https://www.amazon.com/s?k=insomnia&i=aps&page=$it" }
        .map { Hyperlink(it) }

    @Test
    fun testConcurrentNEntrantUrlQueue() {
        val queue = ConcurrentNEntrantQueue<ComparableUrlAware>(3)
        queue.addAll(urls)

        assertTrue { queue.size == urls.size }
        var i = 0
        while (i++ < urls.size) {
            val v = queue.poll()
            assertNotNull(v)
            assertTrue { v is Hyperlink }
        }
        assertTrue { queue.isEmpty() }
    }

    @Test
    fun testConcurrentNonEntrantUrlQueue() {
        val queue = ConcurrentNonReentrantQueue<ComparableUrlAware>()
        queue.addAll(urls)

        assertTrue { queue.size == urls.size }
        var i = 0
        while (i++ < urls.size) {
            val v = queue.poll()
            assertNotNull(v)
            assertTrue { v is Hyperlink }
        }
        assertTrue { queue.isEmpty() }
    }
}
