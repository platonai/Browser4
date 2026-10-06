package ai.platon.pulsar.skeleton.workflow.format

/**
 * A page-output format contributed by a plugin, used by the Firecrawl-compatible
 * `page_scrape` request (`formats: [...]`).
 *
 * Core formats do **not** go through this interface: they are implemented inside
 * the format engine, which needs multi-step, multi-field expressiveness
 * (capture once, derive markdown and links and attributes from the same
 * snapshot). A contributor is deliberately narrower — it is handed inputs that
 * are already loaded and returns one value that the engine drops into
 * [ScrapedDocument.outputField].
 *
 * That narrowness is what makes the SPI safe to expose: a plugin cannot trigger
 * an extra page load, cannot reorder the pipeline, and cannot populate a field
 * belonging to another format.
 *
 * ## Registering
 *
 * Expose implementations through a `PageFormatContributorMount` bean — the
 * `PluginManager` registers them at startup, exactly like
 * `PageSummaryAlgorithmMount` does for summary algorithms:
 *
 * ```kotlin
 * @AutoConfiguration
 * class BrandingAutoConfiguration : PageFormatContributorMount {
 *     override fun getPageFormatContributors(): List<PageFormatContributor> =
 *         listOf(BrandingFormatContributor())
 * }
 * ```
 *
 * ## Availability
 *
 * [id] being registered does not mean the format works in this deployment: a
 * contributor backed by an external service must say so via [isAvailable] /
 * [unavailableReason]. The engine then omits the field and records the reason in
 * `ScrapedDocument.warning` — the same degradation contract Firecrawl uses when
 * its extraction services are not configured. A format id that no contributor
 * claims is reported as unavailable too, so `branding` on a stock install
 * degrades instead of failing the whole request.
 *
 * ## Threading
 *
 * Implementations must be thread-safe: the host invokes [contribute]
 * concurrently for different sessions and pages.
 */
interface PageFormatContributor {

    /**
     * Unique format identifier, matching [PageFormats.ID_PATTERN]
     * (`[a-z][a-zA-Z0-9]*`).
     *
     * This is stable user-facing API: it appears in the `formats` request array
     * and in `scrape formats` output, so renaming an id is a breaking change.
     * Registering an id that is already taken is refused (first wins), which
     * keeps a plugin from silently replacing another format.
     */
    val id: String

    /** Human-readable name, shown by the format capability listing. */
    val displayName: String

    /** One-paragraph description of what the format produces. */
    val description: String

    /**
     * The [ScrapedDocument] field this contributor fills. Defaults to [id];
     * override only when the format's output is a semantic role rather than a
     * payload name (e.g. answering into `answer`).
     */
    val outputField: String get() = id

    /**
     * The inputs the engine must have ready before [contribute] is called.
     *
     * The engine resolves these from the single capture; when one cannot be
     * supplied the format is reported unavailable rather than called with nulls.
     */
    val requires: Set<FormatInput>

    /**
     * Whether this format can run right now (service configured, feature
     * enabled, key present). Defaults to available so a purely local
     * contributor needs no extra code.
     */
    fun isAvailable(): Boolean = true

    /**
     * Why [isAvailable] is false. Goes into `ScrapedDocument.warning` and the
     * capability listing, so it must name the missing piece concretely
     * (e.g. `"no contributor installed"`, `"PRODUCT_EXTRACTION_SERVICE_URL is not set"`).
     *
     * @return the reason, or null when the contributor is available.
     */
    fun unavailableReason(): String? = null

    /**
     * Produce the format's value.
     *
     * @param ctx the capture inputs plus the request options; never null.
     * @return the value for [outputField] — must be JSON-serializable. Returning
     *   null means "nothing to report for this page", which the engine treats as
     *   a graceful degradation with [unavailableReason] omitted; throw instead
     *   for a genuine failure, so its message reaches the caller.
     */
    suspend fun contribute(ctx: FormatContext): Any?
}

/**
 * An input a [PageFormatContributor] needs.
 *
 * The engine guarantees the ones it reports as available are populated; a
 * missing input makes the format unavailable rather than handing over a null.
 */
enum class FormatInput {
    /** The stored page snapshot exists and its key is available. */
    SNAPSHOT,

    /** The page's raw HTML. */
    RAW_HTML,

    /** The cleaned HTML. */
    HTML,

    /** The derived markdown body. */
    MARKDOWN,

    /** The live tab (needed by formats that must render or capture). */
    LIVE_TAB,

    /** The final page URL. */
    URL,

    /** Capture metadata (title, description, status, …). */
    METADATA,
}

/**
 * Inputs handed to [PageFormatContributor.contribute].
 *
 * The engine builds one context per request from **one** capture, so every
 * contributor in a request sees the same page state — the property that makes a
 * multi-format response internally consistent.
 *
 * @property snapshotKey page-store key of the capture the values came from.
 * @property url the final page URL.
 * @property rawHtml the raw HTML, when ready.
 * @property html the cleaned HTML, when ready.
 * @property markdown the derived markdown, when ready.
 * @property metadata capture metadata.
 * @property options format-specific request options (e.g. `mode` for branding).
 */
data class FormatContext(
    val snapshotKey: String,
    val url: String,
    val rawHtml: String? = null,
    val html: String? = null,
    val markdown: String? = null,
    val metadata: ScrapeMetadata = ScrapeMetadata(),
    val options: Map<String, Any?> = emptyMap(),
)
