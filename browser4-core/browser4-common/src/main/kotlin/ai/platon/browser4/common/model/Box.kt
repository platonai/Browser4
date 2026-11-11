package ai.platon.browser4.common.model

/**
 * The coordinate space a [Box]'s numbers are expressed in.
 *
 * Three spaces exist in this project and mixing them is silent: every number
 * looks valid, and the mismatch only shows up as a pointer landing tens or
 * thousands of pixels away.
 *
 * @property wireName The spelling used in JSON/HTML output (`boxSpace`).
 */
enum class BoxSpace(val wireName: String) {
    /**
     * Origin = the document's top-left corner.  Unaffected by scrolling, which
     * makes it the space of `snapshot --boxes`, `getAnnotatedHTML()` `vi` boxes
     * (base 4.11.31+) and screenshot clips.
     */
    DOCUMENT("document"),

    /**
     * Origin = the main-frame layout viewport's top-left corner.  The space of
     * `getBoundingClientRect()`, `elementFromPoint()` and CDP
     * `Input.dispatchMouseEvent`, and therefore of every `mousemove`/`click`
     * coordinate and of `get box`.
     */
    VIEWPORT("viewport"),

    /**
     * Origin = the body border box's top-left corner.  The pre-2026-11 `vi`
     * implementation detail; kept so historical exports can be labelled instead
     * of being mistaken for [DOCUMENT] values.
     */
    BODY("body");

    companion object {
        /** Parse a wire name (`document`, `viewport`, `body`), or null when unknown/blank. */
        @JvmStatic
        fun fromWireName(value: String?): BoxSpace? {
            val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

/**
 * The wire contract that labels coordinate output with its [BoxSpace].
 *
 * Versioning rules:
 *
 * - [SCHEMA_VERSION] 2 introduced [FIELD_SPACE] / [FIELD_SCHEMA_VERSION] /
 *   [FIELD_SCROLL_TOP].  A payload **without** [FIELD_SPACE] is schema 1 and its
 *   boxes are [BoxSpace.BODY] (the pre-2026-11 runtime): that is the only reason
 *   the enum keeps [BoxSpace.BODY] at all.
 * - Readers must treat a missing [FIELD_SPACE] as `body`, never as the space the
 *   current runtime produces.
 */
object BoxContract {
    /** Current output schema; 1 (the implicit legacy schema) had no space field. */
    const val SCHEMA_VERSION = 2

    /** Field carrying the [BoxSpace.wireName] of the sibling box/vi numbers. */
    const val FIELD_SPACE = "boxSpace"

    /** Field carrying [SCHEMA_VERSION]. */
    const val FIELD_SCHEMA_VERSION = "schemaVersion"

    /** Field carrying the scroll offset at capture/measure time, when known. */
    const val FIELD_SCROLL_TOP = "scrollTopAtCapture"

    /**
     * The space a payload's boxes are in, reading the field when present and
     * falling back to [BoxSpace.BODY] for schema-1 payloads.
     */
    @JvmStatic
    fun spaceOf(payload: Map<*, *>): BoxSpace =
        BoxSpace.fromWireName(payload[FIELD_SPACE]?.toString()) ?: BoxSpace.BODY
}

/**
 * A bounding box that carries the [space] its coordinates are in.
 *
 * A bare rect (the base library's `RectD`, a `vi` string, four doubles) cannot
 * say which space it belongs to, so every consumer has to assume one — the root
 * cause of the coordinate bugs documented in
 * `docs-dev/copilot/20261109-mouse-pointer-issues-and-pitfalls.md`. This type
 * makes the space part of the value, so a document-space box cannot be passed
 * where a viewport-space one is expected without an explicit conversion.
 *
 * @property x Left edge in [space]'s pixels.
 * @property y Top edge in [space]'s pixels.
 * @property width Width in CSS pixels (space-independent).
 * @property height Height in CSS pixels (space-independent).
 */
data class Box(
    val space: BoxSpace,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    /**
     * The same box in viewport coordinates.
     *
     * Only [BoxSpace.DOCUMENT] can be converted: the document→viewport offset is
     * exactly the window scroll, which the caller has. A [BoxSpace.BODY] box is
     * the output of a retired implementation whose body offset is not recorded
     * here, so it is refused rather than silently treated as one of the others.
     *
     * @throws IllegalArgumentException for a [BoxSpace.BODY] box
     */
    fun toViewport(scrollX: Double, scrollY: Double): Box = when (space) {
        BoxSpace.VIEWPORT -> this
        BoxSpace.DOCUMENT -> copy(space = BoxSpace.VIEWPORT, x = x - scrollX, y = y - scrollY)
        BoxSpace.BODY -> throw IllegalArgumentException(
            "A body-relative box cannot be converted without the body offset; " +
                "re-derive it from a document-space capture"
        )
    }

    /**
     * The same box in document coordinates.
     *
     * @throws IllegalArgumentException for a [BoxSpace.BODY] box (see [toViewport]).
     */
    fun toDocument(scrollX: Double, scrollY: Double): Box = when (space) {
        BoxSpace.DOCUMENT -> this
        BoxSpace.VIEWPORT -> copy(space = BoxSpace.DOCUMENT, x = x + scrollX, y = y + scrollY)
        BoxSpace.BODY -> throw IllegalArgumentException(
            "A body-relative box cannot be converted without the body offset; " +
                "re-derive it from a document-space capture"
        )
    }

    /** Whether the box overlaps a viewport of [viewportWidth] × [viewportHeight]. */
    fun overlapsViewport(viewportWidth: Double, viewportHeight: Double): Boolean =
        width > 0.0 && height > 0.0 &&
            x < viewportWidth && y < viewportHeight && x + width > 0.0 && y + height > 0.0

    /**
     * The `vi`-compatible `"x y w h"` rendering (integers, as the runtime writes
     * legacy-space boxes).
     */
    fun format(): String = "${x.toInt()} ${y.toInt()} ${width.toInt()} ${height.toInt()}"

    /** The JSON-ready fields, all four numbers plus the space they are in. */
    fun toWireMap(): Map<String, Any> = linkedMapOf(
        "x" to x,
        "y" to y,
        "width" to width,
        "height" to height,
        "boxSpace" to space.wireName,
    )

    companion object {
        /**
         * Parse a `vi` attribute value written by the runtime.
         *
         * Three wire formats are auto-detected (the runtime has used all three):
         * base-36 comma separated (`"3g,cp,5k,1f"`), plain comma separated
         * (`"0,0,1920,1080"`) and legacy whitespace separated
         * (`"123.5 456.7 200.3 50.8"`).
         *
         * @param raw The attribute text.
         * @param space The space the producer wrote the numbers in. It is a
         *   required argument on purpose: parsing must not silently default to
         *   the current runtime's space, because old captures used [BoxSpace.BODY].
         * @return The box, or null when [raw] is blank or unparseable.
         */
        @JvmStatic
        fun parse(raw: String, space: BoxSpace): Box? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val parts: List<String> = if (',' in trimmed) {
                trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                trimmed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            }
            if (parts.size < 4) return null

            // Base-36 detection: any part contains a letter a–z/a–z.  Pure-digit
            // strings stay decimal so existing comma-separated fixtures keep
            // their meaning.
            val isBase36 = parts.any { part -> part.any { c -> c in 'a'..'z' || c in 'A'..'Z' } }

            val nums = parts.take(4).map {
                if (isBase36) it.toIntOrNull(36)?.toDouble() else it.toDoubleOrNull()
            }
            if (nums.any { it == null }) return null

            return Box(space, nums[0]!!, nums[1]!!, nums[2]!!, nums[3]!!)
        }
    }
}
