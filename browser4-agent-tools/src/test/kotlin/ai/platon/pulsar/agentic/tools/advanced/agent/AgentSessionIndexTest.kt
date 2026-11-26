package ai.platon.pulsar.agentic.tools.advanced.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The public projection of the per-session conversation log: folding, TTL and the turn cap
 * the console's sidebar/restore views rely on.
 */
@DisplayName("agent session index")
class AgentSessionIndexTest {

    private fun file(dir: Path): Path = dir.resolve("agent-session-context.jsonl")

    private fun append(
        dir: Path,
        sessionId: String,
        updatedAt: Long,
        turns: List<Pair<String, String>> = emptyList(),
        taskCount: Int = 0,
        compressed: String? = null,
    ) {
        SessionConversationStore.shared(file(dir)).append(
            SessionSnapshot(
                sessionId = sessionId,
                updatedAt = updatedAt,
                taskCount = taskCount,
                compressed = compressed,
                turns = turns.map { (role, text) -> SessionTurn(role, text) },
            ),
        )
    }

    @Test
    @DisplayName("the newest row per session wins, newest session first, expired rows dropped")
    fun foldsRowsAndDropsExpiredSessions(@TempDir dir: Path) {
        val now = 1_000_000_000_000L
        val minute = 60_000L
        // Two rows for the same session: the later one is the truth.
        append(dir, "s-old", now - 10 * minute, listOf("task" to "first"), taskCount = 1)
        append(dir, "s-old", now - 2 * minute, listOf("task" to "second"), taskCount = 2)
        append(dir, "s-new", now - minute, listOf("task" to "newest"), taskCount = 1)
        // Older than the TTL: must not appear.
        append(dir, "s-expired", now - 24 * 60 * minute, listOf("task" to "ancient"))

        val sessions = AgentSessionIndex.list(ttlMinutes = 720, now = now, persistencePath = file(dir))

        assertEquals(listOf("s-new", "s-old"), sessions.map { it.sessionId })
        assertEquals(listOf("second"), sessions[1].turns.map { it.text })
        assertEquals(2, sessions[1].taskCount)
        assertTrue(sessions.none { it.sessionId == "s-expired" })
    }

    @Test
    @DisplayName("a long session keeps its newest turns and says the rest exists")
    fun capsTurnsAndFlagsTruncation(@TempDir dir: Path) {
        val now = 1_000_000_000_000L
        val turns = (1..AgentSessionIndex.MAX_TURNS + 5).map { "tool" to "call $it" }
        append(dir, "s-long", now, turns, taskCount = 40)

        val session = AgentSessionIndex.of("s-long", ttlMinutes = 720, now = now, persistencePath = file(dir))!!

        assertEquals(AgentSessionIndex.MAX_TURNS, session.turns.size)
        assertTrue(session.turnsTruncated)
        assertEquals("call ${AgentSessionIndex.MAX_TURNS + 5}", session.turns.last().text, "the newest turns are kept")
    }

    @Test
    @DisplayName("a session with no conversation is unknown, not an empty shell")
    fun unknownSessionIsNull(@TempDir dir: Path) {
        val now = 1_000_000_000_000L
        append(dir, "s-1", now, listOf("task" to "only one"))

        assertNull(AgentSessionIndex.of("s-2", ttlMinutes = 720, now = now, persistencePath = file(dir)))
        assertNull(AgentSessionIndex.of("  ", ttlMinutes = 720, now = now, persistencePath = file(dir)))
    }

    @Test
    @DisplayName("a non-positive TTL keeps the whole log, for an operator surface that asked for it")
    fun nonPositiveTtlKeepsEverything(@TempDir dir: Path) {
        val now = 1_000_000_000_000L
        append(dir, "s-ancient", now - 365L * 24 * 60 * 60_000, listOf("task" to "a year ago"))

        val sessions = AgentSessionIndex.list(ttlMinutes = 0, now = now, persistencePath = file(dir))

        assertEquals(listOf("s-ancient"), sessions.map { it.sessionId })
    }
}
