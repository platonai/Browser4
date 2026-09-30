package ai.platon.pulsar.agentic.inference.chat

import java.util.UUID

/**
 * Append-only compaction ledger for one [AgentToolCallLoop] instance.
 *
 * Tracks every durable rewrite of the conversation (prunes, reference/diff
 * folds, and region compaction) so that historical references stay resolvable
 * after compression — the structural traceability counterpart of
 * deepseek-harness's `compaction/start|summary|end` event stream plus
 * `sourceEventSeqs` provenance (see
 * docs-dev/copilot/compaction-traceability-design.md).
 *
 * The conversation itself remains a plain `ChatMessage` list (history is only
 * ever rewritten by compression, never by the ledger); the ledger records
 * *what happened* in message-index terms, and [resolve] maps a stable tool
 * `callId` (or a historical message index) to the current state.
 *
 * Not thread-safe — one instance per [AgentToolCallLoop].
 *
 * @param enabled master switch; when false every method becomes a no-op and
 *   [resolve] always returns [Resolution.Unknown], degrading to the previous
 *   digest-only self-sufficiency behavior.
 * @param onEntry optional observer fired after every durable entry is
 *   appended (with [enabled] true only) — used to persist a disk-side audit
 *   trail (e.g. the CLI engine's `cli-compactions.jsonl`) without coupling
 *   the ledger to any storage.
 */
class CompactionLedger(
    val enabled: Boolean = true,
    val onEntry: ((Entry) -> Unit)? = null,
) {
    /** One durable rewrite recorded on the ledger timeline. */
    sealed class Entry {
        /** A raw tool result entered the conversation at [messageIndex]. */
        data class ResultRegistered(val callId: String, val messageIndex: Int) : Entry()

        /**
         * The result of [callId] entered as a compact reference/diff at
         * [compactIndex], replacing what would have been a full copy.
         */
        data class Folded(val callId: String, val originalIndex: Int, val compactIndex: Int) : Entry()

        /** The result of [callId] was head/middle/tail pruned in place. */
        data class Pruned(
            val callId: String,
            val shadowedIndex: Int,
            val replacementIndex: Int,
            val shadowedTokens: Long,
            val removedTokens: Long,
        ) : Entry()

        /**
         * A contiguous message range was replaced by one checkpoint message.
         * [failure] non-null marks a failed (or abandoned) attempt that left
         * the conversation untouched but is still visible on the timeline.
         */
        data class Compacted(
            val compactionId: String,
            val reason: String,
            val shadowedRange: IntRange,
            val replacementIndex: Int,
            val shadowedTokens: Long,
            val replacementTokens: Long,
            val failure: String? = null,
        ) : Entry()
    }

    /** Where a historical reference resolves to in the current conversation. */
    sealed class Resolution {
        /** The referenced content is still present at [messageIndex]. */
        data class Live(val messageIndex: Int) : Resolution()

        /** The referenced content was pruned; [replacementIndex] holds its shrunken form. */
        data class PrunedAway(val replacementIndex: Int) : Resolution()

        /** The referenced content was compacted into the checkpoint [compactionId]. */
        data class CompactedAway(val compactionId: String) : Resolution()

        /** No ledger knowledge of this reference. */
        data class Unknown(val reason: String) : Resolution()
    }

    /** Timeline of recorded entries, oldest first. */
    private val _entries = mutableListOf<Entry>()

    /** Cumulative estimated tokens removed by pruning (audit/对账 surface). */
    private var pruneSavings = 0L

    /** Read-only view of the timeline. */
    val entries: List<Entry> get() = _entries.toList()

    /** Cumulative estimated tokens removed by prunes so far. */
    fun estimatePruneSavings(): Long = pruneSavings

    /** Record a raw tool result entering the conversation. */
    fun registerResult(callId: String, messageIndex: Int) {
        if (!enabled) return
        val entry = Entry.ResultRegistered(callId, messageIndex)
        _entries += entry
        onEntry?.invoke(entry)
    }

    /** Record a reference/diff fold at [compactIndex] for [callId]. */
    fun recordFolded(callId: String, originalIndex: Int, compactIndex: Int) {
        if (!enabled) return
        val entry = Entry.Folded(callId, originalIndex, compactIndex)
        _entries += entry
        onEntry?.invoke(entry)
    }

    /**
     * Record an in-place prune of [callId]'s result.
     * @param shadowedTokens estimated tokens of the original content.
     * @param removedTokens  estimated tokens actually removed (original - replacement).
     */
    fun recordPruned(
        callId: String,
        shadowedIndex: Int,
        replacementIndex: Int,
        shadowedTokens: Long,
        removedTokens: Long,
    ) {
        if (!enabled) return
        pruneSavings += removedTokens
        val entry = Entry.Pruned(callId, shadowedIndex, replacementIndex, shadowedTokens, removedTokens)
        _entries += entry
        onEntry?.invoke(entry)
    }

    /**
     * Record a region compaction (successful or failed). A failed attempt
     * carries [failure] and a `replacementIndex` of -1 (nothing landed).
     * @return the compaction id (generated when not supplied).
     */
    fun recordCompacted(
        reason: String,
        shadowedRange: IntRange,
        replacementIndex: Int,
        shadowedTokens: Long,
        replacementTokens: Long,
        failure: String? = null,
        compactionId: String = UUID.randomUUID().toString(),
    ): String {
        if (!enabled) return compactionId
        val entry = Entry.Compacted(
            compactionId = compactionId,
            reason = reason,
            shadowedRange = shadowedRange,
            replacementIndex = replacementIndex,
            shadowedTokens = shadowedTokens,
            replacementTokens = replacementTokens,
            failure = failure,
        )
        _entries += entry
        onEntry?.invoke(entry)
        return compactionId
    }

    /**
     * Resolve the current whereabouts of a stable tool-call id. The latest
     * entry mentioning the id wins (timeline is monotonic).
     */
    fun resolve(callId: String): Resolution {
        if (!enabled) return Resolution.Unknown("ledger disabled")
        for (entry in _entries.asReversed()) {
            when (entry) {
                is Entry.ResultRegistered -> if (entry.callId == callId) return Resolution.Live(entry.messageIndex)
                is Entry.Folded -> if (entry.callId == callId) return Resolution.Live(entry.compactIndex)
                is Entry.Pruned -> if (entry.callId == callId) return Resolution.PrunedAway(entry.replacementIndex)
                is Entry.Compacted -> Unit
            }
        }
        return Resolution.Unknown("no ledger entry for callId=$callId")
    }

    /**
     * Resolve a historical message index (e.g. the `#k` in a reference text
     * written before a later compaction) against every compaction range.
     */
    fun resolveIndex(originalIndex: Int): Resolution {
        if (!enabled) return Resolution.Unknown("ledger disabled")
        for (entry in _entries.asReversed()) {
            if (entry is Entry.Compacted && entry.failure == null && originalIndex in entry.shadowedRange) {
                return Resolution.CompactedAway(entry.compactionId)
            }
        }
        return Resolution.Unknown("index=$originalIndex not covered by any compaction")
    }
}
