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

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Detects video elements and media source URLs on a web page via CDP JavaScript evaluation.
 *
 * Detection operates entirely via DOM JS evaluation — it never touches the network layer,
 * so [BlockRule] blocking of `ResourceType.MEDIA` is irrelevant.
 *
 * The injected JavaScript probe only collects raw page facts (elements, URLs, frame
 * documents, manifest hints); every derived fact — frame attribution, element counts,
 * MIME retention, opaque-MSE linkage and data-URI summaries — is computed by
 * [parseResult] so it can be unit tested without a browser.
 */
open class VideoDetector {
    private val logger = getLogger(VideoDetector::class)

    /**
     * A detected video source on a web page.
     */
    data class VideoSource(
        /** HTML tag name: "video", "source", "a", "iframe", "manifest", "hlsjs", "dashjs" */
        @JsonProperty("tagName")
        val tagName: String = "",

        /** Raw src attribute value */
        @JsonProperty("srcUrl")
        val srcUrl: String? = null,

        /** Resolved absolute URL (currentSrc for <video>, src for others) */
        @JsonProperty("resolvedUrl")
        val resolvedUrl: String? = null,

        /** MIME type if available from type attribute */
        @JsonProperty("type")
        val type: String? = null,

        /** Display width in pixels (0 if unknown) */
        @JsonProperty("width")
        val width: Int? = null,

        /** Display height in pixels (0 if unknown) */
        @JsonProperty("height")
        val height: Int? = null,

        /** Whether the video element has the controls attribute */
        @JsonProperty("hasControls")
        val hasControls: Boolean = false,

        /** Poster image URL if set */
        @JsonProperty("posterUrl")
        val posterUrl: String? = null,

        /** Whether this is an HLS (m3u8) stream */
        @JsonProperty("isHls")
        val isHls: Boolean = false,

        /** Whether this is a DASH (mpd) stream */
        @JsonProperty("isDash")
        val isDash: Boolean = false,

        /** Whether this is embedded in an iframe (e.g., YouTube, Vimeo) */
        @JsonProperty("isIframe")
        val isIframe: Boolean = false,

        /**
         * URL of the document this entry was found in.
         *
         * `null` means the top-level document; anything else is a readable
         * (same-origin) iframe, e.g. `about:blank` for a video-ad unit or the
         * embed URL of an `#iframeResult` test frame.
         */
        @JsonProperty("frameUrl")
        val frameUrl: String? = null,

        /** Document nesting depth: 0 for the top-level document, 1 for a same-origin iframe */
        @JsonProperty("frameDepth")
        val frameDepth: Int = 0,

        /**
         * Number of DOM elements collapsed into this entry by URL deduplication.
         *
         * A `<video>` plus its matching `<source>` child share one URL and merge into
         * one entry, so this is how many elements the entry stands for (never 0).
         */
        @JsonProperty("elementCount")
        val elementCount: Int = 1,

        /**
         * The source URL is an opaque Media Source Extensions handle (`blob:` /
         * `mediasource:`). Such a URL only exists inside the page and cannot be
         * downloaded; see [manifestUrl] for the stream it plays.
         */
        @JsonProperty("isOpaqueSource")
        val isOpaqueSource: Boolean = false,

        /**
         * Underlying HLS/DASH manifest URL when the element itself only exposes an
         * opaque MSE URL (the standard hls.js / dash.js deployment).
         */
        @JsonProperty("manifestUrl")
        val manifestUrl: String? = null,
    )

    /**
     * Scan the current page for all video sources, including same-origin iframes.
     *
     * @param driver the WebDriver connected to the page
     * @return list of detected video sources, or empty list on error
     */
    open suspend fun detect(driver: WebDriver): List<VideoSource> = detect(driver, includeIframes = true)

    /**
     * Scan the current page for all video sources.
     *
     * @param driver the WebDriver connected to the page
     * @param includeIframes when `false`, entries found inside same-origin iframes
     *   (video-ad creatives, embedded players) are dropped and only the top-level
     *   document is reported
     * @return list of detected video sources, or empty list on error
     */
    open suspend fun detect(driver: WebDriver, includeIframes: Boolean): List<VideoSource> {
        return try {
            val result = driver.evaluate(DETECTION_SCRIPT)
            parseResult(result, includeIframes)
        } catch (e: Exception) {
            logger.warn("Video detection failed: {}", e.message)
            emptyList()
        }
    }

    internal fun parseResult(result: Any?): List<VideoSource> = parseResult(result, includeIframes = true)

    /**
     * Parse the raw probe result and derive every fact the probe does not state itself.
     *
     * @param result the raw JSON string produced by the injected probe
     * @param includeIframes keep entries detected inside same-origin iframes
     */
    internal fun parseResult(result: Any?, includeIframes: Boolean): List<VideoSource> {
        if (result == null) return emptyList()
        val sources = try {
            val json = result.toString()
            if (json.isBlank() || json == "[]") return emptyList()
            pulsarObjectMapper().readValue<List<VideoSource>>(json)
        } catch (e: Exception) {
            logger.debug("Failed to parse video detection result: {}", e.message)
            return emptyList()
        }
        return postProcess(sources, includeIframes)
    }

    /**
     * Derive frame attribution, MIME/HLS facts and element counts from the raw probe entries.
     *
     * Steps, in order:
     * 1. normalise each entry (data-URI summaries, frame depth, opaque-MSE flag, HLS/DASH flags);
     * 2. merge entries that describe the same URL in the same frame, keeping the richer fields;
     * 3. link opaque `blob:` sources to the manifest the probe discovered;
     * 4. optionally drop everything that came from a nested frame.
     */
    internal fun postProcess(sources: List<VideoSource>, includeIframes: Boolean = true): List<VideoSource> {
        val merged = mergeDuplicates(sources.map { normalize(it) })
        val linked = linkManifests(merged)
        return if (includeIframes) linked else linked.filter { it.frameDepth == 0 }
    }

    /**
     * Normalise one probe entry: summarise data URIs, derive the frame depth and
     * re-derive the URL-encoded facts (HLS/DASH/opaque MSE) instead of trusting the probe.
     */
    private fun normalize(source: VideoSource): VideoSource {
        val srcUrl = source.srcUrl?.takeIf { it.isNotBlank() }?.let { MediaUtils.summarizeDataUri(it) }
        val resolvedUrl = source.resolvedUrl?.takeIf { it.isNotBlank() }?.let { MediaUtils.summarizeDataUri(it) }
        val frameUrl = source.frameUrl?.takeIf { it.isNotBlank() }
        val url = resolvedUrl ?: srcUrl
        return source.copy(
            srcUrl = srcUrl,
            resolvedUrl = resolvedUrl,
            frameUrl = frameUrl,
            frameDepth = if (frameUrl != null) maxOf(source.frameDepth, 1) else 0,
            elementCount = maxOf(source.elementCount, 1),
            isHls = source.isHls || isHlsUrl(url),
            isDash = source.isDash || isDashUrl(url),
            isOpaqueSource = source.isOpaqueSource || MediaUtils.isOpaqueMediaUrl(url),
        )
    }

    /**
     * Collapse entries that describe the same URL in the same frame.
     *
     * Entries without any URL are kept as-is, and entries from different frames never
     * merge — the frame is part of the identity, otherwise the frame attribution added
     * for video ads would be lost again.
     */
    private fun mergeDuplicates(sources: List<VideoSource>): List<VideoSource> {
        val merged = ArrayList<VideoSource>(sources.size)
        val indexByKey = HashMap<String, Int>(sources.size)
        for (source in sources) {
            val key = dedupKey(source)
            if (key == null) {
                merged.add(source)
                continue
            }
            val index = indexByKey[key]
            if (index == null) {
                indexByKey[key] = merged.size
                merged.add(source)
            } else {
                merged[index] = merge(merged[index], source)
            }
        }
        return merged
    }

    private fun dedupKey(source: VideoSource): String? {
        val url = source.url() ?: return null
        return "${source.frameUrl ?: TOP_DOCUMENT_KEY}\u0000$url"
    }

    /**
     * Prefer the richer entry instead of first-seen-wins: a `<video>` is emitted before its
     * `<source>` children, so a first-seen-wins merge would drop the declared MIME type.
     */
    private fun merge(first: VideoSource, second: VideoSource): VideoSource = first.copy(
        srcUrl = first.srcUrl ?: second.srcUrl,
        resolvedUrl = first.resolvedUrl ?: second.resolvedUrl,
        type = first.type ?: second.type,
        width = first.width ?: second.width,
        height = first.height ?: second.height,
        hasControls = first.hasControls || second.hasControls,
        posterUrl = first.posterUrl ?: second.posterUrl,
        isHls = first.isHls || second.isHls,
        isDash = first.isDash || second.isDash,
        isIframe = first.isIframe || second.isIframe,
        isOpaqueSource = first.isOpaqueSource || second.isOpaqueSource,
        manifestUrl = first.manifestUrl ?: second.manifestUrl,
        elementCount = first.elementCount + second.elementCount,
    )

    /**
     * Link every opaque `blob:`/`mediasource:` source to a discovered manifest.
     *
     * MSE players never expose the stream URL in the DOM, so the linked manifest is the
     * only usable handle on the stream. A manifest from the same frame wins, then any
     * unused manifest, so several players on one page do not all report the same stream.
     */
    private fun linkManifests(sources: List<VideoSource>): List<VideoSource> {
        if (sources.none { it.needsManifest() }) return sources
        val manifests = sources.mapNotNull { source ->
            val url = source.url()
            if ((source.isHls || source.isDash) && !source.isOpaqueSource && !url.isNullOrBlank()) {
                source to url
            } else {
                null
            }
        }
        if (manifests.isEmpty()) return sources

        val claimed = HashSet<String>()
        return sources.map { source ->
            if (!source.needsManifest()) return@map source
            val sameFrame = manifests.filter { it.first.frameUrl == source.frameUrl }
            val claimedManifest = sameFrame.firstOrNull { it.second !in claimed }
                ?: manifests.firstOrNull { it.second !in claimed }
                ?: sameFrame.firstOrNull()
                ?: manifests.first()
            claimed.add(claimedManifest.second)
            source.copy(
                manifestUrl = claimedManifest.second,
                isHls = claimedManifest.first.isHls,
                isDash = claimedManifest.first.isDash,
                type = source.type ?: claimedManifest.first.type,
            )
        }
    }

    /** An opaque MSE source that has no manifest attached yet. */
    private fun VideoSource.needsManifest(): Boolean =
        isOpaqueSource && !isHls && !isDash && manifestUrl == null

    /** The most specific URL of this entry. */
    private fun VideoSource.url(): String? = resolvedUrl ?: srcUrl

    companion object {
        /** Key prefix for entries found in the top-level document (which has no frame URL). */
        private const val TOP_DOCUMENT_KEY = "\u0000top"

        private val HLS_PATTERN = Regex("""\.m3u8([?#].*)?$""", RegexOption.IGNORE_CASE)

        private val DASH_PATTERN = Regex("""\.mpd([?#].*)?$""", RegexOption.IGNORE_CASE)

        private fun isHlsUrl(url: String?): Boolean = url != null && HLS_PATTERN.containsMatchIn(url)

        private fun isDashUrl(url: String?): Boolean = url != null && DASH_PATTERN.containsMatchIn(url)

        /**
         * JavaScript probe that scans the DOM for video sources.
         *
         * Queries:
         * - `<video>` elements — src, currentSrc, dimensions, controls, poster
         * - `<source>` elements inside `<video>` — src, type, HLS/DASH detection
         * - `<a>` links to video files — href ending in .mp4/.webm/.m3u8/.ts
         * - `<iframe>` embeds from YouTube, Vimeo, Dailymotion, Bilibili
         * - `<link rel="preload">` for media assets
         * - same-origin iframes, recursively one level deep, each entry tagged with its frame
         * - HLS/DASH manifests from player globals, network (Resource Timing) hints and page text
         *
         * Every entry carries `frameUrl`/`frameDepth` so callers can tell page content from
         * ad-unit/embedded-frame content, and long `data:` URIs are summarised here so a
         * base64 blob never crosses the CDP boundary in full.
         */
        // Uses a named function + invocation pattern (not IIFE) to avoid breakage
        // by the JS confuser pipeline (JsUtils.toCDPCompatibleExpression + confuser.confuse())
        // which incorrectly wraps parenthesized function expressions.
        internal val DETECTION_SCRIPT = """
var __b4_video_results = [];

// Inline data: payloads can be megabytes long and cannot be downloaded, so they are
// summarised before leaving the page. Must stay in sync with MediaUtils.DATA_URI_VERBATIM_LIMIT.
var __b4_data_uri_limit = 200;

function __b4_short_src(v) {
    if (!v || typeof v !== 'string' || v.length <= __b4_data_uri_limit) return v;
    if (v.slice(0, 5).toLowerCase() !== 'data:') return v;
    var comma = v.indexOf(',');
    if (comma < 0) return v;
    var meta = v.slice(0, comma);
    var payload = v.slice(comma + 1);
    var size = payload.length;
    if (/;base64/i.test(meta)) {
        var padding = 0;
        if (payload.charAt(payload.length - 1) === '=') padding = 1;
        if (payload.length > 1 && payload.charAt(payload.length - 2) === '=') padding = 2;
        size = Math.floor(payload.length * 3 / 4) - padding;
        if (size < 0) size = 0;
    }
    return meta + ',<' + size + ' B>';
}

// Every element is reported: URL-level collapsing happens once, in Kotlin, where the
// richer of two entries (e.g. the <source> carrying the MIME type) can win.
function __b4_video_add(item) {
    __b4_video_results.push(item);
}

var videoExt = /\.(mp4|webm|mkv|mov|avi|flv|wmv|ts|m3u8|mpd)([?#].*)?$/i;
var playerDomains = /(youtube\.com|youtube-nocookie\.com|vimeo\.com|dailymotion\.com|bilibili\.com|youku\.com|twitch\.tv)/i;
var manifestExt = /\.(m3u8|mpd)([?#].*)?$/i;
// '?' is excluded from the path part on purpose: without it a page URL such as
// https://player.test/demo/?src=https://cdn.test/stream.m3u8 would be extracted whole and
// reported as a manifest, instead of the stream URL it carries.
var manifestUrlRe = /https?:\/\/[^\s"'<>()?\\]+?\.(m3u8|mpd)(\?[^\s"'<>()\\]*)?/gi;

function __b4_scan_videos(doc, frameUrl, frameDepth) {
    frameUrl = frameUrl || null;
    frameDepth = frameDepth || 0;

    // 1. <video> elements
    var videos = doc.querySelectorAll('video');
    for (var i = 0; i < videos.length; i++) {
        var el = videos[i];
        var src = el.currentSrc || el.src || '';
        __b4_video_add({
            tagName: 'video',
            srcUrl: __b4_short_src(el.getAttribute('src') || src || null),
            resolvedUrl: __b4_short_src(src || el.getAttribute('src') || null),
            type: el.getAttribute('type') || null,
            width: el.videoWidth || el.width || null,
            height: el.videoHeight || el.height || null,
            hasControls: el.controls || false,
            posterUrl: el.poster || null,
            isHls: /\.m3u8([?#].*)?$/.test(src),
            isDash: /\.mpd([?#].*)?$/.test(src),
            isIframe: false,
            frameUrl: frameUrl,
            frameDepth: frameDepth
        });

        // <source> children inside <video>
        var sources = el.querySelectorAll('source');
        for (var j = 0; j < sources.length; j++) {
            var s = sources[j];
            var sSrc = s.src || s.getAttribute('src') || '';
            __b4_video_add({
                tagName: 'source',
                srcUrl: __b4_short_src(s.getAttribute('src') || sSrc || null),
                resolvedUrl: __b4_short_src(sSrc || null),
                type: s.type || s.getAttribute('type') || null,
                width: el.videoWidth || el.width || null,
                height: el.videoHeight || el.height || null,
                hasControls: el.controls || false,
                posterUrl: el.poster || null,
                isHls: /\.m3u8([?#].*)?$/.test(sSrc),
                isDash: /\.mpd([?#].*)?$/.test(sSrc),
                isIframe: false,
                frameUrl: frameUrl,
                frameDepth: frameDepth
            });
        }
    }

    // 2. <a> links pointing to video files
    var anchors = doc.querySelectorAll('a[href]');
    for (var k = 0; k < anchors.length; k++) {
        var a = anchors[k];
        var href = a.href || '';
        if (videoExt.test(href)) {
            __b4_video_add({
                tagName: 'a',
                srcUrl: __b4_short_src(a.getAttribute('href') || href || null),
                resolvedUrl: __b4_short_src(href || null),
                type: null,
                width: null,
                height: null,
                hasControls: false,
                posterUrl: null,
                isHls: /\.m3u8([?#].*)?$/.test(href),
                isDash: /\.mpd([?#].*)?$/.test(href),
                isIframe: false,
                frameUrl: frameUrl,
                frameDepth: frameDepth
            });
        }
    }

    // 3. Embedded iframe players (YouTube, Vimeo, Dailymotion, Bilibili)
    var iframes = doc.querySelectorAll('iframe[src]');
    for (var m = 0; m < iframes.length; m++) {
        var iframe = iframes[m];
        var iframeSrc = iframe.src || '';
        if (playerDomains.test(iframeSrc)) {
            __b4_video_add({
                tagName: 'iframe',
                srcUrl: iframe.getAttribute('src') || iframeSrc || null,
                resolvedUrl: iframeSrc || null,
                type: null,
                width: iframe.width || null,
                height: iframe.height || null,
                hasControls: false,
                posterUrl: null,
                isHls: false,
                isDash: false,
                isIframe: true,
                frameUrl: frameUrl,
                frameDepth: frameDepth
            });
        }
    }

    // 4. <link rel="preload"> for media assets
    var links = doc.querySelectorAll('link[rel="preload"][as]');
    for (var n = 0; n < links.length; n++) {
        var link = links[n];
        var linkHref = link.href || '';
        if (link.getAttribute('as') === 'media' || link.getAttribute('as') === 'video' || videoExt.test(linkHref)) {
            __b4_video_add({
                tagName: 'link',
                srcUrl: link.getAttribute('href') || linkHref || null,
                resolvedUrl: linkHref || null,
                type: link.type || link.getAttribute('type') || null,
                width: null,
                height: null,
                hasControls: false,
                posterUrl: null,
                isHls: /\.m3u8([?#].*)?$/.test(linkHref),
                isDash: /\.mpd([?#].*)?$/.test(linkHref),
                isIframe: false,
                frameUrl: frameUrl,
                frameDepth: frameDepth
            });
        }
    }
}

// 5. HLS / DASH manifest discovery
// An MSE player (hls.js, dash.js) hands the <video> nothing but a blob: URL, so the stream
// URL has to be recovered from the player instance, the network hints kept by the Resource
// Timing API, or a manifest URL written in the page itself (URL query, inline script, input).
function __b4_collect_manifests(doc) {
    var found = [];
    var seen = {};

    function push(url, tag) {
        if (!url || typeof url !== 'string') return;
        var clean = url.replace(/\\\//g, '/').trim();
        if (!manifestExt.test(clean)) return;
        var key = tag + '|' + clean;
        if (seen[key]) return;
        seen[key] = true;
        found.push({ url: clean, isDash: /\.mpd([?#].*)?$/i.test(clean), tag: tag });
    }

    function pushText(text, tag) {
        if (!text || typeof text !== 'string' || text.length > 200000) return;
        manifestUrlRe.lastIndex = 0;
        var hit = manifestUrlRe.exec(text);
        var guard = 0;
        while (hit !== null && guard < 10) {
            guard++;
            push(hit[0], tag);
            hit = manifestUrlRe.exec(text);
        }
    }

    // 5.1 player globals, when the page kept a reachable reference
    try {
        if (typeof Hls !== 'undefined' && Hls.instances) {
            for (var i = 0; i < Hls.instances.length; i++) {
                push(Hls.instances[i] ? Hls.instances[i].url : null, 'hlsjs');
            }
        }
    } catch (e) {}
    try {
        if (typeof dashjs !== 'undefined') {
            var players = dashjs.getMediaPlayers ? dashjs.getMediaPlayers() : [];
            for (var j = 0; j < players.length; j++) {
                var playerSrc = players[j] && players[j].getSource ? players[j].getSource() : null;
                push(typeof playerSrc === 'string' ? playerSrc : null, 'dashjs');
            }
        }
    } catch (e) {}

    // 5.2 network hints: the manifest request itself is the reliable pointer
    try {
        var entries = (typeof performance !== 'undefined' && performance.getEntriesByType)
            ? performance.getEntriesByType('resource') : [];
        for (var k = 0; k < entries.length; k++) {
            push(entries[k] ? entries[k].name : null, 'manifest');
        }
    } catch (e) {}

    // 5.3 page hints: inline scripts, the page URL and the player configuration inputs
    try {
        var scripts = doc.querySelectorAll('script:not([src])');
        for (var s = 0; s < scripts.length && s < 50; s++) {
            pushText(scripts[s].textContent || '', 'manifest');
        }
    } catch (e) {}
    try {
        var inputs = doc.querySelectorAll('input[value]');
        for (var t = 0; t < inputs.length && t < 50; t++) {
            pushText(inputs[t].value || '', 'manifest');
        }
    } catch (e) {}
    try { pushText(window.location.href, 'manifest'); } catch (e) {}
    try { pushText(doc.location ? doc.location.href : '', 'manifest'); } catch (e) {}

    return found;
}

// ---- Scan top-level document ----
__b4_scan_videos(document, null, 0);

// ---- Scan same-origin iframes ----
// For iframes whose contentDocument is accessible (same-origin), recursively
// scan for video elements and attribute every entry to the frame document it came
// from. Cross-origin iframes are not readable and are skipped silently.
var allIframes = document.querySelectorAll('iframe');
for (var fi = 0; fi < allIframes.length; fi++) {
    try {
        var frameDoc = allIframes[fi].contentDocument || allIframes[fi].contentWindow.document;
        if (frameDoc && frameDoc !== document) {
            var frameHref = null;
            try {
                frameHref = (frameDoc.location && frameDoc.location.href) || null;
            } catch (e) {}
            if (!frameHref) frameHref = allIframes[fi].getAttribute('src') || 'about:blank';
            __b4_scan_videos(frameDoc, frameHref, 1);
        }
    } catch(e) {
        // Cross-origin iframe — cannot access, skip silently
    }
}

// ---- Report the manifests found for this page ----
var __b4_manifests = __b4_collect_manifests(document);
for (var mi = 0; mi < __b4_manifests.length; mi++) {
    var mf = __b4_manifests[mi];
    __b4_video_add({
        tagName: mf.tag,
        srcUrl: mf.url,
        resolvedUrl: mf.url,
        type: mf.isDash ? 'application/dash+xml' : 'application/vnd.apple.mpegurl',
        width: null,
        height: null,
        hasControls: false,
        posterUrl: null,
        isHls: !mf.isDash,
        isDash: mf.isDash,
        isIframe: false,
        frameUrl: null,
        frameDepth: 0
    });
}

JSON.stringify(__b4_video_results)
""".trimIndent()
    }
}
