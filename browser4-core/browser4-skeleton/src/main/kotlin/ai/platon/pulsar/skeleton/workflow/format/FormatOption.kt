package ai.platon.pulsar.skeleton.workflow.format

/**
 * The set of page-output formats the Firecrawl-compatible `formats` parameter
 * accepts, and the canonical id table behind it.
 *
 * Ids are the user-facing API: they appear in the `formats` request array, in
 * the `formatsDelivered` metadata and in error messages, so renaming one is a
 * breaking change. The canonical spelling follows Firecrawl v2 (`rawHtml`,
 * `rawBase64`, `deterministicJson`, `changeTracking`); lookups are
 * case-insensitive so `rawhtml` and `RAWHTML` resolve to the same format.
 *
 * Three groups exist, and the difference matters to callers:
 *
 * - [CORE] — delivered by the built-in engine (`page_scrape`), no plugin needed.
 * - [CONTRIBUTED] — the id is valid, but something must contribute it
 *   (`PageFormatContributor`); a deployment without that contributor reports the
 *   format as unavailable per the degradation contract (field omitted +
 *   `warning`) instead of rejecting the request.
 * - [DEPRECATED] — accepted for migration only.
 *
 * [isKnown] answers "is this a real format id" (a typo must fail loudly with
 * `UNKNOWN_FORMAT`); availability is a runtime question answered elsewhere.
 */
object PageFormats {

    // ---- core formats -------------------------------------------------------

    /** Clean article/body markdown; the default format. */
    const val MARKDOWN = "markdown"

    /** Cleaned HTML (scripts/styles stripped). */
    const val HTML = "html"

    /** The page's raw HTML exactly as fetched. */
    const val RAW_HTML = "rawHtml"

    /** Base64 of the raw document bytes; only valid as the sole format. */
    const val RAW_BASE64 = "rawBase64"

    /** Absolute links discovered on the page. */
    const val LINKS = "links"

    /** Image URLs discovered on the page. */
    const val IMAGES = "images"

    /** Screenshot (viewport by default); only one screenshot may be requested. */
    const val SCREENSHOT = "screenshot"

    /** CSS-selector driven attribute extraction. */
    const val ATTRIBUTES = "attributes"

    /** LLM extraction constrained by an optional JSON schema. */
    const val JSON = "json"

    /** Extraction performed by a reusable, deterministic extractor (X-SQL). */
    const val DETERMINISTIC_JSON = "deterministicJson"

    /** Compressed page summary. */
    const val SUMMARY = "summary"

    /** Free-form question answered from the page content. */
    const val QUESTION = "question"

    /** Page change tracking against the previous capture. */
    const val CHANGE_TRACKING = "changeTracking"

    /** Audio download. */
    const val AUDIO = "audio"

    /** Video download / discovery. */
    const val VIDEO = "video"

    /** Browser4 extension: render the page to PDF. */
    const val PDF = "pdf"

    /** Browser4 extension: Readability-style article extraction. */
    const val READABILITY = "readability"

    // ---- contributor-provided formats ---------------------------------------

    /** Branding profile (logo, palette, typography); requires a contributor. */
    const val BRANDING = "branding"

    /** Product profile (variants, price, availability); requires a contributor. */
    const val PRODUCT = "product"

    /** Menu profile (merchant, sections, items); requires a contributor. */
    const val MENU = "menu"

    /** Verbatim quotes answering a query; requires a contributor. */
    const val HIGHLIGHTS = "highlights"

    // ---- deprecated ---------------------------------------------------------

    /** @deprecated Use [QUESTION] or [HIGHLIGHTS]; accepted for migration only. */
    const val QUERY = "query"

    /** Formats the built-in engine can deliver without any plugin installed. */
    val CORE: Set<String> = linkedSetOf(
        MARKDOWN, HTML, RAW_HTML, RAW_BASE64, LINKS, IMAGES, SCREENSHOT, ATTRIBUTES,
        JSON, DETERMINISTIC_JSON, SUMMARY, QUESTION, CHANGE_TRACKING, AUDIO, VIDEO,
        PDF, READABILITY,
    )

    /** Formats that only exist once a [PageFormatContributor] claims the id. */
    val CONTRIBUTED: Set<String> = linkedSetOf(BRANDING, PRODUCT, MENU, HIGHLIGHTS)

    /** Formats accepted for backward compatibility only. */
    val DEPRECATED: Set<String> = linkedSetOf(QUERY)

    /** Every accepted format id, in listing order. */
    val ALL: Set<String> = linkedSetOf<String>().apply {
        addAll(CORE)
        addAll(CONTRIBUTED)
        addAll(DEPRECATED)
    }

    /** Format applied when the request does not ask for any. */
    val DEFAULT_TYPES: List<String> = listOf(MARKDOWN)

    /** Human-readable id syntax used by contributor ids and error messages. */
    const val ID_PATTERN = "[a-z][a-zA-Z0-9]*"

    private val ID_REGEX = Regex(ID_PATTERN)

    /** Canonical id by lowercase spelling, e.g. `rawhtml` → `rawHtml`. */
    private val CANONICAL_BY_LOWER: Map<String, String> = ALL.associateBy { it.lowercase() }

    /**
     * Firecrawl v1 / pre-rename spellings mapped to their canonical id. Kept in
     * one place so the v1 names cannot drift apart in different call sites.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "screenshot@fullpage" to SCREENSHOT,
        "extract" to JSON,
    )

    /**
     * Resolve any accepted spelling to the canonical id.
     *
     * @param raw the requested type, e.g. `rawhtml`, `screenshot@fullPage`.
     * @return the canonical id, or null when [raw] is not an accepted spelling
     *   (the caller reports `UNKNOWN_FORMAT`).
     */
    fun canonicalType(raw: String): String? {
        val lower = raw.trim().lowercase()
        if (lower.isEmpty()) return null
        return ALIASES[lower] ?: CANONICAL_BY_LOWER[lower]
    }

    /** True when [raw] is an accepted format id or alias. */
    fun isKnown(raw: String): Boolean = canonicalType(raw) != null

    /** True when the id is valid but needs a [PageFormatContributor]. */
    fun isContributed(type: String): Boolean = type in CONTRIBUTED

    /** True when the id is valid only for backward compatibility. */
    fun isDeprecated(type: String): Boolean = type in DEPRECATED

    /** True when the id matches [ID_PATTERN]. */
    fun isValidId(id: String): Boolean = ID_REGEX.matches(id)

    /** The core format ids, in listing order. */
    fun coreTypes(): List<String> = CORE.toList()

    /** The contributor-provided format ids, in listing order. */
    fun contributedTypes(): List<String> = CONTRIBUTED.toList()

    /**
     * The `ScrapedDocument` fields a [PageFormatContributor] may write.
     *
     * Derived from [CONTRIBUTED] rather than listed a second time, so a new
     * contributed format cannot be forgotten here. A contributor names its
     * target through `outputField`; anything outside this set is refused by the
     * engine with a warning rather than written into a field it would then
     * silently overwrite.
     */
    fun contributedFields(): Set<String> =
        CONTRIBUTED.flatMapTo(linkedSetOf()) { documentFieldsOf(it) }

    /**
     * The `ScrapedDocument` fields a format populates.
     *
     * Most formats write the field with their own name; the exceptions are the
     * formats whose output is a semantic role rather than a payload type:
     * `json` and `deterministicJson` both fill `json`, `question` answers into
     * `answer`, `query` fills `answer` or `highlights` depending on its mode,
     * and `video` also fills `videos`.
     *
     * @return the field names; empty for an unknown id.
     */
    fun documentFieldsOf(type: String): Set<String> {
        val canonical = canonicalType(type) ?: return emptySet()
        return when (canonical) {
            JSON, DETERMINISTIC_JSON -> setOf("json")
            QUESTION -> setOf("answer")
            QUERY -> setOf("answer", "highlights")
            VIDEO -> setOf("video", "videos")
            else -> setOf(canonical)
        }
    }

    /**
     * The canonical format whose output is the given document [field], or null
     * when no format produces it. Used to report which formats a decorated
     * document actually carries.
     */
    fun formatOfField(field: String): String? =
        ALL.firstOrNull { field in documentFieldsOf(it) }
}

/**
 * A single requested output format.
 *
 * Options are a flat superset of every format's settings rather than a sealed
 * hierarchy: the request arrives as loosely-typed JSON/MCP arguments, so a flat
 * shape keeps parsing (and the error messages for a misplaced option) simple.
 * Options that do not apply to [type] are ignored by the engine.
 *
 * @property type canonical format id (see [PageFormats]); never blank, but may
 *   be an unknown string so validation can report it with its original spelling.
 * @property fullPage screenshot: capture the full scrollable page.
 * @property quality screenshot: JPEG quality, 1-100.
 * @property viewport screenshot: capture this viewport size.
 * @property schema json/deterministicJson/changeTracking: JSON schema object.
 * @property prompt json/deterministicJson/changeTracking/query: extraction prompt.
 * @property checkPromptInjection json: ask the extractor to report prompt injection.
 * @property question question: the question to answer.
 * @property query highlights/deprecated query: the query text.
 * @property mode branding (`auto`/`fast`/`standard`) or query (`freeform`/
 *   `directQuote`).
 * @property modes changeTracking: `json` and/or `git-diff`.
 * @property tag changeTracking: caller-chosen tag grouping captures.
 * @property selectors attributes: what to read.
 * @property algorithm summary: summary algorithm id.
 * @property sql deterministicJson: the X-SQL statement to run (Browser4
 *   extension — deterministic extraction here *is* X-SQL, so the caller supplies
 *   the statement instead of a JSON schema).
 */
data class PageFormat(
    val type: String,
    val fullPage: Boolean = false,
    val quality: Int? = null,
    val viewport: FormatViewport? = null,
    val schema: Map<String, Any?>? = null,
    val prompt: String? = null,
    val checkPromptInjection: Boolean? = null,
    val question: String? = null,
    val query: String? = null,
    val mode: String? = null,
    val modes: List<String> = emptyList(),
    val tag: String? = null,
    val selectors: List<AttributeSelector> = emptyList(),
    val algorithm: String? = null,
    val sql: String? = null,
) {
    /** True when this entry is the canonical [expected] format. */
    fun isType(expected: String): Boolean = type == expected
}

/** A screenshot capture size. */
data class FormatViewport(val width: Int, val height: Int)

/** One `attributes` selector: which elements, and which attribute to read. */
data class AttributeSelector(val selector: String, val attribute: String)

/**
 * A rejected format request.
 *
 * Messages for the rules Firecrawl also enforces are copied verbatim so a
 * migrating caller sees the same text it saw before; the codes give the REST/MCP
 * layer something stable to branch on (`INVALID_ARGUMENT`, HTTP 400).
 */
data class FormatIssue(
    val code: FormatIssueCode,
    val message: String,
    val format: String? = null,
)

/** Stable rejection reasons for a `formats` request. */
enum class FormatIssueCode {
    /** The type is not an accepted format id or alias — usually a typo. */
    UNKNOWN_FORMAT,

    /** An entry was neither a string nor an object. */
    INVALID_FORMAT_ENTRY,

    /** More than one `screenshot` entry. */
    DUPLICATE_SCREENSHOT,

    /** `changeTracking` without `markdown`. */
    CHANGE_TRACKING_REQUIRES_MARKDOWN,

    /** `json` and `deterministicJson` together. */
    JSON_CONFLICT,

    /** `rawBase64` combined with any other format. */
    RAW_BASE64_EXCLUSIVE,

    /** `json` without a prompt or a schema. */
    JSON_REQUIRES_PROMPT_OR_SCHEMA,

    /**
     * `deterministicJson` without `sql`.
     *
     * Browser4's deterministic extractor is X-SQL: the caller supplies the
     * statement. A schema-only request cannot be turned into a deterministic
     * extraction, so it is rejected with a pointer at `json` (which is
     * LLM-driven and accepts a schema).
     */
    DETERMINISTIC_JSON_REQUIRES_SQL,

    /** `attributes` without any selector. */
    ATTRIBUTES_SELECTORS_REQUIRED,

    /** An `attributes` selector missing `selector` or `attribute`. */
    ATTRIBUTE_SELECTOR_INCOMPLETE,

    /** `question` without a question. */
    QUESTION_REQUIRED,

    /** `highlights` without a query. */
    HIGHLIGHTS_QUERY_REQUIRED,

    /** Deprecated `query` without a prompt. */
    QUERY_PROMPT_REQUIRED,

    /** `screenshot.quality` outside 1-100. */
    INVALID_QUALITY,

    /** `screenshot.viewport` with a non-positive or oversized dimension. */
    INVALID_VIEWPORT,

    /** An enum-valued option (`mode`, `modes`) holds an unknown value. */
    INVALID_MODE,
}

/** Outcome of parsing a `formats` request: the accepted formats plus every problem found. */
data class FormatParseResult(
    val formats: List<PageFormat>,
    val issues: List<FormatIssue>,
) {
    /** True when no issue was found. */
    val ok: Boolean get() = issues.isEmpty()

    /**
     * The parsed formats, or an [IllegalArgumentException] carrying every
     * message.
     *
     * Throwing [IllegalArgumentException] is deliberate: the tool dispatcher
     * already classifies it as `INVALID_ARGUMENT` (HTTP 400), so the caller gets
     * the right status without a bespoke error type.
     */
    fun requireValid(): List<PageFormat> {
        if (ok) return formats
        throw IllegalArgumentException(issues.joinToString("; ") { it.message })
    }
}

/**
 * Parse and validate the `formats` parameter.
 *
 * Accepts the shapes the REST and MCP layers can produce: a JSON array of
 * strings/objects, a single string, a single object, or already-parsed
 * [PageFormat] values. `null` and an empty array both mean [PageFormats.DEFAULT_TYPES].
 *
 * Never throws: every rejection is returned as a [FormatIssue] so a caller can
 * report all of them at once instead of one per round trip.
 */
object FormatOptionSchema {

    /** Screenshot quality bounds, matching Firecrawl. */
    const val MIN_QUALITY = 1
    const val MAX_QUALITY = 100

    /** Viewport bounds, matching Firecrawl's 8K ceiling. */
    const val MAX_VIEWPORT_WIDTH = 7680
    const val MAX_VIEWPORT_HEIGHT = 4320

    private val BRANDING_MODES = setOf("auto", "fast", "standard")
    private val QUERY_MODES = setOf("freeform", "directQuote")
    private val CHANGE_TRACKING_MODES = setOf("json", "git-diff")

    /**
     * Parse [raw] and report every problem found.
     *
     * @param raw the raw `formats` value (string, map, iterable, or null).
     */
    fun parse(raw: Any?): FormatParseResult {
        val entries = entriesOf(raw)
        val issues = mutableListOf<FormatIssue>()
        val formats = entries.mapNotNull { entry -> toFormat(entry, issues) }
        issues += validate(formats)
        return FormatParseResult(formats, issues)
    }

    /**
     * Best-effort normalization without validation.
     *
     * Malformed entries are skipped; use [parse] when the caller must learn what
     * was rejected.
     */
    fun normalize(raw: Any?): List<PageFormat> = entriesOf(raw).mapNotNull { toFormat(it, null) }

    /**
     * Check the combination rules a parsed format list must satisfy.
     *
     * Rules mirror Firecrawl v2 so a migrating request either keeps working or
     * fails with the same explanation.
     */
    fun validate(formats: List<PageFormat>): List<FormatIssue> {
        val issues = mutableListOf<FormatIssue>()
        val types = formats.map { it.type }

        formats.forEach { format ->
            if (!PageFormats.isKnown(format.type)) {
                issues += FormatIssue(
                    FormatIssueCode.UNKNOWN_FORMAT,
                    "Unknown format '${format.type}'. Known formats: " +
                        PageFormats.ALL.joinToString(", "),
                    format.type,
                )
            }
        }

        if (types.count { it == PageFormats.SCREENSHOT } > 1) {
            issues += FormatIssue(
                FormatIssueCode.DUPLICATE_SCREENSHOT,
                "You may only specify one screenshot format",
                PageFormats.SCREENSHOT,
            )
        }

        if (PageFormats.CHANGE_TRACKING in types && PageFormats.MARKDOWN !in types) {
            issues += FormatIssue(
                FormatIssueCode.CHANGE_TRACKING_REQUIRES_MARKDOWN,
                "The changeTracking format requires the markdown format to be specified as well",
                PageFormats.CHANGE_TRACKING,
            )
        }

        if (PageFormats.JSON in types && PageFormats.DETERMINISTIC_JSON in types) {
            issues += FormatIssue(
                FormatIssueCode.JSON_CONFLICT,
                "Cannot specify both json and deterministicJson formats",
                PageFormats.JSON,
            )
        }

        if (PageFormats.RAW_BASE64 in types && formats.size > 1) {
            issues += FormatIssue(
                FormatIssueCode.RAW_BASE64_EXCLUSIVE,
                "The rawBase64 format cannot be combined with other formats",
                PageFormats.RAW_BASE64,
            )
        }

        formats.forEach { format ->
            if (!PageFormats.isKnown(format.type)) return@forEach
            issues += validateOne(format)
        }

        return issues
    }

    private fun validateOne(format: PageFormat): List<FormatIssue> {
        val issues = mutableListOf<FormatIssue>()
        when (format.type) {
            PageFormats.SCREENSHOT -> {
                format.quality?.let { quality ->
                    if (quality < MIN_QUALITY || quality > MAX_QUALITY) {
                        issues += FormatIssue(
                            FormatIssueCode.INVALID_QUALITY,
                            "screenshot.quality must be between $MIN_QUALITY and $MAX_QUALITY, actual: $quality",
                            format.type,
                        )
                    }
                }
                format.viewport?.let { viewport ->
                    val invalid = viewport.width <= 0 || viewport.height <= 0 ||
                        viewport.width > MAX_VIEWPORT_WIDTH || viewport.height > MAX_VIEWPORT_HEIGHT
                    if (invalid) {
                        issues += FormatIssue(
                            FormatIssueCode.INVALID_VIEWPORT,
                            "screenshot.viewport must be positive and at most " +
                                "${MAX_VIEWPORT_WIDTH}x$MAX_VIEWPORT_HEIGHT, actual: " +
                                "${viewport.width}x${viewport.height}",
                            format.type,
                        )
                    }
                }
            }

            PageFormats.ATTRIBUTES -> {
                if (format.selectors.isEmpty()) {
                    issues += FormatIssue(
                        FormatIssueCode.ATTRIBUTES_SELECTORS_REQUIRED,
                        "The attributes format requires at least one {selector, attribute} entry",
                        format.type,
                    )
                } else {
                    format.selectors.forEachIndexed { index, selector ->
                        if (selector.selector.isBlank() || selector.attribute.isBlank()) {
                            issues += FormatIssue(
                                FormatIssueCode.ATTRIBUTE_SELECTOR_INCOMPLETE,
                                "attributes.selectors[$index] requires a non-blank 'selector' and 'attribute'",
                                format.type,
                            )
                        }
                    }
                }
            }

            PageFormats.JSON -> {
                if (format.prompt.isNullOrBlank() && format.schema == null) {
                    issues += FormatIssue(
                        FormatIssueCode.JSON_REQUIRES_PROMPT_OR_SCHEMA,
                        "json format requires either 'prompt' or 'schema' (or both)",
                        format.type,
                    )
                }
            }

            PageFormats.DETERMINISTIC_JSON -> {
                if (format.sql.isNullOrBlank()) {
                    issues += FormatIssue(
                        FormatIssueCode.DETERMINISTIC_JSON_REQUIRES_SQL,
                        "deterministicJson requires 'sql' (an X-SQL statement); " +
                            "use the json format for schema-driven extraction",
                        format.type,
                    )
                }
            }

            PageFormats.QUESTION -> {                if (format.question.isNullOrBlank()) {
                    issues += FormatIssue(
                        FormatIssueCode.QUESTION_REQUIRED,
                        "question format requires a non-empty 'question' string",
                        format.type,
                    )
                }
            }

            PageFormats.HIGHLIGHTS -> {
                if (format.query.isNullOrBlank()) {
                    issues += FormatIssue(
                        FormatIssueCode.HIGHLIGHTS_QUERY_REQUIRED,
                        "highlights format requires a non-empty 'query' string",
                        format.type,
                    )
                }
            }

            PageFormats.QUERY -> {
                if (format.prompt.isNullOrBlank()) {
                    issues += FormatIssue(
                        FormatIssueCode.QUERY_PROMPT_REQUIRED,
                        "query format requires a non-empty 'prompt' string",
                        format.type,
                    )
                }
                format.mode?.let { mode ->
                    if (mode !in QUERY_MODES) {
                        issues += FormatIssue(
                            FormatIssueCode.INVALID_MODE,
                            "query format mode must be one of ${QUERY_MODES.joinToString(", ")}, actual: '$mode'",
                            format.type,
                        )
                    }
                }
            }

            PageFormats.BRANDING -> {
                format.mode?.let { mode ->
                    if (mode !in BRANDING_MODES) {
                        issues += FormatIssue(
                            FormatIssueCode.INVALID_MODE,
                            "branding format mode must be one of ${BRANDING_MODES.joinToString(", ")}, actual: '$mode'",
                            format.type,
                        )
                    }
                }
            }

            PageFormats.CHANGE_TRACKING -> {
                format.modes.filter { it !in CHANGE_TRACKING_MODES }.forEach { mode ->
                    issues += FormatIssue(
                        FormatIssueCode.INVALID_MODE,
                        "changeTracking modes must be within " +
                            "${CHANGE_TRACKING_MODES.joinToString(", ")}, actual: '$mode'",
                        format.type,
                    )
                }
            }
        }
        return issues
    }

    private fun entriesOf(raw: Any?): List<Any?> = when (raw) {
        null -> PageFormats.DEFAULT_TYPES
        is String -> listOf(raw)
        is PageFormat -> listOf(raw)
        is Map<*, *> -> listOf(raw)
        is Iterable<*> -> raw.toList().ifEmpty { PageFormats.DEFAULT_TYPES }
        is Array<*> -> raw.toList().ifEmpty { PageFormats.DEFAULT_TYPES }
        else -> listOf(raw)
    }

    private fun toFormat(entry: Any?, issues: MutableList<FormatIssue>?): PageFormat? = when (entry) {
        is PageFormat -> entry
        is String -> fromMap(mapOf("type" to entry), issues)
        is Map<*, *> -> fromMap(entry, issues)
        else -> {
            issues?.add(
                FormatIssue(
                    FormatIssueCode.INVALID_FORMAT_ENTRY,
                    "Each format entry must be a string or an object, actual: ${entry?.javaClass?.simpleName ?: "null"}",
                )
            )
            null
        }
    }

    private fun fromMap(map: Map<*, *>, issues: MutableList<FormatIssue>?): PageFormat? {
        val options = normalizeKeys(map)
        val rawType = options["type"]?.toString()?.trim().orEmpty()
        if (rawType.isEmpty()) {
            issues?.add(
                FormatIssue(
                    FormatIssueCode.INVALID_FORMAT_ENTRY,
                    "Each format object requires a 'type' string",
                )
            )
            return null
        }

        val lower = rawType.lowercase()
        val canonical = PageFormats.canonicalType(rawType) ?: rawType
        val aliasFullPage = lower == "screenshot@fullpage"

        return PageFormat(
            type = canonical,
            fullPage = asBoolean(options["fullpage"]) ?: aliasFullPage,
            quality = asInt(options["quality"]),
            viewport = asViewport(options["viewport"]),
            schema = asMap(options["schema"]),
            prompt = asString(options["prompt"]),
            checkPromptInjection = asBoolean(options["checkpromptinjection"]),
            question = asString(options["question"]),
            query = asString(options["query"]),
            mode = asString(options["mode"]),
            modes = asStringList(options["modes"]),
            tag = asString(options["tag"]),
            selectors = asSelectors(options["selectors"]),
            algorithm = asString(options["algorithm"]),
            sql = asString(options["sql"]),
        )
    }

    /** Key lookup ignores case and `_`/`-`, so `check_prompt_injection` still resolves. */
    private fun normalizeKeys(map: Map<*, *>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, value) ->
            val normalized = key?.toString()?.lowercase()?.replace("_", "")?.replace("-", "")
            if (normalized != null && normalized.isNotEmpty()) {
                result[normalized] = value
            }
        }
        return result
    }

    private fun asString(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        else -> value.toString()
    }

    private fun asBoolean(value: Any?): Boolean? = when (value) {
        null -> null
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }

    private fun asInt(value: Any?): Int? = when (value) {
        null -> null
        is Int -> value
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun asStringList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is Iterable<*> -> value.mapNotNull { it?.toString() }
        else -> emptyList()
    }

    private fun asMap(value: Any?): Map<String, Any?>? = when (value) {
        null -> null
        is Map<*, *> -> value.entries
            .filter { it.key != null }
            .associate { it.key.toString() to it.value }
        else -> null
    }

    private fun asViewport(value: Any?): FormatViewport? {
        val map = normalizeKeys(value as? Map<*, *> ?: return null)
        val width = asInt(map["width"]) ?: return null
        val height = asInt(map["height"]) ?: return null
        return FormatViewport(width, height)
    }

    private fun asSelectors(value: Any?): List<AttributeSelector> {
        val entries = value as? Iterable<*> ?: return emptyList()
        return entries.mapNotNull { entry ->
            val map = normalizeKeys(entry as? Map<*, *> ?: return@mapNotNull null)
            val selector = asString(map["selector"])?.trim().orEmpty()
            val attribute = asString(map["attribute"])?.trim().orEmpty()
            if (selector.isEmpty() && attribute.isEmpty()) null
            else AttributeSelector(selector, attribute)
        }
    }
}

/** True when [type] (or a spelling of it) is among the requested formats. */
fun List<PageFormat>.hasFormat(type: String): Boolean = formatOf(type) != null

/** The requested entry for [type], or null when it was not requested. */
fun List<PageFormat>.formatOf(type: String): PageFormat? {
    val canonical = PageFormats.canonicalType(type) ?: return null
    return firstOrNull { it.type == canonical }
}

/** The requested canonical format ids. */
fun List<PageFormat>.formatTypes(): Set<String> = mapTo(linkedSetOf()) { it.type }
