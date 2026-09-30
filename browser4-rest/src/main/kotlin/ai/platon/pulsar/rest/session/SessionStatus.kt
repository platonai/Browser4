package ai.platon.pulsar.rest.session

/**
 * Lifecycle status of a [ManagedSession].
 *
 * Replaces the previous free-form `String` status so lifecycle decisions are
 * compiler-checked instead of string-compared.
 */
enum class SessionStatus {
    /** Session is healthy and usable. */
    ACTIVE,

    /** Session is paused. */
    PAUSED,

    /** Session has been closed, or its browser is gone. */
    STOPPED,

    /** Attached session whose connection (extension WebSocket / CDP) dropped. */
    DISCONNECTED,

    /** Session failed its last health check. */
    UNHEALTHY;

    /** Lowercase wire representation (e.g. "active") — kept for MCP/CLI compatibility. */
    val wire: String
        get() = name.lowercase()

    companion object {
        /**
         * Parse a wire status string (case-insensitive).
         *
         * @return The matching status, or [STOPPED] for null/blank/unknown
         *         values so callers never see a fatal parse error.
         */
        fun fromWire(value: String?): SessionStatus {
            return when (value?.trim()?.lowercase()) {
                "active" -> ACTIVE
                "paused" -> PAUSED
                "stopped" -> STOPPED
                "disconnected" -> DISCONNECTED
                "unhealthy" -> UNHEALTHY
                else -> STOPPED
            }
        }
    }
}
