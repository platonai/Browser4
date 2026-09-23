package ai.platon.pulsar.chrome

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper

/**
 * Bounded, thread-safe buffer of browser console messages captured over CDP.
 *
 * ## Why a buffer instead of a page-side wrapper
 *
 * Console messages used to be collected by replacing `console.log`/`warn`/`error`/`info`/`debug`
 * with driver-owned wrappers and buffering on `window.__b4_console`. That mutates the page: a
 * fingerprinting script reads the wrapper's source through `console.log.toString()`,
 * `Function.prototype.toString.call(console.log)` (which bypasses an own `toString`) or its
 * `name`/`prototype` shape (`console.log` is not a constructor while a wrapper is). Chrome already
 * reports console API calls to the DevTools client attached to the target, so the driver buffers
 * them in the JVM instead and leaves the page untouched.
 *
 * The level semantics match the historical page-side filter: `error=0, warn=1, info=2, log=2,
 * debug=3`, and `Console.messageAdded`'s `warning` is normalized to `warn` so callers keep seeing
 * one vocabulary.
 */
internal class ConsoleMessageBuffer(private val limit: Int = DEFAULT_LIMIT) {

    companion object {
        /** Console messages kept per driver; the oldest are dropped first. */
        const val DEFAULT_LIMIT = 500

        /** A buffered console message, mirroring the shape the page-side builder produced. */
        internal data class Message(val level: String, val text: String, val timestamp: Long)

        /** Level priorities, matching the historical page-side filter. */
        private val LEVEL_PRIORITIES = mapOf(
            "error" to 0,
            "warn" to 1,
            "warning" to 1,
            "info" to 2,
            "log" to 2,
            "debug" to 3,
        )

        /**
         * Normalize a `Console.messageAdded` level: `warning` becomes `warn` (the vocabulary the
         * page-side builder and the CLI used), a missing level becomes `log`.
         */
        fun normalizeLevel(level: String?): String = when (val normalized = level?.trim()?.lowercase()) {
            null, "" -> "log"
            "warning" -> "warn"
            else -> normalized
        }

        /** The priority of [level]; unknown levels are treated like `info`, as the page-side filter did. */
        fun levelPriority(level: String): Int = LEVEL_PRIORITIES[level.trim().lowercase()] ?: 2
    }

    private val messages = ArrayDeque<Message>()

    /**
     * Append a message, dropping the oldest when the buffer is full.
     *
     * @return false when the message carries no text and was therefore not buffered
     */
    @Synchronized
    fun add(level: String?, text: String?, timestamp: Long = System.currentTimeMillis()): Boolean {
        if (text.isNullOrEmpty()) {
            return false
        }

        if (messages.size >= limit) {
            messages.removeFirst()
        }
        messages.addLast(Message(normalizeLevel(level), text, timestamp))
        return true
    }

    /** Drop every buffered message. */
    @Synchronized
    fun clear() {
        messages.clear()
    }

    /** The number of buffered messages (all levels). */
    @Synchronized
    fun size(): Int = messages.size

    /** Messages at [minLevel] or above, oldest first. */
    @Synchronized
    fun snapshot(minLevel: String): List<Message> {
        val minimum = levelPriority(minLevel)
        return messages.filter { levelPriority(it.level) <= minimum }
    }

    /**
     * The messages at [minLevel] or above as the JSON array the CLI consumed before this change:
     * `[{"level":"log","text":"...","timestamp":1699999999999}]`.
     */
    @Synchronized
    fun toJson(minLevel: String): String {
        val payload = snapshot(minLevel).map {
            mapOf("level" to it.level, "text" to it.text, "timestamp" to it.timestamp)
        }
        return pulsarObjectMapper().writeValueAsString(payload)
    }
}
