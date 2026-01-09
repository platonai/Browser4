package ai.platon.pulsar.common

import org.apache.commons.collections4.queue.CircularFifoQueue
import org.apache.commons.lang3.RandomStringUtils
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestCollections {

    @Test
    fun testChunked() {
        val chunkSize = 100
        val chunks = IntRange(1, 1000).toList().chunked(chunkSize)
        chunks.forEach {
            assertEquals(chunkSize, it.size) }
    }

    @Test
    fun testDeque() {
        val deque = ConcurrentLinkedDeque<Int>()
        IntRange(1, 10).forEach { deque.addFirst(it) }
//        deque.take(5).joinToString().let { logPrintln(it) }
        assertEquals("10, 9, 8, 7, 6", deque.take(5).joinToString())
    }

    @Test
    fun `When group by than the order of keys are reserved`() {
        val sortedEntries = IntRange(1, 100).map {
            Random(10).nextInt() to RandomStringUtils.secure().nextAlphanumeric(10)
        }.shuffled().sortedBy { it.first }
        val groupedEntries = sortedEntries.groupBy { it.first }
        groupedEntries.entries.zipWithNext().forEach {
            assertTrue { it.first.key <= it.second.key }
        }
    }
}
