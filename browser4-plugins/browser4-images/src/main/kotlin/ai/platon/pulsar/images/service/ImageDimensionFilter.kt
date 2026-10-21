/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.images.service

/**
 * The size a min-width/min-height filter compares against.
 *
 * Every filter call uses exactly one basis, so a single result never mixes two
 * criteria: the 50×50-rendered logo of a page is dropped by `minWidth=100` on
 * [RENDERED] even though its source file is 150 px wide.
 */
enum class DimensionBasis(val id: String) {
    /**
     * Rendered (layout) size — what the page actually displays. The default:
     * it matches what a user sees and is available for lazy images whose
     * intrinsic size is not known yet.
     */
    RENDERED("rendered"),

    /**
     * Intrinsic (source) size of the image file. Only images that have loaded
     * report it; an unloaded image has an unknown natural size, not a small one.
     */
    NATURAL("natural");

    companion object {
        /** The accepted argument values, for help and error messages. */
        val ids: String get() = DimensionBasis.entries.joinToString("|") { it.id }

        /** Parse a caller-supplied basis id (case-insensitive), or null when unknown. */
        fun fromId(id: String?): DimensionBasis? =
            DimensionBasis.entries.firstOrNull { it.id.equals(id?.trim(), true) }
    }
}

/**
 * The min-width/min-height policy shared by DOM detection, `image.detectImages`
 * and `image.downloadAll`.
 *
 * Two rules keep the filter predictable:
 *
 * 1. **One basis per call** — [DimensionBasis.RENDERED] by default, or the
 *    intrinsic size when the caller explicitly asks for `natural`.
 * 2. **Unknown is not small** — a source whose size is unknown on the filtered
 *    axis (an OG/Twitter meta image, a `<source>` candidate, an unloaded image
 *    with no layout box) passes the filter. Treating "unknown" as `0` silently
 *    dropped the biggest image of a page — a 1280 px `og:image` — while keeping
 *    genuinely small sources.
 */
object ImageDimensionFilter {

    /**
     * Keep the sources that pass the filter.
     *
     * @param sources detected sources
     * @param minWidth minimum width in pixels, or null/&le;0 for no width filter
     * @param minHeight minimum height in pixels, or null/&le;0 for no height filter
     * @param basis size basis compared against
     */
    fun filter(
        sources: List<ImageDetector.ImageSource>,
        minWidth: Int?,
        minHeight: Int?,
        basis: DimensionBasis = DimensionBasis.RENDERED,
    ): List<ImageDetector.ImageSource> = sources.filter { passes(it, minWidth, minHeight, basis) }

    /**
     * Whether [source] passes the filter: unknown dimensions pass, known ones
     * must reach the minimum on every filtered axis.
     */
    fun passes(
        source: ImageDetector.ImageSource,
        minWidth: Int?,
        minHeight: Int?,
        basis: DimensionBasis = DimensionBasis.RENDERED,
    ): Boolean {
        val width = widthOf(source, basis)
        if (minWidth != null && minWidth > 0 && width != null && width < minWidth) return false

        val height = heightOf(source, basis)
        if (minHeight != null && minHeight > 0 && height != null && height < minHeight) return false

        return true
    }

    /** The width of [source] on [basis], or null when it is unknown. */
    fun widthOf(source: ImageDetector.ImageSource, basis: DimensionBasis): Int? = when (basis) {
        DimensionBasis.RENDERED -> source.width
        DimensionBasis.NATURAL -> source.naturalWidth
    }

    /** The height of [source] on [basis], or null when it is unknown. */
    fun heightOf(source: ImageDetector.ImageSource, basis: DimensionBasis): Int? = when (basis) {
        DimensionBasis.RENDERED -> source.height
        DimensionBasis.NATURAL -> source.naturalHeight
    }
}
