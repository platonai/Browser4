package ai.platon.pulsar.agentic.common

/**
 * One rule set for "this value is a credential, do not write it down".
 *
 * Two very different sinks need it, and they used to have separate (or no) rules:
 *
 * - the **event stream** (`AgentTaskEventSanitizer`, browser4-agent-tools) is rendered into
 *   terminals, CI logs and `--ndjson` pipes, so it previews and redacts before a step leaves
 *   the process;
 * - the **run trace** (`CliLoopTracer`, this module) is the full audit trail on the backend
 *   disk: complete tool arguments, complete tool output, page URLs.
 *
 * The second one was written verbatim, which made the trace the one place a password typed
 * into a form or a token passed to a shell command was stored in plaintext — and the one place
 * a remote reviewer cannot reach. Keeping the rules here (the module both sinks can import)
 * means a new sensitive key is added once (issue #652, gap 5).
 *
 * Everything is best effort by design: this runs on the hot path of a run and must never throw,
 * never change the shape of the data it passes, and never block. A value it cannot classify
 * stays as it is — the alternative (dropping payloads it cannot parse) would silently destroy
 * the audit trail this exists to protect.
 */
object SecretRedactor {

    /** Placeholder substituted for a redacted value. */
    const val REDACTED = "***"

    /** Tools whose values are what the user typed into the page. */
    private val TEXT_INPUT_TOOLS = setOf("fill", "type", "insertText", "insert_text", "press")

    /** Argument keys that hold a free-text value for a text-input tool. */
    private val TEXT_INPUT_KEYS = setOf("text", "value")

    /**
     * Argument keys that name a credential, compared lower-cased with `_`/`-` stripped, so
     * `apiKey`, `api_key` and `api-key` all match.
     */
    val SENSITIVE_KEYS: Set<String> = setOf(
        "password", "passwd", "pwd", "secret", "token",
        "apikey", "accesstoken", "refreshtoken", "authorization",
        "cookie", "setcookie", "creditcard", "cardnumber", "cvv", "securitycode",
    )

    /**
     * Selector hints for a credential field. A text-input tool targeting one of these has its
     * typed text redacted instead of stored.
     */
    val SENSITIVE_SELECTOR_HINTS: List<String> = listOf(
        "password", "passwd", "pwd", "secret", "token", "api-key", "api_key",
        "apikey", "cvv", "credit-card", "cardnumber",
    )

    /**
     * Query-parameter names that carry a credential in a URL (`?access_token=…`, `?sig=…`).
     * Trimmed to the same vocabulary as [SENSITIVE_KEYS] plus the signature forms a CDN or a
     * signed URL uses.
     */
    private val SENSITIVE_URL_PARAMS = setOf(
        "password", "passwd", "pwd", "secret", "token", "key", "apikey", "api_key", "api-key",
        "access_token", "access-token", "accesstoken", "id_token", "refresh_token", "sig",
        "signature", "authorization", "auth", "session", "sessionid", "cookie", "code",
    )

    /** A `"key": "value"` pair in JSON-shaped text. */
    private val INLINE_SECRET = Regex(
        """(?i)("(?:[^"\\]|\\.)*?(?:password|passwd|pwd|secret|token|api_?key|authorization|cookie)(?:[^"\\]|\\.)*?"\s*:\s*)(?:"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[^,}\]]+)"""
    )

    /**
     * A CLI-style flag: a `coding.shell` command such as `cli --password hunter2 --token abc` is
     * as sensitive as a JSON body, and it never matches [INLINE_SECRET].
     */
    private val INLINE_FLAG_SECRET = Regex(
        """(?i)(--?(?:password|passwd|pwd|secret|token|api[-_]?key|authorization|cookie)[=\s]+)("[^"]*"|'[^']*'|\S+)"""
    )

    /** A query parameter inside a URL. */
    private val URL_PARAM = Regex("""([?&])([^=&#\s]+)=([^&#\s]*)""")

    /** The `selector` / `ref` / `element` of a text-input tool's arguments. */
    private val SELECTOR_FIELD = Regex(""""(?:selector|ref|element)"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /** True when [key] names a credential (case/`_`/`-` insensitive). */
    fun isSensitiveKey(key: String): Boolean =
        // A nested key may arrive dotted (`credentials.token`): the last segment is the part
        // that names the value.
        normalizeKey(key.substringAfterLast('.')) in SENSITIVE_KEYS

    /** True when the value of a text-input tool must be hidden because its selector names a
     * credential field. */
    fun isSensitiveTextInput(tool: String?, key: String, selector: String?): Boolean {
        if (tool !in TEXT_INPUT_TOOLS) return false
        if (normalizeKey(key) !in TEXT_INPUT_KEYS) return false
        return selectorNamesCredential(selector)
    }

    /** True when [selector] points at a credential field (`input[type=password]`, `#api_token`). */
    fun selectorNamesCredential(selector: String?): Boolean {
        val hint = selector?.lowercase() ?: return false
        return SENSITIVE_SELECTOR_HINTS.any { hint.contains(it) }
    }

    /**
     * Redact credential-bearing free text: JSON-shaped pairs and CLI-style flags.
     *
     * Used for anything that is not parsed (a shell command, a message, a log line) and as the
     * fallback when a payload is not valid JSON.
     */
    fun redactText(raw: String): String {
        if (raw.isEmpty()) return raw
        val jsonish = INLINE_SECRET.replace(raw) { match -> "${match.groupValues[1]}\"$REDACTED\"" }
        return INLINE_FLAG_SECRET.replace(jsonish) { match -> "${match.groupValues[1]}$REDACTED" }
    }

    /**
     * Redact one tool invocation for the trace: its arguments and its output.
     *
     * Applies [redactText] and, for a text-input tool whose selector names a credential field,
     * the typed value (which cannot be classified from its key alone — the key is `text`).
     *
     * @param tool the tool name (`fill`, `coding.shell`, …)
     * @param raw the arguments JSON or the result text, verbatim
     */
    fun redactForTrace(tool: String?, raw: String?): String? {
        if (raw.isNullOrEmpty()) return raw
        val redacted = redactText(raw)
        if (tool !in TEXT_INPUT_TOOLS) return redacted
        val selector = SELECTOR_FIELD.find(redacted)?.groupValues?.getOrNull(1)
        if (!selectorNamesCredential(selector)) return redacted
        // `"text": "…"` / `"value": "…"` — the value a credential field received.
        return TEXT_INPUT_KEYS.fold(redacted) { text, key ->
            Regex("""("$key"\s*:\s*)"(?:[^"\\]|\\.)*"""").replace(text) { match ->
                "${match.groupValues[1]}\"$REDACTED\""
            }
        }
    }

    /**
     * Redact the credential-bearing query parameters of [url], keeping the URL itself usable as
     * an index entry (`?token=***` still says which request it was).
     *
     * A URL is not JSON: the pair regex only looks at the query string, and a fragment is left
     * alone (it is not sent to the server, but it is also not a credential channel the run
     * controls).
     */
    fun redactUrl(url: String?): String? {
        if (url.isNullOrBlank()) return url
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart)
        val head = url.substring(0, queryStart)
        val query = if (fragmentStart >= 0) {
            url.substring(queryStart, fragmentStart)
        } else {
            url.substring(queryStart)
        }
        val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
        val redactedQuery = URL_PARAM.replace(query) { match ->
            val name = match.groupValues[2]
            if (normalizeKey(name) in SENSITIVE_URL_PARAMS || name.lowercase() in SENSITIVE_URL_PARAMS) {
                "${match.groupValues[1]}$name=$REDACTED"
            } else {
                match.value
            }
        }
        return head + redactedQuery + fragment
    }

    private fun normalizeKey(key: String): String = key.lowercase().replace("_", "").replace("-", "")
}
