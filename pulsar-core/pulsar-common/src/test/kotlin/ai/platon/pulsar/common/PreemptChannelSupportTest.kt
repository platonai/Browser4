package ai.platon.pulsar.common

import ai.platon.pulsar.common.concurrent.PreemptChannelSupport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PreemptChannelSupportTest {
    private lateinit var channel: PreemptChannelSupport
    private val testTimeout = 5000L // 5 seconds timeout for tests

    @BeforeEach
    fun setUp() {
        channel = PreemptChannelSupport("test-channel")
    }

    @Test
    fun `test initial state`() {
        assertFalse(channel.isPreempted)
        assertTrue(channel.isNormal)
        assertFalse(channel.hasEvent)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `test preemptive task blocks normal tasks`() {
        val latch = CountDownLatch(1)
        val normalTaskStarted = AtomicInteger(0)
        val normalTaskCompleted = AtomicInteger(0)

        // Start a preemptive task that takes some time
        Thread {
            channel.preempt {
                Thread.sleep(100) // Simulate work
                latch.countDown()
            }
        }.start()

        // Start multiple normal tasks
        repeat(3) {
            Thread {
                channel.whenNormal {
                    normalTaskStarted.incrementAndGet()
                    Thread.sleep(50) // Simulate work
                    normalTaskCompleted.incrementAndGet()
                }
            }.start()
        }

        // Wait for preemptive task to complete
        assertTrue(latch.await(testTimeout, TimeUnit.MILLISECONDS))

        // Give some time for normal tasks to complete
        Thread.sleep(200)

        // Verify that normal tasks were blocked until preemptive task completed
        assertEquals(3, normalTaskStarted.get())
        assertEquals(3, normalTaskCompleted.get())
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `test multiple preemptive tasks`() {
        val latch = CountDownLatch(2)
        val results = ConcurrentLinkedDeque<String>()

        // Start two preemptive tasks
        repeat(2) { i ->
            Thread {
                channel.preempt {
                    Thread.sleep(50) // Simulate work
                    results.add("preemptive-$i")
                    latch.countDown()
                }
            }.start()
        }

        // Wait for both tasks to complete
        assertTrue(latch.await(testTimeout, TimeUnit.MILLISECONDS))

        // Verify both tasks completed
        assertEquals(2, results.size)
        assertTrue(results.contains("preemptive-0"))
        assertTrue(results.contains("preemptive-1"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `test releaseLocks`() {
        val latch = CountDownLatch(1)
        var normalTaskCompleted = false

        // Start a preemptive task
        Thread {
            channel.preempt {
                Thread.sleep(100) // Simulate work
                latch.countDown()
            }
        }.start()

        // Start a normal task
        Thread {
            channel.whenNormal {
                normalTaskCompleted = true
            }
        }.start()

        // Force release locks
        channel.releaseLocks()

        // Wait for tasks to complete
        assertTrue(latch.await(testTimeout, TimeUnit.MILLISECONDS))
        Thread.sleep(100)

        // Verify state after release
        assertFalse(channel.isPreempted)
        assertTrue(channel.isNormal)
        assertTrue(normalTaskCompleted)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `test concurrent normal tasks`() {
        val latch = CountDownLatch(3)
        val results = ConcurrentLinkedDeque<String>()

        // Start multiple normal tasks concurrently
        repeat(3) { i ->
            Thread {
                channel.whenNormal {
                    Thread.sleep(50) // Simulate work
                    results.add("normal-$i")
                    latch.countDown()
                }
            }.start()
        }

        // Wait for all tasks to complete
        assertTrue(latch.await(testTimeout, TimeUnit.MILLISECONDS))

        // Verify all tasks completed
        assertEquals(3, results.size)
        assertTrue(results.contains("normal-0"))
        assertTrue(results.contains("normal-1"))
        assertTrue(results.contains("normal-2"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `test preemptive task with exception`() {
        val exception = assertThrows<RuntimeException> {
            channel.preempt {
                throw RuntimeException("Test exception")
            }
        }
        assertEquals("Test exception", exception.message)
        assertFalse(channel.isPreempted)
        assertTrue(channel.isNormal)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `test normal task with exception`() {
        val exception = assertThrows<RuntimeException> {
            channel.whenNormal {
                throw RuntimeException("Test exception")
            }
        }
        assertEquals("Test exception", exception.message)
        assertFalse(channel.isPreempted)
        assertTrue(channel.isNormal)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `test mixed preemptive and normal tasks`() {
        val latch = CountDownLatch(4)
        val results = ConcurrentLinkedDeque<String>()

        // Start preemptive and normal tasks
        repeat(2) { i ->
            Thread {
                channel.preempt {
                    Thread.sleep(50)
                    results.add("preemptive-$i")
                    latch.countDown()
                }
            }.start()
        }

        repeat(2) { i ->
            Thread {
                channel.whenNormal {
                    Thread.sleep(50)
                    results.add("normal-$i")
                    latch.countDown()
                }
            }.start()
        }

        // Wait for all tasks to complete
        assertTrue(latch.await(testTimeout, TimeUnit.MILLISECONDS))

        // Verify all tasks completed
        assertEquals(4, results.size)
        assertTrue(results.contains("preemptive-0"))
        assertTrue(results.contains("preemptive-1"))
        assertTrue(results.contains("normal-0"))
        assertTrue(results.contains("normal-1"))
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun testPreemptReturnsValue() {
        val result = channel.preempt { 42 }
        assertEquals(42, result)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun testWhenNormalReturnsValue() {
        val result = channel.whenNormal { "hello" }
        assertEquals("hello", result)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun testPreemptReturnsComplexValue() {
        val result = channel.preempt {
            data class Result(val value: Int, val message: String)
            Result(100, "success")
        }
        assertEquals(100, result.value)
        assertEquals("success", result.message)
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun testWhenNormalReturnsComplexValue() {
        val result = channel.whenNormal {
            listOf(1, 2, 3, 4, 5)
        }
        assertEquals(listOf(1, 2, 3, 4, 5), result)
    }
}
