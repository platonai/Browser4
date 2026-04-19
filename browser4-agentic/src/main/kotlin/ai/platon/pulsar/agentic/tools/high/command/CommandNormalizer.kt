package ai.platon.pulsar.agentic.tools.high.command

import ai.platon.pulsar.agentic.tools.high.crawl.PageVisitRequest

/**
 * Interface for normalizing plain text commands into structured [PageVisitRequest] objects.
 *
 * Implementations may use LLM, pattern matching, or other strategies to convert
 * natural language commands into structured requests.
 */
fun interface CommandNormalizer {
    /**
     * Normalize a plain text command into a [PageVisitRequest].
     *
     * @param plainCommand The plain text command to normalize.
     * @return A [PageVisitRequest] if the command can be normalized, null otherwise.
     */
    suspend fun normalize(plainCommand: String): PageVisitRequest?
}
