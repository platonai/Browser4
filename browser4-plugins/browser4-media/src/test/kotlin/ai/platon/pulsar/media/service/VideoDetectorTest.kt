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
package ai.platon.pulsar.media.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [VideoDetector] JSON parsing and result post-processing.
 *
 * Creates a test subclass that exposes [VideoDetector.parseResult] for direct testing,
 * avoiding the need to mock WebDriver/suspend functions. The injected JavaScript probe
 * itself cannot run without a browser, so the derived facts it feeds (frame attribution,
 * element counts, MIME retention, HLS/DASH linkage and data-URI summaries) are pinned here.
 */
class VideoDetectorTest {

    private lateinit var detector: TestVideoDetector

    @BeforeEach
    fun setUp() {
        detector = TestVideoDetector()
    }

    @Test
    @DisplayName("parse empty JSON returns empty list")
    fun testParseEmptyJson() {
        assertEquals(0, detector.parse("[]").size)
    }

    @Test
    @DisplayName("parse null returns empty list")
    fun testParseNull() {
        assertEquals(0, detector.parse(null).size)
    }

    @Test
    @DisplayName("parse blank string returns empty list")
    fun testParseBlank() {
        assertEquals(0, detector.parse("   ").size)
    }

    @Test
    @DisplayName("parse malformed JSON returns empty list")
    fun testParseMalformedJson() {
        assertEquals(0, detector.parse("not valid json {{{").size)
    }

    @Test
    @DisplayName("parse video tag JSON returns correct VideoSource")
    fun testParseVideoTag() {
        val result = detector.parse("""[{"tagName":"video","srcUrl":"https://example.com/video.mp4","resolvedUrl":"https://example.com/video.mp4","type":null,"width":640,"height":360,"hasControls":true,"posterUrl":"https://example.com/poster.jpg","isHls":false,"isDash":false,"isIframe":false}]""")

        assertEquals(1, result.size)
        val video = result[0]
        assertEquals("video", video.tagName)
        assertEquals("https://example.com/video.mp4", video.resolvedUrl)
        assertEquals(640, video.width)
        assertEquals(360, video.height)
        assertTrue(video.hasControls)
        assertEquals("https://example.com/poster.jpg", video.posterUrl)
        assertFalse(video.isHls)
        assertFalse(video.isIframe)
    }

    @Test
    @DisplayName("parse HLS source returns isHls=true")
    fun testParseHlsSource() {
        val result = detector.parse("""[{"tagName":"source","srcUrl":"https://cdn.com/video.m3u8","resolvedUrl":"https://cdn.com/video.m3u8","type":"application/vnd.apple.mpegurl","width":1920,"height":1080,"hasControls":true,"posterUrl":null,"isHls":true,"isDash":false,"isIframe":false}]""")

        assertEquals(1, result.size)
        assertTrue(result[0].isHls)
        assertEquals("application/vnd.apple.mpegurl", result[0].type)
    }

    @Test
    @DisplayName("parse iframe embed returns isIframe=true")
    fun testParseIframeEmbed() {
        val result = detector.parse("""[{"tagName":"iframe","srcUrl":"https://www.youtube.com/embed/dQw4w9WgXcQ","resolvedUrl":"https://www.youtube.com/embed/dQw4w9WgXcQ","type":null,"width":560,"height":315,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":true}]""")

        assertEquals(1, result.size)
        assertTrue(result[0].isIframe)
        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ", result[0].resolvedUrl)
    }

    @Test
    @DisplayName("parse deduplicates by resolved URL")
    fun testParseDeduplicates() {
        val result = detector.parse("""[{"tagName":"video","srcUrl":"https://x.com/v.mp4","resolvedUrl":"https://x.com/v.mp4","type":null,"width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false},{"tagName":"a","srcUrl":"https://x.com/v.mp4","resolvedUrl":"https://x.com/v.mp4","type":null,"width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false}]""")

        assertEquals(1, result.size)
        assertEquals(2, result[0].elementCount)
    }

    @Test
    @DisplayName("parse multiple distinct videos")
    fun testParseMultipleVideos() {
        val result = detector.parse("""[{"tagName":"video","srcUrl":null,"resolvedUrl":"https://a.com/1.mp4","type":null,"width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false},{"tagName":"video","srcUrl":null,"resolvedUrl":"https://a.com/2.webm","type":null,"width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false},{"tagName":"iframe","srcUrl":"https://vimeo.com/123","resolvedUrl":"https://vimeo.com/123","type":null,"width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":true}]""")

        assertEquals(3, result.size)
    }

    @Test
    @DisplayName("parse HLS and DASH flags correctly")
    fun testParseHlsAndDash() {
        val result = detector.parse("""[{"tagName":"source","srcUrl":"https://cdn.com/stream.m3u8","resolvedUrl":"https://cdn.com/stream.m3u8","type":"application/vnd.apple.mpegurl","width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":true,"isDash":false,"isIframe":false},{"tagName":"source","srcUrl":"https://cdn.com/manifest.mpd","resolvedUrl":"https://cdn.com/manifest.mpd","type":"application/dash+xml","width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":true,"isIframe":false}]""")

        assertEquals(2, result.size)
        assertTrue(result.any { it.isHls })
        assertTrue(result.any { it.isDash })
    }

    @Test
    @DisplayName("parse detects isIframe flag correctly")
    fun testParseIsIframeFlag() {
        val result = detector.parse("""[{"tagName":"iframe","srcUrl":"https://player.vimeo.com/video/123","resolvedUrl":"https://player.vimeo.com/video/123","type":null,"width":640,"height":360,"hasControls":true,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":true}]""")

        assertEquals(1, result.size)
        assertTrue(result[0].isIframe)
        assertEquals(640, result[0].width)
    }

    // ---- Issue 4: frame attribution ----

    @Test
    @DisplayName("same-origin iframe entries carry frame attribution")
    fun testFrameAttribution() {
        val result = detector.parse("""[
            {"tagName":"video","resolvedUrl":"https://site.test/mov_bbb.mp4","hasControls":true,"width":320,"height":176},
            {"tagName":"video","resolvedUrl":"data:video/webm;base64,AAAA","isIframe":false,"frameUrl":"about:blank","frameDepth":1}
        ]""")

        assertEquals(2, result.size)
        val pageVideo = result[0]
        assertEquals(0, pageVideo.frameDepth)
        assertNull(pageVideo.frameUrl)
        val adVideo = result[1]
        assertEquals("about:blank", adVideo.frameUrl)
        assertEquals(1, adVideo.frameDepth)
    }

    @Test
    @DisplayName("frame depth is derived from the frame URL when the probe omits it")
    fun testFrameDepthDerivedFromFrameUrl() {
        val result = detector.parse("""[{"tagName":"video","resolvedUrl":"https://site.test/clip.mp4","frameUrl":"https://site.test/tryit.asp?filename=tryhtml5_video"}]""")

        assertEquals(1, result.single().frameDepth)
        assertEquals("https://site.test/tryit.asp?filename=tryhtml5_video", result.single().frameUrl)
    }

    @Test
    @DisplayName("a blank frame URL is treated as the top-level document")
    fun testBlankFrameUrlIsTopLevel() {
        val result = detector.parse("""[{"tagName":"video","resolvedUrl":"https://site.test/clip.mp4","frameUrl":"   ","frameDepth":1}]""")

        assertNull(result.single().frameUrl)
        assertEquals(0, result.single().frameDepth)
    }

    @Test
    @DisplayName("includeIframes=false drops every nested-frame entry")
    fun testExcludeIframes() {
        val json = """[
            {"tagName":"video","resolvedUrl":"https://site.test/page.mp4","width":320,"height":176},
            {"tagName":"video","resolvedUrl":"https://site.test/frame.mp4","frameUrl":"about:blank","frameDepth":1}
        ]"""

        val all = detector.parse(json)
        assertEquals(2, all.size)

        val topOnly = detector.parse(json, includeIframes = false)
        assertEquals(1, topOnly.size)
        assertEquals("https://site.test/page.mp4", topOnly.single().resolvedUrl)
        assertEquals(0, topOnly.single().frameDepth)
    }

    // ---- Issue 5: URL dedup ----

    @Test
    @DisplayName("deduplication keeps the declared MIME type and counts the elements")
    fun testDedupKeepsMimeTypeAndCountsElements() {
        // The probe emits <video> before its <source> children, and the <source> is the
        // entry that declares the MIME type: a first-seen-wins merge would drop it.
        val result = detector.parse("""[
            {"tagName":"video","srcUrl":null,"resolvedUrl":"https://site.test/mov_bbb.mp4","type":null,"width":320,"height":176,"hasControls":true},
            {"tagName":"source","srcUrl":"mov_bbb.mp4","resolvedUrl":"https://site.test/mov_bbb.mp4","type":"video/mp4","width":320,"height":176,"hasControls":true},
            {"tagName":"video","srcUrl":null,"resolvedUrl":"https://site.test/mov_bbb.mp4","type":null,"width":320,"height":176,"hasControls":true},
            {"tagName":"source","srcUrl":"mov_bbb.mp4","resolvedUrl":"https://site.test/mov_bbb.mp4","type":"video/mp4","width":320,"height":176,"hasControls":true}
        ]""")

        assertEquals(1, result.size)
        val entry = result.single()
        assertEquals("video", entry.tagName)
        assertEquals("video/mp4", entry.type)
        assertEquals(320, entry.width)
        assertEquals(176, entry.height)
        assertTrue(entry.hasControls)
        assertEquals(4, entry.elementCount)
    }

    @Test
    @DisplayName("the richer entry wins regardless of which field it carries")
    fun testDedupMergesRicherFields() {
        val result = detector.parse("""[
            {"tagName":"source","resolvedUrl":"https://site.test/clip.mp4","type":null,"width":null,"height":null,"posterUrl":null},
            {"tagName":"source","resolvedUrl":"https://site.test/clip.mp4","type":"video/mp4","width":640,"height":360,"posterUrl":"https://site.test/p.jpg"}
        ]""")

        val entry = result.single()
        assertEquals("video/mp4", entry.type)
        assertEquals(640, entry.width)
        assertEquals(360, entry.height)
        assertEquals("https://site.test/p.jpg", entry.posterUrl)
        assertEquals(2, entry.elementCount)
    }

    @Test
    @DisplayName("the same URL in another frame is not collapsed")
    fun testSameUrlInAnotherFrameIsNotCollapsed() {
        val result = detector.parse("""[
            {"tagName":"video","resolvedUrl":"https://site.test/mov_bbb.mp4","width":320,"height":176},
            {"tagName":"video","resolvedUrl":"https://site.test/mov_bbb.mp4","frameUrl":"https://site.test/tryit.asp","frameDepth":1}
        ]""")

        assertEquals(2, result.size)
        assertEquals(listOf(0, 1), result.map { it.frameDepth })
        assertEquals(1, result[1].elementCount)
    }

    @Test
    @DisplayName("entries without any URL are kept individually")
    fun testEntriesWithoutUrlAreKept() {
        val result = detector.parse("""[
            {"tagName":"video","srcUrl":null,"resolvedUrl":null},
            {"tagName":"video","srcUrl":null,"resolvedUrl":null}
        ]""")

        assertEquals(2, result.size)
        assertEquals(1, result[0].elementCount)
    }

    // ---- Issue 6: MSE / HLS detection ----

    @Test
    @DisplayName("HLS manifest is linked to the opaque blob source playing it")
    fun testHlsManifestLinkedToBlobSource() {
        val result = detector.parse("""[
            {"tagName":"video","resolvedUrl":"blob:https://hlsjs.video-dev.org/8e7f22de-1234","isHls":false,"isDash":false},
            {"tagName":"manifest","resolvedUrl":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8","type":"application/vnd.apple.mpegurl","isHls":true,"isDash":false}
        ]""")

        assertEquals(2, result.size)
        val video = result.first { it.tagName == "video" }
        assertTrue(video.isOpaqueSource)
        assertTrue(video.isHls)
        assertFalse(video.isDash)
        assertEquals("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", video.manifestUrl)
        assertEquals("application/vnd.apple.mpegurl", video.type)
    }

    @Test
    @DisplayName("opaque blob source without a manifest is flagged but not marked HLS")
    fun testOpaqueBlobWithoutManifest() {
        val result = detector.parse("""[{"tagName":"video","resolvedUrl":"blob:https://example.com/abc"}]""")

        val entry = result.single()
        assertTrue(entry.isOpaqueSource)
        assertFalse(entry.isHls)
        assertNull(entry.manifestUrl)
    }

    @Test
    @DisplayName("mediasource: URLs are flagged as opaque as well")
    fun testMediaSourceUrlIsOpaque() {
        val result = detector.parse("""[{"tagName":"video","resolvedUrl":"mediasource:https://example.com/stream"}]""")

        assertTrue(result.single().isOpaqueSource)
    }

    @Test
    @DisplayName("a manifest in the same frame wins over a top-level manifest")
    fun testManifestPrefersSameFrame() {
        val result = detector.parse("""[
            {"tagName":"manifest","resolvedUrl":"https://cdn.test/top.m3u8","type":"application/vnd.apple.mpegurl","isHls":true},
            {"tagName":"manifest","resolvedUrl":"https://ads.test/ad.m3u8","type":"application/vnd.apple.mpegurl","isHls":true,"frameUrl":"about:blank","frameDepth":1},
            {"tagName":"video","resolvedUrl":"blob:https://site.test/1","frameUrl":"about:blank","frameDepth":1}
        ]""")

        val video = result.first { it.tagName == "video" }
        assertEquals("https://ads.test/ad.m3u8", video.manifestUrl)
    }

    @Test
    @DisplayName("a DASH manifest marks the opaque source as DASH")
    fun testDashManifestLinked() {
        val result = detector.parse("""[
            {"tagName":"video","resolvedUrl":"blob:https://dash.test/1"},
            {"tagName":"manifest","resolvedUrl":"https://cdn.test/stream.mpd","type":"application/dash+xml","isDash":true,"isHls":false}
        ]""")

        val video = result.first { it.tagName == "video" }
        assertTrue(video.isDash)
        assertFalse(video.isHls)
        assertEquals("https://cdn.test/stream.mpd", video.manifestUrl)
    }

    @Test
    @DisplayName("a download-able HLS element URL is not linked to a manifest twice")
    fun testDirectHlsUrlKeepsItsOwnManifest() {
        val result = detector.parse("""[
            {"tagName":"video","resolvedUrl":"https://cdn.test/direct.m3u8"},
            {"tagName":"manifest","resolvedUrl":"https://cdn.test/other.m3u8","isHls":true}
        ]""")

        val video = result.first { it.tagName == "video" }
        assertTrue(video.isHls)
        assertFalse(video.isOpaqueSource)
        assertNull(video.manifestUrl)
    }

    @Test
    @DisplayName("HLS/DASH flags are re-derived from the URL when the probe misses them")
    fun testHlsFlagDerivedFromUrl() {
        val result = detector.parse("""[{"tagName":"video","resolvedUrl":"https://cdn.test/live.m3u8?token=1","isHls":false}]""")

        assertTrue(result.single().isHls)
    }

    // ---- Issue 9: data: URIs ----

    @Test
    @DisplayName("long base64 data URIs are summarised with their decoded size")
    fun testLongDataUriIsSummarised() {
        val payload = "A".repeat(1200)
        val result = detector.parse(
            """[{"tagName":"video","srcUrl":"data:video/mp4;base64,$payload","resolvedUrl":"data:video/mp4;base64,$payload"}]"""
        )

        val entry = result.single()
        assertEquals("data:video/mp4;base64,<900 B>", entry.srcUrl)
        assertEquals("data:video/mp4;base64,<900 B>", entry.resolvedUrl)
        assertEquals(1, entry.elementCount)
    }

    @Test
    @DisplayName("short data URIs stay verbatim so a caller can still decode them")
    fun testShortDataUriIsKept() {
        val uri = "data:video/webm;base64,AAAA"
        val result = detector.parse("""[{"tagName":"source","resolvedUrl":"$uri"}]""")

        assertEquals(uri, result.single().resolvedUrl)
    }

    @Test
    @DisplayName("an already summarised data URI is not re-summarised")
    fun testSummarisedDataUriIsIdempotent() {
        val uri = "data:video/mp4;base64,<900 B>"
        val result = detector.parse("""[{"tagName":"source","resolvedUrl":"$uri"}]""")

        assertEquals(uri, result.single().resolvedUrl)
    }

    // ---- The injected script: facts the probe has to collect ----

    @Test
    @DisplayName("detection script reports frame attribution for every scanned document")
    fun testDetectionScriptReportsFrames() {
        val script = VideoDetector.DETECTION_SCRIPT

        assertTrue(script.contains("frameUrl"), "probe must tag entries with their frame URL")
        assertTrue(script.contains("frameDepth"), "probe must tag entries with their frame depth")
        assertTrue(script.contains("__b4_scan_videos(frameDoc, frameHref, 1)"), "probe must scan same-origin frames")
    }

    @Test
    @DisplayName("detection script collects HLS/DASH manifests beyond Hls.instances")
    fun testDetectionScriptCollectsManifests() {
        val script = VideoDetector.DETECTION_SCRIPT

        assertTrue(script.contains("getEntriesByType('resource')"), "probe must use network hints for MSE streams")
        assertTrue(script.contains("__b4_collect_manifests"), "probe must collect manifest candidates")
        assertTrue(script.contains("input[value]"), "probe must read player configuration inputs")
    }

    @Test
    @DisplayName("detection script summarises data URIs before serialising them")
    fun testDetectionScriptSummarisesDataUris() {
        val script = VideoDetector.DETECTION_SCRIPT

        assertTrue(script.contains("__b4_short_src"), "probe must summarise long data: URIs")
        assertTrue(
            script.contains("__b4_data_uri_limit = ${MediaUtils.DATA_URI_VERBATIM_LIMIT}"),
            "probe limit must match MediaUtils.DATA_URI_VERBATIM_LIMIT"
        )
    }

    // ---- Reported page scenarios: the probe output, end to end ----

    /**
     * Probe output for a w3schools `html5_video.asp`-shaped page: two page videos whose `<source>`
     * children repeat the URLs, plus a Viously-style `about:blank` video-ad iframe using data URIs.
     * Captured from the injected script itself, so the entries (already summarised data URIs, every
     * element reported, frame attribution present) match the real CDP payload.
     */
    private val w3schoolsProbeJson = """
        [
        {"tagName":"video","srcUrl":"mov_bbb.mp4","resolvedUrl":"https://www.w3schools.com/html/mov_bbb.mp4","type":null,"width":320,"height":176,"hasControls":true,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false,"frameUrl":null,"frameDepth":0},
        {"tagName":"source","srcUrl":"https://www.w3schools.com/html/mov_bbb.mp4","resolvedUrl":"https://www.w3schools.com/html/mov_bbb.mp4","type":"video/mp4","width":320,"height":176,"hasControls":true,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false,"frameUrl":null,"frameDepth":0},
        {"tagName":"source","srcUrl":"https://www.w3schools.com/html/mov_bbb.ogg","resolvedUrl":"https://www.w3schools.com/html/mov_bbb.ogg","type":"video/ogg","width":320,"height":176,"hasControls":true,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false,"frameUrl":null,"frameDepth":0},
        {"tagName":"video","srcUrl":"mov_bbb.mp4","resolvedUrl":"https://www.w3schools.com/html/mov_bbb.mp4","type":null,"width":320,"height":176,"hasControls":true,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false,"frameUrl":null,"frameDepth":0},
        {"tagName":"source","srcUrl":"https://www.w3schools.com/html/mov_bbb.mp4","resolvedUrl":"https://www.w3schools.com/html/mov_bbb.mp4","type":"video/mp4","width":320,"height":176,"hasControls":true,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false,"frameUrl":null,"frameDepth":0},
        {"tagName":"source","srcUrl":"https://www.w3schools.com/html/mov_bbb.ogg","resolvedUrl":"https://www.w3schools.com/html/mov_bbb.ogg","type":"video/ogg","width":320,"height":176,"hasControls":true,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false,"frameUrl":null,"frameDepth":0},
        {"tagName":"video","srcUrl":"data:video/mp4;base64,<900 B>","resolvedUrl":"data:video/mp4;base64,<900 B>","type":null,"width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false,"frameUrl":"about:blank","frameDepth":1},
        {"tagName":"source","srcUrl":"data:video/webm;base64,<225 B>","resolvedUrl":"data:video/webm;base64,<225 B>","type":"video/webm","width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false,"frameUrl":"about:blank","frameDepth":1}
        ]
    """.trimIndent()

    @Test
    @DisplayName("w3schools-shaped probe output keeps the MIME type, element counts and ad-frame attribution")
    fun testReportedPageProbeOutput() {
        val result = detector.parse(w3schoolsProbeJson)

        assertEquals(4, result.size, "expected mp4, ogg and the two ad-frame entries")
        val pageEntries = result.filter { it.frameDepth == 0 }
        assertEquals(2, pageEntries.size)

        val mp4 = pageEntries.first { it.resolvedUrl?.endsWith(".mp4") == true }
        assertEquals("video", mp4.tagName)
        assertEquals("video/mp4", mp4.type, "the <source> MIME type must survive deduplication")
        assertEquals(4, mp4.elementCount, "2 <video> + 2 matching <source> elements")

        val ogg = pageEntries.first { it.resolvedUrl?.endsWith(".ogg") == true }
        assertEquals("video/ogg", ogg.type)
        assertEquals(2, ogg.elementCount)

        val adEntries = result.filter { it.frameDepth == 1 }
        assertEquals(2, adEntries.size)
        assertTrue(adEntries.all { it.frameUrl == "about:blank" })
        assertEquals("data:video/mp4;base64,<900 B>", adEntries.first { it.tagName == "video" }.resolvedUrl)
        assertEquals("video/webm", adEntries.first { it.tagName == "source" }.type)
    }

    @Test
    @DisplayName("w3schools-shaped probe output can be reduced to the page's own videos")
    fun testReportedPageProbeOutputWithoutIframes() {
        val result = detector.parse(w3schoolsProbeJson, includeIframes = false)

        assertEquals(2, result.size, "the ad-frame data: entries must be droppable")
        assertTrue(result.none { it.resolvedUrl?.startsWith("data:") == true })
        assertTrue(result.all { it.frameDepth == 0 })
    }

    /**
     * Probe output for the hls.js demo: the DOM only exposes a `blob:` URL, the manifest is found
     * through the network hints / page text.
     */
    private val hlsJsProbeJson = """
        [
        {"tagName":"video","srcUrl":"blob:https://hlsjs.video-dev.org/8e7f22de-1234","resolvedUrl":"blob:https://hlsjs.video-dev.org/8e7f22de-1234","type":null,"width":320,"height":176,"hasControls":true,"posterUrl":null,"isHls":false,"isDash":false,"isIframe":false,"frameUrl":null,"frameDepth":0},
        {"tagName":"manifest","srcUrl":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8","resolvedUrl":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8","type":"application/vnd.apple.mpegurl","width":null,"height":null,"hasControls":false,"posterUrl":null,"isHls":true,"isDash":false,"isIframe":false,"frameUrl":null,"frameDepth":0}
        ]
    """.trimIndent()

    @Test
    @DisplayName("hls.js-shaped probe output reports the stream as HLS with a usable manifest URL")
    fun testReportedHlsJsProbeOutput() {
        val result = detector.parse(hlsJsProbeJson)

        assertEquals(2, result.size, "the blob entry and the manifest entry stay separate")
        val element = result.first { it.tagName == "video" }
        assertTrue(element.isHls, "an MSE blob source playing an HLS manifest is an HLS stream")
        assertTrue(element.isOpaqueSource, "the blob: URL itself is not downloadable")
        assertEquals("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", element.manifestUrl)
        assertEquals("application/vnd.apple.mpegurl", element.type)
    }
}

/**
 * Test subclass of VideoDetector that exposes [VideoDetector.parseResult] for direct testing.
 */
private open class TestVideoDetector : VideoDetector() {
    fun parse(json: String?): List<VideoDetector.VideoSource> = parseResult(json)

    fun parse(json: String?, includeIframes: Boolean): List<VideoDetector.VideoSource> =
        parseResult(json, includeIframes)
}
