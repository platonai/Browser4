# Browser4 Markdown

Site crawling and Markdown conversion plugin for [Browser4](https://github.com/platonai/pulsar).

## Overview

`browser4-markdown` crawls pages within a website and converts them to Markdown (`.md`) files. It supports both browser-based crawling (with full JavaScript rendering via Chrome DevTools Protocol) and lightweight HTTP-based fetching (for static HTML pages). The plugin integrates with Browser4's browse event system for auto-crawling and exposes agent-callable tools for LLM-driven workflows.

## Features

- **Browser-based Markdown conversion** — extracts structured content from fully rendered pages via CDP JavaScript evaluation: headings, paragraphs, images, tables, lists, code blocks, blockquotes, inline formatting (bold, italic, links, code)
- **Site crawling** — BFS crawl with configurable depth and page count limits, same-domain filtering, optional path-prefix scoping, and polite delay between requests
- **Link discovery** — discovers and resolves all internal links on a page, preparing them for recursive crawling
- **HTTP fetch mode** — lightweight alternative for static HTML that doesn't require a browser
- **Auto-crawl** — optionally start crawling on every page that reaches DOM steady state
- **LLM agent tool integration** — exposes `markdown.convert`, `markdown.crawl`, `markdown.crawlFrom`, `markdown.fetch`, and `markdown.discoverLinks` as agent-callable tools
- **Security** — path-traversal protection and filename sanitization built in
- **YAML front matter** — optional metadata block (title, URL) at the top of each generated file

## Installation

```xml
<dependency>
    <groupId>ai.platon.pulsar</groupId>
    <artifactId>browser4-markdown</artifactId>
    <version>4.12.0-rc.1</version>
</dependency>
```

The plugin is enabled by default (`markdown.enabled` defaults to `true`). To disable it:

```properties
markdown.enabled=false
```

## Configuration

All properties use the `markdown.*` prefix:

| Property | Default | Description |
|---|---|---|
| `markdown.enabled` | `true` | Enable/disable the plugin |
| `markdown.output.dir` | `downloads/markdown` | Base directory for generated .md files |
| `markdown.crawl.max-depth` | `3` | Maximum crawl depth (0 = single page only) |
| `markdown.crawl.max-pages` | `50` | Maximum total pages to crawl per session |
| `markdown.crawl.same-domain-only` | `true` | Only crawl URLs on the same domain |
| `markdown.crawl.same-path-prefix` | — | Optional path prefix filter (e.g., `/docs/`) |
| `markdown.crawl.delay-ms` | `500` | Delay between page navigations (milliseconds) |
| `markdown.images.download` | `false` | Download and locally reference images |
| `markdown.images.max-size` | `10485760` (10 MB) | Maximum bytes per image download |
| `markdown.images.timeout.seconds` | `30` | Per-image HTTP download timeout |
| `markdown.images.concurrent` | `3` | Maximum concurrent image downloads |
| `markdown.auto-crawl.enabled` | `false` | Auto-crawl site on DOM steady |
| `markdown.page.timeout-seconds` | `30` | Page request timeout |
| `markdown.title.max-length` | `100` | Maximum title length in filenames |
| `markdown.extract.exclude-selectors` | `script,style,noscript,iframe,nav,footer` | CSS selectors to exclude from extraction |
| `markdown.output.include-source-url` | `true` | Add source URL as HTML comment in output |
| `markdown.output.include-front-matter` | `true` | Add YAML front matter (title, URL) |

## Agent Tools

When `browser4-agentic` is present, the following tools are exposed to LLM agents:

### `markdown.convert`

Convert the current browser page to a Markdown file.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `outputPath` | `String` | No | `downloads/markdown` | Output directory |

Returns: `{filePath, title, url, charCount, linkCount, imageCount, durationMs}`

### `markdown.crawl`

Crawl the current site starting from the current page. Follows internal same-domain links using BFS order. Each page is saved as a `.md` file.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `maxDepth` | `Int` | No | config value (3) | Maximum depth (0 = no following) |
| `maxPages` | `Int` | No | config value (50) | Maximum pages to crawl |
| `outputPath` | `String` | No | config value | Output directory |

Returns: `CrawlSummary` — `{pagesCrawled, pagesFailed, totalLinksDiscovered, totalImages, durationMs, pageResults[]}`

### `markdown.crawlFrom`

Same as `markdown.crawl` but explicitly navigates to a start URL first.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `startUrl` | `String` | Yes | — | Starting URL for the crawl |
| `maxDepth` | `Int` | No | config value | Maximum depth |
| `maxPages` | `Int` | No | config value | Maximum pages |
| `outputPath` | `String` | No | config value | Output directory |

### `markdown.fetch`

Fetch a single URL via direct HTTP and convert its HTML to Markdown. Does NOT use the browser — suitable for static pages. For JavaScript-rendered pages, use `markdown.convert` instead.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `url` | `String` | Yes | — | URL to fetch and convert |
| `outputPath` | `String` | No | config value | Output directory |

Returns: `{filePath, title, url, linkCount, success, error}`

### `markdown.discoverLinks`

Discover all links on the current page. Useful for exploring site structure before crawling.

No parameters.

Returns: `{totalLinks, internalLinks, externalLinks, internalUrls[], externalUrls[], links[]}`

Each link: `{href, text, resolvedUrl, isInternal}`

## Architecture

```
browser4-markdown/
├── config/
│   ├── MarkdownConfig.kt              — Configuration properties (markdown.*)
│   └── MarkdownAutoConfiguration.kt   — Spring Boot auto-configuration & bean wiring
├── integration/
│   └── MarkdownBrowseEventHandler.kt  — Hooks into onDocumentSteady for auto-crawl
├── service/
│   ├── MarkdownConverter.kt           — CDP JS evaluation for DOM → Markdown
│   ├── MarkdownUtils.kt               — URL validation, filename, path safety helpers
│   └── SiteCrawler.kt                 — BFS crawl orchestration with link discovery
└── tools/
    └── MarkdownToolExecutor.kt        — LLM agent tool definitions (markdown.*)
```

### How it works

#### Browser-based conversion (`markdown.convert`, `markdown.crawl`)

1. **Markdown extraction** runs entirely in the browser's DOM via CDP `Runtime.evaluate`. A JavaScript probe walks the DOM in document order, converting headings, paragraphs, images, tables, lists, code blocks, and blockquotes directly to Markdown syntax in a single round-trip.
2. **Link discovery** also runs via CDP evaluation — finds all `<a href>` elements, resolves relative URLs against the page URL, and flags same-domain (internal) links for crawling.
3. **Crawling** uses BFS with a visited-set to avoid duplicates. The browser navigates to each page sequentially, extracts markdown, saves to disk, discovers links, and enqueues unvisited internal links up to the configured depth/page limits.
4. **Output files** are named from the page title (sanitized for filesystem compatibility) with optional YAML front matter containing metadata.

#### HTTP-based fetching (`markdown.fetch`)

1. Fetches raw HTML via OkHttp (independent of the browser).
2. Parses with Jsoup to extract the same structural elements.
3. Converts to Markdown using a Kotlin-based renderer — no JavaScript execution.
4. Best for static sites where JavaScript rendering is not needed.

## Dependencies

- `browser4-protocol` — WebDriver, WebPage, and event type definitions
- `browser4-agentic` — `ToolExecutor` / `ToolMount` / `ToolSpec` agent infrastructure
- `pulsar-jsoup` — HTML parsing for HTTP fetch mode
- OkHttp — HTTP client for page fetching and image downloads
- Gson — JSON serialization
- Jackson Kotlin — JSON serialization
- Commons IO — file I/O utilities
- Kotlinx Coroutines — async concurrency control
- Spring Boot Autoconfigure — plugin lifecycle and bean wiring

## License

Apache License 2.0
