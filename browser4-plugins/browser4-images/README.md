# Browser4 Images

Image detection and bulk download plugin for [Browser4](https://github.com/platonai/pulsar).

## Overview

`browser4-images` provides image detection and bulk download capabilities for Browser4-powered browsing sessions and LLM agents. It scans the current page DOM for image sources (via Chrome DevTools Protocol) and downloads them using a dedicated HTTP client, independent of the browser's network stack.

## Features

- **DOM-based image detection** — scans `<img>`, `<picture>`, `<source>`, CSS backgrounds, `<a>` links, Open Graph / Twitter Card meta tags, favicons, and inline SVG `<image>` elements
- **Bulk download** — concurrent downloads with configurable concurrency, size limits, and timeouts
- **Auto-detect / auto-download** — optionally run detection (and download) on every page that reaches DOM steady state
- **LLM agent tool integration** — exposes `image.detectImages`, `image.download`, `image.downloadAll`, and `image.downloadBatch` as agent-callable tools
- **Security** — path-traversal protection, data URI filtering, and filename sanitization built in

## Installation

The plugin is a Maven dependency. Add it to your project:

```xml
<dependency>
    <groupId>ai.platon.pulsar</groupId>
    <artifactId>browser4-images</artifactId>
    <version>4.12.0-rc.1</version>
</dependency>
```

The plugin is enabled by default (`image.enabled` defaults to `true`). To disable it:

```properties
image.enabled=false
```

## Configuration

All properties use the `image.*` prefix:

| Property | Default | Description |
|---|---|---|
| `image.enabled` | `true` | Enable/disable the plugin |
| `image.download.dir` | `downloads/images` | Base directory for downloaded images |
| `image.download.max-size` | `52428800` (50 MB) | Maximum bytes per download |
| `image.download.timeout.seconds` | `60` | Per-download HTTP timeout |
| `image.download.concurrent` | `5` | Maximum concurrent downloads |
| `image.auto-detect.enabled` | `false` | Auto-detect images on DOM steady |
| `image.auto-download.enabled` | `false` | Auto-download detected images (requires `auto-detect.enabled`) |
| `image.detect.min-width` | `0` | Minimum image width filter (0 = no filter) |
| `image.detect.min-height` | `0` | Minimum image height filter (0 = no filter) |
| `image.detect.skip-svg` | `false` | Exclude SVG images from results |
| `image.detect.skip-data-uris` | `true` | Exclude data URI images from results |

## Agent Tools

When `browser4-agentic` is present, the following tools are exposed to LLM agents:

### `image.detectImages`

Scan the current page for all image sources.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `minWidth` | `Int` | No | — | Minimum image width filter |
| `minHeight` | `Int` | No | — | Minimum image height filter |

Returns: `List<ImageSource>` — each containing `srcUrl`, `resolvedUrl`, `type` (MIME), `width`, `height`, `naturalWidth`, `naturalHeight`, `alt`, `tagName`, `isDataUri`, `isSvg`.

### `image.download`

Download a single image by URL.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `url` | `String` | Yes | — | Image URL to download |
| `outputPath` | `String` | No | `downloads/images` | Output directory |
| `filename` | `String` | No | auto-generated | Custom filename |

Returns: `ImageDownloadResult` — `url`, `filePath`, `bytesDownloaded`, `contentType`, `width`, `height`, `durationMs`, `success`, `error`.

### `image.downloadAll`

Detect all images on the current page and download them in bulk.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `outputPath` | `String` | No | `downloads/images` | Output directory |
| `minWidth` | `Int` | No | — | Minimum image width filter |
| `minHeight` | `Int` | No | — | Minimum image height filter |

Returns: `BulkDownloadSummary` — `totalAttempted`, `successful`, `failed`, `totalBytesDownloaded`, `results`.

### `image.downloadBatch`

Download a specific list of image URLs.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `urls` | `List<String>` | Yes | — | List of image URLs to download |
| `outputPath` | `String` | No | `downloads/images` | Output directory |

Returns: `BulkDownloadSummary`.

## Architecture

```
browser4-images/
├── config/
│   ├── ImageConfig.kt              — Configuration properties (image.*)
│   └── ImageAutoConfiguration.kt   — Spring Boot auto-configuration & bean wiring
├── integration/
│   └── ImageBrowseEventHandler.kt  — Hooks into onDocumentSteady for auto-detect/download
├── service/
│   ├── ImageDetector.kt            — DOM scanning via CDP JavaScript evaluation
│   ├── ImageDownloader.kt          — HTTP download via OkHttp with concurrency control
│   └── ImageUtils.kt               — URL validation, filename handling, MIME helpers
└── tools/
    └── ImageToolExecutor.kt        — LLM agent tool definitions (image.*)
```

### How it works

1. **Detection** runs entirely in the browser's DOM via CDP `Runtime.evaluate` — a JavaScript probe queries `<img>`, `<picture>`, CSS backgrounds, meta tags, and more. It never touches the network layer, so browser `BlockRule` settings for `ResourceType.MEDIA` do not affect detection.
2. **Download** uses a dedicated OkHttp client (independent of the browser), with browser-like `User-Agent` headers, redirect following, streaming-to-disk, content-length validation, and coroutine-semaphore concurrency control.
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
