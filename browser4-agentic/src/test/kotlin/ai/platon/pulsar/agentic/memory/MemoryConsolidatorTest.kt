package ai.platon.pulsar.agentic.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("MemoryConsolidator")
class MemoryConsolidatorTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private class FakeProvider : MemoryKnowledgeProvider {
        val deposited = ConcurrentHashMap.newKeySet<String>()
        val calls = AtomicInteger(0)
        override suspend fun query(taskText: String, url: String?, scope: MemoryScope): KnowledgeHits = KnowledgeHits()
        override suspend fun deposit(taskId: String, scope: MemoryScope): Boolean {
            calls.incrementAndGet()
            deposited.add(taskId)
            return true
        }
    }

    @Test
    @DisplayName("schedules async deposit after the delay")
    fun testScheduleDeposits() = runBlocking {
        val provider = FakeProvider()
        val consolidator = MemoryConsolidator(
            provider, MemoryScope(agentUuid = "a1"), scope, delayMs = 50,
        )
        consolidator.schedule("t1")
        delay(300)
        assertTrue(provider.deposited.contains("t1"))
    }

    @Test
    @DisplayName("is idempotent per task")
    fun testIdempotent() = runBlocking {
        val provider = FakeProvider()
        val consolidator = MemoryConsolidator(
            provider, MemoryScope(agentUuid = "a1"), scope, delayMs = 50,
        )
        consolidator.schedule("t1")
        consolidator.schedule("t1")
        delay(300)
        assertEquals(1, provider.calls.get())
    }

    @Test
    @DisplayName("disabled consolidator does nothing")
    fun testDisabled() = runBlocking {
        val provider = FakeProvider()
        val consolidator = MemoryConsolidator(
            provider, MemoryScope(agentUuid = "a1"), scope, enabled = false, delayMs = 50,
        )
        consolidator.schedule("t1")
        delay(200)
        assertEquals(0, provider.calls.get())
    }

    @Test
    @DisplayName("bounded pending queue rejects overflow")
    fun testQueueBound() = runBlocking {
        val provider = FakeProvider()
        val consolidator = MemoryConsolidator(
            provider, MemoryScope(agentUuid = "a1"), scope, delayMs = 200, maxPending = 2,
        )
        repeat(5) { consolidator.schedule("t$it") }
        delay(600)
        // Only 2 deposits may run; the rest were rejected by the bound.
        assertTrue(provider.calls.get() <= 2, "pending queue must be bounded")
    }
}
