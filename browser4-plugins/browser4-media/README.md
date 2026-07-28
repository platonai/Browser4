# Browser4 Media

Video detection, download, and FFmpeg processing plugin for [Browser4](https://github.com/platonai/pulsar).

## Overview

`browser4-media` equips Browser4 with video and media capabilities across three pillars: **detection** (DOM scanning for video sources via CDP), **download** (direct HTTP transfer via OkHttp), and **FFmpeg processing** (probe, transcode, extract audio, trim, compress). It integrates both as a browse-event hook for automated sessions and as agent-callable tools for LLM-driven workflows.

## Features

- **DOM-based video detection** — scans `<video>` elements, `<source>` children, `<a>` links to media files, `<iframe>` embeds from YouTube/Vimeo/Dailymotion/Bilibili/Youku/Twitch, HLS/DASH player globals, and `<link rel="preload">` media hints
- **Media download** — direct HTTP download with configurable concurrency, size limits (default 500 MB), and timeouts
- **FFmpeg/ffprobe integration** — probe media metadata, transcode, extract audio, trim segments, and compress/re-encode video via managed subprocesses
- **Auto-detect** — optionally scan for videos on every page that reaches DOM steady state
- **LLM agent tool integration** — exposes 7 `media.*` tools: `detectVideos`, `download`, `getInfo`, `process`, `extractAudio`, `trim`, `compress`
- **Security** — path-traversal protection, filename sanitization, and subprocess timeout enforcement built in

## Installation

The plugin is a Maven dependency. Add it to your project:

```xml
<dependency>
    <groupId>ai.platon.pulsar</groupId>
    <artifactId>browser4-media</artifactId>
    <version>4.12.0-SNAPSHOT</version>
</dependency>
```

The plugin is enabled by default (`media.enabled` defaults to `true`). To disable it:

```properties
media.enabled=false
```

### Prerequisites for FFmpeg features

The FFmpeg tools (`process`, `extractAudio`, `trim`, `compress`, `getInfo`) require `ffmpeg` and `ffprobe` on the system PATH. Override the paths if needed:

```properties
media.ffmpeg.path=/usr/local/bin/ffmpeg
media.ffprobe.path=/usr/local/bin/ffprobe
```

## Configuration

All properties use the `media.*` prefix:

| Property | Default | Description |
|---|---|---|
| `media.enabled` | `true` | Enable/disable the plugin |
| `media.download.dir` | `downloads/media` | Base directory for downloaded media files |
| `media.download.max-size` | `524288000` (500 MB) | Maximum bytes per download |
| `media.download.timeout.seconds` | `300` | Per-download HTTP timeout |
| `media.download.concurrent` | `3` | Maximum concurrent downloads |
| `media.ffmpeg.path` | `ffmpeg` | Path to the FFmpeg binary |
| `media.ffprobe.path` | `ffprobe` | Path to the ffprobe binary |
| `media.ffmpeg.timeout.seconds` | `600` | Per-FFmpeg-process timeout |
| `media.auto-detect.enabled` | `false` | Auto-detect videos on DOM steady |

## Agent Tools

When `browser4-agentic` is present, the following tools are exposed to LLM agents:

### `media.detectVideos`

Scan the current page for all video sources.

No parameters required.

Returns: `List<VideoSource>` — each containing `tagName`, `srcUrl`, `resolvedUrl`, `type` (MIME), `width`, `height`, `hasControls`, `posterUrl`, `isHls`, `isDash`, `isIframe`.

### `media.download`

Download a media file by URL.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `url` | `String` | Yes | — | Media URL to download |
| `outputPath` | `String` | No | `downloads/media` | Output directory |
| `filename` | `String` | No | auto-generated | Custom filename |

Returns: `DownloadResult` — `url`, `filePath`, `bytesDownloaded`, `contentType`, `durationMs`, `success`, `error`.

### `media.getInfo`

Probe a media file for metadata using ffprobe.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `filePath` | `String` | Yes | — | Path to the media file |

Returns: `ProbeResult` — `format`, `duration`, `width`, `height`, `codec`, `bitrate`, `streams`.

### `media.process`

Run an arbitrary FFmpeg command.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `inputPath` | `String` | Yes | — | Path to the input media file |
| `ffmpegArgs` | `List<String>` | Yes | — | FFmpeg arguments (e.g. `["-vf", "scale=1280:720", "-c:a", "copy"]`) |
| `outputPath` | `String` | Yes | — | Path for the output file |

Returns: `ProcessResult` — `success`, `exitCode`, `stdout`, `stderr`, `durationMs`.

### `media.extractAudio`

Extract the audio track from a video file.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `inputPath` | `String` | Yes | — | Path to the input video file |
| `outputPath` | `String` | Yes | — | Path for the output audio file |
| `format` | `String` | No | `libmp3lame` | Audio codec (e.g. `aac`, `libmp3lame`, `libvorbis`) |

Returns: `ProcessResult`.

### `media.trim`

Trim a segment from a media file without re-encoding.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `inputPath` | `String` | Yes | — | Path to the input media file |
| `startTime` | `String` | Yes | — | Start position (e.g. `00:01:30` or `90`) |
| `duration` | `String` | Yes | — | Duration of the segment (e.g. `00:00:30` or `30`) |
| `outputPath` | `String` | Yes | — | Path for the output file |

Returns: `ProcessResult`.

### `media.compress`

Compress/re-encode a video file with H.264.

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| `inputPath` | `String` | Yes | — | Path to the input video file |
| `outputPath` | `String` | Yes | — | Path for the compressed output |
| `crf` | `Int` | No | `23` | Constant Rate Factor (0–51, lower = better quality) |

Returns: `ProcessResult`.

## Architecture

```
browser4-media/
├── pom.xml
└── src/
    ├── main/kotlin/ai/platon/pulsar/media/
    │   ├── config/
    │   │   ├── MediaConfig.kt              — Configuration properties (media.*)
    │   │   └── MediaAutoConfiguration.kt   — Spring Boot auto-configuration & bean wiring
    │   ├── integration/
    │   │   └── MediaBrowseEventHandler.kt  — Hooks into onDocumentSteady for auto-detection
    │   ├── service/
    │   │   ├── VideoDetector.kt            — DOM scanning via CDP JavaScript evaluation
    │   │   ├── MediaDownloader.kt          — HTTP download via OkHttp
    │   │   ├── FFmpegProcessManager.kt     — FFmpeg/ffprobe subprocess management
    │   │   └── MediaUtils.kt               — URL validation, MIME helpers, filename handling
    │   └── tools/
    │       └── MediaToolExecutor.kt        — LLM agent tool definitions (media.*)
    ├── main/resources/META-INF/
    │   ├── browser4-plugin.json            — Plugin manifest and metadata
    │   └── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/kotlin/ai/platon/pulsar/media/
        ├── config/MediaConfigTest.kt
        ├── integration/MediaBrowseEventHandlerTest.kt
        ├── service/
        │   ├── FFmpegProcessManagerTest.kt
        │   ├── MediaUtilsTest.kt
        │   └── VideoDetectorTest.kt
        └── tools/MediaToolExecutorTest.kt
```

### How it works

1. **Detection** runs entirely in the browser's DOM via CDP `Runtime.evaluate` — a JavaScript probe queries `<video>`, `<source>`, `<a>` links, `<iframe>` embeds from known platforms, HLS/DASH player globals, and `<link rel="preload">` hints. It never touches the network layer, so browser `BlockRule` settings do not affect detection.
2. **Download** uses a dedicated OkHttp client (independent of the browser), with browser-like `User-Agent` headers, redirect following, streaming-to-disk, content-length validation, and coroutine-semaphore concurrency control.
3. **FFmpeg processing** runs `ffmpeg` and `ffprobe` as managed subprocesses via `ProcessBuilder`, with concurrent stdout/stderr collection, configurable timeouts, and graceful error handling — failures return `ProcessResult(success = false)` rather than throwing, so LLM agents can inspect and retry.
4. **Auto mode** hooks into `BrowseEventHandlers.onDocumentSteady` — when `media.auto-detect.enabled` is `true`, detection fires on every page load. Exceptions are swallowed gracefully to avoid disrupting the session.

## Dependencies

- `browser4-protocol` — WebDriver, WebPage, and event type definitions
- `browser4-agentic` — `ToolExecutor` / `ToolMount` / `ToolSpec` agent infrastructure
- OkHttp — HTTP client for media downloads
- Jackson Kotlin — JSON serialization (ffprobe output parsing)
- Commons IO — file I/O utilities
- Kotlinx Coroutines — async concurrency control
- Spring Boot Autoconfigure — plugin lifecycle and bean wiring

## License

Apache License 2.0
