package ai.platon.pulsar.skeleton.crawl

import ai.platon.pulsar.common.StartStopRunnable
import java.util.function.Predicate

class TaskLoops(val loops: MutableList<TaskLoop>) : StartStopRunnable {
    companion object {
        val filters = mutableListOf<Predicate<TaskLoop>>()
    }

    override val isRunning: Boolean
        get() = loops.any { it.isRunning }

    constructor(loop: TaskLoop) : this(mutableListOf(loop))

    fun first() = loops.first()

    fun last() = loops.last()

    inline fun <reified T : TaskLoop> firstIsInstance() = loops.filterIsInstance<T>().first()

    inline fun <reified T : TaskLoop> lastIsInstance() = loops.filterIsInstance<T>().last()

    /**
     * Start all loops matching the filter if not started.
     * */
    override fun start() {
        loops.filter { loop -> filters.isEmpty() || filters.all { it.test(loop) } }
            .filter { !it.isRunning }
            .forEach { it.start() }
    }

    /**
     * Stop all loops matching the filter if started.
     * */
    override fun stop() {
        loops.filter { loop -> filters.isEmpty() || filters.all { it.test(loop) } }
            .filter { it.isRunning }
            .forEach { it.stop() }
    }

    @Throws(InterruptedException::class)
    override fun await() {
        loops.forEach { it.await() }
    }
}
