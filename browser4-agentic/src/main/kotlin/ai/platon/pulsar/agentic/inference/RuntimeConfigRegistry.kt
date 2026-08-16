package ai.platon.pulsar.agentic.inference

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of **runtime configuration overrides** backing the
 * unified REST config interface (`/api/config/{key}`).
 *
 * Design:
 * - Only whitelisted keys ([KEY_DEFS]) can be overridden — no arbitrary
 *   property injection.
 * - Each key carries a validator/normalizer so the REST layer can reject
 *   invalid values with a 400 before anything is stored.
 * - Overrides live in memory only: a restart falls back to the server's
 *   configuration files. This is deliberate — the interface exists to let
 *   the operator allow a halted task to continue without a restart, not to
 *   replace configuration management.
 * - Consumers read the override at use time (e.g.
 *   [RequestTokenLimiter.effectiveMaxTokens]), so already-created
 *   components pick up changes immediately.
 */
object RuntimeConfigRegistry {

    /** Definition of one overridable configuration key. */
    data class KeyDef(
        /** The configuration key, e.g. `agent.llm.maxRequestTokens`. */
        val key: String,
        /** Human-readable description shown by the REST interface. */
        val description: String,
        /** The built-in default when neither override nor config file set a value. */
        val defaultValue: String,
        /**
         * Validate and normalize a raw value. Returns the normalized string,
         * or null if the value is invalid for this key.
         */
        val validate: (String) -> String?,
    )

    /** All configuration keys that support runtime overrides. */
    val KEY_DEFS: List<KeyDef> = listOf(
        KeyDef(
            key = RequestTokenLimiter.CONFIG_KEY,
            description = "Per-request LLM token limit (estimated). A task is halted when a single " +
                "LLM request exceeds it; raise to allow the task to continue",
            defaultValue = RequestTokenLimiter.DEFAULT_MAX_REQUEST_TOKENS.toString(),
            validate = ::validateNonNegativeInt,
        ),
        KeyDef(
            key = AgentTokenBudget.CONFIG_KEY,
            description = "Cumulative token budget per agent run (input + output). Exceeding it " +
                "aborts the agent run",
            defaultValue = AgentTokenBudget.DEFAULT_MAX_TOTAL_TOKENS.toString(),
            validate = ::validateNonNegativeLong,
        ),
    )

    private val overrides = ConcurrentHashMap<String, String>()

    /** All supported keys. */
    fun supportedKeys(): List<String> = KEY_DEFS.map { it.key }

    /** True when [key] supports runtime overrides. */
    fun isSupported(key: String): Boolean = KEY_DEFS.any { it.key == key }

    /** The [KeyDef] for [key], or null when unsupported. */
    fun keyDef(key: String): KeyDef? = KEY_DEFS.firstOrNull { it.key == key }

    /**
     * Validate and store an override. Returns the normalized value, or
     * throws [IllegalArgumentException] when the key is unknown or the
     * value is invalid.
     */
    fun setOverride(key: String, value: String): String {
        val def = keyDef(key) ?: throw IllegalArgumentException("Unknown config key '$key'")
        val normalized = def.validate(value.trim())
            ?: throw IllegalArgumentException(
                "Invalid value '$value' for '$key': expected a non-negative integer, 0, or 'unlimited'"
            )
        overrides[key] = normalized
        return normalized
    }

    /** Clear the override for [key] (falls back to configuration values). */
    fun clearOverride(key: String) {
        overrides.remove(key)
    }

    /** The raw override string for [key], or null when none is set. */
    fun getOverride(key: String): String? = overrides[key]

    /** The override for [key] as [Int], or null when unset/unparseable. */
    fun getOverrideAsInt(key: String): Int? = getOverride(key)?.toIntOrNull()

    /** The override for [key] as [Long], or null when unset/unparseable. */
    fun getOverrideAsLong(key: String): Long? = getOverride(key)?.toLongOrNull()

    private fun validateNonNegativeInt(raw: String): String? {
        val v = normalize(raw) ?: return null
        return v.toIntOrNull()?.takeIf { it >= 0 }?.toString()
    }

    private fun validateNonNegativeLong(raw: String): String? {
        val v = normalize(raw) ?: return null
        return v.toLongOrNull()?.takeIf { it >= 0 }?.toString()
    }

    private fun normalize(raw: String): String? =
        if (raw.equals("unlimited", ignoreCase = true)) "0" else raw.takeIf { it.isNotEmpty() }
}
