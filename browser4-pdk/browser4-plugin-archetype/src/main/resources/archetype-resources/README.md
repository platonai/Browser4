# ${pluginName}

${pluginDescription}

## Prerequisites

- JDK 17 or later
- Maven 3.9 or later (or use the included Maven wrapper)

## Build

```bash
./mvnw package
```

This produces a thin JAR at `target/${artifactId}-${version}.jar`.

## Install into Browser4

### Option 1: REST API

```bash
curl -X POST http://localhost:8080/api/plugins/install \
  -F "file=@target/${artifactId}-${version}.jar"
```

### Option 2: Manual

Copy the JAR into the `plugins/` directory of your Browser4 installation:

```bash
cp target/${artifactId}-${version}.jar /path/to/browser4/plugins/
```

After installation, restart the Browser4 application to activate the plugin.

## Verify Installation

```bash
# List installed plugins
curl http://localhost:8080/api/plugins

# Check logs for plugin activation
# Look for: "PluginManager: Found X PluginMount bean(s)"
```

## Project Structure

```
src/main/kotlin/${pluginPackage}/
├── MyPlugin.kt                  # Plugin lifecycle (Browser4Plugin)
├── config/
│   └── PluginAutoConfiguration.kt  # Spring auto-config + mount points
├── integration/
│   ├── MyBrowseEventHandler.kt  # Browse-phase event handlers (17 hooks)
│   └── MyLoadEventHandler.kt    # Load-phase event handlers (9 hooks)
└── tools/
    └── MyToolExecutor.kt        # Custom LLM agent tools (optional)

src/main/resources/META-INF/
├── browser4-plugin.json                                 # Plugin manifest (required)
└── spring/
    └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  # Spring registration (required)
```

## Key Concepts

### Mount Points

- **LoadEventMount** (9 hooks): URL normalization, fetch, parse
- **BrowseEventMount** (17 hooks): navigation, scroll, interaction, RPA
- **CrawlEventMount** (2 hooks): URL filtering, result handling
- **ToolMount**: register custom LLM agent tools

### Best Event for RPA

`onDocumentSteady` fires when the page is fully loaded and stable — ideal for
custom RPA actions like clicking buttons, filling forms, or extracting data.

## Dependencies

All Browser4 and Spring Boot dependencies use `provided` scope because the
host Browser4 application provides them at runtime.

## Learn More

- [Browser4 Plugin Development Guide](https://browser4.io/docs/plugin-development)
- [Browser4 GitHub](https://github.com/platonai/browser4)
