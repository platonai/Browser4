package ai.platon.pulsar.coding

/**
 * Line-based diff engine with standard algorithms — Myers (default) and Patience.
 *
 * Zero dependencies, pure Kotlin. Replaces the naive index-aligned line diff that
 * produced noisy output whenever a single line was inserted mid-file.
 *
 * ## Myers
 *
 * Models diff as a shortest-path search on an (N+1)x(M+1) edit graph:
 * right = delete old line, down = insert new line, diagonal = matching line (free).
 * A greedy frontier over diagonals (k = x - y) finds the minimum edit path,
 * preferring matches — so unchanged lines stay aligned. O((N+M)*D) time,
 * O(N+M) space via trace-and-backtrack.
 *
 * ## Patience
 *
 * Git's alternative algorithm: anchors on lines that are *unique* in both inputs,
 * matches anchors in order (longest-increasing-subsequence over their positions in
 * the new file), then runs Myers on the gaps between anchors. Produces diffs that
 * read more naturally for code (function moves, repeated boilerplate lines).
 */
object DiffEngine {

    /** A single line-level edit. */
    sealed class Edit {
        /** Line present in both inputs. */
        data class Equal(val line: String) : Edit()

        /** Line only in the old input. */
        data class Delete(val line: String) : Edit()

        /** Line only in the new input. */
        data class Insert(val line: String) : Edit()
    }

    /**
     * Compute the line diff between [old] and [new].
     *
     * @param algorithm "myers" (default) or "patience"
     */
    fun diff(old: List<String>, new: List<String>, algorithm: String = "myers"): List<Edit> {
        if (old == new) return old.map { Edit.Equal(it) }
        return when (algorithm.lowercase()) {
            "patience" -> patienceDiff(old, new)
            else -> myersDiff(old, new)
        }
    }

    /**
     * Render edits as a unified diff (`--- a/` / `+++ b/` header with hunks).
     * Returns null when there are no differences.
     */
    fun toUnified(oldPath: String, newPath: String, edits: List<Edit>, context: Int = 3): String? {
        val changeIdx = edits.indices.filter { edits[it] !is Edit.Equal }
        if (changeIdx.isEmpty()) return null

        val sb = StringBuilder()
        sb.appendLine("--- a/$oldPath")
        sb.appendLine("+++ b/$newPath")

        // Group changes into hunks: each hunk spans a change plus `context` lines of
        // surrounding equal context; hunks closer than 2*context+2 lines merge.
        var hunkStart = 0
        var i = 0
        while (i < changeIdx.size) {
            val first = changeIdx[i]
            val start = (first - context).coerceAtLeast(hunkStart)

            var last = first
            while (i + 1 < changeIdx.size && changeIdx[i + 1] - last - 1 <= 2 * context) {
                i++
                last = changeIdx[i]
            }
            val end = (last + context + 1).coerceAtMost(edits.size)

            // Compute old/new line numbers at hunk start.
            var oldStart = 1
            var newStart = 1
            for (j in 0 until start) {
                when (edits[j]) {
                    is Edit.Equal -> { oldStart++; newStart++ }
                    is Edit.Delete -> oldStart++
                    is Edit.Insert -> newStart++
                }
            }

            // Build the hunk body.
            var oldCount = 0
            var newCount = 0
            val lines = StringBuilder()
            for (j in start until end) {
                when (val e = edits[j]) {
                    is Edit.Equal -> { lines.appendLine(" " + e.line); oldCount++; newCount++ }
                    is Edit.Delete -> { lines.appendLine("-" + e.line); oldCount++ }
                    is Edit.Insert -> { lines.appendLine("+" + e.line); newCount++ }
                }
            }

            sb.appendLine("@@ -$oldStart,$oldCount +$newStart,$newCount @@")
            sb.append(lines.toString().trimEnd()).appendLine()

            i++
            hunkStart = end
        }

        return sb.toString().trimEnd()
    }

    // ==================== Myers ====================

    private fun myersDiff(old: List<String>, new: List<String>): List<Edit> {
        val n = old.size
        val m = new.size
        val max = n + m
        val offset = max
        val v = IntArray(2 * max + 1)
        val trace = ArrayList<IntArray>()

        var dEnd = 0
        outer@ for (d in 0..max) {
            trace.add(v.copyOf())
            val kStart = -d
            val kEnd = d
            for (k in kStart..kEnd step 2) {
                var x = if (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) {
                    v[k + 1 + offset] // down (insert)
                } else {
                    v[k - 1 + offset] + 1 // right (delete)
                }
                var y = x - k
                while (x < n && y < m && old[x] == new[y]) {
                    x++
                    y++
                }
                v[k + offset] = x
                if (x >= n && y >= m) {
                    dEnd = d
                    break@outer
                }
            }
        }

        // Backtrack through the trace to recover the edit script.
        val edits = mutableListOf<Edit>()
        var x = n
        var y = m
        for (d in dEnd downTo 0) {
            val vv = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vv[k - 1 + offset] < vv[k + 1 + offset])) k + 1 else k - 1
            val prevX = vv[prevK + offset]
            val prevY = prevX - prevK

            // Diagonal (equal) moves first.
            while (x > prevX && y > prevY) {
                edits.add(Edit.Equal(old[x - 1]))
                x--
                y--
            }
            if (d > 0) {
                when {
                    x == prevX -> {
                        edits.add(Edit.Insert(new[y - 1]))
                        y--
                    }
                    else -> {
                        edits.add(Edit.Delete(old[x - 1]))
                        x--
                    }
                }
            }
        }

        return edits.reversed()
    }

    // ==================== Patience ====================

    private fun patienceDiff(old: List<String>, new: List<String>): List<Edit> {
        // Small inputs: fall back to Myers — patience overhead is not worth it.
        if (old.size * new.size <= 256 || old.size + new.size <= 32) {
            return myersDiff(old, new)
        }

        // Unique common lines: lines that appear exactly once in each input.
        val oldCount = old.groupingBy { it }.eachCount()
        val newCount = new.groupingBy { it }.eachCount()
        val uniqueOld = old.mapIndexedNotNull { i, line ->
            if (oldCount[line] == 1 && newCount[line] == 1) i to line else null
        }

        // Position of each unique line in the new file (its value is unique there).
        val newPositions = HashMap<String, Int>(new.size * 2)
        new.forEachIndexed { i, line -> if (newCount[line] == 1) newPositions[line] = i }

        // Anchors: unique lines present in both, ordered by old index, with
        // increasing new-file positions (longest increasing subsequence).
        val candidates = uniqueOld.mapNotNull { (oi, line) -> newPositions[line]?.let { oi to it } }
        if (candidates.size < 2) return myersDiff(old, new)

        val lis = longestIncreasingSubsequence(candidates.map { it.second })
        val anchors = lis.map { candidates[it] }

        val edits = mutableListOf<Edit>()
        var aPrev = 0
        var bPrev = 0
        for ((ai, bi) in anchors) {
            edits += myersDiff(old.subList(aPrev, ai), new.subList(bPrev, bi))
            edits.add(Edit.Equal(old[ai]))
            aPrev = ai + 1
            bPrev = bi + 1
        }
        edits += myersDiff(old.subList(aPrev, old.size), new.subList(bPrev, new.size))
        return edits
    }

    /**
     * Longest increasing subsequence over [values] — returns indices into [values]
     * (patience-sorting algorithm, O(n log n)).
     */
    private fun longestIncreasingSubsequence(values: List<Int>): List<Int> {
        if (values.isEmpty()) return emptyList()
        val tails = mutableListOf<Int>() // tails[k] = index into values of the tail of the LIS of length k+1
        val prev = IntArray(values.size) { -1 }

        for ((i, v) in values.withIndex()) {
            // Binary search: first tail with value >= v
            var lo = 0
            var hi = tails.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (values[tails[mid]] < v) lo = mid + 1 else hi = mid
            }
            if (lo == tails.size) {
                tails.add(i)
            } else {
                tails[lo] = i
            }
            prev[i] = if (lo > 0) tails[lo - 1] else -1
        }

        // Reconstruct the subsequence from the last tail.
        val result = mutableListOf<Int>()
        var idx = tails.last()
        while (idx >= 0) {
            result.add(idx)
            idx = prev[idx]
        }
        return result.reversed()
    }
}

