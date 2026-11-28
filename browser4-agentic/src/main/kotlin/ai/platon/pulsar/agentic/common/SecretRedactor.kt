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

    /**
     * Substrings that make a JSON-shaped key a credential.
     *
     * The list mirrors the old `(?i)password|passwd|pwd|secret|token|api_?key|authorization|cookie`
     * alternation exactly (`api_?key` is `apikey` or `api_key`, not `api-key`), so the scan below
     * redacts the same keys the regex did.
     */
    private val INLINE_SENSITIVE_MARKERS = listOf(
        "password", "passwd", "pwd", "secret", "token",
        "apikey", "api_key", "authorization", "cookie",
    )

    /** The `selector` / `ref` / `element` key of a text-input tool's arguments. */
    private val SELECTOR_FIELD_NAMES = setOf("selector", "ref", "element")

    /**
     * A CLI-style flag: a `coding.shell` command such as `cli --password hunter2 --token abc` is
     * as sensitive as a JSON body, and it never matches the JSON-shaped scanner.
     */
    private val INLINE_FLAG_SECRET = Regex(
        """(?i)(--?(?:password|passwd|pwd|secret|token|api[-_]?key|authorization|cookie)[=\s]+)("[^"]*"|'[^']*'|\S+)"""
    )

    /** A query parameter inside a URL. */
    private val URL_PARAM = Regex("""([?&])([^=&#\s]+)=([^&#\s]*)""")

    /**
     * A credential named in prose or YAML: `password: hunter2`, `api_key=...`, `密码：…`, `密码是…`.
     * The value is a quoted literal or a bare token of the literal alphabet.
     */
    private val PROSE_PAIR = Regex(
        """((?i:\b(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|cookie)\b""" +
            """|密码|口令|密钥)\s*[:=：是为]\s*)("[^"\n]*"|'[^'\n]*'|[A-Za-z0-9._~+/=-]{6,256})""",
    )

    /** A `Bearer` credential in a header line or a prose mention. */
    private val BEARER_TOKEN = Regex("""(?i)\b(Bearer)(\s+)[A-Za-z0-9._~+/=-]{8,}=*""")

    /** An http(s) URL inside free text; its credential-bearing query parameters are redacted. */
    private val URL_IN_TEXT = Regex("""https?://[^\s<>"'`\\]+""")

    /** Key formats that carry their own signature: JWT, OpenAI, GitHub, AWS, Slack. */
    private val PROVIDER_KEY = Regex(
        """\b(?:eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""" +
            """|sk-[A-Za-z0-9_-]{16,}""" +
            """|(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{16,}""" +
            """|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,})""",
    )

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
     * Redact credential-bearing free text: JSON-shaped pairs, embedded (escaped) pairs, and
     * CLI-style flags.
     *
     * Used for anything that is not parsed (a shell command, a message, a log line, a trace line
     * read back from disk) and as the fallback when a payload is not valid JSON.
     *
     * Both pair shapes are handled by a **linear scan**, never a regex: see `redactPlainPairs`
     * for the stack overflow the previous regex caused on a long quoted string.
     */
    fun redactText(raw: String): String {
        if (raw.isEmpty()) return raw
        // Escaped pairs first: a plain-quote rule would cut through the middle of `\"password\"`.
        val escaped = redactEscapedPairs(raw)
        val jsonish = redactPlainPairs(escaped) { key ->
            INLINE_SENSITIVE_MARKERS.any { key.contains(it, ignoreCase = true) }
        }
        return INLINE_FLAG_SECRET.replace(jsonish) { match -> "${match.groupValues[1]}$REDACTED" }
    }

    /**
     * Redact credentials in prose: a prompt, a task result, a generated SKILL draft.
     *
     * [redactText] targets tool payloads — JSON-shaped pairs and CLI flags. The same credential in
     * prose is `password: hunter2`, `密码：hunter2`, `token=...`, a `Bearer` header, a signed URL or
     * a bare provider key, none of which the pair scanners recognise.
     *
     * A quoted value is a literal and always goes. A bare value is only treated as a secret when it
     * looks like one — at least six characters of the literal alphabet and a digit — so that code
     * such as `token = settings.authToken` stays readable.
     */
    fun redactProse(raw: String): String {
        if (raw.isEmpty()) return raw
        var text = redactText(raw)
        text = PROSE_PAIR.replace(text) { match ->
            val value = match.groupValues[2]
            val quoted = value.startsWith('"') || value.startsWith('\'')
            if (quoted || value.any { it in '0'..'9' }) "${match.groupValues[1]}$REDACTED" else match.value
        }
        text = BEARER_TOKEN.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
        text = URL_IN_TEXT.replace(text) { redactUrl(it.value) ?: it.value }
        return PROVIDER_KEY.replace(text) { REDACTED }
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
        val selector = findPlainStringField(redacted, SELECTOR_FIELD_NAMES)
        if (!selectorNamesCredential(selector)) return redacted
        // `"text": "…"` / `"value": "…"` — the value a credential field received.
        return redactPlainPairs(redacted) { key -> key in TEXT_INPUT_KEYS }
    }

    /**
     * Replace the value of every JSON-shaped `"key": value` pair whose key `acceptsKey` accepts.
     *
     * ## Why this is a scan and not a regex
     *
     * The previous rule was
     * `("(?:[^"\\]|\\.)*?(?:password|…)(?:[^"\\]|\\.)*?"\s*:\s*)(...)`. Java implements
     * `(?:…)*` recursively, one level per repetition, so **every** quoted string in the text cost
     * stack depth proportional to its length while the engine tried to read it as a key. A single
     * 2 KB quoted value was enough to throw `StackOverflowError` on the default 1 MB thread
     * stack. The prompt dump redacts the system prompt — tens of kilobytes, always containing
     * quoted strings — before every model request, so the first request of a run died. This scan
     * is linear and uses no recursion.
     *
     * Only the shapes the old rule accepted are rewritten: a double-quoted key, optional
     * whitespace, `:`, optional whitespace, then a double-quoted, single-quoted or bare value. A
     * value that never closes is left untouched.
     */
    private fun redactPlainPairs(text: String, acceptsKey: (String) -> Boolean): String {
        val length = text.length
        var index = 0
        var copied = 0
        var out: StringBuilder? = null
        while (index < length) {
            if (text[index] != '"' || isEscaped(text, index)) {
                index++
                continue
            }
            val keyEnd = findQuotedEnd(text, index, '"')
            if (keyEnd < 0) {
                index++
                continue
            }
            val colon = skipRegexSpaces(text, keyEnd + 1)
            if (colon >= length || text[colon] != ':') {
                index = keyEnd + 1
                continue
            }
            if (!acceptsKey(text.substring(index + 1, keyEnd))) {
                index = keyEnd + 1
                continue
            }
            val valueStart = skipRegexSpaces(text, colon + 1)
            val valueEnd = findValueEnd(text, valueStart)
            if (valueEnd <= valueStart) {
                index = keyEnd + 1
                continue
            }
            val builder = out ?: StringBuilder(length).also { out = it }
            builder.append(text, copied, valueStart)
            builder.append('"').append(REDACTED).append('"')
            copied = valueEnd
            index = valueEnd
        }
        val builder = out ?: return text
        builder.append(text, copied, length)
        return builder.toString()
    }

    /**
     * The escaped form of [redactPlainPairs]: `\"key\":\"value\"` inside an embedded JSON string.
     *
     * A trace line is one JSON object whose `arguments`/`resultText` are strings, so a tool's own
     * `{"password":"hunter2"}` arrives as `\"password\":\"hunter2\"`. A scan for the same reason
     * as the plain rule.
     */
    private fun redactEscapedPairs(text: String): String {
        val length = text.length
        var index = 0
        var copied = 0
        var out: StringBuilder? = null
        while (index < length) {
            if (!isEscapedQuoteDelimiter(text, index)) {
                index++
                continue
            }
            val keyEnd = findEscapedKeyEnd(text, index + 2)
            if (keyEnd < 0) break
            val key = text.substring(index + 2, keyEnd)
            if (INLINE_SENSITIVE_MARKERS.none { key.contains(it, ignoreCase = true) }) {
                index = keyEnd + 2
                continue
            }
            val colon = skipRegexSpaces(text, keyEnd + 2)
            if (colon >= length || text[colon] != ':') {
                index = keyEnd + 2
                continue
            }
            val valueStart = skipRegexSpaces(text, colon + 1)
            if (!isEscapedQuoteDelimiter(text, valueStart)) {
                index = keyEnd + 2
                continue
            }
            val valueEnd = findEscapedValueEnd(text, valueStart + 2)
            if (valueEnd < 0) break
            val builder = out ?: StringBuilder(length).also { out = it }
            builder.append(text, copied, valueStart)
            builder.append('\\').append('"').append(REDACTED).append('\\').append('"')
            copied = valueEnd + 2
            index = copied
        }
        val builder = out ?: return text
        builder.append(text, copied, length)
        return builder.toString()
    }

    /** The first `\"…\"` whose next non-space character is `:` — an escaped key close. */
    private fun findEscapedKeyEnd(text: String, from: Int): Int {
        var i = from
        while (i + 1 < text.length) {
            if (isEscapedQuoteDelimiter(text, i)) {
                val next = skipRegexSpaces(text, i + 2)
                if (next < text.length && text[next] == ':') return i
            }
            i++
        }
        return -1
    }

    /** The first `\"…\"` followed by `,`, `}` or the end of the text — an escaped value close. */
    private fun findEscapedValueEnd(text: String, from: Int): Int {
        var i = from
        while (i + 1 < text.length) {
            if (isEscapedQuoteDelimiter(text, i)) {
                val next = skipRegexSpaces(text, i + 2)
                if (next >= text.length || text[next] == ',' || text[next] == '}') return i
            }
            i++
        }
        return -1
    }

    /** The raw (still escaped) value of the first `selector` / `ref` / `element` field. */
    private fun findPlainStringField(text: String, names: Set<String>): String? {
        var index = 0
        while (index < text.length) {
            if (text[index] != '"' || isEscaped(text, index)) {
                index++
                continue
            }
            val keyEnd = findQuotedEnd(text, index, '"')
            if (keyEnd < 0) return null
            val colon = skipRegexSpaces(text, keyEnd + 1)
            if (colon >= text.length || text[colon] != ':') {
                index = keyEnd + 1
                continue
            }
            if (text.substring(index + 1, keyEnd) !in names) {
                index = keyEnd + 1
                continue
            }
            val valueStart = skipRegexSpaces(text, colon + 1)
            if (valueStart >= text.length || text[valueStart] != '"') return null
            val valueEnd = findQuotedEnd(text, valueStart, '"')
            return if (valueEnd < 0) null else text.substring(valueStart + 1, valueEnd)
        }
        return null
    }

    /** The index of the closing [quote] for a string starting at [start], or `-1`. */
    private fun findQuotedEnd(text: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < text.length) {
            when {
                text[i] == '\\' -> i += 2
                text[i] == quote -> return i
                else -> i++
            }
        }
        return -1
    }

    /** The end (exclusive) of the value starting at [start], or `-1` when it cannot be read. */
    private fun findValueEnd(text: String, start: Int): Int {
        if (start >= text.length) return -1
        val first = text[start]
        if (first == '"' || first == '\'') {
            val end = findQuotedEnd(text, start, first)
            return if (end < 0) -1 else end + 1
        }
        var i = start
        while (i < text.length && text[i] != ',' && text[i] != '}' && text[i] != ']') i++
        return if (i > start) i else -1
    }

    /** True when the quote at [index] is escaped by an odd number of backslashes. */
    private fun isEscaped(text: String, index: Int): Boolean {
        var backslashes = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    /** True at the `\` of an unescaped `\"` delimiter inside an embedded JSON string. */
    private fun isEscapedQuoteDelimiter(text: String, index: Int): Boolean {
        if (index < 0 || index + 1 >= text.length) return false
        if (text[index] != '\\' || text[index + 1] != '"') return false
        var backslashes = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 0
    }

    private fun skipRegexSpaces(text: String, start: Int): Int {
        var i = start
        while (i < text.length && isRegexSpace(text[i])) i++
        return i
    }

    /** The `\s` class (ASCII) the replaced regexes used, not Unicode whitespace. */
    private fun isRegexSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

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
