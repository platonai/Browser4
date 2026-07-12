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

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.images.config.ImageConfig
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Detects image elements and sources on a web page via CDP JavaScript evaluation.
 *
 * Detection operates entirely via DOM JS evaluation — it never touches the network layer,
 * so [BlockRule] blocking of `ResourceType.MEDIA` is irrelevant.
 */
open class ImageDetector(
    private val config: ImageConfig = ImageConfig(),
) {
    private val logger = getLogger(ImageDetector::class)

    /**
     * A detected image source on a web page.
     */
    data class ImageSource(
        /** HTML tag name: "img", "picture", "source", "a", "background", "link", "meta", "svg:image" */
        @JsonProperty("tagName")
        val tagName: String = "",

        /** Raw src/srcset/href/content attribute value */
        @JsonProperty("srcUrl")
        val srcUrl: String? = null,

        /** Resolved absolute URL (currentSrc for <img>, href for others) */
        @JsonProperty("resolvedUrl")
        val resolvedUrl: String? = null,

        /** MIME type if available from type attribute or Content-Type */
        @JsonProperty("type")
        val type: String? = null,

        /** CSS display width in pixels (0 if unknown) */
        @JsonProperty("width")
        val width: Int? = null,

        /** CSS display height in pixels (0 if unknown) */
        @JsonProperty("height")
        val height: Int? = null,

        /** Natural (intrinsic) width in pixels (0 if unknown) */
        @JsonProperty("naturalWidth")
        val naturalWidth: Int? = null,

        /** Natural (intrinsic) height in pixels (0 if unknown) */
        @JsonProperty("naturalHeight")
        val naturalHeight: Int? = null,

        /** Alt text attribute */
        @JsonProperty("alt")
        val alt: String? = null,

        /** Whether this is a data URI (data:image/...) */
        @JsonProperty("isDataUri")
        val isDataUri: Boolean = false,

        /** Whether this is an SVG image */
        @JsonProperty("isSvg")
        val isSvg: Boolean = false,
    )

    /**
     * Scan the current page for all image sources.
     *
     * @param driver the WebDriver connected to the page
     * @return list of detected image sources, filtered by config, or empty list on error
     */
    open suspend fun detect(driver: WebDriver): List<ImageSource> {
        return try {
            val result = driver.evaluate(DETECTION_SCRIPT)
            parseResult(result).filter { passesFilter(it) }
        } catch (e: Exception) {
            logger.warn("Image detection failed: {}", e.message)
            emptyList()
        }
    }

    /**
     * Apply config-based filters to an image source.
     */
    private fun passesFilter(source: ImageSource): Boolean {
        // Skip data URIs if configured
        if (config.skipDataUris && source.isDataUri) return false

        // Skip SVGs if configured
        if (config.skipSvg && source.isSvg) return false

        // Dimension filtering
        if (config.minWidth > 0) {
            val w = source.naturalWidth ?: source.width ?: 0
            if (w < config.minWidth) return false
        }
        if (config.minHeight > 0) {
            val h = source.naturalHeight ?: source.height ?: 0
            if (h < config.minHeight) return false
        }

        return true
    }

    internal fun parseResult(result: Any?): List<ImageSource> {
        if (result == null) return emptyList()
        return try {
            val json = result.toString()
            if (json.isBlank() || json == "[]") return emptyList()
            @Suppress("UNCHECKED_CAST")
            pulsarObjectMapper().readValue<List<ImageSource>>(json).distinctBy { it.resolvedUrl ?: it.srcUrl ?: "" }
        } catch (e: Exception) {
            logger.debug("Failed to parse image detection result: {}", e.message)
            emptyList()
        }
    }

    companion object {
        /**
         * JavaScript probe that scans the DOM for image sources.
         *
         * Queries:
         * - `<img>` elements — src, srcset, currentSrc, naturalWidth/Height, dimensions
         * - `<picture>` elements — `<source>` children with srcset
         * - `<a>` links to image files — href ending in known image extensions
         * - Elements with inline `background-image` CSS
         * - `<link rel="icon">`, `<link rel="apple-touch-icon">`
         * - `<meta property="og:image">`, `<meta name="twitter:image">`
         * - `<image>` elements in inline SVG
         */
        private val DETECTION_SCRIPT = """
(function() {
    var results = [];
    var seen = {};

    var IMAGE_EXTENSIONS = /\.(jpg|jpeg|png|gif|webp|svg|bmp|ico|tiff?|avif|heic|heif)([?#].*)?$/i;
    var SVG_EXTENSION = /\.svg([?#].*)?$/i;
    var DATA_URI_RE = /^data:image\//i;

    function add(item) {
        var key = item.resolvedUrl || item.srcUrl || '';
        if (key && seen[key]) return;
        if (key) seen[key] = true;
        results.push(item);
    }

    // ---- 1. <img> elements ----
    var imgs = document.querySelectorAll('img');
    for (var i = 0; i < imgs.length; i++) {
        var el = imgs[i];
        var src = el.currentSrc || el.src || '';
        var srcAttr = el.getAttribute('src') || '';
        var srcset = el.getAttribute('srcset') || '';
        add({
            tagName: 'img',
            srcUrl: src || srcAttr || null,
            resolvedUrl: src || el.src || null,
            type: null,
            width: el.width || null,
            height: el.height || null,
            naturalWidth: el.naturalWidth || null,
            naturalHeight: el.naturalHeight || null,
            alt: el.alt || el.getAttribute('alt') || null,
            isDataUri: DATA_URI_RE.test(src || srcAttr),
            isSvg: SVG_EXTENSION.test(src || srcAttr)
        });

        // Handle srcset candidates (pick the largest)
        if (srcset && !src) {
            var candidates = srcset.split(',').map(function(s) { return s.trim(); });
            var bestUrl = '';
            var bestDensity = 0;
            for (var c = 0; c < candidates.length; c++) {
                var parts = candidates[c].split(/\s+/);
                var candidateUrl = parts[0];
                var descriptor = parts[1] || '1x';
                var density = parseFloat(descriptor) || 1;
                if (density > bestDensity && candidateUrl) {
                    bestDensity = density;
                    bestUrl = candidateUrl;
                }
            }
            if (bestUrl) {
                var resolvedBest = (new URL(bestUrl, document.baseURI)).href;
                add({
                    tagName: 'img',
                    srcUrl: bestUrl,
                    resolvedUrl: resolvedBest,
                    type: null,
                    width: el.width || null,
                    height: el.height || null,
                    naturalWidth: el.naturalWidth || null,
                    naturalHeight: el.naturalHeight || null,
                    alt: el.alt || null,
                    isDataUri: DATA_URI_RE.test(bestUrl),
                    isSvg: SVG_EXTENSION.test(bestUrl)
                });
            }
        }
    }

    // ---- 2. <picture> elements ----
    var pictures = document.querySelectorAll('picture');
    for (var p = 0; p < pictures.length; p++) {
        var picture = pictures[p];
        var sources = picture.querySelectorAll('source');
        for (var s = 0; s < sources.length; s++) {
            var source = sources[s];
            var sourceSrcset = source.getAttribute('srcset') || '';
            if (!sourceSrcset) {
                var sourceSrc = source.getAttribute('src') || '';
                if (sourceSrc) sourceSrcset = sourceSrc + ' 1x';
            }
            if (!sourceSrcset) continue;

            var candidates = sourceSrcset.split(',').map(function(cs) { return cs.trim(); });
            for (var c = 0; c < candidates.length; c++) {
                var parts = candidates[c].split(/\s+/);
                var candidateUrl = parts[0];
                if (!candidateUrl) continue;
                var resolvedUrl = (new URL(candidateUrl, document.baseURI)).href;
                add({
                    tagName: 'source',
                    srcUrl: candidateUrl,
                    resolvedUrl: resolvedUrl,
                    type: source.getAttribute('type') || null,
                    width: null,
                    height: null,
                    naturalWidth: null,
                    naturalHeight: null,
                    alt: null,
                    isDataUri: DATA_URI_RE.test(candidateUrl),
                    isSvg: SVG_EXTENSION.test(candidateUrl)
                });
            }
        }
    }

    // ---- 3. <a> links pointing to image files ----
    var anchors = document.querySelectorAll('a[href]');
    for (var k = 0; k < anchors.length; k++) {
        var a = anchors[k];
        var href = a.href || '';
        if (IMAGE_EXTENSIONS.test(href)) {
            add({
                tagName: 'a',
                srcUrl: a.getAttribute('href') || href || null,
                resolvedUrl: href || null,
                type: null,
                width: null,
                height: null,
                naturalWidth: null,
                naturalHeight: null,
                alt: a.textContent ? a.textContent.trim().substring(0, 200) : null,
                isDataUri: false,
                isSvg: SVG_EXTENSION.test(href)
            });
        }
    }

    // ---- 4. Elements with inline background-image CSS ----
    var allElements = document.querySelectorAll('*');
    for (var e = 0; e < allElements.length; e++) {
        var elem = allElements[e];
        var style = elem.style;
        if (!style || !style.backgroundImage) continue;
        var bg = style.backgroundImage;
        if (bg === 'none' || bg === 'initial' || bg === 'inherit') continue;

        var urlMatch = bg.match(/url\(["']?([^)"'\s]+)["']?\)/);
        if (!urlMatch) continue;
        var bgUrl = urlMatch[1];
        if (!bgUrl) continue;

        // Skip data URIs for background images by default (they're often icons/sprites)
        if (DATA_URI_RE.test(bgUrl)) continue;

        try {
            var resolvedBgUrl = (new URL(bgUrl, document.baseURI)).href;
            add({
                tagName: 'background',
                srcUrl: bgUrl,
                resolvedUrl: resolvedBgUrl,
                type: null,
                width: elem.offsetWidth || null,
                height: elem.offsetHeight || null,
                naturalWidth: null,
                naturalHeight: null,
                alt: null,
                isDataUri: false,
                isSvg: SVG_EXTENSION.test(bgUrl)
            });
        } catch(ex) {
            // Ignore invalid URLs in background-image
        }
    }

    // ---- 5. <link rel="icon"> / <link rel="apple-touch-icon"> ----
    var links = document.querySelectorAll('link[rel]');
    for (var l = 0; l < links.length; l++) {
        var link = links[l];
        var rel = (link.getAttribute('rel') || '').toLowerCase();
        var isIcon = /(^|\s)(icon|shortcut icon|apple-touch-icon|apple-touch-icon-precomposed)($|\s)/i.test(rel);
        if (!isIcon) continue;
        var linkHref = link.href || '';
        if (!linkHref) continue;
        add({
            tagName: 'link',
            srcUrl: link.getAttribute('href') || linkHref || null,
            resolvedUrl: linkHref || null,
            type: link.getAttribute('type') || null,
            width: parseInt(link.getAttribute('sizes') || '') || null,
            height: parseInt((link.getAttribute('sizes') || '').split('x')[1] || '') || null,
            naturalWidth: null,
            naturalHeight: null,
            alt: rel || null,
            isDataUri: DATA_URI_RE.test(linkHref),
            isSvg: SVG_EXTENSION.test(linkHref)
        });
    }

    // ---- 6. <meta property="og:image"> / <meta name="twitter:image"> ----
    var metas = document.querySelectorAll('meta[property], meta[name]');
    for (var m = 0; m < metas.length; m++) {
        var meta = metas[m];
        var property = (meta.getAttribute('property') || '').toLowerCase();
        var name = (meta.getAttribute('name') || '').toLowerCase();
        var content = meta.getAttribute('content') || '';
        if (!content) continue;

        var isOgImage = property === 'og:image' || property === 'og:image:url' || property === 'og:image:secure_url';
        var isTwitterImage = name === 'twitter:image' || name === 'twitter:image:src';

        if (isOgImage || isTwitterImage) {
            try {
                var resolvedMetaUrl = (new URL(content, document.baseURI)).href;
                add({
                    tagName: 'meta',
                    srcUrl: content,
                    resolvedUrl: resolvedMetaUrl,
                    type: null,
                    width: null,
                    height: null,
                    naturalWidth: null,
                    naturalHeight: null,
                    alt: isOgImage ? 'og:image' : 'twitter:image',
                    isDataUri: DATA_URI_RE.test(content),
                    isSvg: SVG_EXTENSION.test(content)
                });
            } catch(ex) {
                // Ignore invalid URLs
            }
        }
    }

    // ---- 7. <image> elements inside inline SVG ----
    var svgImages = document.querySelectorAll('svg image[href], svg image[xlink\\:href]');
    for (var si = 0; si < svgImages.length; si++) {
        var svgImg = svgImages[si];
        var xiHref = svgImg.getAttributeNS
            ? svgImg.getAttributeNS('http://www.w3.org/1999/xlink', 'href')
            : svgImg.getAttribute('xlink:href');
        var svgHref = svgImg.getAttribute('href') || xiHref || '';
        if (!svgHref) continue;
        try {
            var resolvedSvgUrl = (new URL(svgHref, document.baseURI)).href;
            add({
                tagName: 'svg:image',
                srcUrl: svgHref,
                resolvedUrl: resolvedSvgUrl,
                type: null,
                width: svgImg.getAttribute('width') ? parseInt(svgImg.getAttribute('width')) : null,
                height: svgImg.getAttribute('height') ? parseInt(svgImg.getAttribute('height')) : null,
                naturalWidth: null,
                naturalHeight: null,
                alt: null,
                isDataUri: DATA_URI_RE.test(svgHref),
                isSvg: SVG_EXTENSION.test(svgHref)
            });
        } catch(ex) {
            // Ignore invalid URLs
        }
    }

    return JSON.stringify(results);
})()
""".trimIndent()
    }
}
