package ai.platon.pulsar.agentic.tools.advanced.format

import java.time.Duration

/**
 * Identity of the capture every snapshot-scoped format is derived from.
 *
 * A `page_scrape` request captures **once** ([FormatStepRunner.acquireSnapshot])
 * and every `FROM_SNAPSHOT` step reads that one capture, so all formats in one
 * response describe the same page state. The values here make that observable to
 * the caller through `ScrapeMetadata`.
 *
 * @property key the page-store key (the normalized URL) the snapshot is filed under.
 * @property href the browser-facing address the snapshot was taken from.
 * @property capturedAt ISO-8601 timestamp of the capture.
 * @property cacheState `hit` when a stored capture was reused, `miss` when the
 *   live page was captured.
 */
data class FormatSnapshot(
    val key: String,
    val href: String,
    val capturedAt: String,
    val cacheState: String,
)

/**
 * What the format engine needs from its host.
 *
 * The engine owns *what* to run (the plan) and *how to assemble* the result; the
 * host owns *where the tools live*. Implementations call the same executors the
 * MCP layer calls, so an internal step and a client-driven tool call cannot
 * drift apart.
 *
 * Implementations must honour the read/write split the engine relies on:
 *
 * - [readOnSnapshot] **never touches the live tab** — it reads the capture
 *   identified by [snapshot]. This is what makes a multi-format response
 *   internally consistent and re-runnable.
 * - [runOnTab] is the only method allowed to drive the browser, and the engine
 *   calls it after every snapshot-scoped format is done.
 *
 * @see PageFormatEngine
 */
interface FormatStepRunner {

    /**
     * Capture the page once and return its identity.
     *
     * @param expires reuse a stored capture younger than this instead of
     *   capturing again; [Duration.ZERO] means "capture the live page".
     *
     *   **Every caller passes [Duration.ZERO] today, and that is not a gap in the
     *   caller.** A positive window is the analogue of Firecrawl's `maxAge` — "this
     *   page has not changed, spend nothing" — but honouring it requires the
     *   *stored* capture's identity (its key, href and timestamp). No
     *   `html_snapshot` method reports that: `capture` always serialises the live
     *   tab and is the only method returning those values, while the store-reading
     *   methods return content rather than identity. The parameter is kept because
     *   [Duration.ZERO] is a truthful instruction; a positive value cannot yet be
     *   satisfied by any implementation.
     */
    suspend fun acquireSnapshot(expires: Duration): FormatSnapshot

    /**
     * Run one tool method against [snapshot], without touching the tab.
     *
     * @param domain the tool domain, e.g. `html_snapshot`.
     * @param method the tool method, e.g. `export`.
     * @param args the tool arguments.
     * @return the tool's output (HTML, JSON, or plain text, per the method).
     */
    suspend fun readOnSnapshot(
        snapshot: FormatSnapshot,
        domain: String,
        method: String,
        args: Map<String, Any?>,
    ): String

    /**
     * Run one tool method on the live tab (screenshots, PDF export, …).
     *
     * @param domain the tool domain, e.g. `tab`.
     * @param method the tool method, e.g. `screenshot`.
     * @param args the tool arguments.
     * @return the tool's output.
     */
    suspend fun runOnTab(domain: String, method: String, args: Map<String, Any?>): String

    /**
     * Whether this deployment can run the method at all (the plugin providing it
     * is installed, the underlying service is configured).
     *
     * A `false` here degrades the dependent format with a `warning` instead of
     * failing the request; the engine never calls an unsupported method.
     */
    fun supports(domain: String, method: String): Boolean
}
