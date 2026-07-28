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
 */
open class VideoDetector {
    private val logger = getLogger(VideoDetector::class)

    /**
     * A detected video source on a web page.
     */
    data class VideoSource(
        /** HTML tag name: "video", "source", "a", "iframe" */
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
    )

    /**
     * Scan the current page for all video sources.
     *
     * @param driver the WebDriver connected to the page
     * @return list of detected video sources, or empty list on error
     */
    open suspend fun detect(driver: WebDriver): List<VideoSource> {
        return try {
            val result = driver.evaluate(DETECTION_SCRIPT)
            parseResult(result)
        } catch (e: Exception) {
            logger.warn("Video detection failed: {}", e.message)
            emptyList()
        }
    }

    internal fun parseResult(result: Any?): List<VideoSource> {
        if (result == null) return emptyList()
        return try {
            val json = result.toString()
            if (json.isBlank() || json == "[]") return emptyList()
            @Suppress("UNCHECKED_CAST")
            pulsarObjectMapper().readValue<List<VideoSource>>(json).distinctBy { it.resolvedUrl ?: it.srcUrl }
        } catch (e: Exception) {
            logger.debug("Failed to parse video detection result: {}", e.message)
            emptyList()
        }
    }

    companion object {
        /**
         * JavaScript probe that scans the DOM for video sources.
         *
         * Queries:
         * - `<video>` elements — src, currentSrc, dimensions, controls, poster
         * - `<source>` elements inside `<video>` — src, type, HLS/DASH detection
         * - `<a>` links to video files — href ending in .mp4/.webm/.m3u8/.ts
         * - `<iframe>` embeds from YouTube, Vimeo, Dailymotion, Bilibili
         * - `<link rel="preload">` for media assets
         * - `window.Hls` / `window.dashjs` global player objects
         */
        private val DETECTION_SCRIPT = """
(function() {
    var results = [];
    var seen = {};

    function add(item) {
        var key = item.resolvedUrl || item.srcUrl || '';
        if (key && seen[key]) return;
        if (key) seen[key] = true;
        results.push(item);
    }

    // 1. <video> elements
    var videos = document.querySelectorAll('video');
    for (var i = 0; i < videos.length; i++) {
        var el = videos[i];
        var src = el.currentSrc || el.src || '';
        add({
            tagName: 'video',
            srcUrl: el.getAttribute('src') || src || null,
            resolvedUrl: src || el.getAttribute('src') || null,
            type: el.getAttribute('type') || null,
            width: el.videoWidth || el.width || null,
            height: el.videoHeight || el.height || null,
            hasControls: el.controls || false,
            posterUrl: el.poster || null,
            isHls: /\.m3u8([?#].*)?$$/.test(src),
            isDash: /\.mpd([?#].*)?$$/.test(src),
            isIframe: false
        });

        // <source> children inside <video>
        var sources = el.querySelectorAll('source');
        for (var j = 0; j < sources.length; j++) {
            var s = sources[j];
            var sSrc = s.src || s.getAttribute('src') || '';
            add({
                tagName: 'source',
                srcUrl: s.getAttribute('src') || sSrc || null,
                resolvedUrl: sSrc || null,
                type: s.type || s.getAttribute('type') || null,
                width: el.videoWidth || el.width || null,
                height: el.videoHeight || el.height || null,
                hasControls: el.controls || false,
                posterUrl: el.poster || null,
                isHls: /\.m3u8([?#].*)?$$/.test(sSrc),
                isDash: /\.mpd([?#].*)?$$/.test(sSrc),
                isIframe: false
            });
        }
    }

    // 2. <a> links pointing to video files
    var anchors = document.querySelectorAll('a[href]');
    var videoExt = /\.(mp4|webm|mkv|mov|avi|flv|wmv|ts|m3u8|mpd)([?#].*)?$$/i;
    for (var k = 0; k < anchors.length; k++) {
        var a = anchors[k];
        var href = a.href || '';
        if (videoExt.test(href)) {
            add({
                tagName: 'a',
                srcUrl: a.getAttribute('href') || href || null,
                resolvedUrl: href || null,
                type: null,
                width: null,
                height: null,
                hasControls: false,
                posterUrl: null,
                isHls: /\.m3u8([?#].*)?$$/.test(href),
                isDash: /\.mpd([?#].*)?$$/.test(href),
                isIframe: false
            });
        }
    }

    // 3. Embedded iframe players (YouTube, Vimeo, Dailymotion, Bilibili)
    var iframes = document.querySelectorAll('iframe[src]');
    var playerDomains = /(youtube\.com|youtube-nocookie\.com|vimeo\.com|dailymotion\.com|bilibili\.com|youku\.com|twitch\.tv)/i;
    for (var m = 0; m < iframes.length; m++) {
        var iframe = iframes[m];
        var iframeSrc = iframe.src || '';
        if (playerDomains.test(iframeSrc)) {
            add({
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
                isIframe: true
            });
        }
    }

    // 4. <link rel="preload"> for media assets
    var links = document.querySelectorAll('link[rel="preload"][as]');
    for (var n = 0; n < links.length; n++) {
        var link = links[n];
        var linkHref = link.href || '';
        if (link.getAttribute('as') === 'media' || link.getAttribute('as') === 'video' || videoExt.test(linkHref)) {
            add({
                tagName: 'link',
                srcUrl: link.getAttribute('href') || linkHref || null,
                resolvedUrl: linkHref || null,
                type: link.type || link.getAttribute('type') || null,
                width: null,
                height: null,
                hasControls: false,
                posterUrl: null,
                isHls: /\.m3u8([?#].*)?$$/.test(linkHref),
                isDash: /\.mpd([?#].*)?$$/.test(linkHref),
                isIframe: false
            });
        }
    }

    // 5. Check for global Hls.js / dash.js instances
    try {
        if (typeof Hls !== 'undefined' && Hls.instances) {
            for (var p = 0; p < Hls.instances.length; p++) {
                var hls = Hls.instances[p];
                if (hls.url) {
                    add({
                        tagName: 'hlsjs',
                        srcUrl: hls.url,
                        resolvedUrl: hls.url,
                        type: 'application/vnd.apple.mpegurl',
                        width: null,
                        height: null,
                        hasControls: false,
                        posterUrl: null,
                        isHls: true,
                        isDash: false,
                        isIframe: false
                    });
                }
            }
        }
    } catch(e) {}

    try {
        if (typeof dashjs !== 'undefined') {
            var players = dashjs.getMediaPlayers ? dashjs.getMediaPlayers() : [];
            if (!players || !players.length && dashjs.MediaPlayer) {
                players = [dashjs.MediaPlayer];
            }
            for (var q = 0; q < players.length; q++) {
                var dp = players[q];
                var dpUrl = dp.getSource ? dp.getSource() : (dp.url || '');
                if (dpUrl) {
                    add({
                        tagName: 'dashjs',
                        srcUrl: typeof dpUrl === 'string' ? dpUrl : '',
                        resolvedUrl: typeof dpUrl === 'string' ? dpUrl : '',
                        type: 'application/dash+xml',
                        width: dp.getVideoElement ? (dp.getVideoElement().videoWidth || null) : null,
                        height: dp.getVideoElement ? (dp.getVideoElement().videoHeight || null) : null,
                        hasControls: false,
                        posterUrl: null,
                        isHls: false,
                        isDash: true,
                        isIframe: false
                    });
                }
            }
        }
    } catch(e) {}

    return JSON.stringify(results);
})()
""".trimIndent()
    }
}
