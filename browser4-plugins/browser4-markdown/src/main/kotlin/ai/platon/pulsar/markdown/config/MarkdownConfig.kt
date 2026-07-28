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
package ai.platon.pulsar.markdown.config

import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Configuration holder for the browser4-markdown plugin.
 *
 * All properties are read from [ImmutableConfig] with sensible defaults.
 */
data class MarkdownConfig(
    /** Base directory for generated markdown files */
    val outputDir: String = "downloads/markdown",

    /** Maximum crawl depth (0 = single page only, no link following) */
    val maxDepth: Int = 3,

    /** Maximum total pages to crawl per session */
    val maxPages: Int = 50,

    /** Only crawl URLs within the same domain as the starting URL */
    val sameDomainOnly: Boolean = true,

    /** Only crawl URLs within the same path prefix (e.g., /docs/) */
    val samePathPrefix: String? = null,

    /** Whether to download and locally reference images in the markdown */
    val downloadImages: Boolean = false,

    /** Maximum allowed download size in bytes for a single image (default: 10 MB) */
    val maxImageDownloadSize: Long = 10 * 1024 * 1024L,

    /** Per-image download timeout in seconds */
    val imageDownloadTimeoutSeconds: Long = 30,

    /** Maximum concurrent image downloads */
    val concurrentImageDownloads: Int = 3,

    /** Whether to auto-crawl on onDocumentSteady */
    val autoCrawlEnabled: Boolean = false,

    /** Delay between page navigations during crawl (milliseconds, to be polite) */
    val crawlDelayMs: Long = 500,

    /** Maximum title length before truncation in filename */
    val maxTitleLength: Int = 100,

    /** CSS selectors to exclude from content extraction (comma-separated) */
    val excludeSelectors: String = "script,style,noscript,iframe,nav,footer",

    /** Page request timeout in seconds */
    val pageTimeoutSeconds: Long = 30,

    /** Whether to include the page URL as a comment at the top of each markdown file */
    val includeSourceUrl: Boolean = true,

    /** Whether to include page metadata (title, crawl date) as YAML front matter */
    val includeFrontMatter: Boolean = true,
) {
    companion object {
        private const val PREFIX = "markdown."

        /**
         * Build a [MarkdownConfig] from the application configuration.
         */
        fun fromConfig(conf: ImmutableConfig): MarkdownConfig {
            return MarkdownConfig(
                outputDir = conf.get("${PREFIX}output.dir", "downloads/markdown"),
                maxDepth = conf.getInt("${PREFIX}crawl.max-depth", 3),
                maxPages = conf.getInt("${PREFIX}crawl.max-pages", 50),
                sameDomainOnly = conf.getBoolean("${PREFIX}crawl.same-domain-only", true),
                samePathPrefix = conf.get("${PREFIX}crawl.same-path-prefix"),
                downloadImages = conf.getBoolean("${PREFIX}images.download", false),
                maxImageDownloadSize = conf.getLong("${PREFIX}images.max-size", 10 * 1024 * 1024L),
                imageDownloadTimeoutSeconds = conf.getLong("${PREFIX}images.timeout.seconds", 30),
                concurrentImageDownloads = conf.getInt("${PREFIX}images.concurrent", 3),
                autoCrawlEnabled = conf.getBoolean("${PREFIX}auto-crawl.enabled", false),
                crawlDelayMs = conf.getLong("${PREFIX}crawl.delay-ms", 500),
                maxTitleLength = conf.getInt("${PREFIX}title.max-length", 100),
                excludeSelectors = conf.get("${PREFIX}extract.exclude-selectors", "script,style,noscript,iframe,nav,footer"),
                pageTimeoutSeconds = conf.getLong("${PREFIX}page.timeout-seconds", 30),
                includeSourceUrl = conf.getBoolean("${PREFIX}output.include-source-url", true),
                includeFrontMatter = conf.getBoolean("${PREFIX}output.include-front-matter", true),
            )
        }
    }
}
