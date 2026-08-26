package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Background L0→L1 consolidation scheduler (M3): after a task completes, the
 * engine schedules its deposit into the knowledge layer (PEM). Runs fully
 * async on a background scope, delayed so the task's main path returns first,
 * never throws into the agent loop, and is idempotent per task.
 *
 * Design: docs-dev/copilot/robust-browser-agent-memory-system-design.md (§8.3).
 */
class MemoryConsolidator(
    private val provider: MemoryKnowledgeProvider,
    private val scope: MemoryScope,
    private val backgroundScope: CoroutineScope,
    private val enabled: Boolean = MemoryConfig.consolidationEnabled,
    private val delayMs: Long = 5_000,
    private val maxPending: Int = 8,
) {
    private val logger = getLogger(MemoryConsolidator::class)
    private val scheduled = ConcurrentHashMap.newKeySet<String>()
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    /** Schedule [taskId] for async knowledge deposit (idempotent, bounded). */
    fun schedule(taskId: String) {
        if (!enabled) return
        if (!scheduled.add(taskId)) return
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet()
            scheduled.remove(taskId)
            logger.warn("memory.consolidation queue full, skipping task {}", taskId)
            return
        }
        backgroundScope.launch {
            try {
                delay(delayMs.milliseconds)
                runCatching { provider.deposit(taskId, scope) }
                    .onFailure { logger.warn("memory.consolidation failed for {}: {}", taskId, it.message) }
            } finally {
                pending.decrementAndGet()
            }
        }
    }
}
