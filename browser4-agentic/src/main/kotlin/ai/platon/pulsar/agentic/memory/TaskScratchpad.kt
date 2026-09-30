package ai.platon.pulsar.agentic.memory

/**
 * Working-memory write board (task scratchpad).
 *
 * The model writes stable cross-step conclusions here via `memory.note`; the
 * engine re-injects the rendered board as the LAST user message of every
 * round, so it survives message compression (the compressor never touches the
 * tail) and KV-cache prefixes stay intact (replace-tail, never mid-insert).
 *
 * Bounded: entries are LRU-evicted once the total exceeds [maxChars].
 */
class TaskScratchpad(private val maxChars: Int = MemoryConfig.scratchpadMaxChars) {

    private val entries = LinkedHashMap<String, String>()

    companion object {
        private val KEY_PATTERN = Regex("[a-zA-Z0-9_.-]{1,32}")
        private const val VALUE_MAX_CHARS = 200
    }

    /** Write one note; returns a confirmation string (never throws on content). */
    @Synchronized
    fun note(key: String, value: String): String {
        require(KEY_PATTERN.matches(key)) {
            "Invalid note key '$key' — allowed: 1-32 chars of [a-zA-Z0-9_.-]"
        }
        val safe = Sanitizer.brief(value, VALUE_MAX_CHARS)
        entries.remove(key)
        entries[key] = safe
        evict()
        return "Note saved: $key"
    }

    @Synchronized
    fun get(key: String): String? = entries[key]

    @Synchronized
    fun size(): Int = entries.size

    /** Rendered board (markdown), or null when empty. */
    @Synchronized
    fun render(): String? {
        if (entries.isEmpty()) return null
        val body = entries.entries.joinToString("\n") { (k, v) -> "- $k: $v" }
        return "## Task Scratchpad\n$body"
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun evict() {
        while (totalChars() > maxChars && entries.size > 1) {
            entries.remove(entries.keys.first())
        }
    }

    private fun totalChars(): Int =
        entries.entries.sumOf { (k, v) -> k.length + v.length + 4 }
}
