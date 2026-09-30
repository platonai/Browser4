package ai.platon.pulsar.coding

/**
 * CDP (Chrome DevTools Protocol) pitfall awareness for Browser4 browser-driver
 * development.
 *
 * Encodes the three known CDP pitfalls documented in AGENTS.md as
 * keyword → advisory rules. `coding.trapCheck(path)` scans a file for the
 * keywords and surfaces the matching advisories, so an agent editing
 * browser-driver code is reminded of the traps before they bite.
 *
 * Pure data + regex matching — zero dependencies, no runtime cost unless used.
 */
object CdpTrapCheck {

    /** A CDP pitfall rule: keywords that trigger it + the advisory. */
    data class Trap(
        val id: String,
        val keywords: List<String>,
        val advisory: String,
    )

    /**
     * The three known CDP pitfalls from AGENTS.md, keyed by the code constructs
     * that involve them. Keywords are the DANGER constructs (the fix constructs
     * — setSelectionRange, randomDelayMillis — are deliberately NOT keywords, so
     * applying the documented fix does not keep tripping the check).
     */
    val TRAPS: List<Trap> = listOf(
        Trap(
            id = "crbug-444929150",
            keywords = listOf("mouseWheel", "Input.dispatchMouseEvent", "dispatchMouseEvent"),
            advisory = "CDP pitfall (crbug.com/444929150): Input.dispatchMouseEvent type " +
                "'mouseWheel' has a race condition in headless Chrome. Fix: dispatch to a " +
                "{passive: false} wheel listener instead of relying on the CDP event alone.",
        ),
        Trap(
            id = "cursor-positioning",
            keywords = listOf("DOM.focus"),
            advisory = "CDP pitfall (cursor positioning): DOM.focus() + Input.dispatchMouseEvent " +
                "(click) may leave the cursor at position 0. Fix: call setSelectionRange(99999, 99999) " +
                "after focus+click to move the cursor to the end.",
        ),
        Trap(
            id = "insertText-racing",
            keywords = listOf("insertText", "Input.insertText"),
            advisory = "CDP pitfall (Input.insertText racing): 0ms delay between characters can drop " +
                "input events. Fix: use the same inter-character delay as type() via " +
                "randomDelayMillis(\"type\") (90-240ms). Hardcoded small delays are insufficient in " +
                "headless Chrome.",
        ),
    )

    /**
     * Scan [content] for CDP trap keywords and return the matching advisories.
     * Returns an empty list when the file is unrelated to browser-driver code.
     */
    fun check(content: String): List<Trap> {
        return TRAPS.filter { trap ->
            trap.keywords.any { content.contains(it) }
        }
    }

    /** Format the advisories for the agent. */
    fun format(content: String): String {
        val hits = check(content)
        if (hits.isEmpty()) {
            return "No CDP pitfalls detected in this file (browser-driver code only)."
        }
        return buildString {
            appendLine("⚠ CDP pitfalls detected (${hits.size}):")
            hits.forEach { t ->
                appendLine("  [${t.id}] ${t.advisory}")
            }
        }.trimEnd()
    }
}
