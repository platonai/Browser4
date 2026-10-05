# Browser4 Images

Image detection and bulk download plugin for [Browser4](https://github.com/platonai/pulsar).

## Overview

`browser4-images` provides image detection and bulk download capabilities for Browser4-powered browsing sessions and LLM agents. It scans the current page DOM for image sources (via Chrome DevTools Protocol) and downloads them using a dedicated HTTP client, independent of the browser's network stack.

## Features

- **DOM-based image detection** — scans `<img>` (with lazy `data-src`/`srcset` fallbacks), `<picture>`, `<source>`, stylesheet-derived CSS backgrounds (bounded `getComputedStyle` scan), `<a>` links, Open Graph / Twitter Card meta tags, favicons, and inline SVG `<image>` elements; results are distinct sources deduplicated by resolved URL
- **Bulk download** — concurrent downloads with configurable concurrency, size limits, timeouts, optional proxy, Content-Type validation, and extension/Content-Type alignment
- **Auto-detect / auto-download** — optionally run detection (and download) on every page that reaches DOM steady state
- **LLM agent tool integration** — exposes `image.detectImages`, `image.download`, `image.downloadAll`, and `image.downloadBatch` as agent-callable tools (also reachable from the CLI via `browser4-cli tool call`)
- **Security** — path-traversal protection, data URI filtering, non-image response rejection, and filename sanitization built in

## Installation

The plugin is a Maven dependency. Add it to your project:

```xml
<dependency>
    <groupId>ai.platon.pulsar</groupId>
    <artifactId>browser4-images</artifactId>
    <version>4.13.0-SNAPSHOT</version>
</dependency>
```

The plugin is enabled by default (`image.enabled` defaults to `true`). To disable it:

```properties
image.enabled=false
```

After installing or updating the plugin JAR, restart the backend (`browser4-cli stop`; the next command auto-restarts it) so the plugin is loaded.

## Calling from the browser4-cli

Plugin tools are invoked through the generic MCP passthrough command. The tool
name is the MCP snake_case name (the agent-facing `domain.method` name with the
dot removed and camelCase split into underscores):

| Agent tool (`domain.method`) | MCP / CLI tool name |
|---|---|
| `image.detectImages` | `image_detect_images` |
| `image.download` | `image_download` |
| `image.downloadAll` | `image_download_all` |
| `image.downloadBatch` | `image_download_batch` |

```bash
# Detect images on the current session page (arguments are passed as a JSON object)
browser4-cli tool call image_detect_images --json '{"minWidth":100,"minHeight":60,"limit":50}'

# Download one image
browser4-cli tool call image_download --json '{"url":"https://example.com/photo.jpg"}'

# Detect + bulk download
browser4-cli tool call image_download_all --json '{"minWidth":200,"minHeight":120}'

# Download an explicit URL list
browser4-cli tool call image_download_batch --json '{"urls":["https://example.com/a.jpg","https://example.com/b.png"]}'
```

`sessionId` is injected automatically from the active session. Run
`browser4-cli tool call --help` for the full syntax.

## Configuration

All properties use the `image.*` prefix:

| Property | Default | Description |
|---|---|---|
| `image.enabled` | `true` | Enable/disable the plugin |
| `image.download.dir` | `~/.browser4/downloads/images` | Base directory for downloaded images (absolute, anchored at the user home) |
| `image.download.proxy` | _(none)_ | HTTP proxy for downloads, e.g. `127.0.0.1:10808` or `http://127.0.0.1:10808`; falls back to `HTTPS_PROXY`/`HTTP_PROXY` env vars |
| `image.download.max-size` | `52428800` (50 MB) | Maximum bytes per download |
| `image.download.timeout.seconds` | `60` | Per-download HTTP timeout |
| `image.download.concurrent` | `5` | Maximum concurrent downloads |
| `image.auto-detect.enabled` | `false` | Auto-detect images on DOM steady |
| `image.auto-download.enabled` | `false` | Auto-download detected images (requires `auto-detect.enabled`) |
| `image.detect.min-width` | `0` | Minimum image width filter (0 = no filter) |
| `image.detect.min-height` | `0` | Minimum image height filter (0 = no filter) |
| `image.detect.skip-svg` | `false` | Exclude SVG images from results |
| `image.detect.skip-data-uris` | `true` | Exclude data URI images from results |

### Proxy for downloads

The downloader uses its own OkHttp client, which does **not** inherit the
operating-system proxy that Chrome uses (the JVM ships with
`java.net.useSystemProxies=false`). On networks where direct egress is blocked,
downloads fail with `Connection reset`/timeouts while the browser itself works.
Configure a proxy in one of these ways:

1. `image.download.proxy=127.0.0.1:10808` in the Browser4 config
2. `HTTPS_PROXY` / `HTTP_PROXY` environment variables (lowercase variants supported)
3. Standard JVM flags: `-Dhttps.proxyHost=... -Dhttps.proxyPort=...`

Network-style IO errors include a hint pointing at these options.

## Agent Tools

When `browser4-agentic` is present, the following tools are exposed to LLM agents.
The agent-facing names are `domain.method` (e.g. `image.detectImages`); over
MCP/CLI they appear as snake_case (e.g. `image_detect_images`) — see
[Calling from the browser4-cli](#calling-from-the-browser4-cli).

### `image.detectImages`

Scan the current page for image sources: `<img>` (including lazy `data-src`/
`srcset` fallbacks), `<picture>`/`<source>`, CSS background images (a bounded
`getComputedStyle` scan over visible elements), `<a>` links to image files
(MediaWiki `/wiki/File:` description pages excluded), favicons, and
OG/Twitter meta images.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `minWidth` | `Int` | No | — | Minimum image width filter |
| `minHeight` | `Int` | No | — | Minimum image height filter |
| `limit` | `Int` | No | — | Maximum number of entries to return |
| `offset` | `Int` | No | `0` | Number of entries to skip (use with `limit` for paging) |

Returns: `List<ImageSource>` of **distinct image sources, deduplicated by
resolved URL** — the count is unique sources, not DOM elements (the same image
repeated across 10 `<img>` tags appears once). Each entry contains `srcUrl`,
`resolvedUrl`, `type` (MIME), `width`, `height`, `naturalWidth`,
`naturalHeight`, `alt`, `tagName`, `isDataUri`, `isSvg`, and `loaded`.
`loaded=false` marks lazy-loaded images that have not been fetched yet: their
`naturalWidth`/`naturalHeight` are null and `width`/`height` are rendered
(layout) sizes, so dimension filters use rendered dimensions for those images.

### `image.download`

Download a single image by URL.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `url` | `String` | Yes | — | Image URL to download |
| `outputPath` | `String` | No | `~/.browser4/downloads/images` | Output directory |
| `filename` | `String` | No | auto-generated | Custom filename |

Responses whose `Content-Type` is clearly not an image (e.g. `text/html`) fail
instead of being saved. When the server negotiates a different format than the
filename suggests (e.g. WebP bytes for a `.png` URL via content negotiation),
the saved extension is corrected to match the actual content type, and the
returned `filePath` reflects the final name.

Returns: `ImageDownloadResult` — `url`, `filePath`, `bytesDownloaded`, `contentType`, `width`, `height`, `durationMs`, `success`, `error`. HTTP errors include the status code, reason phrase (HTTP/2 responses carry none, so a standard phrase is filled in), and the requested URL.

### `image.downloadAll`

Detect all images on the current page and download them in bulk.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `outputPath` | `String` | No | `~/.browser4/downloads/images` | Output directory |
| `minWidth` | `Int` | No | — | Minimum image width filter |
| `minHeight` | `Int` | No | — | Minimum image height filter |

Returns: `BulkDownloadSummary` — `totalAttempted`, `successful`, `failed`, `totalBytesDownloaded`, `results`. `totalAttempted` counts deduplicated detected sources after filtering.

### `image.downloadBatch`

Download a specific list of image URLs.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `urls` | `List<String>` | Yes | — | List of image URLs to download |
| `outputPath` | `String` | No | `~/.browser4/downloads/images` | Output directory |

Returns: `BulkDownloadSummary`.

## Architecture

```
browser4-images/
├── pom.xml
└── src/
    ├── main/kotlin/ai/platon/pulsar/images/
    │   ├── ImagesPlugin.kt                 — Browser4Plugin lifecycle entry point
    │   ├── config/
    │   │   ├── ImageConfig.kt              — Configuration properties (image.*)
    │   │   └── ImageAutoConfiguration.kt   — Spring Boot auto-configuration & bean wiring
    │   ├── integration/
    │   │   └── ImageBrowseEventHandler.kt  — Hooks into onDocumentSteady for auto-detect/download
    │   ├── service/
    │   │   ├── ImageDetector.kt            — DOM scanning via CDP JavaScript evaluation
    │   │   ├── ImageDownloader.kt          — HTTP download via OkHttp with concurrency control
    │   │   └── ImageUtils.kt               — URL validation, filename handling, MIME helpers
    │   └── tools/
    │       └── ImageToolExecutor.kt        — LLM agent tool definitions (image.*)
    ├── main/resources/META-INF/
    │   ├── browser4-plugin.json            — Plugin manifest and metadata
    │   └── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/kotlin/ai/platon/pulsar/images/
        ├── config/ImageConfigTest.kt
        └── service/
            ├── ImageDetectorTest.kt
            └── ImageUtilsTest.kt
```

### How it works

1. **Detection** runs entirely in the browser's DOM via CDP `Runtime.evaluate` — a JavaScript probe queries `<img>` (with `data-src`/`srcset` fallbacks for lazy images), `<picture>`, CSS backgrounds via a bounded `getComputedStyle` scan over visible elements, meta tags, and more. It never touches the network layer, so browser `BlockRule` settings for `ResourceType.MEDIA` do not affect detection. Results are deduplicated by resolved URL.
2. **Download** uses a dedicated OkHttp client (independent of the browser), with browser-like `User-Agent` headers, redirect following, streaming-to-disk, content-length validation, Content-Type rejection of non-image responses, extension alignment with the actual content type, optional proxy (`image.download.proxy` / `HTTPS_PROXY`), and coroutine-semaphore concurrency control.
3. **Auto mode** hooks into `BrowseEventHandlers.onDocumentSteady` — when enabled, detection fires on every page load, and optional auto-download runs in a fire-and-forget coroutine scope.

## Dependencies

- `browser4-protocol` — WebDriver, WebPage, and event type definitions
- `browser4-agentic` — `ToolExecutor` / `ToolMount` / `ToolSpec` agent infrastructure
- OkHttp — HTTP client for image downloads
- Jackson Kotlin — JSON serialization
- Commons IO — file I/O utilities
- Kotlinx Coroutines — async concurrency control
- Spring Boot Autoconfigure — plugin lifecycle and bean wiring

## License

Apache License 2.0
