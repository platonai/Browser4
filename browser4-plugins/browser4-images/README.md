# Browser4 Images

Image detection and bulk download plugin for [Browser4](https://github.com/platonai/pulsar).

## Overview

`browser4-images` provides image detection and bulk download capabilities for Browser4-powered browsing sessions and LLM agents. It scans the current page DOM for image sources (via Chrome DevTools Protocol) and downloads them using a dedicated HTTP client, independent of the browser's network stack.

## Features

- **DOM-based image detection** — scans `<img>` (with lazy `data-src`/`srcset` fallbacks), `<picture>`, `<source>`, stylesheet-derived CSS backgrounds (bounded `getComputedStyle` scan), `<a>` links, Open Graph / Twitter Card meta tags, favicons, and inline SVG `<image>` elements; results are distinct sources deduplicated by resolved URL
- **MIME type on every entry** — a declared `type` (element attribute or `data:` URI header) or one inferred from the URL extension, so detection results carry the documented field
- **Predictable dimension filters** — one size basis per call (`rendered` by default, `natural` on request); sources whose size is unknown are kept, and bulk summaries report how many were filtered out
- **Bulk download** — concurrent downloads with configurable concurrency, size limits, timeouts, optional proxy, Content-Type validation, and extension/Content-Type alignment
- **Proxy-aware downloads** — honours the OS system proxy (WinINET on Windows), `HTTPS_PROXY`/`HTTP_PROXY`, the standard JVM proxy properties, and `image.download.proxy`
- **Auto-detect / auto-download** — optionally run detection (and download) on every page that reaches DOM steady state
- **LLM agent tool integration** — exposes `image.detectImages`, `image.download`, `image.downloadAll`, and `image.downloadBatch` as agent-callable tools (also reachable from the CLI via `browser4-cli tool call`), all returning JSON
- **Security** — path-traversal protection, data URI filtering, non-image response rejection, and filename sanitization built in

## Installation

The plugin is a Maven dependency. Add it to your project:

```xml
<dependency>
    <groupId>ai.platon.pulsar</groupId>
    <artifactId>browser4-images</artifactId>
    <version>4.14.1</version>
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
| `image.download.proxy` | _(none)_ | HTTP proxy for downloads, e.g. `127.0.0.1:10808` or `http://127.0.0.1:10808`; falls back to the JVM proxy properties, `HTTPS_PROXY`/`HTTP_PROXY` env vars, and the OS system proxy — see [Proxy for downloads](#proxy-for-downloads) |
| `image.download.max-size` | `52428800` (50 MB) | Maximum bytes per download |
| `image.download.timeout.seconds` | `25` | Per-download HTTP budget (connect/read/write and the whole call). Kept below the CLI's 30 s default tool-call timeout so the plugin's own actionable error is what a caller sees; raise it (and the caller's `--timeout`) for unusually large images |
| `image.download.concurrent` | `5` | Maximum concurrent downloads |
| `image.auto-detect.enabled` | `false` | Auto-detect images on DOM steady |
| `image.auto-download.enabled` | `false` | Auto-download detected images (requires `auto-detect.enabled`) |
| `image.detect.min-width` | `0` | Minimum image width filter (0 = no filter); compares rendered size, keeps unknown-size sources |
| `image.detect.min-height` | `0` | Minimum image height filter (0 = no filter); compares rendered size, keeps unknown-size sources |
| `image.detect.skip-svg` | `false` | Exclude SVG images from results |
| `image.detect.skip-data-uris` | `true` | Exclude data URI images from results |

### Proxy for downloads

The downloader uses its own OkHttp client, which does **not** inherit the
operating-system proxy that Chrome uses by default (the JVM ships with
`java.net.useSystemProxies=false`). On networks where direct egress is blocked,
downloads fail while the browser itself works, so the plugin looks for a proxy
itself. Sources are consulted in this order, the first usable one wins:

1. `image.download.proxy=127.0.0.1:10808` in the Browser4 config
2. JVM proxy properties: `-Dhttps.proxyHost=… -Dhttps.proxyPort=…` (then `http.*`)
3. `HTTPS_PROXY` / `HTTP_PROXY` environment variables (lowercase variants supported)
4. The operating-system proxy: WinINET on Windows (registry
   `HKCU\…\Internet Settings`), otherwise whatever the JVM's default
   `ProxySelector` reports. PAC/auto-config URLs are ignored — they need a
   JavaScript engine to evaluate.

Loopback hosts (`localhost`, `127.0.0.1`, `::1`) always bypass the proxy.

When none of these yields a proxy and a download cannot reach the host, the
error names the cause, how long the attempt took, the timeout budget in use,
the proxy that was tried, and the three settings above — a bare transport
timeout is never the only information.

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
| `minWidth` | `Int` | No | — | Minimum width in pixels; unknown-width sources are kept |
| `minHeight` | `Int` | No | — | Minimum height in pixels; unknown-height sources are kept |
| `dimensionBasis` | `String` | No | `rendered` | Size the filters compare: `rendered` (layout) or `natural` (intrinsic) |
| `limit` | `Int` | No | — | Maximum number of entries to return |
| `offset` | `Int` | No | `0` | Number of entries to skip (use with `limit` for paging) |

Returns: `List<ImageSource>` of **distinct image sources, deduplicated by
resolved URL** — the count is unique sources, not DOM elements (the same image
repeated across 10 `<img>` tags appears once). Each entry contains `srcUrl`,
`resolvedUrl`, `type` (MIME), `width`, `height`, `naturalWidth`,
`naturalHeight`, `alt`, `tagName`, `isDataUri`, `isSvg`, and `loaded`.

`type` is the element's declared MIME type when it has one (`type` attribute on
`<source>`/`<link>`, or a `data:` URI header), otherwise a provisional type
inferred from the URL extension (`svg` → `image/svg+xml`, `…svg.png` →
`image/png`). Only the response `Content-Type` at download time is
authoritative; the key is always present so consumers see a stable schema.

`loaded=false` marks lazy-loaded images that have not been fetched yet: their
`naturalWidth`/`naturalHeight` are null and `width`/`height` are rendered
(layout) sizes.

**Dimension filtering.** `minWidth`/`minHeight` compare exactly one size basis
per call — `rendered` (the layout size, the default: an image displayed at
50×50 is dropped by `minWidth=100` even when its source file is larger) or
`natural` (the intrinsic source size, available only for images that loaded).
A source whose size is **unknown** on a filtered axis — an OG/Twitter meta
image, a `<source>` candidate, an unloaded image with no layout box — is
**kept**: unknown is not the same as too small, so a page's 1280 px `og:image`
is never silently dropped. Combine with `limit`/`offset` to bound the payload.

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
returned `filePath` reflects the final name. Percent-escapes in the URL are
decoded for the saved filename (`%28Pantone%29` → `(Pantone)`).

Returns: a JSON object — `url`, `filePath`, `bytesDownloaded`, `contentType`,
`width`, `height`, `durationMs`, `success`, `error`. `width`/`height` are
decoded from the downloaded bytes (PNG/JPEG/GIF/BMP through ImageIO, WebP by
parsing its RIFF header), so a single download reports the same dimensions a
bulk download does. HTTP errors include the status code, reason phrase (HTTP/2
responses carry none, so a standard phrase is filled in), and the requested
URL; transport failures include the cause, the elapsed time, the timeout budget
and the proxy that was used.

### `image.downloadAll`

Detect all images on the current page and download them in bulk.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `outputPath` | `String` | No | `~/.browser4/downloads/images` | Output directory |
| `minWidth` | `Int` | No | — | Minimum width in pixels; unknown-width sources are kept |
| `minHeight` | `Int` | No | — | Minimum height in pixels; unknown-height sources are kept |
| `dimensionBasis` | `String` | No | `rendered` | Size the filters compare: `rendered` (layout) or `natural` (intrinsic) |

Returns: a JSON object — `totalDetected`, `totalAttempted`, `successful`,
`failed`, `filteredOut`, `totalBytesDownloaded`, `results`. Filtering follows
the same documented policy as `image.detectImages`, and the counts make it
explainable: `totalAttempted` is `totalDetected - filteredOut`, and
`filteredOut` never counts unknown-size sources.

### `image.downloadBatch`

Download a specific list of image URLs.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `urls` | `List<String>` | Yes | — | List of image URLs to download |
| `outputPath` | `String` | No | `~/.browser4/downloads/images` | Output directory |

Returns: a JSON object with the same fields as `image.downloadAll`
(`totalDetected` equals `totalAttempted`, `filteredOut` is 0 — no detection ran).

## Architecture

```
browser4-images/
├── pom.xml
└── src/
    ├── main/kotlin/ai/platon/pulsar/images/
    │   ├── ImagesPlugin.kt                 — Browser4Plugin lifecycle entry point (version from the JAR)
    │   ├── config/
    │   │   ├── ImageConfig.kt              — Configuration properties (image.*)
    │   │   ├── ImageProxyResolver.kt       — Proxy discovery: config → JVM → env → OS (WinINET)
    │   │   └── ImageAutoConfiguration.kt   — Spring Boot auto-configuration & bean wiring
    │   ├── integration/
    │   │   └── ImageBrowseEventHandler.kt  — Hooks into onDocumentSteady for auto-detect/download
    │   ├── service/
    │   │   ├── ImageDetector.kt            — DOM scanning via CDP JavaScript evaluation
    │   │   ├── ImageDimensionFilter.kt     — The min-width/min-height policy (basis, unknown = keep)
    │   │   ├── ImageDownloader.kt          — HTTP download via OkHttp with concurrency control
    │   │   └── ImageUtils.kt               — URL validation, filename handling, MIME/dimension helpers
    │   └── tools/
    │       └── ImageToolExecutor.kt        — LLM agent tool definitions (image.*), JSON results
    ├── main/resources/META-INF/
    │   ├── browser4-plugin.json            — Plugin manifest and metadata
    │   └── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/kotlin/ai/platon/pulsar/images/
        ├── config/
        │   ├── ImageConfigTest.kt
        │   └── ImageProxyResolverTest.kt
        ├── service/
        │   ├── ImageDetectorTest.kt
        │   ├── ImageDimensionFilterTest.kt
        │   ├── ImageDownloaderTest.kt
        │   └── ImageUtilsTest.kt
        └── tools/ImageToolExecutorTest.kt
```

### How it works

1. **Detection** runs entirely in the browser's DOM via CDP `Runtime.evaluate` — a JavaScript probe queries `<img>` (with `data-src`/`srcset` fallbacks for lazy images), `<picture>`, CSS backgrounds via a bounded `getComputedStyle` scan over visible elements, meta tags, and more. It never touches the network layer, so browser `BlockRule` settings for `ResourceType.MEDIA` do not affect detection. Results are deduplicated by resolved URL, their `type` is resolved (declared or URL-inferred), and dimension filters go through `ImageDimensionFilter`.
2. **Download** uses a dedicated OkHttp client (independent of the browser), with browser-like `User-Agent` headers, redirect following, streaming-to-disk, content-length validation, Content-Type rejection of non-image responses, extension alignment with the actual content type, a bounded per-download timeout, proxy discovery (`image.download.proxy` → JVM → env → OS), decoded image dimensions, and coroutine-semaphore concurrency control.
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
