package ai.platon.pulsar.rest.api.service.scrape

import ai.platon.pulsar.agentic.tools.advanced.format.FormatSnapshot
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStepRunner
import ai.platon.pulsar.agentic.tools.advanced.format.HTML_SNAPSHOT_DOMAIN
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration

/**
 * The production [FormatStepRunner]: it drives the same `html_snapshot` tools the
 * MCP layer drives, through a [FormatToolDispatcher].
 *
 * ## The one thing that makes capture-once true
 *
 * `html_snapshot`'s reads take an `expires` window that decides where they answer
 * from: `0s` (the tool default) **captures the live page**, a positive window
 * serves the stored snapshot **without touching the tab**. The engine has already
 * captured once by the time it calls [readOnSnapshot], so every read here must ask
 * for the store with a positive window — otherwise each requested format would
 * reload the page and the capture-once invariant would be fiction. That window is
 * [readExpires], and it is:
 *
 * - **injected into every read**, not merely defaulted, so a step that happened to
 *   carry its own `expires: 0s` cannot silently reintroduce the reload;
 * - **validated at construction**, so a zero or negative window fails when the
 *   runner is built rather than producing a request that loads the page eight
 *   times.
 *
 * ## The capture step has no `maxAge` mode
 *
 * [acquireSnapshot] always captures, and reports `cacheState = "miss"`. Reusing a
 * stored capture (Firecrawl's `maxAge`) would need that capture's **identity** —
 * `capture` always serialises the live tab and is the only method returning the
 * store key, href and capture time. Honouring it therefore needs a store-only
 * metadata path, which changes an existing tool's contract and deserves its own
 * review; see the Phase 1c notes in
 * `docs-dev/copilot/firecrawl-compatible-formats-design.md`.
 *
 * Nothing can ask for it in the meantime: `PageScrapeRequest` no longer carries a
 * window, and the CLI/REST/MCP surfaces never accepted one, because a field that
 * promises reuse nobody can deliver is worse than an absent one. The invariant that
 * matters for correctness — **one** capture per request — holds either way.
 *
 * @property dispatcher the tool dispatch, bound to the session the request runs on.
 * @property readExpires the window every snapshot read asks the store for. Must be
 *   positive; one day by default.
 */
class SnapshotFormatStepRunner(
    private val dispatcher: FormatToolDispatcher,
    private val readExpires: Duration = DEFAULT_READ_EXPIRES,
) : FormatStepRunner {

    init {
        // A window below one second rounds to "0s" in the grammar the reads accept,
        // and "0s" is the tool's "capture the live page" — i.e. it would silently
        // undo the invariant instead of failing. So the floor is a second, not
        // "positive".
        require(readExpires.seconds >= 1) {
            "readExpires must be at least one second, actual: $readExpires. A smaller window " +
                "rounds to `0s`, which makes every snapshot read capture the live page — " +
                "exactly the capture-once invariant this runner exists to keep."
        }
    }

    /**
     * Capture the active page once and return the identity every snapshot-scoped
     * format is derived from.
     *
     * The engine passes [Duration.ZERO], and this implementation would not know what
     * to do with a positive one: reusing a stored capture needs that capture's
     * identity, and no `html_snapshot` method reports a *stored* snapshot's identity
     * (see [FormatStepRunner.acquireSnapshot]). Nothing can ask for a positive
     * window any more either — the field that carried it was removed rather than
     * half-honoured — so this parameter documents a contract rather than a choice.
     */
    override suspend fun acquireSnapshot(expires: Duration): FormatSnapshot {
        val raw = dispatcher.call(HTML_SNAPSHOT_DOMAIN, CAPTURE, emptyMap())
        return parseSnapshot(raw)
    }

    /**
     * Read one format's output from the stored snapshot.
     *
     * The `expires` window is written **after** the step's own arguments, so it wins:
     * see the class KDoc. Nothing else about the step is altered.
     */
    override suspend fun readOnSnapshot(
        snapshot: FormatSnapshot,
        domain: String,
        method: String,
        args: Map<String, Any?>,
    ): String {
        val readArgs = LinkedHashMap(args)
        readArgs[EXPIRES_ARG] = formatExpires(readExpires)
        return outputOf(domain, method, dispatcher.call(domain, method, readArgs))
    }

    /**
     * Run a step that needs the live tab.
     *
     * No `expires` is injected here: these methods drive the browser on purpose,
     * and a window would mean nothing to them.
     */
    override suspend fun runOnTab(domain: String, method: String, args: Map<String, Any?>): String =
        outputOf(domain, method, dispatcher.call(domain, method, args))

    override fun supports(domain: String, method: String): Boolean = dispatcher.supports(domain, method)

    /**
     * A tool that ran and produced nothing is a failure, not an empty result.
     *
     * Returning `""` would look like "the page has no links" and be reported as a
     * successful empty format — a silent failure, which this project does not
     * allow. Throwing lets the engine apply the step's policy: a warning for a
     * best-effort format, the original error for a required one.
     */
    private fun outputOf(domain: String, method: String, raw: Any?): String =
        raw?.toString() ?: throw IllegalStateException("$domain.$method returned no output")

    /**
     * Read the capture's identity out of `html_snapshot.capture`'s metadata JSON.
     *
     * `url` is the normalized page-store key, `href` the browser-facing address —
     * the same url/href split the rest of the family uses.
     */
    private fun parseSnapshot(raw: Any?): FormatSnapshot {
        val node = when (raw) {
            is String -> raw.takeIf { it.isNotBlank() }?.let { text ->
                runCatching { pulsarObjectMapper().readTree(text) }.getOrNull()
            }

            is Map<*, *> -> pulsarObjectMapper().valueToTree<JsonNode>(raw)
            else -> null
        } ?: throw IllegalStateException(
            "html_snapshot.$CAPTURE returned no snapshot metadata (got: ${raw?.javaClass?.simpleName ?: "null"})"
        )

        val key = node.path("url").asText("")
        if (key.isEmpty()) {
            throw IllegalStateException("html_snapshot.$CAPTURE returned no page key: $node")
        }

        return FormatSnapshot(
            key = key,
            href = node.path("href").asText(key),
            capturedAt = node.path("capturedAt").asText(""),
            cacheState = "miss",
        )
    }

    companion object {
        /** The window a snapshot read asks the store for. */
        val DEFAULT_READ_EXPIRES: Duration = Duration.ofDays(1)

        /**
         * The read window as the duration grammar the snapshot family accepts
         * (`0s`, `30s`, `10m`, `2h`, `1d`, or ISO-8601).
         *
         * The largest exact unit is used so the value a log or a test sees is the
         * one a human wrote.
         *
         * @throws IllegalArgumentException when [window] is below one second: it
         *   would round to `0s`, the grammar's "read the live page", which is the
         *   opposite of what a snapshot read must ask for. Guarded here as well as
         *   at construction so the dangerous value cannot be produced even if this
         *   is ever reached another way.
         */
        internal fun formatExpires(window: Duration): String {
            val seconds = window.seconds
            require(seconds >= 1) { "A snapshot read window must be at least one second, actual: $window" }
            return when {
                seconds % SECONDS_PER_DAY == 0L -> "${seconds / SECONDS_PER_DAY}d"
                seconds % SECONDS_PER_HOUR == 0L -> "${seconds / SECONDS_PER_HOUR}h"
                seconds % SECONDS_PER_MINUTE == 0L -> "${seconds / SECONDS_PER_MINUTE}m"
                else -> "${seconds}s"
            }
        }

        /** The `expires` argument name every read in the snapshot family takes. */
        internal const val EXPIRES_ARG = "expires"

        private const val CAPTURE = "capture"
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 3_600L
        private const val SECONDS_PER_DAY = 86_400L
    }
}
