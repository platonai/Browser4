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

import ai.platon.pulsar.images.service.ImageDetector.ImageSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the shared min-width/min-height policy in [ImageDimensionFilter]:
 * one size basis per call, and unknown dimensions are never treated as small.
 */
class ImageDimensionFilterTest {

    /** A 50x50-rendered logo whose source file is 150x150. */
    private val renderedSmallNaturalLarge = ImageSource(
        tagName = "img",
        srcUrl = "enwiki-25.svg",
        resolvedUrl = "https://en.wikipedia.org/static/images/enwiki-25.svg",
        width = 50,
        height = 50,
        naturalWidth = 150,
        naturalHeight = 150,
        loaded = true,
    )

    /** A lazy flag thumbnail: rendered size only, natural size unknown. */
    private val lazyThumbnail = ImageSource(
        tagName = "img",
        srcUrl = "250px-Flag_of_Nepal.svg.png",
        resolvedUrl = "https://upload.wikimedia.org/250px-Flag_of_Nepal.svg.png",
        width = 250,
        height = 305,
        naturalWidth = null,
        naturalHeight = null,
        loaded = false,
    )

    /** The page's 1280 px OG photo: no dimensions are declared anywhere. */
    private val dimensionLessOgImage = ImageSource(
        tagName = "meta",
        srcUrl = "https://upload.wikimedia.org/UN_Members_Flags2.JPG/1280px-UN_Members_Flags2.JPG",
        resolvedUrl = "https://upload.wikimedia.org/UN_Members_Flags2.JPG/1280px-UN_Members_Flags2.JPG",
        alt = "og:image",
    )

    /** A favicon that declares its size in the link element. */
    private val tinyIcon = ImageSource(
        tagName = "link",
        srcUrl = "/static/favicon.ico",
        resolvedUrl = "https://en.wikipedia.org/static/favicon.ico",
        width = 32,
        height = 32,
    )

    @Test
    @DisplayName("rendered basis drops a small rendered image even when its source is large")
    fun testRenderedBasisDropsSmallRenderedImage() {
        assertFalse(
            ImageDimensionFilter.passes(renderedSmallNaturalLarge, minWidth = 100, minHeight = 60, DimensionBasis.RENDERED)
        )
    }

    @Test
    @DisplayName("natural basis keeps the same image when the intrinsic size passes")
    fun testNaturalBasisUsesIntrinsicSize() {
        assertTrue(
            ImageDimensionFilter.passes(renderedSmallNaturalLarge, minWidth = 100, minHeight = 60, DimensionBasis.NATURAL)
        )
    }

    @Test
    @DisplayName("rendered is the default basis, so a call never mixes two criteria")
    fun testRenderedIsTheDefaultBasis() {
        val kept = ImageDimensionFilter.filter(
            listOf(renderedSmallNaturalLarge, lazyThumbnail), minWidth = 100, minHeight = 60
        )
        assertEquals(listOf(lazyThumbnail), kept)
    }

    @Test
    @DisplayName("sources with unknown dimensions are kept, including the page's og:image")
    fun testUnknownDimensionsAreKept() {
        assertTrue(ImageDimensionFilter.passes(dimensionLessOgImage, minWidth = 200, minHeight = 120))

        val kept = ImageDimensionFilter.filter(
            listOf(lazyThumbnail, dimensionLessOgImage, tinyIcon), minWidth = 200, minHeight = 120
        )
        // the known 32x32 icon is dropped, the 250x305 thumbnail and the
        // dimension-less 1280px og:image survive
        assertEquals(listOf(lazyThumbnail, dimensionLessOgImage), kept)
    }

    @Test
    @DisplayName("an unknown dimension on one axis does not drop the source on the other")
    fun testUnknownOnOneAxisKeepsTheSource() {
        val halfUnknown = ImageSource(tagName = "img", width = 50, height = null)

        assertFalse(ImageDimensionFilter.passes(halfUnknown, minWidth = 100, minHeight = null))
        assertTrue(ImageDimensionFilter.passes(halfUnknown, minWidth = null, minHeight = 100))
    }

    @Test
    @DisplayName("null and non-positive minimums disable the filter")
    fun testNonPositiveMinimumsDisableTheFilter() {
        val tiny = ImageSource(tagName = "img", width = 1, height = 1)

        assertTrue(ImageDimensionFilter.passes(tiny, minWidth = null, minHeight = null))
        assertTrue(ImageDimensionFilter.passes(tiny, minWidth = 0, minHeight = 0))
        // the minimum is inclusive: 1px passes "at least 1", 2px does not
        assertTrue(ImageDimensionFilter.passes(tiny, minWidth = 1, minHeight = 1))
        assertFalse(ImageDimensionFilter.passes(tiny, minWidth = 2, minHeight = 2))
    }

    @Test
    @DisplayName("natural basis treats an unloaded image's size as unknown, not as small")
    fun testNaturalBasisKeepsUnloadedImages() {
        assertTrue(ImageDimensionFilter.passes(lazyThumbnail, minWidth = 500, minHeight = 500, DimensionBasis.NATURAL))
        assertNull(ImageDimensionFilter.widthOf(lazyThumbnail, DimensionBasis.NATURAL))
        assertEquals(250, ImageDimensionFilter.widthOf(lazyThumbnail, DimensionBasis.RENDERED))
    }

    @Test
    @DisplayName("DimensionBasis parses its documented ids and rejects anything else")
    fun testDimensionBasisParsing() {
        assertEquals(DimensionBasis.RENDERED, DimensionBasis.fromId("rendered"))
        assertEquals(DimensionBasis.NATURAL, DimensionBasis.fromId(" NATURAL "))
        assertNull(DimensionBasis.fromId("diagonal"))
        assertNull(DimensionBasis.fromId(null))
        assertEquals("rendered|natural", DimensionBasis.ids)
    }
}
