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
  "version": "4.12.0-rc.1",
  "description": "Convert web pages to PowerPoint (PPTX) files",
  "dependsOn": ["browser4-protocol", "browser4-agentic"],
  "autoConfigurationClasses": ["ai.platon.pulsar.pptx.config.PptxAutoConfiguration"]
}
```

No additional setup is required — Spring Boot auto-configuration registers the handler and tool executor automatically.

## Usage

### Via AI Agent Tool

```
pptx.generate()
pptx.generate(outputPath: "/path/to/output")
```

Returns a `PptxGenerationResult` with:
- `filePath` — absolute path to the generated `.pptx` file
- `slideCount` — number of slides created
- `blockCount` — number of content blocks extracted
- `imageCount` — number of images embedded
- `durationMs` — generation time in milliseconds

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
| `PageContentExtractor` | Injects a JavaScript probe via CDP that walks the DOM in document order, collecting headings, paragraphs, images, tables, lists, code blocks, and blockquotes as structured `ContentBlock` objects |
| `PptxImageDownloader` | Downloads referenced images via OkHttp with concurrency control (semaphore-gated) for embedding in slides. Skips SVGs and data URIs by default |
| `PptxGenerator` | Builds the PPTX using Apache POI XSLF — creates a title slide, groups content into heading-based sections, and renders each content type with appropriate styling |
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
| `pptx.output.dir` | `downloads/pptx` | Output directory for generated PPTX files |
| `pptx.download.max-size` | `10485760` | Max image download size in bytes (10 MB) |
| `pptx.download.timeout.seconds` | `30` | Per-image download timeout |
| `pptx.download.concurrent` | `3` | Max concurrent image downloads |
| `pptx.auto-generate.enabled` | `false` | Auto-generate PPTX on `onDocumentSteady` |
| `pptx.title.max-length` | `120` | Max title length before truncation |
| `pptx.slide.width` | `720` | Slide width in POI points (10 in, widescreen) |
| `pptx.slide.height` | `540` | Slide height in POI points (7.5 in, widescreen) |
| `pptx.slide.max-images` | `2` | Max images per content slide |
| `pptx.slide.max-content-blocks` | `6` | Max content blocks per slide before splitting |
| `pptx.detect.skip-svg` | `true` | Skip SVG images (unsupported by XSLF) |
| `pptx.detect.skip-data-uris` | `true` | Skip data URI images |
| `pptx.enabled` | `true` | Enable/disable the entire plugin |

## Dependencies

**Provided at runtime (not bundled):** Browser4 core (skeleton, browser, protocol, agentic), Spring Boot auto-configure, Kotlin stdlib/reflect/coroutines, OkHttp.

**Bundled in plugin JAR:** Apache POI (`poi`, `poi-ooxml`).

## License

Apache License 2.0 — see [LICENSE](../../LICENSE).
