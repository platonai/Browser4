package ai.platon.pulsar.coding

/**
 * Character-level text helpers that keep UTF-16 surrogate pairs intact.
 *
 * Every truncation in the coding/agent toolchain (shell output, pruned tool
 * results, execution digests) used to be a plain `take(n)` / `takeLast(n)`.
 * Those split an emoji surrogate pair whenever the cut lands on it, leaving a
 * lone surrogate in the text. A lone surrogate is not encodable as UTF-8, so
 * from that point on:
 *
 * - `Files.writeString` of the prompt dump threw `UnmappableCharacterException`
 *   ("Input length = 1") for every later round — the poisoned message stays in
 *   the conversation, so exactly the long runs worth debugging lost their trace;
 * - trace writers and model providers received an invalid UTF-16 string.
 *
 * [take] / [takeLast] cut on code-point boundaries instead, and [sanitize]
 * repairs a string that already carries a lone surrogate (e.g. from truncated
 * model output).
 */
object SafeText {

    /** Character used in place of an unpaired surrogate by [sanitize]. */
    const val REPLACEMENT = '\uFFFD'

    /**
     * The first [maxChars] characters of [text] without splitting a surrogate
     * pair (the result may therefore be one character shorter).
     */
    fun take(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        var end = maxChars
        if (end < text.length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end--
        }
        return text.substring(0, end)
    }

    /**
     * The last [maxChars] characters of [text] without splitting a surrogate
     * pair (the result may therefore be one character shorter).
     */
    fun takeLast(text: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        if (text.length <= maxChars) return text
        var start = text.length - maxChars
        if (start > 0 &&
            Character.isLowSurrogate(text[start]) &&
            Character.isHighSurrogate(text[start - 1])
        ) {
            start++
        }
        return text.substring(start)
    }

    /**
     * Replace every unpaired surrogate with [REPLACEMENT].
     *
     * Idempotent, and returns [text] unchanged when it is already well formed
     * (the common case, so the scan is the only cost).
     */
    fun sanitize(text: String): String {
        val firstBad = text.indexOfFirstUnpairedSurrogate()
        if (firstBad < 0) return text
        val sb = StringBuilder(text.length)
        sb.append(text, 0, firstBad)
        var i = firstBad
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                sb.append(c).append(text[i + 1])
                i += 2
            } else if (Character.isSurrogate(c)) {
                sb.append(REPLACEMENT)
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Index of the first unpaired surrogate, or -1 when [text] is well formed. */
    private fun String.indexOfFirstUnpairedSurrogate(): Int {
        var i = 0
        while (i < length) {
            val c = this[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < length && Character.isLowSurrogate(this[i + 1])) {
                    i += 2
                    continue
                }
                return i
            }
            if (Character.isLowSurrogate(c)) return i
            i++
        }
        return -1
    }
}
