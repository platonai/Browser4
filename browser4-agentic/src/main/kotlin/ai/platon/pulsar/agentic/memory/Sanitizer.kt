package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper

/**
 * Write-path redaction for the agent memory system.
 *
 * Every text field that reaches [MemoryEvent] or the [TaskScratchpad] passes
 * through here: sensitive parameter names (password/token/secret/...) are
 * masked, whitespace is compacted, and lengths are capped. This is the
 * engine-side counterpart of the `CliProcessManager` env whitelist (defense
 * in depth): secrets never enter the memory log in the first place.
 */
object Sanitizer {

    private val mapper = pulsarObjectMapper()

    private val SENSITIVE_KEY = Regex(
        "(?i)(password|passwd|pwd|token|secret|authorization|cookie|credential|api[_-]?key|session[_-]?id)"
    )

    private val URL_RE = Regex("https?://[^\\s\"'<>]+")

    /** Mask the value of a sensitive key; otherwise return it unchanged. */
    fun sanitizeKeyValue(key: String, value: String): String =
        if (SENSITIVE_KEY.containsMatchIn(key)) "****" else value

    /**
     * Compact + truncate free text to [maxLen] chars (single line).
     */
    fun brief(text: String?, maxLen: Int = 200): String {
        if (text.isNullOrBlank()) return ""
        return Strings.compactInline(text, maxLen)
    }

    /**
     * Redact a tool-call arguments JSON: recursively mask values whose keys
     * match sensitive names, then compact + truncate to [maxLen] chars.
     * Falls back to a regex mask on the raw text when the JSON cannot be parsed.
     */
    fun sanitizeArgsJson(argsJson: String, maxLen: Int = 300): String {
        if (argsJson.isBlank()) return ""
        val sanitized = try {
            val node = mapper.readTree(argsJson)
            maskNode(node)
            mapper.writeValueAsString(node)
        } catch (e: Exception) {
            SENSITIVE_KEY.replace(argsJson) { "****" }
        }
        return Strings.compactInline(sanitized, maxLen)
    }

    /** First http(s):// URL in the text, or null. */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return URL_RE.find(text)?.value?.trimEnd('.', ',', ')', ']')
    }

    private fun maskNode(node: com.fasterxml.jackson.databind.JsonNode) {
        when {
            node.isObject -> node.fields().forEach { (key, value) ->
                if (value.isValueNode) {
                    if (SENSITIVE_KEY.containsMatchIn(key)) (node as com.fasterxml.jackson.databind.node.ObjectNode).put(key, "****")
                } else {
                    maskNode(value)
                }
            }
            node.isArray -> node.forEach { maskNode(it) }
        }
    }
}
