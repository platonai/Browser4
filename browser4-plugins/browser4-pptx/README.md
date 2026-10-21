# Browser4 PPTX

Convert web pages to PowerPoint (PPTX) files.

A [Browser4](https://github.com/platonai/pulsar) plugin that extracts structured content from web pages and generates PowerPoint presentations with embedded images, tables, lists, and code blocks.

## Features

- **🎯 Title slide** — page title and URL as the opening slide
- **📑 Section slides** — content grouped by heading hierarchy (H1–H6)
- **🖼️ Embedded images** — automatic download and embedding of page images (JPEG, PNG, GIF, WebP)
- **📊 Tables & lists** — HTML tables and ordered/unordered lists preserved
- **💻 Code blocks** — `<pre><code>` blocks rendered in monospace
- **💬 Blockquotes** — styled with left border and italic text
- **🔁 Continuation slides** — long sections split across multiple slides
- **🤖 AI agent tool** — `pptx.generate()` available to LLM agents
- **⚡ Auto-generation** — optional hook to auto-generate PPTX when a page becomes steady

## Installation

The module is a Browser4 plugin. Place `browser4-pptx-<version>.jar` on the application classpath and add the auto-configuration class to the plugin descriptor:

```json
{
  "name": "browser4-pptx",
  "version": "4.12.1-SNAPSHOT",
  "sdkVersion": "${project.version}",
  "description": "Convert web pages to PowerPoint (PPTX) files",
  "dependsOn": ["browser4-protocol", "browser4-agentic"],
  "autoConfigurationClasses": ["ai.platon.pulsar.pptx.config.PptxAutoConfiguration"]
}
```

`version` is the plugin's own release version (an independent literal, bumped with the plugin). `sdkVersion` (required since 4.14) declares the Browser4 SDK the plugin was built against and is substituted by Maven resource filtering (`${project.version}`, inherited from the `browser4-pdk` parent) so it always matches the SDK it was compiled against; a plugin whose `sdkVersion` has a newer major than the host is refused, while an older same-major one loads with a warning.

No additional setup is required — Spring Boot auto-configuration registers the handler and tool executor automatically.

## Usage

### Via AI Agent Tool

```
pptx.generate()
pptx.generate(outputDir: "/path/to/output/dir")
```

`outputDir` is the **directory** where the file is written (not a file path). The
generator always names the file `<sanitized-page-title>_<timestamp>.pptx`. If a
`.pptx`-suffixed path is passed, its parent directory is used. The legacy
`outputPath` argument is still accepted as an alias.

Without `outputDir` the deck is written to `pptx.output.dir`, which defaults to the
absolute `~/.browser4/downloads/pptx` — anchored under the user's Browser4 state
directory rather than the backend process working directory, so the file is
findable and survives a runtime-bundle rebuild.

Returns a `PptxGenerationResult` with:
- `filePath` — absolute path to the generated `.pptx` file
- `slideCount` — number of slides created
- `blockCount` — number of content blocks extracted
- `imageCount` — number of images **actually embedded** on slides (not the image block count)
- `downloadedImages` — images **downloaded successfully** (the downloader's own count, which is not `imageCount + skippedImages`)
- `failedImages` — image downloads that errored (network, timeout, HTTP status)
- `skippedImages` — image blocks that could not be placed on any slide (not downloaded, unsupported by XSLF, embed failure)
- `skippedBlocks` — content blocks that could not be rendered at all
- `skippedBlockKinds` — `skippedBlocks` broken down by block kind
- `imageDownloadMs` — wall clock spent downloading images
- `imageDownloadDeadlineExceeded` — `true` when the download phase hit its deadline
- `warnings` — human readable diagnostics, present when the deck is partial
- `durationMs` — generation time in milliseconds

### Partial results instead of a hang

The image download phase is bounded twice over: `pptx.download.timeout.seconds`
per image, and `pptx.download.total-timeout.seconds` for the whole phase. When
the phase deadline expires, the downloads already in flight are cancelled, the
PPTX is written with the images that were fetched, and `warnings` explains what
is missing (including the dominant failure cause and the proxy to configure).
A page whose images cannot be reached therefore still produces a complete text
deck within the deadline.

Slide content is never silently discarded: blocks that do not fit a slide are
carried into a `"(continued N)"` slide, and any block that cannot be rendered at
all is counted in `skippedBlocks`/`skippedBlockKinds` and reported in `warnings`.
Page chrome (site navigation, sidebars, status badges, edit links) is filtered
out before it becomes slide content.

### Via Auto-Generation

Enable automatic PPTX generation when a page becomes steady:

```properties
pptx.auto-generate.enabled=true
```

The `PptxBrowseEventHandler` triggers on `onDocumentSteady`, extracts content from the fully rendered DOM, and generates a PPTX in the background.

## Architecture

```
┌─────────────────────┐
│ PptxAutoConfiguration│  ← Spring Boot auto-config, BrowseEventMount + ToolMount
└─────────┬───────────┘
          │
    ┌─────┴──────────────┐
    │                    │
    ▼                    ▼
┌────────────────┐  ┌──────────────┐
│PptxBrowseEvent │  │PptxTool      │
│Handler         │  │Executor      │
│(auto-generate) │  │(pptx.generate)
└──────┬─────────┘  └──────┬───────┘
       │                   │
       └─────────┬─────────┘
                 │
       ┌─────────▼──────────┐
       │ PageContentExtractor│  ← CDP JS probe → List<ContentBlock>
       └─────────┬──────────┘
                 │
       ┌─────────▼──────────┐
       │ PptxImageDownloader │  ← OkHttp concurrent downloads
       └─────────┬──────────┘
                 │
       ┌─────────▼──────────┐
       │ PptxGenerator       │  ← Apache POI XSLF
       └────────────────────┘
```

### Core Components

| Component | Role |
|-----------|------|
| `PageContentExtractor` | Injects a JavaScript probe via CDP that walks the DOM in document order, collecting headings, paragraphs, images, tables, lists, code blocks, and blockquotes as structured `ContentBlock` objects. Page-chrome subtrees (navigation, banners, sidebars, status badges, edit links) are rejected before they are extracted |
| `PptxImageDownloader` | Downloads referenced images via OkHttp with concurrency control (semaphore-gated) for embedding in slides. Skips SVGs and data URIs by default. Bounded by a per-image timeout and an overall phase deadline, and reports failed/skipped/aborted counts with the dominant failure reason |
| `PptxGenerator` | Builds the PPTX using Apache POI XSLF — creates a title slide, groups content into heading-based sections, and renders each content type with appropriate styling. Blocks that do not fit a slide continue on a `"(continued N)"` slide; anything that cannot be rendered is counted per block kind |
| `PptxToolExecutor` | Exposes `pptx.generate()` as an LLM agent tool, wired via `ToolMount` |
| `PptxBrowseEventHandler` | Optional hook on `onDocumentSteady` for automatic PPTX generation |
| `PptxAutoConfiguration` | Spring Boot auto-configuration bean factory |

### Content Block Types

| Type | Description |
|------|-------------|
| `title` | Page `<title>` |
| `heading` | H1–H6 elements with their level |
| `paragraph` | Top-level `<p>` elements |
| `image` | `<img>` with src, alt, dimensions |
| `table` | `<table>` with row/cell data |
| `list` | `<ul>` or `<ol>` with items |
| `code` | `<pre><code>` blocks |
| `blockquote` | `<blockquote>` elements |

## Configuration

All properties use the `pptx.` prefix.

| Property | Default | Description |
|----------|---------|-------------|
| `pptx.output.dir` | `~/.browser4/downloads/pptx` | Output directory for generated PPTX files (absolute by default, anchored under the user's Browser4 state dir instead of the backend CWD) |
| `pptx.download.max-size` | `10485760` | Max image download size in bytes (10 MB) |
| `pptx.download.timeout.seconds` | `30` | Per-image download timeout (connect + read + wall clock) |
| `pptx.download.total-timeout.seconds` | `60` | Overall deadline for the image download phase; on expiry the deck is written with the images fetched so far |
| `pptx.download.concurrent` | `3` | Max concurrent image downloads |
| `pptx.download.proxy` | _(unset)_ | HTTP proxy for image downloads (`host:port` or full URL). When unset the downloader falls back to `HTTPS_PROXY`/`HTTP_PROXY`, the JVM proxy properties (`https.proxyHost`/`https.proxyPort`, `http.proxyHost`/`http.proxyPort`, `http.nonProxyHosts`), and the OS system proxy (`ProxyEnable`/`ProxyServer`/`ProxyOverride` from WinINET on Windows, otherwise the JVM's default `ProxySelector`) |
| `pptx.download.use-system-proxy` | `true` | Honour the OS system proxy when no explicit proxy is configured |
| `pptx.auto-generate.enabled` | `false` | Auto-generate PPTX on `onDocumentSteady` |
| `pptx.title.max-length` | `120` | Max title length before truncation |
| `pptx.slide.width` | `720` | Slide width in POI points (10 in, widescreen) |
| `pptx.slide.height` | `540` | Slide height in POI points (7.5 in, widescreen) |
| `pptx.slide.max-images` | `2` | Max images per content slide (further images continue on the next slide) |
| `pptx.slide.max-content-blocks` | `6` | Max content blocks per slide before splitting (further blocks continue on the next slide) |
| `pptx.detect.skip-svg` | `true` | Skip SVG images (unsupported by XSLF) |
| `pptx.detect.skip-data-uris` | `true` | Skip data URI images |
| `pptx.detect.exclude-selectors` | _(unset)_ | Comma-separated CSS selectors whose subtrees are dropped before they become slide content, on top of the built-in page-chrome selectors (navigation, banners, sidebars, Wikipedia status badges, edit links, navboxes, …) |
| `pptx.enabled` | `true` | Enable/disable the entire plugin |

Only HTTP proxies are supported: an `InetSocketAddress` cannot express a SOCKS
proxy, so a `socks=` WinINET entry or a SOCKS default selector is ignored with a
warning instead of producing a client that silently bypasses the proxy. Loopback
hosts are always contacted directly.

## Dependencies

**Provided at runtime (not bundled):** Browser4 core (skeleton, browser, protocol, agentic), Spring Boot auto-configure, Kotlin stdlib/coroutines, OkHttp.

**Bundled in plugin JAR:** Apache POI (`poi`, `poi-ooxml`).

## License

Apache License 2.0 — see [LICENSE](../../LICENSE).
