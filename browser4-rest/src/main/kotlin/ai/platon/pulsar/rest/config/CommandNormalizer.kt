package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.tools.high.crawl.PageVisitRequest

/**
 * Interface for normalizing plain text commands into structured [ai.platon.pulsar.agentic.tools.high.crawl.PageVisitRequest] objects.
 *
 * Implementations may use LLM, pattern matching, or other strategies to convert
 * natural language commands into structured requests.
 */
fun interface CommandNormalizer {
    /**
     * Normalize a plain text command into a [ai.platon.pulsar.agentic.tools.high.crawl.PageVisitRequest].
     *
     * @param plainCommand The plain text command to normalize.
     * @return A [ai.platon.pulsar.agentic.tools.high.crawl.PageVisitRequest] if the command can be normalized, null otherwise.
     */
    suspend fun normalize(plainCommand: String): PageVisitRequest?
}
