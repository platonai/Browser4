# Browser4 Plugin Development Guide

This guide shows you how to create, build, and deploy a Browser4 plugin as a standalone project — no need to clone the Browser4 repository.

## Quick Start

### Prerequisites

- JDK 17 or later
- Maven 3.9 or later

### Create a New Plugin Project

**Option A: Maven Archetype (recommended)**

```bash
mvn archetype:generate \
  -DarchetypeGroupId=ai.platon.pulsar \
  -DarchetypeArtifactId=browser4-plugin-archetype \
  -DarchetypeVersion=4.12.0-rc.1 \
  -DgroupId=com.example \
  -DartifactId=my-browser4-plugin \
  -Dpackage=com.example.myplugin \
  -DpluginName="My Browser4 Plugin" \
  -DpluginDescription="A custom Browser4 plugin"
```

**Option B: Manual Setup**

Create a `pom.xml` with the Browser4 PDK as parent:

```xml
<parent>
    <groupId>ai.platon.pulsar</groupId>
    <artifactId>browser4-pdk</artifactId>
    <version>4.12.0-rc.1</version>
    <relativePath/>
</parent>
```

### Build

```bash
cd my-browser4-plugin
./mvnw package
```

### Install into Browser4

**REST API:**

```bash
curl -X POST http://localhost:8080/api/plugins/install \
  -F "file=@target/my-browser4-plugin-1.0.0.jar"
```

**Manual:** Copy the JAR to the `plugins/` directory of your Browser4 installation and restart.

### Verify Installation

```bash
# List installed plugins
curl http://localhost:8080/api/plugins

# Remove a plugin
curl -X DELETE http://localhost:8080/api/plugins/my-browser4-plugin
```

## Plugin Architecture

A Browser4 plugin is a thin JAR (not a fat JAR) that integrates with the host application via three mechanisms:

1. **Plugin Manifest** (`META-INF/browser4-plugin.json`) — identifies the plugin
2. **Spring Boot Auto-Configuration** (`META-INF/spring/...AutoConfiguration.imports`) — registers plugin beans
3. **Mount Points** (`PluginMount` interfaces) — hooks into browser event lifecycle

### Project Structure

```
my-browser4-plugin/
├── pom.xml
├── README.md
└── src/main/
    ├── kotlin/com/example/myplugin/
    │   ├── MyPlugin.kt                  # Plugin lifecycle (Browser4Plugin)
    │   ├── config/
    │   │   └── PluginAutoConfiguration.kt  # Spring auto-config + mount points
    │   ├── integration/
    │   │   ├── MyBrowseEventHandler.kt  # Browse-phase handlers (17 hooks)
    │   │   └── MyLoadEventHandler.kt    # Load-phase handlers (9 hooks)
    │   └── tools/
    │       └── MyToolExecutor.kt        # Custom LLM agent tools (optional)
    └── resources/META-INF/
        ├── browser4-plugin.json         # Plugin manifest (required)
        └── spring/
            └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### Plugin JAR Structure

```
my-browser4-plugin.jar
├── META-INF/
│   ├── browser4-plugin.json          (required)
│   └── spring/
│       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  (required)
└── com/example/myplugin/
    ├── MyPlugin.class
    ├── config/PluginAutoConfiguration.class
    ├── integration/MyBrowseEventHandler.class
    └── tools/MyToolExecutor.class
```

## Plugin API Reference

### Plugin Manifest (`browser4-plugin.json`)

Every plugin JAR must contain this file at `META-INF/browser4-plugin.json`:

```json
{
  "name": "my-browser4-plugin",
  "version": "1.0.0",
  "description": "A custom Browser4 plugin",
  "dependsOn": ["browser4-skeleton", "browser4-browser"],
  "autoConfigurationClasses": [
    "com.example.myplugin.config.PluginAutoConfiguration"
  ]
}
```

| Field | Required | Description |
|---|---|---|
| `name` | Yes | Unique plugin identifier |
| `version` | Yes | Plugin version |
| `description` | No | Human-readable description |
| `dependsOn` | No | List of Browser4 modules this plugin depends on |
| `autoConfigurationClasses` | No | Spring Boot auto-configuration classes |

### Browser4Plugin Interface

Optional lifecycle interface for plugins that need explicit startup/shutdown hooks:

```kotlin
interface Browser4Plugin {
    val manifest: PluginManifest
    fun onStartup() {}    // Called after Spring context refresh + mount wiring
    fun onShutdown() {}   // Called on context close
}
```

### PluginMount Interfaces

Mount points are the core extension mechanism. Create Spring beans that implement one or more of these interfaces, and `PluginManager` will automatically wire them into the appropriate integration points.

#### LoadEventMount — 9 Load-Phase Hooks

Hook into URL normalization, fetching, and HTML parsing:

```kotlin
interface LoadEventMount : PluginMount {
    fun configureLoadHandlers(handlers: LoadEventHandlers)
}
```

Event hooks (in execution order):

| Hook | Signature | Use Case |
|---|---|---|
| `onNormalize` | `(url: String) -> String` | Strip tracking params, normalize URLs |
| `onWillLoad` | `(url: String) -> String` | Last chance to modify/reject URL |
| `onWillFetch` | `(page: WebPage) -> Unit` | Pre-fetch setup |
| `onFetched` | `(page: WebPage) -> Unit` | Post-fetch processing |
| `onWillParse` | `(page: WebPage) -> Unit` | Pre-parse setup |
| `onWillParseHTMLDocument` | `(page: WebPage, doc: Document) -> Unit` | Pre-HTML-parse |
| `onHTMLDocumentParsed` | `(page: WebPage, doc: Document) -> Unit` | **Data extraction from parsed HTML** |
| `onParsed` | `(page: WebPage) -> Unit` | Parse complete |
| `onLoaded` | `(page: WebPage) -> Unit` | Page fully loaded |

#### BrowseEventMount — 17 Browse-Phase Hooks

Hook into browser automation — navigation, scrolling, interaction, RPA:

```kotlin
interface BrowseEventMount : PluginMount {
    fun configureBrowseHandlers(handlers: BrowseEventHandlers)
}
```

Event hooks (in execution order):

| Hook | Signature | Use Case |
|---|---|---|
| `onWillLaunchBrowser` | `(page: WebPage) -> Unit` | Before browser launch |
| `onBrowserLaunched` | `(page: WebPage, driver: WebDriver) -> Unit` | Browser ready (first driver access) |
| `onWillFetch` | `(page: WebPage, driver: WebDriver) -> Unit` | Browse-phase fetch |
| `onWillNavigate` | `(page: WebPage, driver: WebDriver) -> Unit` | **Block resources, set headers** |
| `onNavigated` | `(page: WebPage, driver: WebDriver) -> Unit` | Navigation complete |
| `onWillInteract` | `(page: WebPage, driver: WebDriver) -> Unit` | Interaction starting |
| `onWillCheckDocumentState` | `(page: WebPage, driver: WebDriver) -> Unit` | Check readyState |
| `onDocumentFullyLoaded` | `(page: WebPage, driver: WebDriver) -> Unit` | Document ready |
| `onWillScroll` | `(page: WebPage, driver: WebDriver) -> Unit` | Before scrolling |
| `onDidScroll` | `(page: WebPage, driver: WebDriver) -> Unit` | Scrolling complete |
| `onDocumentSteady` | `(page: WebPage, driver: WebDriver) -> Unit` | **★ BEST for custom RPA actions** |
| `onWillComputeFeature` | `(page: WebPage, driver: WebDriver) -> Unit` | Before feature computation |
| `onFeatureComputed` | `(page: WebPage, driver: WebDriver) -> Unit` | Features computed |
| `onDidInteract` | `(page: WebPage, driver: WebDriver) -> Unit` | All interactions complete |
| `onWillStopTab` | `(page: WebPage, driver: WebDriver) -> Unit` | Last chance before tab close |
| `onTabStopped` | `(page: WebPage, driver: WebDriver) -> Unit` | Tab stopped |
| `onFetched` | `(page: WebPage, driver: WebDriver) -> Unit` | Browse-phase fetch complete |

#### CrawlEventMount — 2 Crawl-Phase Hooks

Hook into the crawl lifecycle:

```kotlin
interface CrawlEventMount : PluginMount {
    fun configureCrawlHandlers(handlers: CrawlEventHandlers)
}
```

| Hook | Signature | Use Case |
|---|---|---|
| `onWillLoad` | `(url: UrlEntry) -> UrlEntry?` | **Reject URLs** (return null to skip) |
| `onLoaded` | `(url: UrlEntry) -> Unit` | Crawl results available |

#### ToolMount — Custom LLM Agent Tools

Register custom tool executors for LLM agents:

```kotlin
interface ToolMount : PluginMount {
    fun getToolExecutors(): List<ToolExecutor> = emptyList()
}
```

Requires dependency: `browser4-agentic` (provided scope).

#### PageSnifferMount — Page Category Detection

Register page category sniffers:

```kotlin
interface PageSnifferMount : PluginMount {
    fun getPageSniffers(): List<PageCategorySniffer> = emptyList()
}
```

Requires dependency: `browser4-protocol` (provided scope).

## Spring Auto-Configuration

### Registration

Create the file `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` with one line:

```
com.example.myplugin.config.PluginAutoConfiguration
```

### Auto-Configuration Class

```kotlin
@AutoConfiguration
@Lazy  // Always use @Lazy for plugin beans
open class PluginAutoConfiguration : BrowseEventMount, LoadEventMount {

    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        handlers.onDocumentSteady.addLast { page, driver ->
            // Your RPA logic here
        }
    }

    override fun configureLoadHandlers(handlers: LoadEventHandlers) {
        handlers.onHTMLDocumentParsed.addLast { page, doc ->
            // Your data extraction logic here
        }
    }

    @Bean
    open fun myService(): MyService = MyService()
}
```

### Conditional Configuration

Use Spring Boot conditionals to make your plugin configurable:

```kotlin
@AutoConfiguration
@ConditionalOnProperty(
    name = ["myplugin.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
@ConditionalOnClass(BrowseEventMount::class)
@Lazy
open class PluginAutoConfiguration : BrowseEventMount { ... }
```

## Tool Executor Development

For plugins that expose tools to LLM agents:

```kotlin
@AutoConfiguration
@Lazy
open class PluginAutoConfiguration : ToolMount {

    override fun getToolExecutors(): List<ToolExecutor> {
        return listOf(myToolExecutor())
    }

    @Bean
    open fun myToolExecutor(): MyToolExecutor = MyToolExecutor()
}
```

## Building and Testing

### Dependencies Use "provided" Scope

All Browser4, Spring Boot, and Kotlin dependencies must use `provided` scope. The host Browser4 application provides these at runtime. Including them in your plugin JAR causes ClassLoader conflicts.

```xml
<dependency>
    <groupId>ai.platon.pulsar</groupId>
    <artifactId>browser4-skeleton</artifactId>
    <scope>provided</scope>
</dependency>
```

### Thin JAR Only

Your plugin JAR must be a **thin JAR** (not a Spring Boot fat JAR). Do not configure `spring-boot-maven-plugin` with the `repackage` goal.

### Verify Your Plugin

Use the verification script to validate your plugin JAR before deployment:

```bash
# Linux/macOS
bin/verify-plugin.sh target/my-plugin-1.0.0.jar

# Windows
bin/verify-plugin.ps1 target/my-plugin-1.0.0.jar
```

Checks performed:
- `META-INF/browser4-plugin.json` exists and is valid JSON
- `AutoConfiguration.imports` exists and is non-empty
- JAR is thin (no embedded dependency JARs)
- Compiled classes are present

### Unit Testing

```xml
<dependency>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-test-junit5</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

## Deployment

### Installation

Plugins are JAR files placed in the `plugins/` directory of a Browser4 installation:

1. Copy the plugin JAR to `plugins/`
2. Restart the Browser4 application

Or use the REST API:

```bash
curl -X POST http://localhost:8080/api/plugins/install \
  -F "file=@my-plugin.jar"
```

### Management

```bash
# List installed plugins
curl http://localhost:8080/api/plugins

# Get plugin details
curl http://localhost:8080/api/plugins/my-plugin

# Remove a plugin
curl -X DELETE http://localhost:8080/api/plugins/my-plugin
```

### Activation

Installed plugins take effect **after restart**. There is no hot-reload mechanism.

### Logging

Check the application logs for plugin lifecycle messages:

```
PluginManager: Found X PluginMount bean(s)
PluginManager:   ✓ Configured browse event handlers
PluginManager: Found X Browser4Plugin bean(s)
  - my-plugin v1.0.0
```

## Troubleshooting

### Plugin not appearing in `/api/plugins`

- Ensure the JAR is in the `plugins/` directory
- Verify the JAR contains `META-INF/browser4-plugin.json` with valid JSON
- Check file permissions

### AutoConfiguration not discovered

- Verify `META-INF/spring/...AutoConfiguration.imports` exists in the JAR
- Ensure the class name in the imports file matches the fully qualified class name
- Check that the auto-configuration class is annotated with `@AutoConfiguration`

### ClassLoader issues

- Ensure all Browser4/Spring/Kotlin dependencies use `provided` scope
- Verify no dependency JARs are embedded in the plugin JAR
- Check for version mismatches between plugin compile-time and host runtime versions

### NoSuchMethodError or ClassNotFoundException

- The plugin was compiled against a different version of Browser4 than the host
- Rebuild with the matching `browser4-pdk` version
- Or reinstall the matching Browser4 version

## Best Practices

1. **Use `onDocumentSteady`** for custom RPA actions — it fires when the page is fully loaded and stable
2. **Keep plugins focused** — one plugin, one responsibility
3. **Use `@ConditionalOnProperty`** to make plugin features toggleable
4. **Prefer `@Lazy`** for plugin beans to avoid startup issues
5. **Test with the verification script** before deploying
6. **Version your plugin** to match the Browser4 version it targets
7. **Use `provided` scope** for all framework dependencies
8. **Do not bundle Spring Boot** — plugins are extensions, not standalone applications
