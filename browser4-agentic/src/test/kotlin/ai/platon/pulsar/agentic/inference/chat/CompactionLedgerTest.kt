package ai.platon.pulsar.agentic.inference.chat

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@DisplayName("CompactionLedger structural traceability")
class CompactionLedgerTest {

    @Test
    @DisplayName("resolve() returns the latest entry for a callId (live result)")
    fun resolveFindsLiveResult() {
        val ledger = CompactionLedger()
        ledger.registerResult("call-1", 3)
        ledger.registerResult("call-2", 5)

        val resolution = ledger.resolve("call-1")

        assertIs<CompactionLedger.Resolution.Live>(resolution)
        assertEquals(3, resolution.messageIndex)
    }

    @Test
    @DisplayName("resolve() returns the compact form index for a folded callId")
    fun resolveFindsFoldedForm() {
        val ledger = CompactionLedger()
        ledger.registerResult("call-1", 3)
        ledger.recordFolded("call-2", 0, 6)

        val resolution = ledger.resolve("call-2")

        assertIs<CompactionLedger.Resolution.Live>(resolution)
        assertEquals(6, resolution.messageIndex, "a folded reference is still live at its compact index")
    }

    @Test
    @DisplayName("resolve() returns PrunedAway for a pruned callId")
    fun resolveFindsPrunedForm() {
        val ledger = CompactionLedger()
        ledger.registerResult("call-1", 3)
        ledger.recordPruned("call-1", 3, 3, shadowedTokens = 500, removedTokens = 400)

        val resolution = ledger.resolve("call-1")

        assertIs<CompactionLedger.Resolution.PrunedAway>(resolution)
        assertEquals(3, resolution.replacementIndex)
        assertEquals(400, ledger.estimatePruneSavings(), "prune savings must accumulate")
    }

    @Test
    @DisplayName("latest entry wins when a callId appears multiple times")
    fun latestEntryWins() {
        val ledger = CompactionLedger()
        ledger.registerResult("call-1", 3)
        ledger.recordPruned("call-1", 3, 3, shadowedTokens = 500, removedTokens = 300)
        ledger.registerResult("call-1", 9)

        assertIs<CompactionLedger.Resolution.Live>(ledger.resolve("call-1")).let {
            assertEquals(9, it.messageIndex, "a later raw registration supersedes the prune")
        }
    }

    @Test
    @DisplayName("resolveIndex() maps a historical index into a successful compaction")
    fun resolveIndexHitsCompactionRange() {
        val ledger = CompactionLedger()
        ledger.recordCompacted(
            reason = "pressure",
            shadowedRange = 2..7,
            replacementIndex = 2,
            shadowedTokens = 1_000,
            replacementTokens = 120,
        )

        val hit = ledger.resolveIndex(4)
        assertIs<CompactionLedger.Resolution.CompactedAway>(hit)
        assertEquals(ledger.entries.filterIsInstance<CompactionLedger.Entry.Compacted>().single().compactionId, hit.compactionId)

        val miss = ledger.resolveIndex(9)
        assertIs<CompactionLedger.Resolution.Unknown>(miss)
    }

    @Test
    @DisplayName("failed compaction attempts are visible on the timeline and never resolve")
    fun failedCompactionsLeaveTrace() {
        val ledger = CompactionLedger()
        val id = ledger.recordCompacted(
            reason = "pressure",
            shadowedRange = 2..7,
            replacementIndex = -1,
            shadowedTokens = 1_000,
            replacementTokens = 0,
            failure = "summary not smaller than shadowed content",
        )

        val entries = ledger.entries.filterIsInstance<CompactionLedger.Entry.Compacted>()
        assertEquals(1, entries.size)
        assertEquals(id, entries.single().compactionId)
        assertEquals("pressure", entries.single().reason)
        assertTrue(entries.single().failure!!.contains("not smaller"))

        // A failed attempt does not shadow its range for resolution purposes.
        assertIs<CompactionLedger.Resolution.Unknown>(ledger.resolveIndex(4))
    }

    @Test
    @DisplayName("disabled ledger records nothing and resolves to Unknown")
    fun disabledLedgerIsTransparent() {
        val ledger = CompactionLedger(enabled = false)
        ledger.registerResult("call-1", 3)
        ledger.recordPruned("call-1", 3, 3, shadowedTokens = 100, removedTokens = 90)
        ledger.recordCompacted("pressure", 2..7, 2, 1_000, 100)

        assertTrue(ledger.entries.isEmpty())
        assertEquals(0, ledger.estimatePruneSavings())
        assertIs<CompactionLedger.Resolution.Unknown>(ledger.resolve("call-1"))
        assertIs<CompactionLedger.Resolution.Unknown>(ledger.resolveIndex(4))
    }

    @Test
    @DisplayName("prune savings accumulate across multiple prunes")
    fun pruneSavingsAccumulate() {
        val ledger = CompactionLedger()
        ledger.recordPruned("a", 1, 1, 500, 400)
        ledger.recordPruned("b", 2, 2, 300, 250)

        assertEquals(650, ledger.estimatePruneSavings())
    }

    @Test
    @DisplayName("onEntry fires for every durable entry (disk-side audit trail)")
    fun onEntryFiresForDurableEntries() {
        val observed = mutableListOf<CompactionLedger.Entry>()
        val ledger = CompactionLedger(onEntry = { observed += it })

        ledger.registerResult("call-1", 3)
        ledger.recordFolded("call-2", 0, 6)
        ledger.recordPruned("call-3", 2, 2, 500, 400)
        ledger.recordCompacted("pressure", 2..7, 2, 1_000, 120)

        assertEquals(4, observed.size)
        assertIs<CompactionLedger.Entry.ResultRegistered>(observed[0])
        assertIs<CompactionLedger.Entry.Folded>(observed[1])
        assertIs<CompactionLedger.Entry.Pruned>(observed[2])
        assertIs<CompactionLedger.Entry.Compacted>(observed[3]).let {
            assertEquals("pressure", it.reason)
            assertEquals(2..7, it.shadowedRange)
        }
    }

    @Test
    @DisplayName("onEntry does not fire when the ledger is disabled")
    fun onEntrySilentWhenDisabled() {
        var fired = 0
        val ledger = CompactionLedger(enabled = false, onEntry = { fired++ })

        ledger.registerResult("call-1", 3)
        ledger.recordCompacted("pressure", 2..7, 2, 1_000, 120)

        assertEquals(0, fired, "a disabled ledger records nothing, so nothing is observed")
    }
}
