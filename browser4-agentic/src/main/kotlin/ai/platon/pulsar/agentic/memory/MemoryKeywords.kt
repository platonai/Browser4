package ai.platon.pulsar.agentic.memory

/**
 * Shared keyword extraction for memory search/recall.
 *
 * Both the SQLite FTS path and the naive log-scan fallback must tokenize
 * identically, and both must drop stop words: a recall query like
 * "extract the amazon price" must not become an AND query over
 * `extract/the/amazon/price` (the text has no "the" → false negative).
 * FTS queries use OR semantics — relevance ordering comes from FTS `rank`,
 * recall-friendly matching from OR.
 */
object MemoryKeywords {

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with",
        "at", "by", "from", "is", "are", "was", "were", "be", "been", "it",
        "this", "that", "these", "those", "please", "me", "my", "your", "you",
        "do", "does", "did", "can", "could", "would", "should", "will", "not",
        "no", "yes", "as", "into", "up", "down", "out", "about", "then", "than",
        "also", "just", "very", "use", "using", "get", "got", "go", "going",
    )

    /** Lowercased, de-duplicated, stop-word-free keywords (≥2 chars), capped at [max]. */
    fun extract(text: String, max: Int = 16): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .distinct()
            .take(max)

    /** FTS5 MATCH expression: `"kw1" OR "kw2" ...` (empty when nothing usable). */
    fun ftsMatchExpression(query: String): String =
        extract(query).joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" }
}
