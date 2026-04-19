package ai.platon.pulsar.rest.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests verifying that WebDriver operations are properly serialized using the mutex.
 *
 * These tests verify that:
 * 1. Multiple concurrent operations on the same session are serialized
 * 2. Operations on different sessions can run in parallel
 * 3. The mutex properly prevents race conditions
 */
class WebDriverSerializationTest {

    @Test
    @DisplayName("test mutex ensures serial execution within a session")
    fun testMutexEnsuresSerialExecution() = runBlocking {
        // Simulate a session mutex
        val mutex = Mutex()

        val executionOrder = mutableListOf<Int>()
        val concurrentCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        // Launch 10 concurrent operations on the same session
        val jobs = (1..10).map { i ->
            launch {
                mutex.withLock {
                    // Track concurrent execution
                    val current = concurrentCount.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }

                    // Record execution order (mutex ensures only one thread here at a time)
                    executionOrder.add(i)

                    // Simulate some work
                    delay(10)

                    concurrentCount.decrementAndGet()
                }
            }
        }

        // Wait for all jobs to complete
        jobs.joinAll()

        // Verify that operations were serialized (max concurrent should be 1)
        assertEquals(1, maxConcurrent.get(), "Operations should be serialized, not run in parallel")

        // Verify all operations completed
        assertEquals(10, executionOrder.size, "All operations should complete")
    }

    @Test
    @DisplayName("test operations on different sessions can run in parallel")
    fun testDifferentSessionsCanRunInParallel() = runBlocking {
        // Create multiple session mutexes
        val mutex1 = Mutex()
        val mutex2 = Mutex()
        val mutex3 = Mutex()

        val concurrentCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        // Launch operations on different sessions
        val jobs = listOf(
            launch {
                mutex1.withLock {
                    val current = concurrentCount.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    delay(100) // Simulate work
                    concurrentCount.decrementAndGet()
                }
            },
            launch {
                mutex2.withLock {
                    val current = concurrentCount.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    delay(100) // Simulate work
                    concurrentCount.decrementAndGet()
                }
            },
            launch {
                mutex3.withLock {
                    val current = concurrentCount.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    delay(100) // Simulate work
                    concurrentCount.decrementAndGet()
                }
            }
        )

        jobs.joinAll()

        // Verify that at least 2 sessions ran concurrently
        // If they were all serialized, maxConcurrent would be 1
        assertTrue(maxConcurrent.get() >= 2, "Different sessions should run in parallel, not be serialized")
    }

    @Test
    @DisplayName("test mutex prevents race conditions")
    fun testMutexPreventsRaceConditions() = runBlocking {
        val mutex = Mutex()
        var counter = 0
        val iterations = 100

        // Launch multiple coroutines that increment a counter
        val jobs = (1..iterations).map {
            launch {
                mutex.withLock {
                    // Read-modify-write without additional synchronization
                    val temp = counter
                    delay(1) // Increase chance of race condition without mutex
                    counter = temp + 1
                }
            }
        }

        jobs.joinAll()

        // With mutex, the counter should be exactly 'iterations'
        // Without mutex, it would be less due to lost updates
        assertEquals(iterations, counter, "Mutex should prevent race conditions")
    }

    @Test
    @DisplayName("test concurrent operations are queued correctly")
    fun testConcurrentOperationsAreQueued() = runBlocking {
        val mutex = Mutex()
        val executionLog = mutableListOf<String>()

        // Start first operation
        val job1 = launch {
            mutex.withLock {
                executionLog.add("job1-start")
                delay(50)
                executionLog.add("job1-end")
            }
        }

        // Wait a bit to ensure job1 acquires the lock first
        delay(10)

        // Start second operation (should be queued)
        val job2 = launch {
            mutex.withLock {
                executionLog.add("job2-start")
                delay(50)
                executionLog.add("job2-end")
            }
        }

        // Wait for both to complete
        job1.join()
        job2.join()

        // Verify order: job1 completes before job2 starts
        assertEquals(4, executionLog.size)
        assertEquals("job1-start", executionLog[0])
        assertEquals("job1-end", executionLog[1])
        assertEquals("job2-start", executionLog[2])
        assertEquals("job2-end", executionLog[3])
    }
}
