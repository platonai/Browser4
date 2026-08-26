package ai.platon.pulsar.agentic.memory

/**
 * Bounded, thread-safe ring of the most recent [MemoryEvent]s of one memory
 * backend. The query service reads this first (live-preferred resolution):
 * in-flight tasks are answerable even before their events hit disk, and the
 * buffer never grows without bound.
 */
class EventBuffer(private val capacity: Int) {

    private val deque = ArrayDeque<MemoryEvent>()

    @Synchronized
    fun add(event: MemoryEvent) {
        deque.addLast(event)
        while (deque.size > capacity) deque.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<MemoryEvent> = deque.toList()

    /** Drop one task's events (explicit forget must not leave live ghosts). */
    @Synchronized
    fun removeTask(taskId: String): Int {
        val before = deque.size
        deque.removeAll { it.taskId == taskId }
        return before - deque.size
    }

    @Synchronized
    fun size(): Int = deque.size

    @Synchronized
    fun clear() = deque.clear()
}
