package ai.platon.pulsar.coding

/**
 * Pure helpers that render acceptance criteria for reports.
 */
object AcceptanceSummary {

    /**
     * Renders [items] as a single-line acceptance summary.
     *
     * Example: `listOf("a", "b")` renders as `"Acceptance: [a, b]"`, an empty list
     * renders as `"Acceptance: []"`.
     */
    fun summarize(items: List<String>): String = "Acceptance: [" + items.joinToString(", ") + "]"
}
