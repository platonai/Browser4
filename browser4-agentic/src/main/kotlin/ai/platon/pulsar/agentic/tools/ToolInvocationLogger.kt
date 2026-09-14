package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.common.getLogger
import org.slf4j.MDC
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The one place a tool call is logged, shared by both MCP channels.
 *
 * A call produces exactly two lines — `start` and `done`/`failed` — so the log is
 * greppable by `requestId` and a failure can be traced from the client's request
 * down to the driver without reading argument bodies:
 *
 * ```
 * tool.call start  requestId=4f2a1c0b channel=A tool=crawl_submit session=caccfaa5 args=[url=https://example.com, depth=1]
 * tool.call done   requestId=4f2a1c0b channel=A tool=crawl_submit durationMs=812 outcome=OK resultChars=36
 * ```
 *
 * Argument values are never printed in full:
 * - names that look sensitive (`token`, `password`, `cookie`, `storageState`,
 *   file contents, …) are redacted entirely;
 * - long values degrade to `len=… sha256=…` so two calls can still be compared.
 *
 * The request id is also returned to the client in the result `_meta`, so a bug
 * report carries the id that identifies the exact call.
 */
object ToolInvocationLogger {
    private val logger = getLogger(this)

    /** MDC key so every nested log line of one call can carry the same id. */
    const val REQUEST_ID_KEY = "requestId"

    /**
     * Argument names whose value must never be logged. Matching is
     * case-insensitive and substring-based: `storageState`, `authToken` and
     * `cookieJar` are all covered by `state`/`token`/`cookie`.
     */
    private val SENSITIVE_NAME_PARTS = listOf(
        "token", "password", "passwd", "secret", "apikey", "api_key", "authorization",
        "cookie", "credential", "session", "state", "content", "file", "path",
        "signature", "private", "key",
    )

    /** Values longer than this are hashed instead of logged. */
    private const val MAX_VALUE_CHARS = 48

    private val sequence = AtomicLong()

    /** A short, log-friendly call id. */
    fun newRequestId(channel: String): String {
        val suffix = sequence.incrementAndGet().toString(16).padStart(4, '0')
        return "$channel-${UUID.randomUUID().toString().take(8)}-$suffix"
    }

    /** Logs the inbound half of a call. */
    fun logStart(
        requestId: String,
        channel: String,
        tool: String,
        sessionId: String?,
        args: Map<String, Any?>,
    ) {
        logger.info(
            "tool.call start  requestId={} channel={} tool={} session={} args=[{}]",
            requestId, channel, tool, sessionId ?: "-", renderArgs(args),
        )
    }

    /** Logs the outbound half of a call. */
    fun logFinished(
        requestId: String,
        channel: String,
        tool: String,
        durationMs: Long,
        errorCode: ToolErrorCode?,
        resultChars: Int,
    ) {
        if (errorCode == null) {
            logger.info(
                "tool.call done   requestId={} channel={} tool={} durationMs={} outcome=OK resultChars={}",
                requestId, channel, tool, durationMs, resultChars,
            )
        } else {
            logger.warn(
                "tool.call failed requestId={} channel={} tool={} durationMs={} outcome={} retryable={} resultChars={}",
                requestId, channel, tool, durationMs, errorCode.wire, errorCode.retryable, resultChars,
            )
        }
    }

    /** Runs [block] with the request id in the MDC, so nested logs carry it. */
    suspend fun <T> withRequestContext(requestId: String, block: suspend () -> T): T {
        MDC.put(REQUEST_ID_KEY, requestId)
        try {
            return block()
        } finally {
            MDC.remove(REQUEST_ID_KEY)
        }
    }

    /** `name=value` pairs with sensitive names redacted and long values hashed. */
    fun renderArgs(args: Map<String, Any?>): String = args.entries
        .joinToString(", ") { (name, value) -> "$name=${renderValue(name, value)}" }

    /** Renders one value for a log line. */
    fun renderValue(name: String, value: Any?): String {
        if (isSensitiveName(name)) return REDACTED
        return when (value) {
            null -> "null"
            is String -> shorten(value)
            is Number, is Boolean -> value.toString()
            is Collection<*> -> "list(size=${value.size})"
            is Map<*, *> -> "map(keys=${value.keys.take(5).joinToString(",")})"
            is Array<*> -> "array(size=${value.size})"
            else -> "${value::class.simpleName}(len=${value.toString().length})"
        }
    }

    /** Whether a name looks like it carries a secret. */
    fun isSensitiveName(name: String): Boolean {
        val lower = name.lowercase()
        return SENSITIVE_NAME_PARTS.any { it in lower }
    }

    private fun shorten(value: String): String = when {
        value.length <= MAX_VALUE_CHARS -> value.replace('\n', ' ')
        else -> "len=${value.length} sha256=${shortHash(value)}"
    }

    private fun shortHash(value: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .take(4)
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault("?")

    private const val REDACTED = "***"
}
