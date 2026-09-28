---
name: browser4-plugin
title: "Browser4 Plugin Development"
description: "Guides the creation of Browser4 plugins from requirements gathering through deployment. Use when the user wants to create, build, scaffold, or extend Browser4 with a new plugin — whether for CAPTCHA solving, media processing, content conversion, page category detection, custom RPA actions, or new LLM agent tools."
tier: procedure
---

# Browser4 Plugin Development

Step-by-step guide to creating a Browser4 plugin — from clarifying requirements and scaffolding via the Maven archetype through implementing `PluginMount` interfaces, services, event handlers and tool executors, wiring Spring auto-configuration, writing tests, building, and deploying. It includes decision tables to help identify which extension points to use and concrete code patterns drawn from the five built-in first-party plugins.

## Quick Start

Scaffold, build, deploy, verify — the 80% path:

```bash
# 1. Scaffold from the PDK archetype (every -D flag: see [Flags](#flags))
mvn archetype:generate \
  -DarchetypeGroupId=ai.platon.pulsar \
  -DarchetypeArtifactId=browser4-plugin-archetype \
  -DarchetypeVersion=4.12.0 \
  -DgroupId=com.example \
  -DartifactId=browser4-myfeature \
  -Dversion=1.0.0-SNAPSHOT \
  -DpluginName="My Feature" \
  -DpluginDescription="A Browser4 plugin that performs custom page processing"

# 2. Build the thin JAR
cd browser4-myfeature
mvn package -DskipTests

# 3. Verify the JAR structure (optional but recommended)
# bin/verify-plugin.ps1 target/browser4-myfeature-1.0.0-SNAPSHOT.jar

# 4. Deploy — copy to Browser4's plugins/ directory and restart
cp target/browser4-myfeature-1.0.0-SNAPSHOT.jar /path/to/browser4/plugins/

# 5. Or install over the REST API without touching the filesystem
curl -X POST http://localhost:18182/api/plugins/install \
  -F "file=@target/browser4-myfeature-1.0.0-SNAPSHOT.jar"
curl http://localhost:18182/api/plugins
```

The archetype writes a new Maven project directory at the path given by `artifactId`, and the build produces a **thin** JAR (`target/<artifactId>-<version>.jar`) deployable to Browser4's `plugins/` directory. The plugin is auto-discovered on restart and logs:

```text
PluginManager: Found X PluginMount bean(s)
PluginManager:   ✓ Configured browse event handlers
PluginManager: Found X Browser4Plugin bean(s)
  - browser4-myfeature v4.12.0-rc.1
```

If a line is missing, the auto-configuration was not discovered — see [Errors & Recovery](#errors--recovery).

## Dependencies

- JDK 17+
- Maven 3.9+
- Access to the Browser4 PDK parent POM (`ai.platon.pulsar:browser4-pdk`) — published to Maven Central and extending `pulsar-parent`, so third-party developers need the archetype plus a Maven installation, not a clone of the full Browser4 repo
- For building the archetype locally: `mvn -pl browser4-pdk install` from this repository

## When to Use

Create a plugin when you need to:

- **Hook into the browse lifecycle** — execute custom logic on page-navigation events (before navigation, after DOM is steady, before tab close). Examples: CAPTCHA detection and solving, ad blocking, custom RPA workflows.
- **Hook into the load lifecycle** — intercept or transform page content during loading. Examples: URL normalization and stripping tracking parameters, content extraction, HTML post-processing.
- **Hook into the crawl lifecycle** — accept or reject URLs during crawling. Examples: domain allowlists, duplicate URL filtering, paywall detection and skip.
- **Register custom tools for LLM agents** — expose new capabilities as callable functions. Examples: image download, PPTX generation, database queries, API integrations.
- **Add page category sniffers** — teach Browser4 to recognize new page types so it can adapt its behavior. Examples: CAPTCHA pages, login pages, paywalls, shopping carts.

**Do NOT create a plugin for:**

- Simple data extraction from a known site — use the browser4-cli HTML snapshot / X-SQL instead.
- One-off browser automation — the CLI or a quick script is faster.
- Modifying core Browser4 behavior — that belongs in the main source tree, not a plugin.

## How It Works

A plugin is a thin JAR on the host's classpath. Its entry point is a Spring `@AutoConfiguration` class that implements one or more `PluginMount` sub-interfaces (`BrowseEventMount`, `LoadEventMount`, `CrawlEventMount`, `ToolMount`, `PageSnifferMount`); the JAR registers that class in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, and `META-INF/browser4-plugin.json` carries the manifest the registry discovers it by. At startup `PluginManager` collects those beans and appends the handlers, tool executors and page sniffers they return to the browse, load and crawl event chains, so plugin code runs inside the same lifecycle as the built-in features — no core change, no fork, and a restart (or a REST install) is the entire deployment step.

## Patterns

Eight numbered recipes take you from an empty directory to a deployed plugin; each names the decisions it resolves, the artifacts it produces, and the code to copy.

### Step 1: Clarify Requirements

Before writing any code, ask the user these questions. The answers determine which `PluginMount` interfaces and project structure to use.

| Question | Why it matters |
|----------|----------------|
| What is the plugin's core purpose? | Determines which `PluginMount` interfaces to implement and what services are needed |
| Should the plugin act automatically on every page, or only when explicitly invoked? | Automatic action → `BrowseEventMount` on `onDocumentSteady`. Explicit invocation → `ToolMount`. Both → implement both. |
| Does the plugin need to transform or extract data during page loading? | Yes → `LoadEventMount` or `CrawlEventMount`. No → skip these. |
| Should LLM agents be able to invoke the plugin's features as tools? | Yes → `ToolMount`. No → skip. |
| Does the plugin recognize a new category of page (CAPTCHA, paywall, etc.)? | Yes → `PageSnifferMount`. No → skip. |
| What external services or APIs does the plugin use? | Determines additional dependencies (OkHttp client, AWS SDK, etc.) and configuration properties. |
| Should the plugin be configurable (enabled/disabled, timeouts, endpoints)? | Yes → create a `Config` data class and `@ConditionalOnProperty` annotations. |
| Does the plugin need explicit startup/shutdown lifecycle hooks? | Yes → implement `Browser4Plugin`. No → auto-configuration only is sufficient. |

### Step 2: Choose PluginMount Interfaces

Map the plugin's capabilities to one or more mount points. The auto-configuration class implements all chosen interfaces.

| If the plugin needs to... | Implement | Primary hook |
|---------------------------|-----------|-------------|
| Execute code when a page finishes loading and the DOM is stable | `BrowseEventMount` | `onDocumentSteady` — the recommended RPA hook |
| Intercept or block resources before navigation | `BrowseEventMount` | `onWillNavigate` — call `driver.addBlockedURLs()` |
| Take screenshots or extract data before tab closes | `BrowseEventMount` | `onWillStopTab` — last chance before tab teardown |
| Normalize or modify URLs before fetching | `LoadEventMount` | `onNormalize` |
| Extract data right after HTML is parsed | `LoadEventMount` | `onHTMLDocumentParsed` |
| Filter which URLs enter the crawl pipeline | `CrawlEventMount` | `onWillLoad` — return `null` to reject |
| Expose new LLM-callable functions | `ToolMount` | `getToolExecutors()` — must return a `List<ToolExecutor>` |
| Detect a page category (e.g., "this is a CAPTCHA page") | `PageSnifferMount` | `getPageSniffers()` — must return a `List<PageCategorySniffer>` |
| Have startup/shutdown lifecycle hooks | `Browser4Plugin` | Override `onStartup()` / `onShutdown()` |

**Common combinations from built-in plugins:**

| Plugin | Mount points used | Pattern |
|--------|------------------|---------|
| CAPTCHA | `BrowseEventMount` + `ToolMount` + `PageSnifferMount` | Auto-detect + manual solve + page classification |
| Images | `BrowseEventMount` + `ToolMount` + `Browser4Plugin` | Auto-detect + agent tool + explicit lifecycle |
| Media | `BrowseEventMount` + `ToolMount` | Auto-detect + agent tool |
| PPTX | `BrowseEventMount` + `ToolMount` | Agent-invoked conversion |
| Markdown | `BrowseEventMount` + `ToolMount` | Agent-invoked conversion |
| PDK Test | `BrowseEventMount` + `LoadEventMount` + `CrawlEventMount` | All event phases (reference/canary) |

### Step 3: Scaffold via the Archetype

Run the Maven archetype command from the Quick Start. The generated project contains:

```text
browser4-<feature>/
├── pom.xml                          # Required: Maven build (parent: browser4-pdk)
├── .gitignore
├── README.md                        # Common: plugin documentation
└── src/main/
    ├── kotlin/<package>/
    │   ├── MyPlugin.kt              # Optional: Browser4Plugin lifecycle
    │   ├── config/
    │   │   ├── PluginAutoConfiguration.kt  # Required: @AutoConfiguration + mounts
    │   │   └── MyFeatureConfig.kt          # Common: config data class
    │   ├── integration/
    │   │   ├── MyBrowseEventHandler.kt     # Optional: browse event handler
    │   │   └── MyLoadEventHandler.kt       # Optional: load event handler
    │   ├── service/
    │   │   └── MyFeatureService.kt         # Common: business logic service
    │   └── tools/
    │       └── MyToolExecutor.kt           # Optional: LLM agent tool
    └── resources/
        └── META-INF/
            ├── browser4-plugin.json                # Required: plugin manifest
            └── spring/
                └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  # Required
```

**Immediately after scaffolding, do these renames:**

1. Rename the generated `PluginAutoConfiguration` class to `<Feature>AutoConfiguration` (e.g., `CaptchaAutoConfiguration`)
2. Rename `MyPlugin` to `<Feature>Plugin` (if keeping the lifecycle class)
3. Rename handler classes: `MyBrowseEventHandler` → `<Feature>BrowseEventHandler`
4. Update `browser4-plugin.json` — change the `name`, `description`, and `autoConfigurationClasses` to match
5. Update `AutoConfiguration.imports` — replace the FQN with the renamed class
6. Delete stub files you won't use

### Step 4: Implement Mount Points

#### 4a. BrowseEventMount — Custom RPA on Every Page

For plugins that execute code automatically when a page loads. The key hook is `onDocumentSteady`, which fires when the DOM is fully rendered and the page is stable.

```kotlin
@AutoConfiguration
@ConditionalOnProperty(name = ["myfeature.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class MyFeatureAutoConfiguration : BrowseEventMount {

    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        // Primary RPA hook — fires when DOM is fully rendered
        handlers.onDocumentSteady.addLast { page, driver ->
            // Access page.url, page.content, driver.*
            // Custom logic here
        }

        // Optional: block unwanted resources before navigation
        handlers.onWillNavigate.addLast { page, driver ->
            driver.addBlockedURLs(listOf("*.png", "*.jpg", "*analytics*"))
        }
    }
}
```

**Browse event hooks in execution order:** `onWillLaunchBrowser` (pre-launch setup) → `onBrowserLaunched` (first access to `WebDriver`) → `onWillFetch` (pre-fetch configuration) → `onWillNavigate` (**block resources, set headers**) → `onNavigated` (post-navigation checks) → `onWillInteract` → `onWillCheckDocumentState` (check readyState) → `onDocumentFullyLoaded` (DOM ready) → `onWillScroll` → `onDidScroll` → `onDocumentSteady` (**★ best for custom RPA**) → `onWillComputeFeature` → `onFeatureComputed` → `onDidInteract` (all interactions complete) → `onWillStopTab` (**last chance before tab close**) → `onTabStopped` → `onFetched`.

#### 4b. LoadEventMount — Content Interception During Loading

For plugins that normalize URLs, extract data during parsing, or transform content.

```kotlin
override fun configureLoadHandlers(handlers: LoadEventHandlers) {
    // Strip tracking parameters from all URLs before loading
    handlers.onNormalize.addLast { url ->
        url.replace(Regex("\\?utm_.*"), "")
    }

    // Extract data right after HTML parsing (doc is a Jsoup Document)
    handlers.onHTMLDocumentParsed.addLast { page, doc ->
        // Parse and extract structured data from doc
    }
}
```

**Load event hooks:** `onNormalize` → `onWillLoad` → `onWillFetch` → `onFetched` → `onWillParse` → `onWillParseHTMLDocument` → `onHTMLDocumentParsed` (★ best for data extraction) → `onParsed` → `onLoaded`

#### 4c. CrawlEventMount — URL Pipeline Filtering

For plugins that accept/reject URLs in the crawl pipeline.

```kotlin
override fun configureCrawlHandlers(handlers: CrawlEventHandlers) {
    handlers.onWillLoad.addLast { url ->
        if (isBlacklisted(url.url)) null else url  // null = reject
    }
}
```

**Crawl hooks:** `onWillLoad` (return `null` to reject URL) → `onLoaded` (results are available)

#### 4d. ToolMount — LLM Agent Tools

For plugins whose features should be invocable by AI agents. Extend `AbstractToolExecutor` from `browser4-agentic`.

```kotlin
// In the auto-configuration class:
class MyFeatureAutoConfiguration : ToolMount {
    override fun getToolExecutors(): List<ToolExecutor> =
        listOf(applicationContext.getBean("myFeatureToolExecutor") as ToolExecutor)
}

// In tools/MyFeatureToolExecutor.kt:
open class MyFeatureToolExecutor(
    private val service: MyFeatureService,
) : AbstractToolExecutor() {
    override val domain = "myfeature"

    init {
        toolSpec["doSomething"] = ToolSpec(
            domain = domain,
            method = "doSomething",
            arguments = listOf(
                ToolSpec.Arg("param1", "String"),
                ToolSpec.Arg("param2", "Int?", "0"),
            ),
            returnType = "MyResult",
            description = "Does something useful with the current page"
        )
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any,
    ): Any? {
        val driver = receiver as? WebDriver
        return when (functionName) {
            "doSomething" -> service.execute(driver, args)
            else -> throw IllegalArgumentException("Unknown method: $functionName")
        }
    }
}
```

#### 4e. PageSnifferMount — Page Category Detection

For plugins that recognize new page types. Implement `PageCategorySniffer` and return it.

#### 4f. Browser4Plugin — Lifecycle Hooks (Optional)

Implement only if you need explicit startup/shutdown callbacks:

```kotlin
open class MyFeaturePlugin(
    override val manifest: PluginManifest = PluginManifest(
        name = "browser4-myfeature",
        version = "4.12.0-rc.1",
        description = "My plugin description",
        dependsOn = listOf("browser4-protocol", "browser4-agentic"),
        autoConfigurationClasses = listOf(
            "ai.platon.pulsar.myfeature.config.MyFeatureAutoConfiguration"
        )
    )
) : Browser4Plugin {
    override fun onStartup() { /* initialize resources */ }
    override fun onShutdown() { /* release resources */ }
}
```

### Step 5: Implement Services and Config

Separate business logic from mount-point wiring. The auto-configuration class wires dependencies and mount points; the service class contains business logic; the config data class holds tunable settings.

```kotlin
// config/MyFeatureConfig.kt
data class MyFeatureConfig(
    val enabled: Boolean = true,
    val timeoutSeconds: Long = 30,
    val endpoint: String = "https://default.example.com",
) {
    companion object {
        private const val PREFIX = "myfeature."
        fun fromConfig(conf: Config): MyFeatureConfig = MyFeatureConfig(
            enabled = conf.getBoolean("${PREFIX}enabled", true),
            timeoutSeconds = conf.getLong("${PREFIX}timeout.seconds", 30),
            endpoint = conf.get("${PREFIX}endpoint", "https://default.example.com"),
        )
    }
}

// service/MyFeatureService.kt
open class MyFeatureService(
    private val config: MyFeatureConfig,
) {
    suspend fun process(page: WebPage, driver: WebDriver): Result {
        // Business logic — use driver.evaluate() for JavaScript,
        // OkHttpClient for HTTP calls, etc.
    }
}

// config/MyFeatureAutoConfiguration.kt — add these beans to the
// auto-configuration class from Step 4a, which carries the annotations and
// configureBrowseHandlers(). Inject ApplicationContext into its constructor
// when Step 4d's getToolExecutors() has to look up executor beans.
@Bean
@ConditionalOnMissingBean
open fun myFeatureConfig(conf: Config): MyFeatureConfig =
    MyFeatureConfig.fromConfig(conf)

@Bean
@ConditionalOnMissingBean
open fun myFeatureService(config: MyFeatureConfig): MyFeatureService =
    MyFeatureService(config)
```

### Step 6: Create Plugin Manifest and Auto-Configuration Imports

Two mandatory resource files in every plugin JAR:

**`src/main/resources/META-INF/browser4-plugin.json`:**

```json
{
  "name": "browser4-myfeature",
  "version": "4.12.0-rc.1",
  "description": "A Browser4 plugin that provides custom page processing functionality",
  "dependsOn": ["browser4-protocol", "browser4-agentic"],
  "autoConfigurationClasses": [
    "ai.platon.pulsar.myfeature.config.MyFeatureAutoConfiguration"
  ]
}
```

**`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`** — a single line, the FQN of the `@AutoConfiguration` class:

```text
ai.platon.pulsar.myfeature.config.MyFeatureAutoConfiguration
```

### Step 7: Write Tests

Place tests in `src/test/kotlin/` mirroring the source package. Use JUnit 5 + `kotlin-test-junit5` + `spring-boot-test` (all `test` scope).

**Config and event-handler test patterns** — use `runBlocking` for coroutine handlers:

```kotlin
@Test
fun `test config defaults`() {
    val config = MyFeatureConfig()
    assertTrue(config.enabled)
    assertEquals(30, config.timeoutSeconds)
}

@Test
fun `test fromConfig reads properties`() {
    val conf = Config.of(mapOf("myfeature.enabled" to "false"))
    val config = MyFeatureConfig.fromConfig(conf)
    assertFalse(config.enabled)
}

@Test
fun `test browse event handler processes page`() = runBlocking {
    val handler = MyFeatureService(mockConfig)
    val result = handler.process(webPageProxy(), mockDriver)
    assertNotNull(result)
}
```

**Service test pattern — use `java.lang.reflect.Proxy` for lightweight mocks:**

```kotlin
@Suppress("UNCHECKED_CAST")
private fun webPageProxy(): WebPage {
    return Proxy.newProxyInstance(
        WebPage::class.java.classLoader,
        arrayOf(WebPage::class.java)
    ) { _, _, _ -> null } as WebPage
}
```

### Step 8: Deploy and Verify

Build, optionally verify the JAR, and deploy exactly as in [Quick Start](#quick-start). After the JAR lands in `plugins/` and Browser4 restarts, look for the four `PluginManager` lines shown there: a missing `Found X PluginMount bean(s)` line means the auto-configuration class was not discovered, and a missing `✓ Configured browse event handlers` line means the mount interface or the `AutoConfiguration.imports` entry is wrong.

---

## Flags

Archetype `-D` flags — every flag except `groupId`, `artifactId` and `pluginName` has a default. Runtime behavior is configured through the host application's properties (e.g., `myfeature.enabled`), not through these flags — see Step 5.

| Flag | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `groupId` | String | Yes | — | Maven groupId for the new plugin project (e.g., `com.example`) |
| `artifactId` | String | Yes | — | Maven artifactId, conventionally `browser4-<feature-name>` |
| `version` | String | No | `1.0.0-SNAPSHOT` | Plugin version |
| `pluginName` | String | Yes | — | Human-readable plugin name (e.g., `"My Feature Plugin"`) |
| `pluginDescription` | String | No | `"A Browser4 plugin that provides custom functionality"` | One-line description |
| `mountPoints` | String[] | No | `["BrowseEventMount"]` | Which `PluginMount` interfaces to implement. Options: `BrowseEventMount`, `LoadEventMount`, `CrawlEventMount`, `ToolMount`, `PageSnifferMount` |
| `hasCustomTools` | Boolean | No | `false` | Whether the plugin registers LLM agent tool executors |
| `hasLifecycle` | Boolean | No | `false` | Whether the plugin implements the `Browser4Plugin` lifecycle interface |
| `features` | String[] | No | — | List of concrete capabilities to implement (e.g., `"detect media on page"`, `"download files"`, `"expose LLM tool"`) |

## Errors & Recovery

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| Plugin not loaded at startup; no "registered plugin" log | `browser4-plugin.json` missing, malformed, or JAR not in the plugins directory | Verify JAR is in the configured plugins directory; verify JSON is valid; verify `autoConfigurationClasses` FQN matches |
| `ClassNotFoundException` for Browser4 API classes | Dependency scope is `compile` instead of `provided` | Change all `browser4-*` and Spring Boot deps to `<scope>provided</scope>` |
| Mount point handlers never fire | Auto-configuration class doesn't implement the correct `PluginMount` interface, or `AutoConfiguration.imports` file is missing/wrong | Verify `AutoConfiguration.imports` contains the exact FQN; verify the auto-config class implements the mount interface |
| `BeanCreationException` at startup | A bean dependency is missing or circular | Check bean constructor args; ensure `@Lazy` on the auto-config class |
| Tool executor not available to agents | `getToolExecutors()` returns empty list, or executor not exposed as a Spring bean | Verify the list contains beans from `applicationContext`; verify executor has `@Bean` in auto-config |
| Archetype generation fails | Maven can't resolve `browser4-plugin-archetype` | Build the PDK locally first: `mvn -pl browser4-pdk install` |
| `onDocumentSteady` handler throws silently | Missing try-catch in handler body | Always wrap handler body in try-catch; log errors via a logger |
| Config properties have no effect | Config class not reading from `Config` or property prefix mismatch | Verify `fromConfig()` method reads all keys with correct prefix; verify property names in application config |
| `NoSuchMethodError` at runtime | Plugin compiled against a different Browser4 version than the host | Rebuild with matching `browser4-pdk` version; or reinstall the matching Browser4 version |
| JAR contains embedded dependencies | Fat JAR instead of thin JAR — `spring-boot-maven-plugin` with `repackage` goal | Remove or skip the `repackage` goal; ensure only `maven-jar-plugin` is active |
| `NoClassDefFoundError` for third-party libraries | Third-party dependency not bundled in the plugin JAR | Use `compile` scope for third-party deps (not `provided`) so they are included in the JAR |

---

## Reference Map

Key source files to read for patterns and examples:

| Resource | Path | What it demonstrates |
|----------|------|---------------------|
| Canonical test plugin | `browser4-pdk/browser4-pdk-test-plugin/` | All three event-phase mount points (BrowseEventMount, LoadEventMount, CrawlEventMount) — the compatibility canary |
| Plugin archetype | `browser4-pdk/browser4-plugin-archetype/src/main/resources/archetype-resources/` | Scaffolded project structure, templates for all required files |
| PluginMount interfaces | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/plugin/MountPoints.kt` | `PluginMount`, `BrowseEventMount`, `LoadEventMount`, `CrawlEventMount` interface definitions |
| Event lifecycle | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/event/PageEvents.kt` | `LoadEventHandlers`, `BrowseEventHandlers`, `CrawlEventHandlers` — all 28 hooks |
| Event handler types | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/event/EventHandlers.kt` | Chainable handler function types (`WebPageWebDriverEventHandler`, etc.) |
| Plugin manifest | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/plugin/PluginManifest.kt` | `PluginManifest` data class schema |
| ToolMount + registry | `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/ToolMount.kt` | `ToolMount` interface + `CustomToolRegistry` singleton |
| ToolExecutor base | `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/AbstractToolExecutor.kt` | `ToolExecutor` interface and `AbstractToolExecutor` base class |
| PDK parent POM | `browser4-pdk/pom.xml` | Parent POM for plugin projects (standalone — inherits from `pulsar-parent` on Maven Central) |
| **Most complete reference plugin** | `browser4-plugins/browser4-images/` | Implements BrowseEventMount + ToolMount + Browser4Plugin + Config + Service + BrowseEventHandler + ToolExecutor — all major patterns |
| CAPTCHA plugin | `browser4-plugins/browser4-captcha/` | Reference for PageSnifferMount + multi-tool executor |
| PDK test plugin source | `browser4-pdk/browser4-pdk-test-plugin/src/main/kotlin/ai/platon/pulsar/pdk/testplugin/config/TestPluginAutoConfiguration.kt` | Minimal mount-point wiring for all three event phases |

---

## Critical Warnings

> **Warning:** All `browser4-*` and Spring Boot dependencies must use `<scope>provided</scope>`. Using `compile` scope causes `ClassNotFoundException` at runtime because the host application provides these classes. The plugin JAR should only bundle your own code and true third-party libraries (use `compile` scope for those).

> **Warning:** Always annotate the auto-configuration class with `@Lazy`. Plugin beans depend on services that may not be available until the application context is fully initialized. Without `@Lazy`, startup may fail with `BeanCreationException`.

> **Warning:** The `browser4-plugin.json` manifest and the `Browser4Plugin.manifest` property must stay in sync. The JSON file is always required for JAR discovery; the `Browser4Plugin` interface is optional. If both are present, the JSON manifest is authoritative for the plugin registry.

> **Warning:** Event handlers run on the browser event loop. Never perform blocking I/O (HTTP calls, file writes) directly in a handler — use coroutines. For `ToolMount` executors, the framework handles coroutine dispatch automatically.

> **Warning:** An uncaught exception in one event handler can break the entire handler chain. Always wrap handler bodies in try-catch and log failures.

> **Tip:** Build and test incrementally. After scaffolding, immediately run `mvn package` to verify the build works before writing any custom code. Then implement one mount point at a time, rebuilding and testing after each.

> **Tip:** Read the `browser4-pdk-test-plugin` source before writing complex handlers. It demonstrates every mount point and serves as the compatibility canary.

> **Tip:** When using `@ConditionalOnProperty`, prefer `matchIfMissing = true` so the plugin is enabled by default unless explicitly disabled. This matches the convention used by all first-party plugins.

> **Tip:** The `browser4-plugins/browser4-images/` plugin is the best all-around reference — it implements `BrowseEventMount` + `ToolMount` + `Browser4Plugin` with a config data class, service layer, browse event handler, and tool executor.

---

## See Also

- [Plugin Development Guide](../../docs-dev/plugin-development.md) — standalone guide to creating, building, and deploying a plugin without cloning the repository, including the Plugin API reference
- [AGENTS.md](../../AGENTS.md) — Project architecture, build commands, code style, and testing conventions
- [PDK Test Plugin](../../browser4-pdk/browser4-pdk-test-plugin/) — Minimal reference plugin implementing all mount points
- [Built-in Plugins](../../browser4-plugins/) — Five first-party plugin implementations (captcha, images, media, pptx, markdown)
