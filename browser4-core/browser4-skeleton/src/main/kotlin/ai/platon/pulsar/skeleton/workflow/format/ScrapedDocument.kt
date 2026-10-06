package ai.platon.pulsar.skeleton.workflow.format

import ai.platon.pulsar.skeleton.workflow.parse.html.ReadabilityResult
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * The Firecrawl-shaped result of a `page_scrape` request: one document carrying
 * whichever formats were asked for.
 *
 * The shape follows Firecrawl v2 (`data.*`) so a migrating caller can keep its
 * field access, with two deliberate differences:
 *
 * - Binary formats ([screenshot], [audio], [video], [pdf]) hold a **local file
 *   path**, not a cloud-hosted URL — Browser4 is a local/self-hosted tool and
 *   has no storage bucket to point at.
 * - [readability] and [pdf] are Browser4 extensions with no Firecrawl
 *   counterpart.
 *
 * A field is null when its format was not requested **or** when the format was
 * unavailable/failed; [warning] and [ScrapeMetadata.formatsDelivered] say which.
 * [retainRequested] enforces the "not requested → not present" rule, matching
 * Firecrawl's `coerceFieldsToFormats`.
 *
 * @property title page title.
 * @property description page description.
 * @property url the final page URL.
 * @property markdown `markdown` format: clean body markdown.
 * @property html `html` format: cleaned HTML.
 * @property rawHtml `rawHtml` format: the page's raw HTML.
 * @property rawBase64 `rawBase64` format: base64 of the raw document bytes.
 * @property links `links` format: absolute links discovered on the page.
 * @property images `images` format: image URLs discovered on the page.
 * @property screenshot `screenshot` format: local path to the capture.
 * @property screenshotBase64 `screenshot` format with `base64`: the capture's bytes.
 *   Coexists with [screenshot] rather than replacing it — the file is written either
 *   way, so the path is always known and the base64 is the part a caller opts into.
 * @property audio `audio` format: local path to the downloaded audio.
 * @property video `video` format: local path to the downloaded video.
 * @property videos `video` format: discovered videos (Firecrawl's `VideoItem[]`).
 * @property pdf `pdf` extension: local path to the rendered PDF.
 * @property json `json` / `deterministicJson`: the extracted object.
 * @property summary `summary` format.
 * @property answer `question` (and deprecated `query`): the answer text.
 * @property highlights `highlights` (and `query` in `directQuote` mode).
 * @property attributes `attributes` format: one entry per requested selector.
 * @property readability `readability` extension: the extracted article.
 * @property changeTracking `changeTracking` format: status plus diff payload.
 * @property branding `branding` format (contributor-provided).
 * @property product `product` format (contributor-provided).
 * @property menu `menu` format (contributor-provided).
 * @property warning degradation notes, one clause per unavailable/failed format.
 * @property metadata capture metadata; always present.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScrapedDocument(
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val markdown: String? = null,
    val html: String? = null,
    val rawHtml: String? = null,
    val rawBase64: String? = null,
    val links: List<String>? = null,
    val images: List<String>? = null,
    val screenshot: String? = null,
    val screenshotBase64: String? = null,
    val audio: String? = null,
    val video: String? = null,
    val videos: List<Any?>? = null,
    val pdf: String? = null,
    val json: Any? = null,
    val summary: String? = null,
    val answer: String? = null,
    val highlights: String? = null,
    val attributes: List<AttributeValue>? = null,
    val readability: ReadabilityResult? = null,
    val changeTracking: Map<String, Any?>? = null,
    val branding: Any? = null,
    val product: Any? = null,
    val menu: Any? = null,
    val warning: String? = null,
    val metadata: ScrapeMetadata = ScrapeMetadata(),
) {

    /**
     * Drop every field whose format was not requested.
     *
     * This is the Firecrawl contract: a caller asking for `["markdown"]` must
     * not receive a `links` field it did not ask for (and did not pay for).
     * [title], [description], [url], [warning] and [metadata] always survive —
     * they are the document envelope, not format output.
     *
     * @param requested the requested format ids; unknown ids keep nothing.
     */
    fun retainRequested(requested: Collection<String>): ScrapedDocument {
        val keep = requested.flatMapTo(mutableSetOf()) { PageFormats.documentFieldsOf(it) }
        fun <T> kept(field: String, value: T?): T? = if (field in keep) value else null

        return copy(
            markdown = kept("markdown", markdown),
            html = kept("html", html),
            rawHtml = kept("rawHtml", rawHtml),
            rawBase64 = kept("rawBase64", rawBase64),
            links = kept("links", links),
            images = kept("images", images),
            screenshot = kept("screenshot", screenshot),
            screenshotBase64 = if ("screenshotBase64" in keep) screenshotBase64 else null,
            audio = kept("audio", audio),
            video = kept("video", video),
            videos = if ("videos" in keep) videos else null,
            pdf = kept("pdf", pdf),
            json = kept("json", json),
            summary = kept("summary", summary),
            answer = kept("answer", answer),
            highlights = kept("highlights", highlights),
            attributes = kept("attributes", attributes),
            readability = kept("readability", readability),
            changeTracking = kept("changeTracking", changeTracking),
            branding = kept("branding", branding),
            product = kept("product", product),
            menu = kept("menu", menu),
        )
    }

    /**
     * Append a degradation note, keeping any earlier ones.
     *
     * @param message the note, e.g. `"screenshot: unavailable (engine does not support captures)"`.
     */
    fun withWarning(message: String?): ScrapedDocument {
        if (message.isNullOrBlank()) return this
        val merged = if (warning.isNullOrBlank()) message else "$warning $message"
        return copy(warning = merged)
    }

    /**
     * Write a contributor's value into the document field it names.
     *
     * Only [PageFormats.contributedFields] can be written: a core field belongs
     * to the provider that produced it, and a format id with no field of its own
     * has nowhere to land. The engine checks the field before calling this and
     * reports a mismatch instead of dropping the value, so `this` coming back
     * unchanged means the caller skipped that check.
     *
     * [highlights] is typed `String?` on the wire (Firecrawl's shape), so a
     * contributor returning another type has it rendered rather than rejected —
     * the alternative is losing the payload to a cast failure.
     *
     * @param field the contributor's `outputField`.
     * @param value the contributed value; null is not written.
     */
    fun withContributedField(field: String, value: Any?): ScrapedDocument {
        if (value == null) return this
        return when (field) {
            "branding" -> copy(branding = value)
            "product" -> copy(product = value)
            "menu" -> copy(menu = value)
            "highlights" -> copy(highlights = value.toString())
            else -> this
        }
    }

    /**
     * The requested formats that actually produced output, in [PageFormats.ALL]
     * order.
     *
     * Two filters, because a document field cannot name its own format:
     *
     * - Only [requested] is inspected. `json` and `deterministicJson` share the
     *   `json` field and `video` also fills `videos`, so scanning every known
     *   format reports ids the caller never asked for — `{deterministicJson}`
     *   would come back as `["json", "deterministicJson"]`.
     * - A deprecated id is never delivered. `query` shares `answer`/`highlights`
     *   with its replacements and no provider produces it, so without this it
     *   would be reported whenever its successor filled the shared field.
     *
     * Compare the result with [requested] to see what degraded; the difference is
     * also explained in [warning].
     *
     * @param requested the canonical format ids the request asked for.
     */
    fun deliveredFormats(requested: Collection<String>): List<String> =
        PageFormats.ALL.filter { type ->
            type in requested &&
                !PageFormats.isDeprecated(type) &&
                PageFormats.documentFieldsOf(type).any { hasFieldValue(it) }
        }

    /**
     * Stamp [ScrapeMetadata.formatsDelivered] with [deliveredFormats], so a
     * caller can tell "not requested" from "requested but unavailable" without
     * parsing the warning text.
     *
     * @param requested the canonical format ids the request asked for.
     */
    fun withDeliveredFormats(requested: Collection<String>): ScrapedDocument =
        copy(metadata = metadata.copy(formatsDelivered = deliveredFormats(requested)))

    /** True when [field] carries a value. */
    private fun hasFieldValue(field: String): Boolean = when (field) {
        "markdown" -> markdown != null
        "html" -> html != null
        "rawHtml" -> rawHtml != null
        "rawBase64" -> rawBase64 != null
        "links" -> links != null
        "images" -> images != null
        "screenshot" -> screenshot != null
        "screenshotBase64" -> screenshotBase64 != null
        "audio" -> audio != null
        "video" -> video != null
        "videos" -> videos != null
        "pdf" -> pdf != null
        "json" -> json != null
        "summary" -> summary != null
        "answer" -> answer != null
        "highlights" -> highlights != null
        "attributes" -> attributes != null
        "readability" -> readability != null
        "changeTracking" -> changeTracking != null
        "branding" -> branding != null
        "product" -> product != null
        "menu" -> menu != null
        else -> false
    }
}

/** One `attributes` extraction result: every value found for a selector/attribute pair. */
data class AttributeValue(
    val selector: String,
    val attribute: String,
    val values: List<String>,
)

/**
 * Capture metadata, always present on a [ScrapedDocument].
 *
 * The first block mirrors the Firecrawl metadata a migrating caller may read;
 * `captureId` / `captureTime` / `formats*` are Browser4 additions that make the
 * capture-once model observable (which snapshot the formats were derived from,
 * and what actually came back).
 *
 * @property url the page URL.
 * @property sourceURL the requested URL when it differs from [url] (redirects).
 * @property title page title as read from the document.
 * @property description page description.
 * @property language the document language, e.g. `en`.
 * @property statusCode the page's HTTP status.
 * @property contentType the page's content type.
 * @property wordCount visible word count, when counted.
 * @property favicon favicon URL.
 * @property ogTitle Open Graph title.
 * @property ogDescription Open Graph description.
 * @property ogImage Open Graph image.
 * @property scrapeId identifier shared by every capture of one scrape request.
 * @property cacheState `hit` when the capture reused the page store, `miss` when it fetched.
 * @property cachedAt ISO-8601 timestamp of the stored capture, when reused.
 * @property captureId the page-store key of the snapshot the formats were derived from.
 * @property captureTime ISO-8601 timestamp of that capture.
 * @property formatsRequested the canonical ids the request asked for.
 * @property formatsDelivered the canonical ids that produced output.
 */
data class ScrapeMetadata(
    val url: String? = null,
    val sourceURL: String? = null,
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
    val statusCode: Int? = null,
    val contentType: String? = null,
    val wordCount: Int? = null,
    val favicon: String? = null,
    val ogTitle: String? = null,
    val ogDescription: String? = null,
    val ogImage: String? = null,
    val scrapeId: String? = null,
    val cacheState: String? = null,
    val cachedAt: String? = null,
    val captureId: String? = null,
    val captureTime: String? = null,
    val formatsRequested: List<String> = emptyList(),
    val formatsDelivered: List<String> = emptyList(),
)
