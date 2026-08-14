# Browser4 SEO Plugin

Extract and audit SEO metadata from any page — inside the real browser.

## What it does

This plugin exposes two LLM agent tools in the `seo` domain:

| Tool | Description |
|------|-------------|
| `seo.extractMeta()` | Extract all SEO metadata: title, meta description, canonical, Open Graph, Twitter Card, robots, headings, word count, JSON-LD structured data. |
| `seo.checkIssues()` | Audit the page for common SEO problems (missing title/description, no canonical, thin content, missing alt text, multiple h1, noindex). Returns a categorized issue list. |

Both tools run JavaScript **inside the real browser page** via `driver.evaluateValue()`, so they see the fully rendered DOM — including meta tags injected client-side by SPAs.

## Why a plugin (not a script)

- Runs in-process with the Browser4 host — no external process to manage.
- Auto-discovered on startup via Spring Boot auto-configuration.
- Tools are registered in the LLM agent tool registry automatically (via `ToolMount`).
- Minimal footprint: no browse event handlers, no lifecycle hooks — just two tools.

## Build

```bash
cd browser4-plugins/browser4-seo
mvn package -DskipTests
# → target/browser4-seo-4.13.4-SNAPSHOT.jar
```

Or use the build script:

```powershell
.\build.ps1                    # build + verify
.\build.ps1 -DeployDir ../..   # build + copy to a plugins directory
```

## Deploy

Copy the JAR to Browser4's `plugins/` directory and restart, or install via REST:

```bash
curl -X POST http://localhost:8182/api/plugins/install \
  -F "file=@target/browser4-seo-4.13.4-SNAPSHOT.jar"
```

After restart, check logs for:

```
PluginManager: Found X PluginMount bean(s)
  ✓ Configured tool executors
```

## Configuration

All properties use the `seo.` prefix and are optional:

| Property | Default | Description |
|----------|---------|-------------|
| `seo.enabled` | `true` | Enable/disable the plugin |
| `seo.title.min-length` | `10` | Min recommended title length |
| `seo.title.max-length` | `60` | Max recommended title length |
| `seo.description.min-length` | `50` | Min recommended description length |
| `seo.description.max-length` | `160` | Max recommended description length |
| `seo.content.min-word-count` | `300` | Min word count before "thin content" warning |

## Architecture

```
browser4-seo/
├── pom.xml
├── build.ps1
├── README.md
└── src/main/
    ├── kotlin/ai/platon/pulsar/seo/
    │   ├── config/
    │   │   ├── SeoAutoConfiguration.kt   # @AutoConfiguration + ToolMount
    │   │   └── SeoConfig.kt              # Config data class
    │   ├── service/
    │   │   └── SeoService.kt             # Loads + executes the JS scripts
    │   └── tools/
    │       └── SeoToolExecutor.kt        # AbstractToolExecutor (seo domain)
    └── resources/
        ├── seo/
        │   ├── extract-meta.js           # Browser-side: extract metadata
        │   └── check-issues.js           # Browser-side: audit issues
        └── META-INF/
            ├── browser4-plugin.json      # Plugin manifest
            └── spring/
                └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

## Design note

This is a **minimal** Browser4 plugin — it demonstrates the smallest useful
plugin that exposes LLM agent tools. It intentionally does **not** implement
`BrowseEventMount` (no automatic action on every page) or `Browser4Plugin`
(no lifecycle hooks). Add those only if you need automatic SEO checking on
every page load.
