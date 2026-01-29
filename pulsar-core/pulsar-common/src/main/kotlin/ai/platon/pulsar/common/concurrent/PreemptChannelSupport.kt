package ai.platon.pulsar.common.concurrent

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.jvm.Throws

/**
 * Implements the preemptive channel concurrency pattern, which consists of two channels: preemptive and normal.
 *
 * Key characteristics:
 * 1. Both channels allow multiple threads.
 * 2. New workers must wait until there are no ready or running preemptive tasks.
 * 3. A preemptive task locks the working channel immediately but must wait to run until all workers are finished.
 *
 *                     |------ waiting ------------|- ready -|-------------- critical  -------------------|---finished----
 *
 *                                              The entrance gate                                    The exit gate
 * Preemptive channel: ------------#1--#1--#1------|----#2---|-----------------------------------#3-------|--- #4 --------
 *                                                 |         |                                            |
 * Normal channel:     ----*1----*1--*1------*1----|---------|-----------*2--*2------*2--*2---------------|--- *3 --------
 *
 * #1 The waiting preemptive tasks
 * #2 The ready preemptive tasks
 * #3 The running preemptive tasks
 * #4 The finished preemptive tasks
 *
 * *1 The waiting workers
 * *2 The running workers
 * *3 The finished workers
 */
open class PreemptChannelSupport(val name: String = "") {
    /**
     * The lock used to synchronize access to the preemptive channel.
     * Preemptive tasks are low-probability events, so it's OK to use locks.
     */
    private val lock = ReentrantLock()
    private val noPreemptiveTasks = lock.newCondition()
    private val noRunningNormalTasks = lock.newCondition()
    private var pollingTimeout = Duration.ofMillis(100)

    protected val numPreemptiveTasks = AtomicInteger()
    protected val numRunningPreemptiveTasks = AtomicInteger()
    protected val numPendingNormalTasks = AtomicInteger()
    protected val numRunningNormalTasks = AtomicInteger()

    /**
     * Indicates whether there are any preemptive tasks.
     */
    val isPreempted get() = numPreemptiveTasks.get() > 0

    /**
     * Indicates whether the channel is in a normal state (no preemptive tasks).
     */
    val isNormal get() = !isPreempted

    /**
     * Indicates whether there are any active events (preemptive or normal tasks).
     */
    val hasEvent get() = arrayOf(
        numPreemptiveTasks,
        numRunningPreemptiveTasks, numPendingNormalTasks, numRunningNormalTasks
    ).sumOf { it.get() } > 0

    /**
     * Executes a preemptive task. If there is at least one preemptive task in the critical section, all normal tasks must wait.
     *
     * Preemptive tasks are low-probability events, so it's OK to use locks.
     *
     * @param preemptiveTask The preemptive task to execute.
     * @return The result of the preemptive task.
     * @throws InterruptedException If the thread is interrupted while waiting.
     */
    @Throws(InterruptedException::class)
    fun <T> preempt(preemptiveTask: () -> T): T {
        return beforePreempt().runCatching { preemptiveTask() }.also { afterPreempt() }.getOrThrow()
    }

    /**
     * Executes a normal task. Normal tasks must wait until there are no preemptive tasks.
     *
     * @param task The normal task to execute.
     * @return The result of the normal task.
     * @throws InterruptedException If the thread is interrupted while waiting.
     */
    @Throws(InterruptedException::class)
    fun <T> whenNormal(task: () -> T): T {
        return beforeTask().runCatching { task() }.also { afterTask() }.getOrThrow()
    }

    /**
     * Executes a deferred normal task. Normal tasks must wait until there are no preemptive tasks.
     *
     * Preemptive task is a low-probability event, so it's OK to use locks.
     *
     * @param task The deferred normal task to execute.
     * @return The result of the normal task.
     * @throws InterruptedException If the thread is interrupted while waiting.
     */
    @Throws(InterruptedException::class)
    suspend fun <T> whenNormalDeferred(task: suspend () -> T): T {
        return beforeTask().runCatching { task() }.also { afterTask() }.getOrThrow()
    }

    /**
     * Releases all locks forcefully. This is typically used in error recovery scenarios.
     */
    fun releaseLocks() {
        if (numRunningNormalTasks.get() == 0) {
            System.err.println("Force release preemptive locks | ${formatPreemptChannelStatus()}")
            numPreemptiveTasks.set(0)
            numRunningPreemptiveTasks.set(0)
        }

        numRunningNormalTasks.set(0)
    }

    /**
     * Formats the current status of the preemptive channel.
     *
     * @return A string representation of the preemptive channel's status.
     */
    fun formatPreemptChannelStatus(): String {
        return "pTasks: $numPreemptiveTasks," +
                " rPTasks: $numRunningPreemptiveTasks," +
                " pNTasks: $numPendingNormalTasks," +
                " rNTasks: $numRunningNormalTasks"
    }

    @Throws(InterruptedException::class)
    private fun beforePreempt() {
        // All workers must NOT pass now
        numPreemptiveTasks.incrementAndGet()
        // Wait until all the normal tasks are finished
        waitUntilNoRunningNormalTasks()
    }

    @Throws(InterruptedException::class)
    private fun afterPreempt() {
        numRunningPreemptiveTasks.decrementAndGet()
        lock.withLock {
            if (numPreemptiveTasks.decrementAndGet() == 0) {
                // all tasks are allowed to pass
                noPreemptiveTasks.signalAll()
            }
        }
    }

    @Throws(InterruptedException::class)
    private fun beforeTask() {
        numPendingNormalTasks.incrementAndGet()
        // wait all the preemptive tasks are finished
        waitUntilNoPreemptiveTask()
    }

    @Throws(InterruptedException::class)
    private fun afterTask() {
        lock.withLock {
            if (numRunningNormalTasks.decrementAndGet() == 0) {
                // all preemptive tasks are allowed to pass
                noRunningNormalTasks.signalAll()
            }
        }
    }

    @Throws(InterruptedException::class)
    private fun waitUntilNoRunningNormalTasks() {
        lock.withLock {
            var nanos = pollingTimeout.toNanos()
            while (numRunningNormalTasks.get() > 0 && nanos > 0) {
                nanos = noRunningNormalTasks.awaitNanos(nanos)
            }

            // must be guarded by lock
            numRunningPreemptiveTasks.incrementAndGet()
        }
    }

    @Throws(InterruptedException::class)
    private fun waitUntilNoPreemptiveTask() {
        lock.withLock {
            var nanos = pollingTimeout.toNanos()
            while (numPreemptiveTasks.get() > 0 && nanos > 0) {
                nanos = noPreemptiveTasks.awaitNanos(nanos)
            }

            // must be guarded by lock
            // task channel is open now
            numPendingNormalTasks.decrementAndGet()
            numRunningNormalTasks.incrementAndGet()
        }
    }
}
